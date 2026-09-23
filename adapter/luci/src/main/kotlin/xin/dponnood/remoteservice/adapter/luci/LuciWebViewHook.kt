package xin.dponnood.remoteservice.adapter.luci

import android.webkit.WebView
import xin.dponnood.remoteservice.core.model.ServiceConfig

/**
 * Request data passed by a Compose WebView host after route resolution.
 * [routeKey] must identify the exact LAN or WAN origin selected by the
 * coordinator; it is never inferred from an untrusted DOM value.
 */
data class LuciDomRequest(
    val service: ServiceConfig,
    val baseUrl: String,
    val routeKey: String,
    val pageUrl: String?,
    val httpStatus: Int? = null,
    val loginPath: String = "/cgi-bin/luci",
)

/**
 * Small host adapter for Compose screens. The host evaluates
 * [DOM_SNAPSHOT_SCRIPT] on the already allow-listed WebView and forwards the
 * callback to [onDomSnapshot]. No JavaScript bridge is installed and no page
 * value is accepted as a credential-store key.
 */
class LuciWebViewHook(
    private val authAdapter: LuciAuthAdapter,
) {
    fun onDomSnapshot(
        webView: WebView,
        request: LuciDomRequest,
        evaluateJavascriptResult: String?,
        onSubmitted: (Boolean) -> Unit = {},
    ): Boolean {
        val html = decodeJavascriptResult(evaluateJavascriptResult)
        return authAdapter.autoRelogin(
            webView = webView,
            endpoint = LuciAuthAdapter.endpointFor(request.service, request.baseUrl, request.loginPath),
            routeKey = request.routeKey,
            pageUrl = request.pageUrl,
            htmlSnippet = html,
            httpStatus = request.httpStatus,
            onSubmitted = onSubmitted,
        )
    }

    /** JVM-testable preparation path for hosts that evaluate scripts themselves. */
    fun prepareScript(request: LuciDomRequest, htmlSnippet: String?): String? =
        authAdapter.prepareAutoRelogin(
            endpoint = LuciAuthAdapter.endpointFor(request.service, request.baseUrl, request.loginPath),
            routeKey = request.routeKey,
            pageUrl = request.pageUrl,
            htmlSnippet = htmlSnippet,
            httpStatus = request.httpStatus,
        )

    companion object {
        /** Returns a bounded DOM snapshot; it does not read cookies or credentials. */
        const val DOM_SNAPSHOT_SCRIPT: String =
            "(function(){var root=document.documentElement;return root ? root.outerHTML : '';})();"

        /**
         * Android returns evaluateJavascript strings as JSON-quoted text. This
         * tiny decoder avoids depending on Android's JSON implementation and
         * keeps the detector directly unit-testable on the JVM.
         */
        fun decodeJavascriptResult(encoded: String?): String {
            val value = encoded?.trim().orEmpty()
            if (value.isEmpty() || value == "null") return ""
            if (value.length < 2 || value.first() != '"' || value.last() != '"') return value

            val body = value.substring(1, value.length - 1)
            val result = StringBuilder(body.length)
            var index = 0
            while (index < body.length) {
                val character = body[index++]
                if (character != '\\' || index >= body.length) {
                    result.append(character)
                    continue
                }
                when (val escaped = body[index++]) {
                    '"' -> result.append('"')
                    '\\' -> result.append('\\')
                    '/' -> result.append('/')
                    'b' -> result.append('\b')
                    'f' -> result.append('\u000C')
                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    'u' -> {
                        if (index + 4 <= body.length) {
                            val hex = body.substring(index, index + 4)
                            val codePoint = hex.toIntOrNull(16)
                            if (codePoint != null) {
                                result.append(codePoint.toChar())
                                index += 4
                            } else {
                                result.append('\\').append(escaped)
                            }
                        } else {
                            result.append('\\').append(escaped)
                        }
                    }
                    else -> result.append('\\').append(escaped)
                }
            }
            return result.toString()
        }
    }
}
