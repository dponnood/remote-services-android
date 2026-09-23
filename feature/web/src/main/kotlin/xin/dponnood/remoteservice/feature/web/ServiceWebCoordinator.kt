package xin.dponnood.remoteservice.feature.web

import xin.dponnood.remoteservice.core.model.ServiceConfig
import xin.dponnood.remoteservice.core.network.RouteEndpoint
import xin.dponnood.remoteservice.core.network.RouteResolution
import xin.dponnood.remoteservice.core.network.RouteResolutionResult
import xin.dponnood.remoteservice.core.network.RouteResolver
import xin.dponnood.remoteservice.core.network.toRouteConfig
import java.net.URI
import java.util.Locale

sealed interface ServiceWebOpenResult {
    data class Ready(
        val target: WebTarget,
        val resolution: RouteResolution,
    ) : ServiceWebOpenResult

    data class Unavailable(
        val attempted: List<RouteEndpoint>,
        val message: String,
    ) : ServiceWebOpenResult
}

/**
 * Application-facing bridge between route selection and the WebView screen.
 * It deliberately accepts trusted SSIDs and credentials separately from the
 * service catalogue; passwords never enter ServiceConfig/DataStore.
 */
class ServiceWebCoordinator(
    private val routeResolver: RouteResolver,
) {
    suspend fun resolve(
        service: ServiceConfig,
        trustedSsids: Set<String> = service.trustedSsids,
        probePath: String = "/",
    ): ServiceWebOpenResult {
        return when (val result = routeResolver.resolve(service.toRouteConfig(trustedSsids, probePath))) {
            is RouteResolutionResult.Success -> {
                val endpoint = result.value.endpoint
                val origin = webOrigin(endpoint.url)
                if (origin == null) {
                    ServiceWebOpenResult.Unavailable(
                        attempted = listOf(endpoint),
                        message = "服务地址不是受支持的 HTTP/HTTPS origin",
                    )
                } else {
                    // Both endpoints were explicitly entered by the user.
                    // Keep them as separate cookie hosts while allowing a
                    // router page to switch LAN/WAN links without jumping to
                    // the system browser.  The exact origin (scheme, host and
                    // effective port) remains the boundary.
                    val configuredOrigins = buildSet {
                        add(origin)
                        service.lanUrl?.let(::webOrigin)?.let(::add)
                        service.wanUrl?.let(::webOrigin)?.let(::add)
                    }
                    ServiceWebOpenResult.Ready(
                        target = WebTarget(
                            url = endpoint.url,
                            sessionKey = WebSessionKey(service.id, endpoint.kind, origin),
                            allowedOrigins = configuredOrigins,
                        ),
                        resolution = result.value,
                    )
                }
            }

            is RouteResolutionResult.Failure -> ServiceWebOpenResult.Unavailable(
                attempted = result.attempted,
                message = "服务暂不可用（${result.code.name}）",
            )
        }
    }

    companion object {
        /** Canonical origin used by WebTarget's exact scheme/host/port allow-list. */
        fun webOrigin(url: String): String? {
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase(Locale.US)
            if (!uri.isAbsolute || uri.isOpaque ||
                (scheme != "http" && scheme != "https") || uri.rawUserInfo != null
            ) {
                return null
            }
            val host = uri.host?.lowercase(Locale.US)?.removePrefix("[")?.removeSuffix("]")?.removeSuffix(".")
                ?.takeIf(String::isNotBlank) ?: return null
            val authorityHost = if (host.contains(':')) "[$host]" else host
            val defaultPort = if (scheme == "http") 80 else 443
            val effectivePort = if (uri.port == -1) defaultPort else uri.port
            if (effectivePort !in 1..65535) return null
            val port = if (effectivePort == defaultPort) "" else ":$effectivePort"
            return "$scheme://$authorityHost$port"
        }

        /** HTTPS-only compatibility helper for callers that require TLS. */
        fun httpsOrigin(url: String): String? = webOrigin(url)?.takeIf {
            it.startsWith("https://")
        }
    }
}
