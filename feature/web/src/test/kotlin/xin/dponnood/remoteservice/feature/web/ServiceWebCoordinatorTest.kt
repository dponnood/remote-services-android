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
import xin.dponnood.remoteservice.core.network.RouteKind
import xin.dponnood.remoteservice.core.network.SsidPermissionState
import xin.dponnood.remoteservice.core.network.SsidProvider

class ServiceWebCoordinatorTest {
    @Test
    fun dockerServiceUsesGetOnlyRouteHealthAndAppendsDetectedDockermanPath() = runTest {
        var getProbes = 0
        var headProbes = 0
        var cookieLookups = 0
        var observedCookie: String? = null
        val routeResolver = RouteResolver(
            object : SsidProvider {
                override fun currentSsid() = "Home"
                override fun permissionState() = SsidPermissionState.AVAILABLE
            },
            object : HealthProbe {
                override suspend fun probe(url: String, path: String): HealthProbeResult {
                    headProbes++
                    return HealthProbeResult(reachable = true, statusCode = 200)
                }

                override suspend fun probeGet(url: String, path: String): HealthProbeResult {
                    getProbes++
                    return HealthProbeResult(reachable = true, statusCode = 200)
                }
            },
        )
        val pageResolver = DockerPageRouteResolver(
            probe = DockerPageProbe { _, cookie ->
                observedCookie = cookie
                DockerPageProbeResult(DockerPageProbeStatus.DOCKER_PAGE)
            },
            cookieProvider = LuCiCookieProvider { url ->
                cookieLookups++
                assertTrue(url.contains("/cgi-bin/luci/admin/services/dockerman/overview"))
                "fixture-cookie"
            },
        )
        val service = ServiceConfig(
            id = "docker",
            displayName = "iStore Docker",
            lanUrl = "http://192.0.2.1/cgi-bin/luci",
            trustedSsids = setOf("Home"),
            serviceType = ServiceType.DOCKER,
        )

        val result = ServiceWebCoordinator(routeResolver, pageResolver).resolve(service)

        val ready = result as ServiceWebOpenResult.Ready
        assertEquals(RouteKind.INTERNAL, ready.resolution.endpoint.kind)
        assertEquals(
            "http://192.0.2.1${DockerPageRouteResolver.CANDIDATE_PATHS.first()}",
            ready.target.url,
        )
        assertEquals("fixture-cookie", observedCookie)
        assertEquals(1, cookieLookups)
        assertEquals(1, getProbes)
        assertEquals(0, headProbes)
    }

    @Test
    fun dockerAuthResponseIsUnverifiedFallbackAndLoginPageIsNotAcceptedAsDocker() = runTest {
        val service = ServiceConfig(
            id = "docker",
            displayName = "iStore Docker",
            wanUrl = "https://router.example",
            serviceType = ServiceType.DOCKER,
        )
        val routeResolver = RouteResolver(
            object : SsidProvider {
                override fun currentSsid() = null
                override fun permissionState() = SsidPermissionState.DENIED
            },
            object : HealthProbe {
                override suspend fun probe(url: String, path: String) = HealthProbeResult(true, 200)
            },
        )
        val pageResolver = DockerPageRouteResolver(
            probe = DockerPageProbe { _, _ -> DockerPageProbeResult(DockerPageProbeStatus.LOGIN_REQUIRED) },
            cookieProvider = LuCiCookieProvider { null },
        )

        val result = ServiceWebCoordinator(routeResolver, pageResolver).resolve(service)

        val ready = result as ServiceWebOpenResult.Ready
        assertEquals("https://router.example${DockerPageRouteResolver.CANDIDATE_PATHS.first()}", ready.target.url)
    }

    @Test
    fun dockerRoutesAreNotAssumedWhenBothCandidatesReturn404() = runTest {
        val service = ServiceConfig(
            id = "docker",
            displayName = "iStore Docker",
            wanUrl = "https://router.example",
            serviceType = ServiceType.DOCKER,
        )
        val routeResolver = RouteResolver(
            object : SsidProvider {
                override fun currentSsid() = null
                override fun permissionState() = SsidPermissionState.DENIED
            },
            object : HealthProbe {
                override suspend fun probe(url: String, path: String) = HealthProbeResult(true, 200)
            },
        )
        val pageResolver = DockerPageRouteResolver(
            probe = DockerPageProbe { _, _ -> DockerPageProbeResult(DockerPageProbeStatus.NOT_FOUND) },
            cookieProvider = LuCiCookieProvider { null },
        )

        val result = ServiceWebCoordinator(routeResolver, pageResolver).resolve(service)

        assertTrue(result is ServiceWebOpenResult.Unavailable)
        assertEquals(2, (result as ServiceWebOpenResult.Unavailable).attempted.size)
    }

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
