package xin.dponnood.remoteservice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DockerSystemInfoReaderTest {
    @Test
    fun parsesOnlyFixedFieldsFromServerRenderedOverviewFixture() {
        val parsed = DockerOverviewHtmlParser.parse(classicOverviewHtml)

        assertTrue(parsed is DockerOverviewParseResult.Overview)
        val snapshot = (parsed as DockerOverviewParseResult.Overview).snapshot
        assertEquals("27.3.1", snapshot.serverVersion)
        assertEquals("1.47", snapshot.apiVersion)
        assertEquals(4, snapshot.cpuCount)
        assertEquals(2L * 1024 * 1024 * 1024, snapshot.memoryTotalBytes)
        assertEquals("/opt/docker", snapshot.dockerRootDirectory)
        assertEquals("10.00 GB", snapshot.dockerRootAvailable)
        assertEquals("https://index.docker.io/v1/", snapshot.indexServerAddress)
        assertEquals("https://mirror.example", snapshot.registryMirrors)
        assertEquals(7, snapshot.containersTotal)
        assertEquals(3, snapshot.containersRunning)
        assertEquals(5, snapshot.imagesTotal)
        assertEquals(2, snapshot.imagesUsed)
        assertEquals(4, snapshot.networksTotal)
        assertEquals(6, snapshot.volumesTotal)
        assertNull(snapshot.containersPaused)
        assertNull(snapshot.containersStopped)
        assertNull(snapshot.containerList)
        assertNull(snapshot.imageList)
    }

    @Test
    fun rejectsLoginAndGenericLuCiHtmlInsteadOfTreatingItAsEmptyDockerData() {
        assertEquals(
            DockerOverviewParseResult.LoginRequired,
            DockerOverviewHtmlParser.parse("<html><title>LuCI - Login</title><form id='login'><input name='luci_password'></form></html>"),
        )
        assertEquals(
            DockerOverviewParseResult.NotDockerPage,
            DockerOverviewHtmlParser.parse("<html><title>iStoreOS</title><main>系统状态</main></html>"),
        )
    }

    @Test
    fun forbiddenOverviewDoesNotTriggerRepeatedLogin() = runBlocking {
        var requests = 0
        var reauthentications = 0
        val reader = DockerSystemInfoReader(DockerOverviewTransport { _, _ ->
            requests++
            DockerOverviewHttpResponse(403, "Forbidden")
        })

        val result = reader.read(
            origin = ORIGIN,
            session = UbusSessionAuth("unused-sid", "sysauth=session"),
            cacheScope = "docker-service",
            reauthenticate = {
                reauthentications++
                UbusSessionAuth("new-sid", "sysauth=fresh")
            },
        )

        assertEquals(DockerOverviewFailureKind.FORBIDDEN, (result as DockerOverviewReadResult.Failure).kind)
        assertEquals(403, result.httpStatus)
        assertEquals(1, requests)
        assertEquals(0, reauthentications)
    }

    @Test
    fun loginRefreshIsRetriedOnceThenSuppressedForCooldown() = runBlocking {
        var now = 1_000L
        var requests = 0
        var reauthentications = 0
        val reader = DockerSystemInfoReader(
            transport = DockerOverviewTransport { _, cookie ->
                requests++
                if (cookie == "sysauth=fresh") {
                    DockerOverviewHttpResponse(200, "<html><title>LuCI - Login</title><form id='login'></form></html>")
                } else {
                    DockerOverviewHttpResponse(401, "login required")
                }
            },
            clockMillis = { now },
        )

        val first = reader.read(
            ORIGIN,
            UbusSessionAuth("old-sid", "sysauth=old"),
            cacheScope = "docker-service",
            reauthenticate = {
                reauthentications++
                UbusSessionAuth("new-sid", "sysauth=fresh")
            },
        )
        val nextPoll = reader.read(
            ORIGIN,
            UbusSessionAuth("old-sid", "sysauth=old"),
            cacheScope = "docker-service",
            reauthenticate = {
                reauthentications++
                UbusSessionAuth("new-sid", "sysauth=fresh")
            },
        )

        assertEquals(DockerOverviewFailureKind.SESSION_EXPIRED, (first as DockerOverviewReadResult.Failure).kind)
        assertEquals(DockerOverviewFailureKind.SESSION_EXPIRED, (nextPoll as DockerOverviewReadResult.Failure).kind)
        assertEquals(3, requests)
        assertEquals(1, reauthentications)
        now += DockerSystemInfoReader.AUTH_REFRESH_COOLDOWN_MILLIS + 1
        reader.read(
            ORIGIN,
            UbusSessionAuth("old-sid", "sysauth=old"),
            cacheScope = "docker-service",
            reauthenticate = {
                reauthentications++
                UbusSessionAuth("new-sid", "sysauth=fresh")
            },
        )
        assertEquals(2, reauthentications)
    }

    @Test
    fun cachesOverviewForTenSecondsAndOnlyUsesFixedGetRoutes() = runBlocking {
        var now = 1_000L
        val urls = mutableListOf<String>()
        val reader = DockerSystemInfoReader(
            transport = DockerOverviewTransport { url, cookie ->
                urls += url
                assertEquals("sysauth=authenticated", cookie)
                DockerOverviewHttpResponse(200, classicOverviewHtml)
            },
            clockMillis = { now },
        )
        val session = UbusSessionAuth("unused-sid", "sysauth=authenticated")

        val first = reader.read(ORIGIN, session, "docker-service") as DockerOverviewReadResult.Success
        val cached = reader.read(ORIGIN, session, "docker-service") as DockerOverviewReadResult.Success
        assertFalse(first.cached)
        assertTrue(cached.cached)
        assertEquals(1, urls.size)
        assertTrue(urls.single().endsWith("/cgi-bin/luci/admin/docker/overview"))

        now += DockerSystemInfoReader.CACHE_TTL_MILLIS + 1
        reader.read(ORIGIN, session, "docker-service")
        assertEquals(2, urls.size)
        assertTrue(urls.all { it.startsWith(ORIGIN) && it.contains("/cgi-bin/luci/admin/") })
    }

    @Test
    fun aGenericNewerCandidateNeverCountsAsDockerPage() = runBlocking {
        val urls = mutableListOf<String>()
        val reader = DockerSystemInfoReader(DockerOverviewTransport { url, _ ->
            urls += url
            if (url.endsWith("/admin/docker/overview")) {
                DockerOverviewHttpResponse(404, "not found")
            } else {
                DockerOverviewHttpResponse(200, "<html><title>iStoreOS</title><main>首页</main></html>")
            }
        })

        val result = reader.read(ORIGIN, UbusSessionAuth("sid", "sysauth=authenticated"))

        assertEquals(DockerOverviewFailureKind.NOT_DOCKER_PAGE, (result as DockerOverviewReadResult.Failure).kind)
        assertEquals(2, urls.size)
        assertTrue(urls.last().endsWith("/cgi-bin/luci/admin/services/dockerman/overview"))
    }

    companion object {
        private const val ORIGIN = "https://router.example"
        private val classicOverviewHtml = """
            <html><head><title>Docker - Overview</title></head><body>
            <table><tbody>
              <tr><td>Docker Version</td><td>27.3.1</td></tr>
              <tr><td>Api Version</td><td>1.47</td></tr>
              <tr><td>CPUs</td><td>4</td></tr>
              <tr><td>Total Memory</td><td>2.00 GB</td></tr>
              <tr><td>Docker Root Dir</td><td>/opt/docker (10.00 GB Available)</td></tr>
              <tr><td>Index Server Address</td><td>https://index.docker.io/v1/</td></tr>
              <tr><td>Registry Mirrors</td><td>https://mirror.example</td></tr>
            </tbody></table>
            <div class="pure-g status">
              <a href="/cgi-bin/luci/admin/docker/containers"><span>3</span><span>/7</span></a>
              <a href="/cgi-bin/luci/admin/docker/images"><span>2</span><span>/5</span></a>
              <a href="/cgi-bin/luci/admin/docker/networks"><span>4</span></a>
              <a href="/cgi-bin/luci/admin/docker/volumes"><span>6</span></a>
            </div></body></html>
        """.trimIndent()
    }
}
