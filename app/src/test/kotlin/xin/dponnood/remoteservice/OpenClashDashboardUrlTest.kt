package xin.dponnood.remoteservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xin.dponnood.remoteservice.core.network.RouteKind

class OpenClashDashboardUrlTest {
    @Test
    fun publicRouteUsesForwardDomainAndEncodesSecret() {
        val url = OpenClashDashboardUrlBuilder.build(
            status = status(
                controllerHost = "192.168.1.1",
                controllerPort = 9090,
                forwardDomain = "clash.example.com",
                forwardPort = 443,
                forwardSsl = true,
                secret = "a+b/c=",
            ),
            routeKind = RouteKind.PUBLIC,
            endpointUrl = "https://i.example.com",
        )

        assertEquals(
            "https://clash.example.com:443/ui/zashboard/#/setup" +
                "?hostname=clash.example.com&port=443&secret=a%2Bb%2Fc%3D",
            url,
        )
    }

    @Test
    fun internalRouteUsesLuCiHostAndControllerPort() {
        val url = OpenClashDashboardUrlBuilder.build(
            status = status(
                controllerHost = "192.168.1.1",
                controllerPort = 9090,
                secret = "local-secret",
            ),
            routeKind = RouteKind.INTERNAL,
            endpointUrl = "http://192.168.1.1",
        )

        assertEquals(
            "http://192.168.1.1:9090/ui/zashboard/#/setup" +
                "?hostname=192.168.1.1&port=9090&secret=local-secret",
            url,
        )
    }

    @Test
    fun publicRouteDoesNotFallBackToIStoreHomeWhenForwardingIsMissing() {
        assertNull(
            OpenClashDashboardUrlBuilder.build(
                status = status(
                    controllerHost = "192.168.1.1",
                    controllerPort = 9090,
                    secret = "secret",
                ),
                routeKind = RouteKind.PUBLIC,
                endpointUrl = "https://i.example.com",
            ),
        )
    }

    private fun status(
        controllerHost: String? = null,
        controllerPort: Int? = null,
        forwardDomain: String? = null,
        forwardPort: Int? = null,
        forwardSsl: Boolean? = null,
        secret: String? = null,
    ) = OpenClashControllerInfo(
        running = true,
        secret = secret,
        runMode = "fake-ip",
        configName = "test",
        controllerHost = controllerHost,
        controllerPort = controllerPort,
        forwardDomain = forwardDomain,
        forwardPort = forwardPort,
        forwardSsl = forwardSsl,
    )
}
