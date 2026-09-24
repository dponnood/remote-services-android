package xin.dponnood.remoteservice.feature.services

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xin.dponnood.remoteservice.core.database.InMemoryServiceConfigStore
import xin.dponnood.remoteservice.core.database.DashboardCardPreferencesStore
import xin.dponnood.remoteservice.core.database.InMemoryDashboardCardPreferencesStore
import xin.dponnood.remoteservice.core.database.ServiceConfigStore
import xin.dponnood.remoteservice.core.designsystem.RemoteServicesMotion
import xin.dponnood.remoteservice.core.designsystem.rememberReduceMotion
import xin.dponnood.remoteservice.core.model.ServiceConfig
import xin.dponnood.remoteservice.core.model.urlContainsUserInfo
import xin.dponnood.remoteservice.core.model.ConnectionPolicy
import xin.dponnood.remoteservice.core.model.ServiceDraft
import xin.dponnood.remoteservice.core.model.ServiceType
import xin.dponnood.remoteservice.core.model.hasEmbeddedUrlCredentials
import xin.dponnood.remoteservice.core.model.stripUrlUserInfo
import xin.dponnood.remoteservice.core.network.WifiNetworkNameProvider
import xin.dponnood.remoteservice.core.network.WifiNetworkNameResult
import xin.dponnood.remoteservice.core.network.prioritizeWifiNetworkNames

private val serviceDraftSaver = Saver<ServiceDraft?, List<Any?>>(
    save = { draft ->
        if (draft == null) {
            listOf(false)
        } else {
            listOf(
                true,
                draft.id,
                draft.displayName,
                stripUrlUserInfo(draft.lanUrl).orEmpty(),
                stripUrlUserInfo(draft.wanUrl).orEmpty(),
                draft.group,
                draft.iconKey,
                draft.trustedSsids.toList(),
                draft.serviceType.name,
                draft.authEnabled,
                draft.connectionPolicy.name,
            )
        }
    },
    restore = { values ->
        if (values.firstOrNull() != true) {
            null
        } else {
            ServiceDraft(
                id = values.getOrNull(1) as? String,
                displayName = values.getOrNull(2) as? String ?: "",
                lanUrl = stripUrlUserInfo(values.getOrNull(3) as? String).orEmpty(),
                wanUrl = stripUrlUserInfo(values.getOrNull(4) as? String).orEmpty(),
                group = values.getOrNull(5) as? String ?: "",
                iconKey = values.getOrNull(6) as? String ?: "service",
                trustedSsids = (values.getOrNull(7) as? List<*>)
                    ?.filterIsInstance<String>()
                    ?.toSet()
                    .orEmpty(),
                serviceType = (values.getOrNull(8) as? String)
                    ?.let { runCatching { ServiceType.valueOf(it) }.getOrNull() }
                    ?: ServiceType.GENERIC,
                authEnabled = values.getOrNull(9) as? Boolean ?: false,
                connectionPolicy = (values.getOrNull(10) as? String)
                    ?.let { runCatching { ConnectionPolicy.valueOf(it) }.getOrNull() }
                    ?: ConnectionPolicy.AUTO,
            )
        }
    },
)

private data class ServiceIconChoice(
    val key: String,
    val label: String,
    val icon: ImageVector,
)

private val serviceIconChoices = listOf(
    ServiceIconChoice("service", "通用", Icons.Outlined.Language),
    ServiceIconChoice("router", "路由器", Icons.Outlined.Lan),
    ServiceIconChoice("home", "家庭", Icons.Outlined.Home),
    ServiceIconChoice("nas", "NAS", Icons.Outlined.Storage),
    ServiceIconChoice("cloud", "云服务", Icons.Outlined.Cloud),
    ServiceIconChoice("web", "网页", Icons.Outlined.Language),
    ServiceIconChoice("download", "下载", Icons.Outlined.Download),
    ServiceIconChoice("movie", "影音", Icons.Outlined.Movie),
    ServiceIconChoice("photo", "相册", Icons.Outlined.Photo),
    ServiceIconChoice("music", "音乐", Icons.Outlined.MusicNote),
    ServiceIconChoice("camera", "摄像头", Icons.Outlined.CameraAlt),
    ServiceIconChoice("code", "开发", Icons.Outlined.Code),
    ServiceIconChoice("security", "安全", Icons.Outlined.Security),
    ServiceIconChoice("database", "数据库", Icons.Outlined.Storage),
    ServiceIconChoice("container", "容器", Icons.Outlined.Dns),
    ServiceIconChoice("settings", "管理", Icons.Outlined.Settings),
)

private fun serviceIcon(iconKey: String): ImageVector =
    serviceIconChoices.firstOrNull { it.key == iconKey }?.icon ?: Icons.Outlined.Language

