package xin.dponnood.remoteservice

import android.os.SystemClock
import android.webkit.CookieManager
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xin.dponnood.remoteservice.core.logging.LogLevel
import xin.dponnood.remoteservice.core.logging.LogRepository
import xin.dponnood.remoteservice.core.model.ServiceConfig
import xin.dponnood.remoteservice.core.model.ServiceType
import xin.dponnood.remoteservice.core.network.RouteKind
import xin.dponnood.remoteservice.core.network.RouteResolution
import xin.dponnood.remoteservice.core.network.RouteResolutionResult
import xin.dponnood.remoteservice.core.network.RouteResolver
import xin.dponnood.remoteservice.core.network.SsidPermissionState
import xin.dponnood.remoteservice.core.network.toRouteConfig
import xin.dponnood.remoteservice.core.security.CredentialStore
import xin.dponnood.remoteservice.core.security.ServiceCredentials

internal fun sessionCookieLookupUrls(origin: String): List<String> = listOf(
    "$origin/cgi-bin/luci/",
    "$origin/cgi-bin/luci",
    origin,
)

internal fun selectSessionCookie(headers: List<String?>): String? =
    headers.filterNotNull().firstOrNull { IStoreSessionManager.sessionIdFromCookie(it) != null }
        ?: headers.firstOrNull { !it.isNullOrBlank() }

internal suspend fun readLuciCookie(cookieManager: CookieManager, origin: String): String? =
    withContext(Dispatchers.Main.immediate) {
        selectSessionCookie(
            sessionCookieLookupUrls(origin).map { url ->
                runCatching { cookieManager.getCookie(url) }.getOrNull()
            },
        )
    }

internal fun InputStream.readBoundedUtf8(maxBytes: Int): String {
    require(maxBytes >= 0) { "maxBytes must not be negative" }
    val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
    val buffer = ByteArray(8 * 1024)
    var remaining = maxBytes
    while (remaining > 0) {
        val count = read(buffer, 0, minOf(buffer.size, remaining))
        if (count < 0) break
        if (count == 0) {
            val next = read()
            if (next < 0) break
            output.write(next)
            remaining--
        } else {
            output.write(buffer, 0, count)
            remaining -= count
        }
    }
    return String(output.toByteArray(), StandardCharsets.UTF_8)
}

/** A short-lived LuCI session shared by the native dashboard and WebView. */
data class IStoreSession(
    val origin: String,
    val routeKind: RouteKind,
    val cookieHeader: String,
    val sessionId: String,
)

sealed interface IStoreSessionResult {
    data class Authenticated(val session: IStoreSession) : IStoreSessionResult

    data class MissingCredentials(val message: String) : IStoreSessionResult

    data class Failed(
        val message: String,
        val statusCode: Int? = null,
    ) : IStoreSessionResult
}

/** Narrow session boundary used by native iStore system-info reads. */
internal interface IStoreSystemInfoSessionAccess {
    suspend fun ensure(service: ServiceConfig, resolution: RouteResolution): IStoreSessionResult

    suspend fun forceReauthenticate(
        service: ServiceConfig,
        resolution: RouteResolution,
    ): IStoreSessionResult

    suspend fun readCookie(origin: String): String?
}

/**
 * Owns the application-wide iStore/LuCI login boundary.
 *
 * The dashboard and the visible WebView must not maintain separate logins.
 * This manager first reuses the WebView CookieManager, then performs one
 * native form login with the Keystore credential for the selected origin.
 * Native cookies are written back to CookieManager so a user can open the
 * router page and continue manual operations without signing in again.
 */
