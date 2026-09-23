package xin.dponnood.remoteservice

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import xin.dponnood.remoteservice.core.logging.LogLevel
import xin.dponnood.remoteservice.core.logging.LogRepository

/**
 * The OpenClash LuCI page exposes two related JSON endpoints.  `status`
 * contains the forwarding TLS flag while `conn_status` is available on older
 * OpenClash builds, so the client tries the richer response first and then
 * falls back without changing the authentication boundary.
 */
internal class OpenClashControllerStatusClient(
    private val logRepository: LogRepository? = null,
) {
    suspend fun fetch(origin: String, cookie: String?): OpenClashControllerStatusResult =
        withContext(Dispatchers.IO) {
            var lastAttempt: StatusAttempt? = null
            var requiresSessionRefresh = false
            for (path in STATUS_PATHS) {
                val attempt = request(origin, path, cookie)
                if (attempt.info != null) {
                    return@withContext OpenClashControllerStatusResult(attempt.info, false)
                }
                if (isSessionRefreshStatus(attempt.statusCode)) {
                    requiresSessionRefresh = true
                }
                lastAttempt = attempt
            }
            appendFailure(origin, lastAttempt)
            OpenClashControllerStatusResult(null, requiresSessionRefresh)
        }

    private fun request(origin: String, path: String, cookie: String?): StatusAttempt {
        val url = "$origin/cgi-bin/luci/admin/services/openclash/$path"
        val connection = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull()
            ?: return StatusAttempt(path, null, null, "connection")
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            cookie?.takeIf(String::isNotBlank)?.let { connection.setRequestProperty("Cookie", it) }
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                return StatusAttempt(path, null, statusCode, "http")
            }
            val body = connection.inputStream.use { it.readBoundedUtf8(MAX_BODY_BYTES) }
            val info = parse(body)
            StatusAttempt(path, info, statusCode, if (info == null) "parse" else null)
        } catch (failure: Exception) {
            StatusAttempt(path, null, runCatching { connection.responseCode }.getOrNull(), failure::class.java.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    private fun appendFailure(origin: String, attempt: StatusAttempt?) {
        val repository = logRepository ?: return
        val now = System.currentTimeMillis()
        if (!lastFailureLogAt.claimLogInterval(origin, now, FAILURE_LOG_INTERVAL_MS)) return
        repository.append(
            LogLevel.WARN,
            "OPENCLASH_CONTROLLER_STATUS_UNAVAILABLE",
            "OpenClash LuCI 状态接口读取失败",
            context = buildMap {
                put("origin_host", origin.substringAfter("://").substringBefore('/'))
                attempt?.path?.let { put("path", it) }
                attempt?.statusCode?.let { put("status_code", it.toString()) }
                attempt?.errorType?.let { put("error_type", it) }
            },
        )
    }

    private fun parse(body: String): OpenClashControllerInfo? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return OpenClashControllerInfo(
            running = json.optBoolean("clash").takeIf { json.has("clash") },
            secret = json.optString("dase").trim().takeIf(String::isNotBlank),
            runMode = json.optString("run_mode").trim().takeIf(String::isNotBlank),
            configName = json.optString("config_name").trim().takeIf(String::isNotBlank),
            controllerHost = json.optString("daip").trim().takeIf(String::isNotBlank),
            controllerPort = port(json, "cn_port"),
            forwardDomain = json.optString("db_foward_domain").trim().takeIf(String::isNotBlank),
            forwardPort = port(json, "db_foward_port"),
            forwardSsl = ssl(json.opt("db_forward_ssl")),
        )
    }

    private fun port(json: JSONObject, key: String): Int? = json.opt(key)?.toString()
        ?.trim()
        ?.toIntOrNull()
        ?.takeIf { it in 1..65535 }

    private fun ssl(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> when (value.trim().lowercase()) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> null
        }
        else -> null
    }

    private companion object {
        val STATUS_PATHS = listOf("status", "conn_status")
        const val CONNECT_TIMEOUT_MS = 2_500
        const val READ_TIMEOUT_MS = 3_000
        const val MAX_BODY_BYTES = 256 * 1024
        const val FAILURE_LOG_INTERVAL_MS = 30_000L
        val lastFailureLogAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    }

    private data class StatusAttempt(
        val path: String,
        val info: OpenClashControllerInfo?,
        val statusCode: Int?,
        val errorType: String?,
    )
}

internal data class OpenClashControllerStatusResult(
    val info: OpenClashControllerInfo?,
    val requiresSessionRefresh: Boolean,
)

internal fun isSessionRefreshStatus(statusCode: Int?): Boolean =
    statusCode == 401 || statusCode == 403

internal suspend fun refreshOpenClashStatusOnce(
    initial: OpenClashControllerStatusResult,
    reauthenticate: suspend () -> String?,
    fetch: suspend (cookie: String) -> OpenClashControllerStatusResult,
): OpenClashControllerStatusResult {
    if (!initial.requiresSessionRefresh) return initial
    val cookie = reauthenticate()?.takeIf(String::isNotBlank) ?: return initial
    return fetch(cookie)
}

internal fun ConcurrentHashMap<String, Long>.claimLogInterval(
    key: String,
    nowMillis: Long,
    intervalMillis: Long,
): Boolean {
    var shouldLog = false
    compute(key) { _, previous ->
        if (previous == null || nowMillis - previous >= intervalMillis) {
            shouldLog = true
            nowMillis
        } else {
            previous
        }
    }
    return shouldLog
}

/** Parsed fields shared by the native status card and the Zashboard opener. */
internal data class OpenClashControllerInfo(
    val running: Boolean?,
    val secret: String?,
    val runMode: String?,
    val configName: String?,
    val controllerHost: String?,
    val controllerPort: Int?,
    val forwardDomain: String?,
    val forwardPort: Int?,
    val forwardSsl: Boolean?,
)
