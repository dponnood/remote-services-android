package xin.dponnood.remoteservice.adapter.luci

import android.webkit.WebView
import xin.dponnood.remoteservice.core.model.ServiceConfig
import xin.dponnood.remoteservice.core.model.ServiceType
import xin.dponnood.remoteservice.core.security.CredentialStore
import xin.dponnood.remoteservice.core.security.ServiceCredentials
import java.net.URI
import java.util.Locale

enum class LuciSessionState {
    AUTHENTICATED,
    LOGIN_REQUIRED,
    UNKNOWN,
}

data class LuciEndpoint(
    val serviceId: String,
    val baseUrl: String,
    val loginPath: String = "/cgi-bin/luci",
    /** Only LuCI/iStore services may invoke the credential autofill hook. */
    val serviceType: ServiceType = ServiceType.LUCI,
)

fun interface LuciCredentialsProvider {
    fun get(serviceId: String, routeKey: String): ServiceCredentials?
}

/** Safe LuCI/iStore login detection and narrowly scoped form submission. */
class LuciAuthAdapter(
    private val credentialStore: CredentialStore,
) {
    fun inspect(
        endpoint: LuciEndpoint,
        pageUrl: String?,
        httpStatus: Int? = null,
        htmlSnippet: String? = null,
    ): LuciSessionState {
        return LuciDomDetector.detect(endpoint, pageUrl, httpStatus, htmlSnippet)
    }

    /**
     * Produces the one-shot form script without requiring an Android [WebView].
     * Compose hosts can inspect the DOM asynchronously and then evaluate the
     * returned script on the same WebView. Credentials are read by the exact
     * `(serviceId, routeKey)` pair only after the page and origin checks pass.
     */
    fun prepareAutoRelogin(
        endpoint: LuciEndpoint,
        routeKey: String,
        pageUrl: String?,
        htmlSnippet: String? = null,
        httpStatus: Int? = null,
    ): String? {
        if (routeKey.isBlank()) return null
        // HTTP services remain browseable in the WebView, but saved
        // credentials must never be submitted over a cleartext origin.
        // Keep this guard explicit at the adapter boundary in addition to the
        // detector's exact HTTPS-origin check so future callers cannot weaken
        // the policy by bypassing DOM detection.
        if (pageUrl == null || !isAllowedOrigin(endpoint.baseUrl, pageUrl)) return null
        if (LuciDomDetector.detect(endpoint, pageUrl, httpStatus, htmlSnippet) != LuciSessionState.LOGIN_REQUIRED) {
            return null
        }
        val credentials = credentialStore.get(endpoint.serviceId, routeKey) ?: return null
        return buildLoginScript(credentials)
    }

    /**
     * Submits credentials only to an exact configured HTTPS host. No JavaScript
     * interface is installed; the script is one-shot and returns no page data.
     */
    fun autoRelogin(
        webView: WebView,
        endpoint: LuciEndpoint,
        routeKey: String,
        pageUrl: String?,
        htmlSnippet: String? = null,
        httpStatus: Int? = null,
        onSubmitted: (Boolean) -> Unit = {},
    ): Boolean {
        val script = prepareAutoRelogin(endpoint, routeKey, pageUrl, htmlSnippet, httpStatus) ?: return false
        webView.evaluateJavascript(script) { result ->
            onSubmitted(result == "true")
        }
        return true
    }

    fun buildLoginScript(credentials: ServiceCredentials): String {
        val username = quoteJavaScriptString(credentials.username)
        val password = quoteJavaScriptString(credentials.password)
        return """
            (function() {
              // A LuCI/iStore page may be returned with HTTP 403 while still
              // containing a usable login form.  Keep this submission one-shot
              // for the current document so a rejected login cannot loop.
              if (window.__remoteServicesLoginSubmitted === true) return false;
              const username = document.querySelector('input[name="luci_username"],input[name="username"]');
              const password = document.querySelector('input[name="luci_password"],input[name="password"],input[type="password"]');
              if (!username || !password) return false;
              username.value = $username;
              password.value = $password;
              const form = password.form || username.form;
              if (!form) return false;
              window.__remoteServicesLoginSubmitted = true;
              const submitter = form.querySelector('button[type="submit"],input[type="submit"],button[name="login"],input[name="login"]');
              // requestSubmit/click dispatches the page's normal submit
              // handlers (newer LuCI/iStore builds use them for challenge
              // fields and validation).  Keep a standards-based direct POST
              // fallback for older WebView/HTML implementations.
              if (typeof form.requestSubmit === 'function') {
                if (submitter) form.requestSubmit(submitter); else form.requestSubmit();
              } else if (submitter && typeof submitter.click === 'function') {
                submitter.click();
              } else if (typeof HTMLFormElement !== 'undefined' && HTMLFormElement.prototype.submit) {
                HTMLFormElement.prototype.submit.call(form);
              } else if (typeof form.submit === 'function') {
                form.submit();
              } else {
                window.__remoteServicesLoginSubmitted = false;
                return false;
              }
              return true;
            })();
        """.trimIndent()
    }

    companion object {
        /**
         * Checks the complete HTTPS origin, including the effective port.
         * `https://router` and `https://router:443` are equivalent, while a
         * custom LuCI port must be explicitly configured in [baseUrl].
         */
        fun isAllowedOrigin(baseUrl: String, pageUrl: String): Boolean {
            val base = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: return false
            val page = runCatching { URI(pageUrl.trim()) }.getOrNull() ?: return false
            if (!base.isAbsolute || !page.isAbsolute ||
                !base.scheme.equals("https", ignoreCase = true) ||
                !page.scheme.equals("https", ignoreCase = true) ||
                base.rawUserInfo != null || page.rawUserInfo != null
            ) return false
            val expectedHost = base.host?.lowercase(Locale.US)?.removeSuffix(".") ?: return false
            val actualHost = page.host?.lowercase(Locale.US)?.removeSuffix(".") ?: return false
            val expectedPort = if (base.port == -1) 443 else base.port
            val actualPort = if (page.port == -1) 443 else page.port
            return expectedHost == actualHost && expectedPort == actualPort
        }

        fun endpointFor(
            service: ServiceConfig,
            baseUrl: String,
            loginPath: String = "/cgi-bin/luci",
        ): LuciEndpoint = LuciEndpoint(
            serviceId = service.id,
            baseUrl = baseUrl,
            loginPath = loginPath,
            serviceType = service.serviceType,
        )
    }

    private fun quoteJavaScriptString(value: String): String {
        val result = StringBuilder(value.length + 2).append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> result.append("\\\\")
                '"' -> result.append("\\\"")
                '\b' -> result.append("\\b")
                '\u000C' -> result.append("\\f")
                '\n' -> result.append("\\n")
                '\r' -> result.append("\\r")
                '\t' -> result.append("\\t")
                '\u2028' -> result.append("\\u2028")
                '\u2029' -> result.append("\\u2029")
                else -> if (ch.code < 0x20) {
                    result.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    result.append(ch)
                }
            }
        }
        return result.append('"').toString()
    }
}

