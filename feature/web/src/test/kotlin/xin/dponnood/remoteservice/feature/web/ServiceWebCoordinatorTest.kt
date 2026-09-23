package xin.dponnood.remoteservice.feature.web

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xin.dponnood.remoteservice.core.model.ServiceConfig
import xin.dponnood.remoteservice.core.model.ServiceType
import xin.dponnood.remoteservice.core.network.HealthProbe
import xin.dponnood.remoteservice.core.network.HealthProbeResult
import xin.dponnood.remoteservice.core.network.RouteResolver
import xin.dponnood.remoteservice.core.network.SsidPermissionState
import xin.dponnood.remoteservice.core.network.SsidProvider

class ServiceWebCoordinatorTest {
    @Test
    fun buildsExactOriginTargetAfterRouteResolution() = runTest {
        val service = ServiceConfig(
            id = "luci",
            displayName = "软路由",
            lanUrl = "https://192.168.1.1:8443/cgi-bin/luci",
            wanUrl = "https://router.example/cgi-bin/luci",
        )
        val resolver = RouteResolver(
            object : SsidProvider {
                override fun currentSsid() = "Home"
                override fun permissionState() = SsidPermissionState.AVAILABLE
            },
            object : HealthProbe {
                override suspend fun probe(url: String, path: String): HealthProbeResult =
                    HealthProbeResult(reachable = true, statusCode = 200)
            },
        )

        val result = ServiceWebCoordinator(resolver).resolve(service, setOf("Home"))

        val ready = result as ServiceWebOpenResult.Ready
        assertEquals(
            setOf("https://192.168.1.1:8443", "https://router.example"),
            ready.target.allowedOrigins,
        )
        assertEquals("https://192.168.1.1:8443", ready.target.sessionKey.host)
        assertTrue(ready.resolution.endpoint.kind.name == "INTERNAL")
    }

    @Test
    fun httpsOriginRejectsNonHttpsAndPreservesCustomPort() {
        assertEquals("https://router.example", ServiceWebCoordinator.httpsOrigin("https://router.example/path"))
        assertEquals("https://192.168.1.1:8443", ServiceWebCoordinator.httpsOrigin("https://192.168.1.1:8443/cgi-bin/luci"))
        assertEquals(null, ServiceWebCoordinator.httpsOrigin("http://router.example/path"))
        assertEquals(null, ServiceWebCoordinator.httpsOrigin("https://user:pass@router.example/path"))
    }

    @Test
    fun webOriginAcceptsHttpAndKeepsEffectivePort() {
        assertEquals("http://router.example", ServiceWebCoordinator.webOrigin("http://router.example/cgi-bin/luci"))
        assertEquals("http://192.168.1.1:8080", ServiceWebCoordinator.webOrigin("http://192.168.1.1:8080/cgi-bin/luci"))
        assertEquals("http://router.example", ServiceWebCoordinator.webOrigin("http://router.example:80/path"))
        assertEquals(null, ServiceWebCoordinator.webOrigin("ftp://router.example/path"))
        assertEquals(null, ServiceWebCoordinator.webOrigin("http://user:pass@router.example/path"))
    }

    @Test
    fun resolvesHttpTargetAndUsesHttpOriginAllowList() = runTest {
        val service = ServiceConfig(
            id = "legacy-luci",
            displayName = "旧版软路由",
            lanUrl = "http://192.168.1.1:8080/cgi-bin/luci",
        )
        val resolver = RouteResolver(
            object : SsidProvider {
                override fun currentSsid() = "Home"
                override fun permissionState() = SsidPermissionState.AVAILABLE
            },
            object : HealthProbe {
                override suspend fun probe(url: String, path: String): HealthProbeResult =
                    HealthProbeResult(reachable = true, statusCode = 200)
            },
        )

        val result = ServiceWebCoordinator(resolver).resolve(service, setOf("Home"))

        val ready = result as ServiceWebOpenResult.Ready
        assertEquals("http://192.168.1.1:8080", ready.target.allowedOrigins.single())
        assertTrue(SecureWebViewController.isAllowedOrigin(ready.target.url, ready.target.allowedOrigins))
    }

    @Test
    fun luciAuthenticationResponseStillOpensWebTargetForLogin() = runTest {
        val service = ServiceConfig(
            id = "istore",
            displayName = "iStore",
            wanUrl = "https://i.example.com/cgi-bin/luci",
            serviceType = ServiceType.ISTORE,
            authEnabled = true,
        )
        val resolver = RouteResolver(
            object : SsidProvider {
                override fun currentSsid() = null
                override fun permissionState() = SsidPermissionState.DENIED
            },
            object : HealthProbe {
                override suspend fun probe(url: String, path: String): HealthProbeResult =
                    HealthProbeResult(
                        reachable = false,
                        statusCode = 403,
                        errorCode = xin.dponnood.remoteservice.core.network.NetworkErrorCode.HTTP_FAILURE,
                    )
            },
        )

        val result = ServiceWebCoordinator(resolver).resolve(service)

        val ready = result as ServiceWebOpenResult.Ready
        assertEquals("https://i.example.com", ready.target.allowedOrigins.single())
        assertEquals(403, ready.resolution.probe.statusCode)
        assertTrue(ready.resolution.probe.reachable)
    }
}
