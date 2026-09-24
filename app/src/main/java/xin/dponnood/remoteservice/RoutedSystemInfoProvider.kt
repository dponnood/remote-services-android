package xin.dponnood.remoteservice

import xin.dponnood.remoteservice.core.model.ServiceType
import xin.dponnood.remoteservice.core.model.ServiceConfig
import xin.dponnood.remoteservice.feature.services.SystemInfoProvider
import xin.dponnood.remoteservice.feature.services.SystemInfoResult

/** Routes the native overview to the adapter matching the selected service. */
class RoutedSystemInfoProvider(
    private val iStoreProvider: SystemInfoProvider,
    private val openClashProvider: SystemInfoProvider,
) : SystemInfoProvider {
    override suspend fun load(service: ServiceConfig?): SystemInfoResult = when (service?.serviceType) {
        ServiceType.OPENCLASH, ServiceType.OPENCLASH_PANEL -> openClashProvider.load(service)
        else -> iStoreProvider.load(service)
    }
}
