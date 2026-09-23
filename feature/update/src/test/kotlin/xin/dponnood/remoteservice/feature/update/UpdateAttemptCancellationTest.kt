package xin.dponnood.remoteservice.feature.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import xin.dponnood.remoteservice.core.update.UpdateErrorCode
import xin.dponnood.remoteservice.core.update.UpdateException

class UpdateAttemptCancellationTest {
    @Test
    fun cancellationPropagatesAndSkipsFailureHandlers() = runTest {
        val cancellation = CancellationException("download cancelled")
        var updateFailureHandled = false
        var otherFailureHandled = false
        var cancellationHandled: CancellationException? = null
        var propagated: CancellationException? = null

        try {
            runUpdateAttempt(
                operation = { throw cancellation },
                onUpdateFailure = {
                    updateFailureHandled = true
                    "update failure"
                },
                onFailure = {
                    otherFailureHandled = true
                    "other failure"
                },
                onCancellation = { cancellationHandled = it },
            )
        } catch (error: CancellationException) {
            propagated = error
        }

        assertSame(cancellation, cancellationHandled)
        assertSame(cancellation, propagated)
        assertFalse(updateFailureHandled)
        assertFalse(otherFailureHandled)
    }

    @Test
    fun ordinaryUpdateFailureStillUsesUpdateFailureHandler() = runTest {
        val failure = UpdateException.Download(UpdateErrorCode.DOWNLOAD_FAILED, "download failed")
        val result = runUpdateAttempt(
            operation = { throw failure },
            onUpdateFailure = { "update:${it.code}" },
            onFailure = { "other:${it.message}" },
        )

        assertEquals("update:${UpdateErrorCode.DOWNLOAD_FAILED}", result)
    }
}
