package xin.dponnood.remoteservice.feature.services

import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xin.dponnood.remoteservice.core.model.ServiceConfig
import xin.dponnood.remoteservice.core.model.ServiceType

/**
 * The metric vocabulary understood by the NAS dashboard.
 *
 * This is deliberately kept independent from any HTTP response shape. A NAS
 * adapter can map its own API into [SystemInfoSnapshot] without making the
 * Compose surface depend on a particular vendor or endpoint.
 */
enum class DashboardCardId {
    CPU,
    TEMPERATURE,
    MEMORY,
    MEMORY_FREE,
    MEMORY_SHARED,
    MEMORY_BUFFERED,
    SWAP,
    STORAGE,
    UPTIME,
    NETWORK,
    HOST,
    HOSTNAME,
    MODEL,
    OS_DISTRIBUTION,
    FIRMWARE,
    KERNEL,
    LOAD_1,
    LOAD_5,
    LOAD_15,
    OPENCLASH_NODE_SELECTOR,
    OPENCLASH_STATUS,
    OPENCLASH_VERSION,
    OPENCLASH_MODE,
    OPENCLASH_CONFIG,
    OPENCLASH_ROUTE,
    OPENCLASH_TRAFFIC,
    OPENCLASH_DOWNLOAD_RATE,
    OPENCLASH_UPLOAD_RATE,
    OPENCLASH_DOWNLOAD_TOTAL,
    OPENCLASH_UPLOAD_TOTAL,
    OPENCLASH_CONNECTIONS,
    OPENCLASH_GROUPS,
    OPENCLASH_SELECTED_GROUP,
    OPENCLASH_MEMORY,
    DOCKER_ENGINE,
    DOCKER_REGISTRY,
    DOCKER_CONTAINERS,
    DOCKER_RUNNING,
    DOCKER_PAUSED,
    DOCKER_STOPPED,
    DOCKER_IMAGES,
    DOCKER_NETWORKS,
    DOCKER_VOLUMES,
    DOCKER_HOST_RESOURCES,
    DOCKER_RUNTIME,
    DOCKER_STORAGE,
    DOCKER_CONTAINER_LIST,
    DOCKER_IMAGE_LIST,
    DOCKER_NETWORK_LIST,
    DOCKER_VOLUME_LIST,
}

enum class DashboardCardStatus {
    LOADING,
    READY,
    UNAVAILABLE,
    ERROR,
}