class IStoreSessionManager(
    private val credentialStore: CredentialStore,
    private val cookieManager: CookieManager = CookieManager.getInstance(),
    /** Service ids used by the Settings > iStore total-login editor. */
    private val globalCredentialServiceIds: () -> Set<String> = { emptySet() },
    private val logRepository: LogRepository? = null,
) : IStoreSystemInfoSessionAccess {
    private val loginLock = Mutex()
    private val sessions = ConcurrentHashMap<String, IStoreSession>()
    private val retryAfter = ConcurrentHashMap<String, Long>()
    private val loginClient = LuciNativeLoginClient()

    suspend fun warmUp(
        service: ServiceConfig,
        routeResolver: RouteResolver,
    ): IStoreSessionResult {
        if (
            service.serviceType != ServiceType.ISTORE &&
            service.serviceType != ServiceType.LUCI &&
            service.serviceType != ServiceType.OPENCLASH
        ) {
            return IStoreSessionResult.MissingCredentials("当前服务不是 LuCI/iStore/OpenClash 服务")
        }
        return when (val result = routeResolver.resolve(service.toRouteConfig())) {
            is RouteResolutionResult.Success -> ensure(service, result.value)
            is RouteResolutionResult.Failure -> {
                logRepository?.append(
                    LogLevel.WARN,
                    "ISTORE_AUTO_LOGIN_ROUTE_UNAVAILABLE",
                    "iStore 自动登录线路不可达",
                    context = mapOf(
                        "service_id" to service.id,
                        "service_type" to service.serviceType.name,
                        "error_code" to result.code.name,
                    ),
                )
                IStoreSessionResult.Failed("线路不可达：${result.code.name}")
            }
        }
    }

    override suspend fun ensure(
        service: ServiceConfig,
        resolution: RouteResolution,
    ): IStoreSessionResult = loginLock.withLock {
        val origin = originOf(resolution.endpoint.url)
            ?: return@withLock IStoreSessionResult.Failed("服务地址无效")
        val cacheKey = "${service.id}|$origin"
        val cookie = readCookie(origin)
        val cookieSession = sessionIdFromCookie(cookie)
        if (cookieSession != null) {
            val session = IStoreSession(
                origin = origin,
                routeKind = resolution.endpoint.kind,
                cookieHeader = cookie.orEmpty(),
                sessionId = cookieSession,
            )
            sessions[cacheKey] = session
            retryAfter.remove(cacheKey)
            return@withLock IStoreSessionResult.Authenticated(session)
        }
        sessions[cacheKey]?.let { cached ->
            val session = cached.copy(routeKind = resolution.endpoint.kind)
            return@withLock IStoreSessionResult.Authenticated(session)
        }

        val now = SystemClock.elapsedRealtime()
        if ((retryAfter[cacheKey] ?: 0L) > now) {
            return@withLock IStoreSessionResult.Failed("自动登录暂未成功，请检查 iStore 用户名、密码和线路")
        }

        val globalIds = runCatching { globalCredentialServiceIds() }.getOrDefault(emptySet())
        val credentials = loadCredentials(service, origin, globalIds)
            ?: run {
                logRepository?.append(
                    LogLevel.WARN,
                    "ISTORE_AUTO_LOGIN_REQUIRED",
                    "iStore 未找到已保存的登录凭据",
                    context = mapOf(
                        "service_id" to service.id,
                        "route" to resolution.endpoint.kind.name,
                        "credential_scope_count" to globalIds.size.toString(),
                    ),
                )
                return@withLock IStoreSessionResult.MissingCredentials(
                    "请在设置中的 iStore 总登录或当前服务中保存登录凭据",
                )
            }

        if (!isCredentialTransportAllowed(service, resolution, origin)) {
            logRepository?.append(
                LogLevel.WARN,
                "ISTORE_AUTO_LOGIN_INSECURE_TRANSPORT",
                "已阻止在不受信任的 HTTP 线路提交 iStore 凭据",
                context = mapOf(
                    "service_id" to service.id,
                    "route" to resolution.endpoint.kind.name,
                ),
            )
            return@withLock IStoreSessionResult.Failed(
                "当前线路为 HTTP；请改用 HTTPS，或先在内网网页中手动登录",
            )
        }

        logRepository?.append(
            LogLevel.INFO,
            "ISTORE_AUTO_LOGIN_START",
            "开始建立 iStore 应用级登录会话",
            context = mapOf(
                "service_id" to service.id,
                "route" to resolution.endpoint.kind.name,
            ),
        )
        val login = loginClient.login(origin, credentials)
        if (login !is LuciNativeLoginResult.Success) {
            val failure = login as LuciNativeLoginResult.Failure
            retryAfter[cacheKey] = now + RETRY_COOLDOWN_MILLIS
            logRepository?.append(
                LogLevel.WARN,
                "ISTORE_AUTO_LOGIN_FAILED",
                failure.message,
                context = buildMap {
                    put("service_id", service.id)
                    put("route", resolution.endpoint.kind.name)
                    failure.statusCode?.let { put("status_code", it.toString()) }
                },
            )
            return@withLock IStoreSessionResult.Failed(failure.message, failure.statusCode)
        }

        writeCookies(origin, login.setCookies)
        val refreshedCookie = readCookie(origin).orEmpty().ifBlank { login.cookieHeader }
        val sessionId = sessionIdFromCookie(refreshedCookie) ?: login.sessionId
        if (sessionId.isNullOrBlank()) {
            retryAfter[cacheKey] = now + RETRY_COOLDOWN_MILLIS
            return@withLock IStoreSessionResult.Failed("登录响应未建立 LuCI 会话")
        }
        val session = IStoreSession(
            origin = origin,
            routeKind = resolution.endpoint.kind,
            cookieHeader = refreshedCookie,
            sessionId = sessionId,
        )
        sessions[cacheKey] = session
        retryAfter.remove(cacheKey)
        logRepository?.append(
            LogLevel.INFO,
            "ISTORE_AUTO_LOGIN_SUCCESS",
            "iStore 应用级登录会话已建立",
            context = mapOf(
                "service_id" to service.id,
                "route" to resolution.endpoint.kind.name,
            ),
        )
        IStoreSessionResult.Authenticated(session)
    }

    fun invalidate(service: ServiceConfig) {
        sessions.keys.removeIf { it.startsWith("${service.id}|") }
        retryAfter.keys.removeIf { it.startsWith("${service.id}|") }
    }

    /** Clear an expired CookieManager session before one controlled relogin. */
    override suspend fun forceReauthenticate(
        service: ServiceConfig,
        resolution: RouteResolution,
    ): IStoreSessionResult {
        val origin = originOf(resolution.endpoint.url)
        invalidate(service)
        if (origin != null) {
            withContext(Dispatchers.Main.immediate) {
                SESSION_COOKIE_PATHS.forEach { path ->
                    val cookieUrl = if (path == "/") "$origin/" else "$origin$path"
                    SESSION_COOKIE_NAMES.forEach { name ->
                        runCatching {
                            cookieManager.setCookie(cookieUrl, "$name=; Max-Age=0; Path=$path")
                        }
                    }
                }
                runCatching { cookieManager.flush() }
            }
        }
        return ensure(service, resolution)
    }

    private fun loadCredentials(
        service: ServiceConfig,
        origin: String,
        globalServiceIds: Set<String>,
    ): ServiceCredentials? {
        val routeKeys = listOf(
            origin,
            service.lanUrl?.let(::originOf),
            service.wanUrl?.let(::originOf),
            DEFAULT_ROUTE_KEY,
        ).filterNotNull().distinct()
        // A service-specific credential remains first.  The Settings > iStore
        // total-login editor historically stored under the selected iStore
        // service id, so OpenClash/LuCI services must also search those ids.
        val serviceIds = credentialServiceIdOrder(service.id, globalServiceIds)
        return serviceIds.asSequence()
            .flatMap { serviceId ->
                routeKeys.asSequence().mapNotNull { routeKey ->
                    runCatching { credentialStore.get(serviceId, routeKey) }.getOrNull()
                }
            }
            .firstOrNull { it.username.isNotBlank() && it.password.isNotBlank() }
    }

    override suspend fun readCookie(origin: String): String? = readLuciCookie(cookieManager, origin)

    private suspend fun writeCookies(origin: String, cookies: List<String>) {
        if (cookies.isEmpty()) return
        withContext(Dispatchers.Main.immediate) {
            cookies.forEach { cookie -> runCatching { cookieManager.setCookie(origin, cookie) } }
            runCatching { cookieManager.flush() }
        }
    }

    private fun isCredentialTransportAllowed(
        service: ServiceConfig,
        resolution: RouteResolution,
        origin: String,
    ): Boolean {
        if (origin.startsWith("https://", ignoreCase = true)) return true
        // A user-selected LAN route may be HTTP on older iStoreOS installs.
        // Permit it only when Android actually confirmed the configured SSID;
        // never submit credentials over an unknown/public cleartext route.
        return resolution.endpoint.kind == RouteKind.INTERNAL &&
            resolution.ssidPermission == SsidPermissionState.AVAILABLE &&
            resolution.ssid != null &&
            service.trustedSsids.any { it.equals(resolution.ssid, ignoreCase = true) }
    }

    companion object {
        private const val DEFAULT_ROUTE_KEY = "default"
        private const val RETRY_COOLDOWN_MILLIS = 15_000L
        private val SESSION_ID_PATTERN = Regex("^[0-9a-fA-F]{32}$")
        private val SESSION_COOKIE_NAMES = listOf("sysauth", "sysauth_http", "sysauth_https")
        private val SESSION_COOKIE_PATHS = listOf(
            "/",
            "/cgi-bin/luci",
            "/cgi-bin/luci/",
            "/cgi-bin/luci/admin",
            "/cgi-bin/luci/admin/",
            "/cgi-bin/luci/admin/istore",
            "/cgi-bin/luci/admin/istore/",
        )

        internal fun originOf(url: String): String? = runCatching {
            val parsed = URI(url.trim())
            val scheme = parsed.scheme?.lowercase(Locale.US)
            val authority = parsed.rawAuthority?.takeIf { it.isNotBlank() }
            if (scheme !in setOf("http", "https") || authority == null || parsed.rawUserInfo != null) {
                null
            } else {
                val host = parsed.host?.lowercase(Locale.US)?.removeSuffix(".") ?: return@runCatching null
                val effectivePort = if (parsed.port == -1) {
                    if (scheme == "https") 443 else 80
                } else {
                    parsed.port
                }
                val displayHost = if (host.contains(':')) "[$host]" else host
                val port = if ((scheme == "https" && effectivePort == 443) ||
                    (scheme == "http" && effectivePort == 80)
                ) "" else ":$effectivePort"
                "$scheme://$displayHost$port"
            }
        }.getOrNull()

        internal fun sessionIdFromCookie(cookie: String?): String? = cookie
            ?.split(';')
            ?.asSequence()
            ?.mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val name = part.substring(0, separator).trim().lowercase(Locale.US)
                val value = part.substring(separator + 1).trim().trim('"')
                if (name !in setOf("sysauth", "sysauth_http", "sysauth_https")) return@mapNotNull null
                value.takeIf { it.matches(SESSION_ID_PATTERN) }
            }
            ?.firstOrNull()

        /** Current-service credentials win, then Settings > iStore credentials are tried. */
        internal fun credentialServiceIdOrder(
            serviceId: String,
            globalServiceIds: Set<String>,
        ): List<String> = buildList {
            if (serviceId.isNotBlank()) add(serviceId)
            // Settings > iStore total login uses a stable namespace so it
            // survives service renames/replacement and catalogue timing.
            add(GLOBAL_CREDENTIAL_SERVICE_ID)
            globalServiceIds
                .filter(String::isNotBlank)
                .forEach { if (it !in this) add(it) }
        }

        /** Stable Keystore namespace for Settings > iStore total login. */
        internal const val GLOBAL_CREDENTIAL_SERVICE_ID = "__global_istore__"
    }
}

