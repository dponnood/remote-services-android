package xin.dponnood.remoteservice.feature.services

import xin.dponnood.remoteservice.core.model.ServiceConfig
import xin.dponnood.remoteservice.core.model.ServiceDraft
import xin.dponnood.remoteservice.core.model.ServiceType

enum class ServiceHealth {
    UNKNOWN,
    CHECKING,
    AVAILABLE,
    UNAVAILABLE,
}

data class ServiceUiModel(
    val config: ServiceConfig,
    val health: ServiceHealth = ServiceHealth.UNKNOWN,
)

data class ServiceEditorState(
    val draft: ServiceDraft,
    val original: ServiceConfig? = null,
    val validationError: String? = null,
) {
    val isNew: Boolean get() = original == null
    val isDirty: Boolean get() = draft.isDirtyComparedTo(original)
}

data class ServicesUiState(
    val services: List<ServiceUiModel> = emptyList(),
    val isLoading: Boolean = true,
    val editor: ServiceEditorState? = null,
    val deleteCandidateId: String? = null,
    val showDiscardConfirmation: Boolean = false,
    val errorMessage: String? = null,
) {
    val hasServices: Boolean get() = services.isNotEmpty()
}

/**
 * Keeps a valid explicit selection, otherwise chooses the most useful home
 * service. The same fallback is used after restoring stale saved state or
 * removing the currently selected service.
 */
internal fun resolveSelectedServiceId(
    services: List<ServiceUiModel>,
    selectedId: String?,
): String? {
    services.firstOrNull { it.config.id == selectedId }?.let { return it.config.id }
    return services.firstOrNull { it.config.serviceType == ServiceType.ISTORE }?.config?.id
        ?: services.firstOrNull { it.config.serviceType == ServiceType.LUCI }?.config?.id
        ?: services.firstOrNull()?.config?.id
}

sealed interface ServicesIntent {
    data object AddClicked : ServicesIntent
    /** Opens a preconfigured OpenClash management editor from the home shortcut. */
    data object AddOpenClashClicked : ServicesIntent
    /** Opens a preconfigured Zashboard node-selection editor from the home shortcut. */
    data object AddZashboardClicked : ServicesIntent
    data class EditClicked(val id: String) : ServicesIntent
    data class DraftChanged(val draft: ServiceDraft) : ServicesIntent
    data object SaveClicked : ServicesIntent
    data object DismissEditor : ServicesIntent
    data class DeleteClicked(val id: String) : ServicesIntent
    data object ConfirmDelete : ServicesIntent
    data object CancelDelete : ServicesIntent
    data object BackRequested : ServicesIntent
    data object ConfirmDiscard : ServicesIntent
    data object CancelDiscard : ServicesIntent
    data class MoveWithinGroup(val id: String, val delta: Int) : ServicesIntent
    data object ClearError : ServicesIntent
}
