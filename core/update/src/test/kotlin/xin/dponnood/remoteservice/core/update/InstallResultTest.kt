package xin.dponnood.remoteservice.core.update

import android.content.pm.PackageInstaller
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallResultTest {
    @Test
    fun successStatusIsDistinguishedFromFailure() {
        assertTrue(
            InstallResult(
                sessionId = 7,
                status = PackageInstaller.STATUS_SUCCESS,
                message = null,
                receivedAtMs = 1L,
            ).succeeded,
        )
        assertFalse(
            InstallResult(
                sessionId = 7,
                status = PackageInstaller.STATUS_FAILURE,
                message = "cancelled",
                receivedAtMs = 1L,
            ).succeeded,
        )
    }

    @Test
    fun pendingUserActionIsIntermediateAndCanBeRetried() {
        val pending = InstallResult(
            sessionId = 8,
            status = PackageInstaller.STATUS_PENDING_USER_ACTION,
            message = "等待系统确认",
            receivedAtMs = 2L,
        )

        assertTrue(pending.requiresUserAction)
        assertFalse(pending.isTerminal)
        assertFalse(pending.succeeded)
    }
}
