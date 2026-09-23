package xin.dponnood.remoteservice.feature.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZashboardPanelTest {
    @Test
    fun defaultBundleUsesSafeWebViewAssetLoaderUrl() {
        val config = ZashboardAssetConfig()

        assertEquals("https://appassets.androidplatform.net", config.assetOrigin)
        assertEquals(
            "https://appassets.androidplatform.net/assets/zashboard/index.html",
            config.entryUrl,
        )
        assertTrue(ZashboardBundleMetadata.PLACEHOLDER.isPlaceholder)
        assertEquals("MIT", ZashboardBundleMetadata.PLACEHOLDER.licenseName)
    }

    @Test
    fun assetConfigRejectsTraversalAndArbitraryAssetDomain() {
        assertIllegalArgument { ZashboardAssetConfig(assetDirectory = "../secrets") }
        assertIllegalArgument { ZashboardAssetConfig(entryFile = "../index.html") }
        assertIllegalArgument { ZashboardAssetConfig(assetDomain = "evil.example") }
    }

    @Test
    fun gatewayConfigCanonicalizesOriginWithoutAcceptingCredentialsOrQuery() {
        val config = OpenClashGatewayConfig(
            upstreamUrl = "HTTP://192.168.1.1:9090/api/",
            bindPort = 31_415,
        )

        assertEquals("http://192.168.1.1:9090", config.upstreamOrigin)
        assertEquals("/api", config.upstreamPathPrefix)
        assertEquals("http://192.168.1.1:9090/api", config.upstreamBaseUrl)
        assertTrue(OpenClashGatewayConfig.isLoopbackHost("127.0.0.1"))
        assertTrue(OpenClashGatewayConfig.isLoopbackHost("::1"))
        assertFalse(OpenClashGatewayConfig.isLoopbackHost("localhost"))

        assertIllegalArgument {
            OpenClashGatewayConfig("http://user:secret@192.168.1.1:9090")
        }
        assertIllegalArgument {
            OpenClashGatewayConfig("http://192.168.1.1:9090?secret=do-not-log")
        }
        assertIllegalArgument {
            OpenClashGatewayConfig("http://192.168.1.1:9090/../secret")
        }
        assertIllegalArgument {
            OpenClashGatewayConfig(
                upstreamUrl = "http://192.168.1.1:9090",
                bindHost = "0.0.0.0",
            )
        }
    }

    @Test
    fun panelEntryAllowListsOnlyStaticAssetAndLoopbackGatewayOrigins() {
        val gateway = OpenClashGatewaySession(
            localOrigin = "http://127.0.0.1:31415",
            upstreamOrigin = "http://192.168.1.1:9090",
            boundPort = 31_415,
        )
        val entry = ZashboardPanelEntry.forGateway(
            assetConfig = ZashboardAssetConfig(),
            gatewaySession = gateway,
        )

        assertEquals("http://127.0.0.1:31415", entry.gatewayOrigin)
        assertEquals(
            setOf("https://appassets.androidplatform.net", "http://127.0.0.1:31415"),
            entry.allowedOrigins,
        )
        val target = entry.webTarget("openclash")
        assertEquals(entry.assetEntryUrl, target.url)
        assertEquals(entry.gatewayOrigin, target.sessionKey.host)
        assertTrue(entry.bundle.isPlaceholder)
    }

    @Test
    fun gatewaySessionRejectsNonLoopbackWebViewOrigin() {
        assertIllegalArgument {
            OpenClashGatewaySession(
                localOrigin = "http://192.168.1.10:31415",
                upstreamOrigin = "http://192.168.1.1:9090",
                boundPort = 31_415,
            )
        }
        assertIllegalArgument {
            OpenClashGatewaySession(
                localOrigin = "http://127.0.0.1:31415/api",
                upstreamOrigin = "http://192.168.1.1:9090",
                boundPort = 31_415,
            )
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
