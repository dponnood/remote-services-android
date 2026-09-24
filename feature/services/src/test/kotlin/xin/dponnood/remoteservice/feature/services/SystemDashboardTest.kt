package xin.dponnood.remoteservice.feature.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xin.dponnood.remoteservice.core.model.ServiceType

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SystemDashboardTest {
    @Test
    fun unavailableProviderNeverCreatesMetricValues() = runTest {
        val presenter = SystemDashboardPresenter(
            provider = UnavailableSystemInfoProvider,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
        )

        presenter.refresh()
        advanceUntilIdle()

        val state = presenter.state.value
        assertFalse(state.isLoading)
        assertTrue(state.hasLoaded)
        assertTrue(state.cards.isEmpty())
        assertNotNull(state.errorMessage)
        presenter.close()
    }

    @Test
    fun snapshotMapsOnlyProvidedMetricsAndComputesProgress() {
        val cards = DashboardCardModel.from(
            SystemInfoSnapshot(
                hostname = "nas-home",
                osName = "ExampleOS",
                cpuUsagePercent = 42.4f,
                memoryUsedBytes = 4L * 1024 * 1024 * 1024,
                memoryTotalBytes = 8L * 1024 * 1024 * 1024,
                storageUsedBytes = 3L * 1024 * 1024 * 1024 * 1024,
                storageTotalBytes = 6L * 1024 * 1024 * 1024 * 1024,
                uptimeMillis = 26L * 60 * 60 * 1000,
            ),
        )

        val cpu = cards.first { it.id == DashboardCardId.CPU }
        val memory = cards.first { it.id == DashboardCardId.MEMORY }
        assertEquals(DashboardCardStatus.READY, cpu.status)
        assertEquals("42%", cpu.value)
        assertEquals(0.424f, cpu.progress!!, 0.001f)
        assertEquals("已使用 50%", memory.detail)
        assertEquals(0.5f, memory.progress!!, 0.001f)
        assertTrue(cards.none { it.id in setOf(DashboardCardId.TEMPERATURE, DashboardCardId.NETWORK) })
    }

    @Test
    fun openClashSnapshotRendersNativeStatusCardsOnly() {
        val cards = DashboardCardModel.from(
            SystemInfoSnapshot(
                openClash = OpenClashDashboardSnapshot(
                    running = true,
                    version = "v1.19.31",
                    coreLabel = "Mihomo Meta",
                    mode = "fake-ip",
                    connectionCount = 12,
                    proxyGroupCount = 4,
                    memoryBytes = 1024L * 1024,
                    routeLabel = "内网",
                ),
            ),
        )

        assertTrue(cards.isNotEmpty())
        assertTrue(cards.all { it.id.name.startsWith("OPENCLASH_") })
        assertTrue(cards.all { it.status != DashboardCardStatus.UNAVAILABLE })
        assertEquals("运行中", cards.first { it.id == DashboardCardId.OPENCLASH_STATUS }.value)
        assertEquals("v1.19.31", cards.first { it.id == DashboardCardId.OPENCLASH_VERSION }.value)
        assertEquals("12 个", cards.first { it.id == DashboardCardId.OPENCLASH_CONNECTIONS }.value)
        assertTrue(cards.none { it.id == DashboardCardId.OPENCLASH_TRAFFIC })
    }

    @Test
    fun iStoreOnlyShowsFieldsReturnedByStandardUbus() {
        val cards = DashboardCardModel.from(
            SystemInfoSnapshot(
                hostname = "router-home",
                model = "iStoreOS Router",
                osName = "OpenWrt",
                firmware = "24.10",
                kernel = "6.6",
                memoryUsedBytes = 256L * 1024 * 1024,
                memoryTotalBytes = 512L * 1024 * 1024,
                uptimeMillis = 90_000L,
            ),
        )

        assertTrue(cards.map { it.id }.toSet().containsAll(
            setOf(
                DashboardCardId.HOST,
                DashboardCardId.HOSTNAME,
                DashboardCardId.MODEL,
                DashboardCardId.OS_DISTRIBUTION,
                DashboardCardId.FIRMWARE,
                DashboardCardId.KERNEL,
                DashboardCardId.MEMORY,
                DashboardCardId.UPTIME,
            ),
        ))
        assertTrue(cards.none { it.id in setOf(
            DashboardCardId.CPU,
            DashboardCardId.TEMPERATURE,
            DashboardCardId.STORAGE,
            DashboardCardId.NETWORK,
        ) })
        assertTrue(cards.all { it.status == DashboardCardStatus.READY })
    }

    @Test
    fun iStoreCatalogueOffersDetailedUbusFieldsAndMarksUnsupportedMetrics() {
        val catalogue = DashboardCardModel.catalogue(
            SystemInfoSnapshot(
                hostname = "router-home",
                model = "iStoreOS Router",
                memoryUsedBytes = 256L,
                memoryTotalBytes = 512L,
                memoryFreeBytes = 256L,
                swapUsedBytes = 64L,
                swapTotalBytes = 128L,
                loadAverage1 = 0.25f,
                loadAverage5 = 0.18f,
                loadAverage15 = 0.12f,
            ),
            ServiceType.ISTORE,
        ).associateBy { it.model.id }

        assertTrue(catalogue.getValue(DashboardCardId.LOAD_1).addable)
        assertTrue(catalogue.getValue(DashboardCardId.SWAP).addable)
        assertTrue(catalogue.getValue(DashboardCardId.MEMORY_FREE).addable)
        assertTrue(catalogue.getValue(DashboardCardId.CPU).addable.not())
        assertTrue(catalogue.getValue(DashboardCardId.TEMPERATURE).addable.not())
        assertTrue(catalogue.getValue(DashboardCardId.STORAGE).addable.not())
        assertTrue(catalogue.getValue(DashboardCardId.LOAD_1).model.detail!!.contains("负载"))
    }

    @Test
    fun openClashCatalogueIncludesFineGrainedApiMetrics() {
        val catalogue = DashboardCardModel.catalogue(
            SystemInfoSnapshot(
                openClash = OpenClashDashboardSnapshot(
                    configName = "home.yaml",
                    routeLabel = "内网",
                    downloadBytesPerSecond = 2048,
                    uploadBytesPerSecond = 512,
                    downloadTotalBytes = 4096,
                    uploadTotalBytes = 1024,
                ),
            ),
            ServiceType.OPENCLASH,
        ).associateBy { it.model.id }

        assertTrue(catalogue.getValue(DashboardCardId.OPENCLASH_CONFIG).addable)
        assertTrue(catalogue.getValue(DashboardCardId.OPENCLASH_ROUTE).addable)
        assertTrue(catalogue.getValue(DashboardCardId.OPENCLASH_DOWNLOAD_TOTAL).addable)
        assertTrue(catalogue.getValue(DashboardCardId.OPENCLASH_UPLOAD_RATE).addable)
        assertTrue(catalogue.getValue(DashboardCardId.CPU).addable.not())
    }

    @Test
    fun dockerSnapshotRendersRuntimeCountsAndOptionalResourceListCards() {
        val snapshot = DockerDashboardSnapshot(
            serverVersion = "26.1.2",
            apiVersion = "1.45",
            storageDriver = "overlay2",
            indexServerAddress = "https://index.docker.io/v1/",
            registryMirrors = "https://mirror.example",
            dockerRootDirectory = "/opt/docker",
            operatingSystem = "OpenWrt",
            kernelVersion = "6.6.80",
            cpuCount = 4,
            memoryTotalBytes = 2_147_483_648L,
            containersTotal = 3,
            containersRunning = 1,
            containersPaused = 1,
            containersStopped = 1,
            imagesTotal = 5,
            imagesUsed = 2,
            networksTotal = 2,
            volumesTotal = 1,
            containerList = listOf(DockerResourceSummary("proxy", "running · alpine")),
            imageList = listOf(DockerResourceSummary("alpine:latest")),
            networkList = listOf(DockerResourceSummary("bridge", "bridge")),
            volumeList = listOf(DockerResourceSummary("config", "local")),
        )
        val cards = DashboardCardModel.from(SystemInfoSnapshot(docker = snapshot)).associateBy { it.id }
        val catalogue = DashboardCardModel.catalogue(SystemInfoSnapshot(docker = snapshot), ServiceType.DOCKER)
            .associateBy { it.model.id }

        assertEquals("26.1.2", cards.getValue(DashboardCardId.DOCKER_ENGINE).value)
        assertEquals("1.45", cards.getValue(DashboardCardId.DOCKER_ENGINE).detail?.substringAfter("API ")?.substringBefore(" · "))
        assertEquals("1 个", cards.getValue(DashboardCardId.DOCKER_RUNNING).value)
        assertEquals("5 个", cards.getValue(DashboardCardId.DOCKER_IMAGES).value)
        assertTrue(cards.getValue(DashboardCardId.DOCKER_IMAGES).detail!!.contains("2 个镜像正在被容器使用"))
        assertEquals("https://index.docker.io/v1/", cards.getValue(DashboardCardId.DOCKER_REGISTRY).value)
        assertTrue(cards.getValue(DashboardCardId.DOCKER_REGISTRY).detail!!.contains("https://mirror.example"))
        assertTrue(cards.getValue(DashboardCardId.DOCKER_CONTAINER_LIST).detail!!.contains("proxy"))
        assertTrue(catalogue.getValue(DashboardCardId.DOCKER_CONTAINER_LIST).addable)
        assertTrue(catalogue.getValue(DashboardCardId.DOCKER_IMAGE_LIST).addable)
        assertTrue(catalogue.getValue(DashboardCardId.DOCKER_NETWORK_LIST).addable)
        assertTrue(catalogue.getValue(DashboardCardId.DOCKER_VOLUME_LIST).addable)
        assertTrue(catalogue.getValue(DashboardCardId.DOCKER_REGISTRY).addable)
        assertTrue(cards.keys.all { it.name.startsWith("DOCKER_") })
    }

    @Test
    fun cardOrderHonorsSavedSelectionAndCanMoveSeveralPlaces() {
        val catalogue = DashboardCardModel.catalogue(
            SystemInfoSnapshot(hostname = "router"),
            ServiceType.ISTORE,
        )

        assertEquals(
            listOf(DashboardCardId.UPTIME, DashboardCardId.HOST),
            resolveDashboardCardOrder(ServiceType.ISTORE, listOf("UPTIME", "CPU", "HOST"), catalogue),
        )
        assertEquals(emptyList<DashboardCardId>(), resolveDashboardCardOrder(ServiceType.ISTORE, emptyList(), catalogue))
        assertEquals(
            listOf("B", "C", "A", "D"),
            moveDashboardCard(listOf("A", "B", "C", "D"), "A", 2),
        )
    }

    @Test
    fun draggedCardTargetChangesAtNeighborCenterAndClampsToListBounds() {
        assertEquals(1, dashboardCardDragTargetIndex(1, 49f, 100f, 4))
        assertEquals(2, dashboardCardDragTargetIndex(1, 51f, 100f, 4))
        assertEquals(0, dashboardCardDragTargetIndex(1, -51f, 100f, 4))
        assertEquals(3, dashboardCardDragTargetIndex(1, 900f, 100f, 4))
        assertEquals(0, dashboardCardDragTargetIndex(0, -900f, 100f, 4))
        assertEquals(1, dashboardCardDragTargetIndex(1, 100f, 0f, 4))
    }

    @Test
    fun cancelledDragRestoresSlotWithoutChangingTheCardScreenPosition() {
        val originIndex = 0
        val currentIndex = 2
        val dragDistancePx = 220f
        val rowStepPx = 100f
        val beforeCancel = currentIndex * rowStepPx + dashboardCardDragTranslation(
            originIndex,
            currentIndex,
            dragDistancePx,
            rowStepPx,
        )
        val afterRestore = originIndex * rowStepPx + dashboardCardDragTranslation(
            originIndex,
            originIndex,
            dragDistancePx,
            rowStepPx,
        )

        assertEquals(220f, beforeCancel, 0.01f)
        assertEquals(beforeCancel, afterRestore, 0.01f)
    }

    @Test
    fun openClashDoesNotInventRunModeFromVersion() {
        val cards = DashboardCardModel.from(
            SystemInfoSnapshot(
                openClash = OpenClashDashboardSnapshot(version = "v1.19.31"),
            ),
        )

        assertEquals(setOf(DashboardCardId.OPENCLASH_VERSION), cards.map { it.id }.toSet())
    }

    @Test
    fun refreshFailurePreservesLoadedCardsAndExposesRetryMessage() = runTest {
        var calls = 0
        val provider = object : SystemInfoProvider {
            override suspend fun load(service: xin.dponnood.remoteservice.core.model.ServiceConfig?): SystemInfoResult {
                calls++
                if (calls == 1) {
                    return SystemInfoResult.Success(SystemInfoSnapshot(hostname = "nas-home"))
                }
                error("测试连接失败")
            }
        }
        val presenter = SystemDashboardPresenter(
            provider = provider,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
        )

        presenter.refresh()
        advanceUntilIdle()
        val loadedCards = presenter.state.value.cards
        presenter.refresh()
        advanceUntilIdle()

        val state = presenter.state.value
        assertNotNull(state.errorMessage)
        assertEquals("测试连接失败", state.errorMessage)
        assertEquals(loadedCards, state.cards)
        assertFalse(state.isRefreshing)
        presenter.close()
    }
}
