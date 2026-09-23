package xin.dponnood.remoteservice

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import xin.dponnood.remoteservice.feature.services.SystemInfoSnapshot

/** Minimal injectable boundary for the two read-only standard ubus calls. */
internal fun interface UbusTransport {
    suspend fun post(url: String, cookieHeader: String?, body: String): UbusHttpResponse?
}

internal data class UbusHttpResponse(
    val statusCode: Int,
    val body: String?,
    val loginRequired: Boolean = false,
)

internal data class UbusSessionAuth(
    val sid: String,
    val cookieHeader: String?,
)

internal data class UbusCallIssue(
    val method: String,
    val code: Int,
    val description: String,
    val rpcErrorCode: Int? = null,
)

internal enum class UbusFailureKind {
    NO_SESSION,
    SESSION_EXPIRED,
    ACL_DENIED,
    BUSINESS_ERROR,
    ENDPOINT_UNAVAILABLE,
    INVALID_RESPONSE,
}

internal sealed interface UbusReadResult {
    data class Success(
        val snapshot: SystemInfoSnapshot,
        val issues: List<UbusCallIssue> = emptyList(),
        val sessionRefreshed: Boolean = false,
    ) : UbusReadResult

    data class Failure(
        val kind: UbusFailureKind,
        val httpStatus: Int? = null,
        val ubusCode: Int? = null,
        val rpcErrorCode: Int? = null,
    ) : UbusReadResult
}

/**
 * Reads only system.board and system.info through the standard OpenWrt ubus
 * JSON-RPC endpoint. All HTTP behavior is behind [UbusTransport] so request
 * shape, endpoint fallback, and status handling can be tested without a router.
 */
