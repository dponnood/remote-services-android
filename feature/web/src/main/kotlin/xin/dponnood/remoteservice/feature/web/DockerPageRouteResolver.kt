package xin.dponnood.remoteservice.feature.web

import android.webkit.CookieManager
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of a safe GET against one known Dockerman page candidate. */
enum class DockerPageProbeStatus {
    DOCKER_PAGE,
    LOGIN_REQUIRED,
    FORBIDDEN,
    NOT_FOUND,
    NON_DOCKER_PAGE,
    TRANSIENT_FAILURE,
}

data class DockerPageProbeResult(val status: DockerPageProbeStatus)

fun interface DockerPageProbe {
    /** Must issue GET only, never follow redirects, and never mutate router state. */
    suspend fun get(url: String, cookieHeader: String?): DockerPageProbeResult
}

fun interface LuCiCookieProvider {
    /** Query a path-specific cookie view (LuCI auth cookies may not use Path=/). */
    suspend fun cookieFor(url: String): String?
}

/**
 * Finds the overview route used by the two supported LuCI Dockerman generations.
 * A login redirect, permission response, timeout, or generic LuCI shell is not
 * considered proof that the candidate is Docker; it remains an explicit,
 * unverified fallback only when no candidate is positively identified.
 */
class DockerPageRouteResolver(
    private val probe: DockerPageProbe = HttpDockerPageProbe(),
    private val cookieProvider: LuCiCookieProvider = WebViewLuCiCookieProvider,
) {
    suspend fun resolve(origin: String): DockerPageRouteResolution {
        if (canonicalOrigin(origin) == null) return DockerPageRouteResolution.NotFound(emptyList())
        val attempts = mutableListOf<DockerPageRouteAttempt>()
        for (path in CANDIDATE_PATHS) {
            val candidateUrl = origin + path
            val cookie = runCatching { cookieProvider.cookieFor(candidateUrl) }.getOrNull()
            val result = runCatching { probe.get(candidateUrl, cookie) }
                .getOrElse { DockerPageProbeResult(DockerPageProbeStatus.TRANSIENT_FAILURE) }
            val attempt = DockerPageRouteAttempt(path, result.status)
            attempts += attempt
            if (result.status == DockerPageProbeStatus.DOCKER_PAGE) {
                return DockerPageRouteResolution.Confirmed(path, attempts)
            }
            // A 404 proves absence. A generic LuCI shell is also not enough to
            // select this candidate (it can silently land on iStore home), so
            // try the other known route. Auth, ACL and transport errors remain
            // explicit unknowns and are preserved for a manual WebView retry.
            if (result.status !in setOf(
                    DockerPageProbeStatus.NOT_FOUND,
                    DockerPageProbeStatus.NON_DOCKER_PAGE,
                )
            ) {
                return DockerPageRouteResolution.UnverifiedFallback(path, result.status, attempts)
            }
        }
        return DockerPageRouteResolution.NotFound(attempts)
    }

    companion object {
        /** Newer OpenWrt LuCI route first, followed by legacy lisaac packaging. */
        val CANDIDATE_PATHS = listOf(
            "/cgi-bin/luci/admin/services/dockerman/overview",
            "/cgi-bin/luci/admin/docker/overview",
        )

        private fun canonicalOrigin(value: String): String? {
            val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase(Locale.US)
            if (scheme !in setOf("http", "https") || uri.rawUserInfo != null || uri.host.isNullOrBlank()) {
                return null
            }
            val port = uri.port
            if (port !in -1..65535 || port == 0) return null
            return ServiceWebCoordinator.webOrigin(value)
        }
    }
}

sealed interface DockerPageRouteResolution {
    val attempts: List<DockerPageRouteAttempt>

    data class Confirmed(
        val path: String,
        override val attempts: List<DockerPageRouteAttempt>,
    ) : DockerPageRouteResolution

    data class UnverifiedFallback(
        val path: String,
        val reason: DockerPageProbeStatus,
        override val attempts: List<DockerPageRouteAttempt>,
    ) : DockerPageRouteResolution

    data class NotFound(override val attempts: List<DockerPageRouteAttempt>) : DockerPageRouteResolution
}

data class DockerPageRouteAttempt(
    val path: String,
    val status: DockerPageProbeStatus,
)

/** CookieManager bridge to the authenticated session already used by LuCI WebView. */
private object WebViewLuCiCookieProvider : LuCiCookieProvider {
    override suspend fun cookieFor(url: String): String? = withContext(Dispatchers.Main.immediate) {
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
    }
}

/** Production route probe: same-origin GET, redirects disabled, bounded response body. */
private class HttpDockerPageProbe(
    private val connectTimeoutMs: Int = 4_000,
    private val readTimeoutMs: Int = 4_000,
) : DockerPageProbe {
    override suspend fun get(url: String, cookieHeader: String?): DockerPageProbeResult =
        withContext(Dispatchers.IO) {
            val candidate = runCatching { URI(url) }.getOrNull()
                ?: return@withContext DockerPageProbeResult(DockerPageProbeStatus.TRANSIENT_FAILURE)
            if (candidate.scheme !in setOf("http", "https") || candidate.rawUserInfo != null ||
                candidate.host.isNullOrBlank() || !candidate.path.startsWith("/cgi-bin/luci/admin/")
            ) {
                return@withContext DockerPageProbeResult(DockerPageProbeStatus.TRANSIENT_FAILURE)
            }
            val connection = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull()
                ?: return@withContext DockerPageProbeResult(DockerPageProbeStatus.TRANSIENT_FAILURE)
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
                val location = connection.getHeaderField("Location")
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val body = stream?.use { it.readBoundedUtf8() }.orEmpty()
                DockerPageProbeResult(classifyDockerPageResponse(status, location, body, candidate.path))
            } catch (_: java.net.SocketTimeoutException) {
                DockerPageProbeResult(DockerPageProbeStatus.TRANSIENT_FAILURE)
            } catch (_: java.io.IOException) {
                DockerPageProbeResult(DockerPageProbeStatus.TRANSIENT_FAILURE)
            } finally {
                connection.disconnect()
            }
        }
}

