package xin.dponnood.remoteservice.feature.web

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.os.Build
import android.os.Message
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import xin.dponnood.remoteservice.core.network.RouteKind
import java.net.URI
import java.util.Locale

data class WebSessionKey(
    val serviceId: String,
    val routeKind: RouteKind,
    val host: String,
)

data class WebTarget(
    val url: String,
    val sessionKey: WebSessionKey,
    /** Exact HTTP(S) origins; scheme and effective port are part of the trust boundary. */
    val allowedOrigins: Set<String> = emptySet(),
    /**
     * Legacy host-only input. It maps to HTTPS/443; callers using another
     * port must provide [allowedOrigins].
     */
    @Deprecated("Use allowedOrigins so the HTTPS port is explicit")
    val allowedHosts: Set<String> = emptySet(),
) {
    /** Source-compatible constructor for the original host-list API. */
    @Deprecated("Use the primary constructor with allowedOrigins")
    constructor(url: String, allowedHosts: Set<String>, sessionKey: WebSessionKey) :
        this(url, sessionKey, emptySet(), allowedHosts)
}

private data class WebOrigin(
    val scheme: String,
    val host: String,
    val port: Int,
)

private fun parseWebUrl(value: String): Pair<URI, WebOrigin>? {
    val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.US)
    if (!uri.isAbsolute || uri.isOpaque || (scheme != "http" && scheme != "https")) return null
    if (uri.rawUserInfo != null) return null
    val host = uri.host?.lowercase(Locale.US)
        ?.removePrefix("[")
        ?.removeSuffix("]")
        ?.removeSuffix(".")
        ?.takeIf(String::isNotBlank)
        ?: return null
    val defaultPort = if (scheme == "http") 80 else 443
    val port = if (uri.port == -1) defaultPort else uri.port
    if (port !in 1..65535) return null
    return uri to WebOrigin(scheme, host, port)
}

private fun parseWebOrigin(value: String): WebOrigin? {
    val (uri, origin) = parseWebUrl(value) ?: return null
    if ((uri.rawPath.isNotEmpty() && uri.rawPath != "/") || uri.rawQuery != null || uri.rawFragment != null) {
        return null
    }
    return origin
}

private fun isAllowedWebOrigin(url: String, allowedOrigins: Set<String>): Boolean {
    val requested = parseWebUrl(url)?.second ?: return false
    return allowedOrigins.asSequence()
        .mapNotNull(::parseWebOrigin)
        .any { it == requested }
}

/**
 * Allows the narrowly defined upgrade that legacy router pages commonly use:
 * a configured HTTP origin may redirect to HTTPS on the same host and either
 * the same port or the conventional 80 -> 443 pair.  It never allows a
 * different host, arbitrary port, downgrade, or non-HTTP(S) scheme.
 */
private fun isAllowedWebNavigation(url: String, allowedOrigins: Set<String>): Boolean {
    if (isAllowedWebOrigin(url, allowedOrigins)) return true
    val requested = parseWebUrl(url)?.second ?: return false
    if (requested.scheme != "https") return false
    return allowedOrigins.asSequence()
        .mapNotNull(::parseWebOrigin)
        .any { configured ->
            configured.scheme == "http" &&
                configured.host == requested.host &&
                (configured.port == requested.port ||
                    (configured.port == 80 && requested.port == 443))
        }
}

private fun WebTarget.resolvedAllowedOrigins(): Set<String> = if (allowedOrigins.isNotEmpty()) {
    allowedOrigins
} else {
    allowedHosts.map { "https://${it.trim()}" }.toSet()
}

data class WebDownloadRequest(
    val url: String,
    val userAgent: String?,
    val contentDisposition: String?,
    val mimeType: String?,
    val contentLength: Long,
)

fun interface ExternalLinkHandler {
    fun open(uri: Uri)
}

/**
 * Opens a user-gesture-created WebView window inside the host application.
 *
 * The callback receives the configured target rather than an arbitrary URL:
 * the URL is delivered later by Chromium through [Message].  The child WebView
 * must therefore use the same exact-origin policy before it is displayed.
 */
fun interface InAppWindowHandler {
    fun open(parent: WebView, target: WebTarget, resultMsg: Message): Boolean
}

fun interface FileChooserHandler {
    fun choose(callback: ValueCallback<Array<Uri>>?, acceptTypes: Array<String>, capture: Boolean): Boolean
}

fun interface DownloadHandler {
    fun enqueue(request: WebDownloadRequest)
}