/** Pure DOM/origin detector kept free of Android framework types for JVM tests. */
object LuciDomDetector {
    fun detect(
        endpoint: LuciEndpoint,
        pageUrl: String?,
        httpStatus: Int? = null,
        htmlSnippet: String? = null,
    ): LuciSessionState {
        if (
            endpoint.serviceType != ServiceType.LUCI &&
            endpoint.serviceType != ServiceType.ISTORE &&
            endpoint.serviceType != ServiceType.OPENCLASH
        ) {
            return LuciSessionState.UNKNOWN
        }
        val page = pageUrl?.trim()?.takeIf(String::isNotEmpty) ?: return LuciSessionState.UNKNOWN
        if (!LuciAuthAdapter.isAllowedOrigin(endpoint.baseUrl, page)) return LuciSessionState.UNKNOWN
        if (httpStatus == 401 || httpStatus == 403) return LuciSessionState.LOGIN_REQUIRED

        val uri = runCatching { URI(page) }.getOrNull() ?: return LuciSessionState.UNKNOWN
        val path = uri.path.orEmpty().lowercase(Locale.US)
        val html = htmlSnippet.orEmpty().lowercase(Locale.US)
        val loginPath = endpoint.loginPath.lowercase(Locale.US).trimEnd('/').ifEmpty { "/" }
        val loginPathMatch = path == loginPath || path.startsWith("$loginPath/")
        val loginMarkers = html.contains("luci-login") ||
            html.contains("luci_login") ||
            html.contains("name=\"luci_username\"") ||
            html.contains("name='luci_username'") ||
            html.contains("name=\"luci_password\"") ||
            html.contains("name='luci_password'") ||
            html.contains("name=\"password\"") ||
            html.contains("name='password'") ||
            html.contains("type=\"password\"") ||
            html.contains("type='password'") ||
            html.contains("id=\"luci_login\"") ||
            html.contains("id='luci_login'")
        if (loginPathMatch && loginMarkers) return LuciSessionState.LOGIN_REQUIRED
        if (loginMarkers && html.contains("login")) return LuciSessionState.LOGIN_REQUIRED
        return LuciSessionState.AUTHENTICATED
    }
}
