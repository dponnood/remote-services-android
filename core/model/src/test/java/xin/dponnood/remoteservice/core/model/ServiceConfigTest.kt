package xin.dponnood.remoteservice.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceConfigTest {
    @Test
    fun defaultsAreStableForPersistence() {
        val config = ServiceConfig(id = "nas", displayName = "NAS")
        assertEquals(0, config.sortOrder)
        assertEquals(null, config.lanUrl)
        assertEquals(null, config.wanUrl)
        assertEquals("service", config.iconKey)
        assertEquals(emptySet<String>(), config.trustedSsids)
        assertEquals(ServiceType.GENERIC, config.serviceType)
        assertFalse(config.authEnabled)
        assertEquals(ConnectionPolicy.AUTO, config.connectionPolicy)
        assertFalse(config.hasEndpoint)
    }

    @Test
    fun groupAndEndpointsAreNormalizedForUi() {
        val config = ServiceConfig(
            id = "router",
            displayName = "Router",
            lanUrl = "https://192.168.1.1",
            group = "  家庭网络  ",
            trustedSsids = setOf("  Home WiFi ", "Guest"),
            serviceType = ServiceType.NAS,
            authEnabled = true,
        )
        assertEquals("家庭网络", config.normalizedGroup)
        assertTrue(config.hasEndpoint)
        val draft = config.toDraft()
        assertEquals(config.id, draft.id)
        assertEquals(config.displayName, draft.displayName)
        assertEquals(config.lanUrl, draft.lanUrl)
        assertEquals("家庭网络", draft.group)
        assertEquals(setOf("Home WiFi", "Guest"), draft.trustedSsids)
        assertEquals(ServiceType.NAS, draft.serviceType)
        assertTrue(draft.authEnabled)
    }

    @Test
    fun trustedSsidInputAcceptsCommonSeparators() {
        assertEquals(
            setOf("Home", "Office"),
            ServiceDraft.parseTrustedSsids(" Home,\nOffice; Home "),
        )
    }

    @Test
    fun embeddedUrlCredentialsAreDetectedWithoutRejectingQueries() {
        assertTrue(urlContainsUserInfo("https://router:secret@router.example/cgi-bin/luci"))
        assertTrue(urlContainsUserInfo("http://user%40home:secret@192.168.1.1/"))
        assertTrue(urlContainsUserInfo("http://user:secret@bad host/path"))
        assertTrue(urlContainsUserInfo("https://user:secret@router.example:bad/admin"))
        assertFalse(urlContainsUserInfo("https://router.example/path?user=alice&token=abc#overview"))
        assertFalse(urlContainsUserInfo("https://router.example/cgi-bin/luci"))
        assertFalse(urlContainsUserInfo("https://bad host/path"))
    }

    @Test
    fun stripsOnlyUrlUserInfoAndPreservesPathQueryAndFragment() {
        assertEquals(
            "https://router.example/cgi-bin/luci?next=%2Fadmin#status",
            stripUrlUserInfo("https://alice:secret@router.example/cgi-bin/luci?next=%2Fadmin#status"),
        )
        assertEquals(
            "http://bad host/path",
            stripUrlUserInfo("http://user:secret@bad host/path"),
        )
        assertEquals(
            "https://router.example:bad/admin",
            stripUrlUserInfo("https://user:secret@router.example:bad/admin"),
        )
        assertEquals(
            "https://router.example/path?user=alice&token=abc#overview",
            stripUrlUserInfo("https://router.example/path?user=alice&token=abc#overview"),
        )
        val atSignsOutsideAuthority = "https://router.example/path@segment?mail=a@b.example#overview"
        assertFalse(urlContainsUserInfo(atSignsOutsideAuthority))
        assertEquals(atSignsOutsideAuthority, stripUrlUserInfo(atSignsOutsideAuthority))
    }

    @Test
    fun draftFromLegacyConfigDoesNotExposeEmbeddedCredentials() {
        val draft = ServiceConfig(
            id = "router",
            displayName = "Router",
            lanUrl = "http://admin:secret@192.168.1.1/cgi-bin/luci",
            wanUrl = "https://router.example/admin?next=%2Fstatus",
        ).toDraft()

        assertEquals("http://192.168.1.1/cgi-bin/luci", draft.lanUrl)
        assertEquals("https://router.example/admin?next=%2Fstatus", draft.wanUrl)
    }

    @Test
    fun serviceDetectsCredentialsInEitherEndpoint() {
        assertTrue(
            ServiceConfig(
                id = "router",
                displayName = "Router",
                wanUrl = "https://alice:secret@router.example",
            ).hasEmbeddedUrlCredentials(),
        )
        assertFalse(
            ServiceConfig(
                id = "router",
                displayName = "Router",
                lanUrl = "http://192.168.1.1/cgi-bin/luci?next=%2Fadmin",
            ).hasEmbeddedUrlCredentials(),
        )
    }
}
