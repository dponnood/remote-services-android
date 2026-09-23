package xin.dponnood.remoteservice.feature.web

import java.net.URI
import java.util.Locale

/**
 * Configuration for a future OpenClash/Mihomo API gateway.
 *
 * The gateway is intentionally described as a contract rather than started
 * here.  A concrete implementation must bind only to a literal loopback
 * address and must inject the controller secret in an Authorization header;
 * the secret must never become part of a URL, a WebView query string, or a
 * log message.
 */
data class OpenClashGatewayConfig(
    /** Explicit OpenClash/Mihomo external-controller URL, without a secret. */
    val upstreamUrl: String,
    /** Literal loopback address; DNS names are rejected to avoid rebinding. */
    val bindHost: String = DEFAULT_BIND_HOST,
    /** Zero asks the OS for an ephemeral port. */
    val bindPort: Int = 0,
    val connectTimeoutMillis: Long = 5_000,
    val readTimeoutMillis: Long = 15_000,
) {
    private val parsedUpstream: HttpEndpoint = requireValidEndpoint(upstreamUrl, "upstreamUrl")

    init {
        require(bindHost == bindHost.trim() && isLoopbackHost(bindHost)) {
            "OpenClash gateway must bind to a literal loopback address"
        }
        require(bindPort == 0 || bindPort in 1_024..65_535) {
            "bindPort must be 0 or an unprivileged TCP port"
        }
        require(connectTimeoutMillis in 1..60_000) {
            "connectTimeoutMillis must be between 1 and 60000"
        }
        require(readTimeoutMillis in 1..120_000) {
            "readTimeoutMillis must be between 1 and 120000"
        }
    }

    /** Canonical upstream origin; no path, query, fragment, or credentials. */
    val upstreamOrigin: String
        get() = parsedUpstream.origin

    /** Optional API path prefix retained separately from the origin. */
    val upstreamPathPrefix: String
        get() = parsedUpstream.path

    /**
     * URL used by a concrete proxy when composing an upstream request.  It
     * still contains no secret and never includes a query string.
     */
    val upstreamBaseUrl: String
        get() = upstreamOrigin + if (upstreamPathPrefix == "/") "" else upstreamPathPrefix

    companion object {
        const val DEFAULT_BIND_HOST: String = "127.0.0.1"
        const val IPV6_LOOPBACK_HOST: String = "::1"

        fun isLoopbackHost(host: String): Boolean =
            host == DEFAULT_BIND_HOST || host == IPV6_LOOPBACK_HOST

        /** Exact HTTP(S) loopback origin check used by WebView launch plans. */
        fun isLoopbackOrigin(value: String): Boolean {
            val endpoint = parseEndpoint(value) ?: return false
            return endpoint.path == "/" && isLoopbackHost(endpoint.host)
        }
    }
}

/**
 * Runtime-only secret source.  Implementations should clear the returned
 * array as soon as the gateway has copied it into its request header.
 */
fun interface OpenClashSecretProvider {
    suspend fun readSecret(): CharArray?
}

/**
 * A gateway session exposes only a loopback origin to the WebView.  It does
 * not carry the controller secret, cookies, or any upstream credentials.
 */
data class OpenClashGatewaySession(
    val localOrigin: String,
    val upstreamOrigin: String,
    val boundPort: Int,
) {
    init {
        val local = requireValidEndpoint(localOrigin, "localOrigin")
        require(OpenClashGatewayConfig.isLoopbackHost(local.host)) {
            "gateway session must expose a literal loopback origin"
        }
        require(local.path == "/") {
            "localOrigin must be an origin without a path"
        }
        require(boundPort == local.port) {
            "boundPort must match the loopback origin"
        }
        val upstream = requireValidEndpoint(upstreamOrigin, "upstreamOrigin")
        require(upstream.path == "/") {
            "upstreamOrigin must be an origin without a path"
        }
    }
}

/**
 * Boundary for the future local proxy.  Keeping this interface in the web
 * feature lets the UI and WebView depend on a loopback origin without
 * coupling them to a particular HTTP server implementation.
 */
interface OpenClashGateway {
    /** Starts a loopback listener and returns its non-secret WebView origin. */
    suspend fun start(
        config: OpenClashGatewayConfig,
        secretProvider: OpenClashSecretProvider,
    ): OpenClashGatewaySession

    /** Stops the listener and releases all request/session resources. */
    suspend fun stop()
}

private data class HttpEndpoint(
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String,
) {
    val origin: String
        get() {
            val defaultPort = if (scheme == "http") 80 else 443
            val authorityHost = if (host.contains(':')) "[$host]" else host
            val portSuffix = if (port == defaultPort) "" else ":$port"
            return "$scheme://$authorityHost$portSuffix"
        }
}

private fun requireValidEndpoint(value: String, fieldName: String): HttpEndpoint {
    val parsed = parseEndpoint(value)
    require(parsed != null) {
        "$fieldName must be an absolute HTTP(S) URL without credentials, query, or fragment"
    }
    return parsed
}

private fun parseEndpoint(value: String): HttpEndpoint? {
    val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.US)
    if (!uri.isAbsolute || uri.isOpaque || (scheme != "http" && scheme != "https")) return null
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    val rawHost = uri.host ?: return null
    val host = rawHost.lowercase(Locale.US)
        .removePrefix("[")
        .removeSuffix("]")
        .removeSuffix(".")
        .takeIf(String::isNotBlank)
        ?: return null
    val defaultPort = if (scheme == "http") 80 else 443
    val port = if (uri.port == -1) defaultPort else uri.port
    if (port !in 1..65_535) return null
    val path = uri.rawPath?.ifBlank { "/" } ?: "/"
    if (!path.startsWith("/")) return null
    // A gateway must not turn a configured prefix into a path traversal.
    if (path.split('/').any { it == ".." } || path.contains("%2e", ignoreCase = true)) return null
    return HttpEndpoint(scheme, host, port, path.trimEnd('/').ifBlank { "/" })
}