/** Main services catalogue surface. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServicesScreen(
    store: ServiceConfigStore,
    modifier: Modifier = Modifier,
    restoredSelectedServiceId: String? = null,
    onSelectedServiceIdChanged: (String?) -> Unit = {},
    dashboardCardPreferencesStore: DashboardCardPreferencesStore? = null,
    onOpenService: (ServiceConfig) -> Unit = {},
    /** Opens the app-owned credential editor; plaintext never enters this feature state. */
    onManageCredentials: (ServiceConfig) -> Unit = {},
    /** Removes app-owned credentials when an existing service disables authentication. */
    onAuthenticationDisabled: (ServiceConfig) -> Unit = {},
    /** Removes app-owned credentials after a service has been deleted. */
    onServiceDeleted: (ServiceConfig) -> Unit = {},
    onCheckUpdates: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    /** Best-effort platform Wi-Fi suggestions; manual SSID entry remains available. */
    wifiNetworkNameProvider: WifiNetworkNameProvider? = null,
    /** Increment after a runtime permission result to refresh the suggestion list. */
    wifiPermissionRefreshToken: Int = 0,
    onRequestWifiPermissions: () -> Unit = {},
    /** Optional remote system-info adapter; the default stays honest until one is wired. */
    systemInfoProvider: SystemInfoProvider = UnavailableSystemInfoProvider,
    /** Runtime-only Mihomo node switching; null keeps controls hidden. */
    openClashNodeSwitcher: OpenClashNodeSwitcher? = null,
    /** Explicit per-node delay probing; kept separate from periodic status refresh. */
    openClashNodeLatencyTester: OpenClashNodeLatencyTester? = null,
) {
    val authenticationDisabledHandler = rememberUpdatedState(onAuthenticationDisabled)
    val serviceDeletedHandler = rememberUpdatedState(onServiceDeleted)
    val presenter = remember(store) {
        ServicesPresenter(
            store = store,
            onAuthenticationDisabled = { authenticationDisabledHandler.value(it) },
            onServiceDeleted = { serviceDeletedHandler.value(it) },
        )
    }
    val state by presenter.state.collectAsState()
    var savedSelectedServiceId by rememberSaveable { mutableStateOf(restoredSelectedServiceId) }
    var sidebarVisible by rememberSaveable { mutableStateOf(true) }
    val selectedServiceId = resolveSelectedServiceId(state.services, savedSelectedServiceId)
    val selectedServiceIdState = rememberUpdatedState(selectedServiceId)
    val selectedService = state.services.firstOrNull { it.config.id == selectedServiceId }
    LaunchedEffect(state.services, state.isLoading, savedSelectedServiceId) {
        // Wait for the store's first emission before replacing a restored id.
        if (!state.isLoading) {
            val resolvedId = resolveSelectedServiceId(state.services, savedSelectedServiceId)
            if (resolvedId != savedSelectedServiceId) savedSelectedServiceId = resolvedId
            if (resolvedId != restoredSelectedServiceId) onSelectedServiceIdChanged(resolvedId)
        }
    }
    LaunchedEffect(restoredSelectedServiceId) {
        if (restoredSelectedServiceId != savedSelectedServiceId) {
            savedSelectedServiceId = restoredSelectedServiceId
        }
    }
    val dashboardService = selectedService?.config?.takeIf { it.serviceType.usesSystemDashboard() }
    val dashboardPresenter = remember(systemInfoProvider, dashboardService) {
        SystemDashboardPresenter(systemInfoProvider, service = dashboardService)
    }
    val dashboardState by dashboardPresenter.state.collectAsState()
    val cardPreferencesStore = remember(dashboardCardPreferencesStore) {
        dashboardCardPreferencesStore ?: InMemoryDashboardCardPreferencesStore()
    }
    var savedDashboardCardIds by remember(selectedServiceId) { mutableStateOf<List<String>?>(null) }
    var dashboardCardPreferencesLoaded by remember(selectedServiceId) { mutableStateOf(false) }
    LaunchedEffect(cardPreferencesStore, selectedServiceId) {
        val id = selectedServiceId ?: return@LaunchedEffect
        cardPreferencesStore.observe(id).collect { orderedIds ->
            savedDashboardCardIds = orderedIds
            dashboardCardPreferencesLoaded = true
        }
    }
    val dashboardCardOrder = remember(
        dashboardService?.serviceType,
        savedDashboardCardIds,
        dashboardState.cardCatalogue,
    ) {
        dashboardService?.let {
            resolveDashboardCardOrder(it.serviceType, savedDashboardCardIds, dashboardState.cardCatalogue)
        }.orEmpty()
    }
    val dashboardCards = remember(
        dashboardCardOrder,
        dashboardState.cardCatalogue,
        dashboardState.isLoading,
        dashboardState.hasLoaded,
    ) {
        val availableModels = dashboardState.cardCatalogue.associate { it.model.id to it.model }
        dashboardCardOrder.mapNotNull { id ->
            if (dashboardState.isLoading && !dashboardState.hasLoaded) {
                DashboardCardModel.loadingCard(id)
            } else {
                availableModels[id]
            }
        }
    }
    val reduceMotion = rememberReduceMotion()
    var editingDashboardCards by rememberSaveable(selectedServiceId) { mutableStateOf(false) }
    var editingDashboardCardIds by rememberSaveable(selectedServiceId) { mutableStateOf(emptyList<String>()) }
    var cardRepositoryOpen by rememberSaveable(selectedServiceId) { mutableStateOf(false) }
    var nodeSelectorSheetOpen by rememberSaveable(selectedServiceId) { mutableStateOf(false) }
    val secondarySheetOpen = cardRepositoryOpen || state.editor != null || nodeSelectorSheetOpen
    val repositoryBackdropBlur by animateDpAsState(
        targetValue = if (secondarySheetOpen) 16.dp else 0.dp,
        animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 0.86f, stiffness = 420f),
        label = "card-repository-backdrop-blur",
    )
    var cardLayoutSaveError by rememberSaveable(selectedServiceId) { mutableStateOf<String?>(null) }
    var savedEditorOpen by rememberSaveable { mutableStateOf(false) }
    var savedEditorId by rememberSaveable { mutableStateOf<String?>(null) }
    var savedDraft by rememberSaveable(stateSaver = serviceDraftSaver) {
        mutableStateOf<ServiceDraft?>(null)
    }
    var restoreAttempted by remember { mutableStateOf(false) }
    var userOpenedEditor by remember { mutableStateOf(false) }
    var wifiNamesResult by remember { mutableStateOf(WifiNetworkNameResult()) }
    var wifiNamesLoading by remember { mutableStateOf(false) }
    val wifiNamesScope = rememberCoroutineScope()
    val dashboardScope = rememberCoroutineScope()
    var switchingNodeGroup by remember(selectedServiceId) { mutableStateOf<String?>(null) }
    var nodeSwitchFeedback by remember(selectedServiceId) { mutableStateOf<String?>(null) }
    var testingNode by remember(selectedServiceId) { mutableStateOf<OpenClashNodeLatencyKey?>(null) }
    var latencyBatchProgress by remember(selectedServiceId) {
        mutableStateOf<OpenClashLatencyBatchProgress?>(null)
    }
    var latencyBatchJob by remember(selectedServiceId) { mutableStateOf<Job?>(null) }
    val nodeLatencyResults = remember(selectedServiceId) {
        mutableStateMapOf<OpenClashNodeLatencyKey, OpenClashNodeLatencyDisplay>()
    }
    val editorKey = state.editor?.let { it.original?.id ?: "new" }

    fun switchOpenClashNode(groupName: String, nodeName: String) {
        val service = selectedService?.config ?: return
        val switcher = openClashNodeSwitcher ?: return
        if (switchingNodeGroup != null) return
        switchingNodeGroup = groupName
        nodeSwitchFeedback = "正在切换节点…"
        dashboardScope.launch {
            val result = try {
                switcher.selectNode(service, groupName, nodeName)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                OpenClashNodeSwitchResult.Failure("节点切换失败，请稍后重试")
            }
            switchingNodeGroup = null
            if (selectedServiceId == service.id) {
                nodeSwitchFeedback = when (result) {
                    OpenClashNodeSwitchResult.Success -> "已切换至 $nodeName"
                    is OpenClashNodeSwitchResult.Failure -> result.message
                }
                if (result == OpenClashNodeSwitchResult.Success) dashboardPresenter.refresh()
            }
        }
    }

    fun testOpenClashNode(groupName: String, nodeName: String) {
        val service = selectedService?.config ?: return
        val tester = openClashNodeLatencyTester ?: return
        if (testingNode != null || switchingNodeGroup != null || latencyBatchProgress != null) return
        val key = OpenClashNodeLatencyKey(groupName, nodeName)
        testingNode = key
        nodeLatencyResults[key] = OpenClashNodeLatencyDisplay.Testing
        dashboardScope.launch {
            val result = try {
                tester.testNodeLatency(service, groupName, nodeName)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                OpenClashNodeLatencyResult.Failure("延迟检测失败，请稍后重试")
            }
            if (selectedServiceIdState.value != service.id) return@launch
            testingNode = null
            nodeLatencyResults[key] = result.toLatencyDisplay()
        }
    }

    fun testOpenClashNodeBatch(
        targets: List<OpenClashNodeLatencyTarget>,
        label: String,
        groupName: String? = null,
    ) {
        val service = selectedService?.config ?: return
        val tester = openClashNodeLatencyTester ?: return
        val uniqueTargets = targets.distinct()
        if (uniqueTargets.isEmpty() || testingNode != null || switchingNodeGroup != null ||
            latencyBatchProgress != null
        ) return

        latencyBatchProgress = OpenClashLatencyBatchProgress(
            label = label,
            groupName = groupName,
            total = uniqueTargets.size,
        )
        latencyBatchJob = dashboardScope.launch {
            var successfulTests = 0
            try {
                tester.testNodeLatencies(
                    service = service,
                    targets = uniqueTargets,
                    onNodeTesting = { target ->
                        if (selectedServiceIdState.value != service.id) {
                            throw CancellationException("Selected service changed")
                        }
                        val key = OpenClashNodeLatencyKey(target.groupName, target.nodeName)
                        testingNode = key
                        nodeLatencyResults[key] = OpenClashNodeLatencyDisplay.Testing
                        latencyBatchProgress = latencyBatchProgress?.copy(
                            activeNodeName = target.nodeName,
                        )
                    },
                    onNodeResult = { target, result ->
                        if (selectedServiceIdState.value != service.id) {
                            throw CancellationException("Selected service changed")
                        }
                        val key = OpenClashNodeLatencyKey(target.groupName, target.nodeName)
                        testingNode = null
                        nodeLatencyResults[key] = result.toLatencyDisplay()
                        if (result is OpenClashNodeLatencyResult.Success) successfulTests++
                        latencyBatchProgress = latencyBatchProgress?.copy(
                            completed = (latencyBatchProgress?.completed ?: 0) + 1,
                            activeNodeName = null,
                        )
                    },
                )
                if (selectedServiceIdState.value == service.id) {
                    nodeSwitchFeedback = "${label}完成：成功 $successfulTests/${uniqueTargets.size} 个节点"
                }
            } catch (cancelled: CancellationException) {
                if (selectedServiceIdState.value == service.id) nodeSwitchFeedback = "测速已取消"
                throw cancelled
            } finally {
                if (selectedServiceIdState.value == service.id) {
                    testingNode?.let { key ->
                        nodeLatencyResults[key] = OpenClashNodeLatencyDisplay.Failure("已取消")
                    }
                    testingNode = null
                    latencyBatchProgress = null
                    latencyBatchJob = null
                }
            }
        }
    }

    fun testOpenClashGroup(groupName: String) {
        val group = prioritizeManualSelectionProxyGroups(dashboardState.selectableGroups)
            .firstOrNull { it.name == groupName } ?: return
        testOpenClashNodeBatch(
            targets = group.candidates.map { node -> OpenClashNodeLatencyTarget(group.name, node) },
            label = "本组测速",
            groupName = group.name,
        )
    }

    fun testAllOpenClashNodes() {
        val orderedGroups = prioritizeManualSelectionProxyGroups(dashboardState.selectableGroups)
        testOpenClashNodeBatch(
            targets = orderedGroups.flatMap { group ->
                group.candidates.map { node -> OpenClashNodeLatencyTarget(group.name, node) }
            },
                label = "总测速",
        )
    }

    fun cancelOpenClashNodeBatch() {
        latencyBatchJob?.cancel()
    }

    fun beginDashboardCardEdit() {
        if (!dashboardCardPreferencesLoaded) return
        editingDashboardCardIds = dashboardCardOrder.map(DashboardCardId::name)
        cardLayoutSaveError = null
        editingDashboardCards = true
    }

    fun moveDashboardCardInEditor(cardId: String, offset: Int) {
        editingDashboardCardIds = moveDashboardCard(editingDashboardCardIds, cardId, offset)
    }

    fun toggleDashboardCard(cardId: String) {
        val item = dashboardState.cardCatalogue.firstOrNull { it.model.id.name == cardId } ?: return
        if (!item.addable) return
        editingDashboardCardIds = if (cardId in editingDashboardCardIds) {
            editingDashboardCardIds - cardId
        } else {
            editingDashboardCardIds + cardId
        }
    }

    fun saveDashboardCardLayout() {
        val serviceId = selectedServiceId ?: return
        val orderedIds = editingDashboardCardIds.toList()
        dashboardScope.launch {
            runCatching {
                cardPreferencesStore.save(serviceId, orderedIds)
            }.onSuccess {
                if (selectedServiceIdState.value == serviceId) {
                    editingDashboardCards = false
                    cardRepositoryOpen = false
                    cardLayoutSaveError = null
                }
            }.onFailure { failure ->
                if (selectedServiceIdState.value == serviceId) {
                    cardLayoutSaveError = failure.message?.takeIf(String::isNotBlank)
                        ?: "布局保存失败，请重试"
                }
            }
        }
    }

    fun refreshWifiNames() {
        val provider = wifiNetworkNameProvider ?: return
        wifiNamesScope.launch {
            wifiNamesLoading = true
            wifiNamesResult = runCatching { provider.load() }
                .getOrElse { WifiNetworkNameResult() }
            wifiNamesLoading = false
        }
    }

    LaunchedEffect(editorKey, wifiPermissionRefreshToken) {
        if (editorKey != null) {
            refreshWifiNames()
        } else {
            wifiNamesResult = WifiNetworkNameResult()
        }
    }

    val dispatchIntent: (ServicesIntent) -> Unit = { intent ->
        if (intent is ServicesIntent.AddClicked || intent is ServicesIntent.EditClicked) {
            userOpenedEditor = true
        }
        presenter.dispatch(intent)
    }

    LaunchedEffect(state.isLoading, savedEditorOpen, savedDraft, userOpenedEditor) {
        if (!state.isLoading && !restoreAttempted && !userOpenedEditor && savedEditorOpen && savedDraft != null) {
            val id = savedEditorId
            if (id != null && state.services.any { it.config.id == id }) {
                presenter.dispatch(ServicesIntent.EditClicked(id))
            } else {
                presenter.dispatch(ServicesIntent.AddClicked)
            }
            presenter.dispatch(ServicesIntent.DraftChanged(savedDraft!!))
            restoreAttempted = true
            userOpenedEditor = true
        }
    }

    LaunchedEffect(state.editor?.draft, state.editor?.original?.id, state.isLoading) {
        val editor = state.editor
        if (editor != null) {
            savedEditorOpen = true
            savedEditorId = editor.original?.id
            savedDraft = editor.draft
        } else if (!state.isLoading && restoreAttempted) {
            savedEditorOpen = false
            savedEditorId = null
            savedDraft = null
            restoreAttempted = false
        }
    }

    DisposableEffect(presenter) {
        onDispose { presenter.close() }
    }
    LifecycleStartEffect(dashboardPresenter, dashboardService?.id) {
        val pollJob = if (dashboardService != null) {
            dashboardPresenter.refresh()
            dashboardScope.launch {
                while (isActive) {
                    delay(DASHBOARD_LIVE_REFRESH_INTERVAL_MILLIS)
                    dashboardPresenter.refresh()
                }
            }
        } else {
            null
        }
        onStopOrDispose { pollJob?.cancel() }
    }
    DisposableEffect(dashboardPresenter) {
        onDispose { dashboardPresenter.close() }
    }
    BackHandler(enabled = state.editor != null) {
        dispatchIntent(ServicesIntent.BackRequested)
    }
    BackHandler(enabled = editingDashboardCards && state.editor == null) {
        if (cardRepositoryOpen) {
            cardRepositoryOpen = false
        } else {
            editingDashboardCards = false
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize().blur(repositoryBackdropBlur),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("远程服务", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (state.hasServices) "${state.services.size} 个服务" else "添加你的第一个服务",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                            .border(
                                BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                CircleShape,
                            ),
                    ) {
                        Icon(Icons.Outlined.Settings, contentDescription = "设置")
                    }
                    IconButton(
                        onClick = onCheckUpdates,
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                            .border(
                                BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                CircleShape,
                            ),
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "检查更新")
                    }
                    IconButton(
                        onClick = { dispatchIntent(ServicesIntent.AddClicked) },
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                            .border(
                                BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                CircleShape,
                            ),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = "新增服务")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            ServicesContent(
                state = state,
                selectedServiceId = selectedServiceId,
                onSelectService = {
                    savedSelectedServiceId = it
                    onSelectedServiceIdChanged(it)
                },
                sidebarVisible = sidebarVisible,
                onToggleSidebar = { sidebarVisible = it },
                dashboardState = dashboardState,
                dashboardCards = dashboardCards,
                dashboardCardPreferencesLoaded = dashboardCardPreferencesLoaded,
                editingDashboardCards = editingDashboardCards,
                editingDashboardCardIds = editingDashboardCardIds,
                cardRepositoryOpen = cardRepositoryOpen,
                cardLayoutSaveError = cardLayoutSaveError,
                openClashNodeSwitcher = openClashNodeSwitcher,
                openClashNodeLatencyTester = openClashNodeLatencyTester,
                switchingNodeGroup = switchingNodeGroup,
                nodeSwitchFeedback = nodeSwitchFeedback,
                testingNode = testingNode,
                nodeLatencyResults = nodeLatencyResults,
                latencyBatchProgress = latencyBatchProgress,
                onSwitchOpenClashNode = ::switchOpenClashNode,
                onTestOpenClashNode = ::testOpenClashNode,
                onTestOpenClashGroup = ::testOpenClashGroup,
                onTestAllOpenClashNodes = ::testAllOpenClashNodes,
                onCancelOpenClashNodeBatch = ::cancelOpenClashNodeBatch,
                onOpenClashSelectorSheetChange = { nodeSelectorSheetOpen = it },
                liveMonitoring = dashboardService != null,
                reduceMotion = reduceMotion,
                onIntent = dispatchIntent,
                onOpenService = onOpenService,
                onRefreshDashboard = dashboardPresenter::refresh,
                onBeginCardEdit = ::beginDashboardCardEdit,
                onCancelCardEdit = {
                    editingDashboardCards = false
                    cardRepositoryOpen = false
                    cardLayoutSaveError = null
                },
                onSaveCardLayout = ::saveDashboardCardLayout,
                onOpenCardRepository = { cardRepositoryOpen = true },
                onMoveCard = ::moveDashboardCardInEditor,
                onRemoveCard = { cardId -> editingDashboardCardIds = editingDashboardCardIds - cardId },
                onToggleCard = ::toggleDashboardCard,
                onDismissCardRepository = { cardRepositoryOpen = false },
            )
            if (state.errorMessage != null) {
                AssistChip(
                    onClick = { dispatchIntent(ServicesIntent.ClearError) },
                    label = { Text(state.errorMessage.orEmpty()) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                )
            }
        }
    }

    state.editor?.let { editor ->
        ServiceEditorSheet(
            editor = editor,
            reduceMotion = reduceMotion,
            onIntent = dispatchIntent,
            onManageCredentials = onManageCredentials,
            wifiNames = wifiNamesResult,
            wifiNamesLoading = wifiNamesLoading,
            onRefreshWifiNames = ::refreshWifiNames,
            onRequestWifiPermissions = onRequestWifiPermissions,
        )
    }
    state.deleteCandidateId?.let { id ->
        val serviceName = state.services.firstOrNull { it.config.id == id }?.config?.displayName.orEmpty()
        AlertDialog(
            onDismissRequest = { dispatchIntent(ServicesIntent.CancelDelete) },
            title = { Text("删除服务？") },
            text = { Text("将从“远程服务”中移除 $serviceName。此操作不会删除远端数据。") },
            confirmButton = {
                TextButton(onClick = { dispatchIntent(ServicesIntent.ConfirmDelete) }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { dispatchIntent(ServicesIntent.CancelDelete) }) { Text("取消") }
            },
        )
    }
    if (state.showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = { dispatchIntent(ServicesIntent.CancelDiscard) },
            title = { Text("放弃未保存内容？") },
            text = { Text("当前编辑还没有保存，离开后修改会丢失。") },
            confirmButton = {
                TextButton(onClick = { dispatchIntent(ServicesIntent.ConfirmDiscard) }) { Text("放弃") }
            },
            dismissButton = {
                TextButton(onClick = { dispatchIntent(ServicesIntent.CancelDiscard) }) { Text("继续编辑") }
            },
        )
    }
}

