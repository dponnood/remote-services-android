package xin.dponnood.remoteservice

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xin.dponnood.remoteservice.core.model.ConnectionPolicy
import xin.dponnood.remoteservice.core.model.ServiceConfig
import xin.dponnood.remoteservice.core.model.ServiceType
import xin.dponnood.remoteservice.core.network.HealthProbe
import xin.dponnood.remoteservice.core.network.HealthProbeResult
import xin.dponnood.remoteservice.core.network.RouteKind
import xin.dponnood.remoteservice.core.network.RouteResolution
import xin.dponnood.remoteservice.core.network.RouteResolver
import xin.dponnood.remoteservice.core.network.SsidPermissionState
import xin.dponnood.remoteservice.core.network.SsidProvider
import xin.dponnood.remoteservice.feature.services.SystemInfoResult

class IStoreSystemInfoProviderTest {
    @Test
    fun autoRouteFallsBackToWanAfterLanUbusFailureWithOriginScopedSession() = runBlocking {
        val fixture = Fixture()

        val result = fixture.provider.load(fixture.service)

        assertTrue(result is SystemInfoResult.Success)
        assertEquals(listOf(LAN_URL, WAN_URL), fixture.probedUrls)
        assertEquals(listOf(LAN_ORIGIN, LAN_ORIGIN, WAN_ORIGIN), fixture.sessionsEnsuredFor)
        assertEquals(1, fixture.refreshCount)
        assertEquals(6, fixture.calls.size)
        assertTrue(fixture.calls.take(4).all { it.url.startsWith(LAN_ORIGIN) })
        assertTrue(fixture.calls.take(4).all { it.cookie == LAN_COOKIE && it.sid == LAN_SID })
        assertTrue(fixture.calls.drop(4).all { it.url.startsWith(WAN_ORIGIN) })
        assertTrue(fixture.calls.drop(4).all { it.cookie == WAN_COOKIE && it.sid == WAN_SID })
    }

    @Test
    fun internalOnlyPolicyNeverFallsBackToWanForUbusFailure() = runBlocking {
        val fixture = Fixture(connectionPolicy = ConnectionPolicy.INTERNAL_ONLY)

        val result = fixture.provider.load(fixture.service)

        assertTrue(result is SystemInfoResult.Unavailable)
        assertEquals(listOf(LAN_URL), fixture.probedUrls)
        assertEquals(1, fixture.refreshCount)
        assertEquals(4, fixture.calls.size)
        assertTrue(fixture.calls.all { it.url.startsWith(LAN_ORIGIN) })
    }

    private class Fixture(
        connectionPolicy: ConnectionPolicy = ConnectionPolicy.AUTO,
    ) {
        val service = ServiceConfig(
            id = "router",
            displayName = "iStoreOS",
            lanUrl = LAN_URL,
            wanUrl = WAN_URL,
            trustedSsids = setOf(TRUSTED_SSID),
            serviceType = ServiceType.ISTORE,
            connectionPolicy = connectionPolicy,
        )
        val probedUrls = mutableListOf<String>()
        val sessionsEnsuredFor = mutableListOf<String>()
        val calls = mutableListOf<RecordedCall>()
        var refreshCount = 0

        private val resolver = RouteResolver(
            ssidProvider = object : SsidProvider {
                override fun currentSsid(): String = TRUSTED_SSID
                override fun permissionState(): SsidPermissionState = SsidPermissionState.AVAILABLE
            },
            healthProbe = object : HealthProbe {
                override suspend fun probe(url: String, path: String): HealthProbeResult {
                    probedUrls += url
                    return HealthProbeResult(reachable = true, statusCode = 200)
                }
            },
        )
        private val sessionAccess = object : IStoreSystemInfoSessionAccess {
            override suspend fun ensure(
                service: ServiceConfig,
                resolution: RouteResolution,
            ): IStoreSessionResult {
                val origin = IStoreSystemInfoProvider.originOf(resolution.endpoint.url).orEmpty()
                sessionsEnsuredFor += origin
                val internal = resolution.endpoint.kind == RouteKind.INTERNAL
                return IStoreSessionResult.Authenticated(
                    IStoreSession(
                        origin = origin,
                        routeKind = resolution.endpoint.kind,
                        cookieHeader = if (internal) LAN_COOKIE else WAN_COOKIE,
                        sessionId = if (internal) LAN_SID else WAN_SID,
                    ),
                )
            }

            override suspend fun forceReauthenticate(
                service: ServiceConfig,
                resolution: RouteResolution,
            ): IStoreSessionResult {
                refreshCount++
                return ensure(service, resolution)
            }

            override suspend fun readCookie(origin: String): String? = null
        }
        private val transport = UbusTransport { url, cookieHeader, body ->
            val request = JSONObject(body)
            val params = request.getJSONArray("params")
            val sid = params.getString(0)
            calls += RecordedCall(url, cookieHeader, sid)
            if (url.startsWith(LAN_ORIGIN)) {
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
                val method = params.getString(2)
                val payload = if (method == "board") {
                    JSONObject().put("hostname", "router")
                } else {
                    JSONObject().put("uptime", 60)
                }
                UbusHttpResponse(
                    statusCode = 200,
                    body = JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("id", request.getInt("id"))
                        .put("result", JSONArray().put(0).put(payload))
                        .toString(),
                )
            }
        }

        val provider = IStoreSystemInfoProvider(
            _context = null,
            routeResolver = resolver,
            sessionManager = sessionAccess,
            reader = UbusSystemInfoReader(transport),
        )
    }

    private data class RecordedCall(
        val url: String,
        val cookie: String?,
        val sid: String,
    )

    private companion object {
        const val TRUSTED_SSID = "Home WiFi"
        const val LAN_URL = "http://192.168.1.1/cgi-bin/luci"
        const val WAN_URL = "https://router.example/cgi-bin/luci"
        const val LAN_ORIGIN = "http://192.168.1.1"
        const val WAN_ORIGIN = "https://router.example"
        const val LAN_COOKIE = "sysauth=lan-cookie"
        const val WAN_COOKIE = "sysauth=wan-cookie"
        const val LAN_SID = "lan-session"
        const val WAN_SID = "wan-session"
    }
}
