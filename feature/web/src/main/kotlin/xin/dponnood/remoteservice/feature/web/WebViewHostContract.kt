package xin.dponnood.remoteservice.feature.web

import android.net.Uri
import android.webkit.ValueCallback

/**
 * Host capabilities supplied by the Compose screen that owns a WebView.
 *
 * The feature module deliberately does not own Activity result launchers or a
 * download service. The screen wires those platform concerns into this
 * contract, while [SecureWebViewController] remains responsible for the
 * exact configured HTTP(S)-origin allow-list before invoking either callback.
 */
interface WebViewHostContract {
    /** Launches ACTION_OPEN_DOCUMENT/GET_CONTENT and returns whether it was scheduled. */
    fun chooseFile(
        callback: ValueCallback<Array<Uri>>?,
        acceptTypes: Array<String>,
        capture: Boolean,
    ): Boolean

    /** Enqueues a download after the controller has checked its exact origin. */
    fun enqueueDownload(request: WebDownloadRequest)

    /** Opens a navigation that is outside the configured service origin. */
    fun openExternal(uri: Uri)
}

/**
 * Adapts the host contract to the callback types consumed by
 * [SecureWebViewController]. Keep one instance per WebView host so callbacks
 * use the same Activity result launcher and download queue.
 */
class WebViewHostHandlers(
    private val host: WebViewHostContract,
) {
    val externalLinkHandler: ExternalLinkHandler = ExternalLinkHandler(host::openExternal)

    val fileChooserHandler: FileChooserHandler = FileChooserHandler { callback, acceptTypes, capture ->
        host.chooseFile(callback, acceptTypes, capture)
    }

    val downloadHandler: DownloadHandler = DownloadHandler { request ->
        host.enqueueDownload(request)
    }
}