/**
 * iStore's public system endpoints are HTTP/ubus reads rather than a stable
 * push/WebSocket feed. Keep the visible dashboard close to real time while
 * avoiding a tight loop that would starve the WebView or router.
 */
private const val DASHBOARD_LIVE_REFRESH_INTERVAL_MILLIS = 2_000L

private fun serviceTypeLabel(type: ServiceType): String = when (type) {
    ServiceType.GENERIC -> "通用服务"
    ServiceType.NAS -> "NAS / 存储"
    ServiceType.LUCI -> "路由器 LuCI"
    ServiceType.ISTORE -> "iStoreOS"
    ServiceType.OPENCLASH_PANEL -> "OpenClash 管理"
    ServiceType.OPENCLASH -> "Zashboard 节点选择"
    ServiceType.DOCKER -> "Docker 容器"
}

private fun ServiceType.usesSystemDashboard(): Boolean = when (this) {
    ServiceType.ISTORE, ServiceType.LUCI, ServiceType.OPENCLASH_PANEL,
    ServiceType.OPENCLASH, ServiceType.DOCKER -> true
    ServiceType.GENERIC, ServiceType.NAS -> false
}

@Composable
private fun ServicesContent(
    state: ServicesUiState,
    selectedServiceId: String?,
    onSelectService: (String) -> Unit,
    sidebarVisible: Boolean,
    onToggleSidebar: (Boolean) -> Unit,
    dashboardState: SystemDashboardUiState,
    dashboardCards: List<DashboardCardModel>,
    dashboardCardPreferencesLoaded: Boolean,
    editingDashboardCards: Boolean,
    editingDashboardCardIds: List<String>,
    cardRepositoryOpen: Boolean,
    cardLayoutSaveError: String?,
    openClashNodeSwitcher: OpenClashNodeSwitcher?,
    openClashNodeLatencyTester: OpenClashNodeLatencyTester?,
    switchingNodeGroup: String?,
    nodeSwitchFeedback: String?,
    testingNode: OpenClashNodeLatencyKey?,
    nodeLatencyResults: Map<OpenClashNodeLatencyKey, OpenClashNodeLatencyDisplay>,
    latencyBatchProgress: OpenClashLatencyBatchProgress?,
    onSwitchOpenClashNode: (String, String) -> Unit,
    onTestOpenClashNode: (String, String) -> Unit,
    onTestOpenClashGroup: (String) -> Unit,
    onTestAllOpenClashNodes: () -> Unit,
    onCancelOpenClashNodeBatch: () -> Unit,
    onOpenClashSelectorSheetChange: (Boolean) -> Unit,
    liveMonitoring: Boolean,
    reduceMotion: Boolean,
    onIntent: (ServicesIntent) -> Unit,
    onOpenService: (ServiceConfig) -> Unit,
    onRefreshDashboard: () -> Unit,
    onBeginCardEdit: () -> Unit,
    onCancelCardEdit: () -> Unit,
    onSaveCardLayout: () -> Unit,
    onOpenCardRepository: () -> Unit,
    onMoveCard: (String, Int) -> Unit,
    onRemoveCard: (String) -> Unit,
    onToggleCard: (String) -> Unit,
    onDismissCardRepository: () -> Unit,
) {
    when {
        state.isLoading -> LoadingServices()
        else -> BoxWithConstraints(Modifier.fillMaxSize()) {
            val expandedSidebar = maxWidth >= 720.dp
            val motionDuration = RemoteServicesMotion.durationMillis(
                reduceMotion,
                RemoteServicesMotion.StandardMillis,
            )
            val selectedService = state.services.firstOrNull { it.config.id == selectedServiceId }
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                AnimatedVisibility(
                    visible = sidebarVisible,
                    enter = expandHorizontally(
                        expandFrom = Alignment.Start,
                        animationSpec = tween(motionDuration),
                    ) + fadeIn(animationSpec = tween(motionDuration)),
                    exit = shrinkHorizontally(
                        shrinkTowards = Alignment.Start,
                        animationSpec = tween(motionDuration),
                    ) + fadeOut(animationSpec = tween(motionDuration)),
                ) {
                    ServicesSidebar(
                        services = state.services,
                        selectedServiceId = selectedServiceId,
                        expanded = expandedSidebar,
                        reduceMotion = reduceMotion,
                        onSelectService = onSelectService,
                        onAddService = { onIntent(ServicesIntent.AddClicked) },
                        onAddOpenClash = { onIntent(ServicesIntent.AddOpenClashClicked) },
                        onAddZashboard = { onIntent(ServicesIntent.AddZashboardClicked) },
                        onHideSidebar = { onToggleSidebar(false) },
                        onMoveService = { id, delta ->
                            onIntent(ServicesIntent.MoveWithinGroup(id, delta))
                        },
                    )
                }
                AnimatedVisibility(
                    visible = !sidebarVisible,
                    enter = expandHorizontally(
                        expandFrom = Alignment.Start,
                        animationSpec = tween(motionDuration),
                    ) + fadeIn(animationSpec = tween(motionDuration)),
                    exit = shrinkHorizontally(
                        shrinkTowards = Alignment.Start,
                        animationSpec = tween(motionDuration),
                    ) + fadeOut(animationSpec = tween(motionDuration)),
                ) {
                    SidebarRevealButton(
                        modifier = Modifier.width(48.dp).fillMaxHeight(),
                        onClick = { onToggleSidebar(true) },
                    )
                }
                Spacer(Modifier.width(if (sidebarVisible && expandedSidebar) 12.dp else 8.dp))
                when {
                    selectedService == null -> EmptyServices(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        onAdd = { onIntent(ServicesIntent.AddClicked) },
                    )

                    selectedService.config.serviceType.usesSystemDashboard() -> LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentPadding = PaddingValues(start = 4.dp, top = 4.dp, end = 8.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        item(key = "selected-service-dashboard-${selectedService.config.id}") {
                            DashboardSection(
                                state = dashboardState,
                                cards = dashboardCards,
                                cardCatalogue = dashboardState.cardCatalogue,
                                service = selectedService.config,
                                liveMonitoring = liveMonitoring,
                                reduceMotion = reduceMotion,
                                cardPreferencesLoaded = dashboardCardPreferencesLoaded,
                                editingCards = editingDashboardCards,
                                editingCardIds = editingDashboardCardIds,
                                cardRepositoryOpen = cardRepositoryOpen,
                                cardLayoutSaveError = cardLayoutSaveError,
                                selectableGroups = dashboardState.selectableGroups,
                                nodeSwitchEnabled = openClashNodeSwitcher != null,
                                nodeLatencyEnabled = openClashNodeLatencyTester != null,
                                switchingNodeGroup = switchingNodeGroup,
                                nodeSwitchFeedback = nodeSwitchFeedback,
                                testingNode = testingNode,
                                nodeLatencyResults = nodeLatencyResults,
                                latencyBatchProgress = latencyBatchProgress,
                                onSwitchNode = onSwitchOpenClashNode,
                                onTestNode = onTestOpenClashNode,
                                onTestGroup = onTestOpenClashGroup,
                                onTestAllNodes = onTestAllOpenClashNodes,
                                onCancelBatch = onCancelOpenClashNodeBatch,
                                onSelectorSheetVisibilityChange = onOpenClashSelectorSheetChange,
                                onOpenService = { onOpenService(selectedService.config) },
                                onEdit = { onIntent(ServicesIntent.EditClicked(selectedService.config.id)) },
                                onDelete = { onIntent(ServicesIntent.DeleteClicked(selectedService.config.id)) },
                                onRefresh = onRefreshDashboard,
                                onBeginCardEdit = onBeginCardEdit,
                                onCancelCardEdit = onCancelCardEdit,
                                onSaveCardLayout = onSaveCardLayout,
                                onOpenCardRepository = onOpenCardRepository,
                                onMoveCard = onMoveCard,
                                onRemoveCard = onRemoveCard,
                                onToggleCard = onToggleCard,
                                onDismissCardRepository = onDismissCardRepository,
                            )
                        }
                    }

                    else -> LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentPadding = PaddingValues(start = 4.dp, top = 4.dp, end = 8.dp, bottom = 24.dp),
                    ) {
                        item(key = "selected-service-home-${selectedService.config.id}") {
                            ServiceHomeSection(
                                service = selectedService,
                                reduceMotion = reduceMotion,
                                onOpenService = { onOpenService(selectedService.config) },
                                onEdit = { onIntent(ServicesIntent.EditClicked(selectedService.config.id)) },
                                onDelete = { onIntent(ServicesIntent.DeleteClicked(selectedService.config.id)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardSection(
    state: SystemDashboardUiState,
    cards: List<DashboardCardModel>,
    cardCatalogue: List<DashboardCardCatalogItem>,
    service: ServiceConfig,
    liveMonitoring: Boolean,
    reduceMotion: Boolean,
    cardPreferencesLoaded: Boolean,
    editingCards: Boolean,
    editingCardIds: List<String>,
    cardRepositoryOpen: Boolean,
    cardLayoutSaveError: String?,
    selectableGroups: List<OpenClashProxyGroup>,
    nodeSwitchEnabled: Boolean,
    nodeLatencyEnabled: Boolean,
    switchingNodeGroup: String?,
    nodeSwitchFeedback: String?,
    testingNode: OpenClashNodeLatencyKey?,
    nodeLatencyResults: Map<OpenClashNodeLatencyKey, OpenClashNodeLatencyDisplay>,
    latencyBatchProgress: OpenClashLatencyBatchProgress?,
    onSwitchNode: (String, String) -> Unit,
    onTestNode: (String, String) -> Unit,
    onTestGroup: (String) -> Unit,
    onTestAllNodes: () -> Unit,
    onCancelBatch: () -> Unit,
    onSelectorSheetVisibilityChange: (Boolean) -> Unit,
    onOpenService: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRefresh: () -> Unit,
    onBeginCardEdit: () -> Unit,
    onCancelCardEdit: () -> Unit,
    onSaveCardLayout: () -> Unit,
    onOpenCardRepository: () -> Unit,
    onMoveCard: (String, Int) -> Unit,
    onRemoveCard: (String) -> Unit,
    onToggleCard: (String) -> Unit,
    onDismissCardRepository: () -> Unit,
) {
    val motionDuration = RemoteServicesMotion.durationMillis(
        reduceMotion,
        RemoteServicesMotion.StandardMillis,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(motionDuration)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    when (service.serviceType) {
                        ServiceType.OPENCLASH -> "Zashboard 节点快速选择"
                        ServiceType.OPENCLASH_PANEL -> "OpenClash 运行概览"
                        ServiceType.ISTORE -> "iStoreOS 系统概览"
                        ServiceType.LUCI -> "LuCI 系统概览"
                        ServiceType.DOCKER -> "Docker 容器概览"
                        else -> "系统概览"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = service.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        state.isLoading && liveMonitoring -> "实时监视 · 正在读取系统信息"
                        state.isLoading -> "正在读取系统信息"
                        state.errorMessage != null -> "系统信息暂不可用 · 可重试"
                        liveMonitoring && state.lastUpdatedEpochMillis != null -> "实时监视 · 约 2 秒采样"
                        liveMonitoring -> "实时监视 · 等待系统接口"
                        else -> "仅展示已获取的数据"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onOpenService,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.Lan, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(when (service.serviceType) {
                    ServiceType.OPENCLASH -> "打开完整 Zashboard"
                    ServiceType.OPENCLASH_PANEL -> "打开 OpenClash 管理"
                    ServiceType.DOCKER -> "打开 Docker 管理"
                    else -> "打开网页"
                })
            }
            ServiceActionsMenu(service, onEdit = onEdit, onDelete = onDelete)
            if (!editingCards) {
                IconButton(onClick = onBeginCardEdit, enabled = cardPreferencesLoaded) {
                    Icon(Icons.Outlined.Edit, contentDescription = "编辑本页卡片")
                }
            }
            IconButton(
                onClick = onRefresh,
                enabled = !state.isRefreshing,
            ) {
                if (state.isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新系统信息")
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        androidx.compose.animation.AnimatedVisibility(
            visible = state.errorMessage != null,
            enter = fadeIn(animationSpec = tween(motionDuration)),
            exit = fadeOut(animationSpec = tween(motionDuration)),
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = state.errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cardColumns = when {
                maxWidth >= 840.dp -> 3
                maxWidth >= 600.dp -> 2
                else -> 1
            }
            if (editingCards) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "正在编辑 ${service.displayName} 的卡片（${editingCardIds.size} 项）",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = onOpenCardRepository, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("卡片仓库")
                        }
                        TextButton(onClick = onCancelCardEdit) { Text("取消") }
                        Button(onClick = onSaveCardLayout) { Text("保存") }
                    }
                    cardLayoutSaveError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (editingCardIds.isEmpty()) {
                        Text(
                            "本页暂时没有卡片，打开卡片仓库添加。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    val byId = cards.associateBy { it.id.name }
                    editingCardIds.forEachIndexed { index, cardId ->
                        key(cardId) {
                            byId[cardId]?.let { model ->
                                DashboardCardEditorRow(
                                    model = model,
                                    index = index,
                                    itemCount = editingCardIds.size,
                                    reduceMotion = reduceMotion,
                                    onMove = { offset -> onMoveCard(cardId, offset) },
                                    onRemove = { onRemoveCard(cardId) },
                                )
                            }
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (service.serviceType == ServiceType.OPENCLASH) {
                        cards.forEach { card ->
                            if (card.id == DashboardCardId.OPENCLASH_NODE_SELECTOR &&
                                nodeSwitchEnabled && selectableGroups.isNotEmpty()
                            ) {
                                OpenClashNodeSelectorCard(
                                    groups = selectableGroups,
                                    switchingGroup = switchingNodeGroup,
                                    feedback = nodeSwitchFeedback,
                                    nodeSwitchEnabled = nodeSwitchEnabled,
                                    nodeLatencyEnabled = nodeLatencyEnabled,
                                    testingNode = testingNode,
                                    latencyResults = nodeLatencyResults,
                                    latencyBatchProgress = latencyBatchProgress,
                                    onSelectNode = onSwitchNode,
                                    onTestNode = onTestNode,
                                    onTestGroup = onTestGroup,
                                    onTestAllNodes = onTestAllNodes,
                                    onCancelBatch = onCancelBatch,
                                    onSheetVisibilityChange = onSelectorSheetVisibilityChange,
                                )
                            } else {
                                DashboardCard(
                                    model = card,
                                    reduceMotion = reduceMotion,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    } else {
                        cards.chunked(cardColumns).forEach { rowCards ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                rowCards.forEach { card ->
                                    DashboardCard(
                                        model = card,
                                        reduceMotion = reduceMotion,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                repeat(cardColumns - rowCards.size) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (cards.isEmpty() && selectableGroups.isEmpty() && !state.isLoading &&
            state.errorMessage == null
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("本页还没有选择要显示的卡片", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "打开卡片编辑器，从仓库添加适合此服务的数据卡片。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onBeginCardEdit, enabled = cardPreferencesLoaded) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("添加卡片")
                    }
                }
            }
        }
        if (cardRepositoryOpen) {
            DashboardCardRepositorySheet(
                entries = cardCatalogue,
                selectedCardIds = editingCardIds.toSet(),
                onToggleCard = onToggleCard,
                onDismiss = onDismissCardRepository,
            )
        }
    }
}

@Composable
private fun DashboardCardEditorRow(
    model: DashboardCardModel,
    index: Int,
    itemCount: Int,
    reduceMotion: Boolean,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val dragTranslation = remember(model.id) { Animatable(0f) }
    val layoutTranslation = remember(model.id) { Animatable(0f) }
    var dragDistance by remember(model.id) { mutableFloatStateOf(0f) }
    var horizontalDragDistance by remember(model.id) { mutableFloatStateOf(0f) }
    var rowStepPx by remember(model.id) { mutableFloatStateOf(with(density) { 86.dp.toPx() }) }
    var isDragging by remember(model.id) { mutableStateOf(false) }
    var skipNextReflow by remember(model.id) { mutableStateOf(false) }
    var previousIndex by remember(model.id) { mutableIntStateOf(index) }
    var dragOriginIndex by remember(model.id) { mutableIntStateOf(index) }
    var dragCurrentIndex by remember(model.id) { mutableIntStateOf(index) }
    val currentIndex = rememberUpdatedState(index)
    val currentItemCount = rememberUpdatedState(itemCount)
    val currentOnMove = rememberUpdatedState(onMove)
    val currentRowStepPx = rememberUpdatedState(rowStepPx)
    val currentReduceMotion = rememberUpdatedState(reduceMotion)

    LaunchedEffect(index, rowStepPx) {
        if (!isDragging) dragCurrentIndex = index
        if (previousIndex != index) {
            val oldIndex = previousIndex
            previousIndex = index
            if (skipNextReflow) {
                skipNextReflow = false
            } else {
                val initialOffset = (oldIndex - index) * rowStepPx
                layoutTranslation.snapTo(initialOffset)
                if (reduceMotion) {
                    layoutTranslation.snapTo(0f)
                } else {
                    layoutTranslation.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(dampingRatio = 0.82f, stiffness = 520f),
                    )
                }
            }
        }
    }

    val cardScale by animateFloatAsState(
        targetValue = if (isDragging && !reduceMotion) 1.035f else 1f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 520f),
        label = "${model.id.name}-drag-scale",
    )
    val cardRotationX by animateFloatAsState(
        targetValue = if (isDragging && !reduceMotion) {
            (-dragDistance / rowStepPx.coerceAtLeast(1f) * 5.5f).coerceIn(-7f, 7f)
        } else {
            0f
        },
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 430f),
        label = "${model.id.name}-drag-rotation-x",
    )
    val cardRotationY by animateFloatAsState(
        targetValue = if (isDragging && !reduceMotion) {
            (horizontalDragDistance / rowStepPx.coerceAtLeast(1f) * 4f).coerceIn(-5f, 5f)
        } else {
            0f
        },
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 430f),
        label = "${model.id.name}-drag-rotation-y",
    )
    val cardElevation by animateDpAsState(
        targetValue = if (isDragging && !reduceMotion) 20.dp else 2.dp,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 480f),
        label = "${model.id.name}-drag-elevation",
    )

    val dragHandle = Modifier.pointerInput(model.id) {
        detectDragGesturesAfterLongPress(
            onDragStart = {
                dragDistance = 0f
                horizontalDragDistance = 0f
                dragOriginIndex = currentIndex.value
                dragCurrentIndex = currentIndex.value
                isDragging = true
                scope.launch {
                    dragTranslation.stop()
                    dragTranslation.snapTo(0f)
                }
            },
            onDragCancel = {
                val stepPx = currentRowStepPx.value.coerceAtLeast(with(density) { 76.dp.toPx() })
                val restoreOffset = dragOriginIndex - dragCurrentIndex
                val settleFrom = dashboardCardDragTranslation(
                    originIndex = dragOriginIndex,
                    currentIndex = dragOriginIndex,
                    dragDistancePx = dragDistance,
                    rowStepPx = stepPx,
                )
                if (restoreOffset != 0) {
                    skipNextReflow = true
                    currentOnMove.value(restoreOffset)
                    dragCurrentIndex = dragOriginIndex
                }
                isDragging = false
                dragDistance = 0f
                horizontalDragDistance = 0f
                scope.launch {
                    dragTranslation.snapTo(settleFrom)
                    if (currentReduceMotion.value) {
                        dragTranslation.snapTo(0f)
                    } else {
                        dragTranslation.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(dampingRatio = 0.58f, stiffness = 420f),
                        )
                    }
                }
            },
            onDragEnd = {
                dragCurrentIndex = currentIndex.value
                isDragging = false
                dragDistance = 0f
                horizontalDragDistance = 0f
                scope.launch {
                    if (currentReduceMotion.value) {
                        dragTranslation.snapTo(0f)
                    } else {
                        dragTranslation.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(dampingRatio = 0.54f, stiffness = 360f),
                        )
                    }
                }
            },
            onDrag = { change, amount ->
                change.consume()
                dragDistance += amount.y
                horizontalDragDistance += amount.x
                val stepPx = currentRowStepPx.value.coerceAtLeast(with(density) { 76.dp.toPx() })
                val targetIndex = dashboardCardDragTargetIndex(
                    originIndex = dragOriginIndex,
                    dragDistancePx = dragDistance,
                    rowStepPx = stepPx,
                    itemCount = currentItemCount.value,
                )
                val reorderOffset = targetIndex - dragCurrentIndex
                if (reorderOffset != 0) {
                    skipNextReflow = true
                    currentOnMove.value(reorderOffset)
                    dragCurrentIndex = targetIndex
                }
                val visualOffset = dashboardCardDragTranslation(
                    originIndex = dragOriginIndex,
                    currentIndex = dragCurrentIndex,
                    dragDistancePx = dragDistance,
                    rowStepPx = stepPx,
                )
                scope.launch {
                    dragTranslation.snapTo(visualOffset)
                }
            },
        )
    }

    val rowGapPx = with(density) { 10.dp.toPx() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 2f else 0f)
            .graphicsLayer {
                translationY = dragTranslation.value + layoutTranslation.value
                scaleX = cardScale
                scaleY = cardScale
                rotationX = cardRotationX
                rotationY = cardRotationY
                cameraDistance = 28f * density.density
            }
            .onSizeChanged { rowStepPx = it.height + rowGapPx },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDragging) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
        border = BorderStroke(
            width = if (isDragging && !reduceMotion) 1.5.dp else 0.75.dp,
            color = if (isDragging && !reduceMotion) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.78f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = cardElevation),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(40.dp).then(dragHandle),
                shape = RoundedCornerShape(13.dp),
                color = if (isDragging) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
                },
                border = BorderStroke(
                    0.75.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.44f),
                ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.DragHandle,
                        contentDescription = "长按抬起并拖动排序",
                        modifier = Modifier.size(23.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    model.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    model.value ?: "暂无数据",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    model.detail?.takeIf(String::isNotBlank) ?: "\u00a0",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "移除${model.title}")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardCardRepositorySheet(
    entries: List<DashboardCardCatalogItem>,
    selectedCardIds: Set<String>,
    onToggleCard: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
        tonalElevation = 8.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            Text("卡片仓库", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "每个服务页面独立保存卡片。长按卡片编辑页左侧的拖动柄可排序。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            if (entries.isEmpty()) {
                Text(
                    "尚未取得卡片目录，请先检查系统接口并刷新。",
                    modifier = Modifier.padding(vertical = 18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text("可添加（${entries.count { it.addable }}）", style = MaterialTheme.typography.titleSmall)
                    }
                    items(entries.filter(DashboardCardCatalogItem::addable), key = { it.model.id.name }) { entry ->
                        DashboardCardRepositoryRow(entry, entry.model.id.name in selectedCardIds, onToggleCard)
                    }
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text("接口暂不支持（${entries.count { !it.addable }}）", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "以下项保留在目录中供识别；当前服务端接口没有可靠数据，不会加入页面。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(entries.filterNot(DashboardCardCatalogItem::addable), key = { it.model.id.name }) { entry ->
                        DashboardCardRepositoryRow(entry, selected = false, onToggleCard = {})
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardCardRepositoryRow(
    entry: DashboardCardCatalogItem,
    selected: Boolean,
    onToggleCard: (String) -> Unit,
) {
    val model = entry.model
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (entry.addable) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            },
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                dashboardIcon(model.id),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (entry.addable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(model.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Text(
                    entry.explanation,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(6.dp))
            if (entry.addable) {
                if (selected) {
                    TextButton(onClick = { onToggleCard(model.id.name) }) { Text("已添加") }
                } else {
                    FilledTonalButton(onClick = { onToggleCard(model.id.name) }) { Text("添加") }
                }
            } else {
                Text("暂不可用", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

private data class OpenClashNodeLatencyKey(val groupName: String, val nodeName: String)

private data class OpenClashLatencyBatchProgress(
    val label: String,
    val groupName: String? = null,
    val total: Int,
    val completed: Int = 0,
    val activeNodeName: String? = null,
)

private fun OpenClashNodeLatencyResult.toLatencyDisplay(): OpenClashNodeLatencyDisplay = when (this) {
    is OpenClashNodeLatencyResult.Success -> OpenClashNodeLatencyDisplay.Success(delayMillis)
    is OpenClashNodeLatencyResult.Failure -> OpenClashNodeLatencyDisplay.Failure(message)
}

private sealed interface OpenClashNodeLatencyDisplay {
    data object Testing : OpenClashNodeLatencyDisplay
    data class Success(val delayMillis: Int) : OpenClashNodeLatencyDisplay
    data class Failure(val message: String) : OpenClashNodeLatencyDisplay
}

private fun OpenClashNodeLatencyDisplay?.cardLatencyLabel(): String = when (this) {
    OpenClashNodeLatencyDisplay.Testing -> "当前节点延迟：检测中…"
    is OpenClashNodeLatencyDisplay.Success -> "当前节点延迟：$delayMillis ms"
    is OpenClashNodeLatencyDisplay.Failure -> "当前节点延迟：检测失败"
    null -> "当前节点延迟：未测速"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenClashNodeSelectorCard(
    groups: List<OpenClashProxyGroup>,
    switchingGroup: String?,
    feedback: String?,
    nodeSwitchEnabled: Boolean,
    nodeLatencyEnabled: Boolean,
    testingNode: OpenClashNodeLatencyKey?,
    latencyResults: Map<OpenClashNodeLatencyKey, OpenClashNodeLatencyDisplay>,
    latencyBatchProgress: OpenClashLatencyBatchProgress?,
    onSelectNode: (String, String) -> Unit,
    onTestNode: (String, String) -> Unit,
    onTestGroup: (String) -> Unit,
    onTestAllNodes: () -> Unit,
    onCancelBatch: () -> Unit,
    onSheetVisibilityChange: (Boolean) -> Unit,
) {
    val orderedGroups = remember(groups) { prioritizeManualSelectionProxyGroups(groups) }
    var selectedGroup by remember { mutableStateOf<OpenClashProxyGroup?>(null) }
    LaunchedEffect(selectedGroup != null) {
        onSheetVisibilityChange(selectedGroup != null)
    }
    val configuredHeight = LocalConfiguration.current.screenHeightDp.dp
    val availableHeight = configuredHeight.takeIf { it > 0.dp } ?: 800.dp
    val maxSheetHeight = minOf(560.dp, availableHeight * 0.68f)
    val minSheetHeight = minOf(320.dp, maxSheetHeight)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lan, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("代理节点", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                FilledTonalButton(
                    onClick = if (latencyBatchProgress != null) onCancelBatch else onTestAllNodes,
                    enabled = latencyBatchProgress != null || (nodeLatencyEnabled &&
                        testingNode == null && switchingGroup == null),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    if (latencyBatchProgress != null) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(5.dp))
                        Text("${latencyBatchProgress.label} ${latencyBatchProgress.completed}/${latencyBatchProgress.total} · 取消")
                    } else {
                        Icon(Icons.Outlined.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("总测速")
                    }
                }
            }
            Text(
                "仅列出支持手动选择的 Selector 代理组；切换只影响当前运行状态，不修改路由器配置文件。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            orderedGroups.forEach { group ->
                val currentNodeLatency = group.currentNode?.let { node ->
                    latencyResults[OpenClashNodeLatencyKey(group.name, node)]
                }
                OutlinedButton(
                    onClick = { selectedGroup = group },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = switchingGroup == null && testingNode == null && latencyBatchProgress == null,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                        Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${group.candidates.size} 个候选 · 当前：${group.currentNode ?: "未返回"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            currentNodeLatency.cardLatencyLabel(),
                            style = MaterialTheme.typography.labelSmall,
                            color = when (currentNodeLatency) {
                                is OpenClashNodeLatencyDisplay.Success -> MaterialTheme.colorScheme.primary
                                is OpenClashNodeLatencyDisplay.Failure -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (switchingGroup == group.name) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "选择节点")
                    }
                }
            }
            if (!feedback.isNullOrBlank()) {
                Text(
                    feedback,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (feedback.contains("失败") || feedback.contains("不可用")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
    }

    selectedGroup?.let { requestedGroup ->
        val group = orderedGroups.firstOrNull { it.name == requestedGroup.name } ?: requestedGroup
        ModalBottomSheet(
            onDismissRequest = { selectedGroup = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f),
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minSheetHeight, max = maxSheetHeight)
                    .padding(horizontal = 20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("选择节点", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            group.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { selectedGroup = null }) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭节点列表")
                    }
                }
                Text(
                    "单独测试节点延迟不会切换节点；列表可滚动。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                val batchLabel = latencyBatchProgress?.let { progress ->
                    if (progress.groupName == group.name) "本组测速" else progress.label
                }
                FilledTonalButton(
                    onClick = {
                        if (latencyBatchProgress != null) onCancelBatch() else onTestGroup(group.name)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = latencyBatchProgress != null || (group.candidates.isNotEmpty() &&
                        nodeLatencyEnabled && testingNode == null && switchingGroup == null),
                ) {
                    if (latencyBatchProgress != null) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("$batchLabel ${latencyBatchProgress.completed}/${latencyBatchProgress.total} · 取消")
                    } else {
                        Icon(Icons.Outlined.Speed, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("测速本组 · ${group.candidates.size} 个节点")
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (group.candidates.isEmpty()) {
                    Text(
                        "当前没有可选节点，请刷新 OpenClash 状态。",
                        modifier = Modifier.weight(1f).padding(vertical = 24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(group.candidates, key = { it }) { node ->
                            val key = OpenClashNodeLatencyKey(group.name, node)
                            OpenClashNodeCandidateRow(
                                node = node,
                                isCurrent = node == group.currentNode,
                                switchEnabled = nodeSwitchEnabled && switchingGroup == null && testingNode == null &&
                                    latencyBatchProgress == null,
                                latencyEnabled = nodeLatencyEnabled && switchingGroup == null && testingNode == null &&
                                    latencyBatchProgress == null,
                                latencyState = latencyResults[key],
                                isTesting = testingNode == key,
                                onSwitch = {
                                    selectedGroup = null
                                    onSelectNode(group.name, node)
                                },
                                onTest = { onTestNode(group.name, node) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenClashNodeCandidateRow(
    node: String,
    isCurrent: Boolean,
    switchEnabled: Boolean,
    latencyEnabled: Boolean,
    latencyState: OpenClashNodeLatencyDisplay?,
    isTesting: Boolean,
    onSwitch: () -> Unit,
    onTest: () -> Unit,
) {
    val latencyLabel = when (latencyState) {
        OpenClashNodeLatencyDisplay.Testing -> "正在检测延迟…"
        is OpenClashNodeLatencyDisplay.Success -> "延迟 ${latencyState.delayMillis} ms"
        is OpenClashNodeLatencyDisplay.Failure -> latencyState.message
        null -> "尚未检测延迟"
    }
    val latencyColor = when (latencyState) {
        is OpenClashNodeLatencyDisplay.Failure -> MaterialTheme.colorScheme.error
        is OpenClashNodeLatencyDisplay.Success -> if (latencyState.delayMillis <= 180) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.tertiary
        }
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f)
                else MaterialTheme.colorScheme.surfaceContainerLow,
            )
            .padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(node, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(latencyLabel, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall, color = latencyColor)
        }
        if (latencyEnabled || isTesting) {
            TextButton(onClick = onTest, enabled = latencyEnabled) {
                if (isTesting) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(4.dp))
                Text("测速")
            }
        }
        when {
            isCurrent -> AssistChip(onClick = {}, enabled = false, label = { Text("当前") })
            switchEnabled -> TextButton(onClick = onSwitch, enabled = switchEnabled) {
                Text("切换")
            }
        }
    }
}

@Composable
private fun ServicesSidebar(
    services: List<ServiceUiModel>,
    selectedServiceId: String?,
    expanded: Boolean,
    reduceMotion: Boolean,
    onSelectService: (String) -> Unit,
    onAddService: () -> Unit,
    onAddOpenClash: () -> Unit,
    onAddZashboard: () -> Unit,
    onHideSidebar: () -> Unit,
    onMoveService: (String, Int) -> Unit,
) {
    val groups = services
        .groupBy { it.config.normalizedGroup ?: "未分组" }
        .toSortedMap(compareBy<String> { if (it == "未分组") "" else it })
    val hasOpenClashPanel = services.any { it.config.serviceType == ServiceType.OPENCLASH_PANEL }
    val hasZashboard = services.any { it.config.serviceType == ServiceType.OPENCLASH }
    val sidebarWidth = if (expanded) 248.dp else 80.dp

    Card(
        modifier = Modifier
            .width(sidebarWidth)
            .fillMaxHeight(),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = if (expanded) 10.dp else 6.dp, vertical = 10.dp),
        ) {
            if (expanded) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("服务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "按住服务卡片拖动排序",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text("${services.size}", style = MaterialTheme.typography.labelMedium)
                    IconButton(
                        onClick = onHideSidebar,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = "隐藏服务侧栏")
                    }
                }
            } else {
                IconButton(
                    onClick = onHideSidebar,
                    modifier = Modifier.align(Alignment.CenterHorizontally).size(36.dp),
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = "隐藏服务侧栏")
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                groups.forEach { (group, groupedServices) ->
                    item(key = "sidebar-group-$group") {
                        Text(
                            text = group,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp, bottom = 2.dp)
                                .semantics { contentDescription = "分组：$group" },
                            style = if (expanded) {
                                MaterialTheme.typography.labelMedium
                            } else {
                                MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp)
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    itemsIndexed(groupedServices, key = { _, service -> "sidebar-service-${service.config.id}" }) { index, service ->
                        ServiceSidebarItem(
                            service = service,
                            selected = service.config.id == selectedServiceId,
                            expanded = expanded,
                            reduceMotion = reduceMotion,
                            indexInGroup = index,
                            groupItemCount = groupedServices.size,
                            onClick = { onSelectService(service.config.id) },
                            onMove = { delta ->
                                onMoveService(service.config.id, delta)
                            },
                        )
                    }
                }
            }
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            if (expanded) {
                FilledTonalButton(
                    onClick = onAddService,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("新增服务")
                }
            } else {
                IconButton(
                    onClick = onAddService,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "新增服务")
                }
            }
            if (!hasOpenClashPanel) {
                Spacer(Modifier.height(4.dp))
                if (expanded) {
                    OutlinedButton(
                        onClick = onAddOpenClash,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Outlined.Lan, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("OpenClash 管理", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    IconButton(
                        onClick = onAddOpenClash,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Lan, contentDescription = "新增 OpenClash 管理")
                    }
                }
            }
            if (!hasZashboard) {
                Spacer(Modifier.height(4.dp))
                if (expanded) {
                    OutlinedButton(
                        onClick = onAddZashboard,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Outlined.Security, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Zashboard 节点", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    IconButton(
                        onClick = onAddZashboard,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Security, contentDescription = "新增 Zashboard 节点选择")
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarRevealButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            modifier = Modifier.padding(top = 2.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            tonalElevation = 2.dp,
            shadowElevation = 4.dp,
        ) {
            IconButton(
                onClick = onClick,
                modifier = Modifier
                    .size(44.dp)
                    .semantics { contentDescription = "显示服务侧栏" },
            ) {
                Icon(Icons.Outlined.Menu, contentDescription = null)
            }
        }
    }
}

@Composable
private fun ServiceSidebarItem(
    service: ServiceUiModel,
    selected: Boolean,
    expanded: Boolean,
    reduceMotion: Boolean,
    indexInGroup: Int,
    groupItemCount: Int,
    onClick: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val config = service.config
    val motionDuration = RemoteServicesMotion.durationMillis(
        reduceMotion,
        RemoteServicesMotion.StandardMillis,
    )
    val shape = RoundedCornerShape(16.dp)
    val dragTranslation = remember(config.id) { Animatable(0f) }
    val layoutTranslation = remember(config.id) { Animatable(0f) }
    var dragDistance by remember(config.id) { mutableFloatStateOf(0f) }
    var horizontalDragDistance by remember(config.id) { mutableFloatStateOf(0f) }
    var rowStepPx by remember(config.id) {
        mutableFloatStateOf(with(density) { (if (expanded) 76.dp else 88.dp).toPx() })
    }
    var isDragging by remember(config.id) { mutableStateOf(false) }
    var skipNextReflow by remember(config.id) { mutableStateOf(false) }
    var previousIndex by remember(config.id) { mutableIntStateOf(indexInGroup) }
    var dragOriginIndex by remember(config.id) { mutableIntStateOf(indexInGroup) }
    var dragCurrentIndex by remember(config.id) { mutableIntStateOf(indexInGroup) }
    val currentIndex = rememberUpdatedState(indexInGroup)
    val currentItemCount = rememberUpdatedState(groupItemCount)
    val currentOnMove = rememberUpdatedState(onMove)
    val currentRowStepPx = rememberUpdatedState(rowStepPx)
    val currentReduceMotion = rememberUpdatedState(reduceMotion)

    LaunchedEffect(indexInGroup, rowStepPx) {
        if (!isDragging) dragCurrentIndex = indexInGroup
        if (previousIndex != indexInGroup) {
            val oldIndex = previousIndex
            previousIndex = indexInGroup
            if (skipNextReflow) {
                skipNextReflow = false
            } else {
                layoutTranslation.snapTo((oldIndex - indexInGroup) * rowStepPx)
                if (reduceMotion) {
                    layoutTranslation.snapTo(0f)
                } else {
                    layoutTranslation.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(dampingRatio = 0.82f, stiffness = 520f),
                    )
                }
            }
        }
    }

    val itemScale by animateFloatAsState(
        targetValue = if (isDragging && !reduceMotion) 1.045f else 1f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 520f),
        label = "${config.id}-sidebar-drag-scale",
    )
    val itemRotation by animateFloatAsState(
        targetValue = if (isDragging && !reduceMotion) {
            (-dragDistance / rowStepPx.coerceAtLeast(1f) * 4f).coerceIn(-5f, 5f)
        } else {
            0f
        },
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 430f),
        label = "${config.id}-sidebar-drag-rotation",
    )
    val itemElevation by animateDpAsState(
        targetValue = if (isDragging && !reduceMotion) 18.dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 480f),
        label = "${config.id}-sidebar-drag-elevation",
    )

    val dragModifier = Modifier.pointerInput(config.id) {
        detectDragGesturesAfterLongPress(
            onDragStart = {
                dragDistance = 0f
                horizontalDragDistance = 0f
                dragOriginIndex = currentIndex.value
                dragCurrentIndex = currentIndex.value
                isDragging = true
                scope.launch {
                    dragTranslation.stop()
                    dragTranslation.snapTo(0f)
                }
            },
            onDragCancel = {
                val restoreOffset = dragOriginIndex - dragCurrentIndex
                val settleFrom = dashboardCardDragTranslation(
                    originIndex = dragOriginIndex,
                    currentIndex = dragOriginIndex,
                    dragDistancePx = dragDistance,
                    rowStepPx = currentRowStepPx.value,
                )
                if (restoreOffset != 0) {
                    skipNextReflow = true
                    currentOnMove.value(restoreOffset)
                    dragCurrentIndex = dragOriginIndex
                }
                isDragging = false
                dragDistance = 0f
                horizontalDragDistance = 0f
                scope.launch {
                    dragTranslation.snapTo(settleFrom)
                    if (currentReduceMotion.value) {
                        dragTranslation.snapTo(0f)
                    } else {
                        dragTranslation.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(dampingRatio = 0.58f, stiffness = 420f),
                        )
                    }
                }
            },
            onDragEnd = {
                isDragging = false
                dragDistance = 0f
                horizontalDragDistance = 0f
                scope.launch {
                    if (currentReduceMotion.value) {
                        dragTranslation.snapTo(0f)
                    } else {
                        dragTranslation.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(dampingRatio = 0.54f, stiffness = 360f),
                        )
                    }
                }
            },
            onDrag = { change, amount ->
                change.consume()
                dragDistance += amount.y
                horizontalDragDistance += amount.x
                val stepPx = currentRowStepPx.value.coerceAtLeast(with(density) { 68.dp.toPx() })
                val targetIndex = dashboardCardDragTargetIndex(
                    originIndex = dragOriginIndex,
                    dragDistancePx = dragDistance,
                    rowStepPx = stepPx,
                    itemCount = currentItemCount.value,
                )
                val reorderOffset = targetIndex - dragCurrentIndex
                if (reorderOffset != 0) {
                    skipNextReflow = true
                    currentOnMove.value(reorderOffset)
                    dragCurrentIndex = targetIndex
                }
                val visualOffset = dashboardCardDragTranslation(
                    originIndex = dragOriginIndex,
                    currentIndex = dragCurrentIndex,
                    dragDistancePx = dragDistance,
                    rowStepPx = stepPx,
                )
                scope.launch {
                    dragTranslation.snapTo(visualOffset)
                }
            },
        )
    }
    val selectionDescription = buildString {
        append(config.displayName)
        append("，分组 ")
        append(config.normalizedGroup ?: "未分组")
        append("，")
        append(service.health.accessibilityLabel())
        append(if (selected) "，当前选中" else "")
        append("。长按并拖动可调整组内顺序")
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (expanded) 68.dp else 78.dp)
            .zIndex(if (isDragging) 2f else 0f)
            .graphicsLayer {
                translationY = dragTranslation.value + layoutTranslation.value
                scaleX = itemScale
                scaleY = itemScale
                rotationX = itemRotation
                rotationY = if (isDragging && !reduceMotion) {
                    (horizontalDragDistance / rowStepPx.coerceAtLeast(1f) * 3.5f).coerceIn(-4f, 4f)
                } else {
                    0f
                }
                cameraDistance = 28f * density.density
                shadowElevation = with(density) { itemElevation.toPx() }
                this.shape = shape
            }
            .clip(shape)
            .background(
                when {
                    isDragging -> MaterialTheme.colorScheme.surfaceContainerHigh
                    selected -> MaterialTheme.colorScheme.primaryContainer
                    else -> Color.Transparent
                },
            )
            .animateContentSize(animationSpec = tween(motionDuration))
            .onSizeChanged { rowStepPx = it.height + with(density) { 4.dp.toPx() } }
            .then(dragModifier)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = selectionDescription
            }
            .padding(horizontal = if (expanded) 9.dp else 4.dp, vertical = if (expanded) 9.dp else 8.dp),
    ) {
        if (expanded) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ServiceSidebarGlyph(config.iconKey, selected = selected, size = 40.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        config.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        service.health.accessibilityLabel(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Box(
                    modifier = Modifier.size(44.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.DragHandle,
                        contentDescription = "拖动排序",
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ServiceSidebarGlyph(config.iconKey, selected = selected, size = 42.dp)
                Spacer(Modifier.height(3.dp))
                Text(
                    config.displayName,
                    modifier = Modifier.widthIn(max = 54.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ServiceSidebarGlyph(iconKey: String, selected: Boolean, size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                else MaterialTheme.colorScheme.secondaryContainer,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = serviceIcon(iconKey),
            contentDescription = null,
            modifier = Modifier.size(size * 0.56f),
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

private fun ServiceHealth.accessibilityLabel(): String = when (this) {
    ServiceHealth.UNKNOWN -> "未检查"
    ServiceHealth.CHECKING -> "检查中"
    ServiceHealth.AVAILABLE -> "可访问"
    ServiceHealth.UNAVAILABLE -> "暂不可用"
}

@Composable
private fun ServiceActionsMenu(
    service: ServiceConfig,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember(service.id) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "${service.displayName} 服务选项")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("编辑服务") },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                onClick = {
                    expanded = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text("删除服务", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun ServiceHomeSection(
    service: ServiceUiModel,
    reduceMotion: Boolean,
    onOpenService: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val config = service.config
    val motionDuration = RemoteServicesMotion.durationMillis(
        reduceMotion,
        RemoteServicesMotion.StandardMillis,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(motionDuration)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    config.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${serviceTypeLabel(config.serviceType)} · ${config.normalizedGroup ?: "未分组"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ServiceActionsMenu(config, onEdit = onEdit, onDelete = onDelete)
        }
        Spacer(Modifier.height(14.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("服务状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    HealthChip(service.health)
                }
                Text(
                    when (config.serviceType) {
                        ServiceType.OPENCLASH_PANEL -> "独立 OpenClash 管理入口；应用会自动补齐 LuCI 管理目录。"
                        else -> "普通服务没有系统状态面板，可直接打开已配置的服务网页。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                EndpointLine(Icons.Outlined.Lan, "内网", config.lanUrl)
                EndpointLine(Icons.Outlined.Language, "公网", config.wanUrl)
                FilledTonalButton(
                    onClick = onOpenService,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Language, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (config.serviceType) {
                            ServiceType.OPENCLASH_PANEL -> "打开 OpenClash 管理"
                            else -> "打开网页"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardCard(
    model: DashboardCardModel,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val motionDuration = RemoteServicesMotion.durationMillis(
        reduceMotion,
        RemoteServicesMotion.StandardMillis,
    )
    val progress by animateFloatAsState(
        targetValue = model.progress ?: 0f,
        animationSpec = tween(motionDuration),
        label = "dashboard-${model.id.name.lowercase()}-progress",
    )
    val statusColor = when (model.status) {
        DashboardCardStatus.READY -> MaterialTheme.colorScheme.primary
        DashboardCardStatus.LOADING -> MaterialTheme.colorScheme.tertiary
        DashboardCardStatus.UNAVAILABLE -> MaterialTheme.colorScheme.outline
        DashboardCardStatus.ERROR -> MaterialTheme.colorScheme.error
    }
    Card(
        modifier = modifier
            .animateContentSize(animationSpec = tween(motionDuration)),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        dashboardIcon(model.id),
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    model.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = model.value ?: "暂无数据",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (model.status == DashboardCardStatus.UNAVAILABLE) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
            )
            model.progress?.let {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = statusColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = model.detail ?: dashboardStatusLabel(model.status),
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                maxLines = 2,
            )
        }
    }
}

private fun dashboardIcon(id: DashboardCardId): ImageVector = when (id) {
    DashboardCardId.CPU -> Icons.Outlined.Refresh
    DashboardCardId.TEMPERATURE -> Icons.Outlined.Settings
    DashboardCardId.MEMORY -> Icons.Outlined.Lan
    DashboardCardId.MEMORY_FREE -> Icons.Outlined.Lan
    DashboardCardId.MEMORY_SHARED -> Icons.Outlined.Lan
    DashboardCardId.MEMORY_BUFFERED -> Icons.Outlined.Lan
    DashboardCardId.SWAP -> Icons.Outlined.Storage
    DashboardCardId.STORAGE -> Icons.Outlined.Language
    DashboardCardId.UPTIME -> Icons.Outlined.Settings
    DashboardCardId.NETWORK -> Icons.Outlined.Lan
    DashboardCardId.HOST -> Icons.Outlined.Info
    DashboardCardId.HOSTNAME -> Icons.Outlined.Info
    DashboardCardId.MODEL -> Icons.Outlined.Dns
    DashboardCardId.OS_DISTRIBUTION -> Icons.Outlined.Settings
    DashboardCardId.FIRMWARE -> Icons.Outlined.Settings
    DashboardCardId.KERNEL -> Icons.Outlined.Code
    DashboardCardId.LOAD_1 -> Icons.Outlined.Refresh
    DashboardCardId.LOAD_5 -> Icons.Outlined.Refresh
    DashboardCardId.LOAD_15 -> Icons.Outlined.Refresh
    DashboardCardId.OPENCLASH_NODE_SELECTOR -> Icons.Outlined.Lan
    DashboardCardId.OPENCLASH_STATUS -> Icons.Outlined.Lan
    DashboardCardId.OPENCLASH_VERSION -> Icons.Outlined.Info
    DashboardCardId.OPENCLASH_MODE -> Icons.Outlined.Settings
    DashboardCardId.OPENCLASH_CONFIG -> Icons.Outlined.Code
    DashboardCardId.OPENCLASH_ROUTE -> Icons.Outlined.Language
    DashboardCardId.OPENCLASH_TRAFFIC -> Icons.Outlined.Language
    DashboardCardId.OPENCLASH_DOWNLOAD_RATE -> Icons.Outlined.Download
    DashboardCardId.OPENCLASH_UPLOAD_RATE -> Icons.Outlined.Download
    DashboardCardId.OPENCLASH_DOWNLOAD_TOTAL -> Icons.Outlined.Download
    DashboardCardId.OPENCLASH_UPLOAD_TOTAL -> Icons.Outlined.Download
    DashboardCardId.OPENCLASH_CONNECTIONS -> Icons.Outlined.MoreVert
    DashboardCardId.OPENCLASH_GROUPS -> Icons.Outlined.Lan
    DashboardCardId.OPENCLASH_SELECTED_GROUP -> Icons.Outlined.Lan
    DashboardCardId.OPENCLASH_MEMORY -> Icons.Outlined.Lan
    DashboardCardId.DOCKER_ENGINE -> Icons.Outlined.Dns
    DashboardCardId.DOCKER_REGISTRY -> Icons.Outlined.Language
    DashboardCardId.DOCKER_CONTAINERS -> Icons.Outlined.Dns
    DashboardCardId.DOCKER_RUNNING -> Icons.Outlined.PlayArrow
    DashboardCardId.DOCKER_PAUSED -> Icons.Outlined.Pause
    DashboardCardId.DOCKER_STOPPED -> Icons.Outlined.Stop
    DashboardCardId.DOCKER_IMAGES -> Icons.Outlined.Dns
    DashboardCardId.DOCKER_NETWORKS -> Icons.Outlined.Lan
    DashboardCardId.DOCKER_VOLUMES -> Icons.Outlined.Storage
    DashboardCardId.DOCKER_HOST_RESOURCES -> Icons.Outlined.Memory
    DashboardCardId.DOCKER_RUNTIME -> Icons.Outlined.Info
    DashboardCardId.DOCKER_STORAGE -> Icons.Outlined.Storage
    DashboardCardId.DOCKER_CONTAINER_LIST -> Icons.Outlined.Dns
    DashboardCardId.DOCKER_IMAGE_LIST -> Icons.Outlined.Dns
    DashboardCardId.DOCKER_NETWORK_LIST -> Icons.Outlined.Lan
    DashboardCardId.DOCKER_VOLUME_LIST -> Icons.Outlined.Storage
}

private fun dashboardStatusLabel(status: DashboardCardStatus): String = when (status) {
    DashboardCardStatus.READY -> "已获取"
    DashboardCardStatus.LOADING -> "读取中"
    DashboardCardStatus.UNAVAILABLE -> "未获取"
    DashboardCardStatus.ERROR -> "读取失败"
}

@Composable
private fun LoadingServices() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyServices(
    modifier: Modifier = Modifier,
    onAdd: () -> Unit,
) {
    Column(
        modifier = modifier
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
            )
        }
        Text("还没有服务", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "把 NAS、路由器或自建服务集中到这里，应用会优先尝试内网地址。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAdd) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("新增服务")
        }
    }
}

@Composable
private fun HealthChip(health: ServiceHealth) {
    val (label, color) = when (health) {
        ServiceHealth.UNKNOWN -> "未检查" to MaterialTheme.colorScheme.secondary
        ServiceHealth.CHECKING -> "检查中" to MaterialTheme.colorScheme.tertiary
        ServiceHealth.AVAILABLE -> "可访问" to Color(0xFF238636)
        ServiceHealth.UNAVAILABLE -> "暂不可用" to MaterialTheme.colorScheme.error
    }
    AssistChip(
        onClick = {},
        label = { Text(label) },
        leadingIcon = {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        },
        border = null,
    )
}

@Composable
private fun EndpointLine(icon: ImageVector, label: String, value: String?) {
    val hasEmbeddedCredentials = urlContainsUserInfo(value)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(
            text = when {
                hasEmbeddedCredentials -> "含有嵌入式登录信息，请编辑移除"
                else -> value ?: "未配置"
            },
            style = MaterialTheme.typography.bodySmall,
            color = when {
                hasEmbeddedCredentials -> MaterialTheme.colorScheme.error
                value == null -> MaterialTheme.colorScheme.outline
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EndpointField(
    label: String,
    state: EndpointFieldState,
    onStateChange: (EndpointFieldState) -> Unit,
) {
    var schemeMenuExpanded by remember(label) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                OutlinedButton(
                    onClick = { schemeMenuExpanded = true },
                    modifier = Modifier.width(112.dp),
                ) {
                    Text(state.scheme.value.uppercase())
                }
                DropdownMenu(
                    expanded = schemeMenuExpanded,
                    onDismissRequest = { schemeMenuExpanded = false },
                ) {
                    EndpointScheme.entries.forEach { scheme ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (scheme == EndpointScheme.HTTP) {
                                        "HTTP（不加密）"
                                    } else {
                                        "HTTPS（加密）"
                                    },
                                )
                            },
                            onClick = {
                                schemeMenuExpanded = false
                                onStateChange(state.copy(scheme = scheme))
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = state.address,
                onValueChange = { onStateChange(state.copy(address = it)) },
                modifier = Modifier.weight(1f),
                label = { Text(label) },
                placeholder = { Text("192.168.1.10:5001/path") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
            )
        }
        Text(
            text = if (state.scheme == EndpointScheme.HTTP) {
                "HTTP 为不加密连接，仅建议用于可信内网。"
            } else {
                "HTTPS 为加密连接。地址只填写主机/IP、端口和路径。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 120.dp, top = 4.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceEditorSheet(
    editor: ServiceEditorState,
    reduceMotion: Boolean,
    onIntent: (ServicesIntent) -> Unit,
    onManageCredentials: (ServiceConfig) -> Unit,
    wifiNames: WifiNetworkNameResult,
    wifiNamesLoading: Boolean,
    onRefreshWifiNames: () -> Unit,
    onRequestWifiPermissions: () -> Unit,
) {
    var serviceTypeMenuExpanded by remember { mutableStateOf(false) }
    var wifiNamesMenuExpanded by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = { onIntent(ServicesIntent.DismissEditor) },
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
        tonalElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, top = 4.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text(
                if (editor.isNew) "新增服务" else "编辑服务",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (editor.original?.hasEmbeddedUrlCredentials() == true) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "旧地址中的登录信息已从输入框移除；保存服务会清理旧配置。账号密码请单独保存在服务凭据中。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = editor.draft.displayName,
                onValueChange = { onIntent(ServicesIntent.DraftChanged(editor.draft.copy(displayName = it))) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("服务名称") },
                placeholder = { Text("例如：家庭 NAS") },
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            EndpointField(
                label = "内网地址",
                state = splitEndpointUrl(editor.draft.lanUrl),
                onStateChange = { field ->
                    onIntent(
                        ServicesIntent.DraftChanged(
                            editor.draft.copy(lanUrl = joinEndpointUrl(field)),
                        ),
                    )
                },
            )
            Spacer(Modifier.height(12.dp))
            EndpointField(
                label = "公网地址",
                state = splitEndpointUrl(editor.draft.wanUrl),
                onStateChange = { field ->
                    onIntent(
                        ServicesIntent.DraftChanged(
                            editor.draft.copy(wanUrl = joinEndpointUrl(field)),
                        ),
                    )
                },
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = editor.draft.group,
                onValueChange = { onIntent(ServicesIntent.DraftChanged(editor.draft.copy(group = it))) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("分组（可选）") },
                placeholder = { Text("例如：家庭网络") },
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            ServiceIconPicker(
                selectedKey = editor.draft.iconKey,
                onSelect = { key ->
                    onIntent(ServicesIntent.DraftChanged(editor.draft.copy(iconKey = key)))
                },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "服务类型",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box {
                OutlinedButton(onClick = { serviceTypeMenuExpanded = true }) {
                    Text(serviceTypeLabel(editor.draft.serviceType))
                }
                DropdownMenu(
                    expanded = serviceTypeMenuExpanded,
                    onDismissRequest = { serviceTypeMenuExpanded = false },
                ) {
                    ServiceType.entries.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(serviceTypeLabel(type)) },
                            onClick = {
                                serviceTypeMenuExpanded = false
                                onIntent(
                                    ServicesIntent.DraftChanged(editor.draft.copy(serviceType = type)),
                                )
                            },
                        )
                    }
                }
            }
            when (editor.draft.serviceType) {
                ServiceType.OPENCLASH_PANEL -> Text(
                    "填写 iStoreOS 根地址即可；打开时自动进入 OpenClash 管理页：/cgi-bin/luci/admin/services/openclash/client。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ServiceType.OPENCLASH -> Text(
                    "此入口用于 Zashboard 节点快速选择；应用会按当前内外网线路获取控制器令牌，不需要手动填写目录或令牌。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Unit
            }
            Spacer(Modifier.height(12.dp))
            val wifiSuggestions = prioritizeWifiNetworkNames(
                wifiNames.names + editor.draft.trustedSsids,
                wifiNames.currentSsid,
            )
            Box(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = editor.draft.trustedSsidsText,
                    onValueChange = {
                        onIntent(ServicesIntent.DraftChanged(editor.draft.withTrustedSsidsText(it)))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("可信 Wi-Fi 名称（可选）") },
                    placeholder = { Text("每行一个，也可用逗号分隔") },
                    trailingIcon = {
                        TextButton(
                            onClick = {
                                wifiNamesMenuExpanded = true
                                onRefreshWifiNames()
                            },
                        ) {
                            Text("选择")
                        }
                    },
                    minLines = 2,
                    maxLines = 4,
                )
                DropdownMenu(
                    expanded = wifiNamesMenuExpanded,
                    onDismissRequest = { wifiNamesMenuExpanded = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    when {
                        wifiNamesLoading -> DropdownMenuItem(
                            text = { Text("正在读取 Wi-Fi 名称…") },
                            onClick = {},
                        )
                        wifiSuggestions.isEmpty() -> DropdownMenuItem(
                            text = {
                                Text(wifiNames.statusMessage ?: "未读取到可用名称，请手动输入")
                            },
                            onClick = { wifiNamesMenuExpanded = false },
                        )
                        else -> wifiSuggestions.forEach { name ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    val selected = (editor.draft.trustedSsids + name)
                                        .sortedWith(String.CASE_INSENSITIVE_ORDER)
                                        .joinToString("\n")
                                    onIntent(
                                        ServicesIntent.DraftChanged(
                                            editor.draft.withTrustedSsidsText(selected),
                                        ),
                                    )
                                    wifiNamesMenuExpanded = false
                                },
                            )
                        }
                    }
                    if (wifiNames.needsPermission) {
                        DropdownMenuItem(
                            text = { Text("允许读取 Wi-Fi 名称…") },
                            onClick = {
                                wifiNamesMenuExpanded = false
                                onRequestWifiPermissions()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("刷新列表") },
                        onClick = {
                            onRefreshWifiNames()
                        },
                    )
                }
            }
            Text(
                "列表仅包含系统能提供的已配置、当前连接及本服务已保存名称；Android 10+ 普通应用无法枚举完整系统保存清单，未显示的名称可手动输入。仅在名称匹配时探测内网。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            wifiNames.statusMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("需要登录", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "凭据只保存在 Android Keystore 加密存储，不写入服务配置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = editor.draft.authEnabled,
                    onCheckedChange = {
                        onIntent(ServicesIntent.DraftChanged(editor.draft.copy(authEnabled = it)))
                    },
                )
            }
            if (editor.original != null && editor.draft.authEnabled) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { onManageCredentials(editor.original) }) {
                    Text("管理登录凭据（安全存储）")
                }
            }
            editor.validationError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { onIntent(ServicesIntent.DismissEditor) }) { Text("取消") }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = { onIntent(ServicesIntent.SaveClicked) },
                ) { Text("保存") }
            }
        }
    }
}

@Composable
private fun ServiceIconPicker(
    selectedKey: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "服务图标",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "选择常用图标；保存后会显示在左侧服务栏。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        serviceIconChoices.chunked(4).forEach { rowChoices ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowChoices.forEach { choice ->
                    val selected = choice.key == selectedKey ||
                        (selectedKey !in serviceIconChoices.map { it.key } && choice.key == "service")
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainer,
                            )
                            .selectable(
                                selected = selected,
                                onClick = { onSelect(choice.key) },
                                role = Role.RadioButton,
                            )
                            .semantics {
                                contentDescription = "${choice.label}服务图标${if (selected) "，已选中" else ""}"
                            }
                            .padding(horizontal = 2.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = choice.icon,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = choice.label,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                repeat(4 - rowChoices.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
