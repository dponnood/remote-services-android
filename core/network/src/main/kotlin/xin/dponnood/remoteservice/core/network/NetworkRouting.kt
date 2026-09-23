package xin.dponnood.remoteservice.core.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.location.LocationManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xin.dponnood.remoteservice.core.model.ConnectionPolicy
import xin.dponnood.remoteservice.core.model.ServiceType
import xin.dponnood.remoteservice.core.model.urlContainsUserInfo
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

/** Which configured endpoint was selected for a service. */
enum class RouteKind {
    INTERNAL,
    PUBLIC,
}

/** User supplied routing configuration. URLs may use HTTP or HTTPS. */
data class ServiceRouteConfig(
    val internalUrl: String? = null,
    val publicUrl: String? = null,
    val trustedSsids: Set<String> = emptySet(),
    val probePath: String = "/",
    /**
     * Service family is part of route policy so authentication-required
     * responses can be treated as online only for LuCI/iStore. Generic
     * services must continue to require a normal 2xx/3xx health response.
     */
    val serviceType: ServiceType = ServiceType.GENERIC,
    /** Route selection policy; AUTO preserves the original behavior. */
    val connectionPolicy: ConnectionPolicy = ConnectionPolicy.AUTO,
)

data class RouteEndpoint(
    val kind: RouteKind,
    val url: String,
)

enum class SsidPermissionState {
    AVAILABLE,
    DENIED,
    UNAVAILABLE,
}

interface SsidProvider {
    fun currentSsid(): String?

    fun permissionState(): SsidPermissionState
}

/**
 * Android 12/13+ compatible SSID provider. A missing nearby-Wi-Fi permission is
 * intentionally represented as an unknown SSID; callers must fall back to the
 * public endpoint instead of assuming the device is on a trusted LAN.
 */
class AndroidSsidProvider(
    private val context: Context,
) : SsidProvider {
    private val wifiManager: WifiManager?
        get() = context.applicationContext.getSystemService(WifiManager::class.java)

    override fun permissionState(): SsidPermissionState {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // NEARBY_WIFI_DEVICES is the Android 13+ Wi-Fi access group, but
            // SSID/scan data remains location-sensitive and is redacted unless
            // precise location is also granted. Keep route selection safe when
            // the user grants Nearby devices but chooses not to grant location.
            if (context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) !=
                PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return SsidPermissionState.DENIED
            }
        } else {
            // Android 6 through 12 gate SSID access behind location permission.
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return SsidPermissionState.DENIED
            }
        }
        // On Android 9+ SSID/scan disclosure also depends on the system
        // location toggle. Never infer a LAN from a value the platform refused
        // to disclose, regardless of which permission group was granted.
        if (wifiManager == null) return SsidPermissionState.UNAVAILABLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val locationManager = context.getSystemService(LocationManager::class.java)
            if (locationManager == null || !runCatching { locationManager.isLocationEnabled }.getOrDefault(false)) {
                return SsidPermissionState.DENIED
            }
        }
        return SsidPermissionState.AVAILABLE
    }

    override fun currentSsid(): String? {
        val permission = permissionState()
        if (permission == SsidPermissionState.DENIED || permission == SsidPermissionState.UNAVAILABLE) {
            return null
        }
        // WifiManager.connectionInfo is deprecated on API 31+ and can be
        // stale/redacted on newer Android releases. Prefer the active
        // network's WifiInfo, then keep the legacy API as a compatibility
        // fallback for API 30 and vendor builds.
        val connectedWifiSsid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val connectivity = context.applicationContext
                    .getSystemService(ConnectivityManager::class.java)
                if (connectivity == null) return@runCatching null
                // `activeNetwork` can legitimately be cellular while a
                // validated Wi-Fi network remains connected (for example on
                // dual-network emulators and some vendor devices). Inspect
                // all currently connected networks so route selection follows
                // the actual Wi-Fi transport instead of silently falling back
                // to the public endpoint.
                val networks = buildList {
                    connectivity.activeNetwork?.let(::add)
                    connectivity.allNetworks.forEach { network ->
                        if (!contains(network)) add(network)
                    }
                }
                networks.asSequence()
                    .mapNotNull { network ->
                        val capabilities = connectivity.getNetworkCapabilities(network)
                        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                            // Android may return a location-redacted
                            // `<unknown ssid>` in transportInfo even when a
                            // real Wi-Fi network is connected. Filter it at
                            // the source so the legacy WifiManager fallback
                            // is still attempted instead of treating the
                            // redacted value as a successful read.
                            (capabilities.transportInfo as? WifiInfo)?.ssid
                                ?.let(AndroidSsidProvider::normalizeSsid)
                        } else {
                            null
                        }
                    }
                    .firstOrNull()
            }.getOrNull()
        } else {
            null
        }
        val raw = connectedWifiSsid ?: runCatching { wifiManager?.connectionInfo?.ssid }.getOrNull()
        return normalizeSsid(raw)
    }

    companion object {
        fun normalizeSsid(raw: String?): String? {
            val value = raw
                ?.trim()
                ?.let(::removeSsidQuotes)
                ?.trim()
                ?: return null
            if (
                value.isBlank() ||
                value.equals("<unknown ssid>", ignoreCase = true) ||
                value.equals("unknown ssid", ignoreCase = true)
            ) return null
            return value
        }

        private fun removeSsidQuotes(value: String): String = when {
            value.length >= 2 &&
                ((value.first() == '"' && value.last() == '"') ||
                    (value.first() == '\'' && value.last() == '\'')) ->
                value.substring(1, value.length - 1)
            else -> value
        }
    }
}

