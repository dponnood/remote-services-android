package xin.dponnood.remoteservice.core.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardCardPreferencesStoreTest {
    @Test
    fun layoutsAreIsolatedAndEmptyLayoutIsDifferentFromDefaults() = runTest {
        val store = DataStoreDashboardCardPreferencesStore(FakePreferencesDataStore())

        assertNull(store.observe("router/a").first())
        store.save("router/a", listOf("HOST", "MEMORY", "HOST", " "))
        store.save("router-b", listOf("UPTIME"))

        assertEquals(listOf("HOST", "MEMORY"), store.observe("router/a").first())
        assertEquals(listOf("UPTIME"), store.observe("router-b").first())

        store.save("router/a", emptyList())
        assertEquals(emptyList<String>(), store.observe("router/a").first())
        assertEquals(listOf("UPTIME"), store.observe("router-b").first())

        store.delete("router/a")
        assertNull(store.observe("router/a").first())
    }

    private class FakePreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }
}
