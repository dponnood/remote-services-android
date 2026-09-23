package xin.dponnood.remoteservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IStoreSessionManagerTest {
    @Test
    fun originUsesEffectivePortAndDropsPath() {
        assertEquals(
            "https://router.example",
            IStoreSessionManager.originOf("https://router.example/cgi-bin/luci/")
        )
        assertEquals(
            "http://192.168.1.1:8080",
            IStoreSessionManager.originOf("http://192.168.1.1:8080/cgi-bin/luci")
        )
    }

    @Test
    fun sessionCookieAcceptsLuCiCookieNamesOnly() {
        assertEquals(
            "0123456789abcdef0123456789abcdef",
            IStoreSessionManager.sessionIdFromCookie(
                "foo=bar; sysauth_https=0123456789abcdef0123456789abcdef; Path=/"
            )
        )
        assertNull(IStoreSessionManager.sessionIdFromCookie("sysauth_https=not-a-session"))
        assertNull(IStoreSessionManager.sessionIdFromCookie("foo=bar"))
    }

    @Test
    fun cookieLookupChecksLuCiPathBeforeRootAndSelectsItsSession() {
        assertEquals(
            listOf(
                "https://router.example/cgi-bin/luci/",
                "https://router.example/cgi-bin/luci",
                "https://router.example",
            ),
            sessionCookieLookupUrls("https://router.example"),
        )
        assertEquals(
            "sysauth_https=0123456789abcdef0123456789abcdef",
            selectSessionCookie(
                listOf(
                    "theme=dark",
                    "sysauth_https=0123456789abcdef0123456789abcdef",
                    null,
                ),
            ),
        )
    }

    @Test
    fun openClashCredentialLookupFallsBackToIStoreTotalLoginServiceIds() {
        assertEquals(
            listOf("openclash-id", "__global_istore__", "istore-id", "luci-id"),
            IStoreSessionManager.credentialServiceIdOrder(
                serviceId = "openclash-id",
                globalServiceIds = linkedSetOf("istore-id", "luci-id", "openclash-id", ""),
            ),
        )
    }
}
