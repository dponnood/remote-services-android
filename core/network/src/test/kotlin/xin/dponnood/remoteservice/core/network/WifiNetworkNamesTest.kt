package xin.dponnood.remoteservice.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiNetworkNamesTest {
    @Test
    fun normalizeWifiNetworkNames_removesQuotesUnknownAndDuplicates() {
        assertEquals(
            listOf("Home", "Office"),
            normalizeWifiNetworkNames(
                listOf("\"Office\"", "Home", "<unknown ssid>", " Home ", null, "Office"),
            ),
        )
    }

    @Test
    fun deniedResult_keepsManualEntryPathExplicit() {
        val result = WifiNetworkNameResult(permissionState = SsidPermissionState.DENIED)

        assertTrue(result.needsPermission)
        assertTrue(result.names.isEmpty())
    }

    @Test
    fun availableResult_canContainNoConfiguredNetworks() {
        val result = WifiNetworkNameResult(
            permissionState = SsidPermissionState.AVAILABLE,
            configuredNetworksReadable = true,
        )

        assertFalse(result.needsPermission)
        assertTrue(result.names.isEmpty())
        assertTrue(result.configuredNetworksReadable)
    }
}
