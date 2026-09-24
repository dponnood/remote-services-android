package xin.dponnood.remoteservice

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xin.dponnood.remoteservice.feature.services.DockerDashboardSnapshot
import xin.dponnood.remoteservice.feature.services.SystemInfoSnapshot

/** Minimal same-origin GET response used for the server-rendered Dockerman overview. */
internal data class DockerOverviewHttpResponse(
    val statusCode: Int,
    val body: String?,
    val location: String? = null,
)

internal fun interface DockerOverviewTransport {
    /** Implementations must issue a GET, disable redirects, and bound the response body. */
    suspend fun get(url: String, cookieHeader: String?): DockerOverviewHttpResponse?
}

internal enum class DockerOverviewFailureKind {
    NO_SESSION,
    SESSION_EXPIRED,
    FORBIDDEN,
    NOT_FOUND,
    NOT_DOCKER_PAGE,
    DAEMON_UNAVAILABLE,
    INVALID_RESPONSE,
    ENDPOINT_UNAVAILABLE,
}

internal sealed interface DockerOverviewReadResult {
    data class Success(
        val snapshot: SystemInfoSnapshot,
        val sessionRefreshed: Boolean = false,
        val cached: Boolean = false,
    ) : DockerOverviewReadResult

    data class Failure(
        val kind: DockerOverviewFailureKind,
        val httpStatus: Int? = null,
    ) : DockerOverviewReadResult
}

/**
 * Reads Docker overview data through the same authenticated, server-rendered
 * LuCI page used by iStoreOS 24.10. That Dockerman generation accesses its
 * local Docker socket on the router and does not grant native mobile clients
 * the Docker ubus ACL. Only fixed overview GET routes are used here.
 */
