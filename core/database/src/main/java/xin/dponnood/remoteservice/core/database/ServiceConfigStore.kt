package xin.dponnood.remoteservice.core.database

import kotlinx.coroutines.flow.Flow
import xin.dponnood.remoteservice.core.model.ServiceConfig

/**
 * Storage boundary for the service catalogue.
 *
 * UI code only observes this stream and emits intents; it never knows whether the
 * backing implementation is DataStore, a test double, or a future Room database.
 */
interface ServiceConfigStore {
    val services: Flow<List<ServiceConfig>>

    suspend fun upsert(config: ServiceConfig)

    suspend fun delete(id: String)

    /** Persists the visible order as one atomic operation. Unknown ids are ignored. */
    suspend fun reorder(orderedIds: List<String>)
}
