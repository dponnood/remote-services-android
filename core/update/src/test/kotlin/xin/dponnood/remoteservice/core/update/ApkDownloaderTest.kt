package xin.dponnood.remoteservice.core.update

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import xin.dponnood.remoteservice.core.model.UpdateInfo
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class ApkDownloaderTest {
    @Test
    fun resumes206AtExistingOffsetAndAppendsRemainder() = runTest {
        val complete = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
        val tempDir = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "remote-services-resume-${System.nanoTime()}",
        ).apply { mkdirs() }
        try {
            val versionCode = 2L
            val part = java.io.File(tempDir, "remote-service-$versionCode.apk.part")
            part.writeBytes(complete.copyOfRange(0, 4))
            val requestedRanges = mutableListOf<Long?>()
            val info = UpdateInfo(
                versionCode = versionCode,
                versionName = "0.2.0",
                minimumVersionCode = 0,
                apkUrl = "https://app.dponnood.xin/releases/remote-service-2.apk",
                apkSizeBytes = complete.size.toLong(),
                sha256 = sha256(complete),
                signingCertificateSha256 = "b".repeat(64),
                publishedAt = "",
                releaseNotes = "",
                securityRequired = false,
            )
            val downloader = ApkDownloader(
                connectionFactory = { url, rangeStart ->
                    requestedRanges += rangeStart
                    assertEquals(info.apkUrl, url)
                    FakeHttpURLConnection(
                        URL(url),
                        HttpURLConnection.HTTP_PARTIAL,
                        complete.copyOfRange(4, complete.size),
                        "bytes 4-7/${complete.size}",
                    )
                },
            )

            val result = downloader.download(info, tempDir)

            assertEquals(listOf(4L), requestedRanges)
            assertArrayEquals(complete, result.readBytes())
            assertFalse(part.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private class FakeHttpURLConnection(
        url: URL,
        private val status: Int,
        private val body: ByteArray,
        private val contentRange: String,
    ) : HttpURLConnection(url) {
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun connect() = Unit
        override fun getResponseCode(): Int = status
        override fun getHeaderField(name: String): String? =
            if (name.equals("Content-Range", ignoreCase = true)) contentRange else null

        override fun getInputStream() = ByteArrayInputStream(body)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
