package xin.dponnood.remoteservice

import android.webkit.CookieManager
import xin.dponnood.remoteservice.core.logging.LogLevel
import xin.dponnood.remoteservice.core.logging.LogRepository
import xin.dponnood.remoteservice.core.model.ServiceConfig
import xin.dponnood.remoteservice.core.model.ServiceType
import xin.dponnood.remoteservice.core.network.RouteKind
import xin.dponnood.remoteservice.core.network.RouteResolution
import xin.dponnood.remoteservice.core.network.RouteResolutionResult
import xin.dponnood.remoteservice.core.network.RouteResolver
import xin.dponnood.remoteservice.core.network.toRouteConfig
import xin.dponnood.remoteservice.feature.web.ServiceWebCoordinator
import xin.dponnood.remoteservice.feature.web.WebSessionKey
import xin.dponnood.remoteservice.feature.web.WebTarget

sealed interface OpenClashDashboardTargetResult {
    data class Ready(
        val target: WebTarget,
        val resolution: RouteResolution,
    ) : OpenClashDashboardTargetResult

    data class Unavailable(
        val message: String,
        val resolution: RouteResolution? = null,
    ) : OpenClashDashboardTargetResult
}

/** Resolves the authenticated LuCI session and then opens OpenClash's real Zashboard target. */
internal class OpenClashDashboardResolver(
    private val routeResolver: RouteResolver,
    private val sessionManager: IStoreSessionManager,
    private val statusClient: OpenClashControllerStatusClient,
    private val cookieManager: CookieManager = CookieManager.getInstance(),
    private val logRepository: LogRepository? = null,
) {
    suspend fun resolve(service: ServiceConfig): OpenClashDashboardTargetResult {
        if (service.serviceType != ServiceType.OPENCLASH) {
            return OpenClashDashboardTargetResult.Unavailable("当前服务不是 OpenClash")
        }
        val resolution = when (val result = routeResolver.resolve(service.toRouteConfig())) {
            is RouteResolutionResult.Success -> result.value
            is RouteResolutionResult.Failure -> {
                return OpenClashDashboardTargetResult.Unavailable(
                    message = "OpenClash 线路暂不可用（${result.code.name}）",
                )
            }
        }
        val sessionResult = runCatching { sessionManager.ensure(service, resolution) }.getOrElse {
            return OpenClashDashboardTargetResult.Unavailable("iStore 登录会话建立失败")
        }
        val session = (sessionResult as? IStoreSessionResult.Authenticated)?.session
            ?: return OpenClashDashboardTargetResult.Unavailable(sessionMessage(sessionResult), resolution)
        val origin = IStoreSessionManager.originOf(resolution.endpoint.url)
            ?: return OpenClashDashboardTargetResult.Unavailable("iStore 地址无效", resolution)
        var cookie = session.cookieHeader.ifBlank { readCookie(origin).orEmpty() }
        val initialStatus = statusClient.fetch(origin, cookie)
        val statusResult = refreshOpenClashStatusOnce(
            initial = initialStatus,
            reauthenticate = {
                val refreshed = runCatching {
                    sessionManager.forceReauthenticate(service, resolution)
                }.getOrNull()
                val refreshedSession = (refreshed as? IStoreSessionResult.Authenticated)?.session
                if (refreshedSession == null) {
                    null
                } else {
                    cookie = refreshedSession.cookieHeader.ifBlank { readCookie(origin).orEmpty() }
                    cookie.takeIf(String::isNotBlank)
                }
            },
            fetch = { refreshedCookie -> statusClient.fetch(origin, refreshedCookie) },
        )
        val status = statusResult.info
            ?: return OpenClashDashboardTargetResult.Unavailable(
                "无法读取 OpenClash 状态，请确认已登录且 OpenClash 正在运行",
                resolution,
            )
        val url = OpenClashDashboardUrlBuilder.build(
            status = status,
            routeKind = resolution.endpoint.kind,
            endpointUrl = resolution.endpoint.url,
        ) ?: return OpenClashDashboardTargetResult.Unavailable(
            if (resolution.endpoint.kind == RouteKind.INTERNAL) {
                "OpenClash 未返回内网控制器端口或访问令牌"
            } else {
                "OpenClash 未返回公网面板域名、端口或访问令牌"
            },
            resolution,
        )
        val dashboardOrigin = ServiceWebCoordinator.webOrigin(url)
            ?: return OpenClashDashboardTargetResult.Unavailable("OpenClash Zashboard 地址无效", resolution)
        val allowedOrigins = buildSet {
            add(dashboardOrigin)
            service.lanUrl?.let(ServiceWebCoordinator::webOrigin)?.let(::add)
            service.wanUrl?.let(ServiceWebCoordinator::webOrigin)?.let(::add)
            add(origin)
        }
        logRepository?.append(
            LogLevel.INFO,
            "OPENCLASH_DASHBOARD_TARGET_READY",
            "已按 OpenClash 状态生成 Zashboard 目标",
            context = mapOf(
                "service_id" to service.id,
                "route" to resolution.endpoint.kind.name,
                "fallback_used" to resolution.fallbackUsed.toString(),
                "connection_policy" to service.connectionPolicy.name,
                "trusted_ssid_count" to service.trustedSsids.size.toString(),
                "ssid_permission" to resolution.ssidPermission.name,
                "ssid_present" to (!resolution.ssid.isNullOrBlank()).toString(),
                "dashboard_host" to dashboardOrigin.substringAfter("://"),
            ),
        )
        return OpenClashDashboardTargetResult.Ready(
            target = WebTarget(
                url = url,
                sessionKey = WebSessionKey(service.id, resolution.endpoint.kind, dashboardOrigin),
                allowedOrigins = allowedOrigins,
            ),
            resolution = resolution,
        )
    }

    private suspend fun readCookie(origin: String): String? = readLuciCookie(cookieManager, origin)

    private fun sessionMessage(result: IStoreSessionResult): String = when (result) {
        is IStoreSessionResult.MissingCredentials -> result.message
        is IStoreSessionResult.Failed -> result.message
        is IStoreSessionResult.Authenticated -> ""
    }
}
