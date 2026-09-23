package xin.dponnood.remoteservice.core.network

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xin.dponnood.remoteservice.core.model.ServiceType
import xin.dponnood.remoteservice.core.model.ConnectionPolicy

class RouteResolverTest {
    private class FakeSsidProvider(
        private val ssid: String?,
        private val state: SsidPermissionState = SsidPermissionState.AVAILABLE,
    ) : SsidProvider {
        override fun currentSsid(): String? = ssid
        override fun permissionState(): SsidPermissionState = state
    }

    private class FakeProbe(
        private val responses: Map<String, HealthProbeResult>,
    ) : HealthProbe {
        val calls = mutableListOf<String>()
        override suspend fun probe(url: String, path: String): HealthProbeResult {
            calls += url
            return responses[url] ?: HealthProbeResult(false, errorCode = NetworkErrorCode.IO_FAILURE)
        }
    }

    @Test
    fun trustedSsid_prefersInternal() = runTest {
        val internal = "https://192.168.1.1"
        val public = "https://router.example"
        val probe = FakeProbe(
            mapOf(internal to HealthProbeResult(true, statusCode = 204)),
        )
        val result = RouteResolver(FakeSsidProvider("Home"), probe).resolve(
            ServiceRouteConfig(internal, public, setOf("Home")),
        )
        val success = result as RouteResolutionResult.Success
        assertEquals(RouteKind.INTERNAL, success.value.endpoint.kind)
        assertEquals(listOf(internal), probe.calls)
    }

    @Test
    fun trustedSsid_matchesPersistedQuotesAndWhitespace() = runTest {
        val internal = "http://192.168.1.1"
        val public = "https://router.example"
        val probe = FakeProbe(
            mapOf(internal to HealthProbeResult(true, statusCode = 204)),
        )
        val result = RouteResolver(FakeSsidProvider("Home WiFi"), probe).resolve(
            ServiceRouteConfig(
                internalUrl = internal,
                publicUrl = public,
                trustedSsids = setOf("  \"Home WiFi\"  "),
            ),
        )

        assertEquals(RouteKind.INTERNAL, (result as RouteResolutionResult.Success).value.endpoint.kind)
        assertEquals(listOf(internal), probe.calls)
    }

    @Test
    fun trustedSsid_fallsBackToPublicExactlyOnce() = runTest {
        val internal = "https://192.168.1.1"
        val public = "https://router.example"
        val probe = FakeProbe(
            mapOf(
                internal to HealthProbeResult(false, errorCode = NetworkErrorCode.TIMEOUT),
                public to HealthProbeResult(true, statusCode = 200),
            ),
        )
        val result = RouteResolver(FakeSsidProvider("Home"), probe).resolve(
            ServiceRouteConfig(internal, public, setOf("Home")),
        )
        val success = result as RouteResolutionResult.Success
        assertEquals(RouteKind.PUBLIC, success.value.endpoint.kind)
        assertTrue(success.value.fallbackUsed)
        assertEquals(listOf(internal, public), probe.calls)
    }

    @Test
    fun unknownSsid_doesNotProbeInternalWhenPublicExists() = runTest {
        val internal = "https://192.168.1.1"
        val public = "https://router.example"
        val probe = FakeProbe(mapOf(public to HealthProbeResult(true, statusCode = 200)))
        val result = RouteResolver(FakeSsidProvider(null, SsidPermissionState.DENIED), probe).resolve(
            ServiceRouteConfig(internal, public, setOf("Home")),
        )
        assertEquals(RouteKind.PUBLIC, (result as RouteResolutionResult.Success).value.endpoint.kind)
        assertEquals(listOf(public), probe.calls)
    }

    @Test
    fun ssidCaseMismatch_doesNotTrustOrProbeInternal() = runTest {
        val internal = "https://192.168.1.1"
        val public = "https://router.example"
        val probe = FakeProbe(mapOf(public to HealthProbeResult(true, statusCode = 200)))
        val result = RouteResolver(FakeSsidProvider("home wifi"), probe).resolve(
            ServiceRouteConfig(
                internalUrl = internal,
                publicUrl = public,
                trustedSsids = setOf("Home WiFi"),
            ),
        )

        assertEquals(RouteKind.PUBLIC, (result as RouteResolutionResult.Success).value.endpoint.kind)
        assertEquals(listOf(public), probe.calls)
    }

    @Test
    fun unknownSsid_withoutPublicEndpoint_failsWithoutProbingInternal() = runTest {
        val internal = "https://192.168.1.1"
        val probe = FakeProbe(mapOf(internal to HealthProbeResult(true, statusCode = 200)))
        val result = RouteResolver(FakeSsidProvider(null, SsidPermissionState.DENIED), probe).resolve(
            ServiceRouteConfig(internalUrl = internal, publicUrl = null, trustedSsids = setOf("Home")),
        )
        val failure = result as RouteResolutionResult.Failure
        assertEquals(NetworkErrorCode.INVALID_ENDPOINT, failure.code)
        assertTrue(probe.calls.isEmpty())
    }

