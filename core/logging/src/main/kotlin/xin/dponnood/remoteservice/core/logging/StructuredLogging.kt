package xin.dponnood.remoteservice.core.logging

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

/** Stable severity values written to the local JSONL log. */
enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

/** A single sanitized record. Sensitive data is removed before this object is created. */
data class LogEntry(
    val timestamp: String,
    val level: LogLevel,
    val eventCode: String,
    val message: String,
    val context: Map<String, String> = emptyMap(),
    val errorType: String? = null,
) {
    fun toJsonLine(): String {
        val fields = linkedMapOf<String, String>()
        fields["timestamp"] = timestamp
        fields["level"] = level.name
        fields["eventCode"] = eventCode
        fields["message"] = message
        val contextJson = context.toSortedMap().entries.joinToString(",") { (key, value) ->
            "\"${jsonEscape(key)}\":\"${jsonEscape(value)}\""
        }
        val scalarJson = fields.entries.joinToString(",") { (key, value) ->
            "\"${jsonEscape(key)}\":\"${jsonEscape(value)}\""
        }
        val errorJson = errorType?.let { ",\"errorType\":\"${jsonEscape(it)}\"" }.orEmpty()
        return "{$scalarJson,\"context\":{$contextJson}$errorJson}"
    }

    companion object {
        fun create(
            level: LogLevel,
            eventCode: String,
            message: String,
            context: Map<String, String> = emptyMap(),
            error: Throwable? = null,
            clock: () -> String = { Instant.now().toString() },
        ): LogEntry = LogEntry(
            timestamp = clock(),
            level = level,
            eventCode = LogSanitizer.eventCode(eventCode),
            message = LogSanitizer.message(message),
            context = LogSanitizer.context(context),
            errorType = error?.javaClass?.simpleName?.let(LogSanitizer::plainValue),
        )
    }
}

/**
 * Removes values that must never leave the app, even when a caller accidentally includes them
 * in a diagnostic message. Service URLs and private addresses are deliberately not retained.
 */
object LogSanitizer {
    private const val REDACTED = "[REDACTED]"
    private const val URL_REDACTED = "[URL_REDACTED]"
    private const val PRIVATE_IP = "[PRIVATE_IP]"
    private const val MAX_MESSAGE_LENGTH = 2_048
    private const val MAX_VALUE_LENGTH = 256

    private val urlPattern = Regex("(?i)https?://[^\\s\\\"'<>]+")
    private val credentialPattern = Regex(
        "(?i)\\b(authorization|cookie|set-cookie|password|passwd|secret|token|api[-_]?key|access[-_]?token|refresh[-_]?token)\\s*(?:=|:)\\s*[^\\r\\n,;]+",
    )
    private val privateIpv4Pattern = Regex(
        "(?<!\\d)(?:10(?:\\.\\d{1,3}){3}|127(?:\\.\\d{1,3}){3}|192\\.168(?:\\.\\d{1,3}){2}|172\\.(?:1[6-9]|2\\d|3[0-1])(?:\\.\\d{1,3}){2})(?!\\d)",
    )
    private val privateIpv6Pattern = Regex(
        "(?i)(?<![0-9a-f:])\\[?(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?:%[0-9a-z_.-]+)?\\]?(?![0-9a-f:])",
    )

    fun eventCode(value: String): String = value
        .trim()
        .uppercase()
        .replace(Regex("[^A-Z0-9_.-]"), "_")
        .take(64)
        .ifBlank { "UNSPECIFIED" }

    fun plainValue(value: String): String = value
        .replace(Regex("[^A-Za-z0-9_.-]"), "_")
        .take(MAX_VALUE_LENGTH)
        .ifBlank { "unknown" }

    fun message(value: String): String = sanitize(value, MAX_MESSAGE_LENGTH)

    fun context(values: Map<String, String>): Map<String, String> = values.entries
        .associate { (key, value) ->
            val safeKey = plainValue(key.lowercase())
            val safeValue = if (isSensitiveKey(safeKey)) REDACTED else sanitize(value, MAX_VALUE_LENGTH)
            safeKey to safeValue
        }

