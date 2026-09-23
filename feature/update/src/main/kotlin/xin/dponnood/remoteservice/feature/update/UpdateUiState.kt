package xin.dponnood.remoteservice.feature.update

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xin.dponnood.remoteservice.core.update.ApkDownloader
import xin.dponnood.remoteservice.core.update.ApkVerifier
import xin.dponnood.remoteservice.core.update.DownloadProgress
import xin.dponnood.remoteservice.core.update.InstallLaunchResult
import xin.dponnood.remoteservice.core.update.PackageInstallerLauncher
import xin.dponnood.remoteservice.core.update.UpdateChecker
import xin.dponnood.remoteservice.core.update.UpdateCheckResult
import xin.dponnood.remoteservice.core.update.UpdateException
import xin.dponnood.remoteservice.core.model.UpdateInfo

enum class UpdatePromptAction {
    UPDATE_NOW,
    REMIND_LATER,
    SKIP_VERSION,
}

data class UpdateUiState(
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val available: UpdateInfo? = null,
    val mandatory: Boolean = false,
    val error: UpdateException? = null,
    val installResult: InstallLaunchResult? = null,
)

internal suspend fun <T> runUpdateAttempt(
    operation: suspend () -> T,
    onUpdateFailure: (UpdateException) -> T,
    onFailure: (Exception) -> T,
    onCancellation: (CancellationException) -> Unit = {},
): T = try {
    operation()
} catch (cancellation: CancellationException) {
    onCancellation(cancellation)
    throw cancellation
} catch (error: UpdateException) {
    onUpdateFailure(error)
} catch (error: Exception) {
    onFailure(error)
}

/**
 * Feature-layer coordinator. UI code observes [state] and decides whether to
 * render an optional prompt or the blocking mandatory-update screen.
 */
class UpdateCoordinator(
    context: Context,
    private val checker: UpdateChecker = UpdateChecker(context),
    private val downloader: ApkDownloader = ApkDownloader(),
    private val verifier: ApkVerifier = ApkVerifier(context),
    private val installer: PackageInstallerLauncher = PackageInstallerLauncher(context),
) {
    private val appContext = context.applicationContext
    private val stateFlow = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = stateFlow.asStateFlow()

    suspend fun check(force: Boolean = false): UpdateCheckResult {
        stateFlow.value = stateFlow.value.copy(checking = true, error = null)
        return when (val result = checker.check(force)) {
            is UpdateCheckResult.Available -> {
                val currentCode = currentVersionCode()
                stateFlow.value = stateFlow.value.copy(
                    checking = false,
                    available = result.info,
                    mandatory = currentCode < result.info.minimumVersionCode,
                    error = null,
                )
                result
            }
            UpdateCheckResult.NoUpdate -> {
                stateFlow.value = stateFlow.value.copy(checking = false, available = null, mandatory = false)
                result
            }
            is UpdateCheckResult.Throttled -> {
                stateFlow.value = stateFlow.value.copy(checking = false)
                result
            }
            is UpdateCheckResult.Failed -> {
                stateFlow.value = stateFlow.value.copy(checking = false, error = result.error)
                result
            }
        }
    }

    suspend fun downloadAndVerify(): Result<InstallLaunchResult> {
        val info = stateFlow.value.available
            ?: return Result.failure(IllegalStateException("No update is available"))
        // Each tap starts a fresh attempt. Clear the previous launch result so
        // a cancelled/failed PackageInstaller session cannot leave the UI in a
        // stale state or make the retry appear inert.
        stateFlow.value = stateFlow.value.copy(
            downloading = true,
            error = null,
            installResult = null,
            downloadedBytes = 0L,
            totalBytes = 0L,
        )
        return runUpdateAttempt(
            operation = {
                val file = downloader.download(info, FileLocations.updateDir(appContext)) { progress ->
                    stateFlow.value = stateFlow.value.copy(
                        downloadedBytes = progress.downloadedBytes,
                        totalBytes = progress.totalBytes,
                    )
                }
                val verification = verifier.verify(file, info)
                verification.getOrThrow()
                val launch = installer.launch(file, info.apkSizeBytes)
                if (launch is InstallLaunchResult.Failed) throw launch.error
                stateFlow.value = stateFlow.value.copy(downloading = false, installResult = launch)
                Result.success(launch)
            },
            onCancellation = {
                stateFlow.value = stateFlow.value.copy(downloading = false, installResult = null)
            },
            onUpdateFailure = { error ->
                stateFlow.value = stateFlow.value.copy(downloading = false, error = error)
                Result.failure(error)
            },
            onFailure = { error ->
                stateFlow.value = stateFlow.value.copy(downloading = false, installResult = null)
                Result.failure(error)
            },
        )
    }

    fun applyPromptAction(action: UpdatePromptAction) {
        val info = stateFlow.value.available ?: return
        when (action) {
            UpdatePromptAction.UPDATE_NOW -> Unit
            UpdatePromptAction.REMIND_LATER -> Unit
            UpdatePromptAction.SKIP_VERSION -> {
                appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_SKIPPED_VERSION, info.versionCode).apply()
                stateFlow.value = stateFlow.value.copy(available = null, mandatory = false)
            }
        }
    }

    fun shouldShowOptionalPrompt(info: UpdateInfo): Boolean {
        val skipped = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_SKIPPED_VERSION, -1L)
        return skipped != info.versionCode
    }

    private fun currentVersionCode(): Long {
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
        }
    }

    private object FileLocations {
        /**
         * Keep the verified APK in app-private persistent storage.  The system
         * confirmation screen may suspend or kill this process while the user
         * grants "install unknown apps"; cacheDir is eligible for eviction in
         * exactly that window and made a retry look like a no-op.
         */
        fun updateDir(context: Context) = java.io.File(context.filesDir, "updates").apply { mkdirs() }
    }

    companion object {
        private const val PREFERENCES_NAME = "remote_service_update_ui"
        private const val KEY_SKIPPED_VERSION = "skipped_version_code"
    }
}
