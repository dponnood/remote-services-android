package xin.dponnood.remoteservice.core.model

/** Persisted definition of a remote service shown by the app. */
data class ServiceConfig(
    val id: String,
    val displayName: String,
    val lanUrl: String? = null,
    val wanUrl: String? = null,
    val group: String? = null,
    val sortOrder: Int = 0,
    /** Stable key into the app's built-in service icon library. */
    val iconKey: String = "service",
    /** SSIDs on which probing the LAN endpoint is allowed. */
    val trustedSsids: Set<String> = emptySet(),
    /** Service family used by the web/session coordinator. */
    val serviceType: ServiceType = ServiceType.GENERIC,
    /** Whether the service has credentials kept in CredentialStore. */
    val authEnabled: Boolean = false,
    /**
     * How the route resolver should choose between the configured LAN and WAN
     * endpoints. AUTO preserves the original trusted-Wi-Fi/LAN-first policy.
     */
    val connectionPolicy: ConnectionPolicy = ConnectionPolicy.AUTO,
) {
    val hasEndpoint: Boolean
        get() = !lanUrl.isNullOrBlank() || !wanUrl.isNullOrBlank()

    val normalizedGroup: String?
        get() = group?.trim()?.takeIf { it.isNotEmpty() }
}

/** Editable form value. Keeping this separate prevents half-edited values being persisted. */
data class ServiceDraft(
    val id: String? = null,
    val displayName: String = "",
    val lanUrl: String = "",
    val wanUrl: String = "",
    val group: String = "",
    val iconKey: String = "service",
    val trustedSsids: Set<String> = emptySet(),
    val serviceType: ServiceType = ServiceType.GENERIC,
    val authEnabled: Boolean = false,
    val connectionPolicy: ConnectionPolicy = ConnectionPolicy.AUTO,
) {
    fun isDirtyComparedTo(config: ServiceConfig?): Boolean {
        if (config == null) return displayName.isNotBlank() || lanUrl.isNotBlank() ||
            wanUrl.isNotBlank() || group.isNotBlank() || trustedSsids.isNotEmpty() ||
            serviceType != ServiceType.GENERIC || authEnabled ||
            connectionPolicy != ConnectionPolicy.AUTO
        return this != config.toDraft()
    }

    /** Form-friendly representation: one SSID per line, commas are accepted on input. */
    val trustedSsidsText: String
        get() = trustedSsids.sorted().joinToString("\n")

    fun withTrustedSsidsText(value: String): ServiceDraft = copy(
        trustedSsids = parseTrustedSsids(value),
    )

    companion object {
        fun parseTrustedSsids(value: String): Set<String> = value
            .split(',', ';', '\n')
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
    }
}

fun ServiceConfig.toDraft(): ServiceDraft = ServiceDraft(
    id = id,
    displayName = displayName,
    lanUrl = stripUrlUserInfo(lanUrl).orEmpty(),
    wanUrl = stripUrlUserInfo(wanUrl).orEmpty(),
    group = normalizedGroup.orEmpty(),
    iconKey = iconKey,
    trustedSsids = trustedSsids.map(String::trim).filter(String::isNotBlank).toSet(),
    serviceType = serviceType,
    authEnabled = authEnabled,
    connectionPolicy = connectionPolicy,
)

enum class ServiceType {
    GENERIC,
    NAS,
    LUCI,
    ISTORE,
    /** OpenClash LuCI management page. */
    OPENCLASH_PANEL,
    /** Zashboard quick node selection plus the OpenClash/Mihomo compatible API. */
    OPENCLASH,
    /** OpenWrt/iStoreOS Dockerman page and read-only Docker overview. */
    DOCKER,
}

/**
 * User-selectable route policy. The default is deliberately the historical
 * behavior so existing services do not change after an upgrade.
 */
enum class ConnectionPolicy {
    /** Trusted Wi-Fi: probe LAN first and fall back to WAN; otherwise WAN only. */
    AUTO,
    /** Always use/probe the configured WAN endpoint. */
    PUBLIC_ONLY,
    /** Trusted Wi-Fi only; never use the WAN fallback. */
    INTERNAL_ONLY,
}

enum class ServiceRoute {
    LAN,
    WAN,
    UNAVAILABLE,
}
