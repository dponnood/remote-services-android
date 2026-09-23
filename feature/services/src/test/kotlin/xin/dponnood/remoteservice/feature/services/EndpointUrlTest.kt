package xin.dponnood.remoteservice.feature.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointUrlTest {
    @Test
    fun splitsSchemeCaseInsensitivelyAndPreservesIpv6PathAndQuery() {
        assertEquals(
            EndpointFieldState(
                scheme = EndpointScheme.HTTPS,
                address = "[fd00::1]:5001/ui?next=https://example.com",
            ),
            splitEndpointUrl("HTTPS://[fd00::1]:5001/ui?next=https://example.com"),
        )
        assertEquals(
            EndpointFieldState(EndpointScheme.HTTP, "router.local:80"),
            splitEndpointUrl("hTtP://router.local:80"),
        )
    }

    @Test
    fun emptyAndSchemeLessValuesUseDefaultScheme() {
        assertEquals(EndpointFieldState(), splitEndpointUrl(null))
        assertEquals(
            EndpointFieldState(EndpointScheme.HTTP, "192.168.1.1/path"),
            splitEndpointUrl("192.168.1.1/path", EndpointScheme.HTTP),
        )
        assertEquals("", joinEndpointUrl(EndpointFieldState(EndpointScheme.HTTP, "")))
        assertEquals(
            "http://192.168.1.1:8080/path?mode=1",
            joinEndpointUrl(EndpointFieldState(EndpointScheme.HTTP, "192.168.1.1:8080/path?mode=1")),
        )
    }

    @Test
    fun validatesHttpHttpsIpv6AndRejectsNestedSchemeOrWhitespace() {
        assertNull(endpointUrlValidationError("http://192.168.1.1:8080"))
        assertNull(endpointUrlValidationError("https://[fd00::1]:5001/ui?next=https://example.com"))
        assertFalse(isValidEndpointUrl("https://http://router.local"))
        assertFalse(isValidEndpointUrl("https://router.local/path with spaces"))
        assertFalse(isValidEndpointUrl("https://"))
        assertFalse(isValidEndpointUrl("ftp://router.local"))
    }
}
