package xin.dponnood.remoteservice.feature.web

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DockerPageRouteResolverTest {
    @Test
    fun modernRouteIsSelectedOnlyAfterDockerMarkerIsConfirmed() = runTest {
        val probed = mutableListOf<String>()
        val resolver = DockerPageRouteResolver(
            probe = DockerPageProbe { url, _ ->
                probed += url
                DockerPageProbeResult(DockerPageProbeStatus.DOCKER_PAGE)
            },
            cookieProvider = LuCiCookieProvider { null },
        )

        val result = resolver.resolve("https://router.example")

        assertEquals(
            DockerPageRouteResolution.Confirmed(
                DockerPageRouteResolver.CANDIDATE_PATHS.first(),
                listOf(DockerPageRouteAttempt(
                    DockerPageRouteResolver.CANDIDATE_PATHS.first(),
                    DockerPageProbeStatus.DOCKER_PAGE,
                )),
            ),
            result,
        )
        assertEquals(1, probed.size)
    }

    @Test
    fun only404FallsThroughToLegacyRoute() = runTest {
        val probed = mutableListOf<String>()
        val resolver = DockerPageRouteResolver(
            probe = DockerPageProbe { url, _ ->
                probed += url
                DockerPageProbeResult(
                    if (url.endsWith(DockerPageRouteResolver.CANDIDATE_PATHS.first())) {
                        DockerPageProbeStatus.NOT_FOUND
                    } else {
                        DockerPageProbeStatus.DOCKER_PAGE
                    },
                )
            },
            cookieProvider = LuCiCookieProvider { null },
        )

        val result = resolver.resolve("http://192.0.2.1:8080")

        assertTrue(result is DockerPageRouteResolution.Confirmed)
        assertEquals(2, probed.size)
        assertTrue(probed.last().endsWith(DockerPageRouteResolver.CANDIDATE_PATHS.last()))
    }

    @Test
    fun genericLuCiShellFallsThroughButNeverCountsAsDocker() = runTest {
        var calls = 0
        val resolver = DockerPageRouteResolver(
            probe = DockerPageProbe { _, _ ->
                calls++
                DockerPageProbeResult(
                    if (calls == 1) DockerPageProbeStatus.NON_DOCKER_PAGE
                    else DockerPageProbeStatus.DOCKER_PAGE,
                )
            },
            cookieProvider = LuCiCookieProvider { null },
        )

        val result = resolver.resolve("https://router.example")

        assertTrue(result is DockerPageRouteResolution.Confirmed)
        assertEquals(DockerPageRouteResolver.CANDIDATE_PATHS.last(), (result as DockerPageRouteResolution.Confirmed).path)
        assertEquals(2, calls)
    }

    @Test
    fun twoGenericLuCiShellsAreNotOpenedAsDocker() = runTest {
        val resolver = DockerPageRouteResolver(
            probe = DockerPageProbe { _, _ -> DockerPageProbeResult(DockerPageProbeStatus.NON_DOCKER_PAGE) },
            cookieProvider = LuCiCookieProvider { null },
        )

        val result = resolver.resolve("https://router.example")

        assertTrue(result is DockerPageRouteResolution.NotFound)
        assertEquals(2, result.attempts.size)
        assertTrue(result.attempts.all { it.status == DockerPageProbeStatus.NON_DOCKER_PAGE })
    }

    @Test
    fun authForbiddenAndTimeoutRemainUnverifiedAndDoNotGuessSecondRoute() = runTest {
        listOf(
            DockerPageProbeStatus.LOGIN_REQUIRED,
            DockerPageProbeStatus.FORBIDDEN,
            DockerPageProbeStatus.TRANSIENT_FAILURE,
        ).forEach { status ->
            var calls = 0
            val resolver = DockerPageRouteResolver(
                probe = DockerPageProbe { _, _ ->
                    calls++
                    DockerPageProbeResult(status)
                },
                cookieProvider = LuCiCookieProvider { null },
            )

            val result = resolver.resolve("https://router.example")

            assertTrue(result is DockerPageRouteResolution.UnverifiedFallback)
            assertEquals(1, calls)
            assertEquals(status, (result as DockerPageRouteResolution.UnverifiedFallback).reason)
        }
    }

    @Test
    fun all404CandidatesAreReportedAsNotFound() = runTest {
        val resolver = DockerPageRouteResolver(
            probe = DockerPageProbe { _, _ -> DockerPageProbeResult(DockerPageProbeStatus.NOT_FOUND) },
            cookieProvider = LuCiCookieProvider { null },
        )

        val result = resolver.resolve("https://router.example")

        assertTrue(result is DockerPageRouteResolution.NotFound)
        assertEquals(2, result.attempts.size)
    }

    @Test
    fun loginHtmlAndNonDockerHtmlAreNeverClassifiedAsDockerPages() {
        assertEquals(
            DockerPageProbeStatus.LOGIN_REQUIRED,
            classifyDockerPageResponse(
                statusCode = 200,
                location = null,
                body = "<form><input name='luci_password' type='password'><input name='luci_username'></form>",
                candidatePath = DockerPageRouteResolver.CANDIDATE_PATHS.first(),
            ),
        )
        assertEquals(
            DockerPageProbeStatus.NON_DOCKER_PAGE,
            classifyDockerPageResponse(
                200,
                null,
                "<html><title>LuCI</title><main>home</main></html>",
                DockerPageRouteResolver.CANDIDATE_PATHS.first(),
            ),
        )
        assertEquals(
            DockerPageProbeStatus.DOCKER_PAGE,
            classifyDockerPageResponse(
                200,
                null,
                """
                    <html><h1>Overview</h1><div>Docker Version 27.3.1</div>
                    <div>API Version 1.47</div><div>CPUs 4</div></html>
                """.trimIndent(),
                DockerPageRouteResolver.CANDIDATE_PATHS.first(),
            ),
        )
        assertEquals(
            DockerPageProbeStatus.DOCKER_PAGE,
            classifyDockerPageResponse(
                200,
                null,
                """
                    <div class="pure-g status"><div>Docker version</div></div>
                    <a href="/cgi-bin/luci/admin/docker/containers">Containers</a>
                    <a href="/cgi-bin/luci/admin/docker/images">Images</a>
                """.trimIndent(),
                DockerPageRouteResolver.CANDIDATE_PATHS.last(),
            ),
        )
    }

    @Test
    fun ordinaryLuCiPageWithDockermanSidebarIsNotClassifiedAsOverview() {
        val genericLuCiWithDockerMenu = """
            <!doctype html>
            <html>
              <title>LuCI</title>
              <nav>
                <a href="/cgi-bin/luci/admin/services/dockerman/overview">Dockerman</a>
                <a href="/cgi-bin/luci/admin/services/dockerman/containers">Containers</a>
                <a href="/cgi-bin/luci/admin/services/dockerman/images">Images</a>
              </nav>
              <main><h1>Status</h1><p>System overview and network status</p></main>
            </html>
        """.trimIndent()

        assertEquals(
            DockerPageProbeStatus.NON_DOCKER_PAGE,
            classifyDockerPageResponse(
                200,
                null,
                genericLuCiWithDockerMenu,
                DockerPageRouteResolver.CANDIDATE_PATHS.first(),
            ),
        )
        assertEquals(
            DockerPageProbeStatus.NON_DOCKER_PAGE,
            classifyDockerPageResponse(
                200,
                null,
                genericLuCiWithDockerMenu,
                DockerPageRouteResolver.CANDIDATE_PATHS.last(),
            ),
        )
    }

    @Test
    fun loginRedirectIsUnknownRatherThanSuccessfulRoute() {
        assertEquals(
            DockerPageProbeStatus.LOGIN_REQUIRED,
            classifyDockerPageResponse(
                302,
                "/cgi-bin/luci/",
                "",
                DockerPageRouteResolver.CANDIDATE_PATHS.first(),
            ),
        )
    }
}