    fun sanitize(value: String, maxLength: Int): String {
        var safe = value
        safe = credentialPattern.replace(safe) { match ->
            val key = match.value.substringBefore('=').substringBefore(':').trim()
            "$key=$REDACTED"
        }
        safe = urlPattern.replace(safe, URL_REDACTED)
        safe = privateIpv4Pattern.replace(safe, PRIVATE_IP)
        safe = privateIpv6Pattern.replace(safe) { match ->
            if (isPrivateIpv6Literal(match.value)) PRIVATE_IP else match.value
        }
        return safe.replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
            .trim()
            .take(maxLength)
    }

    private fun isPrivateIpv6Literal(value: String): Boolean {
        val literal = value
            .removePrefix("[")
            .removeSuffix("]")
            .substringBefore('%')
        val address = runCatching { InetAddress.getByName(literal) }.getOrNull() as? Inet6Address
            ?: return false
        val bytes = address.address
        if (bytes.size != 16) return false
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val isUniqueLocal = (first and 0xfe) == 0xfc
        val isLinkLocal = first == 0xfe && (second and 0xc0) == 0x80
        val isLoopback = bytes.take(15).all { it.toInt() == 0 } && bytes[15].toInt() == 1
        val isUnspecified = bytes.all { it.toInt() == 0 }
        return isUniqueLocal || isLinkLocal || isLoopback || isUnspecified
    }

    private fun isSensitiveKey(key: String): Boolean =
        key.contains("password") || key.contains("passwd") || key.contains("secret") ||
            key.contains("token") || key.contains("cookie") || key.contains("authorization") ||
            key.contains("api_key") || key.contains("apikey") || key.contains("url") ||
            key.contains("uri") || key == "host" || key.endsWith("_ip")
}

private fun jsonEscape(value: String): String = buildString(value.length + 8) {
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(char)
        }
    }
}

/** Minimal local logging contract used by the app and settings screen. */
interface LogRepository {
    fun append(
        level: LogLevel,
        eventCode: String,
        message: String,
        context: Map<String, String> = emptyMap(),
        error: Throwable? = null,
    )

    fun readRecent(limit: Int = 100): List<String>

    fun clear()

    fun sizeBytes(): Long
}

/** File-backed bounded JSONL log. The file is private app storage and survives process death. */
open class FileLogRepository(
    private val file: File,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) : LogRepository {
    init {
        require(maxBytes >= MIN_MAX_BYTES) { "maxBytes must be at least $MIN_MAX_BYTES" }
    }

    override fun append(
        level: LogLevel,
        eventCode: String,
        message: String,
        context: Map<String, String>,
        error: Throwable?,
    ) {
        val line = LogEntry.create(level, eventCode, message, context, error).toJsonLine()
        synchronized(lock) {
            file.parentFile?.mkdirs()
            FileOutputStream(file, true).bufferedWriter(StandardCharsets.UTF_8).use {
                it.append(line).append('\n')
            }
            trimIfNeeded()
        }
    }

    override fun readRecent(limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        synchronized(lock) {
            if (!file.isFile) return emptyList()
            return file.readLines(StandardCharsets.UTF_8).takeLast(limit)
        }
    }

    override fun clear() {
        synchronized(lock) {
            if (file.exists()) file.delete()
        }
    }

    override fun sizeBytes(): Long = synchronized(lock) { file.length() }

    private fun trimIfNeeded() {
        if (file.length() <= maxBytes) return
        val lines = file.readLines(StandardCharsets.UTF_8)
        val keep = ArrayDeque<String>()
        var bytes = 0
        for (line in lines.asReversed()) {
            val lineBytes = line.toByteArray(StandardCharsets.UTF_8).size + 1
            if (bytes + lineBytes > maxBytes && keep.isNotEmpty()) break
            keep.addFirst(line)
            bytes += lineBytes
        }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(tmp, false).bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            keep.forEach { writer.append(it).append('\n') }
        }
        runCatching {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("Unable to replace log file", it)
            }
        }
    }

    private val lock = Any()

    companion object {
        const val DEFAULT_MAX_BYTES: Int = 512 * 1024
        const val MIN_MAX_BYTES: Int = 4 * 1024
    }
}

/** Android app-storage implementation; no external storage permission is needed. */
class AndroidLogRepository(
    context: Context,
    maxBytes: Int = FileLogRepository.DEFAULT_MAX_BYTES,
) : FileLogRepository(
    File(context.filesDir, "remote-services/logs/app.jsonl"),
    maxBytes,
)

/** Simple in-memory repository useful for previews and deterministic JVM tests. */
class InMemoryLogRepository : LogRepository {
    private val lines = ArrayDeque<String>()

