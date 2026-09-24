package xin.dponnood.remoteservice

import android.webkit.CookieManager
import java.net.URI
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import xin.dponnood.remoteservice.core.logging.LogLevel
import xin.dponnood.remoteservice.core.logging.LogRepository
import xin.dponnood.remoteservice.core.model.ServiceConfig
import xin.dponnood.remoteservice.core.model.ServiceType
import xin.dponnood.remoteservice.core.network.HttpOpenClashApiTransport
import xin.dponnood.remoteservice.core.network.OpenClashApiException
import xin.dponnood.remoteservice.core.network.OpenClashConfig
import xin.dponnood.remoteservice.core.network.OpenClashConnectionSnapshot
import xin.dponnood.remoteservice.core.network.OpenClashProxySnapshot
import xin.dponnood.remoteservice.core.network.OpenClashProxySelector
import xin.dponnood.remoteservice.core.network.OpenClashProxyDelayTester
import xin.dponnood.remoteservice.core.network.OpenClashVersion
import xin.dponnood.remoteservice.core.network.ReadOnlyOpenClashApiClient
import xin.dponnood.remoteservice.core.network.RouteKind
import xin.dponnood.remoteservice.core.network.RouteResolution
import xin.dponnood.remoteservice.core.network.RouteResolutionResult
import xin.dponnood.remoteservice.core.network.RouteResolver
import xin.dponnood.remoteservice.core.network.toRouteConfig
import xin.dponnood.remoteservice.feature.services.OpenClashDashboardSnapshot
import xin.dponnood.remoteservice.feature.services.OpenClashNodeSwitchResult
import xin.dponnood.remoteservice.feature.services.OpenClashNodeSwitcher
import xin.dponnood.remoteservice.feature.services.OpenClashNodeLatencyResult
import xin.dponnood.remoteservice.feature.services.OpenClashNodeLatencyTester
import xin.dponnood.remoteservice.feature.services.OpenClashProxyGroup
import xin.dponnood.remoteservice.feature.services.SystemInfoProvider
import xin.dponnood.remoteservice.feature.services.SystemInfoResult
import xin.dponnood.remoteservice.feature.services.SystemInfoSnapshot

/**
 * Read-only native dashboard adapter for an OpenClash service.
 *
 * The LuCI session is used only to obtain the controller secret from the
 * authenticated OpenClash status endpoint. The secret is kept in a local
 * variable for the duration of the request and is never placed in a URL or a
 * log entry. Traffic rate is derived from the controller's cumulative
 * connection totals so the first native card does not need a streaming client.
 */