/**
 * Compares the platform SSID with user-entered trusted SSIDs safely.
 *
 * Android returns a decoded SSID wrapped in double quotes, while persisted
 * values can come from a text field, an older release, or a copied Wi-Fi
 * name. Normalize surrounding whitespace and Android's quote wrappers at the
 * routing boundary, while preserving case because SSIDs are case-sensitive.
 */
fun isTrustedSsid(currentSsid: String?, trustedSsids: Iterable<String?>): Boolean {
    val current = AndroidSsidProvider.normalizeSsid(currentSsid) ?: return false
    return trustedSsids.any { configured ->
        AndroidSsidProvider.normalizeSsid(configured) == current
    }
}

data class HealthProbeResult(
    val reachable: Boolean,
    val statusCode: Int? = null,
    val elapsedMs: Long = 0,
    val errorCode: NetworkErrorCode? = null,
)

enum class NetworkErrorCode {
    INVALID_ENDPOINT,
    /** The endpoint used a scheme other than HTTP or HTTPS. */
    UNSUPPORTED_SCHEME,
    /** @deprecated Use [UNSUPPORTED_SCHEME]; kept for persisted/error API compatibility. */
    @Deprecated("Use UNSUPPORTED_SCHEME")
    NON_HTTPS_ENDPOINT,
    TIMEOUT,
    TLS_FAILURE,
    IO_FAILURE,
    HTTP_FAILURE,
}

interface HealthProbe {
    suspend fun probe(url: String, path: String = "/"): HealthProbeResult
}

/**
 * Small HTTP(S) probe which deliberately does not disable certificate
 * validation and does not follow redirects. Redirects are reported as
 * reachable only when the configured endpoint itself is healthy; opening the
 * final URL remains the WebView's responsibility and its exact origin
 * allow-list checks.
 *
 * The historical class name is kept for binary/source compatibility with the
 * application wiring. It now intentionally supports both schemes because a
 * service can be explicitly configured as an unencrypted local HTTP endpoint.
 */