private sealed interface LuciNativeLoginResult {
    data class Success(
        val setCookies: List<String>,
        val cookieHeader: String,
        val sessionId: String,
    ) : LuciNativeLoginResult

    data class Failure(
        val message: String,
        val statusCode: Int? = null,
    ) : LuciNativeLoginResult
}

/** Minimal LuCI form login client. It accepts hidden challenge fields when a firmware adds them. */
private class LuciNativeLoginClient {
    suspend fun login(origin: String, credentials: ServiceCredentials): LuciNativeLoginResult =
        withContext(Dispatchers.IO) {
            val loginUrl = "$origin/cgi-bin/luci/"
            val jar = CookieJar()
            val first = request("GET", loginUrl, null, null)
                ?: return@withContext LuciNativeLoginResult.Failure("无法读取 iStore 登录页面")
            jar.addAll(first.setCookies)
            val form = LuciLoginForm.parse(first.body)
                ?: return@withContext LuciNativeLoginResult.Failure(
                    "未找到 iStore 登录表单",
                    first.statusCode,
                )
            val action = resolveAction(origin, form.action)
                ?: return@withContext LuciNativeLoginResult.Failure("iStore 登录表单地址无效")
            val fields = LinkedHashMap(form.hiddenFields)
            fields[form.usernameName] = credentials.username
            fields[form.passwordName] = credentials.password
            val post = request(
                method = "POST",
                url = action,
                cookieHeader = jar.header(),
                body = encodeForm(fields),
                referer = loginUrl,
            ) ?: return@withContext LuciNativeLoginResult.Failure("提交 iStore 登录请求失败")
            jar.addAll(post.setCookies)

            var response = post
            repeat(MAX_REDIRECTS) {
                val location = response.location ?: return@repeat
                if (response.statusCode !in 300..399) return@repeat
                val nextUrl = resolveAction(origin, location) ?: return@repeat
                response = request("GET", nextUrl, jar.header(), null, loginUrl) ?: return@repeat
                jar.addAll(response.setCookies)
            }
            val sessionId = IStoreSessionManager.sessionIdFromCookie(jar.header())
            if (sessionId == null) {
                val message = if (LuciLoginForm.parse(response.body) != null) {
                    "iStore 用户名或密码错误"
                } else {
                    "iStore 登录未建立会话（HTTP ${response.statusCode}）"
                }
                return@withContext LuciNativeLoginResult.Failure(message, response.statusCode)
            }
            LuciNativeLoginResult.Success(jar.setCookies, jar.header(), sessionId)
        }

