package xin.dponnood.remoteservice

import android.content.Context
import android.webkit.CookieManager
import java.net.URI
import java.util.Locale
import xin.dponnood.remoteservice.core.logging.LogLevel
import xin.dponnood.remoteservice.core.logging.LogRepository
import xin.dponnood.remoteservice.core.model.ConnectionPolicy
import xin.dponnood.remoteservice.core.model.ServiceConfig
import xin.dponnood.remoteservice.core.model.ServiceType
import xin.dponnood.remoteservice.core.network.RouteKind
import xin.dponnood.remoteservice.core.network.RouteResolutionResult
import xin.dponnood.remoteservice.core.network.RouteResolution
import xin.dponnood.remoteservice.core.network.RouteResolver
import xin.dponnood.remoteservice.core.network.toRouteConfig
import xin.dponnood.remoteservice.feature.services.SystemInfoProvider
import xin.dponnood.remoteservice.feature.services.SystemInfoResult
import xin.dponnood.remoteservice.feature.services.SystemInfoSnapshot

/** Native dashboard adapter backed only by standard, read-only OpenWrt ubus calls. */
internal class IStoreSystemInfoProvider(
    @Suppress("UNUSED_PARAMETER")
    _context: Context?,
    private val routeResolver: RouteResolver,
    private val cookieManager: CookieManager? = null,
    private val sessionManager: IStoreSystemInfoSessionAccess? = null,
    private val logRepository: LogRepository? = null,
    private val reader: UbusSystemInfoReader = UbusSystemInfoReader(),
) : SystemInfoProvider {
    override suspend fun load(service: ServiceConfig?): SystemInfoResult {
        if (service == null) return SystemInfoResult.Unavailable("请先添加 iStoreOS 或 LuCI 服务")
        if (service.serviceType != ServiceType.ISTORE && service.serviceType != ServiceType.LUCI) {
            return SystemInfoResult.Unavailable("当前服务不是 iStoreOS/LuCI 类型")
        }

        val resolved = when (val result = routeResolver.resolve(service.toRouteConfig())) {
            is RouteResolutionResult.Success -> result.value
            is RouteResolutionResult.Failure -> {
                logRepository?.append(
                    LogLevel.WARN,
                    "ISTORE_SYSTEM_ROUTE_UNAVAILABLE",
                    "iStore 系统信息线路不可达",
                    context = mapOf(
                        "service_id" to service.id,
                        "service_type" to service.serviceType.name,
                        "error_code" to result.code.name,
                    ),
                )
                return SystemInfoResult.Unavailable("线路不可达：${result.code.name}")
            }
        }
        val initialResult = readOnRoute(service, resolved)
        if (!shouldTryPublicAfterInternalFailure(service, resolved) ||
            initialResult !is SystemInfoResult.Unavailable
        ) {
            return initialResult
        }

        logRepository?.append(
            LogLevel.WARN,
            "ISTORE_SYSTEM_INFO_ROUTE_FALLBACK_START",
            "内网 ubus 系统信息读取失败，尝试公网线路一次",
            context = mapOf(
                "service_id" to service.id,
                "from_route" to RouteKind.INTERNAL.name,
                "to_route" to RouteKind.PUBLIC.name,
            ),
        )
        val publicResolution = when (
            val result = routeResolver.resolve(
                service.toRouteConfig().copy(connectionPolicy = ConnectionPolicy.PUBLIC_ONLY),
            )
        ) {
            is RouteResolutionResult.Success -> result.value
            is RouteResolutionResult.Failure -> {
                logRepository?.append(
                    LogLevel.WARN,
                    "ISTORE_SYSTEM_INFO_ROUTE_FALLBACK_UNAVAILABLE",
                    "iStore 公网线路不可达，保留内网读取错误",
                    context = mapOf(
                        "service_id" to service.id,
                        "route" to RouteKind.PUBLIC.name,
                        "error_code" to result.code.name,
                    ),
                )
                return initialResult
            }
        }
        if (publicResolution.endpoint.kind != RouteKind.PUBLIC) return initialResult

        val publicResult = readOnRoute(service, publicResolution)
        val succeeded = publicResult is SystemInfoResult.Success
        logRepository?.append(
            if (succeeded) LogLevel.INFO else LogLevel.WARN,
            if (succeeded) {
                "ISTORE_SYSTEM_INFO_ROUTE_FALLBACK_SUCCESS"
            } else {
                "ISTORE_SYSTEM_INFO_ROUTE_FALLBACK_FAILED"
            },
            if (succeeded) {
                "iStore 系统信息已通过公网线路读取"
            } else {
                "iStore 公网线路也未能读取系统信息"
            },
            context = mapOf(
                "service_id" to service.id,
                "from_route" to RouteKind.INTERNAL.name,
                "to_route" to RouteKind.PUBLIC.name,
            ),
        )
        return publicResult
    }

    private fun shouldTryPublicAfterInternalFailure(
        service: ServiceConfig,
        resolution: RouteResolution,
    ): Boolean =
        service.connectionPolicy == ConnectionPolicy.AUTO &&
            resolution.endpoint.kind == RouteKind.INTERNAL &&
            !service.wanUrl.isNullOrBlank()

    private suspend fun readOnRoute(
        service: ServiceConfig,
        resolved: RouteResolution,
    ): SystemInfoResult {
        val origin = originOf(resolved.endpoint.url)
            ?: return SystemInfoResult.Unavailable("服务地址无效")

        var sessionResult = sessionManager?.ensure(service, resolved)
        val cookie = (sessionResult as? IStoreSessionResult.Authenticated)?.session?.cookieHeader
            ?: readCookie(origin)
        val sid = (sessionResult as? IStoreSessionResult.Authenticated)?.session?.sessionId
            ?: extractLuCiSessionId(cookie)
        if (sid == null) {
            return SystemInfoResult.Unavailable(
                when (sessionResult) {
                    is IStoreSessionResult.MissingCredentials -> sessionResult.message
                    is IStoreSessionResult.Failed -> sessionResult.message
                    else -> "请先在网页端登录后再读取系统信息"
                },
            )
        }

        val readResult = reader.read(origin, UbusSessionAuth(sid, cookie)) {
            val manager = sessionManager ?: return@read null
            logRepository?.append(
                LogLevel.WARN,
                "ISTORE_SESSION_REFRESH",
                "LuCI ubus 会话失效或被拒绝，尝试重新登录一次",
                context = mapOf("service_id" to service.id, "route" to resolved.endpoint.kind.name),
            )
            sessionResult = manager.forceReauthenticate(service, resolved)
            val refreshed = (sessionResult as? IStoreSessionResult.Authenticated)?.session
                ?: return@read null
            UbusSessionAuth(refreshed.sessionId, refreshed.cookieHeader)
        }

        when (readResult) {
            is UbusReadResult.Success -> {
                readResult.issues.forEach { issue ->
                    val permissionDenied = issue.code == 6
                    logRepository?.append(
                        LogLevel.WARN,
                        if (permissionDenied) "UBUS_ACL_DENIED" else "UBUS_BUSINESS_ERROR",
                        if (permissionDenied) "标准 ubus 读取被 ACL 拒绝" else "标准 ubus 返回业务错误",
                        context = buildMap {
                            put("service_id", service.id)
                            put("method", "system.${issue.method}")
                            put("ubus_code", issue.code.toString())
                            issue.rpcErrorCode?.let { put("rpc_error_code", it.toString()) }
                            put("session_refreshed", readResult.sessionRefreshed.toString())
                        },
                    )
                }
                if (!hasAnyMetric(readResult.snapshot)) {
                    return unavailable(
                        service,
                        "标准 ubus 已响应，但没有返回仪表盘支持的系统字段",
                        "UBUS_NO_SUPPORTED_FIELDS",
                        readResult.issues,
                    )
                }
                logRepository?.append(
                    LogLevel.INFO,
                    "ISTORE_SYSTEM_INFO_READ",
                    "标准 ubus 系统信息读取完成",
                    context = mapOf(
                        "service_id" to service.id,
                        "route" to resolved.endpoint.kind.name,
                        "session_refreshed" to readResult.sessionRefreshed.toString(),
                        "ubus_issue_count" to readResult.issues.size.toString(),
                        "ubus_issues" to readResult.issues.joinToString(",") { issue ->
                            "${issue.method}:${issue.code}" +
                                (issue.rpcErrorCode?.let { "/rpc:$it" } ?: "")
                        },
                    ),
                )
                return SystemInfoResult.Success(readResult.snapshot)
            }
            is UbusReadResult.Failure -> {
                val (event, message, userMessage) = when (readResult.kind) {
                    UbusFailureKind.NO_SESSION -> Triple(
                        "UBUS_NO_SESSION",
                        "标准 ubus 未建立 LuCI 会话",
                        "请先在网页端登录后再读取系统信息",
                    )
                    UbusFailureKind.SESSION_EXPIRED -> Triple(
                        "UBUS_SESSION_EXPIRED",
                        "LuCI ubus 会话失效，单次重新登录后仍不可用",
                        sessionFailureMessage(sessionResult),
                    )
                    UbusFailureKind.ACL_DENIED -> Triple(
                        "UBUS_ACL_DENIED",
                        "标准 ubus 读取被 HTTP/ACL 权限拒绝",
                        "当前 LuCI 会话没有读取 system.board/system.info 的权限",
                    )
                    UbusFailureKind.BUSINESS_ERROR -> Triple(
                        "UBUS_BUSINESS_ERROR",
                        "标准 ubus 返回非零业务结果",
                        "ubus 业务错误（结果码 ${readResult.ubusCode ?: "未知"}）",
                    )
                    UbusFailureKind.ENDPOINT_UNAVAILABLE -> Triple(
                        "UBUS_ENDPOINT_UNAVAILABLE",
                        "标准 ubus 端点不可用",
                        "标准 ubus 端点不可用",
                    )
                    UbusFailureKind.INVALID_RESPONSE -> Triple(
                        "UBUS_INVALID_RESPONSE",
                        "标准 ubus 返回格式无效",
                        "标准 ubus 响应格式无效",
                    )
                }
                logRepository?.append(
                    LogLevel.WARN,
                    event,
                    message,
                    context = buildMap {
                        put("service_id", service.id)
                        put("route", resolved.endpoint.kind.name)
                        readResult.httpStatus?.let { put("http_status", it.toString()) }
                        readResult.ubusCode?.let { put("ubus_code", it.toString()) }
                        readResult.rpcErrorCode?.let { put("rpc_error_code", it.toString()) }
                    },
                )
                return SystemInfoResult.Unavailable(userMessage)
            }
        }
    }

    private fun unavailable(
        service: ServiceConfig,
        message: String,
        event: String,
        issues: List<UbusCallIssue>,
    ): SystemInfoResult.Unavailable {
        logRepository?.append(
            LogLevel.WARN,
            event,
            message,
            context = mapOf(
                "service_id" to service.id,
                "ubus_issue_count" to issues.size.toString(),
                "ubus_issues" to issues.joinToString(",") { issue ->
                    "${issue.method}:${issue.code}" + (issue.rpcErrorCode?.let { "/rpc:$it" } ?: "")
                },
            ),
        )
        return SystemInfoResult.Unavailable(message)
    }

    private fun sessionFailureMessage(result: IStoreSessionResult?): String = when (result) {
        is IStoreSessionResult.MissingCredentials -> result.message
        is IStoreSessionResult.Failed -> "LuCI 会话已失效，重新登录失败：${result.message}"
        else -> "LuCI 会话已失效；自动重新登录后仍无法读取，请重新登录"
    }

    private suspend fun readCookie(origin: String): String? {
        sessionManager?.readCookie(origin)?.let { return it }
        cookieManager?.let { manager -> readLuciCookie(manager, origin)?.let { return it } }
        if (sessionManager == null && cookieManager == null) {
            return runCatching {
                readLuciCookie(CookieManager.getInstance(), origin)
            }.getOrNull()
        }
        return null
    }

    companion object {
        private val SESSION_ID_PATTERN = Regex("^[0-9a-fA-F]{32}$")

        internal fun originOf(url: String): String? = runCatching {
            val parsed = URI(url.trim())
            val scheme = parsed.scheme?.lowercase(Locale.US)
            val authority = parsed.rawAuthority?.takeIf { it.isNotBlank() }
            if (scheme !in setOf("http", "https") || authority == null || parsed.rawUserInfo != null) {
                null
            } else {
                "$scheme://$authority"
            }
        }.getOrNull()

        internal fun extractLuCiSessionId(cookie: String?): String? = cookie
            ?.split(';')
            ?.asSequence()
            ?.mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val name = part.substring(0, separator).trim().lowercase(Locale.US)
                val value = part.substring(separator + 1).trim().trim('"')
                if (name !in setOf("sysauth", "sysauth_http", "sysauth_https")) return@mapNotNull null
                value.takeIf { it.matches(SESSION_ID_PATTERN) }
            }
            ?.firstOrNull()

        private fun readLuciCookie(cookieManager: CookieManager, origin: String): String? =
            runCatching { cookieManager.getCookie(origin) }.getOrNull()

        private fun hasAnyMetric(snapshot: SystemInfoSnapshot): Boolean =
            snapshot.hostname != null || snapshot.model != null || snapshot.osName != null ||
                snapshot.firmware != null || snapshot.kernel != null || snapshot.memoryTotalBytes != null ||
                snapshot.uptimeMillis != null
    }
}
