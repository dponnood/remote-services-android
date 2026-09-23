package xin.dponnood.remoteservice.feature.update

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xin.dponnood.remoteservice.core.update.InstallLaunchResult
import xin.dponnood.remoteservice.core.update.InstallResult
import xin.dponnood.remoteservice.core.update.InstallResultStore
import xin.dponnood.remoteservice.core.update.UpdateCheckResult

/** Why the host requested an update check. */
enum class UpdateCheckTrigger {
    STARTUP,
    MANUAL,
}

/**
 * State consumed by a Compose update surface. [ui] contains download progress
 * and install-launch state; [lastInstallResult] is persisted by the receiver
 * and can be refreshed after process recreation or when the screen resumes.
 */
data class UpdateHostState(
    val trigger: UpdateCheckTrigger? = null,
    val lastCheck: UpdateCheckResult? = null,
    val ui: UpdateUiState = UpdateUiState(),
    val lastInstallResult: InstallResult? = null,
) {
    val hasBlockingUpdate: Boolean
        get() = UpdateHostPolicy.isBlocking(ui)

    /** Non-null only when Android requires the user to enable unknown sources. */
    val unknownSourcesIntent: Intent?
        get() = (ui.installResult as? InstallLaunchResult.RequiresUnknownSourcesPermission)?.intent
}

/** Pure decisions shared by the host and JVM tests. */
object UpdateHostPolicy {
    fun forceNetworkCheck(trigger: UpdateCheckTrigger): Boolean = trigger == UpdateCheckTrigger.MANUAL

    fun isBlocking(ui: UpdateUiState): Boolean = ui.available != null && ui.mandatory

    fun shouldShowOptional(ui: UpdateUiState, skippedVersionCode: Long?): Boolean {
        val info = ui.available ?: return false
        return !ui.mandatory && info.versionCode != skippedVersionCode
    }
}

/**
 * Reusable update host facade for Compose or another Activity. Startup checks
 * use [UpdateChecker]'s once-per-day throttle; manual checks bypass it. The
 * caller owns rendering and confirmation, including the system installer UI.
 */
class UpdateHostCoordinator(
    context: Context,
    private val coordinator: UpdateCoordinator = UpdateCoordinator(context),
    private val installResultStore: InstallResultStore = InstallResultStore(context),
    scope: CoroutineScope? = null,
) : AutoCloseable {
    private val ownsScope = scope == null
    private val workerScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateFlow = MutableStateFlow(
        UpdateHostState(
            ui = coordinator.state.value,
            lastInstallResult = installResultStore.get(),
        ),
    )
    private val observerJob: Job = workerScope.launch {
        coordinator.state.collect { ui ->
            stateFlow.update { current -> current.copy(ui = ui) }
        }
    }

    val state: StateFlow<UpdateHostState> = stateFlow.asStateFlow()

    /** Launch from Activity/Compose startup; the core checker enforces 24h. */
    fun startupCheck(): Job = workerScope.launch {
        check(UpdateCheckTrigger.STARTUP)
    }

    /** Launch from a user action; this always performs a fresh network check. */
    fun manualCheck(): Job = workerScope.launch {
        check(UpdateCheckTrigger.MANUAL)
    }

    suspend fun check(trigger: UpdateCheckTrigger): UpdateCheckResult {
        stateFlow.update { it.copy(trigger = trigger) }
        val result = coordinator.check(UpdateHostPolicy.forceNetworkCheck(trigger))
        stateFlow.update {
            it.copy(
                lastCheck = result,
                ui = coordinator.state.value,
                lastInstallResult = installResultStore.get(),
            )
        }
        return result
    }

    /** Downloads, verifies and submits to PackageInstaller; progress is in [state]. */
    suspend fun downloadAndInstall(): Result<InstallLaunchResult> {
        val result = coordinator.downloadAndVerify()
        stateFlow.update {
            it.copy(
                ui = coordinator.state.value,
                lastInstallResult = installResultStore.get(),
            )
        }
        return result
    }

    fun applyPromptAction(action: UpdatePromptAction) {
        coordinator.applyPromptAction(action)
        stateFlow.update { it.copy(ui = coordinator.state.value) }
    }

    fun shouldShowOptionalPrompt(): Boolean {
        val ui = stateFlow.value.ui
        val info = ui.available ?: return false
        return UpdateHostPolicy.shouldShowOptional(ui, null) && coordinator.shouldShowOptionalPrompt(info)
    }

    /** Re-reads the non-exported PackageInstaller receiver's persisted result. */
    fun refreshInstallResult(): InstallResult? {
        val result = installResultStore.get()
        stateFlow.update { it.copy(lastInstallResult = result) }
        return result
    }

    /** Convenience accessor for the system ACTION_MANAGE_UNKNOWN_APP_SOURCES intent. */
    fun unknownSourcesIntent(): Intent? = stateFlow.value.unknownSourcesIntent

    override fun close() {
        observerJob.cancel()
        if (ownsScope) workerScope.cancel()
    }

}
