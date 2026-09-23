package xin.dponnood.remoteservice.core.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xin.dponnood.remoteservice.core.model.UpdateInfo
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

const val DEFAULT_UPDATE_HOST = "app.dponnood.xin"
const val DEFAULT_MANIFEST_URL = "https://app.dponnood.xin/update/stable.json"
const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1_000L
private const val MAX_MANIFEST_BYTES = 1L * 1024L * 1024L

enum class UpdateErrorCode {
    MANIFEST_URL_NOT_ALLOWED,
    HTTP_STATUS,
    RESPONSE_TOO_LARGE,
    MALFORMED_MANIFEST,
    MISSING_FIELD,
    INVALID_FIELD,
    APK_NOT_FOUND,
    APK_SIZE_MISMATCH,
    APK_HASH_MISMATCH,
    APK_PACKAGE_MISMATCH,
    APK_VERSION_NOT_NEWER,
    APK_SIGNATURE_MISMATCH,
    APK_PARSE_FAILED,
    INSTALL_PERMISSION_REQUIRED,
    INSTALL_FAILED,
    DOWNLOAD_FAILED,
}

sealed class UpdateException(
    val code: UpdateErrorCode,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {
    class InvalidManifest(code: UpdateErrorCode, message: String, cause: Throwable? = null) :
        UpdateException(code, message, cause)

    class Download(code: UpdateErrorCode, message: String, cause: Throwable? = null) :
        UpdateException(code, message, cause)

    class Verification(code: UpdateErrorCode, message: String, cause: Throwable? = null) :
        UpdateException(code, message, cause)
}

data class UpdateCheckPolicy(
    val manifestUrl: String = DEFAULT_MANIFEST_URL,
    val expectedHost: String = DEFAULT_UPDATE_HOST,
    val intervalMs: Long = CHECK_INTERVAL_MS,
)

sealed interface UpdateCheckResult {
    data object NoUpdate : UpdateCheckResult
    data class Available(val info: UpdateInfo) : UpdateCheckResult
    data class Throttled(val lastCheckedAtMs: Long) : UpdateCheckResult
    data class Failed(val error: UpdateException) : UpdateCheckResult
}

/** Strict parser for the static JSON manifest hosted by app.dponnood.xin. */
object UpdateManifestParser {
    private val SCHEMA_FIELDS = setOf(
        "versionCode",
        "versionName",
        "minimumVersionCode",
        "apkUrl",
        "apkSizeBytes",
        "sha256",
        "signingCertificateSha256",
        "publishedAt",
        "releaseNotes",
        "securityRequired",
    )

    fun parse(json: String, expectedHost: String = DEFAULT_UPDATE_HOST): UpdateInfo {
        if (json.length > MAX_MANIFEST_BYTES) {
            throw UpdateException.InvalidManifest(UpdateErrorCode.RESPONSE_TOO_LARGE, "manifest exceeds 1 MiB")
        }
        val root = try {
            ManifestJsonParser.parseObject(json)
        } catch (error: ManifestJsonParseException) {
            throw UpdateException.InvalidManifest(UpdateErrorCode.MALFORMED_MANIFEST, "manifest is not JSON", error)
        }
        val additionalProperties = root.keys - SCHEMA_FIELDS
        if (additionalProperties.isNotEmpty()) {
            throw UpdateException.InvalidManifest(
                UpdateErrorCode.INVALID_FIELD,
                "unknown manifest field(s): ${additionalProperties.sorted().joinToString(", ")}",
            )
        }

        fun requiredString(name: String): String {
            val value = root[name] ?: throw UpdateException.InvalidManifest(
                UpdateErrorCode.MISSING_FIELD,
                "missing $name",
            )
            val string = (value as? ManifestJsonValue.StringValue)?.value
                ?: throw UpdateException.InvalidManifest(UpdateErrorCode.INVALID_FIELD, "$name must be a string")
            if (string.trim().isEmpty()) {
                throw UpdateException.InvalidManifest(UpdateErrorCode.INVALID_FIELD, "$name must not be empty")
            }
            return string.trim()
        }

        fun requiredStringAllowEmpty(name: String): String {
            val value = root[name] ?: throw UpdateException.InvalidManifest(
                UpdateErrorCode.MISSING_FIELD,
                "missing $name",
            )
            return (value as? ManifestJsonValue.StringValue)?.value
                ?: throw UpdateException.InvalidManifest(UpdateErrorCode.INVALID_FIELD, "$name must be a string")
        }

        fun requiredLong(name: String): Long {
            val value = root[name] ?: throw UpdateException.InvalidManifest(
                UpdateErrorCode.MISSING_FIELD,
                "missing $name",
            )
            val raw = (value as? ManifestJsonValue.NumberValue)?.raw
                ?: throw UpdateException.InvalidManifest(UpdateErrorCode.INVALID_FIELD, "$name must be an integer")
            val number = raw.toLongOrNull()
            if (number == null || number < 0) {
                throw UpdateException.InvalidManifest(UpdateErrorCode.INVALID_FIELD, "invalid $name")
            }
            return number
        }

        fun requiredBoolean(name: String): Boolean {
            val value = root[name] ?: throw UpdateException.InvalidManifest(
                UpdateErrorCode.MISSING_FIELD,
                "missing $name",
            )
            return (value as? ManifestJsonValue.BooleanValue)?.value
                ?: throw UpdateException.InvalidManifest(UpdateErrorCode.INVALID_FIELD, "$name must be boolean")
        }

        val versionCode = requiredLong("versionCode")
        val versionName = requiredString("versionName")
        val minimumVersionCode = requiredLong("minimumVersionCode")
        val apkUrl = requiredString("apkUrl")
        val apkSizeBytes = requiredLong("apkSizeBytes")
        val sha256 = requiredString("sha256").lowercase(Locale.US)
        val signingCertificateSha256 = requiredString("signingCertificateSha256").lowercase(Locale.US)
        val publishedAt = requiredString("publishedAt")
        val releaseNotes = requiredStringAllowEmpty("releaseNotes")
        val securityRequired = requiredBoolean("securityRequired")

        requireHash("sha256", sha256)
        requireHash("signingCertificateSha256", signingCertificateSha256)
        if (runCatching { OffsetDateTime.parse(publishedAt, DateTimeFormatter.ISO_OFFSET_DATE_TIME) }.isFailure) {
            throw UpdateException.InvalidManifest(UpdateErrorCode.INVALID_FIELD, "publishedAt must be an ISO-8601 date-time")
        }
        if (versionCode <= 0L || apkSizeBytes <= 0L) {
            throw UpdateException.InvalidManifest(UpdateErrorCode.INVALID_FIELD, "versionCode and apkSizeBytes must be positive")
        }
        if (minimumVersionCode > versionCode) {
            throw UpdateException.InvalidManifest(UpdateErrorCode.INVALID_FIELD, "minimumVersionCode exceeds versionCode")
        }
        val parsedUrl = parseAllowedHttpsUrl(apkUrl, expectedHost)

        return UpdateInfo(
            versionCode = versionCode,
            versionName = versionName,
            minimumVersionCode = minimumVersionCode,
            apkUrl = parsedUrl.toString(),
            apkSizeBytes = apkSizeBytes,
            sha256 = sha256,
            signingCertificateSha256 = signingCertificateSha256,
            publishedAt = publishedAt,
            releaseNotes = releaseNotes,
            securityRequired = securityRequired,
        )
    }

    fun parseAllowedHttpsUrl(value: String, expectedHost: String): URI {
        val uri = runCatching { URI(value) }.getOrNull()
            ?: throw UpdateException.InvalidManifest(UpdateErrorCode.INVALID_FIELD, "invalid URL")
        val host = uri.host?.trim()?.lowercase(Locale.US)?.removeSuffix(".")
        val approvedHost = expectedHost.lowercase(Locale.US).removeSuffix(".")
        if (!uri.isAbsolute || uri.scheme == null || !uri.scheme.equals("https", ignoreCase = true) || host != approvedHost) {
            throw UpdateException.InvalidManifest(UpdateErrorCode.MANIFEST_URL_NOT_ALLOWED, "URL is not an approved HTTPS host")
        }
        if (!uri.rawUserInfo.isNullOrBlank() || !uri.rawQuery.isNullOrBlank() || !uri.rawFragment.isNullOrBlank()) {
            throw UpdateException.InvalidManifest(UpdateErrorCode.INVALID_FIELD, "URL must not include user info, query, or fragment")
        }
        return uri
    }

    private fun requireHash(name: String, value: String) {
        if (!value.matches(Regex("[0-9a-f]{64}"))) {
            throw UpdateException.InvalidManifest(UpdateErrorCode.INVALID_FIELD, "$name must be a SHA-256 hex string")
        }
    }
}

class UpdateChecker(
    context: Context,
    private val policy: UpdateCheckPolicy = UpdateCheckPolicy(),
) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val checking = AtomicBoolean(false)

    suspend fun check(force: Boolean = false): UpdateCheckResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val last = preferences.getLong(KEY_LAST_CHECKED, 0L)
        if (!force && last > 0L && now - last < policy.intervalMs) return@withContext UpdateCheckResult.Throttled(last)
        if (!checking.compareAndSet(false, true)) return@withContext UpdateCheckResult.Throttled(now)
        try {
            // Record an attempt to enforce the once-per-day startup policy even
            // when the network is unavailable. Settings can still pass force=true.
            preferences.edit().putLong(KEY_LAST_CHECKED, now).apply()
            val body = fetch(policy.manifestUrl, policy.expectedHost)
            val manifest = UpdateManifestParser.parse(body, policy.expectedHost)
            if (manifest.versionCode <= currentVersionCode(context)) UpdateCheckResult.NoUpdate
            else UpdateCheckResult.Available(manifest)
        } catch (error: UpdateException) {
            UpdateCheckResult.Failed(error)
        } catch (error: Exception) {
            UpdateCheckResult.Failed(UpdateException.Download(UpdateErrorCode.DOWNLOAD_FAILED, "update check failed", error))
        } finally {
            checking.set(false)
        }
    }

    private val context: Context = context.applicationContext

    private fun fetch(url: String, expectedHost: String): String {
        val uri = try {
            UpdateManifestParser.parseAllowedHttpsUrl(url, expectedHost)
        } catch (error: UpdateException) {
            throw error
        }
        val connection = (URL(uri.toString()).openConnection() as? HttpURLConnection)
            ?: throw UpdateException.Download(UpdateErrorCode.DOWNLOAD_FAILED, "not an HTTP connection")
        try {
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw UpdateException.Download(UpdateErrorCode.HTTP_STATUS, "manifest HTTP status $status")
            }
            val declared = connection.contentLengthLong
            if (declared > MAX_MANIFEST_BYTES) {
                throw UpdateException.Download(UpdateErrorCode.RESPONSE_TOO_LARGE, "manifest too large")
            }
            val bytes = connection.inputStream.use { input ->
                val initialCapacity = if (declared in 1L..MAX_MANIFEST_BYTES) declared.toInt() else 1024
                val output = java.io.ByteArrayOutputStream(initialCapacity)
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_MANIFEST_BYTES) {
                        throw UpdateException.Download(UpdateErrorCode.RESPONSE_TOO_LARGE, "manifest too large")
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return bytes.toString(Charsets.UTF_8)
        } catch (error: UpdateException) {
            throw error
        } catch (error: IOException) {
            throw UpdateException.Download(UpdateErrorCode.DOWNLOAD_FAILED, "manifest download failed", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun currentVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    companion object {
        private const val PREFERENCES_NAME = "remote_service_update"
        private const val KEY_LAST_CHECKED = "last_checked_at_ms"
        private const val HTTP_TIMEOUT_MS = 10_000
    }
}

data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long)

class ApkDownloader(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    /**
     * Optional connection seam used by JVM tests. Production callers leave it
     * null so every URL still goes through the strict HTTPS/host validator.
     */
    private val connectionFactory: ((String, Long?) -> HttpURLConnection)? = null,
) {
    suspend fun download(
        info: UpdateInfo,
        destinationDir: File,
        onProgress: (DownloadProgress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        destinationDir.mkdirs()
        val part = File(destinationDir, "remote-service-${info.versionCode}.apk.part")
        val final = File(destinationDir, "remote-service-${info.versionCode}.apk")
        if (final.isFile && final.length() == info.apkSizeBytes && sha256(final) == info.sha256.lowercase(Locale.US)) return@withContext final

        var existing = part.length().coerceAtLeast(0L)
        val connection = openConnection(info.apkUrl, existing.takeIf { it > 0L })
        try {
            var status = connection.responseCode
            if (existing > 0L && status != HttpURLConnection.HTTP_PARTIAL) {
                existing = 0L
                connection.disconnect()
                val retry = openConnection(info.apkUrl, null)
                status = retry.responseCode
                return@withContext streamToFile(retry, part, final, info, 0L, onProgress)
            }
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                throw UpdateException.Download(UpdateErrorCode.HTTP_STATUS, "APK HTTP status $status")
            }
            return@withContext streamToFile(connection, part, final, info, existing, onProgress)
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String, rangeStart: Long?): HttpURLConnection {
        connectionFactory?.let { return it(url, rangeStart) }
        val uri = UpdateManifestParser.parseAllowedHttpsUrl(url, DEFAULT_UPDATE_HOST)
        val connection = URL(uri.toString()).openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.requestMethod = "GET"
        if (rangeStart != null && rangeStart > 0L) connection.setRequestProperty("Range", "bytes=$rangeStart-")
        return connection
    }

    private fun streamToFile(
        connection: HttpURLConnection,
        part: File,
        final: File,
        info: UpdateInfo,
        existing: Long,
        onProgress: (DownloadProgress) -> Unit,
    ): File {
        // Preserve the existing byte count. Passing 0 here turns a valid 206
        // response into a fresh write, corrupting the APK and defeating the
        // resume path's Content-Range validation.
        return streamResponse(connection, part, final, info, existing, onProgress)
    }

    private fun streamResponse(
        connection: HttpURLConnection,
        part: File,
        final: File,
        info: UpdateInfo,
        existing: Long,
        onProgress: (DownloadProgress) -> Unit,
    ): File {
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                throw UpdateException.Download(UpdateErrorCode.HTTP_STATUS, "APK HTTP status $status")
            }
            if (existing > 0L) {
                val contentRange = connection.getHeaderField("Content-Range")
                if (status != HttpURLConnection.HTTP_PARTIAL || contentRange?.startsWith("bytes $existing-") != true) {
                    throw UpdateException.Download(UpdateErrorCode.DOWNLOAD_FAILED, "server returned an invalid resume range")
                }
            }
            val total = info.apkSizeBytes
            if (existing > total) {
                part.delete()
                throw UpdateException.Download(UpdateErrorCode.APK_SIZE_MISMATCH, "partial APK is larger than manifest")
            }
            FileOutputStreamCompat(part, append = existing > 0L).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = existing
                    onProgress(DownloadProgress(downloaded, total))
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        if (downloaded > total) {
                            throw UpdateException.Download(UpdateErrorCode.APK_SIZE_MISMATCH, "APK exceeds manifest size")
                        }
                        output.write(buffer, 0, count)
                        onProgress(DownloadProgress(downloaded, total))
                    }
                }
                output.flush()
            }
            if (part.length() != total) {
                throw UpdateException.Download(UpdateErrorCode.APK_SIZE_MISMATCH, "APK size does not match manifest")
            }
            if (sha256(part) != info.sha256.lowercase(Locale.US)) {
                throw UpdateException.Download(UpdateErrorCode.APK_HASH_MISMATCH, "APK SHA-256 does not match manifest")
            }
            if (final.exists()) final.delete()
            if (!part.renameTo(final)) throw UpdateException.Download(UpdateErrorCode.DOWNLOAD_FAILED, "cannot finalize APK")
            return final
        } catch (error: UpdateException) {
            throw error
        } catch (error: IOException) {
            throw UpdateException.Download(UpdateErrorCode.DOWNLOAD_FAILED, "APK download failed", error)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun sha256(file: File): String = FileInputStream(file).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

private class FileOutputStreamCompat(file: File, append: Boolean) : java.io.FileOutputStream(file, append)

class ApkVerifier(
    private val context: Context,
) {
    fun verify(file: File, info: UpdateInfo): Result<Unit> {
        return runCatching {
            if (!file.isFile) throw UpdateException.Verification(UpdateErrorCode.APK_NOT_FOUND, "APK not found")
            if (file.length() != info.apkSizeBytes) throw UpdateException.Verification(UpdateErrorCode.APK_SIZE_MISMATCH, "APK size mismatch")
            if (ApkDownloader.sha256(file) != info.sha256.lowercase(Locale.US)) {
                throw UpdateException.Verification(UpdateErrorCode.APK_HASH_MISMATCH, "APK hash mismatch")
            }
            val packageInfo = archivePackageInfo(file)
                ?: throw UpdateException.Verification(UpdateErrorCode.APK_PARSE_FAILED, "cannot parse APK")
            if (packageInfo.packageName != context.packageName) {
                throw UpdateException.Verification(UpdateErrorCode.APK_PACKAGE_MISMATCH, "APK package mismatch")
            }
            val version = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
            val current = currentVersionCode()
            if (version <= current) throw UpdateException.Verification(UpdateErrorCode.APK_VERSION_NOT_NEWER, "APK is not newer")
            val signers = apkSignatures(packageInfo)
            val expected = info.signingCertificateSha256.lowercase(Locale.US)
            val currentSigners = currentSignatures().map(::signatureSha256)
            if (expected !in signers || expected !in currentSigners) {
                throw UpdateException.Verification(UpdateErrorCode.APK_SIGNATURE_MISMATCH, "APK signing certificate mismatch")
            }
        }
    }

    private fun archivePackageInfo(file: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES.toLong()
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES.toLong()
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(flags))
        } else {
            @Suppress("DEPRECATION") context.packageManager.getPackageArchiveInfo(file.absolutePath, flags.toInt())
        }
    }

    private fun apkSignatures(packageInfo: PackageInfo): Set<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return packageInfo.signingInfo?.apkContentsSigners?.map(::signatureSha256)?.toSet().orEmpty()
        }
        @Suppress("DEPRECATION")
        return packageInfo.signatures?.map(::signatureSha256)?.toSet().orEmpty()
    }

    private fun currentSignatures(): List<Signature> {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    PackageManager.GET_SIGNING_CERTIFICATES.toLong()
                } else {
                    @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES.toLong()
                },
            ))
        } else {
            @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return info.signingInfo?.apkContentsSigners?.toList().orEmpty()
        @Suppress("DEPRECATION") return info.signatures?.toList().orEmpty()
    }

    private fun currentVersionCode(): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    companion object {
        fun signatureSha256(signature: Signature): String = MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

sealed interface InstallLaunchResult {
    data class Submitted(val sessionId: Int) : InstallLaunchResult
    data class RequiresUnknownSourcesPermission(val intent: Intent) : InstallLaunchResult
    data class Failed(val error: UpdateException) : InstallLaunchResult
}

/** Submits an already verified APK to Android's user-confirmed installer. */
class PackageInstallerLauncher(
    private val context: Context,
) {
    private val installResultStore = InstallResultStore(context)

    fun launch(file: File, expectedSize: Long): InstallLaunchResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return InstallLaunchResult.RequiresUnknownSourcesPermission(intent)
        }
        return try {
            val installer = context.packageManager.packageInstaller
            // A previous build could have committed a session and then lost
            // the STATUS_PENDING_USER_ACTION callback. Reusing that sealed
            // session is not possible; abandon it before creating a fresh
            // retry so one failed attempt cannot lock the update flow.
            abandonPendingSession(installer)
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).apply {
                setSize(expectedSize)
                setAppPackageName(context.packageName)
                setInstallReason(PackageManager.INSTALL_REASON_USER)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
            }
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            try {
                FileInputStream(file).use { input ->
                    session.openWrite("base.apk", 0L, expectedSize).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                // Use an explicit component because the library manifest does
                // not need an exported intent-filter. PackageInstaller still
                // fills status extras into this PendingIntent on Android 12+.
                val callback = Intent(context, InstallResultReceiver::class.java)
                    .setAction(ACTION_INSTALL_RESULT)
                val pendingFlags = PendingIntentFlags()
                val pending = android.app.PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    callback,
                    pendingFlags.value,
                )
                // Persist an intermediate state before commit. If the process
                // is killed before Android delivers its callback, the next
                // attempt can identify and abandon this stale session.
                installResultStore.save(
                    InstallResult(
                        sessionId = sessionId,
                        status = PackageInstaller.STATUS_PENDING_USER_ACTION,
                        message = "等待系统安装确认",
                        receivedAtMs = System.currentTimeMillis(),
                    ),
                )
                session.commit(pending.intentSender)
                session.close()
                InstallLaunchResult.Submitted(sessionId)
            } catch (error: Exception) {
                runCatching { session.abandon() }
                runCatching { session.close() }
                installResultStore.clear()
                InstallLaunchResult.Failed(UpdateException.Download(UpdateErrorCode.INSTALL_FAILED, "install submission failed", error))
            }
        } catch (error: Exception) {
            InstallLaunchResult.Failed(UpdateException.Download(UpdateErrorCode.INSTALL_FAILED, "cannot create install session", error))
        }
    }

    private fun abandonPendingSession(installer: PackageInstaller) {
        val previous = installResultStore.get() ?: return
        if (!previous.requiresUserAction || previous.sessionId < 0) return
        runCatching { installer.abandonSession(previous.sessionId) }
        installResultStore.clear()
    }

    private class PendingIntentFlags {
        val value: Int = PendingIntent.FLAG_UPDATE_CURRENT or
            // PackageInstaller supplies status extras when delivering the
            // IntentSender; Android 12+ requires a mutable PendingIntent for
            // those fill-in extras. The intent is explicit to this app and the
            // receiver is non-exported, limiting the mutability surface.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "xin.dponnood.remoteservice.UPDATE_INSTALL_RESULT"
    }
}