    override fun append(
        level: LogLevel,
        eventCode: String,
        message: String,
        context: Map<String, String>,
        error: Throwable?,
    ) {
        synchronized(lines) {
            lines.addLast(LogEntry.create(level, eventCode, message, context, error).toJsonLine())
        }
    }

    override fun readRecent(limit: Int): List<String> = synchronized(lines) {
        lines.toList().takeLast(limit.coerceAtLeast(0))
    }

    override fun clear() = synchronized(lines) { lines.clear() }

    override fun sizeBytes(): Long = synchronized(lines) {
        lines.sumOf { it.toByteArray(StandardCharsets.UTF_8).size + 1L }
    }
}

interface InstallationIdProvider {
    fun get(): String
}

/** Stable anonymous installation identifier. It never contains account or network data. */
class AndroidInstallationIdProvider(context: Context) : InstallationIdProvider {
    private val preferences: SharedPreferences = context.getSharedPreferences(
        "remote_services_feedback",
        Context.MODE_PRIVATE,
    )

    override fun get(): String = synchronized(preferences) {
        preferences.getString(KEY_INSTALLATION_ID, null)
            ?.takeIf { it.matches(UUID_PATTERN) }
            ?: UUID.randomUUID().toString().also {
                preferences.edit().putString(KEY_INSTALLATION_ID, it).apply()
            }
    }

    private companion object {
        const val KEY_INSTALLATION_ID = "anonymous_installation_id"
        val UUID_PATTERN = Regex("[0-9a-fA-F-]{36}")
    }
}

class FixedInstallationIdProvider(private val value: String = UUID.randomUUID().toString()) : InstallationIdProvider {
    override fun get(): String = value
}

data class FeedbackConfig(
    val endpoint: String = DEFAULT_ENDPOINT,
    val maxPayloadBytes: Int = 512 * 1024,
    val connectTimeoutMillis: Int = 10_000,
    val readTimeoutMillis: Int = 15_000,
    val maxAttempts: Int = 3,
    val retryBackoffMillis: Long = 350L,
) {
    init {
        require(endpoint.isNotBlank())
        require(maxPayloadBytes in 16 * 1024..2 * 1024 * 1024)
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0)
        require(maxAttempts in 1..3)
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://app.dponnood.xin/feedback"
    }
}

data class FeedbackPackage(
    val json: String,
    val logCount: Int,
    val sizeBytes: Int,
)

/** Builds a deliberately small JSON payload only after the user explicitly requests feedback. */
class FeedbackPayloadBuilder(
    private val repository: LogRepository,
    private val installationIdProvider: InstallationIdProvider,
    private val config: FeedbackConfig = FeedbackConfig(),
    private val clock: () -> String = { Instant.now().toString() },
) {
    fun build(userDescription: String = "", maxEntries: Int = 200): FeedbackPackage {
        val safeDescription = LogSanitizer.message(userDescription)
        val allLogs = repository.readRecent(maxEntries.coerceIn(0, 500))
        val selected = allLogs.toMutableList()
        var json = buildJson(safeDescription, selected)
        while (json.toByteArray(StandardCharsets.UTF_8).size > config.maxPayloadBytes && selected.isNotEmpty()) {
            selected.removeAt(0)
            json = buildJson(safeDescription, selected)
        }
        require(json.toByteArray(StandardCharsets.UTF_8).size <= config.maxPayloadBytes) {
            "feedback payload exceeds configured size limit"
        }
        return FeedbackPackage(
            json = json,
            logCount = selected.size,
            sizeBytes = json.toByteArray(StandardCharsets.UTF_8).size,
        )
    }

    private fun buildJson(description: String, logs: List<String>): String {
        val validLines = logs.filter { it.trim().startsWith("{") && it.trim().endsWith("}") }
        val logJson = validLines.joinToString(",")
        return "{" +
            "\"schemaVersion\":1," +
            "\"installationId\":\"${jsonEscape(installationIdProvider.get())}\"," +
            "\"createdAt\":\"${jsonEscape(clock())}\"," +
            "\"description\":\"${jsonEscape(description)}\"," +
            "\"logs\":[${logJson}]" +
            "}"
    }
}

data class FeedbackHttpResponse(val statusCode: Int, val body: String = "")