/** Reusable, display-ready model consumed by the dashboard card renderer. */
data class DashboardCardModel(
    val id: DashboardCardId,
    val title: String,
    val iconKey: String,
    val status: DashboardCardStatus,
    val value: String? = null,
    val detail: String? = null,
    /** A normalized 0..1 value for progress-style cards such as CPU/storage. */
    val progress: Float? = null,
) {
    companion object {
        private val systemIds = listOf(
            DashboardCardId.HOST, DashboardCardId.HOSTNAME, DashboardCardId.MODEL,
            DashboardCardId.OS_DISTRIBUTION, DashboardCardId.FIRMWARE, DashboardCardId.KERNEL,
            DashboardCardId.MEMORY, DashboardCardId.MEMORY_FREE, DashboardCardId.MEMORY_SHARED,
            DashboardCardId.MEMORY_BUFFERED, DashboardCardId.SWAP, DashboardCardId.UPTIME,
            DashboardCardId.LOAD_1, DashboardCardId.LOAD_5, DashboardCardId.LOAD_15,
            DashboardCardId.CPU, DashboardCardId.TEMPERATURE, DashboardCardId.STORAGE,
            DashboardCardId.NETWORK,
        )

        private val zashboardIds = listOf(
            DashboardCardId.OPENCLASH_NODE_SELECTOR,
        )

        private val openClashIds = listOf(
            DashboardCardId.OPENCLASH_STATUS, DashboardCardId.OPENCLASH_VERSION,
            DashboardCardId.OPENCLASH_MODE, DashboardCardId.OPENCLASH_CONFIG,
            DashboardCardId.OPENCLASH_ROUTE, DashboardCardId.OPENCLASH_TRAFFIC,
            DashboardCardId.OPENCLASH_DOWNLOAD_RATE, DashboardCardId.OPENCLASH_UPLOAD_RATE,
            DashboardCardId.OPENCLASH_DOWNLOAD_TOTAL, DashboardCardId.OPENCLASH_UPLOAD_TOTAL,
            DashboardCardId.OPENCLASH_CONNECTIONS, DashboardCardId.OPENCLASH_GROUPS,
            DashboardCardId.OPENCLASH_SELECTED_GROUP, DashboardCardId.OPENCLASH_MEMORY,
        )

        private val dockerIds = listOf(
            DashboardCardId.DOCKER_ENGINE,
            DashboardCardId.DOCKER_REGISTRY,
            DashboardCardId.DOCKER_CONTAINERS,
            DashboardCardId.DOCKER_RUNNING,
            DashboardCardId.DOCKER_PAUSED,
            DashboardCardId.DOCKER_STOPPED,
            DashboardCardId.DOCKER_IMAGES,
            DashboardCardId.DOCKER_NETWORKS,
            DashboardCardId.DOCKER_VOLUMES,
            DashboardCardId.DOCKER_HOST_RESOURCES,
            DashboardCardId.DOCKER_RUNTIME,
            DashboardCardId.DOCKER_STORAGE,
            DashboardCardId.DOCKER_CONTAINER_LIST,
            DashboardCardId.DOCKER_IMAGE_LIST,
            DashboardCardId.DOCKER_NETWORK_LIST,
            DashboardCardId.DOCKER_VOLUME_LIST,
        )

        private val definitions = mapOf(
            DashboardCardId.CPU to ("处理器占用" to "cpu"),
            DashboardCardId.TEMPERATURE to ("温度" to "temperature"),
            DashboardCardId.MEMORY to ("内存使用" to "memory"),
            DashboardCardId.MEMORY_FREE to ("可用内存" to "memory"),
            DashboardCardId.MEMORY_SHARED to ("共享内存" to "memory"),
            DashboardCardId.MEMORY_BUFFERED to ("缓存内存" to "memory"),
            DashboardCardId.SWAP to ("交换分区" to "storage"),
            DashboardCardId.STORAGE to ("存储空间" to "storage"),
            DashboardCardId.UPTIME to ("运行时间" to "uptime"),
            DashboardCardId.NETWORK to ("实时网络" to "network"),
            DashboardCardId.HOST to ("设备概览" to "host"),
            DashboardCardId.HOSTNAME to ("主机名" to "host"),
            DashboardCardId.MODEL to ("设备型号" to "host"),
            DashboardCardId.OS_DISTRIBUTION to ("系统发行版" to "host"),
            DashboardCardId.FIRMWARE to ("固件版本" to "host"),
            DashboardCardId.KERNEL to ("内核版本" to "host"),
            DashboardCardId.LOAD_1 to ("1 分钟负载" to "cpu"),
            DashboardCardId.LOAD_5 to ("5 分钟负载" to "cpu"),
            DashboardCardId.LOAD_15 to ("15 分钟负载" to "cpu"),
            DashboardCardId.OPENCLASH_NODE_SELECTOR to ("节点选择" to "openclash-groups"),
            DashboardCardId.OPENCLASH_STATUS to ("OpenClash 状态" to "openclash-status"),
            DashboardCardId.OPENCLASH_VERSION to ("代理内核版本" to "openclash-version"),
            DashboardCardId.OPENCLASH_MODE to ("运行模式" to "openclash-mode"),
            DashboardCardId.OPENCLASH_CONFIG to ("当前配置" to "openclash-mode"),
            DashboardCardId.OPENCLASH_ROUTE to ("当前线路" to "network"),
            DashboardCardId.OPENCLASH_TRAFFIC to ("实时流量" to "openclash-traffic"),
            DashboardCardId.OPENCLASH_DOWNLOAD_RATE to ("实时下载速率" to "network"),
            DashboardCardId.OPENCLASH_UPLOAD_RATE to ("实时上传速率" to "network"),
            DashboardCardId.OPENCLASH_DOWNLOAD_TOTAL to ("累计下载流量" to "network"),
            DashboardCardId.OPENCLASH_UPLOAD_TOTAL to ("累计上传流量" to "network"),
            DashboardCardId.OPENCLASH_CONNECTIONS to ("活动连接" to "openclash-connections"),
            DashboardCardId.OPENCLASH_GROUPS to ("代理组数量" to "openclash-groups"),
            DashboardCardId.OPENCLASH_SELECTED_GROUP to ("当前代理节点" to "openclash-groups"),
            DashboardCardId.OPENCLASH_MEMORY to ("连接占用内存" to "openclash-memory"),
            DashboardCardId.DOCKER_ENGINE to ("Docker 引擎" to "container"),
            DashboardCardId.DOCKER_REGISTRY to ("镜像仓库" to "container"),
            DashboardCardId.DOCKER_CONTAINERS to ("容器总数" to "container"),
            DashboardCardId.DOCKER_RUNNING to ("运行中容器" to "container"),
            DashboardCardId.DOCKER_PAUSED to ("已暂停容器" to "container"),
            DashboardCardId.DOCKER_STOPPED to ("已停止容器" to "container"),
            DashboardCardId.DOCKER_IMAGES to ("镜像总数" to "container"),
            DashboardCardId.DOCKER_NETWORKS to ("网络总数" to "network"),
            DashboardCardId.DOCKER_VOLUMES to ("卷总数" to "storage"),
            DashboardCardId.DOCKER_HOST_RESOURCES to ("主机资源" to "memory"),
            DashboardCardId.DOCKER_RUNTIME to ("运行环境" to "host"),
            DashboardCardId.DOCKER_STORAGE to ("Docker 数据目录" to "storage"),
            DashboardCardId.DOCKER_CONTAINER_LIST to ("容器清单" to "container"),
            DashboardCardId.DOCKER_IMAGE_LIST to ("镜像清单" to "container"),
            DashboardCardId.DOCKER_NETWORK_LIST to ("网络清单" to "network"),
            DashboardCardId.DOCKER_VOLUME_LIST to ("数据卷清单" to "storage"),
        )

        fun defaultCardOrder(serviceType: ServiceType): List<DashboardCardId> = when (serviceType) {
            ServiceType.OPENCLASH -> zashboardIds
            ServiceType.OPENCLASH_PANEL -> listOf(
                DashboardCardId.OPENCLASH_STATUS,
                DashboardCardId.OPENCLASH_VERSION,
                DashboardCardId.OPENCLASH_MODE,
                DashboardCardId.OPENCLASH_TRAFFIC,
                DashboardCardId.OPENCLASH_CONNECTIONS,
                DashboardCardId.OPENCLASH_GROUPS,
                DashboardCardId.OPENCLASH_MEMORY,
            )
            ServiceType.DOCKER -> listOf(
                DashboardCardId.DOCKER_CONTAINERS,
                DashboardCardId.DOCKER_RUNNING,
                DashboardCardId.DOCKER_ENGINE,
                DashboardCardId.DOCKER_IMAGES,
                DashboardCardId.DOCKER_NETWORKS,
                DashboardCardId.DOCKER_VOLUMES,
                DashboardCardId.DOCKER_HOST_RESOURCES,
            )
            else -> listOf(DashboardCardId.HOST, DashboardCardId.MEMORY, DashboardCardId.UPTIME)
        }

        fun loadingCards(serviceType: ServiceType? = null): List<DashboardCardModel> =
            (serviceType?.let(::defaultCardOrder) ?: listOf(DashboardCardId.HOST, DashboardCardId.MEMORY, DashboardCardId.UPTIME))
                .mapNotNull(::loadingCard)

        fun loadingCard(id: DashboardCardId): DashboardCardModel? = definitions[id]?.let { definition ->
            DashboardCardModel(
                id = id,
                title = definition.first,
                iconKey = definition.second,
                status = DashboardCardStatus.LOADING,
                value = "读取中…",
                detail = "等待系统信息",
            )
        }

        fun from(snapshot: SystemInfoSnapshot): List<DashboardCardModel> =
            allFrom(snapshot).filter { it.status == DashboardCardStatus.READY }

        /** All candidates shown in the repository, including explicitly unsupported metrics. */
        fun catalogue(snapshot: SystemInfoSnapshot?, serviceType: ServiceType): List<DashboardCardCatalogItem> {
            val effectiveSnapshot = snapshot ?: SystemInfoSnapshot()
            val models = when (serviceType) {
                ServiceType.OPENCLASH, ServiceType.OPENCLASH_PANEL ->
                    openClashCards(effectiveSnapshot.openClash ?: OpenClashDashboardSnapshot())
                ServiceType.DOCKER -> dockerCards(effectiveSnapshot.docker ?: DockerDashboardSnapshot())
                else -> systemCards(effectiveSnapshot)
            }.associateBy(DashboardCardModel::id)
            val ids = when (serviceType) {
                ServiceType.OPENCLASH -> zashboardIds
                ServiceType.OPENCLASH_PANEL -> openClashIds
                ServiceType.DOCKER -> dockerIds
                else -> systemIds
            }
            val supported = when (serviceType) {
                ServiceType.OPENCLASH -> zashboardIds.toSet()
                ServiceType.OPENCLASH_PANEL -> openClashIds.toSet()
                ServiceType.DOCKER -> dockerIds.toSet()
                else -> systemIds.filterNot { it in setOf(
                    DashboardCardId.CPU, DashboardCardId.TEMPERATURE, DashboardCardId.STORAGE,
                    DashboardCardId.NETWORK,
                ) }.toSet()
            }
            return ids.mapNotNull { id ->
                val model = models[id] ?: loadingCard(id) ?: return@mapNotNull null
                val addable = id in supported
                val explanation = when {
                    !addable -> when (id) {
                        DashboardCardId.CPU -> "标准 system.info 提供负载，不提供 CPU 百分比"
                        DashboardCardId.TEMPERATURE -> "当前接口不提供 CPU / SoC 温度"
                        DashboardCardId.STORAGE -> "当前接口不提供磁盘容量统计"
                        DashboardCardId.NETWORK -> "当前接口不提供网卡实时速率"
                        else -> "此服务的数据接口暂不支持"
                    }
                    id == DashboardCardId.OPENCLASH_NODE_SELECTOR ->
                        "仅保留节点选择与延迟检测，供 Zashboard 节点入口使用"
                    model.status == DashboardCardStatus.READY -> "数据源已返回，可添加到本页"
                    serviceType == ServiceType.DOCKER -> "Docker 只读信息接口支持；设备未返回字段时显示暂无数据"
                    snapshot == null -> "该类型服务支持此卡片，等待接口返回数据"
                    id == DashboardCardId.OPENCLASH_DOWNLOAD_RATE || id == DashboardCardId.OPENCLASH_UPLOAD_RATE ->
                        "需要连续采样后显示，首个采样周期内可能暂无数据"
                    else -> "接口支持；若设备未返回此字段，将显示暂无数据"
                }
                DashboardCardCatalogItem(
                    model = if (addable) model else model.copy(
                        status = DashboardCardStatus.UNAVAILABLE,
                        value = "暂无数据",
                        detail = explanation,
                    ),
                    addable = addable,
                    explanation = explanation,
                )
            }
        }

        private fun allFrom(snapshot: SystemInfoSnapshot): List<DashboardCardModel> =
            when {
                snapshot.docker != null -> dockerCards(snapshot.docker)
                snapshot.openClash != null -> openClashCards(snapshot.openClash)
                else -> systemCards(snapshot)
            }

        private fun systemCards(snapshot: SystemInfoSnapshot): List<DashboardCardModel> = listOf(
            model(DashboardCardId.CPU, snapshot.cpuUsagePercent != null,
                snapshot.cpuUsagePercent?.let { "${it.percentText()}%" } ?: "暂无数据",
                if (snapshot.cpuUsagePercent == null) "标准 ubus 不提供 CPU 使用百分比" else "当前使用率",
                snapshot.cpuUsagePercent?.let { (it / 100f).coerceIn(0f, 1f) }),
            model(DashboardCardId.TEMPERATURE, snapshot.cpuTemperatureCelsius != null,
                snapshot.cpuTemperatureCelsius?.let { "${it.temperatureText()}°C" } ?: "暂无数据",
                if (snapshot.cpuTemperatureCelsius == null) "当前数据源未提供温度" else "CPU / SoC 温度"),
            model(DashboardCardId.MEMORY, snapshot.memoryUsedBytes != null && snapshot.memoryTotalBytes != null,
                memoryText(snapshot.memoryUsedBytes, snapshot.memoryTotalBytes),
                memoryDetail(snapshot.memoryUsedBytes, snapshot.memoryTotalBytes),
                ratio(snapshot.memoryUsedBytes, snapshot.memoryTotalBytes)),
            model(DashboardCardId.MEMORY_FREE, snapshot.memoryFreeBytes != null,
                snapshot.memoryFreeBytes?.let(::formatBytes) ?: "暂无数据", "系统报告的空闲内存"),
            model(DashboardCardId.MEMORY_SHARED, snapshot.memorySharedBytes != null,
                snapshot.memorySharedBytes?.let(::formatBytes) ?: "暂无数据", "系统报告的共享内存"),
            model(DashboardCardId.MEMORY_BUFFERED, snapshot.memoryBufferedBytes != null,
                snapshot.memoryBufferedBytes?.let(::formatBytes) ?: "暂无数据", "系统报告的缓存内存"),
            model(DashboardCardId.SWAP, snapshot.swapUsedBytes != null && snapshot.swapTotalBytes != null,
                if (snapshot.swapTotalBytes == 0L) "未配置" else memoryText(snapshot.swapUsedBytes, snapshot.swapTotalBytes),
                if (snapshot.swapTotalBytes == 0L) "设备未配置交换分区" else "交换分区使用情况",
                ratio(snapshot.swapUsedBytes, snapshot.swapTotalBytes)),
            model(DashboardCardId.STORAGE, snapshot.storageUsedBytes != null && snapshot.storageTotalBytes != null,
                storageText(snapshot.storageUsedBytes, snapshot.storageTotalBytes),
                storageDetail(snapshot.storageUsedBytes, snapshot.storageTotalBytes),
                ratio(snapshot.storageUsedBytes, snapshot.storageTotalBytes)),
            model(DashboardCardId.UPTIME, snapshot.uptimeMillis != null,
                snapshot.uptimeMillis?.let(::uptimeText) ?: "暂无数据", "系统已运行"),
            model(DashboardCardId.NETWORK, snapshot.rxBytesPerSecond != null || snapshot.txBytesPerSecond != null,
                networkText(snapshot.rxBytesPerSecond, snapshot.txBytesPerSecond), "下载 / 上传"),
            model(DashboardCardId.HOST, listOf(snapshot.hostname, snapshot.model, snapshot.osName).any { !it.isNullOrBlank() },
                snapshot.hostname?.takeIf(String::isNotBlank) ?: snapshot.model ?: "未命名主机",
                listOfNotNull(snapshot.model, snapshot.osName, snapshot.firmware,
                    snapshot.kernel?.let { "内核 $it" }).joinToString(" · ").ifBlank { "数据源未提供" }),
            model(DashboardCardId.HOSTNAME, !snapshot.hostname.isNullOrBlank(), snapshot.hostname ?: "暂无数据", "系统主机名"),
            model(DashboardCardId.MODEL, !snapshot.model.isNullOrBlank(), snapshot.model ?: "暂无数据", "设备板型 / 型号"),
            model(DashboardCardId.OS_DISTRIBUTION, !snapshot.osName.isNullOrBlank(), snapshot.osName ?: "暂无数据", "系统发行版"),
            model(DashboardCardId.FIRMWARE, !snapshot.firmware.isNullOrBlank(), snapshot.firmware ?: "暂无数据", "固件版本或修订号"),
            model(DashboardCardId.KERNEL, !snapshot.kernel.isNullOrBlank(), snapshot.kernel ?: "暂无数据", "Linux 内核版本"),
            loadModel(DashboardCardId.LOAD_1, snapshot.loadAverage1, "1 分钟平均负载"),
            loadModel(DashboardCardId.LOAD_5, snapshot.loadAverage5, "5 分钟平均负载"),
            loadModel(DashboardCardId.LOAD_15, snapshot.loadAverage15, "15 分钟平均负载"),
        )

        private fun openClashCards(snapshot: OpenClashDashboardSnapshot): List<DashboardCardModel> = listOf(
            model(
                DashboardCardId.OPENCLASH_NODE_SELECTOR,
                snapshot.selectableGroups.isNotEmpty(),
                snapshot.selectableGroups.size.takeIf { it > 0 }?.let { "$it 个可切换代理组" } ?: "暂无可切换代理组",
                "选择节点并检测延迟",
            ),
            model(DashboardCardId.OPENCLASH_STATUS, snapshot.running != null,
                when (snapshot.running) { true -> "运行中"; false -> "已停止"; null -> "暂无数据" },
                snapshot.routeLabel?.let { "线路：$it" } ?: "运行状态"),
            model(DashboardCardId.OPENCLASH_VERSION, !snapshot.version.isNullOrBlank(),
                snapshot.version ?: "暂无数据", snapshot.coreLabel ?: "Mihomo / Clash-compatible API"),
            model(DashboardCardId.OPENCLASH_MODE, !snapshot.mode.isNullOrBlank(),
                snapshot.mode ?: "暂无数据", "代理运行模式"),
            model(DashboardCardId.OPENCLASH_CONFIG, !snapshot.configName.isNullOrBlank(),
                snapshot.configName ?: "暂无数据", "当前加载的配置"),
            model(DashboardCardId.OPENCLASH_ROUTE, !snapshot.routeLabel.isNullOrBlank(),
                snapshot.routeLabel ?: "暂无数据", "自动选择的内网 / 公网线路"),
            model(DashboardCardId.OPENCLASH_TRAFFIC,
                snapshot.downloadBytesPerSecond != null || snapshot.uploadBytesPerSecond != null,
                if (snapshot.downloadBytesPerSecond == null && snapshot.uploadBytesPerSecond == null) {
                    "暂无数据"
                } else {
                    "↓ ${formatRate(snapshot.downloadBytesPerSecond)}  ↑ ${formatRate(snapshot.uploadBytesPerSecond)}"
                },
                if (snapshot.downloadBytesPerSecond == null && snapshot.uploadBytesPerSecond == null) {
                    "等待连续采样"
                } else {
                    "最近采样速率"
                }),
            model(DashboardCardId.OPENCLASH_DOWNLOAD_RATE, snapshot.downloadBytesPerSecond != null,
                snapshot.downloadBytesPerSecond?.let(::formatRate) ?: "暂无数据", "最近采样下载速率"),
            model(DashboardCardId.OPENCLASH_UPLOAD_RATE, snapshot.uploadBytesPerSecond != null,
                snapshot.uploadBytesPerSecond?.let(::formatRate) ?: "暂无数据", "最近采样上传速率"),
            model(DashboardCardId.OPENCLASH_DOWNLOAD_TOTAL, snapshot.downloadTotalBytes != null,
                snapshot.downloadTotalBytes?.let(::formatBytes) ?: "暂无数据", "Mihomo 累计下载字节"),
            model(DashboardCardId.OPENCLASH_UPLOAD_TOTAL, snapshot.uploadTotalBytes != null,
                snapshot.uploadTotalBytes?.let(::formatBytes) ?: "暂无数据", "Mihomo 累计上传字节"),
            model(DashboardCardId.OPENCLASH_CONNECTIONS, snapshot.connectionCount != null,
                snapshot.connectionCount?.let { "$it 个" } ?: "暂无数据", "当前活动连接"),
            model(DashboardCardId.OPENCLASH_GROUPS, snapshot.proxyGroupCount != null,
                snapshot.proxyGroupCount?.let { "$it 个" } ?: "暂无数据",
                snapshot.selectedGroup?.let { "当前：$it" } ?: "可用代理组"),
            model(DashboardCardId.OPENCLASH_SELECTED_GROUP, !snapshot.selectedGroup.isNullOrBlank(),
                snapshot.selectedGroup ?: "暂无数据", "第一个可用代理组的当前节点"),
            model(DashboardCardId.OPENCLASH_MEMORY, snapshot.memoryBytes != null,
                snapshot.memoryBytes?.let(::formatBytes) ?: "暂无数据", "Mihomo 当前连接内存"),
        )

        private fun dockerCards(snapshot: DockerDashboardSnapshot): List<DashboardCardModel> = listOf(
            model(DashboardCardId.DOCKER_ENGINE, !snapshot.serverVersion.isNullOrBlank(),
                snapshot.serverVersion ?: "暂无数据",
                listOfNotNull(snapshot.apiVersion?.let { "API $it" }, snapshot.storageDriver).joinToString(" · ").ifBlank { "Docker Engine 服务端版本" }),
            model(DashboardCardId.DOCKER_REGISTRY,
                !snapshot.indexServerAddress.isNullOrBlank() || !snapshot.registryMirrors.isNullOrBlank(),
                snapshot.indexServerAddress ?: "暂无数据",
                snapshot.registryMirrors?.let { "镜像加速：$it" } ?: "Docker 镜像索引地址"),
            model(DashboardCardId.DOCKER_CONTAINERS, snapshot.containersTotal != null,
                snapshot.containersTotal?.let { "$it 个" } ?: "暂无数据", "容器总数"),
            model(DashboardCardId.DOCKER_RUNNING, snapshot.containersRunning != null,
                snapshot.containersRunning?.let { "$it 个" } ?: "暂无数据", "运行中的容器"),
            model(DashboardCardId.DOCKER_PAUSED, snapshot.containersPaused != null,
                snapshot.containersPaused?.let { "$it 个" } ?: "暂无数据",
                if (snapshot.containersPaused != null) "已暂停的容器" else "当前 Dockerman 概览页未返回暂停数"),
            model(DashboardCardId.DOCKER_STOPPED, snapshot.containersStopped != null,
                snapshot.containersStopped?.let { "$it 个" } ?: "暂无数据",
                if (snapshot.containersStopped != null) "已停止的容器" else "当前 Dockerman 概览页未返回停止数"),
            model(DashboardCardId.DOCKER_IMAGES, snapshot.imagesTotal != null,
                snapshot.imagesTotal?.let { "$it 个" } ?: "暂无数据",
                snapshot.imagesUsed?.let { "$it 个镜像正在被容器使用 · 本地镜像总数" } ?: "本地镜像总数"),
            model(DashboardCardId.DOCKER_NETWORKS, snapshot.networksTotal != null,
                snapshot.networksTotal?.let { "$it 个" } ?: "暂无数据", "Docker 网络总数"),
            model(DashboardCardId.DOCKER_VOLUMES, snapshot.volumesTotal != null,
                snapshot.volumesTotal?.let { "$it 个" } ?: "暂无数据", "Docker 卷总数"),
            model(DashboardCardId.DOCKER_HOST_RESOURCES,
                snapshot.cpuCount != null || snapshot.memoryTotalBytes != null,
                listOfNotNull(snapshot.cpuCount?.let { "$it 核" }, snapshot.memoryTotalBytes?.let(::formatBytes))
                    .joinToString(" · ").ifBlank { "暂无数据" },
                "Docker 宿主机 CPU / 内存总量"),
            model(DashboardCardId.DOCKER_RUNTIME,
                !snapshot.operatingSystem.isNullOrBlank() || !snapshot.kernelVersion.isNullOrBlank(),
                snapshot.operatingSystem ?: snapshot.kernelVersion ?: "暂无数据",
                snapshot.kernelVersion?.takeIf { !snapshot.operatingSystem.isNullOrBlank() }
                    ?.let { "内核 $it" } ?: "Docker 宿主机运行环境"),
            model(DashboardCardId.DOCKER_STORAGE, !snapshot.dockerRootDirectory.isNullOrBlank(),
                snapshot.dockerRootDirectory ?: "暂无数据",
                snapshot.dockerRootAvailable?.let { "Docker 根目录 · 可用 $it" }
                    ?: "Docker 数据根目录；未调用耗时的 system/df"),
            resourceListModel(DashboardCardId.DOCKER_CONTAINER_LIST, snapshot.containerList, "容器"),
            resourceListModel(DashboardCardId.DOCKER_IMAGE_LIST, snapshot.imageList, "镜像"),
            resourceListModel(DashboardCardId.DOCKER_NETWORK_LIST, snapshot.networkList, "网络"),
            resourceListModel(DashboardCardId.DOCKER_VOLUME_LIST, snapshot.volumeList, "数据卷"),
        )

        private fun resourceListModel(
            id: DashboardCardId,
            entries: List<DockerResourceSummary>?,
            label: String,
        ): DashboardCardModel {
            val summary = entries?.take(4).orEmpty().joinToString(" · ") { entry ->
                listOfNotNull(entry.name, entry.detail).joinToString("：")
            }
            val more = (entries?.size ?: 0) - 4
            val detail = when {
                entries == null -> "当前 Dockerman 概览页不提供${label}清单数据"
                entries.isEmpty() -> "没有 $label"
                else -> summary + if (more > 0) " 等 ${entries.size} 项" else ""
            }
            return model(
                id = id,
                available = entries != null,
                value = entries?.let { "${it.size} 项" } ?: "暂无数据",
                detail = detail,
            )
        }

        private fun model(
            id: DashboardCardId,
            available: Boolean,
            value: String,
            detail: String,
            progress: Float? = null,
        ): DashboardCardModel {
            val definition = definitions.getValue(id)
            return DashboardCardModel(
                id = id,
                title = definition.first,
                iconKey = definition.second,
                status = if (available) DashboardCardStatus.READY else DashboardCardStatus.UNAVAILABLE,
                value = value,
                detail = detail,
                progress = progress,
            )
        }

        private fun loadModel(id: DashboardCardId, value: Float?, detail: String): DashboardCardModel =
            model(id, value != null, value?.let { String.format(Locale.US, "%.2f", it) } ?: "暂无数据", detail)
    }
}

