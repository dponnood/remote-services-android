package xin.dponnood.remoteservice

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import xin.dponnood.remoteservice.core.network.RouteKind

/**
 * Mirrors OpenClash's own dashboard URL construction, including the
 * `/ui/zashboard/#/setup` route and controller secret.  The secret is only
 * returned as part of the in-memory navigation URL; callers must not log or
 * persist that URL.
 */
internal object OpenClashDashboardUrlBuilder {
    fun build(
        status: OpenClashControllerInfo,
        routeKind: RouteKind,
        endpointUrl: String,
    ): String? {
        val secret = status.secret?.trim()?.takeIf(String::isNotBlank) ?: return null
        val endpointHost = runCatching { URI(endpointUrl.trim()).host?.trim() }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: return null
        val controllerHost = status.controllerHost?.trim()?.takeIf(String::isNotBlank)
        val directInternal = routeKind == RouteKind.INTERNAL &&
            (controllerHost == null || endpointHost.equals(controllerHost, ignoreCase = true))

        val host: String
        val port: Int
        val scheme: String
        if (directInternal) {
            host = endpointHost
            port = status.controllerPort ?: return null
            scheme = "http"
        } else {
            // Never fall back to the iStore/LuCI hostname for a public route:
            // that is precisely the old behaviour that opened the iStore home.
            host = status.forwardDomain?.trim()?.takeIf(String::isNotBlank) ?: return null
            port = status.forwardPort ?: return null
            // Missing SSL metadata is treated as secure.  Passing the secret
            // over HTTP would be a worse failure mode than a clear error from
            // a misconfigured forwarder.
            scheme = if (status.forwardSsl == false) "http" else "https"
        }

        val normalizedHost = normalizeHost(host) ?: return null
        val encodedHost = queryEncode(normalizedHost)
        val encodedSecret = queryEncode(secret)
        return "$scheme://$normalizedHost:$port/ui/zashboard/#/setup" +
            "?hostname=$encodedHost&port=$port&secret=$encodedSecret"
    }

    private fun normalizeHost(value: String): String? {
        val candidate = value.trim().removePrefix("[").removeSuffix("]")
        if (candidate.isBlank() || candidate.any { it.isWhitespace() || it in "/?#" }) return null
        val parsed = runCatching { URI("http://$candidate") }.getOrNull() ?: return null
        val parsedHost = parsed.host?.lowercase(Locale.US)?.removeSuffix(".") ?: return null
        if (parsed.rawPath.isNotEmpty() && parsed.rawPath != "/") return null
        if (parsed.rawQuery != null || parsed.rawFragment != null || parsed.port != -1) return null
        return if (parsedHost.contains(':')) "[$parsedHost]" else parsedHost
    }

    private fun queryEncode(value: String): String = URLEncoder
        .encode(value, StandardCharsets.UTF_8.name())
        .replace("+", "%20")
}
