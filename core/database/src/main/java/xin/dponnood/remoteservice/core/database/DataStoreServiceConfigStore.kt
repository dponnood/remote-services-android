package xin.dponnood.remoteservice.core.database

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.Base64
import xin.dponnood.remoteservice.core.model.ServiceConfig
import xin.dponnood.remoteservice.core.model.ConnectionPolicy
import xin.dponnood.remoteservice.core.model.ServiceType
import xin.dponnood.remoteservice.core.model.hasEmbeddedUrlCredentials

private val servicesKey = stringPreferencesKey("services_v1")

/**
 * DataStore instances are process-scoped resources.  Keeping the registry here
 * prevents an Activity recreation (or a second Activity instance during an
 * install/restore transition) from opening the same preferences file twice.
 * DataStore rejects that situation at the first write, which otherwise makes
 * the editor appear to ignore the Save action.
 */
private val serviceDataStores = ConcurrentHashMap<String, DataStore<Preferences>>()

    /** Creates the app's private DataStore file without leaking an Activity context. */
fun createServiceDataStore(
    context: Context,
    fileName: String = "remote_services.preferences_pb",
): DataStore<Preferences> {
    val file = context.applicationContext.preferencesDataStoreFile(fileName)
    return serviceDataStores.computeIfAbsent(file.absolutePath) {
        PreferenceDataStoreFactory.create { file }
    }
}

/**
 * DataStore-backed service catalogue. Each field is Base64 encoded before being
 * placed in one preference so arbitrary Unicode and URL characters round-trip
 * without introducing a JSON parser dependency in the storage module.
 */
class DataStoreServiceConfigStore(
    private val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ServiceConfigStore {
    override val services: Flow<List<ServiceConfig>> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { decode(it[servicesKey].orEmpty()) }

    override suspend fun upsert(config: ServiceConfig) {
        require(config.id.isNotBlank()) { "Service id must not be blank" }
        require(config.displayName.isNotBlank()) { "Service display name must not be blank" }
        require(!config.hasEmbeddedUrlCredentials()) {
            "Service endpoint must not contain embedded credentials"
        }
        withContext(ioDispatcher) {
            dataStore.edit { preferences ->
                val current = decode(preferences[servicesKey].orEmpty()).toMutableList()
                val index = current.indexOfFirst { it.id == config.id }
                if (index >= 0) current[index] = config else current += config
                preferences[servicesKey] = encode(current)
            }
        }
    }

    override suspend fun delete(id: String) {
        withContext(ioDispatcher) {
            dataStore.edit { preferences ->
                preferences[servicesKey] = encode(
                    decode(preferences[servicesKey].orEmpty()).filterNot { it.id == id },
                )
            }
        }
    }

    override suspend fun reorder(orderedIds: List<String>) {
        withContext(ioDispatcher) {
            dataStore.edit { preferences ->
                val current = decode(preferences[servicesKey].orEmpty())
                val byId = current.associateBy { it.id }
                val ordered = orderedIds.mapNotNull(byId::get)
                val missing = current.filterNot { it.id in orderedIds }
                preferences[servicesKey] = encode((ordered + missing).mapIndexed { index, item ->
                    item.copy(sortOrder = index)
                })
            }
        }
    }

    companion object {
        internal fun encode(configs: List<ServiceConfig>): String = configs
            .sortedWith(compareBy<ServiceConfig> { it.sortOrder }.thenBy { it.id })
            .joinToString("\n") { config ->
                listOf(
                    config.id,
                    config.displayName,
                    config.lanUrl.orEmpty(),
                    config.wanUrl.orEmpty(),
                    config.group.orEmpty(),
                    config.sortOrder.toString(),
                    config.iconKey,
                    config.trustedSsids
                        .asSequence()
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .sorted()
                        .joinToString(TRUSTED_SSID_SEPARATOR),
                    config.serviceType.name,
                    config.authEnabled.toString(),
                    config.connectionPolicy.name,
                ).joinToString("|", transform = ::encodePart)
            }

        internal fun decode(value: String): List<ServiceConfig> = value
            .lineSequence()
            .mapNotNull { line ->
                runCatching {
                    val fields = line.split('|').map(::decodePart)
                    // Seven-field records are the pre-auth/trusted-SSID format;
                    // defaults keep upgrades backward compatible. The optional
                    // eleventh field is the route policy introduced later.
                    if (fields.size !in 7..11 || fields[0].isBlank() || fields[1].isBlank()) {
                        return@runCatching null
                    }
                    ServiceConfig(
                        id = fields[0],
                        displayName = fields[1],
                        lanUrl = fields[2].takeIf(String::isNotBlank),
                        wanUrl = fields[3].takeIf(String::isNotBlank),
                        group = fields[4].takeIf(String::isNotBlank),
                        sortOrder = fields[5].toIntOrNull() ?: 0,
                        iconKey = fields[6].ifBlank { "service" },
                        trustedSsids = fields.getOrNull(7)
                            .orEmpty()
                            .split(TRUSTED_SSID_SEPARATOR)
                            .asSequence()
                            .map(String::trim)
                            .filter(String::isNotBlank)
                            .toSet(),
                        serviceType = fields.getOrNull(8)
                            ?.let { value -> runCatching { ServiceType.valueOf(value) }.getOrNull() }
                            ?: ServiceType.GENERIC,
                        authEnabled = fields.getOrNull(9)?.equals("true", ignoreCase = true) == true,
                        connectionPolicy = fields.getOrNull(10)
                            ?.let { value -> runCatching { ConnectionPolicy.valueOf(value) }.getOrNull() }
                            ?: ConnectionPolicy.AUTO,
                    )
                }.getOrNull()
            }
            .sortedWith(compareBy<ServiceConfig> { it.sortOrder }.thenBy { it.id })
            .toList()

        private fun encodePart(value: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))

        private fun decodePart(value: String): String = String(
            Base64.getUrlDecoder().decode(value),
            Charsets.UTF_8,
        )

        private const val TRUSTED_SSID_SEPARATOR = "\u001F"
    }
}
