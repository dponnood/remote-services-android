package xin.dponnood.remoteservice.feature.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xin.dponnood.remoteservice.core.model.ServiceType

class SecureWebViewTest {
    @Test
    fun childWindowCallbacksAreNotAcceptedAsRootWebViewEvents() {
        val rootView = Any()
        val childView = Any()

        assertTrue(RootWebViewEventTarget.accepts(rootView, rootView))
        assertFalse(RootWebViewEventTarget.accepts(rootView, childView))
        assertFalse(RootWebViewEventTarget.accepts(null, childView))
    }

    @Test
    fun onlyConfiguredHttpsHostIsAllowed() {
        val origins = setOf("https://lan.example")
        assertTrue(SecureWebViewController.isAllowedHttps("https://lan.example/path", origins))
        assertTrue(SecureWebViewController.isAllowedHttps("https://lan.example:443/path", origins))
        assertFalse(SecureWebViewController.isAllowedHttps("http://lan.example/path", origins))
        assertFalse(SecureWebViewController.isAllowedHttps("https://lan.example:8443/path", origins))
        assertFalse(SecureWebViewController.isAllowedHttps("https://evil.example/path", origins))
        assertFalse(SecureWebViewController.isAllowedHttps("https://lan.example.evil/path", origins))
    }

    @Test
    fun customHttpsPortMustBeExplicitlyAllowed() {
        val origins = setOf("https://192.168.1.1:8443")
        assertTrue(SecureWebViewController.isAllowedHttps("https://192.168.1.1:8443/cgi-bin/luci", origins))
        assertFalse(SecureWebViewController.isAllowedHttps("https://192.168.1.1/cgi-bin/luci", origins))
    }

    @Test
    fun configuredHttpOriginIsAllowedButNeverMatchesHttpsOrAnotherPort() {
        val origins = setOf("http://192.168.1.1:8080")
        assertTrue(SecureWebViewController.isAllowedOrigin("http://192.168.1.1:8080/cgi-bin/luci", origins))
        assertTrue(SecureWebViewController.isAllowedOrigin("http://192.168.1.1:8080/", origins))
        assertFalse(SecureWebViewController.isAllowedOrigin("https://192.168.1.1:8080/cgi-bin/luci", origins))
        assertFalse(SecureWebViewController.isAllowedOrigin("http://192.168.1.1/cgi-bin/luci", origins))
        assertFalse(SecureWebViewController.isAllowedOrigin("http://192.168.1.2:8080/cgi-bin/luci", origins))
    }

    @Test
    fun defaultHttpPortIsEquivalentToExplicitPort() {
        val origins = setOf("http://router.example")
        assertTrue(SecureWebViewController.isAllowedOrigin("http://router.example:80/path", origins))
        assertFalse(SecureWebViewController.isAllowedOrigin("http://router.example:81/path", origins))
    }

    @Test
    fun sameHostHttpToHttpsRedirectIsAllowedForDefaultPortPair() {
        val origins = setOf("http://router.example")
        assertTrue(
            SecureWebViewController.isAllowedNavigation(
                "https://router.example/cgi-bin/luci/admin",
                origins,
            ),
        )
        assertFalse(
            SecureWebViewController.isAllowedNavigation(
                "https://other.example/cgi-bin/luci/admin",
                origins,
            ),
        )
        assertFalse(
            SecureWebViewController.isAllowedNavigation(
                "https://router.example:8443/cgi-bin/luci/admin",
                origins,
            ),
        )
    }

    @Test
    fun customHttpPortOnlyAllowsHttpsOnTheSamePort() {
        val origins = setOf("http://192.168.1.1:8080")
        assertTrue(
            SecureWebViewController.isAllowedNavigation(
                "https://192.168.1.1:8080/cgi-bin/luci",
                origins,
            ),
        )
        assertFalse(
            SecureWebViewController.isAllowedNavigation(
                "https://192.168.1.1/cgi-bin/luci",
                origins,
            ),
        )
    }

    @Test
    fun httpsConfigurationDoesNotGainHttpDowngradeException() {
        val origins = setOf("https://router.example")
        assertFalse(
            SecureWebViewController.isAllowedNavigation(
                "http://router.example/cgi-bin/luci",
                origins,
            ),
        )
    }

    @Test
    fun onlyHttpAndHttpsMayBeSentToAnExternalBrowser() {
        assertTrue(SecureWebViewController.isSafeExternalScheme("https"))
        assertTrue(SecureWebViewController.isSafeExternalScheme("HTTP"))
        assertFalse(SecureWebViewController.isSafeExternalScheme("javascript"))
        assertFalse(SecureWebViewController.isSafeExternalScheme("intent"))
        assertFalse(SecureWebViewController.isSafeExternalScheme("file"))
        assertFalse(SecureWebViewController.isSafeExternalScheme(null))
    }

    @Test
    fun layoutRepairIsLimitedToRouterServicesAndZeroRoot() {
        assertTrue(WebViewLayoutCompatibility.isRouterService(ServiceType.LUCI))
        assertTrue(WebViewLayoutCompatibility.isRouterService(ServiceType.ISTORE))
        assertTrue(WebViewLayoutCompatibility.isRouterService(ServiceType.OPENCLASH_PANEL))
        assertTrue(WebViewLayoutCompatibility.isRouterService(ServiceType.OPENCLASH))
        assertFalse(WebViewLayoutCompatibility.isRouterService(ServiceType.GENERIC))
        assertFalse(WebViewLayoutCompatibility.isRouterService(ServiceType.NAS))

        assertTrue(
            WebViewLayoutCompatibility.needsRootHeightRepair(
                ServiceType.ISTORE, viewportHeight = 778, htmlHeight = 0, bodyHeight = 0, contentHeight = 0,
            ),
        )
        assertTrue(
            WebViewLayoutCompatibility.needsRootHeightRepair(
                ServiceType.OPENCLASH, viewportHeight = 744, htmlHeight = 744, bodyHeight = 744, contentHeight = 0,
            ),
        )
        assertFalse(
            WebViewLayoutCompatibility.needsRootHeightRepair(
                ServiceType.ISTORE, viewportHeight = 778, htmlHeight = 778, bodyHeight = 778, contentHeight = 778,
            ),
        )
        assertFalse(
            WebViewLayoutCompatibility.needsRootHeightRepair(
                ServiceType.GENERIC, viewportHeight = 778, htmlHeight = 0, bodyHeight = 0, contentHeight = 0,
            ),
        )
        assertFalse(
            WebViewLayoutCompatibility.needsRootHeightRepair(
                ServiceType.ISTORE, viewportHeight = 0, htmlHeight = 0, bodyHeight = 0, contentHeight = 0,
            ),
        )
    }

    @Test
    fun layoutRepairScriptUsesViewportAndNeverReadsCredentials() {
        val script = WebViewLayoutCompatibility.ROOT_HEIGHT_REPAIR_SCRIPT
        assertTrue(script.contains("window.innerHeight"))
        assertTrue(script.contains("#app,#app-content,#main"))
        assertTrue(script.contains("remote-services-layout-compat"))
        assertFalse(script.contains("document.cookie"))
        assertFalse(script.contains("password"))
        assertFalse(script.contains("luci_username"))
    }
}