    @Test
    fun publicOnly_neverProbesInternalEvenOnTrustedWifi() = runTest {
        val internal = "https://192.168.1.1"
        val public = "https://router.example"
        val probe = FakeProbe(mapOf(public to HealthProbeResult(true, statusCode = 200)))
        val result = RouteResolver(FakeSsidProvider("Home"), probe).resolve(
            ServiceRouteConfig(
                internalUrl = internal,
                publicUrl = public,
                trustedSsids = setOf("Home"),
                connectionPolicy = ConnectionPolicy.PUBLIC_ONLY,
            ),
        )

        assertEquals(RouteKind.PUBLIC, (result as RouteResolutionResult.Success).value.endpoint.kind)
        assertEquals(listOf(public), probe.calls)
    }

    @Test
    fun internalOnly_doesNotFallbackToPublicWhenInternalFails() = runTest {
        val internal = "https://192.168.1.1"
        val public = "https://router.example"
        val probe = FakeProbe(
            mapOf(internal to HealthProbeResult(false, errorCode = NetworkErrorCode.TIMEOUT)),
        )
        val result = RouteResolver(FakeSsidProvider("Home"), probe).resolve(
            ServiceRouteConfig(
                internalUrl = internal,
                publicUrl = public,
                trustedSsids = setOf("Home"),
                connectionPolicy = ConnectionPolicy.INTERNAL_ONLY,
            ),
        )

        val failure = result as RouteResolutionResult.Failure
        assertEquals(NetworkErrorCode.TIMEOUT, failure.code)
        assertEquals(listOf(internal), probe.calls)
    }

    @Test
    fun deniedPermission_neverTrustsProviderSsid() = runTest {
        val internal = "https://192.168.1.1"
        val public = "https://router.example"
        val probe = FakeProbe(mapOf(public to HealthProbeResult(true, statusCode = 200)))
        val result = RouteResolver(
            FakeSsidProvider("Home", SsidPermissionState.DENIED),
            probe,
        ).resolve(ServiceRouteConfig(internal, public, setOf("Home")))

        assertEquals(RouteKind.PUBLIC, (result as RouteResolutionResult.Success).value.endpoint.kind)
        assertEquals(listOf(public), probe.calls)
    }

    @Test
    fun ssidNormalization_removesQuotesAndUnknownValue() {
        assertEquals("Home", AndroidSsidProvider.normalizeSsid("\"Home\""))
        assertEquals("Home WiFi", AndroidSsidProvider.normalizeSsid("  'Home WiFi'  "))
        assertEquals(null, AndroidSsidProvider.normalizeSsid("<unknown ssid>"))
        assertEquals(null, AndroidSsidProvider.normalizeSsid(" UNKNOWN SSID "))
        assertEquals(null, AndroidSsidProvider.normalizeSsid("  "))
    }

    @Test
    fun trustedSsidMatching_normalizesBothPlatformAndConfiguredValues() {
        assertTrue(isTrustedSsid("\"Home WiFi\"", listOf("  'Home WiFi'  ")))
        assertFalse(isTrustedSsid("Home WiFi", listOf("home wifi")))
        assertFalse(isTrustedSsid("Home WiFi", listOf("'HOME WIFI'")))
        assertFalse(isTrustedSsid("\"Guest\"", listOf("Home WiFi")))
    }

    @Test
    fun trustedSsid_withQuotedConfiguredValueStillPrefersInternal() = runTest {
        val internal = "http://192.168.1.1"
        val public = "https://router.example"
        val probe = FakeProbe(
            mapOf(internal to HealthProbeResult(true, statusCode = 204)),
        )

        val result = RouteResolver(FakeSsidProvider("\"home wifi\""), probe).resolve(
            ServiceRouteConfig(
                internalUrl = internal,
                publicUrl = public,
                trustedSsids = setOf("  home wifi  "),
            ),
        )

        val success = result as RouteResolutionResult.Success
        assertEquals(RouteKind.INTERNAL, success.value.endpoint.kind)
        assertEquals(listOf(internal), probe.calls)
    }

    @Test
    fun httpEndpointIsAcceptedByRoutePolicy() = runTest {
        val internal = "http://192.168.1.1:8080"
        val probe = FakeProbe(mapOf(internal to HealthProbeResult(true, statusCode = 204)))
        val result = RouteResolver(FakeSsidProvider("Home"), probe).resolve(
            ServiceRouteConfig(internalUrl = internal, trustedSsids = setOf("Home")),
        )

        val success = result as RouteResolutionResult.Success
        assertEquals(internal, success.value.endpoint.url)
        assertEquals(listOf(internal), probe.calls)
    }

