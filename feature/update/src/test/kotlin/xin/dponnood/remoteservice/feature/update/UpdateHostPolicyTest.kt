package xin.dponnood.remoteservice.feature.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xin.dponnood.remoteservice.core.model.UpdateInfo

class UpdateHostPolicyTest {
    private val info = UpdateInfo(
        versionCode = 12L,
        versionName = "1.2.0",
        minimumVersionCode = 1L,
        apkUrl = "https://app.dponnood.xin/apk/12.apk",
        apkSizeBytes = 1L,
        sha256 = "a".repeat(64),
        signingCertificateSha256 = "b".repeat(64),
        publishedAt = "",
        releaseNotes = "",
        securityRequired = false,
    )

    @Test
    fun startupIsThrottledButManualCheckForcesNetwork() {
        assertFalse(UpdateHostPolicy.forceNetworkCheck(UpdateCheckTrigger.STARTUP))
        assertTrue(UpdateHostPolicy.forceNetworkCheck(UpdateCheckTrigger.MANUAL))
    }

    @Test
    fun mandatoryUpdatesCannotBeSkipped() {
        assertTrue(UpdateHostPolicy.isBlocking(UpdateUiState(available = info, mandatory = true)))
        assertFalse(UpdateHostPolicy.shouldShowOptional(UpdateUiState(available = info, mandatory = true), null))
    }

    @Test
    fun optionalPromptHonorsSkippedVersion() {
        val ui = UpdateUiState(available = info, mandatory = false)
        assertTrue(UpdateHostPolicy.shouldShowOptional(ui, null))
        assertFalse(UpdateHostPolicy.shouldShowOptional(ui, info.versionCode))
        assertFalse(UpdateHostPolicy.shouldShowOptional(UpdateUiState(), null))
    }
}
