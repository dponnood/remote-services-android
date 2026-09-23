package xin.dponnood.remoteservice.core.logging

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredLoggingTest {
    @Test
    fun sanitizerRemovesCredentialsUrlsAndPrivateIps() {
        val message = LogSanitizer.message(
            "Authorization: Bearer secret-token password=hunter2 url=https://192.168.1.2:8080/luci?token=x from 10.0.0.4",
        )
        assertFalse(message.contains("secret-token"))
        assertFalse(message.contains("hunter2"))
        assertFalse(message.contains("https://"))
        assertFalse(message.contains("192.168.1.2"))
        assertFalse(message.contains("10.0.0.4"))
        assertTrue(message.contains("REDACTED") || message.contains("PRIVATE_IP"))
    }

    @Test
    fun sanitizerRemovesPrivateAndLocalIpv6AddressesButKeepsPublicIpv6() {
        val message = LogSanitizer.message(
            "loopback=::1 ula=fc00::1234 local=fd12:3456::9 link=[fe80::abcd%wlan0] " +
                "unspecified=:: public=2001:db8::1",
        )
        assertFalse(message.contains("loopback=::1"))
        assertFalse(message.contains("ula=fc00::1234"))
        assertFalse(message.contains("local=fd12:3456::9"))
        assertFalse(message.contains("link=[fe80::abcd"))
        assertFalse(message.contains("unspecified=:: public"))
        assertTrue(message.contains("2001:db8::1"))
        assertTrue(message.count { it == '[' } >= 4)
    }

    @Test
    fun contextRedactsSensitiveKeysAndBoundsValues() {
        val context = LogSanitizer.context(
            mapOf(
                "password" to "do-not-store",
                "serviceUrl" to "https://example.test/path",
                "screen" to "services",
                "long" to "x".repeat(500),
            ),
        )
        assertEquals("[REDACTED]", context["password"])
        assertEquals("[REDACTED]", context["serviceurl"])
        assertEquals("services", context["screen"])
        assertTrue(context.getValue("long").length <= 256)
    }

    @Test
    fun fileRepositoryKeepsRecentRecordsWithinCapacity() {
        val dir = Files.createTempDirectory("remote-services-log").toFile()
        val file = File(dir, "app.jsonl")
        val repository = FileLogRepository(file, maxBytes = 4 * 1024)
        repeat(100) { index ->
            repository.append(LogLevel.INFO, "EVENT_$index", "message-${"x".repeat(100)}")
        }
        assertTrue(repository.sizeBytes() <= 4 * 1024)
        val records = repository.readRecent(100)
        assertTrue(records.isNotEmpty())
        assertTrue(records.last().contains("EVENT_99"))
        repository.clear()
        assertEquals(0L, repository.sizeBytes())
    }

    @Test
    fun payloadContainsOnlyStructuredLogsAndRespectsLimit() {
        val repository = InMemoryLogRepository()
        repository.append(LogLevel.ERROR, "WEB_LOAD_FAILED", "failed https://10.0.0.2/path token=abc")
        val payload = FeedbackPayloadBuilder(
            repository,
            FixedInstallationIdProvider("00000000-0000-0000-0000-000000000001"),
            FeedbackConfig(maxPayloadBytes = 16 * 1024),
            clock = { "2026-09-22T00:00:00Z" },
        ).build("用户反馈 password=hidden")
        assertTrue(payload.json.contains("schemaVersion"))
        assertTrue(payload.json.contains("WEB_LOAD_FAILED"))
        assertFalse(payload.json.contains("https://"))
        assertFalse(payload.json.contains("10.0.0.2"))
        assertFalse(payload.json.contains("hidden"))
        assertTrue(payload.sizeBytes <= 16 * 1024)
    }

    @Test
    fun feedbackClientRejectsNonHttpsWithoutTransport() {
        var calls = 0
        val client = HttpsFeedbackClient(
            FeedbackConfig(endpoint = "http://example.test/feedback"),
            transport = FeedbackTransport { _, _, _, _ ->
                calls++
                FeedbackHttpResponse(200)
            },
            sleeper = {},
        )
        val result = client.upload(FeedbackPackage("{}", 0, 2))
        assertFalse(result.succeeded)
        assertEquals(0, calls)
    }

    @Test
    fun feedbackClientRetriesTransientFailureThenSucceeds() {
        var calls = 0
        val client = HttpsFeedbackClient(
            FeedbackConfig(maxAttempts = 3, retryBackoffMillis = 0),
            transport = FeedbackTransport { _, body, _, _ ->
                calls++
                assertTrue(body.toString(StandardCharsets.UTF_8).length <= 10)
                if (calls == 1) FeedbackHttpResponse(503) else FeedbackHttpResponse(204)
            },
            sleeper = {},
        )
        val result = client.upload(FeedbackPackage("{}", 0, 2))
        assertTrue(result.succeeded)
        assertEquals(2, calls)
        assertEquals(2, result.attempts)
    }
}