class HttpsHealthProbe(
    private val connectTimeoutMs: Int = 3_000,
    private val readTimeoutMs: Int = 3_000,
) : HealthProbe {
    override suspend fun probe(url: String, path: String): HealthProbeResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val parsedUri = runCatching { URI(url.trim()) }.getOrNull()
            ?: return@withContext HealthProbeResult(false, errorCode = NetworkErrorCode.INVALID_ENDPOINT)
        // Do not pass legacy userinfo-bearing service URLs to URLConnection.
        // New config writes reject these credentials; this also makes older
        // persisted values fail closed before any network request is issued.
        if (urlContainsUserInfo(url)) {
            return@withContext HealthProbeResult(false, errorCode = NetworkErrorCode.INVALID_ENDPOINT)
        }
        val parsedScheme = parsedUri.scheme?.lowercase(Locale.US)
        if (parsedScheme != "http" && parsedScheme != "https") {
            return@withContext HealthProbeResult(false, errorCode = NetworkErrorCode.UNSUPPORTED_SCHEME)
        }
        val uri = runCatching { Uri.parse(url) }.getOrNull()
            ?: return@withContext HealthProbeResult(false, errorCode = NetworkErrorCode.INVALID_ENDPOINT)
        if (!uri.scheme.equals("http", ignoreCase = true) &&
            !uri.scheme.equals("https", ignoreCase = true)
        ) {
            return@withContext HealthProbeResult(false, errorCode = NetworkErrorCode.UNSUPPORTED_SCHEME)
        }
        if (!uri.isHierarchical || uri.host.isNullOrBlank()) {
            return@withContext HealthProbeResult(false, errorCode = NetworkErrorCode.INVALID_ENDPOINT)
        }
        val probeUri = buildProbeUri(uri, path)
        fun openConnection(method: String): HttpURLConnection? {
            val candidate = runCatching {
                URL(probeUri.toString()).openConnection() as HttpURLConnection
            }.getOrNull() ?: return null
            candidate.connectTimeout = connectTimeoutMs
            candidate.readTimeout = readTimeoutMs
            candidate.instanceFollowRedirects = false
            candidate.useCaches = false
            candidate.requestMethod = method
            candidate.setRequestProperty("Cache-Control", "no-cache")
            return candidate
        }

        var connection = openConnection("HEAD")
            ?: return@withContext HealthProbeResult(false, errorCode = NetworkErrorCode.INVALID_ENDPOINT)
        try {
            var status = connection.responseCode
            // Some older uhttpd/LuCI deployments reject HEAD even though the
            // same URL works with a normal browser GET. Retry only the two
            // standard "method unsupported" responses; all other failures
            // retain the existing strict probe semantics.
            if (shouldRetryHealthProbeWithGet(status)) {
                connection.disconnect()
                connection = openConnection("GET")
                    ?: return@withContext HealthProbeResult(false, errorCode = NetworkErrorCode.INVALID_ENDPOINT)
                status = connection.responseCode
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            val reachable = status in 200..399
            HealthProbeResult(
                reachable = reachable,
                statusCode = status,
                elapsedMs = elapsed,
                errorCode = if (reachable) null else NetworkErrorCode.HTTP_FAILURE,
            )
        } catch (_: java.net.SocketTimeoutException) {
            val elapsed = (System.nanoTime() - started) / 1_000_000
            HealthProbeResult(false, elapsedMs = elapsed, errorCode = NetworkErrorCode.TIMEOUT)
        } catch (_: javax.net.ssl.SSLException) {
            val elapsed = (System.nanoTime() - started) / 1_000_000
            HealthProbeResult(false, elapsedMs = elapsed, errorCode = NetworkErrorCode.TLS_FAILURE)
        } catch (_: java.io.IOException) {
            val elapsed = (System.nanoTime() - started) / 1_000_000
            HealthProbeResult(false, elapsedMs = elapsed, errorCode = NetworkErrorCode.IO_FAILURE)
        } finally {
            connection.disconnect()
        }
    }

    private fun buildProbeUri(base: Uri, path: String): Uri {
        val cleanPath = effectiveProbePath(base.path, path)
        return base.buildUpon()
            .path(cleanPath)
            .clearQuery()
            .fragment(null)
            .build()
    }
}

/**
 * LuCI URLs are commonly configured as `/cgi-bin/luci`. Keep that path for
 * the default health check instead of replacing it with `/`; callers can
 * still provide a non-root [requestedPath] when a different probe is needed.
 */
internal fun effectiveProbePath(basePath: String?, requestedPath: String): String {
    val requested = requestedPath.trim()
    val normalizedBase = basePath?.trim().orEmpty()
    if ((requested.isBlank() || requested == "/") &&
        normalizedBase.isNotBlank() && normalizedBase != "/"
    ) {
        return if (normalizedBase.startsWith('/')) normalizedBase else "/$normalizedBase"
    }
    return if (requested.startsWith('/')) requested else "/$requested"
}

/** Retry GET only when the server explicitly rejects the HEAD method. */
internal fun shouldRetryHealthProbeWithGet(statusCode: Int): Boolean =
    statusCode == HttpURLConnection.HTTP_BAD_METHOD ||
        statusCode == HttpURLConnection.HTTP_NOT_IMPLEMENTED

/** LuCI returns these statuses with a login form when the session is absent. */
internal fun isAuthenticationRequiredStatus(statusCode: Int?): Boolean =
    statusCode == HttpURLConnection.HTTP_UNAUTHORIZED || statusCode == HttpURLConnection.HTTP_FORBIDDEN