    private fun request(
        method: String,
        url: String,
        cookieHeader: String?,
        body: ByteArray?,
        referer: String? = null,
    ): HttpResponse? {
        val connection = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull() ?: return null
        return try {
            connection.connectTimeout = 6_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            connection.setRequestProperty("User-Agent", "RemoteServices/iStoreSession")
            cookieHeader?.takeIf(String::isNotBlank)?.let { connection.setRequestProperty("Cookie", it) }
            referer?.let { connection.setRequestProperty("Referer", it) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val responseBody = stream?.use { it.readBoundedUtf8(MAX_BODY_BYTES) }.orEmpty()
            HttpResponse(
                statusCode = status,
                body = responseBody,
                location = connection.getHeaderField("Location"),
                setCookies = connection.headerFields.entries
                    .filter { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
                    .flatMap { it.value.orEmpty() },
            )
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun resolveAction(origin: String, action: String?): String? = runCatching {
        val base = URI("$origin/cgi-bin/luci/")
        val resolved = base.resolve(action?.trim().orEmpty().ifBlank { "." })
        if (IStoreSessionManager.originOf(resolved.toString()) == origin) resolved.toString() else null
    }.getOrNull()

    private fun encodeForm(fields: Map<String, String>): ByteArray = fields.entries.joinToString("&") { (key, value) ->
        "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
    }.toByteArray(StandardCharsets.UTF_8)

    private data class HttpResponse(
        val statusCode: Int,
        val body: String,
        val location: String?,
        val setCookies: List<String>,
    )

    private class CookieJar {
        private val values = LinkedHashMap<String, String>()
        val setCookies = mutableListOf<String>()

        fun addAll(cookies: List<String>) {
            cookies.forEach { cookie ->
                setCookies += cookie
                val pair = cookie.substringBefore(';').trim()
                val separator = pair.indexOf('=')
                if (separator <= 0) return@forEach
                val name = pair.substring(0, separator).trim()
                val value = pair.substring(separator + 1).trim()
                if (value.isBlank() || value.equals("deleted", ignoreCase = true)) {
                    values.remove(name)
                } else {
                    values[name] = value
                }
            }
        }

        fun header(): String = values.entries.joinToString("; ") { (name, value) -> "$name=$value" }
    }

    private companion object {
        const val MAX_BODY_BYTES = 512 * 1024
        const val MAX_REDIRECTS = 3
    }
}

private data class LuciLoginForm(
    val action: String?,
    val usernameName: String,
    val passwordName: String,
    val hiddenFields: Map<String, String>,
) {
    companion object {
        private val formPattern = Regex("(?is)<form\\b([^>]*)>(.*?)</form>")
        private val inputPattern = Regex("(?is)<input\\b([^>]*)>")
        private val attributePattern = Regex("(?is)([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")

        fun parse(html: String): LuciLoginForm? {
            for (formMatch in formPattern.findAll(html)) {
                val formAttributes = attributes(formMatch.groupValues[1])
                val inputMatches = inputPattern.findAll(formMatch.groupValues[2]).toList()
                val parsedInputs = inputMatches.map { attributes(it.groupValues[1]) }
                val password = parsedInputs.firstOrNull { input ->
                    input["name"]?.let { name ->
                        name.equals("luci_password", true) || name.equals("password", true)
                    } == true || input["type"].equals("password", true)
                } ?: continue
                val username = parsedInputs.firstOrNull { input ->
                    input["name"]?.let { name ->
                        name.equals("luci_username", true) || name.equals("username", true)
                    } == true
                } ?: mapOf("name" to "luci_username")
                val hidden = parsedInputs.asSequence()
                    .filter { it["type"].equals("hidden", true) }
                    .mapNotNull { input ->
                        val name = input["name"]?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                        name to input["value"].orEmpty()
                    }
                    .toMap()
                return LuciLoginForm(
                    action = formAttributes["action"],
                    usernameName = username["name"].orEmpty().ifBlank { "luci_username" },
                    passwordName = password["name"].orEmpty().ifBlank { "luci_password" },
                    hiddenFields = hidden,
                )
            }
            return null
        }

        private fun attributes(raw: String): Map<String, String> = attributePattern.findAll(raw).associate { match ->
            val value = listOf(match.groupValues[2], match.groupValues[3], match.groupValues[4])
                .firstOrNull(String::isNotEmpty)
                .orEmpty()
            match.groupValues[1].lowercase(Locale.US) to decodeHtml(value)
        }

        private fun decodeHtml(value: String): String = value
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
            .replace("&#x2f;", "/", ignoreCase = true)
            .replace("&#47;", "/")
    }
}
