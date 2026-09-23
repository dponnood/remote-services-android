package xin.dponnood.remoteservice.core.model

/** Validated metadata received from the static update manifest. */
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val minimumVersionCode: Long,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val sha256: String,
    val signingCertificateSha256: String,
    val publishedAt: String,
    val releaseNotes: String,
    val securityRequired: Boolean,
)