    @Test
    fun unsupportedSchemeIsRejectedByHealthProbeValidation() = runTest {
        val result = HttpsHealthProbe().probe("ftp://router.example/")

        assertEquals(false, result.reachable)
        assertEquals(NetworkErrorCode.UNSUPPORTED_SCHEME, result.errorCode)
    }

    @Test
    fun embeddedUrlCredentialsAreRejectedBeforeNetworkProbe() = runTest {
        for (url in listOf(
            "http://user:secret@127.0.0.1:1/",
            "https://user:secret@router.example:bad/admin",
        )) {
            val result = HttpsHealthProbe().probe(url)

            assertEquals(false, result.reachable)
            assertEquals(NetworkErrorCode.INVALID_ENDPOINT, result.errorCode)
        }
    }

    @Test
    fun defaultProbePreservesConfiguredLuCiPath() {
        assertEquals("/cgi-bin/luci", effectiveProbePath("/cgi-bin/luci", "/"))
        assertEquals("/cgi-bin/luci/", effectiveProbePath("/cgi-bin/luci/", ""))
        assertEquals("/", effectiveProbePath("/", "/"))
        assertEquals("/health", effectiveProbePath("/cgi-bin/luci", "/health"))
    }

    @Test
    fun headFallbackIsLimitedToMethodUnsupportedResponses() {
        assertTrue(shouldRetryHealthProbeWithGet(405))
        assertTrue(shouldRetryHealthProbeWithGet(501))
        assertFalse(shouldRetryHealthProbeWithGet(400))
        assertFalse(shouldRetryHealthProbeWithGet(500))
    }

    @Test
    fun luciAuthenticationResponseIsUsableRouteAndKeepsStatus() = runTest {
        val public = "https://i.example.com/cgi-bin/luci"
        val probe = FakeProbe(
            mapOf(
                public to HealthProbeResult(
                    reachable = false,
                    statusCode = 403,
                    errorCode = NetworkErrorCode.HTTP_FAILURE,
                ),
            ),
        )

        val result = RouteResolver(FakeSsidProvider(null, SsidPermissionState.DENIED), probe).resolve(
            ServiceRouteConfig(
                publicUrl = public,
                serviceType = ServiceType.LUCI,
            ),
        )

        val success = result as RouteResolutionResult.Success
        assertTrue(success.value.probe.reachable)
        assertEquals(403, success.value.probe.statusCode)
        assertEquals(null, success.value.probe.errorCode)
        assertEquals(listOf(public), probe.calls)
    }

    @Test
    fun iStoreUnauthorizedResponseIsUsableRouteAndKeepsStatus() = runTest {
        val public = "https://i.example.com/cgi-bin/luci"
        val probe = FakeProbe(
            mapOf(public to HealthProbeResult(false, statusCode = 401, errorCode = NetworkErrorCode.HTTP_FAILURE)),
        )

        val result = RouteResolver(FakeSsidProvider(null, SsidPermissionState.DENIED), probe).resolve(
            ServiceRouteConfig(publicUrl = public, serviceType = ServiceType.ISTORE),
        )

        val success = result as RouteResolutionResult.Success
        assertTrue(success.value.probe.reachable)
        assertEquals(401, success.value.probe.statusCode)
    }

    @Test
    fun openClashUnauthorizedApiResponseIsUsableRouteAndKeepsStatus() = runTest {
        val public = "https://clash.example.com"
        val probe = FakeProbe(
            mapOf(public to HealthProbeResult(false, statusCode = 401, errorCode = NetworkErrorCode.HTTP_FAILURE)),
        )

        val result = RouteResolver(FakeSsidProvider(null, SsidPermissionState.DENIED), probe).resolve(
            ServiceRouteConfig(publicUrl = public, serviceType = ServiceType.OPENCLASH),
        )

        val success = result as RouteResolutionResult.Success
        assertTrue(success.value.probe.reachable)
        assertEquals(401, success.value.probe.statusCode)
    }

    @Test
    fun genericAuthenticationResponseRemainsUnavailable() = runTest {
        val public = "https://service.example/login"
        val probe = FakeProbe(
            mapOf(public to HealthProbeResult(false, statusCode = 403, errorCode = NetworkErrorCode.HTTP_FAILURE)),
        )

        val result = RouteResolver(FakeSsidProvider(null, SsidPermissionState.DENIED), probe).resolve(
            ServiceRouteConfig(publicUrl = public),
        )

        val failure = result as RouteResolutionResult.Failure
        assertEquals(NetworkErrorCode.HTTP_FAILURE, failure.code)
        assertEquals(listOf(public), probe.calls)
    }

    @Test
    fun onlyAuthenticationStatusesAreAcceptedForLuCi() {
        assertTrue(isAuthenticationRequiredStatus(401))
        assertTrue(isAuthenticationRequiredStatus(403))
        assertFalse(isAuthenticationRequiredStatus(402))
        assertFalse(isAuthenticationRequiredStatus(404))
        assertFalse(isAuthenticationRequiredStatus(null))
    }
}
