package xin.dponnood.remoteservice

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UbusSystemInfoReaderTest {
    @Test
    fun sendsOnlyStandardSystemCallsAndMapsStandardFields() = runBlocking {
        val transport = RecordingTransport { _, _, body ->
            val request = JSONObject(body)
            when (request.getJSONArray("params").getString(2)) {
                "board" -> rpcReply(
                    request.getInt("id"),
                    payload = JSONObject()
                        .put("hostname", "router")
                        .put("model", "Example Router")
                        .put("board_name", "vendor,model")
                        .put("kernel", "6.6.88")
                        .put("release", JSONObject()
                            .put("distribution", "OpenWrt")
                            .put("version", "24.10.0")
                            .put("revision", "r12345")),
                )
                "info" -> rpcReply(
                    request.getInt("id"),
                    payload = JSONObject()
                        .put("uptime", 12_345)
                        .put("load", JSONArray().put(0.2).put(0.1).put(0.05))
                        .put("memory", JSONObject()
                            .put("total", 1_024)
                            .put("free", 256)
                            .put("shared", 32)
                            .put("buffered", 48))
                        .put("swap", JSONObject().put("total", 512).put("free", 128)),
                )
                else -> error("Unexpected ubus method")
            }
        }

        val result = UbusSystemInfoReader(transport).read(
            origin = "https://router.example",
            session = UbusSessionAuth("session-id", "sysauth=session-cookie"),
        )

        assertTrue(result is UbusReadResult.Success)
        val snapshot = (result as UbusReadResult.Success).snapshot
        assertEquals("router", snapshot.hostname)
        assertEquals("Example Router", snapshot.model)
        assertEquals("OpenWrt", snapshot.osName)
        assertEquals("24.10.0", snapshot.firmware)
        assertEquals("6.6.88", snapshot.kernel)
        assertEquals(768L, snapshot.memoryUsedBytes)
        assertEquals(1_024L, snapshot.memoryTotalBytes)
        assertEquals(256L, snapshot.memoryFreeBytes)
        assertEquals(32L, snapshot.memorySharedBytes)
        assertEquals(48L, snapshot.memoryBufferedBytes)
        assertEquals(384L, snapshot.swapUsedBytes)
        assertEquals(512L, snapshot.swapTotalBytes)
        assertEquals(0.2f, snapshot.loadAverage1!!, 0.0001f)
        assertEquals(0.1f, snapshot.loadAverage5!!, 0.0001f)
        assertEquals(0.05f, snapshot.loadAverage15!!, 0.0001f)
        assertEquals(12_345_000L, snapshot.uptimeMillis)
        assertNull(snapshot.cpuUsagePercent)
        assertNull(snapshot.cpuTemperatureCelsius)
        assertNull(snapshot.storageUsedBytes)
        assertNull(snapshot.storageTotalBytes)
        assertNull(snapshot.rxBytesPerSecond)
        assertNull(snapshot.txBytesPerSecond)

        assertEquals(2, transport.calls.size)
        transport.calls.forEach { call ->
            assertEquals("https://router.example/cgi-bin/luci/admin/ubus", call.url)
            val request = JSONObject(call.body)
            assertEquals("2.0", request.getString("jsonrpc"))
            assertEquals("call", request.getString("method"))
            val params = request.getJSONArray("params")
            assertEquals("session-id", params.getString(0))
            assertEquals("system", params.getString(1))
            assertTrue(params.getString(2) in setOf("board", "info"))
            assertEquals(0, params.getJSONObject(3).length())
            assertFalse(call.url.contains("/istore/"))
        }
        assertEquals(setOf(1, 2), transport.calls.map { JSONObject(it.body).getInt("id") }.toSet())
    }

    @Test
    fun fallsBackToUbusWhenPreferredEndpointFails() = runBlocking {
        val transport = RecordingTransport { url, _, body ->
            if (url.endsWith("/cgi-bin/luci/admin/ubus")) {
                UbusHttpResponse(statusCode = 404, body = null)
            } else {
                successfulReply(body)
            }
        }

        val result = UbusSystemInfoReader(transport).read(ORIGIN, SESSION)

        assertTrue(result is UbusReadResult.Success)
        assertEquals(
            listOf(
                "$ORIGIN/cgi-bin/luci/admin/ubus",
                "$ORIGIN/cgi-bin/luci/admin/ubus",
                "$ORIGIN/ubus",
                "$ORIGIN/ubus",
            ),
            transport.calls.map { it.url },
        )
    }

    @Test
    fun reportsNonzeroInnerUbusBusinessResult() = runBlocking {
        val transport = RecordingTransport { _, _, body ->
            val request = JSONObject(body)
            rpcReply(request.getInt("id"), code = 3)
        }

        val result = UbusSystemInfoReader(transport).read(ORIGIN, SESSION)

        assertEquals(
            UbusReadResult.Failure(UbusFailureKind.BUSINESS_ERROR, ubusCode = 3),
            result,
        )
    }

    @Test
    fun jsonRpcAccessDeniedErrorTriggersSingleSessionRefresh() = runBlocking {
        var refreshCount = 0
        val transport = RecordingTransport { _, _, body ->
            val request = JSONObject(body)
            val sid = request.getJSONArray("params").getString(0)
            if (sid == "old-sid") {
                UbusHttpResponse(
                    statusCode = 200,
                    body = JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("id", request.getInt("id"))
                        .put(
                            "error",
                            JSONObject()
                                .put("code", -32002)
                                .put("message", "Access denied"),
                        )
                        .toString(),
                )
            } else {
                successfulReply(body)
            }
        }

        val result = UbusSystemInfoReader(transport).read(
            ORIGIN,
            UbusSessionAuth("old-sid", "sysauth=old-cookie"),
        ) {
            refreshCount++
            UbusSessionAuth("new-sid", "sysauth=new-cookie")
        }

        assertTrue(result is UbusReadResult.Success)
        assertTrue((result as UbusReadResult.Success).sessionRefreshed)
        assertEquals(1, refreshCount)
        assertEquals(4, transport.calls.size)
        assertEquals(
            listOf("old-sid", "old-sid", "new-sid", "new-sid"),
            transport.calls.map { JSONObject(it.body).getJSONArray("params").getString(0) },
        )
        assertEquals(
            listOf("sysauth=old-cookie", "sysauth=old-cookie", "sysauth=new-cookie", "sysauth=new-cookie"),
            transport.calls.map { it.cookie },
        )
    }

    @Test
    fun unrelatedJsonRpcErrorWithAccessDeniedCodeDoesNotTriggerSessionRefresh() = runBlocking {
        var refreshCount = 0
        val transport = RecordingTransport { _, _, body ->
            val request = JSONObject(body)
            rpcErrorReply(request.getInt("id"), -32002, "Internal error")
        }

        val result = UbusSystemInfoReader(transport).read(ORIGIN, SESSION) {
            refreshCount++
            UbusSessionAuth("fresh-sid", "sysauth=fresh-cookie")
        }

        assertEquals(
            UbusReadResult.Failure(
                UbusFailureKind.BUSINESS_ERROR,
                ubusCode = -32002,
                rpcErrorCode = -32002,
            ),
            result,
        )
        assertEquals(0, refreshCount)
        assertEquals(2, transport.calls.size)
    }

    @Test
    fun persistentJsonRpcAccessDeniedPreservesRawCodeAfterSingleRefresh() = runBlocking {
        var refreshCount = 0
        val transport = RecordingTransport { _, _, body ->
            val request = JSONObject(body)
            rpcErrorReply(request.getInt("id"), -32002, "Access denied")
        }

        val result = UbusSystemInfoReader(transport).read(ORIGIN, SESSION) {
            refreshCount++
            UbusSessionAuth("fresh-sid", "sysauth=fresh-cookie")
        }

        assertEquals(
            UbusReadResult.Failure(
                UbusFailureKind.ACL_DENIED,
                ubusCode = 6,
                rpcErrorCode = -32002,
            ),
            result,
        )
        assertEquals(1, refreshCount)
        assertEquals(4, transport.calls.size)
    }

    @Test
    fun retriesOnceWithFreshSessionWhenInnerPermissionErrorMayBeExpiredSid() = runBlocking {
        var refreshCount = 0
        val transport = RecordingTransport { _, _, body ->
            val request = JSONObject(body)
            val params = request.getJSONArray("params")
            if (params.getString(0) == "old-sid") {
                rpcReply(request.getInt("id"), code = 6)
            } else {
                successfulReply(body)
            }
        }

        val result = UbusSystemInfoReader(transport).read(ORIGIN, UbusSessionAuth("old-sid", null)) {
            refreshCount++
            UbusSessionAuth("new-sid", "sysauth=fresh-cookie")
        }

        assertTrue(result is UbusReadResult.Success)
        assertTrue((result as UbusReadResult.Success).sessionRefreshed)
        assertEquals(1, refreshCount)
        assertEquals(4, transport.calls.size)
    }

    @Test
    fun reportsAclDenialWhenPermissionErrorRemainsAfterSingleRefresh() = runBlocking {
        var refreshCount = 0
        val transport = RecordingTransport { _, _, body ->
            rpcReply(JSONObject(body).getInt("id"), code = 6)
        }

        val result = UbusSystemInfoReader(transport).read(ORIGIN, SESSION) {
            refreshCount++
            UbusSessionAuth("fresh-sid", "sysauth=fresh-cookie")
        }

        assertEquals(
            UbusReadResult.Failure(UbusFailureKind.ACL_DENIED, ubusCode = 6),
            result,
        )
        assertEquals(1, refreshCount)
        assertEquals(4, transport.calls.size)
    }

    @Test
    fun fallback404DoesNotHideEarlierSessionExpiredStatus() = runBlocking {
        val transport = RecordingTransport { url, _, _ ->
            if (url.endsWith("/cgi-bin/luci/admin/ubus")) {
                UbusHttpResponse(statusCode = 401, body = null)
            } else {
                UbusHttpResponse(statusCode = 404, body = null)
            }
        }

        val result = UbusSystemInfoReader(transport).read(ORIGIN, SESSION)

        assertEquals(UbusReadResult.Failure(UbusFailureKind.SESSION_EXPIRED, httpStatus = 401), result)
        assertEquals(4, transport.calls.size)
    }

    @Test
    fun http200LoginRequiredHeaderTriggersOnlyOneSessionRefresh() = runBlocking {
        var refreshCount = 0
        val transport = RecordingTransport { _, _, body ->
            val params = JSONObject(body).getJSONArray("params")
            if (params.getString(0) == "old-sid") {
                UbusHttpResponse(statusCode = 200, body = "login page", loginRequired = true)
            } else {
                successfulReply(body)
            }
        }

        val result = UbusSystemInfoReader(transport).read(ORIGIN, UbusSessionAuth("old-sid", null)) {
            refreshCount++
            UbusSessionAuth("new-sid", "sysauth=fresh-cookie")
        }

        assertTrue(result is UbusReadResult.Success)
        assertTrue((result as UbusReadResult.Success).sessionRefreshed)
        assertEquals(1, refreshCount)
        assertEquals(6, transport.calls.size)
    }

    @Test
    fun persistentHttp403AfterSingleRefreshIsReportedAsAclDenied() = runBlocking {
        var refreshCount = 0
        val transport = RecordingTransport { url, _, _ ->
            if (url.endsWith("/cgi-bin/luci/admin/ubus")) {
                UbusHttpResponse(statusCode = 403, body = null)
            } else {
                UbusHttpResponse(statusCode = 404, body = null)
            }
        }

        val result = UbusSystemInfoReader(transport).read(ORIGIN, SESSION) {
            refreshCount++
            UbusSessionAuth("fresh-sid", "sysauth=fresh-cookie")
        }

        assertEquals(UbusReadResult.Failure(UbusFailureKind.ACL_DENIED, httpStatus = 403), result)
        assertEquals(1, refreshCount)
        assertEquals(8, transport.calls.size)
    }

    private fun successfulReply(body: String): UbusHttpResponse {
        val request = JSONObject(body)
        val method = request.getJSONArray("params").getString(2)
        val payload = if (method == "board") {
            JSONObject().put("hostname", "router")
        } else {
            JSONObject().put("uptime", 60)
        }
        return rpcReply(request.getInt("id"), payload = payload)
    }

    private fun rpcReply(
        id: Int,
        code: Int = 0,
        payload: JSONObject = JSONObject(),
    ): UbusHttpResponse = UbusHttpResponse(
        statusCode = 200,
        body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("result", JSONArray().put(code).apply { if (code == 0) put(payload) })
            .toString(),
    )

    private fun rpcErrorReply(id: Int, code: Int, message: String): UbusHttpResponse = UbusHttpResponse(
        statusCode = 200,
        body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("error", JSONObject().put("code", code).put("message", message))
            .toString(),
    )

    private class RecordingTransport(
        private val responder: (url: String, cookie: String?, body: String) -> UbusHttpResponse?,
    ) : UbusTransport {
        val calls = mutableListOf<RecordedCall>()

        override suspend fun post(url: String, cookieHeader: String?, body: String): UbusHttpResponse? {
            calls += RecordedCall(url, cookieHeader, body)
            return responder(url, cookieHeader, body)
        }
    }

    private data class RecordedCall(val url: String, val cookie: String?, val body: String)

    private companion object {
        const val ORIGIN = "https://router.example"
        val SESSION = UbusSessionAuth("session-id", "sysauth=session-cookie")
    }
}