internal class OpenClashSystemInfoProvider(
    private val routeResolver: RouteResolver,
    private val sessionManager: IStoreSessionManager,
    private val cookieManager: CookieManager = CookieManager.getInstance(),
    private val controllerStatusClient: OpenClashControllerStatusClient =
        OpenClashControllerStatusClient(),
    private val logRepository: LogRepository? = null,
) : SystemInfoProvider, OpenClashNodeSwitcher, OpenClashNodeLatencyTester {
    private val counters = ConcurrentHashMap<String, TrafficCounter>()
    private val lastSuccessLogAt = ConcurrentHashMap<String, Long>()
    private val lastApiFailureLogAt = ConcurrentHashMap<String, Long>()

    override suspend fun load(service: ServiceConfig?): SystemInfoResult {
        if (service == null || service.serviceType !in setOf(ServiceType.OPENCLASH, ServiceType.OPENCLASH_PANEL)) {
            return SystemInfoResult.Unavailable("请先添加 OpenClash 服务")
        }
        val resolved = when (val result = routeResolver.resolve(service.toRouteConfig())) {
            is RouteResolutionResult.Success -> result.value
            is RouteResolutionResult.Failure -> {
                logRepository?.append(
                    LogLevel.WARN,
                    "OPENCLASH_STATUS_ROUTE_UNAVAILABLE",
                    "OpenClash 状态线路不可达",
                    context = mapOf(
                        "service_id" to service.id,
                        "route_error" to result.code.name,
                    ),
                )
                return SystemInfoResult.Unavailable("线路不可达：${result.code.name}")
            }
        }
        var sessionResult = runCatchingCancellable { sessionManager.ensure(service, resolved) }.getOrNull()
        val session = (sessionResult as? IStoreSessionResult.Authenticated)?.session
        val origin = IStoreSessionManager.originOf(resolved.endpoint.url)
        var cookie = session?.cookieHeader ?: origin?.let { readCookie(it) }
        var controllerStatus = origin?.let { controllerStatusClient.fetch(it, cookie) }
            ?: OpenClashControllerStatusResult(info = null, requiresSessionRefresh = false)
        if (controllerStatus.requiresSessionRefresh && sessionResult is IStoreSessionResult.Authenticated && origin != null) {
            controllerStatus = refreshOpenClashStatusOnce(
                initial = controllerStatus,
                reauthenticate = {
                    val refreshed = runCatchingCancellable {
                        sessionManager.forceReauthenticate(service, resolved)
                    }.getOrNull()
                    val refreshedSession = (refreshed as? IStoreSessionResult.Authenticated)?.session
                    if (refreshedSession != null) {
                        sessionResult = refreshed
                        cookie = refreshedSession.cookieHeader.ifBlank { readCookie(origin).orEmpty() }
                        cookie?.takeIf(String::isNotBlank)
                    } else {
                        sessionResult = refreshed
                        null
                    }
                },
                fetch = { refreshedCookie -> controllerStatusClient.fetch(origin, refreshedCookie) },
            )
        }
        val controller = controllerStatus.info
        val apiBase = apiBaseUrl(resolved, controller?.controllerHost, controller?.controllerPort)
            ?: return SystemInfoResult.Unavailable("OpenClash API 地址无效")
        val secret = controller?.secret
        val client = ReadOnlyOpenClashApiClient(
            HttpOpenClashApiTransport(
                baseUrl = apiBase,
                secretProvider = { secret },
                connectTimeoutMs = API_CONNECT_TIMEOUT_MS,
                readTimeoutMs = API_READ_TIMEOUT_MS,
            ),
        )

        val (version, config, proxies, connections) = coroutineScope {
            val versionJob = async {
                readApi(service.id, resolved.endpoint.kind.name, "version") { client.fetchVersion() }
            }
            val configJob = async {
                readApi(service.id, resolved.endpoint.kind.name, "configs") { client.fetchConfig() }
            }
            val proxyJob = async {
                readApi(service.id, resolved.endpoint.kind.name, "proxies") { client.fetchProxies() }
            }
            val connectionJob = async {
                readApi(service.id, resolved.endpoint.kind.name, "connections") { client.fetchConnections() }
            }
            awaitAll(versionJob, configJob, proxyJob, connectionJob).let { values ->
                ApiReadSnapshot(
                    values[0] as OpenClashVersion?,
                    values[1] as OpenClashConfig?,
                    values[2] as OpenClashProxySnapshot?,
                    values[3] as OpenClashConnectionSnapshot?,
                )
            }
        }

        if (version == null && config == null && proxies == null && connections == null && controller == null) {
            val failedSessionResult = sessionResult
            val reason = when (failedSessionResult) {
                is IStoreSessionResult.MissingCredentials -> failedSessionResult.message
                is IStoreSessionResult.Failed -> failedSessionResult.message
                else -> "OpenClash API 未返回可读数据"
            }
            logRepository?.append(
                LogLevel.WARN,
                "OPENCLASH_STATUS_UNAVAILABLE",
                reason,
                context = mapOf(
                    "service_id" to service.id,
                    "route" to resolved.endpoint.kind.name,
                    "api_base_host" to hostForLog(apiBase),
                ),
            )
            return SystemInfoResult.Unavailable(reason)
        }

        val rates = connections?.let { deriveRates(service.id, resolved.endpoint.kind, it) }
        val groups = proxies?.groups.orEmpty()
        val selectedGroup = groups.firstOrNull { !it.now.isNullOrBlank() }
            ?.let { group -> "${group.name}：${group.now}" }
        val snapshot = OpenClashDashboardSnapshot(
            running = controller?.running,
            version = version?.version,
            coreLabel = if (version?.meta == true) "Mihomo Meta" else "Clash-compatible API",
            mode = controller?.runMode ?: config?.mode,
            configName = controller?.configName,
            connectionCount = connections?.connections?.size,
            proxyGroupCount = proxies?.groups?.size,
            selectedGroup = selectedGroup,
            memoryBytes = connections?.memoryBytes,
            downloadBytesPerSecond = rates?.downloadBytesPerSecond,
            uploadBytesPerSecond = rates?.uploadBytesPerSecond,
            routeLabel = if (resolved.endpoint.kind == RouteKind.INTERNAL) "内网" else "公网",
            downloadTotalBytes = connections?.downloadTotalBytes,
            uploadTotalBytes = connections?.uploadTotalBytes,
            selectableGroups = proxies?.proxies.orEmpty()
                .filter { it.type.equals("Selector", ignoreCase = true) }
                .mapNotNull { group ->
                    val candidates = group.all.filter(String::isNotBlank).distinct()
                    candidates.takeIf { it.isNotEmpty() }?.let {
                        OpenClashProxyGroup(
                            name = group.name,
                            currentNode = group.now,
                            candidates = it,
                        )
                    }
                },
        )
        appendSuccessLogAtMostEvery(
            serviceId = service.id,
            route = resolved.endpoint.kind.name,
            version = version?.version,
            proxyGroups = groups.size,
            connections = connections?.connections?.size ?: 0,
        )
        return SystemInfoResult.Success(SystemInfoSnapshot(openClash = snapshot))
    }

    override suspend fun selectNode(
        service: ServiceConfig,
        groupName: String,
        nodeName: String,
    ): OpenClashNodeSwitchResult {
        if (service.serviceType != ServiceType.OPENCLASH) {
            return OpenClashNodeSwitchResult.Failure("当前服务不是 OpenClash")
        }
        if (groupName.isBlank() || nodeName.isBlank()) {
            return OpenClashNodeSwitchResult.Failure("代理组和节点名称不能为空")
        }

        var routeLabel = "unknown"
        return try {
            val resolved = when (val result = routeResolver.resolve(service.toRouteConfig())) {
                is RouteResolutionResult.Success -> result.value
                is RouteResolutionResult.Failure -> return OpenClashNodeSwitchResult.Failure(
                    "OpenClash 线路不可用（${result.code.name}）",
                )
            }
            routeLabel = resolved.endpoint.kind.name
            val origin = IStoreSessionManager.originOf(resolved.endpoint.url)
                ?: return OpenClashNodeSwitchResult.Failure("OpenClash 服务地址无效")
            var sessionResult = runCatchingCancellable { sessionManager.ensure(service, resolved) }.getOrNull()
            var session = (sessionResult as? IStoreSessionResult.Authenticated)?.session
            var cookie = session?.cookieHeader ?: readCookie(origin)
            var status = controllerStatusClient.fetch(origin, cookie)
            if (status.requiresSessionRefresh && sessionResult is IStoreSessionResult.Authenticated) {
                sessionResult = runCatchingCancellable {
                    sessionManager.forceReauthenticate(service, resolved)
                }.getOrNull()
                session = (sessionResult as? IStoreSessionResult.Authenticated)?.session
                if (session != null) {
                    cookie = session.cookieHeader.ifBlank { readCookie(origin).orEmpty() }
                    status = controllerStatusClient.fetch(origin, cookie)
                }
            }
            val controller = status.info ?: run {
                val message = when (sessionResult) {
                    is IStoreSessionResult.MissingCredentials -> sessionResult.message
                    is IStoreSessionResult.Failed -> "iStore 登录失败：${sessionResult.message}"
                    else -> "无法读取 OpenClash 控制器信息，请确认 iStore 登录状态和权限"
                }
                return OpenClashNodeSwitchResult.Failure(message)
            }
            val apiBase = apiBaseUrl(resolved, controller.controllerHost, controller.controllerPort)
                ?: return OpenClashNodeSwitchResult.Failure("OpenClash API 地址无效")
            val transport = HttpOpenClashApiTransport(
                baseUrl = apiBase,
                secretProvider = { controller.secret },
                connectTimeoutMs = API_CONNECT_TIMEOUT_MS,
                readTimeoutMs = API_READ_TIMEOUT_MS,
            )

            // Re-read the current list immediately before writing, so a stale card cannot
            // select a node that was removed from a refreshed subscription.
            val freshGroup = ReadOnlyOpenClashApiClient(transport).fetchProxies().proxies
                .firstOrNull { it.name == groupName }
                ?: return OpenClashNodeSwitchResult.Failure("代理组已变化，请刷新状态后重试")
            if (!freshGroup.type.equals("Selector", ignoreCase = true)) {
                return OpenClashNodeSwitchResult.Failure("该代理组不支持手动节点切换")
            }
            if (nodeName !in freshGroup.all) {
                return OpenClashNodeSwitchResult.Failure("该节点已不在代理组内，请刷新列表后重试")
            }

            OpenClashProxySelector(transport).selectNode(groupName, nodeName)
            logRepository?.append(
                LogLevel.INFO,
                "OPENCLASH_NODE_SWITCHED",
                "OpenClash 运行时节点切换已提交",
                context = mapOf(
                    "service_id" to service.id,
                    "route" to routeLabel,
                    "group_type" to "Selector",
                ),
            )
            OpenClashNodeSwitchResult.Success
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val apiFailure = failure as? OpenClashApiException
            val logNow = System.currentTimeMillis()
            if (lastApiFailureLogAt.claimLogInterval(
                    "${service.id}|$routeLabel|node-switch",
                    logNow,
                    API_FAILURE_LOG_INTERVAL_MS,
                )
            ) {
                logRepository?.append(
                    LogLevel.WARN,
                    "OPENCLASH_NODE_SWITCH_FAILED",
                    "OpenClash 运行时节点切换失败",
                    context = buildMap {
                        put("service_id", service.id)
                        put("route", routeLabel)
                        apiFailure?.statusCode?.let { put("status_code", it.toString()) }
                        put("error_type", failure::class.java.simpleName)
                    },
                )
            }
            OpenClashNodeSwitchResult.Failure(
                when (apiFailure?.statusCode) {
                    401, 403 -> "OpenClash 控制器拒绝了切换请求，请检查控制器认证和权限"
                    else -> "节点切换失败，请确认 OpenClash 正常后重试"
                },
            )
        }
    }

    override suspend fun testNodeLatency(
        service: ServiceConfig,
        groupName: String,
        nodeName: String,
    ): OpenClashNodeLatencyResult {
        if (service.serviceType != ServiceType.OPENCLASH) {
            return OpenClashNodeLatencyResult.Failure("当前服务不是 OpenClash")
        }
        if (groupName.isBlank() || nodeName.isBlank()) {
            return OpenClashNodeLatencyResult.Failure("代理组和节点名称不能为空")
        }

        var routeLabel = "unknown"
        return try {
            val resolved = when (val result = routeResolver.resolve(service.toRouteConfig())) {
                is RouteResolutionResult.Success -> result.value
                is RouteResolutionResult.Failure -> return OpenClashNodeLatencyResult.Failure(
                    "OpenClash 线路不可用（${result.code.name}）",
                )
            }
            routeLabel = resolved.endpoint.kind.name
            val origin = IStoreSessionManager.originOf(resolved.endpoint.url)
                ?: return OpenClashNodeLatencyResult.Failure("OpenClash 服务地址无效")
            var sessionResult = runCatchingCancellable { sessionManager.ensure(service, resolved) }.getOrNull()
            var session = (sessionResult as? IStoreSessionResult.Authenticated)?.session
            var cookie = session?.cookieHeader ?: readCookie(origin)
            var status = controllerStatusClient.fetch(origin, cookie)
            if (status.requiresSessionRefresh && sessionResult is IStoreSessionResult.Authenticated) {
                sessionResult = runCatchingCancellable {
                    sessionManager.forceReauthenticate(service, resolved)
                }.getOrNull()
                session = (sessionResult as? IStoreSessionResult.Authenticated)?.session
                if (session != null) {
                    cookie = session.cookieHeader.ifBlank { readCookie(origin).orEmpty() }
                    status = controllerStatusClient.fetch(origin, cookie)
                }
            }
            val controller = status.info ?: run {
                val message = when (sessionResult) {
                    is IStoreSessionResult.MissingCredentials -> sessionResult.message
                    is IStoreSessionResult.Failed -> "iStore 登录失败：${sessionResult.message}"
                    else -> "无法读取 OpenClash 控制器信息，请确认 iStore 登录状态和权限"
                }
                return OpenClashNodeLatencyResult.Failure(message)
            }
            val apiBase = apiBaseUrl(resolved, controller.controllerHost, controller.controllerPort)
                ?: return OpenClashNodeLatencyResult.Failure("OpenClash API 地址无效")
            val transport = HttpOpenClashApiTransport(
                baseUrl = apiBase,
                secretProvider = { controller.secret },
                connectTimeoutMs = API_CONNECT_TIMEOUT_MS,
                readTimeoutMs = NODE_DELAY_READ_TIMEOUT_MS,
            )

            // Validate that the tapped candidate is still part of a manual Selector before probing it.
            val freshGroup = ReadOnlyOpenClashApiClient(transport).fetchProxies().proxies
                .firstOrNull { it.name == groupName }
                ?: return OpenClashNodeLatencyResult.Failure("代理组已变化，请刷新状态后重试")
            if (!freshGroup.type.equals("Selector", ignoreCase = true)) {
                return OpenClashNodeLatencyResult.Failure("该代理组不支持手动节点测速")
            }
            if (nodeName !in freshGroup.all) {
                return OpenClashNodeLatencyResult.Failure("该节点已不在代理组内，请刷新列表后重试")
            }

            val delayMillis = OpenClashProxyDelayTester(transport).testNodeDelay(nodeName)
            logRepository?.append(
                LogLevel.INFO,
                "OPENCLASH_NODE_DELAY_TESTED",
                "OpenClash 单节点延迟检测完成",
                context = mapOf(
                    "service_id" to service.id,
                    "route" to routeLabel,
                    "group_name" to groupName,
                    "delay_ms" to delayMillis.toString(),
                ),
            )
            OpenClashNodeLatencyResult.Success(delayMillis)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val apiFailure = failure as? OpenClashApiException
            val logNow = System.currentTimeMillis()
            if (lastApiFailureLogAt.claimLogInterval(
                    "${service.id}|$routeLabel|node-delay",
                    logNow,
                    API_FAILURE_LOG_INTERVAL_MS,
                )
            ) {
                logRepository?.append(
                    LogLevel.WARN,
                    "OPENCLASH_NODE_DELAY_FAILED",
                    "OpenClash 单节点延迟检测失败",
                    context = buildMap {
                        put("service_id", service.id)
                        put("route", routeLabel)
                        put("group_name", groupName)
                        apiFailure?.statusCode?.let { put("status_code", it.toString()) }
                        put("error_type", failure::class.java.simpleName)
                    },
                )
            }
            OpenClashNodeLatencyResult.Failure(
                when {
                    apiFailure?.statusCode == 404 && apiFailure.path.substringBefore('?').endsWith("/delay") ->
                        "当前 Clash 控制器不支持节点延迟检测"
                    apiFailure?.statusCode in setOf(401, 403) -> "控制器拒绝了测速请求，请检查认证和权限"
                    apiFailure?.statusCode in setOf(408, 504) || failure is SocketTimeoutException ->
                        "延迟检测超时，请稍后重试"
                    else -> "延迟检测失败，请确认节点可用后重试"
                },
            )
        }
    }

    private suspend fun <T> readApi(
        serviceId: String,
        route: String,
        endpoint: String,
        block: suspend () -> T,
    ): T? {
        return try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            appendApiFailureAtMostEvery(serviceId, route, endpoint, failure)
            null
        }
    }

    private fun appendApiFailureAtMostEvery(
        serviceId: String,
        route: String,
        endpoint: String,
        failure: Throwable,
    ) {
        val repository = logRepository ?: return
        val now = System.currentTimeMillis()
        val key = "$serviceId|$route|$endpoint"
        if (!lastApiFailureLogAt.claimLogInterval(key, now, API_FAILURE_LOG_INTERVAL_MS)) return
        val apiFailure = failure as? OpenClashApiException
        repository.append(
            LogLevel.WARN,
            "OPENCLASH_API_READ_FAILED",
            "OpenClash 只读接口读取失败",
            context = buildMap {
                put("service_id", serviceId)
                put("route", route)
                put("endpoint", endpoint)
                apiFailure?.statusCode?.let { put("status_code", it.toString()) }
                put("error_type", failure::class.java.simpleName)
            },
        )
    }

    private fun appendSuccessLogAtMostEvery(
        serviceId: String,
        route: String,
        version: String?,
        proxyGroups: Int,
        connections: Int,
    ) {
        val repository = logRepository ?: return
        val now = System.currentTimeMillis()
        val key = "$serviceId|$route"
        val previous = lastSuccessLogAt.put(key, now)
        if (previous != null && now - previous < SUCCESS_LOG_INTERVAL_MS) return
        repository.append(
            LogLevel.INFO,
            "OPENCLASH_STATUS_READ",
            "OpenClash 原生状态读取完成",
            context = mapOf(
                "service_id" to serviceId,
                "route" to route,
                "version" to (version ?: "unknown"),
                "proxy_groups" to proxyGroups.toString(),
                "connections" to connections.toString(),
            ),
        )
    }

    private fun deriveRates(
        serviceId: String,
        routeKind: RouteKind,
        current: OpenClashConnectionSnapshot,
    ): RateSnapshot {
        val now = System.currentTimeMillis()
        val key = "$serviceId|${routeKind.name}"
        val currentDownload = current.downloadTotalBytes
        val currentUpload = current.uploadTotalBytes
        val previous = counters.put(
            key,
            TrafficCounter(
                timestampMillis = now,
                downloadTotalBytes = currentDownload,
                uploadTotalBytes = currentUpload,
            ),
        )
        if (previous == null || previous.downloadTotalBytes == null ||
            previous.uploadTotalBytes == null || currentDownload == null ||
            currentUpload == null
        ) {
            return RateSnapshot()
        }
        val elapsed = (now - previous.timestampMillis).coerceAtLeast(1L)
        return RateSnapshot(
            downloadBytesPerSecond = rate(currentDownload, previous.downloadTotalBytes, elapsed),
            uploadBytesPerSecond = rate(currentUpload, previous.uploadTotalBytes, elapsed),
        )
    }

    private fun rate(current: Long, previous: Long, elapsedMillis: Long): Long? {
        if (current < previous) return null
        return ((current - previous).toDouble() * 1_000.0 / elapsedMillis.toDouble())
            .toLong()
            .coerceAtLeast(0L)
    }

    private suspend fun readCookie(origin: String): String? = readLuciCookie(cookieManager, origin)

    private fun apiBaseUrl(
        resolution: RouteResolution,
        controllerHost: String?,
        controllerPort: Int?,
    ): String? = openClashApiBaseUrl(
        resolution.endpoint.url,
        resolution.endpoint.kind,
        controllerHost,
        controllerPort,
    )

    private fun hostForLog(url: String): String =
        runCatching { URI(url).host?.lowercase(Locale.US) }.getOrNull() ?: "unknown"

    private data class ApiReadSnapshot(
        val version: OpenClashVersion?,
        val config: OpenClashConfig?,
        val proxies: OpenClashProxySnapshot?,
        val connections: OpenClashConnectionSnapshot?,
    )

    private data class TrafficCounter(
        val timestampMillis: Long,
        val downloadTotalBytes: Long?,
        val uploadTotalBytes: Long?,
    )

    private data class RateSnapshot(
        val downloadBytesPerSecond: Long? = null,
        val uploadBytesPerSecond: Long? = null,
    )

    private companion object {
        const val API_CONNECT_TIMEOUT_MS = 2_500
        const val API_READ_TIMEOUT_MS = 3_000
        const val NODE_DELAY_READ_TIMEOUT_MS = OpenClashProxyDelayTester.DEFAULT_TIMEOUT_MS + 2_000
        const val SUCCESS_LOG_INTERVAL_MS = 30_000L
        const val API_FAILURE_LOG_INTERVAL_MS = 30_000L
    }
}