data class DashboardCardCatalogItem(
    val model: DashboardCardModel,
    val addable: Boolean,
    val explanation: String,
)

fun resolveDashboardCardOrder(
    serviceType: ServiceType,
    savedCardIds: List<String>?,
    catalogue: List<DashboardCardCatalogItem>,
): List<DashboardCardId> {
    val supported = catalogue.asSequence()
        .filter(DashboardCardCatalogItem::addable)
        .map { it.model.id }
        .toSet()
    val requested = savedCardIds?.mapNotNull { raw ->
        runCatching { DashboardCardId.valueOf(raw) }.getOrNull()
    } ?: DashboardCardModel.defaultCardOrder(serviceType)
    val resolved = requested.distinct().filter { it in supported }
    // Existing Zashboard pages saved OpenClash metric IDs before the selector
    // became the only card there. Preserve a useful page after upgrade.
    if (serviceType == ServiceType.OPENCLASH &&
        !savedCardIds.isNullOrEmpty() &&
        resolved.isEmpty() &&
        DashboardCardId.OPENCLASH_NODE_SELECTOR in supported
    ) {
        return listOf(DashboardCardId.OPENCLASH_NODE_SELECTOR)
    }
    return resolved
}

fun moveDashboardCard(cardIds: List<String>, cardId: String, offset: Int): List<String> {
    val from = cardIds.indexOf(cardId)
    if (from < 0 || offset == 0) return cardIds
    val to = (from + offset).coerceIn(0, cardIds.lastIndex)
    if (to == from) return cardIds
    return cardIds.toMutableList().apply { add(to, removeAt(from)) }
}