internal class UbusSystemInfoReader(
    private val transport: UbusTransport = HttpUbusTransport(),
) {
    suspend fun read(
        origin: String,
        session: UbusSessionAuth,
        reauthenticate: (suspend () -> UbusSessionAuth?)? = null,
    ): UbusReadResult {
        val first = readOnce(origin, session)
        if (!first.needsSessionRefresh()) return first

        val freshSession = reauthenticate?.invoke()
            ?: return first.withoutAvailableRefresh()
        val retried = readOnce(origin, freshSession)
        return retried.afterRefresh()
    }

    private suspend fun readOnce(origin: String, session: UbusSessionAuth): UbusReadResult {
        val httpFailures = mutableListOf<UbusHttpResponse>()
        var hadInvalidResponse = false

        for (path in ENDPOINT_PATHS) {
            val url = origin + path
            val boardResponse = postCall(url, session, BOARD_ID, "board")
            val infoResponse = postCall(url, session, INFO_ID, "info")
            if (boardResponse == null || infoResponse == null) continue
            val responses = listOf(boardResponse, infoResponse)
            httpFailures += responses.filter { it.statusCode !in 200..299 || it.loginRequired }
            if (responses.any { it.statusCode !in 200..299 || it.loginRequired }) {
                continue
            }

            val parsed = parseResponses(boardResponse.body, infoResponse.body)
            if (parsed == null) {
                hadInvalidResponse = true
                continue
            }
            return parsed.toReadResult()
        }

        val httpFailure = httpFailures.firstOrNull { it.statusCode == 401 || it.loginRequired }
            ?: httpFailures.firstOrNull { it.statusCode == 403 }
            ?: httpFailures.lastOrNull()
        if (httpFailure != null) {
            return when {
                httpFailure.statusCode == 401 || httpFailure.loginRequired ->
                    UbusReadResult.Failure(UbusFailureKind.SESSION_EXPIRED, httpStatus = httpFailure.statusCode)
                httpFailure.statusCode == 403 ->
                    UbusReadResult.Failure(UbusFailureKind.ACL_DENIED, httpStatus = httpFailure.statusCode)
                else -> UbusReadResult.Failure(
                    UbusFailureKind.ENDPOINT_UNAVAILABLE,
                    httpStatus = httpFailure.statusCode,
                )
            }
        }
        return UbusReadResult.Failure(
            if (hadInvalidResponse) UbusFailureKind.INVALID_RESPONSE else UbusFailureKind.ENDPOINT_UNAVAILABLE,
        )
    }

    private suspend fun postCall(
        url: String,
        session: UbusSessionAuth,
        id: Int,
        method: String,
    ): UbusHttpResponse? = try {
        transport.post(url, session.cookieHeader, buildCall(session.sid, id, method))
    } catch (_: Exception) {
        null
    }

    private fun ParsedUbusResponse.toReadResult(): UbusReadResult {
        val snapshot = snapshot()
        if (snapshot != null) return UbusReadResult.Success(snapshot, issues)
        val permissionIssue = issues.firstOrNull { it.code == UBUS_PERMISSION_DENIED }
        if (permissionIssue != null) {
            return UbusReadResult.Failure(
                UbusFailureKind.ACL_DENIED,
                ubusCode = permissionIssue.code,
                rpcErrorCode = permissionIssue.rpcErrorCode,
            )
        }
        val issue = issues.firstOrNull()
        return if (issue != null) {
            UbusReadResult.Failure(
                UbusFailureKind.BUSINESS_ERROR,
                ubusCode = issue.code,
                rpcErrorCode = issue.rpcErrorCode,
            )
        } else {
            UbusReadResult.Failure(UbusFailureKind.INVALID_RESPONSE)
        }
    }

    private fun UbusReadResult.needsSessionRefresh(): Boolean = when (this) {
        is UbusReadResult.Failure -> kind == UbusFailureKind.SESSION_EXPIRED ||
            (kind == UbusFailureKind.ACL_DENIED &&
                (ubusCode == UBUS_PERMISSION_DENIED || httpStatus == 403))
        is UbusReadResult.Success -> issues.any { it.code == UBUS_PERMISSION_DENIED }
    }

    private fun UbusReadResult.withoutAvailableRefresh(): UbusReadResult = when (this) {
        is UbusReadResult.Failure -> this
        is UbusReadResult.Success -> copy(
            issues = issues.map { issue ->
                if (issue.code == UBUS_PERMISSION_DENIED) issue.copy(
                    description = "permission denied (code ${issue.code}); ACL or expired session",
                ) else issue
            },
        )
    }

    private fun UbusReadResult.afterRefresh(): UbusReadResult = when (this) {
        is UbusReadResult.Success -> {
            val stillDenied = issues.any { it.code == UBUS_PERMISSION_DENIED }
            copy(
                sessionRefreshed = true,
                issues = if (stillDenied) issues.map { issue ->
                    if (issue.code == UBUS_PERMISSION_DENIED) issue.copy(
                        description = "ACL denied after successful session refresh (code ${issue.code})",
                    ) else issue
                } else issues,
            )
        }
        is UbusReadResult.Failure -> when {
            kind == UbusFailureKind.SESSION_EXPIRED -> copy(kind = UbusFailureKind.SESSION_EXPIRED)
            kind == UbusFailureKind.ACL_DENIED -> copy(kind = UbusFailureKind.ACL_DENIED)
            else -> this
        }
    }

    private fun buildCall(sid: String, id: Int, method: String): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("method", "call")
        .put("params", JSONArray().apply {
            put(sid)
            put("system")
            put(method)
            put(JSONObject())
        })
        .toString()

    private fun parseResponses(boardBody: String?, infoBody: String?): ParsedUbusResponse? {
        val boardRoot = parseJson(boardBody) ?: return null
        val infoRoot = parseJson(infoBody) ?: return null
        val board = parseCall(boardRoot, BOARD_ID, "board") ?: return null
        val info = parseCall(infoRoot, INFO_ID, "info") ?: return null
        return ParsedUbusResponse(board.payload, info.payload, listOfNotNull(board.issue, info.issue))
    }

    private fun parseJson(body: String?): Any? {
        if (body.isNullOrBlank()) return null
        return try {
            if (body.trimStart().startsWith("[")) JSONArray(body) else JSONObject(body)
        } catch (_: JSONException) {
            null
        }
    }

    private fun parseCall(root: Any, id: Int, name: String): ParsedCall? {
        val reply = when (root) {
            is JSONArray -> (0 until root.length())
                .asSequence()
                .mapNotNull { root.opt(it) as? JSONObject }
                .firstOrNull { it.optInt("id", -1) == id }
            is JSONObject -> root.takeIf { it.optInt("id", -1) == id }
            else -> null
        } ?: return null

        val rpcError = reply.optJSONObject("error")
        if (rpcError != null) {
            val rpcCode = rpcError.optInt("code", INVALID_RESULT_CODE)
            val description = rpcError.optString("message").trim()
                .takeIf(String::isNotBlank)
                ?: "JSON-RPC error (code $rpcCode)"
            // LuCI uses this exact JSON-RPC error for an expired/denied session.
            // Do not refresh on unrelated errors that happen to reuse its code.
            val isLuCiAccessDenied = rpcCode == JSONRPC_ACCESS_DENIED &&
                description.equals(LUCI_ACCESS_DENIED_MESSAGE, ignoreCase = true)
            val ubusCode = if (isLuCiAccessDenied) {
                UBUS_PERMISSION_DENIED
            } else {
                rpcCode
            }
            return ParsedCall(
                payload = null,
                issue = UbusCallIssue(name, ubusCode, description, rpcErrorCode = rpcCode),
            )
        }

        val result = reply.opt("result") as? JSONArray ?: return null
        if (result.length() < 1) return null
        val code = result.optInt(0, INVALID_RESULT_CODE)
        if (code != 0) {
            return ParsedCall(
                payload = null,
                issue = UbusCallIssue(name, code, describeUbusCode(code)),
            )
        }
        val payload = result.opt(1) as? JSONObject ?: JSONObject()
        return ParsedCall(payload = payload, issue = null)
    }

    private data class ParsedCall(val payload: JSONObject?, val issue: UbusCallIssue?)

    private data class ParsedUbusResponse(
        val board: JSONObject?,
        val info: JSONObject?,
        val issues: List<UbusCallIssue>,
    ) {
        fun snapshot(): SystemInfoSnapshot? {
            if (board == null && info == null) return null
            val release = board?.optJSONObject("release")
            val memory = info?.optJSONObject("memory")
            val totalMemory = memory?.numberAsLong("total")
            val freeMemory = memory?.numberAsLong("free")
            val swap = info?.optJSONObject("swap")
            val totalSwap = swap?.numberAsLong("total")
            val freeSwap = swap?.numberAsLong("free")
            val usedMemory = if (totalMemory != null && freeMemory != null && totalMemory >= freeMemory) {
                totalMemory - freeMemory
            } else {
                null
            }
            val usedSwap = if (totalSwap != null && freeSwap != null && totalSwap >= freeSwap) {
                totalSwap - freeSwap
            } else {
                null
            }
            val load = info?.optJSONArray("load")
            val uptimeSeconds = info?.numberAsLong("uptime")
            return SystemInfoSnapshot(
                hostname = board?.nonBlankString("hostname"),
                model = board?.nonBlankString("model") ?: board?.nonBlankString("board_name"),
                osName = release?.nonBlankString("distribution"),
                firmware = release?.nonBlankString("version") ?: release?.nonBlankString("revision"),
                kernel = board?.nonBlankString("kernel"),
                // Standard system.info exposes load averages, not a CPU percentage.
                cpuUsagePercent = null,
                cpuTemperatureCelsius = null,
                memoryUsedBytes = usedMemory,
                memoryTotalBytes = totalMemory?.takeIf { it > 0 },
                storageUsedBytes = null,
                storageTotalBytes = null,
                uptimeMillis = uptimeSeconds?.takeIf { it >= 0L && it <= Long.MAX_VALUE / 1_000L }
                    ?.times(1_000L),
                rxBytesPerSecond = null,
                txBytesPerSecond = null,
                memoryFreeBytes = freeMemory?.takeIf { it >= 0L },
                memorySharedBytes = memory?.numberAsLong("shared")?.takeIf { it >= 0L },
                memoryBufferedBytes = memory?.numberAsLong("buffered")?.takeIf { it >= 0L },
                swapUsedBytes = usedSwap,
                swapTotalBytes = totalSwap?.takeIf { it >= 0L },
                loadAverage1 = load?.finiteNonNegativeFloat(0),
                loadAverage5 = load?.finiteNonNegativeFloat(1),
                loadAverage15 = load?.finiteNonNegativeFloat(2),
            )
        }
    }

    private companion object {
        const val BOARD_ID = 1
        const val INFO_ID = 2
        const val INVALID_RESULT_CODE = Int.MIN_VALUE
        const val UBUS_PERMISSION_DENIED = 6
        const val JSONRPC_ACCESS_DENIED = -32002
        const val LUCI_ACCESS_DENIED_MESSAGE = "Access denied"
        val ENDPOINT_PATHS = listOf("/cgi-bin/luci/admin/ubus", "/ubus")

        fun describeUbusCode(code: Int): String = when (code) {
            1 -> "invalid command (code 1)"
            2 -> "invalid argument (code 2)"
            3 -> "method not found (code 3)"
            4 -> "object not found (code 4)"
            5 -> "no response (code 5)"
            6 -> "permission denied (code 6)"
            7 -> "timeout (code 7)"
            8 -> "not supported (code 8)"
            9 -> "unknown ubus error (code 9)"
            else -> "ubus business error (code $code)"
        }

        private fun JSONObject.nonBlankString(key: String): String? =
            optString(key).trim().takeIf { it.isNotBlank() && it != "null" }

        private fun JSONObject.numberAsLong(key: String): Long? {
            val value = opt(key)
            return when (value) {
                is Number -> value.toLong()
                else -> null
            }
        }

        private fun JSONArray.finiteNonNegativeFloat(index: Int): Float? {
            if (index !in 0 until length()) return null
            val value = optDouble(index, Double.NaN)
            return value.takeIf { it.isFinite() && it >= 0.0 }?.toFloat()
        }
    }
}

/** Production HTTP transport. It never writes request details to logs. */
private class HttpUbusTransport : UbusTransport {
    override suspend fun post(url: String, cookieHeader: String?, body: String): UbusHttpResponse? =
        withContext(Dispatchers.IO) {
            val connection = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull()
                ?: return@withContext null
            try {
                connection.connectTimeout = 4_000
                connection.readTimeout = 5_000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Cache-Control", "no-cache")
                cookieHeader?.takeIf(String::isNotBlank)?.let { connection.setRequestProperty("Cookie", it) }
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
                val status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val responseBody = stream?.use { it.readBoundedUtf8(MAX_RESPONSE_BYTES) }
                UbusHttpResponse(
                    statusCode = status,
                    body = responseBody,
                    loginRequired = connection.getHeaderField("X-LuCI-Login-Required")
                        ?.equals("yes", ignoreCase = true) == true,
                )
            } catch (_: Exception) {
                null
            } finally {
                connection.disconnect()
            }
        }

    private companion object {
        const val MAX_RESPONSE_BYTES = 512 * 1024
    }
}
