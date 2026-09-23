package xin.dponnood.remoteservice.core.network

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClashApiTest {
    @Test
    fun parsesVersionPayloadWithoutNeedingRealController() {
        val result = OpenClashJsonParser.parseVersion(
            """{"meta":true,"version":"v1.19.31"}""",
        )

        assertEquals("v1.19.31", result.version)
        assertEquals(true, result.meta)
        assertNull(result.premium)
    }

    @Test
    fun parsesConfigPortsModeAndNestedTunState() {
        val result = OpenClashJsonParser.parseConfig(
            """
            {
              "port":7890,
              "socks-port":"7891",
              "mixed-port":7893,
              "mode":"rule",
              "log-level":"info",
              "allow-lan":true,
              "ipv6":false,
              "tun":{"enable":true}
            }
            """.trimIndent(),
        )

        assertEquals(7890, result.port)
        assertEquals(7891, result.socksPort)
        assertEquals(7893, result.mixedPort)
        assertEquals("rule", result.mode)
        assertEquals("info", result.logLevel)
        assertEquals(true, result.allowLan)
        assertEquals(false, result.ipv6)
        assertEquals(true, result.tunEnabled)
        assertNull(result.redirPort)
    }

    @Test
    fun parsesProxyMapAndIdentifiesGroups() {
        val result = OpenClashJsonParser.parseProxies(
            """
            {
              "proxies": {
                "GLOBAL": {"type":"Selector","all":["DIRECT","香港-01"],"now":"香港-01"},
                "香港-01": {"type":"Shadowsocks","udp":true},
                "DIRECT": {"type":"Direct","udp":true}
              }
            }
            """.trimIndent(),
        )

        assertEquals(3, result.proxies.size)
        assertEquals(1, result.groups.size)
        assertEquals("GLOBAL", result.groups.single().name)
        assertEquals(listOf("DIRECT", "香港-01"), result.groups.single().all)
        assertEquals("香港-01", result.groups.single().now)
        assertEquals(true, result.proxies.first { it.name == "香港-01" }.udp)
    }

    @Test
    fun parsesConnectionsTotalsMetadataAndTrafficRates() {
        val connections = OpenClashJsonParser.parseConnections(
            """
            {
              "downloadTotal":123456,
              "uploadTotal":4567,
              "memory":1048576,
              "connections":[
                {
                  "id":"conn-1",
                  "metadata":{
                    "network":"tcp",
                    "type":"HTTP",
                    "host":"example.com",
                    "destinationIP":"203.0.113.10",
                    "destinationPort":"443",
                    "sourceIP":"192.0.2.2",
                    "sourcePort":52344
                  },
                  "upload":12,
                  "download":345,
                  "start":"2026-09-22T00:00:00Z",
                  "chains":["香港-01","DIRECT"],
                  "rule":"MATCH",
                  "rulePayload":"MATCH"
                }
              ]
            }
            """.trimIndent(),
        )
        val traffic = OpenClashJsonParser.parseTraffic("""{"up":64,"down":1024}""")

        assertEquals(123456L, connections.downloadTotalBytes)
        assertEquals(4567L, connections.uploadTotalBytes)
        assertEquals(1048576L, connections.memoryBytes)
        val connection = connections.connections.single()
        assertEquals("conn-1", connection.id)
        assertEquals("example.com", connection.metadata.host)
        assertEquals(443, connection.metadata.destinationPort)
        assertEquals(52344, connection.metadata.sourcePort)
        assertEquals(listOf("香港-01", "DIRECT"), connection.chains)
        assertEquals(345L, connection.downloadBytes)
        assertEquals(12L, connection.uploadBytes)
        assertEquals(64L, traffic.uploadBytesPerSecond)
        assertEquals(1024L, traffic.downloadBytesPerSecond)
    }

    @Test
    fun parserKeepsMissingOptionalFieldsHonest() {
        val proxies = OpenClashJsonParser.parseProxies("{}")
        val connections = OpenClashJsonParser.parseConnections("{\"connections\":[]}")
        val traffic = OpenClashJsonParser.parseTraffic("{\"up\":\"not-a-number\"}")

        assertTrue(proxies.proxies.isEmpty())
        assertTrue(connections.connections.isEmpty())
        assertNull(connections.memoryBytes)
        assertNull(traffic.uploadBytesPerSecond)
        assertNull(traffic.downloadBytesPerSecond)
    }

    @Test
    fun readOnlyClientUsesExpectedPathsAndParsers() = runTest {
        val payloads = mapOf(
            OpenClashApiPaths.VERSION to "{\"version\":\"v1\"}",
            OpenClashApiPaths.CONFIGS to "{\"mode\":\"global\"}",
            OpenClashApiPaths.PROXIES to "{\"proxies\":{}}",
            OpenClashApiPaths.CONNECTIONS to "{\"connections\":[]}",
            OpenClashApiPaths.TRAFFIC to "{\"up\":1,\"down\":2}",
        )
        val transport = RecordingTransport(payloads)
        val client = ReadOnlyOpenClashApiClient(transport)

        assertEquals("v1", client.fetchVersion().version)
        assertEquals("global", client.fetchConfig().mode)
        assertTrue(client.fetchProxies().proxies.isEmpty())
        assertTrue(client.fetchConnections().connections.isEmpty())
        assertEquals(2L, client.fetchTraffic().downloadBytesPerSecond)
        assertEquals(1, transport.trafficFrameCount)
        assertEquals(
            listOf(
                OpenClashApiPaths.VERSION,
                OpenClashApiPaths.CONFIGS,
                OpenClashApiPaths.PROXIES,
                OpenClashApiPaths.CONNECTIONS,
            ),
            transport.paths,
        )
    }

    @Test
    fun selectorEncodesGroupPathAndSendsSelectedNodeAsJson() = runTest {
        val transport = RecordingControlTransport()

        OpenClashProxySelector(transport).selectNode("Group A/B", "Tokyo 01")

        assertEquals("/proxies/Group%20A%2FB", transport.path.orEmpty())
        assertEquals("Tokyo 01", JSONObject(transport.body.orEmpty()).getString("name"))
    }

    @Test
    fun selectorRejectsBlankGroupOrNodeBeforeSending() = runTest {
        val transport = RecordingControlTransport()

        assertTrue(runCatching { OpenClashProxySelector(transport).selectNode(" ", "node") }.isFailure)
        assertTrue(runCatching { OpenClashProxySelector(transport).selectNode("group", "") }.isFailure)
        assertNull(transport.path)
    }

    @Test
    fun proxyDelayTesterUsesOneEncodedGetAndParsesMilliseconds() = runTest {
        val path = OpenClashApiPaths.proxyDelay(
            proxyName = "Node A/B",
            url = "https://example.test/p?q=a&b=c",
            timeoutMs = 4_321,
        )
        val transport = RecordingTransport(mapOf(path to "{\"delay\":137}"))

        val delay = OpenClashProxyDelayTester(transport).testNodeDelay(
            proxyName = "Node A/B",
            url = "https://example.test/p?q=a&b=c",
            timeoutMs = 4_321,
        )

        assertEquals(137, delay)
        assertEquals(
            "/proxies/Node%20A%2FB/delay?url=https%3A%2F%2Fexample.test%2Fp%3Fq%3Da%26b%3Dc&timeout=4321",
            path,
        )
        assertEquals(listOf(path), transport.paths)
    }

    @Test
    fun proxyDelayParserAcceptsNumericStringButRejectsMissingOrOutOfRangeValues() {
        assertEquals(250, OpenClashJsonParser.parseProxyDelay("{\"delay\":\"250\"}"))
        listOf("{}", "{\"delay\":65536}", "{\"delay\":1.5}").forEach { payload ->
            val failure = runCatching { OpenClashJsonParser.parseProxyDelay(payload) }.exceptionOrNull()
            assertTrue("Expected invalid delay payload to fail: $payload", failure is OpenClashApiException)
        }
    }

    @Test
    fun proxyDelayTesterRejectsUnsafeOrUnboundedInputsBeforeRequest() = runTest {
        val transport = RecordingTransport(emptyMap())
        val tester = OpenClashProxyDelayTester(transport)

        assertTrue(runCatching { tester.testNodeDelay(" ") }.isFailure)
        assertTrue(runCatching { tester.testNodeDelay("node", "file:///etc/passwd") }.isFailure)
        assertTrue(runCatching { tester.testNodeDelay("node", timeoutMs = 30_001) }.isFailure)
        assertTrue(transport.paths.isEmpty())
    }

    @Test
    fun httpOnlyTransportDoesNotRequestTrafficAsOrdinaryHttp() = runTest {
        val httpOnly = object : OpenClashApiTransport {
            override suspend fun get(path: String): String = "{}"
        }
        val failure = runCatching { ReadOnlyOpenClashApiClient(httpOnly).fetchTraffic() }.exceptionOrNull()

        assertTrue(failure is OpenClashApiException)
        assertEquals(OpenClashApiPaths.TRAFFIC, (failure as OpenClashApiException).path)
        assertTrue(failure.message.orEmpty().contains("WebSocket"))
    }

    @Test
    fun responseLimitCountsUtf8BytesAndStopsAtTheFirstOverflowByte() {
        val exactPayload = "路由器"
        val exactBytes = exactPayload.toByteArray(Charsets.UTF_8)
        assertEquals(
            exactPayload,
            readUtf8Bounded(ByteArrayInputStream(exactBytes), exactBytes.size, OpenClashApiPaths.CONFIGS),
        )

        val countingInput = CountingInputStream(exactBytes + byteArrayOf('x'.code.toByte()))
        val failure = runCatching {
            readUtf8Bounded(countingInput, exactBytes.size, OpenClashApiPaths.CONFIGS)
        }.exceptionOrNull()

        assertTrue(failure is OpenClashApiException)
        assertEquals(exactBytes.size + 1, countingInput.bytesRead)
    }

    @Test
    fun transportRejectsQueryAndFragmentOnBaseUrl() {
        assertIllegalArgument {
            HttpOpenClashApiTransport("http://192.168.1.1:9090?secret=never")
        }
        assertIllegalArgument {
            HttpOpenClashApiTransport("http://192.168.1.1:9090/#fragment")
        }
    }

    private class RecordingTransport(
        private val payloads: Map<String, String>,
    ) : OpenClashApiTransport, OpenClashTrafficTransport {
        val paths = mutableListOf<String>()
        var trafficFrameCount = 0

        override suspend fun get(path: String): String {
            paths += path
            return payloads.getValue(path)
        }

        override suspend fun nextTrafficFrame(): String {
            trafficFrameCount++
            return payloads.getValue(OpenClashApiPaths.TRAFFIC)
        }
    }

    private class RecordingControlTransport : OpenClashApiControlTransport {
        var path: String? = null
        var body: String? = null

        override suspend fun get(path: String): String = error("GET is not expected")

        override suspend fun put(path: String, body: String) {
            this.path = path
            this.body = body
        }
    }

    private class CountingInputStream(private val payload: ByteArray) : InputStream() {
        var bytesRead: Int = 0
            private set

        override fun read(): Int {
            if (bytesRead >= payload.size) return -1
            return payload[bytesRead++].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (bytesRead >= payload.size) return -1
            val count = minOf(length, payload.size - bytesRead)
            payload.copyInto(buffer, offset, bytesRead, bytesRead + count)
            bytesRead += count
            return count
        }
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }
}