/** Resolve the live drop index once the dragged card's center crosses a neighbor's center. */
fun dashboardCardDragTargetIndex(
    originIndex: Int,
    dragDistancePx: Float,
    rowStepPx: Float,
    itemCount: Int,
): Int {
    val lastIndex = (itemCount - 1).coerceAtLeast(0)
    val safeOrigin = originIndex.coerceIn(0, lastIndex)
    if (itemCount <= 1 || !dragDistancePx.isFinite() || !rowStepPx.isFinite() || rowStepPx <= 0f) {
        return safeOrigin
    }
    val projectedIndex = safeOrigin + dragDistancePx / rowStepPx
    if (!projectedIndex.isFinite()) return safeOrigin
    return projectedIndex.roundToInt().coerceIn(0, lastIndex)
}

/** Keep the dragged card under the pointer as its backing list index changes. */
fun dashboardCardDragTranslation(
    originIndex: Int,
    currentIndex: Int,
    dragDistancePx: Float,
    rowStepPx: Float,
): Float {
    if (!dragDistancePx.isFinite()) return 0f
    if (!rowStepPx.isFinite() || rowStepPx <= 0f) return dragDistancePx
    return dragDistancePx + (originIndex - currentIndex) * rowStepPx
}

/** Read-only OpenClash fields shown beside the normal system metrics. */
data class OpenClashDashboardSnapshot(
    val running: Boolean? = null,
    val version: String? = null,
    val coreLabel: String? = null,
    val mode: String? = null,
    val configName: String? = null,
    val connectionCount: Int? = null,
    val proxyGroupCount: Int? = null,
    val selectedGroup: String? = null,
    val memoryBytes: Long? = null,
    val downloadBytesPerSecond: Long? = null,
    val uploadBytesPerSecond: Long? = null,
    val routeLabel: String? = null,
    val selectableGroups: List<OpenClashProxyGroup> = emptyList(),
    val downloadTotalBytes: Long? = null,
    val uploadTotalBytes: Long? = null,
)

