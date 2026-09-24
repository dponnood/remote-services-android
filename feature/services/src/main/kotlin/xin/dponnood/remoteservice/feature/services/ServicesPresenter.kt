package xin.dponnood.remoteservice.feature.services

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xin.dponnood.remoteservice.core.database.ServiceConfigStore
import xin.dponnood.remoteservice.core.model.ServiceConfig
import xin.dponnood.remoteservice.core.model.ServiceDraft
import xin.dponnood.remoteservice.core.model.ServiceType
import xin.dponnood.remoteservice.core.model.toDraft

/**
 * UDF coordinator for the services screen. The presenter owns no Android view
 * references, so it can be exercised with a plain JVM in-memory store.
 */
class ServicesPresenter(
    private val store: ServiceConfigStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onAuthenticationDisabled: (ServiceConfig) -> Unit = {},
    private val onServiceDeleted: (ServiceConfig) -> Unit = {},
) {
    private val _state = MutableStateFlow(ServicesUiState())
    val state: StateFlow<ServicesUiState> = _state.asStateFlow()

    private val observationJob: Job = scope.launch {
        store.services.collectLatest { configs ->
            _state.update { current ->
                val healthById = current.services.associate { it.config.id to it.health }
                current.copy(
                    isLoading = false,
                    services = configs
                        .sortedWith(compareBy<ServiceConfig> { it.sortOrder }.thenBy { it.id })
                        .map { config -> ServiceUiModel(config, healthById[config.id] ?: ServiceHealth.UNKNOWN) },
                )
            }
        }
    }

    fun dispatch(intent: ServicesIntent) {
        when (intent) {
            ServicesIntent.AddClicked -> openNewEditor()
            ServicesIntent.AddOpenClashClicked -> openNewEditor(ServiceType.OPENCLASH_PANEL)
            ServicesIntent.AddZashboardClicked -> openNewEditor(ServiceType.OPENCLASH)
            is ServicesIntent.EditClicked -> openEditor(intent.id)
            is ServicesIntent.DraftChanged -> updateDraft(intent.draft)
            ServicesIntent.SaveClicked -> save()
            ServicesIntent.DismissEditor -> requestEditorDismissal()
            is ServicesIntent.DeleteClicked -> requestDelete(intent.id)
            ServicesIntent.ConfirmDelete -> confirmDelete()
            ServicesIntent.CancelDelete -> _state.update { it.copy(deleteCandidateId = null) }
            ServicesIntent.BackRequested -> requestBack()
            ServicesIntent.ConfirmDiscard -> _state.update {
                it.copy(editor = null, showDiscardConfirmation = false)
            }
            ServicesIntent.CancelDiscard -> _state.update { it.copy(showDiscardConfirmation = false) }
            is ServicesIntent.MoveWithinGroup -> moveWithinGroup(intent.id, intent.delta)
            ServicesIntent.ClearError -> _state.update { it.copy(errorMessage = null) }
        }
    }

    fun close() {
        observationJob.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun openNewEditor(serviceType: ServiceType = ServiceType.GENERIC) {
        _state.update {
            it.copy(
                editor = ServiceEditorState(
                    draft = if (serviceType == ServiceType.OPENCLASH_PANEL || serviceType == ServiceType.OPENCLASH) {
                        ServiceDraft(
                            displayName = if (serviceType == ServiceType.OPENCLASH_PANEL) {
                                "OpenClash 管理"
                            } else {
                                "Zashboard 节点选择"
                            },
                            lanUrl = "http://192.168.1.1",
                            wanUrl = "https://i.example.com",
                            group = "远程网络",
                            serviceType = serviceType,
                            authEnabled = true,
                        )
                    } else {
                        ServiceDraft()
                    },
                    original = null,
                ),
                errorMessage = null,
            )
        }
    }

    private fun openEditor(id: String) {
        val config = _state.value.services.firstOrNull { it.config.id == id }?.config ?: return
        _state.update {
            it.copy(
                editor = ServiceEditorState(draft = config.toDraft(), original = config),
                errorMessage = null,
            )
        }
    }

    private fun updateDraft(draft: ServiceDraft) {
        _state.update { current ->
            val editor = current.editor ?: return@update current
            current.copy(editor = editor.copy(draft = draft, validationError = null))
        }
    }

    private fun save() {
        val editor = _state.value.editor ?: return
        val error = validate(editor.draft)
        if (error != null) {
            _state.update { it.copy(editor = editor.copy(validationError = error)) }
            return
        }
        val existing = editor.original
        val id = existing?.id ?: editor.draft.id?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString()
        val sortOrder = existing?.sortOrder ?: ((_state.value.services.maxOfOrNull { it.config.sortOrder } ?: -1) + 1)
        val config = ServiceConfig(
            id = id,
            displayName = editor.draft.displayName.trim(),
            lanUrl = editor.draft.lanUrl.trim().takeIf(String::isNotBlank),
            wanUrl = editor.draft.wanUrl.trim().takeIf(String::isNotBlank),
            group = editor.draft.group.trim().takeIf(String::isNotBlank),
            sortOrder = sortOrder,
            iconKey = editor.draft.iconKey.ifBlank { "service" },
            trustedSsids = editor.draft.trustedSsids
                .map(String::trim)
                .filter(String::isNotBlank)
                .toSet(),
            serviceType = editor.draft.serviceType,
            authEnabled = editor.draft.authEnabled,
            connectionPolicy = editor.draft.connectionPolicy,
        )
        scope.launch {
            runCatching { store.upsert(config) }
                .onSuccess {
                    if (existing?.authEnabled == true && !config.authEnabled) {
                        runCatching { onAuthenticationDisabled(existing) }
                    }
                    _state.update { it.copy(editor = null, errorMessage = null) }
                }
                .onFailure { failure ->
                    _state.update { it.copy(errorMessage = failure.message ?: "保存服务失败") }
                }
        }
    }

    private fun requestEditorDismissal() {
        val editor = _state.value.editor ?: return
        if (editor.isDirty) {
            _state.update { it.copy(showDiscardConfirmation = true) }
        } else {
            _state.update { it.copy(editor = null) }
        }
    }

    private fun requestDelete(id: String) {
        if (_state.value.services.any { it.config.id == id }) {
            _state.update { it.copy(deleteCandidateId = id) }
        }
    }

    private fun confirmDelete() {
        val id = _state.value.deleteCandidateId ?: return
        val deletedService = _state.value.services.firstOrNull { it.config.id == id }?.config
        scope.launch {
            runCatching { store.delete(id) }
                .onSuccess {
                    deletedService?.let { service -> runCatching { onServiceDeleted(service) } }
                    _state.update { it.copy(deleteCandidateId = null) }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(deleteCandidateId = null, errorMessage = failure.message ?: "删除服务失败")
                    }
                }
        }
    }

    private fun requestBack() {
        if (_state.value.editor == null) return
        requestEditorDismissal()
    }

    private fun moveWithinGroup(id: String, delta: Int) {
        if (delta == 0) return
        val current = _state.value.services.map { it.config }
        val target = current.firstOrNull { it.id == id } ?: return
        val group = target.normalizedGroup
        val groupItems = current.filter { it.normalizedGroup == group }
        val from = groupItems.indexOfFirst { it.id == id }
        val to = (from + delta).coerceIn(0, groupItems.lastIndex)
        if (from < 0 || from == to) return
        val reorderedGroup = groupItems.toMutableList().apply {
            add(to, removeAt(from))
        }
        val replacement = reorderedGroup.iterator()
        val orderedIds = current.map { config ->
            if (config.normalizedGroup == group) replacement.next().id else config.id
        }
        scope.launch {
            runCatching { store.reorder(orderedIds) }
                .onFailure { failure ->
                    _state.update { it.copy(errorMessage = failure.message ?: "排序失败") }
                }
        }
    }

    private fun validate(draft: ServiceDraft): String? {
        if (draft.displayName.trim().isBlank()) return "请输入服务名称"
        if (draft.lanUrl.isBlank() && draft.wanUrl.isBlank()) return "至少填写一个访问地址"
        val invalid = listOf(draft.lanUrl to "内网地址", draft.wanUrl to "公网地址")
            .firstNotNullOfOrNull { (value, label) ->
                endpointUrlValidationError(value.trim())?.let { error -> label to error }
            }
        return invalid?.let { (label, error) -> "$label$error" }
    }
}