fun interface FeedbackTransport {
    fun post(
        endpoint: String,
        body: ByteArray,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
    ): FeedbackHttpResponse
}

/** Default transport uses the platform trust store and never installs a permissive TLS manager. */
object UrlConnectionFeedbackTransport : FeedbackTransport {
    override fun post(
        endpoint: String,
        body: ByteArray,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
    ): FeedbackHttpResponse {
        val connection = (URL(endpoint).openConnection() as? HttpURLConnection)
            ?: error("unsupported feedback URL")
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "RemoteServices/feedback")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = input?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText().take(4_096) }.orEmpty()
            return FeedbackHttpResponse(status, responseBody)
        } finally {
            connection.disconnect()
        }
    }
}

data class FeedbackUploadResult(
    val succeeded: Boolean,
    val statusCode: Int? = null,
    val attempts: Int = 0,
    val message: String,
)

class HttpsFeedbackClient(
    private val config: FeedbackConfig = FeedbackConfig(),
    private val transport: FeedbackTransport = UrlConnectionFeedbackTransport,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    fun upload(payload: FeedbackPackage): FeedbackUploadResult {
        val endpoint = runCatching { URI(config.endpoint) }.getOrNull()
            ?: return FeedbackUploadResult(false, message = "反馈地址无效")
        if (!endpoint.scheme.equals("https", ignoreCase = true) || endpoint.host.isNullOrBlank() || endpoint.userInfo != null) {
            return FeedbackUploadResult(false, message = "反馈地址必须使用 HTTPS")
        }
        val body = payload.json.toByteArray(StandardCharsets.UTF_8)
        if (body.size > config.maxPayloadBytes) {
            return FeedbackUploadResult(false, message = "反馈内容超过大小限制")
        }
        var lastStatus: Int? = null
        repeat(config.maxAttempts) { index ->
            val attempt = index + 1
            try {
                val response = transport.post(
                    endpoint = config.endpoint,
                    body = body,
                    connectTimeoutMillis = config.connectTimeoutMillis,
                    readTimeoutMillis = config.readTimeoutMillis,
                )
                lastStatus = response.statusCode
                if (response.statusCode in 200..299) {
                    return FeedbackUploadResult(true, response.statusCode, attempt, "反馈已上传")
                }
                val retryable = response.statusCode == 408 || response.statusCode == 429 || response.statusCode >= 500
                if (!retryable || attempt == config.maxAttempts) {
                    return FeedbackUploadResult(false, response.statusCode, attempt, "服务器拒绝反馈（HTTP ${response.statusCode}）")
                }
            } catch (_: IOException) {
                if (attempt == config.maxAttempts) {
                    return FeedbackUploadResult(false, lastStatus, attempt, "网络错误，反馈未上传")
                }
            } catch (_: RuntimeException) {
                if (attempt == config.maxAttempts) {
                    return FeedbackUploadResult(false, lastStatus, attempt, "反馈上传失败")
                }
            }
            sleeper(config.retryBackoffMillis * attempt)
        }
        return FeedbackUploadResult(false, lastStatus, config.maxAttempts, "反馈上传失败")
    }
}

/** Application facade used by settings. It logs only aggregate upload status, never payload data. */
class FeedbackService(
    private val repository: LogRepository,
    installationIdProvider: InstallationIdProvider,
    private val client: HttpsFeedbackClient = HttpsFeedbackClient(),
    private val config: FeedbackConfig = FeedbackConfig(),
) {
    private val builder = FeedbackPayloadBuilder(repository, installationIdProvider, config)

    fun prepare(description: String = ""): FeedbackPackage = builder.build(description)

    fun upload(payload: FeedbackPackage): FeedbackUploadResult {
        repository.append(
            LogLevel.INFO,
            "FEEDBACK_UPLOAD_START",
            "用户确认上传错误反馈",
            context = mapOf("log_count" to payload.logCount.toString(), "payload_bytes" to payload.sizeBytes.toString()),
        )
        val result = client.upload(payload)
        repository.append(
            if (result.succeeded) LogLevel.INFO else LogLevel.WARN,
            if (result.succeeded) "FEEDBACK_UPLOAD_SUCCESS" else "FEEDBACK_UPLOAD_FAILURE",
            result.message,
            context = mapOf(
                "attempts" to result.attempts.toString(),
                "status_code" to (result.statusCode?.toString() ?: "none"),
            ),
        )
        return result
    }
}