/** Read-only fields exposed by the authenticated Dockerman overview page. */
data class DockerDashboardSnapshot(
    val serverVersion: String? = null,
    val apiVersion: String? = null,
    val storageDriver: String? = null,
    val indexServerAddress: String? = null,
    val registryMirrors: String? = null,
    val dockerRootDirectory: String? = null,
    val dockerRootAvailable: String? = null,
    val operatingSystem: String? = null,
    val kernelVersion: String? = null,
    val cpuCount: Int? = null,
    val memoryTotalBytes: Long? = null,
    val containersTotal: Int? = null,
    val containersRunning: Int? = null,
    val containersPaused: Int? = null,
    val containersStopped: Int? = null,
    val imagesTotal: Int? = null,
    val imagesUsed: Int? = null,
    val networksTotal: Int? = null,
    val volumesTotal: Int? = null,
    val containerList: List<DockerResourceSummary>? = null,
    val imageList: List<DockerResourceSummary>? = null,
    val networkList: List<DockerResourceSummary>? = null,
    val volumeList: List<DockerResourceSummary>? = null,
)

/** Small read-only row summary; intentionally excludes IDs, labels and config. */
data class DockerResourceSummary(
    val name: String,
    val detail: String? = null,
)

/** A Selector group and its current candidate nodes, supplied by Mihomo `/proxies`. */
data class OpenClashProxyGroup(
    val name: String,
    val currentNode: String?,
    val candidates: List<String>,
)

