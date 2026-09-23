package xin.dponnood.remoteservice

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xin.dponnood.remoteservice.core.network.RouteKind

class OpenClashRegressionTest {
    @Test
    fun expiredControllerSessionGetsOnlyOneRefreshAndRetry() = runBlocking {
        var refreshCalls = 0
        var fetchCalls = 0
        val initial = OpenClashControllerStatusResult(info = null, requiresSessionRefresh = true)

        val result = refreshOpenClashStatusOnce(
            initial = initial,
            reauthenticate = {
                refreshCalls++
                "refreshed-cookie"
            },
            fetch = { cookie ->
                assertEquals("refreshed-cookie", cookie)
                fetchCalls++
                OpenClashControllerStatusResult(info = null, requiresSessionRefresh = true)
            },
        )

        assertTrue(result.requiresSessionRefresh)
        assertEquals(1, refreshCalls)
        assertEquals(1, fetchCalls)
    }

    @Test
    fun onlyUnauthorizedResponsesRequestSessionRefresh() {
        assertTrue(isSessionRefreshStatus(401))
        assertTrue(isSessionRefreshStatus(403))
        assertFalse(isSessionRefreshStatus(404))
        assertFalse(isSessionRefreshStatus(null))
    }

    @Test
    fun internalApiPrefersParsedControllerHostAndPort() {
        assertEquals(
            "http://192.168.1.2:8877",
            openClashApiBaseUrl(
                "http://router.lan/cgi-bin/luci",
                RouteKind.INTERNAL,
                controllerHost = "192.168.1.2",
                controllerPort = 8877,
            ),
        )
    }

    @Test
    fun internalApiFallsBackToRouteHostWhenControllerHostIsMissingOrInvalid() {
        assertEquals(
            "http://router.lan:8877",
            openClashApiBaseUrl(
                "http://router.lan/cgi-bin/luci",
                RouteKind.INTERNAL,
                controllerHost = null,
                controllerPort = 8877,
            ),
        )
        assertEquals(
            "http://router.lan:8877",
            openClashApiBaseUrl(
                "http://router.lan/cgi-bin/luci",
                RouteKind.INTERNAL,
                controllerHost = "user@attacker.example:80",
                controllerPort = 8877,
            ),
        )
        assertEquals(
            "http://192.168.1.1:9090",
            openClashApiBaseUrl(
                "http://192.168.1.1/cgi-bin/luci",
                RouteKind.INTERNAL,
                controllerHost = null,
                controllerPort = null,
            ),
        )
    }

    @Test
    fun publicApiIgnoresLanControllerHostAndUsesForwardDomain() {
        assertEquals(
            "https://clash.example.com",
            openClashApiBaseUrl(
                "https://i.example.com/cgi-bin/luci",
                RouteKind.PUBLIC,
                controllerHost = "192.168.1.1",
                controllerPort = 8877,
            ),
        )
    }

    @Test
    fun responseReaderStopsAtUtf8ByteLimit() {
        val input = ByteArrayInputStream("你abc".toByteArray(StandardCharsets.UTF_8))

        val body = input.readBoundedUtf8(3)

        assertEquals("你", body)
        assertEquals(3, input.available())
    }

    @Test
    fun suppressedFailureLogsDoNotMoveTheRateLimitWindow() {
        val lastLoggedAt = ConcurrentHashMap<String, Long>()

        assertTrue(lastLoggedAt.claimLogInterval("origin", nowMillis = 1_000, intervalMillis = 100))
        assertFalse(lastLoggedAt.claimLogInterval("origin", nowMillis = 1_050, intervalMillis = 100))
        assertFalse(lastLoggedAt.claimLogInterval("origin", nowMillis = 1_099, intervalMillis = 100))
        assertTrue(lastLoggedAt.claimLogInterval("origin", nowMillis = 1_100, intervalMillis = 100))
        assertEquals(1_100L, lastLoggedAt["origin"])
    }
}
