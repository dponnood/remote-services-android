package xin.dponnood.remoteservice.core.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Preview and test implementation of per-service card layouts. */
class InMemoryDashboardCardPreferencesStore : DashboardCardPreferencesStore {
    private val layouts = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    override fun observe(serviceId: String): Flow<List<String>?> {
        require(serviceId.isNotBlank()) { "Service id must not be blank" }
        return layouts.map { it[serviceId] }
    }

    override suspend fun save(serviceId: String, orderedCardIds: List<String>) {
        require(serviceId.isNotBlank()) { "Service id must not be blank" }
        val normalized = orderedCardIds.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        layouts.update { it + (serviceId to normalized) }
    }

    override suspend fun delete(serviceId: String) {
        require(serviceId.isNotBlank()) { "Service id must not be blank" }
        layouts.update { it - serviceId }
    }
}