data class OpenClashNodeLatencyTarget(
    val groupName: String,
    val nodeName: String,
)

/** Keep the explicit manual-choice group easy to find without reordering the others. */
fun prioritizeManualSelectionProxyGroups(groups: List<OpenClashProxyGroup>): List<OpenClashProxyGroup> =
    groups.sortedBy { group ->
        val normalizedName = group.name.trim().lowercase(Locale.ROOT)
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
        if (normalizedName in setOf("手动选择", "manual", "manual select", "manual selection")) 0 else 1
    }

/** Values returned by a vendor-specific or app-server-specific adapter. */
data class SystemInfoSnapshot(
    val hostname: String? = null,
    val model: String? = null,
    val osName: String? = null,
    val firmware: String? = null,
    val kernel: String? = null,
    val cpuUsagePercent: Float? = null,
    val cpuTemperatureCelsius: Float? = null,
    val memoryUsedBytes: Long? = null,
    val memoryTotalBytes: Long? = null,
    val storageUsedBytes: Long? = null,
    val storageTotalBytes: Long? = null,
    val uptimeMillis: Long? = null,
    val rxBytesPerSecond: Long? = null,
    val txBytesPerSecond: Long? = null,
    val openClash: OpenClashDashboardSnapshot? = null,
    val docker: DockerDashboardSnapshot? = null,
    val memoryFreeBytes: Long? = null,
    val memorySharedBytes: Long? = null,
    val memoryBufferedBytes: Long? = null,
    val swapUsedBytes: Long? = null,
    val swapTotalBytes: Long? = null,
    val loadAverage1: Float? = null,
    val loadAverage5: Float? = null,
    val loadAverage15: Float? = null,
)

