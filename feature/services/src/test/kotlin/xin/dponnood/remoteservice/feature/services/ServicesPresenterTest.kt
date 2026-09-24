package xin.dponnood.remoteservice.feature.services

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xin.dponnood.remoteservice.core.database.InMemoryServiceConfigStore
import xin.dponnood.remoteservice.core.database.ServiceConfigStore
import xin.dponnood.remoteservice.core.model.ServiceConfig
import xin.dponnood.remoteservice.core.model.ServiceDraft
import xin.dponnood.remoteservice.core.model.ServiceType

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ServicesPresenterTest {
    @Test
    fun initialSelectionPrefersIstoreThenLuciThenFirstService() {
        val services = listOf(
            ServiceUiModel(ServiceConfig("nas", "NAS", serviceType = ServiceType.NAS)),
            ServiceUiModel(ServiceConfig("luci", "LuCI", serviceType = ServiceType.LUCI)),
            ServiceUiModel(ServiceConfig("istore", "iStore", serviceType = ServiceType.ISTORE)),
        )

        assertEquals("istore", resolveSelectedServiceId(services, selectedId = null))
        assertEquals("luci", resolveSelectedServiceId(services.take(2), selectedId = null))
        assertEquals("nas", resolveSelectedServiceId(services.take(1), selectedId = null))
    }

    @Test
    fun deletedSelectionFallsBackToPreferredServiceOrFirstRemainingService() {
        val services = listOf(
            ServiceUiModel(ServiceConfig("generic", "普通服务")),
            ServiceUiModel(ServiceConfig("router", "路由器", serviceType = ServiceType.LUCI)),
        )

        assertEquals("router", resolveSelectedServiceId(services, selectedId = "deleted"))
        assertEquals("generic", resolveSelectedServiceId(services.take(1), selectedId = "deleted"))
        assertEquals(null, resolveSelectedServiceId(emptyList(), selectedId = "deleted"))
    }

    @Test
    fun authenticationCleanupWaitsUntilTheUpdatedConfigIsSaved() = runTest {
        val original = ServiceConfig(
            id = "router",
            displayName = "Router",
            lanUrl = "https://router.example",
            authEnabled = true,
        )
        val store = InMemoryServiceConfigStore(listOf(original))
        val cleaned = mutableListOf<ServiceConfig>()
        val presenter = ServicesPresenter(
            store = store,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
            onAuthenticationDisabled = cleaned::add,
        )
        runCurrent()

        presenter.dispatch(ServicesIntent.EditClicked(original.id))
        val invalidDraft = presenter.state.value.editor!!.draft.copy(
            lanUrl = "",
            wanUrl = "",
            authEnabled = false,
        )
        presenter.dispatch(ServicesIntent.DraftChanged(invalidDraft))
        presenter.dispatch(ServicesIntent.SaveClicked)

        assertNotNull(presenter.state.value.editor?.validationError)
        assertTrue(cleaned.isEmpty())

        presenter.dispatch(ServicesIntent.DraftChanged(invalidDraft.copy(lanUrl = original.lanUrl!!)))
        presenter.dispatch(ServicesIntent.SaveClicked)
        advanceUntilIdle()

        assertEquals(listOf(original), cleaned)
        assertTrue(!store.services.first().single().authEnabled)
        presenter.close()
    }

    @Test
    fun authenticationCleanupDoesNotRunWhenStorageWriteFails() = runTest {
        val original = ServiceConfig(
            id = "router",
            displayName = "Router",
            lanUrl = "https://router.example",
            authEnabled = true,
        )
        val cleaned = mutableListOf<ServiceConfig>()
        val presenter = ServicesPresenter(
            store = FailingUpsertServiceConfigStore(original),
            scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
            onAuthenticationDisabled = cleaned::add,
        )
        runCurrent()

        presenter.dispatch(ServicesIntent.EditClicked(original.id))
        presenter.dispatch(
            ServicesIntent.DraftChanged(presenter.state.value.editor!!.draft.copy(authEnabled = false)),
        )
        presenter.dispatch(ServicesIntent.SaveClicked)
        advanceUntilIdle()

        assertTrue(cleaned.isEmpty())
        assertNotNull(presenter.state.value.errorMessage)
        presenter.close()
    }

    @Test
    fun deletingServiceRunsCredentialCleanupAfterStoreDelete() = runTest {
        val service = ServiceConfig(
            id = "router",
            displayName = "Router",
            lanUrl = "https://router.example",
            authEnabled = true,
        )
        val store = InMemoryServiceConfigStore(listOf(service))
        val deleted = mutableListOf<ServiceConfig>()
        val presenter = ServicesPresenter(
            store = store,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
            onServiceDeleted = deleted::add,
        )
        runCurrent()

        presenter.dispatch(ServicesIntent.DeleteClicked(service.id))
        presenter.dispatch(ServicesIntent.ConfirmDelete)
        advanceUntilIdle()

        assertTrue(store.services.first().isEmpty())
        assertEquals(listOf(service), deleted)
        presenter.close()
    }

    @Test
    fun openClashManagementShortcutOpensPreconfiguredEditor() = runTest {
        val presenter = ServicesPresenter(
            InMemoryServiceConfigStore(),
            CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
        )
        runCurrent()

        presenter.dispatch(ServicesIntent.AddOpenClashClicked)

        val draft = presenter.state.value.editor?.draft
        assertEquals("OpenClash 管理", draft?.displayName)
        assertEquals(ServiceType.OPENCLASH_PANEL, draft?.serviceType)
        assertEquals("http://192.168.1.1", draft?.lanUrl)
        assertEquals("https://i.example.com", draft?.wanUrl)
        assertTrue(draft?.authEnabled == true)
        presenter.close()
    }

    @Test
    fun zashboardShortcutOpensPreconfiguredNodeSelectionEditor() = runTest {
        val presenter = ServicesPresenter(
            InMemoryServiceConfigStore(),
            CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
        )
        runCurrent()

        presenter.dispatch(ServicesIntent.AddZashboardClicked)

        val draft = presenter.state.value.editor?.draft
        assertEquals("Zashboard 节点选择", draft?.displayName)
        assertEquals(ServiceType.OPENCLASH, draft?.serviceType)
        assertEquals("http://192.168.1.1", draft?.lanUrl)
        assertEquals("https://i.example.com", draft?.wanUrl)
        assertTrue(draft?.authEnabled == true)
        presenter.close()
    }

    @Test
    fun savesNetworkMetadataWithoutCredentials() = runTest {
        val store = InMemoryServiceConfigStore()
        val presenter = ServicesPresenter(
            store,
            CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
        )
        runCurrent()

        presenter.dispatch(ServicesIntent.AddClicked)
        presenter.dispatch(
            ServicesIntent.DraftChanged(
                ServiceDraft(
                    displayName = "家庭 NAS",
                    lanUrl = "https://192.168.1.10:5001",
                    wanUrl = "https://nas.example.com",
                    trustedSsids = setOf("Home WiFi", "办公网络"),
                    serviceType = ServiceType.NAS,
                    authEnabled = true,
                ),
            ),
        )
        presenter.dispatch(ServicesIntent.SaveClicked)
        advanceUntilIdle()

        val saved = store.services.first().single()
        assertEquals(setOf("Home WiFi", "办公网络"), saved.trustedSsids)
        assertEquals(ServiceType.NAS, saved.serviceType)
        assertTrue(saved.authEnabled)
        presenter.close()
    }

    @Test
    fun invalidUrlStaysInEditorWithValidationMessage() = runTest {
        val presenter = ServicesPresenter(
            InMemoryServiceConfigStore(),
            CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
        )
        runCurrent()
        presenter.dispatch(ServicesIntent.AddClicked)
        presenter.dispatch(
            ServicesIntent.DraftChanged(
                ServiceDraft(displayName = "测试", lanUrl = "https://http://192.168.1.2"),
            ),
        )
        presenter.dispatch(ServicesIntent.SaveClicked)

        assertNotNull(presenter.state.value.editor?.validationError)
        assertTrue(presenter.state.value.editor?.validationError.orEmpty().isNotBlank())
        presenter.close()
    }

    @Test
    fun httpEndpointIsAllowedAndRemainsAFullUrlInStorage() = runTest {
        val store = InMemoryServiceConfigStore()
        val presenter = ServicesPresenter(
            store,
            CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
        )
        runCurrent()
        presenter.dispatch(ServicesIntent.AddClicked)
        presenter.dispatch(
            ServicesIntent.DraftChanged(
                ServiceDraft(
                    displayName = "内网面板",
                    lanUrl = joinEndpointUrl(
                        EndpointFieldState(EndpointScheme.HTTP, "[fd00::1]:8080/ui?next=https://example.com"),
                    ),
                ),
            ),
        )
        presenter.dispatch(ServicesIntent.SaveClicked)
        advanceUntilIdle()

        assertEquals("http://[fd00::1]:8080/ui?next=https://example.com", store.services.first().single().lanUrl)
        assertNull(presenter.state.value.editor)
        presenter.close()
    }

    @Test
    fun dirtyEditorRequiresDiscardConfirmation() = runTest {
        val presenter = ServicesPresenter(
            InMemoryServiceConfigStore(),
            CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
        )
        runCurrent()
        presenter.dispatch(ServicesIntent.AddClicked)
        presenter.dispatch(ServicesIntent.DraftChanged(ServiceDraft(displayName = "未保存")))
        presenter.dispatch(ServicesIntent.DismissEditor)

        assertTrue(presenter.state.value.showDiscardConfirmation)
        assertNotNull(presenter.state.value.editor)
        presenter.dispatch(ServicesIntent.ConfirmDiscard)
        assertNull(presenter.state.value.editor)
        presenter.close()
    }

    @Test
    fun moveWithinGroupPersistsOrder() = runTest {
        val store = InMemoryServiceConfigStore(
            listOf(
                ServiceConfig("a", "A", group = "家庭"),
                ServiceConfig("b", "B", group = "家庭"),
                ServiceConfig("c", "C", group = "其他"),
            ),
        )
        val presenter = ServicesPresenter(
            store,
            CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
        )
        runCurrent()
        presenter.dispatch(ServicesIntent.MoveWithinGroup("b", -1))
        advanceUntilIdle()

        assertEquals(listOf("b", "a", "c"), store.services.first().map(ServiceConfig::id))
        presenter.close()
    }
}

private class FailingUpsertServiceConfigStore(
    initial: ServiceConfig,
) : ServiceConfigStore {
    override val services = flowOf(listOf(initial))

    override suspend fun upsert(config: ServiceConfig) {
        error("simulated storage failure")
    }

    override suspend fun delete(id: String) = Unit

    override suspend fun reorder(orderedIds: List<String>) = Unit
}