private suspend fun <T> runCatchingCancellable(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

internal fun openClashApiBaseUrl(
    endpointUrl: String,
    routeKind: RouteKind,
    controllerHost: String?,
    controllerPort: Int?,
): String? {
    val uri = runCatching { URI(endpointUrl) }.getOrNull() ?: return null
    val host = uri.host?.lowercase(Locale.US) ?: return null
    return if (routeKind == RouteKind.INTERNAL) {
        val apiHost = controllerHost?.let(::normalizeControllerHost) ?: host
        val port = controllerPort?.takeIf { it in 1..65535 } ?: 9090
        "http://$apiHost:$port"
    } else {
        val forwardedHost = host
            .removePrefix("i.")
            .takeIf { host.startsWith("i.") && it.contains('.') }
            ?.let { "clash.$it" }
        if (forwardedHost != null) {
            "https://$forwardedHost"
        } else {
            val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
            val port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
            if ((scheme == "https" && port == 443) || (scheme == "http" && port == 80)) {
                "$scheme://$host"
            } else {
                "$scheme://$host:$port"
            }
        }
    }
}

private fun normalizeControllerHost(value: String): String? {
    val candidate = value.trim()
    if (candidate.isBlank() || candidate.any { it.isWhitespace() || it in "/?#@" }) return null
    val uriHost = when {
        candidate.startsWith("[") && candidate.endsWith("]") -> candidate
        candidate.count { it == ':' } > 1 -> "[$candidate]"
        else -> candidate
    }
    val parsed = runCatching { URI("http://$uriHost") }.getOrNull() ?: return null
    val parsedHost = parsed.host?.removePrefix("[")?.removeSuffix("]")
        ?.lowercase(Locale.US)
        ?.removeSuffix(".")
        ?: return null
    if (parsed.rawPath.isNotEmpty() && parsed.rawPath != "/") return null
    if (parsed.rawQuery != null || parsed.rawFragment != null || parsed.rawUserInfo != null || parsed.port != -1) {
        return null
    }
    return if (parsedHost.contains(':')) "[$parsedHost]" else parsedHost
}