interface WebViewEventListener {
    fun onLoading(view: WebView, url: String?, progress: Int) = Unit
    fun onPageError(view: WebView, url: String?, description: String?) = Unit
    fun onHttpError(
        view: WebView,
        url: String?,
        statusCode: Int,
        responseHeaders: Map<String, String> = emptyMap(),
    ) = Unit
    fun onTlsError(view: WebView, url: String?) = Unit
    /**
     * Called when the WebView renderer exits before a usable page can be
     * drawn. Returning true from WebViewClient lets the host replace the
     * failed view with a visible recovery state instead of a black canvas.
     */
    fun onRenderProcessGone(view: WebView, didCrash: Boolean) = Unit
}

/** Identity gate for host state that belongs only to the root WebView. */
object RootWebViewEventTarget {
    fun accepts(rootView: Any?, sourceView: Any): Boolean = rootView === sourceView
}

/**
 * Secure WebView configuration shared by service pages. There is deliberately
 * no JavaScript bridge: pages communicate through normal navigation and form
 * submits, while navigation is constrained to configured HTTP(S) origins. Android
 * WebView persists cookies by host, so the LAN and WAN endpoints remain
 * independent when they use their configured distinct hosts/IPs.
 */
class SecureWebViewController(
    private val externalLinkHandler: ExternalLinkHandler = ExternalLinkHandler { uri ->
        // Default is intentionally a no-op; the app must supply an explicit
        // browser handler instead of silently launching arbitrary intents.
    },
    private val inAppWindowHandler: InAppWindowHandler? = null,
    private val fileChooserHandler: FileChooserHandler? = null,
    private val downloadHandler: DownloadHandler? = null,
    private val eventListener: WebViewEventListener = object : WebViewEventListener {},
    /** Optional local static-asset hook, e.g. [ZashboardAssetLoader]. */
    private val resourceInterceptor: WebResourceInterceptor? = null,
) {
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView, target: WebTarget) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // Keep user-gesture-created target=_blank links in the host app.  The
        // host supplies the child WebView through [InAppWindowHandler].
        // Automatic script pop-ups remain disabled below.
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.safeBrowsingEnabled = true
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        val allowedOrigins = target.resolvedAllowedOrigins()
        webView.webViewClient = Client(allowedOrigins)
        webView.webChromeClient = Chrome(allowedOrigins, target)
        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            val parsed = runCatching { Uri.parse(url) }.getOrNull()
            if (parsed != null && isAllowedWebNavigation(parsed.toString(), allowedOrigins)) {
                downloadHandler?.enqueue(WebDownloadRequest(url, userAgent, contentDisposition, mimeType, contentLength))
            } else if (parsed != null) {
                openExternalIfSafe(webView, parsed)
            }
        })
    }

    fun load(webView: WebView, target: WebTarget) {
        val uri = runCatching { Uri.parse(target.url) }.getOrNull()
        if (uri == null || !isAllowedOrigin(uri, target.resolvedAllowedOrigins())) {
            eventListener.onPageError(webView, target.url, "仅允许打开已配置的 HTTP/HTTPS 地址")
            return
        }
        webView.loadUrl(uri.toString())
    }

    fun flushCookies() {
        CookieManager.getInstance().flush()
    }

    fun clearSession(
        host: String,
        paths: Set<String> = DEFAULT_SESSION_PATHS,
        callback: (() -> Unit)? = null,
    ) {
        val cookieManager = CookieManager.getInstance()
        // CookieManager has one persistent jar, but cookies are host/path
        // scoped. LuCI may issue a session cookie under /cgi-bin/luci rather
        // than /, so expire names observed at each known route.
        val cookieBase = (if (host.startsWith("https://")) host else "https://$host").trimEnd('/')
        val normalizedPaths = paths.map { path -> if (path.startsWith('/')) path else "/$path" }.toSet()
        normalizedPaths.forEach { path ->
            val cookieUrl = "$cookieBase$path"
            val names = CookieManager.getInstance().getCookie(cookieUrl).orEmpty()
                .split(';')
                .mapNotNull { part -> part.trim().substringBefore('=').takeIf { it.isNotBlank() } }
                .distinct()
            names.forEach { name ->
                cookieManager.setCookie(cookieUrl, "$name=; Max-Age=0; Path=$path")
            }
        }
        cookieManager.flush()
        callback?.invoke()
    }

    private fun openExternalIfSafe(view: WebView, uri: Uri) {
        if (isSafeExternalScheme(uri.scheme)) {
            externalLinkHandler.open(uri)
        } else {
            // Do not turn javascript:, intent:, file:, content:, or other
            // opaque schemes into arbitrary ACTION_VIEW intents.
            eventListener.onPageError(view, uri.toString(), "已阻止不安全链接类型")
        }
    }

    private inner class Client(private val allowedOrigins: Set<String>) : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            return resourceInterceptor?.intercept(request)
                ?: super.shouldInterceptRequest(view, request)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (isAllowedWebNavigation(uri.toString(), allowedOrigins)) return false
            openExternalIfSafe(view, uri)
            return true
        }

        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return true
            if (isAllowedWebNavigation(uri.toString(), allowedOrigins)) return false
            openExternalIfSafe(view, uri)
            return true
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
            // Never call proceed(): certificate errors are not recoverable by
            // an in-app setting and must not be silently ignored.
            handler.cancel()
            eventListener.onTlsError(view, view.url)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                eventListener.onPageError(view, request.url?.toString(), error.description?.toString())
            }
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: android.webkit.WebResourceResponse,
        ) {
            if (request.isForMainFrame) {
                eventListener.onHttpError(
                    view,
                    request.url?.toString(),
                    errorResponse.statusCode,
                    errorResponse.responseHeaders.orEmpty(),
                )
            }
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: android.webkit.RenderProcessGoneDetail,
        ): Boolean {
            eventListener.onRenderProcessGone(view, detail.didCrash())
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            eventListener.onLoading(view, url, 0)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            eventListener.onLoading(view, url, 100)
        }
    }

    private inner class Chrome(
        private val allowedOrigins: Set<String>,
        private val target: WebTarget,
    ) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            eventListener.onLoading(view, view.url, newProgress)
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            val handler = fileChooserHandler ?: return false
            val capture = fileChooserParams.isCaptureEnabled
            return handler.choose(filePathCallback, fileChooserParams.acceptTypes, capture)
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            // A background/script popup must never create an in-app window.
            // A link opened by the user may create a child WebView.  Its first
            // navigation still goes through the child WebViewClient and the
            // exact-origin allow-list; no origin is widened here.
            if (!isUserGesture) return false
            return inAppWindowHandler?.open(view, target, resultMsg) == true
        }
    }

    companion object {
        val DEFAULT_SESSION_PATHS: Set<String> = setOf(
            "/",
            "/cgi-bin/luci",
            "/cgi-bin/luci/",
            "/cgi-bin/luci/admin",
            "/cgi-bin/luci/admin/istore",
        )

        /** Exact HTTP(S) origin match. Scheme, host and effective port matter. */
        fun isAllowedOrigin(uri: Uri, allowedOrigins: Set<String>): Boolean {
            return isAllowedWebOrigin(uri.toString(), allowedOrigins)
        }

        /** JVM-testable form without Android Uri framework stubs. */
        fun isAllowedOrigin(url: String, allowedOrigins: Set<String>): Boolean {
            return isAllowedWebOrigin(url, allowedOrigins)
        }

        /**
         * Exact origin plus the same-host HTTP -> HTTPS redirect exception.
         * The initial configured URL is still checked with [isAllowedOrigin].
         */
        fun isAllowedNavigation(url: String, allowedOrigins: Set<String>): Boolean {
            return isAllowedWebNavigation(url, allowedOrigins)
        }

        /** Only ordinary web URLs may leave the app through ACTION_VIEW. */
        fun isSafeExternalScheme(scheme: String?): Boolean {
            return scheme.equals("http", ignoreCase = true) ||
                scheme.equals("https", ignoreCase = true)
        }

        /** Exact HTTPS origin match retained for callers that require TLS. */
        fun isAllowedHttps(uri: Uri, allowedOrigins: Set<String>): Boolean {
            return uri.scheme.equals("https", ignoreCase = true) && isAllowedWebOrigin(uri.toString(), allowedOrigins)
        }

        /** JVM-testable HTTPS-only form retained for source compatibility. */
        fun isAllowedHttps(url: String, allowedOrigins: Set<String>): Boolean {
            return parseWebUrl(url)?.second?.scheme == "https" && isAllowedWebOrigin(url, allowedOrigins)
        }
    }
}

/** Android 13+ predictive back integration without deprecated onBackPressed(). */
class PredictiveWebBackRegistration private constructor(
    private val unregister: () -> Unit,
) : AutoCloseable {
    override fun close() = unregister()

    companion object {
        fun register(
            activity: Activity,
            webView: WebView,
            onExit: () -> Unit = { activity.finish() },
        ): PredictiveWebBackRegistration? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
            val callback = android.window.OnBackInvokedCallback {
                if (webView.canGoBack()) webView.goBack() else onExit()
            }
            val dispatcher = activity.onBackInvokedDispatcher
            dispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback,
            )
            return PredictiveWebBackRegistration {
                dispatcher.unregisterOnBackInvokedCallback(callback)
            }
        }
    }
}
