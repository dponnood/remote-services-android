package xin.dponnood.remoteservice.core.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xin.dponnood.remoteservice.core.model.ServiceConfig
import xin.dponnood.remoteservice.core.model.ConnectionPolicy
import xin.dponnood.remoteservice.core.model.ServiceType

class ServiceConfigStoreTest {
    @Test
    fun inMemoryStorePersistsCrudAndOrder() = runTest {
        val store = InMemoryServiceConfigStore(
            listOf(
                ServiceConfig("a", "NAS"),
                ServiceConfig("b", "路由器"),
            ),
        )

        store.upsert(ServiceConfig("c", "媒体", group = "家庭"))
        store.reorder(listOf("c", "a", "b"))
        store.delete("b")

        assertEquals(listOf("c", "a"), store.services.first().map(ServiceConfig::id))
        assertEquals(listOf(0, 1), store.services.first().map(ServiceConfig::sortOrder))
    }

    @Test
    fun dataStoreCodecRoundTripsUnicodeAndSeparators() {
        val input = listOf(
            ServiceConfig(
                id = "media|1",
                displayName = "媒体\n服务",
                lanUrl = "https://192.168.1.5:8443/a?x=1|2",
                group = "家庭网络",
                sortOrder = 3,
                iconKey = "server",
                trustedSsids = setOf("Home WiFi", "办公网络"),
                serviceType = ServiceType.OPENCLASH_PANEL,
                authEnabled = true,
                connectionPolicy = ConnectionPolicy.PUBLIC_ONLY,
            ),
        )
        val encoded = DataStoreServiceConfigStore.encode(input)
        assertEquals(input, DataStoreServiceConfigStore.decode(encoded))
    }

    @Test
    fun legacyOpenClashTypeStillRoundTripsAsZashboard() {
        val legacy = ServiceConfig("old-openclash", "Zashboard", serviceType = ServiceType.OPENCLASH)

        assertEquals(legacy, DataStoreServiceConfigStore.decode(DataStoreServiceConfigStore.encode(listOf(legacy))).single())
    }

    @Test
    fun inMemoryStoreRejectsCredentialsEmbeddedInUrl() = runTest {
        val store = InMemoryServiceConfigStore()
        val error = runCatching {
            store.upsert(
                ServiceConfig(
                    id = "router",
                    displayName = "Router",
                    lanUrl = "http://admin:secret@192.168.1.1/cgi-bin/luci",
                ),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("Service endpoint must not contain embedded credentials", error?.message)
        assertEquals(emptyList<ServiceConfig>(), store.services.first())
    }

    @Test
    fun persistentStoreRejectsCredentialsBeforeWritingThem() = runTest {
        val store = DataStoreServiceConfigStore(
            object : DataStore<Preferences> {
                override val data = flowOf(emptyPreferences())

                override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                    throw AssertionError("Credentials must be rejected before DataStore is touched")
                }
            },
        )
        val error = runCatching {
            store.upsert(
                ServiceConfig(
                    id = "router",
                    displayName = "Router",
                    wanUrl = "https://user:secret@router.example/admin",
                ),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("Service endpoint must not contain embedded credentials", error?.message)
    }

    @Test
    fun persistentStoreRejectsCredentialShapedRegistryAuthorityBeforeWriting() = runTest {
        val store = DataStoreServiceConfigStore(
            object : DataStore<Preferences> {
                override val data = flowOf(emptyPreferences())

                override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                    throw AssertionError("Credentials must be rejected before DataStore is touched")
                }
            },
        )
        val error = runCatching {
            store.upsert(
                ServiceConfig(
                    id = "router",
                    displayName = "Router",
                    wanUrl = "https://user:secret@router.example:bad/admin",
                ),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("Service endpoint must not contain embedded credentials", error?.message)
    }

    @Test
    fun inMemoryStoreRejectsCredentialShapedMalformedAuthority() = runTest {
        val store = InMemoryServiceConfigStore()
        val error = runCatching {
            store.upsert(
                ServiceConfig(
                    id = "router",
                    displayName = "Router",
                    wanUrl = "https://user:secret@bad host/admin",
                ),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("Service endpoint must not contain embedded credentials", error?.message)
        assertEquals(emptyList<ServiceConfig>(), store.services.first())
    }
}