internal class DockerSystemInfoReader(
    private val transport: DockerOverviewTransport = HttpDockerOverviewTransport(),
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val snapshots = ConcurrentHashMap<String, CachedOverview>()
    private val refreshSuppressedUntil = ConcurrentHashMap<String, Long>()

    suspend fun read(
        origin: String,
        session: UbusSessionAuth,
        cacheScope: String = origin,
        reauthenticate: (suspend () -> UbusSessionAuth?)? = null,
    ): DockerOverviewReadResult {
        val key = "$cacheScope|$origin"
        val now = clockMillis()
        snapshots[key]?.takeIf { now - it.savedAtMillis < CACHE_TTL_MILLIS }?.let {
            return DockerOverviewReadResult.Success(it.snapshot, cached = true)
        }

        if (session.cookieHeader.isNullOrBlank()) {
            return DockerOverviewReadResult.Failure(DockerOverviewFailureKind.NO_SESSION)
        }
        val first = readOnce(origin, session.cookieHeader)
        if (first !is DockerOverviewReadResult.Failure ||
            first.kind != DockerOverviewFailureKind.SESSION_EXPIRED ||
            reauthenticate == null || now < (refreshSuppressedUntil[key] ?: Long.MIN_VALUE)
        ) {
            return first.cacheSuccess(key, now)
        }

        val fresh = try {
            reauthenticate()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (fresh?.cookieHeader.isNullOrBlank()) {
            refreshSuppressedUntil[key] = clockMillis() + AUTH_REFRESH_COOLDOWN_MILLIS
            return first
        }
        val retried = readOnce(origin, fresh!!.cookieHeader!!)
        if (retried is DockerOverviewReadResult.Success) {
            refreshSuppressedUntil.remove(key)
            return retried.copy(sessionRefreshed = true).cacheSuccess(key, clockMillis())
        }
        if (retried is DockerOverviewReadResult.Failure &&
            retried.kind == DockerOverviewFailureKind.SESSION_EXPIRED
        ) {
            // A successful credential exchange followed by another login page
            // is not an ACL problem. Back off automatic logins for this origin.
            refreshSuppressedUntil[key] = clockMillis() + AUTH_REFRESH_COOLDOWN_MILLIS
        }
        return retried
    }

    private suspend fun readOnce(origin: String, cookieHeader: String): DockerOverviewReadResult {
        if (!isSafeOrigin(origin)) return DockerOverviewReadResult.Failure(DockerOverviewFailureKind.INVALID_RESPONSE)
        var sawGenericPage = false
        var sawUnsupportedDockerPage = false
        for (path in OVERVIEW_PATHS) {
            val response = try {
                transport.get(origin + path, cookieHeader)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return DockerOverviewReadResult.Failure(DockerOverviewFailureKind.ENDPOINT_UNAVAILABLE)

            when {
                response.statusCode == HttpURLConnection.HTTP_NOT_FOUND -> continue
                response.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED ->
                    return DockerOverviewReadResult.Failure(
                        DockerOverviewFailureKind.SESSION_EXPIRED,
                        response.statusCode,
                    )
                response.statusCode == HttpURLConnection.HTTP_FORBIDDEN ->
                    return DockerOverviewReadResult.Failure(
                        DockerOverviewFailureKind.FORBIDDEN,
                        response.statusCode,
                    )
                response.statusCode in 300..399 -> {
                    val kind = if (isLoginRedirect(response.location)) {
                        DockerOverviewFailureKind.SESSION_EXPIRED
                    } else {
                        DockerOverviewFailureKind.INVALID_RESPONSE
                    }
                    return DockerOverviewReadResult.Failure(kind, response.statusCode)
                }
                response.statusCode !in 200..299 ->
                    return DockerOverviewReadResult.Failure(
                        DockerOverviewFailureKind.ENDPOINT_UNAVAILABLE,
                        response.statusCode,
                    )
            }

            val html = response.body.orEmpty()
            when (val parsed = DockerOverviewHtmlParser.parse(html)) {
                DockerOverviewParseResult.LoginRequired ->
                    return DockerOverviewReadResult.Failure(
                        DockerOverviewFailureKind.SESSION_EXPIRED,
                        response.statusCode,
                    )
                DockerOverviewParseResult.DaemonUnavailable ->
                    return DockerOverviewReadResult.Failure(
                        DockerOverviewFailureKind.DAEMON_UNAVAILABLE,
                        response.statusCode,
                    )
                DockerOverviewParseResult.NotDockerPage -> sawGenericPage = true
                DockerOverviewParseResult.UnsupportedOverview -> sawUnsupportedDockerPage = true
                is DockerOverviewParseResult.Overview ->
                    return DockerOverviewReadResult.Success(SystemInfoSnapshot(docker = parsed.snapshot))
            }
        }
        return when {
            sawUnsupportedDockerPage -> DockerOverviewReadResult.Failure(DockerOverviewFailureKind.NOT_DOCKER_PAGE)
            sawGenericPage -> DockerOverviewReadResult.Failure(DockerOverviewFailureKind.NOT_DOCKER_PAGE)
            else -> DockerOverviewReadResult.Failure(DockerOverviewFailureKind.NOT_FOUND)
        }
    }

    private fun DockerOverviewReadResult.cacheSuccess(key: String, now: Long): DockerOverviewReadResult {
        if (this is DockerOverviewReadResult.Success) snapshots[key] = CachedOverview(now, snapshot)
        return this
    }

    private data class CachedOverview(val savedAtMillis: Long, val snapshot: SystemInfoSnapshot)

    internal companion object {
        const val CACHE_TTL_MILLIS = 10_000L
        const val AUTH_REFRESH_COOLDOWN_MILLIS = 60_000L
        val OVERVIEW_PATHS = listOf(
            // iStoreOS 24.10 / lisaac's server-rendered LuCI package.
            "/cgi-bin/luci/admin/docker/overview",
            // Newer upstream LuCI Dockerman route; supported only if it renders
            // the expected overview fields into the HTML response.
            "/cgi-bin/luci/admin/services/dockerman/overview",
        )

        fun isSafeOrigin(value: String): Boolean = runCatching {
            val uri = URI(value.trim())
            uri.isAbsolute && !uri.isOpaque &&
                uri.scheme?.lowercase(Locale.US) in setOf("http", "https") &&
                !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                uri.path.isNullOrEmpty() && uri.rawQuery == null && uri.fragment == null
        }.getOrDefault(false)

        fun overviewPaths(): Set<String> = OVERVIEW_PATHS.toSet()

        private fun isLoginRedirect(location: String?): Boolean = location.orEmpty().let {
            it.contains("login", ignoreCase = true) || it.contains("sysauth", ignoreCase = true)
        }
    }
}

/** Parser deliberately recognizes only the legacy overview's fixed read-only output. */
internal object DockerOverviewHtmlParser {
    fun parse(html: String): DockerOverviewParseResult {
        if (html.isBlank()) return DockerOverviewParseResult.NotDockerPage
        val safeHtml = SCRIPT_STYLE.replace(html, " ")
        if (LOGIN_MARKERS.any { it.containsMatchIn(safeHtml) }) return DockerOverviewParseResult.LoginRequired
        if (DAEMON_ERROR_MARKERS.any { it.containsMatchIn(safeHtml) }) return DockerOverviewParseResult.DaemonUnavailable

        val tableRows = ROW.findAll(safeHtml).mapNotNull { rowMatch ->
            val cells = CELL.findAll(rowMatch.groupValues[1]).map { htmlText(it.groupValues[1]) }.toList()
            if (cells.size >= 2) cells.first() to cells.last() else null
        }.toList()
        val field = { aliases: Set<String> ->
            tableRows.firstNotNullOfOrNull { (label, value) ->
                val normalized = normalizeLabel(label)
                if (normalized in aliases.map(::normalizeLabel).toSet()) value.takeIf(String::isNotBlank) else null
            }?.takeUnless(::isPlaceholder)
        }

        val containerCounts = linkedCount(safeHtml, "admin/docker/containers", "admin/services/dockerman/containers")
            ?.let(::parsePair)
        val imageCounts = linkedCount(safeHtml, "admin/docker/images", "admin/services/dockerman/images")
            ?.let(::parsePair)
        val networkCount = linkedCount(safeHtml, "admin/docker/networks", "admin/services/dockerman/networks")
            ?.let(::parseSingle)
        val volumeCount = linkedCount(safeHtml, "admin/docker/volumes", "admin/services/dockerman/volumes")
            ?.let(::parseSingle)

        val serverVersion = field(DOCKER_VERSION_LABELS)
        val apiVersion = field(API_VERSION_LABELS)
        val cpuCount = field(CPU_LABELS)?.toIntOrNull()?.takeIf { it >= 0 }
        val memoryTotalBytes = field(MEMORY_LABELS)?.let(::parseByteCount)
        val rootDirectory = field(ROOT_DIRECTORY_LABELS)?.let(::parseRootDirectory)
        val indexServerAddress = field(INDEX_SERVER_LABELS)
        val registryMirrors = field(REGISTRY_MIRRORS_LABELS)

        val hasOverviewMarkers = safeHtml.contains("pure-g status", ignoreCase = true) &&
            hasDockerOverviewLink(safeHtml, "admin/docker/containers", "admin/services/dockerman/containers") &&
            hasDockerOverviewLink(safeHtml, "admin/docker/images", "admin/services/dockerman/images")
        val hasKnownOverviewRows = serverVersion != null || apiVersion != null || cpuCount != null ||
            memoryTotalBytes != null || rootDirectory != null
        if (!hasOverviewMarkers && !hasKnownOverviewRows) return DockerOverviewParseResult.NotDockerPage
        if (!hasOverviewMarkers && hasKnownOverviewRows) return DockerOverviewParseResult.UnsupportedOverview

        val snapshot = DockerDashboardSnapshot(
            serverVersion = serverVersion,
            apiVersion = apiVersion,
            cpuCount = cpuCount,
            memoryTotalBytes = memoryTotalBytes,
            dockerRootDirectory = rootDirectory?.first,
            dockerRootAvailable = rootDirectory?.second,
            containersTotal = containerCounts?.second,
            containersRunning = containerCounts?.first,
            imagesTotal = imageCounts?.second,
            imagesUsed = imageCounts?.first,
            networksTotal = networkCount,
            volumesTotal = volumeCount,
            indexServerAddress = indexServerAddress,
            registryMirrors = registryMirrors,
        )
        return if (listOfNotNull(
                snapshot.serverVersion, snapshot.apiVersion, snapshot.cpuCount,
                snapshot.memoryTotalBytes, snapshot.dockerRootDirectory,
                snapshot.containersTotal, snapshot.imagesTotal, snapshot.networksTotal, snapshot.volumesTotal,
            ).isEmpty()
        ) DockerOverviewParseResult.UnsupportedOverview else DockerOverviewParseResult.Overview(snapshot)
    }

    private fun hasDockerOverviewLink(html: String, vararg fragments: String): Boolean = ANCHOR.findAll(html).any { match ->
        val href = HREF.find(match.groupValues[1])?.groupValues?.getOrNull(1).orEmpty()
        fragments.any { href.contains(it, ignoreCase = true) }
    }

    private fun linkedCount(html: String, vararg fragments: String): String? = ANCHOR.findAll(html)
        .firstOrNull { match ->
            val href = HREF.find(match.groupValues[1])?.groupValues?.getOrNull(1).orEmpty()
            fragments.any { href.contains(it, ignoreCase = true) }
        }
        ?.groupValues?.getOrNull(2)?.let(::htmlText)

    private fun parsePair(value: String): Pair<Int, Int>? {
        val match = PAIR_COUNT.find(value) ?: return null
        val first = match.groupValues[1].toIntOrNull() ?: return null
        val second = match.groupValues[2].toIntOrNull() ?: return null
        return first to second
    }

    private fun parseSingle(value: String): Int? = SINGLE_COUNT.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun parseByteCount(value: String): Long? {
        val match = BYTE_VALUE.find(value.trim()) ?: return null
        val quantity = match.groupValues[1].replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0.0 } ?: return null
        val unit = match.groupValues[2].lowercase(Locale.US)
        val exponent = when (unit.firstOrNull()) {
            'k' -> 1
            'm' -> 2
            'g' -> 3
            't' -> 4
            else -> 0
        }
        val bytes = quantity * Math.pow(1024.0, exponent.toDouble())
        return bytes.takeIf { it.isFinite() && it <= Long.MAX_VALUE.toDouble() }?.toLong()
    }

    private fun parseRootDirectory(value: String): Pair<String, String?> {
        val match = AVAILABLE_VALUE.find(value.trim()) ?: return value.trim() to null
        return value.substring(0, match.range.first).trim() to match.groupValues[1].trim()
    }

    private fun normalizeLabel(value: String): String = htmlText(value)
        .lowercase(Locale.US)
        .filterNot(Char::isWhitespace)
        .filterNot { it in "：:·" }

    private fun isPlaceholder(value: String): Boolean = value == "-" || value.equals("NaN", true)

    private fun htmlText(value: String): String = ENTITY.replace(
        TAG.replace(value.replace("<br", " <br"), " "),
    ) { match ->
        when (val entity = match.groupValues[1].lowercase(Locale.US)) {
            "nbsp", "#160", "#xa0" -> " "
            "amp" -> "&"
            "quot" -> "\""
            "apos", "#39" -> "'"
            "lt" -> "<"
            "gt" -> ">"
            else -> decodeNumericEntity(entity) ?: match.value
        }
    }.replace(Regex("\\s+"), " ").trim()

    private fun decodeNumericEntity(entity: String): String? = runCatching {
        val codePoint = if (entity.startsWith("#x")) entity.substring(2).toInt(16)
        else if (entity.startsWith('#')) entity.substring(1).toInt()
        else return null
        String(Character.toChars(codePoint))
    }.getOrNull()

    private val SCRIPT_STYLE = Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>")
    private val ROW = Regex("(?is)<tr\\b[^>]*>(.*?)</tr\\s*>")
    private val CELL = Regex("(?is)<t[dh]\\b[^>]*>(.*?)</t[dh]\\s*>")
    private val ANCHOR = Regex("(?is)<a\\b([^>]*)>(.*?)</a\\s*>")
    private val HREF = Regex("(?is)\\bhref\\s*=\\s*['\"]([^'\"]+)['\"]")
    private val TAG = Regex("(?is)<[^>]*>")
    private val ENTITY = Regex("&([A-Za-z]+|#[0-9]+|#x[0-9a-fA-F]+);")
    private val PAIR_COUNT = Regex("(\\d+)\\s*/\\s*(\\d+)")
    private val SINGLE_COUNT = Regex("(?<![\\d.])(\\d+)(?![\\d.])")
    private val BYTE_VALUE = Regex("(?i)^\\s*([0-9]+(?:[.,][0-9]+)?)\\s*(B|KB|MB|GB|TB|KiB|MiB|GiB|TiB)?\\b")
    private val AVAILABLE_VALUE = Regex("(?i)\\s*\\(([^)]*?)\\s+(?:available|可用)\\s*\\)$")
    private val LOGIN_MARKERS = listOf(
        Regex("(?is)<form[^>]+(?:sysauth|login)"),
        Regex("(?is)name\\s*=\\s*['\"]luci_password['\"]"),
        Regex("(?is)<title>[^<]*(?:login|sign in|登录)[^<]*</title>"),
    )
    private val DAEMON_ERROR_MARKERS = listOf(
        Regex("(?i)can not connect to docker daemon"),
        Regex("(?i)cannot connect to docker daemon"),
        Regex("无法连接[^<]{0,40}docker"),
    )
    private val DOCKER_VERSION_LABELS = setOf("dockerversion", "docker版本", "docker引擎版本")
    private val API_VERSION_LABELS = setOf("apiversion", "api版本", "接口版本")
    private val CPU_LABELS = setOf("cpus", "cpu数量", "cpu核心数", "处理器数量")
    private val MEMORY_LABELS = setOf("totalmemory", "总内存", "内存总量")
    private val ROOT_DIRECTORY_LABELS = setOf("dockerrootdir", "docker根目录", "docker数据目录")
    private val INDEX_SERVER_LABELS = setOf("indexserveraddress", "索引服务器地址", "镜像索引地址")
    private val REGISTRY_MIRRORS_LABELS = setOf("registrymirrors", "镜像加速器", "镜像加速地址")
}

internal sealed interface DockerOverviewParseResult {
    data class Overview(val snapshot: DockerDashboardSnapshot) : DockerOverviewParseResult
    data object LoginRequired : DockerOverviewParseResult
    data object DaemonUnavailable : DockerOverviewParseResult
    data object UnsupportedOverview : DockerOverviewParseResult
    data object NotDockerPage : DockerOverviewParseResult
}

/** Production GET transport with same-origin cookies, no redirects and bounded output. */
private class HttpDockerOverviewTransport(
    private val connectTimeoutMs: Int = 4_000,
    private val readTimeoutMs: Int = 5_000,
) : DockerOverviewTransport {
    override suspend fun get(url: String, cookieHeader: String?): DockerOverviewHttpResponse? =
        withContext(Dispatchers.IO) {
            val uri = runCatching { URI(url) }.getOrNull() ?: return@withContext null
            if (!DockerSystemInfoReader.isSafeOrigin("${uri.scheme}://${uri.rawAuthority}") ||
                uri.rawUserInfo != null || uri.rawQuery != null || uri.fragment != null ||
                uri.path !in DockerSystemInfoReader.overviewPaths()
            ) return@withContext null
            val connection = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull()
                ?: return@withContext null
            try {
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                connection.setRequestProperty("Cache-Control", "no-cache")
                cookieHeader?.takeIf(String::isNotBlank)?.let { connection.setRequestProperty("Cookie", it) }
                val status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val body = stream?.use { it.readBoundedUtf8(MAX_HTML_BYTES) }
                DockerOverviewHttpResponse(status, body, connection.getHeaderField("Location"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } finally {
                connection.disconnect()
            }
        }

    private companion object {
        const val MAX_HTML_BYTES = 1024 * 1024
    }
}
