package xin.dponnood.remoteservice.core.update

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Android-only handoff that cannot be exercised by the JVM runner.
 * PackageInstaller requires the app to launch EXTRA_INTENT when it reports
 * STATUS_PENDING_USER_ACTION; merely persisting that callback leaves the
 * downloaded APK with no visible installer UI.
 */
class InstallResultReceiverRegressionTest {
    @Test
    fun pendingCallbackLaunchesSystemConfirmationIntent() {
        val source = File(
            "src/main/kotlin/xin/dponnood/remoteservice/core/update/InstallResultReceiver.kt",
        ).readText()

        assertTrue(source.contains("STATUS_PENDING_USER_ACTION"))
        assertTrue(source.contains("Intent.EXTRA_INTENT"))
        assertTrue(source.contains("FLAG_ACTIVITY_NEW_TASK"))
        assertTrue(source.contains("context.startActivity(userAction)"))
        assertFalse(source.contains("context.startActivity(intent)"))
    }
}
