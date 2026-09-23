package xin.dponnood.remoteservice.core.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

data class InstallResult(
    val sessionId: Int,
    val status: Int,
    val message: String?,
    val receivedAtMs: Long,
) {
    val succeeded: Boolean get() = status == PackageInstaller.STATUS_SUCCESS
    /** Android sends this intermediate status before showing its confirmation UI. */
    val requiresUserAction: Boolean get() = status == PackageInstaller.STATUS_PENDING_USER_ACTION
    /** A pending-user-action callback is not an install failure or a final result. */
    val isTerminal: Boolean get() = !requiresUserAction
}

/** Persists the last installer callback so UI can survive process recreation. */
class InstallResultStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun save(result: InstallResult) {
        preferences.edit()
            .putInt(KEY_SESSION_ID, result.sessionId)
            .putInt(KEY_STATUS, result.status)
            .putString(KEY_MESSAGE, result.message)
            .putLong(KEY_RECEIVED_AT, result.receivedAtMs)
            .apply()
    }

    fun get(): InstallResult? {
        if (!preferences.contains(KEY_STATUS)) return null
        return InstallResult(
            sessionId = preferences.getInt(KEY_SESSION_ID, -1),
            status = preferences.getInt(KEY_STATUS, PackageInstaller.STATUS_FAILURE),
            message = preferences.getString(KEY_MESSAGE, null),
            receivedAtMs = preferences.getLong(KEY_RECEIVED_AT, 0L),
        )
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "remote_service_install_result"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_STATUS = "status"
        private const val KEY_MESSAGE = "message"
        private const val KEY_RECEIVED_AT = "received_at"
    }
}

/** Receiver is non-exported and only accepts explicit PackageInstaller callbacks. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PackageInstallerLauncher.ACTION_INSTALL_RESULT) return
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val result = InstallResult(
            sessionId = sessionId,
            status = status,
            message = message,
            receivedAtMs = System.currentTimeMillis(),
        )
        val store = InstallResultStore(context)
        store.save(result)

        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) return

        // PackageInstaller deliberately does not launch this UI on behalf of
        // the caller. The status callback carries the system-owned confirmation
        // Intent and the receiver must start it explicitly. Without this step
        // the APK is fully downloaded and verified but appears to do nothing.
        val userAction = pendingUserActionIntent(intent)
        if (userAction == null) {
            store.save(result.copy(message = "系统未返回安装确认界面"))
            return
        }
        userAction.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(userAction) }
            .onFailure { error ->
                // Keep the non-terminal pending result so the next user tap
                // can retry/recover the session; do not report a false failure.
                store.save(result.copy(message = "无法打开系统安装确认界面：${error.message.orEmpty()}"))
            }
    }

    private fun pendingUserActionIntent(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
    }
}