sealed interface SystemInfoResult {
    data class Success(val snapshot: SystemInfoSnapshot) : SystemInfoResult

    /** The endpoint is valid but this service/version does not expose metrics. */
    data class Unavailable(val reason: String) : SystemInfoResult
}

/**
 * Adapter boundary for real NAS data. Implementations must not return
 * fabricated values when an endpoint omits a metric; leave that field null.
 */
interface SystemInfoProvider {
    suspend fun load(service: ServiceConfig? = null): SystemInfoResult
}

/** Honest default while the remote system-information API is not wired yet. */
object UnavailableSystemInfoProvider : SystemInfoProvider {
    override suspend fun load(service: ServiceConfig?): SystemInfoResult =
        SystemInfoResult.Unavailable("当前版本尚未接入 NAS 系统信息接口")
}

data class SystemDashboardUiState(
    val cards: List<DashboardCardModel> = emptyList(),
    val cardCatalogue: List<DashboardCardCatalogItem> = emptyList(),
    val selectableGroups: List<OpenClashProxyGroup> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    val errorMessage: String? = null,
    val lastUpdatedEpochMillis: Long? = null,
)

sealed interface OpenClashNodeSwitchResult {
    data object Success : OpenClashNodeSwitchResult
    data class Failure(val message: String) : OpenClashNodeSwitchResult
}

/** Explicit runtime-only Mihomo proxy selector; it must not rewrite router config files. */
fun interface OpenClashNodeSwitcher {
    suspend fun selectNode(
        service: ServiceConfig,
        groupName: String,
        nodeName: String,
    ): OpenClashNodeSwitchResult
}

sealed interface OpenClashNodeLatencyResult {
    data class Success(val delayMillis: Int) : OpenClashNodeLatencyResult
    data class Failure(val message: String) : OpenClashNodeLatencyResult
}

