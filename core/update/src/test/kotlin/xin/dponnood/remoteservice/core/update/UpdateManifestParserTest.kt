package xin.dponnood.remoteservice.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateManifestParserTest {
    private val hash = "a".repeat(64)
    private val certificate = "b".repeat(64)

    private fun validManifest(extra: String = ""): String =
        """
        {
          "versionCode": 2,
          "versionName": "0.2",
          "minimumVersionCode": 1,
          "apkUrl": "https://app.dponnood.xin/a.apk",
          "apkSizeBytes": 1,
          "sha256": "$hash",
          "signingCertificateSha256": "$certificate",
          "publishedAt": "2026-09-21T10:00:00Z",
          "releaseNotes": "修复网络切换",
          "securityRequired": false$extra
        }
        """.trimIndent()

    @Test
    fun parsesAndNormalizesManifest() {
        val info = UpdateManifestParser.parse(
            """
            {
              "versionCode": 2,
              "versionName": "0.2.0",
              "minimumVersionCode": 1,
              "apkUrl": "https://app.dponnood.xin/releases/remote-service-2.apk",
              "apkSizeBytes": 1234,
              "sha256": "${hash.uppercase()}",
              "signingCertificateSha256": "${certificate.uppercase()}",
              "publishedAt": "2026-09-21T10:00:00Z",
              "releaseNotes": "修复网络切换",
              "securityRequired": true
            }
            """.trimIndent(),
        )
        assertEquals(2L, info.versionCode)
        assertEquals(hash, info.sha256)
        assertEquals(certificate, info.signingCertificateSha256)
        assertEquals(true, info.securityRequired)
    }

    @Test
    fun rejectsNonHttpsOrWrongHost() {
        val base = """
          {"versionCode":2,"versionName":"0.2","minimumVersionCode":1,"apkUrl":"%s","apkSizeBytes":1,"sha256":"$hash","signingCertificateSha256":"$certificate","publishedAt":"2026-09-21T10:00:00Z","releaseNotes":"","securityRequired":false}
        """.trimIndent()
        assertThrows(UpdateException.InvalidManifest::class.java) {
            UpdateManifestParser.parse(base.format("http://app.dponnood.xin/a.apk"))
        }
        assertThrows(UpdateException.InvalidManifest::class.java) {
            UpdateManifestParser.parse(base.format("https://evil.example/a.apk"))
        }
    }

    @Test
    fun rejectsMalformedHashAndVersionRange() {
        val malformedHash = """
          {"versionCode":2,"versionName":"0.2","minimumVersionCode":1,"apkUrl":"https://app.dponnood.xin/a.apk","apkSizeBytes":1,"sha256":"bad","signingCertificateSha256":"$certificate","publishedAt":"2026-09-21T10:00:00Z","releaseNotes":"","securityRequired":false}
        """.trimIndent()
        assertThrows(UpdateException.InvalidManifest::class.java) {
            UpdateManifestParser.parse(malformedHash)
        }
        val invalidRange = """
          {"versionCode":2,"versionName":"0.2","minimumVersionCode":3,"apkUrl":"https://app.dponnood.xin/a.apk","apkSizeBytes":1,"sha256":"$hash","signingCertificateSha256":"$certificate","publishedAt":"2026-09-21T10:00:00Z","releaseNotes":"","securityRequired":false}
        """.trimIndent()
        assertThrows(UpdateException.InvalidManifest::class.java) {
            UpdateManifestParser.parse(invalidRange)
        }
    }

    @Test
    fun requiresAllSchemaFieldsAndRejectsAdditionalProperties() {
        val missingFields = mapOf(
            "minimumVersionCode" to "  \"minimumVersionCode\": 1,\n",
            "publishedAt" to "  \"publishedAt\": \"2026-09-21T10:00:00Z\",\n",
            "releaseNotes" to "  \"releaseNotes\": \"修复网络切换\",\n",
            "securityRequired" to ",\n  \"securityRequired\": false",
        )
        missingFields.forEach { (field, line) ->
            val error = assertThrows(UpdateException.InvalidManifest::class.java) {
                UpdateManifestParser.parse(validManifest().replace(line, ""))
            }
            assertEquals("missing $field", UpdateErrorCode.MISSING_FIELD, error.code)
        }

        val extra = assertThrows(UpdateException.InvalidManifest::class.java) {
            UpdateManifestParser.parse(validManifest(",\n  \"unexpected\": true"))
        }
        assertEquals(UpdateErrorCode.INVALID_FIELD, extra.code)
    }

    @Test
    fun enforcesStrictSchemaTypesAndPublishedAtFormat() {
        val wrongTypes = listOf(
            validManifest().replace("\"minimumVersionCode\": 1", "\"minimumVersionCode\": \"1\""),
            validManifest().replace("\"publishedAt\": \"2026-09-21T10:00:00Z\"", "\"publishedAt\": 123"),
            validManifest().replace("\"releaseNotes\": \"修复网络切换\"", "\"releaseNotes\": false"),
            validManifest().replace("\"securityRequired\": false", "\"securityRequired\": \"false\""),
        )
        wrongTypes.forEach { json ->
            val error = assertThrows(UpdateException.InvalidManifest::class.java) {
                UpdateManifestParser.parse(json)
            }
            assertEquals(UpdateErrorCode.INVALID_FIELD, error.code)
        }

        val badDate = assertThrows(UpdateException.InvalidManifest::class.java) {
            UpdateManifestParser.parse(validManifest().replace("2026-09-21T10:00:00Z", "2026-09-21"))
        }
        assertEquals(UpdateErrorCode.INVALID_FIELD, badDate.code)
    }
}
