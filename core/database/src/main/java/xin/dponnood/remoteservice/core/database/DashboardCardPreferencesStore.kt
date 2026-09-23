package xin.dponnood.remoteservice.core.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Base64

/** Per-service ordered selection of dashboard card IDs. A null value means "use defaults". */
interface DashboardCardPreferencesStore {
    fun observe(serviceId: String): Flow<List<String>?>

    suspend fun save(serviceId: String, orderedCardIds: List<String>)

    suspend fun delete(serviceId: String)
}

/** DataStore implementation kept separate from service definitions for a clear persistence boundary. */
class DataStoreDashboardCardPreferencesStore(
    private val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DashboardCardPreferencesStore {
    override fun observe(serviceId: String): Flow<List<String>?> {
        require(serviceId.isNotBlank()) { "Service id must not be blank" }
        val key = cardOrderKey(serviceId)
        return dataStore.data
            .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
            .map { preferences ->
                if (key in preferences) decode(preferences[key].orEmpty()) else null
            }
    }

    override suspend fun save(serviceId: String, orderedCardIds: List<String>) {
        require(serviceId.isNotBlank()) { "Service id must not be blank" }
        val normalized = orderedCardIds.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        val key = cardOrderKey(serviceId)
        withContext(ioDispatcher) {
            dataStore.edit { preferences -> preferences[key] = encode(normalized) }
        }
    }

    override suspend fun delete(serviceId: String) {
        require(serviceId.isNotBlank()) { "Service id must not be blank" }
        val key = cardOrderKey(serviceId)
        withContext(ioDispatcher) { dataStore.edit { it.remove(key) } }
    }

    private fun cardOrderKey(serviceId: String) = stringPreferencesKey(
        "dashboard_cards_v1_${Base64.getUrlEncoder().withoutPadding().encodeToString(serviceId.toByteArray(Charsets.UTF_8))}",
    )

    private fun encode(ids: List<String>): String = ids.joinToString("\n") {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8))
    }

    private fun decode(value: String): List<String> = value.lineSequence()
        .mapNotNull { encoded ->
            if (encoded.isBlank()) null else runCatching {
                String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
            }.getOrNull()?.takeIf(String::isNotBlank)
        }
        .distinct()
        .toList()
}