/**
 * A router can be reachable while requiring authentication. Keep the raw
 * status code in [HealthProbeResult] for the UI/logs, but normalize only this
 * narrow service family to a usable route. No generic service gains this
 * exception, and 4xx statuses other than 401/403 stay unavailable.
 */
internal fun HealthProbeResult.acceptForRoute(serviceType: ServiceType): HealthProbeResult {
    if (
        reachable ||
        serviceType != ServiceType.LUCI &&
        serviceType != ServiceType.ISTORE &&
        serviceType != ServiceType.OPENCLASH
    ) return this
    if (!isAuthenticationRequiredStatus(statusCode)) return this
    return copy(reachable = true, errorCode = null)
}

data class RouteResolution(
    val endpoint: RouteEndpoint,
    val ssid: String?,
    val ssidPermission: SsidPermissionState,
    val probe: HealthProbeResult,
    val fallbackUsed: Boolean,
)

sealed interface RouteResolutionResult {
    data class Success(val value: RouteResolution) : RouteResolutionResult

    data class Failure(
        val code: NetworkErrorCode,
        val attempted: List<RouteEndpoint>,
        val ssid: String?,
        val ssidPermission: SsidPermissionState,
    ) : RouteResolutionResult
}

/**
 * Resolves a service route without probing both endpoints more than necessary.
 * When the device is on a configured SSID the internal endpoint is attempted
 * first; one public fallback is allowed. On an unknown/untrusted SSID only the
 * public endpoint is attempted, preventing accidental LAN probing.
 */
class RouteResolver(
    private val ssidProvider: SsidProvider,
    private val healthProbe: HealthProbe,
) {
    suspend fun resolve(config: ServiceRouteConfig): RouteResolutionResult {
        val permission = ssidProvider.permissionState()
        // Never use an SSID returned while permission is denied/unavailable.
        // Besides being safer, this avoids trusting a stale value from a fake
        // or vendor provider after Android revoked the Wi-Fi permission.
        val ssid = if (permission == SsidPermissionState.AVAILABLE) {
            ssidProvider.currentSsid()
        } else {
            null
        }
        // Do not trust a stale/provider-supplied SSID when the platform has
        // denied access. Unknown permission state must take the public-only
        // path just like an unknown SSID.
        val onTrustedSsid = permission == SsidPermissionState.AVAILABLE &&
            isTrustedSsid(ssid, config.trustedSsids)
        val internal = config.internalUrl?.let { RouteEndpoint(RouteKind.INTERNAL, it) }
        val public = config.publicUrl?.let { RouteEndpoint(RouteKind.PUBLIC, it) }
        val candidates = when (config.connectionPolicy) {
            ConnectionPolicy.PUBLIC_ONLY -> listOfNotNull(public)
            ConnectionPolicy.INTERNAL_ONLY -> if (onTrustedSsid) {
                listOfNotNull(internal)
            } else {
                // Unknown/untrusted Wi-Fi must not probe an RFC1918/LAN
                // address, and INTERNAL_ONLY intentionally has no fallback.
                emptyList()
            }
            ConnectionPolicy.AUTO -> if (onTrustedSsid) {
                listOfNotNull(internal, public)
            } else {
                // Unknown/untrusted Wi-Fi must not probe an RFC1918/LAN address.
                listOfNotNull(public)
            }
        }
        if (candidates.isEmpty()) {
            return RouteResolutionResult.Failure(
                code = NetworkErrorCode.INVALID_ENDPOINT,
                attempted = emptyList(),
                ssid = ssid,
                ssidPermission = permission,
            )
        }

        val attempted = ArrayList<RouteEndpoint>(candidates.size)
        var lastError = NetworkErrorCode.IO_FAILURE
        candidates.forEachIndexed { index, endpoint ->
            attempted += endpoint
            val probe = healthProbe.probe(endpoint.url, config.probePath)
            val routeProbe = probe.acceptForRoute(config.serviceType)
            if (routeProbe.reachable) {
                return RouteResolutionResult.Success(
                    RouteResolution(
                        endpoint = endpoint,
                        ssid = ssid,
                        ssidPermission = permission,
                        probe = routeProbe,
                        fallbackUsed = index > 0,
                    ),
                )
            }
            lastError = probe.errorCode ?: NetworkErrorCode.IO_FAILURE
            // candidates contains at most internal + one public fallback.
        }
        return RouteResolutionResult.Failure(lastError, attempted, ssid, permission)
    }
}

internal fun String.normalizedHost(): String = trim().lowercase(Locale.US).removeSuffix(".")
