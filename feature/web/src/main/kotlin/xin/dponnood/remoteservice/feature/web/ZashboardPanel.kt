package xin.dponnood.remoteservice.feature.web

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import xin.dponnood.remoteservice.core.network.RouteKind

/**
 * Describes the static Zashboard bundle expected in APK assets.
 *
 * The current repository intentionally ships a small placeholder instead of
 * downloading an upstream dist archive.  Keeping the path and metadata in a
 * typed object makes replacing that placeholder a contained asset-only
 * change, while preventing path traversal or arbitrary WebView origins.
 */
data class ZashboardAssetConfig(
    val assetDirectory: String = DEFAULT_ASSET_DIRECTORY,
    val entryFile: String = DEFAULT_ENTRY_FILE,
    val assetDomain: String = DEFAULT_ASSET_DOMAIN,
) {
    init {
        require(isSafeAssetSegment(assetDirectory)) {
            "assetDirectory must be one safe path segment"
        }
        require(isSafeAssetSegment(entryFile)) {
            "entryFile must be one safe path segment"
        }
        require(assetDomain == DEFAULT_ASSET_DOMAIN) {
            "Zashboard assets must use the WebViewAssetLoader appassets domain"
        }
    }

    val assetOrigin: String
        get() = "https://$assetDomain"

    val assetPathPrefix: String
        get() = "/assets/"

    /** URL passed to WebView.loadUrl after the loader is attached. */
    val entryUrl: String
        get() = "$assetOrigin$assetPathPrefix$assetDirectory/$entryFile"

    companion object {
        const val DEFAULT_ASSET_DIRECTORY: String = "zashboard"
        const val DEFAULT_ENTRY_FILE: String = "index.html"
        const val DEFAULT_ASSET_DOMAIN: String = "appassets.androidplatform.net"

        private fun isSafeAssetSegment(value: String): Boolean =
            value.isNotBlank() &&
                value != "." &&
                value != ".." &&
                value.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
    }
}

/** Metadata shown in diagnostics and retained with future upstream assets. */
data class ZashboardBundleMetadata(
    val upstreamVersion: String,
    val sourceUrl: String,
    val licenseName: String = "MIT",
    val isPlaceholder: Boolean = true,
) {
    init {
        require(upstreamVersion.isNotBlank()) { "upstreamVersion must not be blank" }
        require(sourceUrl.startsWith("https://")) { "sourceUrl must use HTTPS" }
        require(licenseName == "MIT") { "the planned Zashboard bundle is MIT-licensed" }
    }

    companion object {
        /** Current repository state: no upstream dist is bundled yet. */
        val PLACEHOLDER = ZashboardBundleMetadata(
            upstreamVersion = "not-bundled",
            sourceUrl = "https://github.com/Zephyruso/zashboard",
            isPlaceholder = true,
        )
    }
}

/**
 * All information needed by the host to open the panel without putting a
 * controller secret into the entry URL.  A concrete gateway session exposes
 * only a loopback origin; the panel's static origin remains separately
 * allow-listed for WebView navigation and asset loading.
 */
data class ZashboardPanelEntry(
    val assetEntryUrl: String,
    val gatewayOrigin: String,
    val allowedOrigins: Set<String>,
    val bundle: ZashboardBundleMetadata = ZashboardBundleMetadata.PLACEHOLDER,
) {
    init {
        require(isSafeAssetEntryUrl(assetEntryUrl)) {
            "panel entry must use the WebViewAssetLoader appassets origin"
        }
        require(OpenClashGatewayConfig.isLoopbackOrigin(gatewayOrigin)) {
            "panel gateway must use an exact HTTP(S) loopback origin"
        }
        require(gatewayOrigin in allowedOrigins) { "gateway origin must be allow-listed" }
        require(assetOrigin in allowedOrigins) { "asset origin must be allow-listed" }
    }

    private val assetOrigin: String
        get() = "https://${ZashboardAssetConfig.DEFAULT_ASSET_DOMAIN}"

    private fun isSafeAssetEntryUrl(value: String): Boolean {
        val prefix = "$assetOrigin/assets/"
        val relative = value.removePrefix(prefix)
        return value.startsWith(prefix) &&
            relative.isNotBlank() &&
            !relative.contains("..") &&
            !relative.contains('?') &&
            !relative.contains('#')
    }

    /** Source-compatible bridge for the existing secure WebView host. */
    fun webTarget(
        serviceId: String,
        routeKind: RouteKind = RouteKind.INTERNAL,
    ): WebTarget = WebTarget(
        url = assetEntryUrl,
        sessionKey = WebSessionKey(serviceId, routeKind, gatewayOrigin),
        allowedOrigins = allowedOrigins,
    )

    companion object {
        fun forGateway(
            assetConfig: ZashboardAssetConfig,
            gatewaySession: OpenClashGatewaySession,
            bundle: ZashboardBundleMetadata = ZashboardBundleMetadata.PLACEHOLDER,
        ): ZashboardPanelEntry {
            val allowed = setOf(assetConfig.assetOrigin, gatewaySession.localOrigin)
            return ZashboardPanelEntry(
                assetEntryUrl = assetConfig.entryUrl,
                gatewayOrigin = gatewaySession.localOrigin,
                allowedOrigins = allowed,
                bundle = bundle,
            )
        }
    }
}

/**
 * Thin wrapper around AndroidX's safe asset loader.  It deliberately exposes
 * only interception methods; it does not install a JavaScript bridge or
 * rewrite arbitrary network requests.  The host passes this interceptor to a
 * WebViewClient and keeps the controller API traffic behind the gateway.
 */
class ZashboardAssetLoader(
    context: Context,
    config: ZashboardAssetConfig = ZashboardAssetConfig(),
) {
    private val delegate = WebViewAssetLoader.Builder()
        .setDomain(config.assetDomain)
        .addPathHandler(config.assetPathPrefix, WebViewAssetLoader.AssetsPathHandler(context.applicationContext))
        .build()

    fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
        delegate.shouldInterceptRequest(request.url)

    fun shouldInterceptRequest(uri: Uri): WebResourceResponse? =
        delegate.shouldInterceptRequest(uri)
}

/** Optional hook consumed by [SecureWebViewController] for local assets. */
fun interface WebResourceInterceptor {
    fun intercept(request: WebResourceRequest): WebResourceResponse?
}
