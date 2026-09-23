package xin.dponnood.remoteservice.core.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import xin.dponnood.remoteservice.core.model.ServiceConfig
import xin.dponnood.remoteservice.core.model.hasEmbeddedUrlCredentials

/** Deterministic store used by previews and plain-JVM presenter tests. */
class InMemoryServiceConfigStore(initial: List<ServiceConfig> = emptyList()) : ServiceConfigStore {
    private val _services = MutableStateFlow(normalize(initial))
    override val services: Flow<List<ServiceConfig>> = _services.asStateFlow()

    override suspend fun upsert(config: ServiceConfig) {
        require(!config.hasEmbeddedUrlCredentials()) {
            "Service endpoint must not contain embedded credentials"
        }
        _services.update { current ->
            val next = current.toMutableList()
            val index = next.indexOfFirst { it.id == config.id }
            if (index >= 0) next[index] = config else next += config
            normalize(next)
        }
    }

    override suspend fun delete(id: String) {
        _services.update { current -> normalize(current.filterNot { it.id == id }) }
    }

    override suspend fun reorder(orderedIds: List<String>) {
        _services.update { current ->
            val byId = current.associateBy { it.id }
            val ordered = orderedIds.mapNotNull(byId::get)
            normalize(ordered + current.filterNot { it.id in orderedIds })
        }
    }

    private fun normalize(values: List<ServiceConfig>): List<ServiceConfig> = values
        .distinctBy { it.id }
        .mapIndexed { index, config -> config.copy(sortOrder = index) }
}
