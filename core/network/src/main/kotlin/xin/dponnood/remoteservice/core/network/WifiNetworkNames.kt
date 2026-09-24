package xin.dponnood.remoteservice.core.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Result of asking the platform for Wi-Fi names that can be used as routing
 * hints. A denied/unavailable permission is deliberately represented as an
 * empty current-network result so the editor can continue accepting a
 * manually entered SSID.
 */
data class WifiNetworkNameResult(
    val names: List<String> = emptyList(),
    val currentSsid: String? = null,
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
 * The deliberately small platform boundary for SSID suggestions. It exposes
 * only networks Android can report as configured and the currently connected
 * SSID; scan results are intentionally not part of this contract.
 */
internal interface WifiNetworkNameSource {
    fun permissionState(): SsidPermissionState

    fun configuredNetworkNames(): List<String?>

    fun currentSsid(): String?
}

/** Best-effort reader used only by trusted-SSID editors. */
class AndroidWifiNetworkNameProvider internal constructor(
    private val source: WifiNetworkNameSource,
) : WifiNetworkNameProvider {
    constructor(context: Context) : this(AndroidWifiNetworkNameSource(context))

    override suspend fun load(): WifiNetworkNameResult = withContext(Dispatchers.IO) {
        val permissionState = runCatching { source.permissionState() }
            .getOrDefault(SsidPermissionState.UNAVAILABLE)

        // Android may reject configuredNetworks for an ordinary app or return
        // an empty list for target-29+ apps. Keep it best-effort and independent
        // from the currently connected SSID so either source can still help.
        val configuredResult = runCatching { source.configuredNetworkNames() }
        val currentName = if (permissionState == SsidPermissionState.AVAILABLE) {
            runCatching { source.currentSsid() }.getOrNull()
        } else {
            null
        }
        val normalizedCurrentName = normalizeWifiNetworkNames(listOf(currentName)).firstOrNull()
        val names = normalizeWifiNetworkNames(configuredResult.getOrDefault(emptyList()) + currentName)
        WifiNetworkNameResult(
            names = names,
            currentSsid = normalizedCurrentName,
            permissionState = permissionState,
            configuredNetworksReadable = configuredResult.isSuccess,
            statusMessage = if (names.isEmpty()) emptyResultMessage(permissionState) else null,
        )
    }

    private fun emptyResultMessage(permissionState: SsidPermissionState): String = when (permissionState) {
        SsidPermissionState.DENIED ->
            "未读取到可用名称；当前 Wi-Fi 名称可能受系统权限限制。Android 10+ 普通应用无法枚举系统完整已保存列表，请手动输入。"
        SsidPermissionState.UNAVAILABLE ->
            "系统未提供可用的 Wi-Fi 名称。Android 10+ 普通应用无法枚举系统完整已保存列表，请手动输入。"
        SsidPermissionState.AVAILABLE ->
            "系统未返回已配置或当前 Wi-Fi 名称。Android 10+ 普通应用无法枚举系统完整已保存列表，请手动输入。"
    }
}

/** Android adapter: no scan-result API is called or exposed to the provider. */
private class AndroidWifiNetworkNameSource(
    context: Context,
) : WifiNetworkNameSource {
    private val appContext = context.applicationContext
    private val ssidProvider by lazy { AndroidSsidProvider(appContext) }

    override fun permissionState(): SsidPermissionState = ssidProvider.permissionState()

    @SuppressLint("MissingPermission")
    override fun configuredNetworkNames(): List<String?> {
        val wifiManager = appContext.getSystemService(WifiManager::class.java) ?: return emptyList()
        return wifiManager.configuredNetworks.orEmpty().map { it.SSID }
    }

    override fun currentSsid(): String? = ssidProvider.currentSsid()
}

/** Normalizes quoted/unknown SSIDs, removes duplicates, and gives a stable UI order. */
fun normalizeWifiNetworkNames(rawNames: Iterable<String?>): List<String> = rawNames
    .mapNotNull(AndroidSsidProvider::normalizeSsid)
    .distinct()
    .sortedWith(String.CASE_INSENSITIVE_ORDER)

/** Keeps the connected SSID first while sorting all other suggestions by name. */
fun prioritizeWifiNetworkNames(rawNames: Iterable<String>, currentSsid: String?): List<String> {
    val names = rawNames
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
    val current = currentSsid?.trim()?.takeIf(names::contains) ?: return names
    return listOf(current) + names.filterNot { it == current }
}