internal fun classifyDockerPageResponse(
    statusCode: Int,
    location: String?,
    body: String,
    candidatePath: String,
): DockerPageProbeStatus {
    if (statusCode in 300..399) {
        val redirectPath = runCatching { URI("https://placeholder.invalid/").resolve(location.orEmpty()).path }
            .getOrNull().orEmpty().lowercase(Locale.US)
        return if (redirectPath.endsWith("/cgi-bin/luci/") || redirectPath.contains("login")) {
            DockerPageProbeStatus.LOGIN_REQUIRED
        } else {
            DockerPageProbeStatus.TRANSIENT_FAILURE
        }
    }
    if (statusCode == HttpURLConnection.HTTP_UNAUTHORIZED) return DockerPageProbeStatus.LOGIN_REQUIRED
    if (statusCode == HttpURLConnection.HTTP_FORBIDDEN) return DockerPageProbeStatus.FORBIDDEN
    if (statusCode == HttpURLConnection.HTTP_NOT_FOUND) return DockerPageProbeStatus.NOT_FOUND
    if (statusCode !in 200..299) return DockerPageProbeStatus.TRANSIENT_FAILURE

    val normalized = body.lowercase(Locale.US)
    val loginForm = Regex("(?is)<input\\b[^>]*type\\s*=\\s*['\\\"]?password\\b").containsMatchIn(body) ||
        (normalized.contains("luci_password") && normalized.contains("luci_username"))
    if (loginForm) return DockerPageProbeStatus.LOGIN_REQUIRED

    val isLegacyOverview = candidatePath == DockerPageRouteResolver.CANDIDATE_PATHS.last() &&
        hasLegacyDockermanOverviewMarkers(body)
    val isModernOverview = candidatePath == DockerPageRouteResolver.CANDIDATE_PATHS.first() &&
        hasModernDockermanOverviewMarkers(body)
    val hasDockerMarker = isLegacyOverview || isModernOverview
    return if (hasDockerMarker) DockerPageProbeStatus.DOCKER_PAGE else DockerPageProbeStatus.NON_DOCKER_PAGE
}

/**
 * The lisaac/iStoreOS legacy overview is server-rendered. Its status grid and
 * both data-page links distinguish it from LuCI's global sidebar, which may
 * contain Dockerman, Containers and Images links on unrelated pages.
 */
private fun hasLegacyDockermanOverviewMarkers(body: String): Boolean {
    val hasStatusGrid = Regex("(?is)<[^>]+\\bclass\\s*=\\s*(['\"])(.*?)\\1[^>]*>")
        .findAll(body)
        .any { match ->
            val classes = match.groupValues[2].split(Regex("\\s+")).toSet()
            "pure-g" in classes && "status" in classes
        }
    if (!hasStatusGrid) return false

    val pageLinks = Regex("(?is)<a\\b[^>]*\\bhref\\s*=\\s*(['\"])(.*?)\\1[^>]*>")
        .findAll(body)
        .map { it.groupValues[2].substringBefore('?').substringBefore('#').lowercase(Locale.US) }
        .toList()
    fun hasPageLink(page: String) = pageLinks.any { href ->
        Regex("(?:^|/)${page}(?:$|/)").containsMatchIn(href.trimEnd('/'))
    }
    return hasPageLink("containers") && hasPageLink("images")
}

/**
 * Newer LuCI builds render their overview client-side. Do not accept a generic
 * LuCI shell just because its menu contains a Dockerman overview link: require
 * an actual Overview heading/title and multiple engine-specific data labels.
 */
private fun hasModernDockermanOverviewMarkers(body: String): Boolean {
    val visibleBody = body
        .replace(Regex("(?is)<script\\b[^>]*>.*?</script>"), " ")
        .replace(Regex("(?is)<style\\b[^>]*>.*?</style>"), " ")
        .replace(Regex("(?is)<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale.US)

    val titleText = Regex("(?is)<title\\b[^>]*>(.*?)</title>")
        .find(body)?.groupValues?.get(1).orEmpty()
        .replace(Regex("(?is)<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .lowercase(Locale.US)
    val headingTexts = Regex("(?is)<h[1-3]\\b[^>]*>(.*?)</h[1-3]>")
        .findAll(body)
        .map { it.groupValues[1].replace(Regex("(?is)<[^>]+>"), " ").trim().lowercase(Locale.US) }
    val hasOverviewHeading = headingTexts.any { it == "overview" || it == "docker overview" } ||
        titleText == "overview" || titleText == "docker overview" || titleText == "overview - docker"
    if (!hasOverviewHeading) return false

    val engineDataLabels = listOf(
        "docker version",
        "api version",
        "total memory",
        "docker root",
        "rootdir",
        "index server",
        "registry mirrors",
    )
    return engineDataLabels.count(visibleBody::contains) >= 2
}

private fun InputStream.readBoundedUtf8(maxBytes: Int = 256 * 1024): String {
    val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
    val buffer = ByteArray(8 * 1024)
    var remaining = maxBytes
    while (remaining > 0) {
        val count = read(buffer, 0, minOf(buffer.size, remaining))
        if (count < 0) break
        if (count == 0) continue
        output.write(buffer, 0, count)
        remaining -= count
    }
    return output.toString(Charsets.UTF_8.name())
}
