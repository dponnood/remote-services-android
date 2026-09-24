package xin.dponnood.remoteservice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xin.dponnood.remoteservice.core.model.ServiceConfig
import xin.dponnood.remoteservice.core.model.ServiceType
import xin.dponnood.remoteservice.feature.services.SystemInfoProvider
import xin.dponnood.remoteservice.feature.services.SystemInfoResult

class RoutedSystemInfoProviderTest {
    @Test
    fun bothOpenClashServiceTypesUseTheOpenClashProvider() = runBlocking {
        val iStore = RecordingProvider("iStore")
        val openClash = RecordingProvider("OpenClash")
        val router = RoutedSystemInfoProvider(iStore, openClash)

        val zashboardResult = router.load(service(ServiceType.OPENCLASH))
        val managementResult = router.load(service(ServiceType.OPENCLASH_PANEL))

        assertTrue(zashboardResult is SystemInfoResult.Unavailable)
        assertEquals("OpenClash", (zashboardResult as SystemInfoResult.Unavailable).reason)
        assertTrue(managementResult is SystemInfoResult.Unavailable)
        assertEquals("OpenClash", (managementResult as SystemInfoResult.Unavailable).reason)
        assertEquals(2, openClash.callCount)
        assertEquals(0, iStore.callCount)
    }

    private fun service(type: ServiceType) = ServiceConfig(
        id = type.name,
        displayName = type.name,
        serviceType = type,
    )

    private class RecordingProvider(private val result: String) : SystemInfoProvider {
        var callCount = 0

        override suspend fun load(service: ServiceConfig?): SystemInfoResult {
            callCount++
            return SystemInfoResult.Unavailable(result)
        }
    }
}
