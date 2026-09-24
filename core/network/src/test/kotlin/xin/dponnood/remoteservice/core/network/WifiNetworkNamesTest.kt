package xin.dponnood.remoteservice.core.network

import kotlinx.coroutines.test.runTest
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

    @Test
    fun providerUsesOnlyConfiguredAndCurrentNames_notNearbyScanResults() = runTest {
        val provider = AndroidWifiNetworkNameProvider(
            FakeWifiNetworkNameSource(
                configuredNames = listOf("\"Saved Office\"", "Saved Home"),
                currentName = "Connected Cafe",
            ),
        )

        val result = provider.load()

        assertEquals(listOf("Connected Cafe", "Saved Home", "Saved Office"), result.names)
        assertEquals("Connected Cafe", result.currentSsid)
        assertTrue(result.configuredNetworksReadable)
        // WifiNetworkNameSource has no scan-results method, so visible but
        // unsaved hotspots cannot enter this list.
        assertFalse(result.names.contains("Nearby Only"))
    }

    @Test
    fun providerFallsBackToConfiguredNetworksWhenCurrentSsidIsUnavailable() = runTest {
        val provider = AndroidWifiNetworkNameProvider(
            FakeWifiNetworkNameSource(
                permission = SsidPermissionState.DENIED,
                configuredNames = listOf("Saved Home"),
                currentName = "Must Not Be Read",
            ),
        )

        val result = provider.load()

        assertEquals(listOf("Saved Home"), result.names)
        assertTrue(result.needsPermission)
        assertTrue(result.configuredNetworksReadable)
    }

    @Test
    fun providerFallsBackToCurrentSsidWhenConfiguredNetworksCannotBeRead() = runTest {
        val provider = AndroidWifiNetworkNameProvider(
            FakeWifiNetworkNameSource(
                configuredNamesFailure = IllegalStateException("platform hides configured list"),
                currentName = "Connected Home",
            ),
        )

        val result = provider.load()

        assertEquals(listOf("Connected Home"), result.names)
        assertEquals("Connected Home", result.currentSsid)
        assertFalse(result.configuredNetworksReadable)
    }

    @Test
    fun prioritizeWifiNetworkNames_putsCurrentNetworkFirstAndSortsRemainder() {
        assertEquals(
            listOf("Connected WiFi", "Home", "Office"),
            prioritizeWifiNetworkNames(
                listOf("Office", "Connected WiFi", "Home"),
                "Connected WiFi",
            ),
        )
    }

    @Test
    fun prioritizeWifiNetworkNames_keepsAlphabeticalOrderWhenCurrentIsUnknown() {
        assertEquals(
            listOf("Home", "Office"),
            prioritizeWifiNetworkNames(listOf("Office", "Home"), "Unlisted WiFi"),
        )
    }

    private class FakeWifiNetworkNameSource(
        private val permission: SsidPermissionState = SsidPermissionState.AVAILABLE,
        private val configuredNames: List<String?> = emptyList(),
        private val configuredNamesFailure: Throwable? = null,
        private val currentName: String? = null,
    ) : WifiNetworkNameSource {
        override fun permissionState(): SsidPermissionState = permission

        override fun configuredNetworkNames(): List<String?> {
            configuredNamesFailure?.let { throw it }
            return configuredNames
        }

        override fun currentSsid(): String? = currentName
    }
}
