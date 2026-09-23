package xin.dponnood.remoteservice.core.network

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Result of asking the platform for Wi‑Fi names that can be used as routing
 * hints. A denied/unavailable permission is deliberately represented as an
 * empty result so the editor can continue accepting a manually entered SSID.
 */
data class WifiNetworkNameResult(
    val names: List<String> = emptyList(),
    val permissionState: SsidPermissionState = SsidPermissionState.UNAVAILABLE,
    val configuredNetworksReadable: Boolean = false,
    val statusMessage: String? = null,
) {
    val needsPermission: Boolean
        get() = permissionState == SsidPermissionState.DENIED
}

interface WifiNetworkNameProvider {
    suspend fun load(): WifiNetworkNameResult
}

/**
 * Best-effort Android Wi‑Fi name reader used only by the service editor.
 *
 * Android 10+ intentionally limits access to the full saved-network list for
 * ordinary applications. We therefore combine the currently connected SSID,
 * configured networks when the platform exposes them, and visible scan names
 * when available. The UI labels this as a suggestion list and always keeps a
 * free-form input path. No password or other Wi‑Fi configuration is read.
 */
class AndroidWifiNetworkNameProvider(
    context: Context,
) : WifiNetworkNameProvider {
    private val appContext = context.applicationContext
    private val ssidProvider by lazy { AndroidSsidProvider(appContext) }

    @SuppressLint("MissingPermission")
    override suspend fun load(): WifiNetworkNameResult = withContext(Dispatchers.IO) {
        val permissionState = ssidProvider.permissionState()
        if (permissionState != SsidPermissionState.AVAILABLE) {
            return@withContext WifiNetworkNameResult(
                permissionState = permissionState,
                statusMessage = unavailableReason(),
            )
        }

        val wifiManager = appContext.getSystemService(WifiManager::class.java)
            ?: return@withContext WifiNetworkNameResult(
                permissionState = SsidPermissionState.UNAVAILABLE,
                statusMessage = "当前设备没有可用的 Wi-Fi 服务，请手动输入名称。",
            )
        val names = mutableListOf<String?>()
        names += ssidProvider.currentSsid()

        var configuredReadable = false
        runCatching {
            // getConfiguredNetworks is deprecated on API 29+ and can return
            // an empty list for target-29+ apps, but remains useful on older
            // releases and on vendor builds that still expose saved networks.
            wifiManager.configuredNetworks.orEmpty().forEach { configuration ->
                names += configuration.SSID
            }
            configuredReadable = true
        }

        runCatching {
            // Scan names are a useful fallback where the saved list is hidden.
            // They contain names only; BSSID/security/password data is ignored.
            wifiManager.scanResults.orEmpty().forEach { result ->
                names += result.SSID
            }
        }

        val normalizedNames = normalizeWifiNetworkNames(names)
        WifiNetworkNameResult(
            names = normalizedNames,
            permissionState = permissionState,
            configuredNetworksReadable = configuredReadable,
            statusMessage = if (normalizedNames.isEmpty()) {
                "系统未返回可选择的 Wi-Fi 名称；Android 10+ 可能不提供完整保存列表，请手动输入。"
            } else {
                null
            },
        )
    }

    private fun unavailableReason(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            appContext.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return "需要允许“附近的设备”权限才能读取 Wi-Fi 名称。"
        }
        if (appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return "需要允许“精确位置”权限才能读取 Wi-Fi 名称；也可以直接手动输入。"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val locationManager = appContext.getSystemService(LocationManager::class.java)
            if (locationManager == null || !runCatching { locationManager.isLocationEnabled }.getOrDefault(false)) {
                return "系统定位服务未开启，无法读取 Wi-Fi 名称；也可以直接手动输入。"
            }
        }
        return "系统暂时不允许读取 Wi-Fi 名称；也可以直接手动输入。"
    }
}

/** Normalizes quoted/unknown SSIDs, removes duplicates, and gives a stable UI order. */
fun normalizeWifiNetworkNames(rawNames: Iterable<String?>): List<String> = rawNames
    .mapNotNull(AndroidSsidProvider::normalizeSsid)
    .distinct()
    .sortedWith(String.CASE_INSENSITIVE_ORDER)