/** Explicitly probes one Mihomo candidate without changing a selector group. */
fun interface OpenClashNodeLatencyTester {
    suspend fun testNodeLatency(
        service: ServiceConfig,
        groupName: String,
        nodeName: String,
    ): OpenClashNodeLatencyResult

    /** Tests each requested proxy once, reporting start and completion for progress UI. */
    suspend fun testNodeLatencies(
        service: ServiceConfig,
        targets: List<OpenClashNodeLatencyTarget>,
        onNodeTesting: suspend (OpenClashNodeLatencyTarget) -> Unit = {},
        onNodeResult: suspend (OpenClashNodeLatencyTarget, OpenClashNodeLatencyResult) -> Unit,
    ) {
        targets.distinct().forEach { target ->
            onNodeTesting(target)
            val result = try {
                testNodeLatency(service, target.groupName, target.nodeName)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                OpenClashNodeLatencyResult.Failure("延迟检测失败，请稍后重试")
            }
            onNodeResult(target, result)
        }
    }
}

/**
 * Small UDF coordinator for the dashboard. It owns refresh concurrency and
 * converts adapter results into stable display models, leaving the screen
 * free of network/API assumptions.
 */
class SystemDashboardPresenter(
    private val provider: SystemInfoProvider,
    private val service: ServiceConfig? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _state = MutableStateFlow(
        SystemDashboardUiState(
            cardCatalogue = DashboardCardModel.catalogue(
                snapshot = null,
                serviceType = service?.serviceType ?: ServiceType.ISTORE,
            ),
        ),
    )
    val state: StateFlow<SystemDashboardUiState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    fun refresh() {
        if (refreshJob?.isActive == true) return
        val initialLoad = !_state.value.hasLoaded
        _state.update { current ->
            current.copy(
                cards = if (initialLoad) DashboardCardModel.loadingCards(service?.serviceType) else current.cards,
                isLoading = initialLoad,
                isRefreshing = true,
                errorMessage = null,
            )
        }
        refreshJob = scope.launch {
            try {
                when (val result = provider.load(service)) {
                    is SystemInfoResult.Success -> _state.update {
                        it.copy(
                            cards = DashboardCardModel.from(result.snapshot),
                            cardCatalogue = DashboardCardModel.catalogue(
                                result.snapshot,
                                service?.serviceType ?: ServiceType.ISTORE,
                            ),
                            selectableGroups = result.snapshot.openClash?.selectableGroups.orEmpty(),
                            isLoading = false,
                            isRefreshing = false,
                            hasLoaded = true,
                            errorMessage = null,
                            lastUpdatedEpochMillis = System.currentTimeMillis(),
                        )
                    }
                    is SystemInfoResult.Unavailable -> _state.update {
                        it.copy(
                            cards = emptyList(),
                            cardCatalogue = DashboardCardModel.catalogue(
                                snapshot = null,
                                serviceType = service?.serviceType ?: ServiceType.ISTORE,
                            ),
                            selectableGroups = emptyList(),
                            isLoading = false,
                            isRefreshing = false,
                            hasLoaded = true,
                            errorMessage = result.reason.ifBlank { "当前服务未提供系统信息" },
                            lastUpdatedEpochMillis = null,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                val message = failure.message?.takeIf(String::isNotBlank) ?: "读取系统信息失败"
                _state.update { current ->
                    current.copy(
                        cards = if (current.hasLoaded) current.cards else emptyList(),
                        selectableGroups = if (current.hasLoaded) current.selectableGroups else emptyList(),
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = message,
                    )
                }
            }
        }
    }

    fun close() {
        refreshJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
    }
}

private fun Float.percentText(): String = String.format(Locale.US, "%.0f", coerceIn(0f, 100f))

private fun Float.temperatureText(): String = String.format(Locale.US, "%.1f", this)

private fun ratio(used: Long?, total: Long?): Float? {
    if (used == null || total == null || total <= 0L) return null
    return (used.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
}

private fun memoryText(used: Long?, total: Long?): String =
    if (used != null && total != null && total > 0L) {
        "${formatBytes(used)} / ${formatBytes(total)}"
    } else {
        "暂无数据"
    }

private fun memoryDetail(used: Long?, total: Long?): String =
    if (used != null && total != null && total > 0L) {
        "已使用 ${(used.toDouble() / total.toDouble() * 100).roundToInt().coerceIn(0, 100)}%"
    } else {
        "数据源未提供"
    }

private fun storageText(used: Long?, total: Long?): String =
    if (used != null && total != null && total > 0L) {
        "${formatBytes(used)} / ${formatBytes(total)}"
    } else {
        "暂无数据"
    }

private fun storageDetail(used: Long?, total: Long?): String =
    if (used != null && total != null && total > 0L) {
        "已使用 ${(used.toDouble() / total.toDouble() * 100).roundToInt().coerceIn(0, 100)}%"
    } else {
        "数据源未提供"
    }

private fun networkText(rx: Long?, tx: Long?): String =
    if (rx != null || tx != null) {
        "↓ ${formatRate(rx)}  ↑ ${formatRate(tx)}"
    } else {
        "暂无数据"
    }

private fun uptimeText(milliseconds: Long): String {
    val totalMinutes = (milliseconds.coerceAtLeast(0L) / 60_000L)
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes / 60) % 24
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> "${days}天 ${hours}小时"
        hours > 0 -> "${hours}小时 ${minutes}分钟"
        else -> "${minutes}分钟"
    }
}

private fun formatRate(bytesPerSecond: Long?): String =
    bytesPerSecond?.let(::formatBytes)?.plus("/s") ?: "—"

private fun formatBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L).toDouble()
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var scaled = value
    var unitIndex = 0
    while (scaled >= 1024.0 && unitIndex < units.lastIndex) {
        scaled /= 1024.0
        unitIndex++
    }
    return if (unitIndex == 0) {
        "${scaled.roundToInt()} ${units[unitIndex]}"
    } else {
        String.format(Locale.US, "%.1f %s", scaled, units[unitIndex])
    }
}
