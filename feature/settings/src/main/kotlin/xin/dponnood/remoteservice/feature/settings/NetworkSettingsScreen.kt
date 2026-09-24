package xin.dponnood.remoteservice.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xin.dponnood.remoteservice.core.database.ServiceConfigStore
import xin.dponnood.remoteservice.core.model.ConnectionPolicy
import xin.dponnood.remoteservice.core.model.ServiceConfig
import xin.dponnood.remoteservice.core.model.hasEmbeddedUrlCredentials
import xin.dponnood.remoteservice.core.model.urlContainsUserInfo
import xin.dponnood.remoteservice.core.model.stripUrlUserInfo
import xin.dponnood.remoteservice.core.network.RouteKind
import xin.dponnood.remoteservice.core.network.RouteResolution
import xin.dponnood.remoteservice.core.network.RouteResolutionResult
import xin.dponnood.remoteservice.core.network.RouteResolver
import xin.dponnood.remoteservice.core.network.ServiceRouteConfig
import xin.dponnood.remoteservice.core.network.WifiNetworkNameProvider
import xin.dponnood.remoteservice.core.network.WifiNetworkNameResult
import xin.dponnood.remoteservice.core.network.prioritizeWifiNetworkNames
import xin.dponnood.remoteservice.core.network.toRouteConfig
import java.net.URI

private sealed interface ProbeUiState {
    data object Idle : ProbeUiState
    data object Checking : ProbeUiState
    data class Success(val resolution: RouteResolution) : ProbeUiState
    data class Failure(val result: RouteResolutionResult.Failure) : ProbeUiState
}

private data class ProbeKey(val serviceId: String, val routeConfig: ServiceRouteConfig)

internal data class NetworkDraft(
    val lanUrl: String,
    val wanUrl: String,
    val trustedSsids: Set<String>,
    val connectionPolicy: ConnectionPolicy,
) {
    companion object {
        fun from(service: ServiceConfig): NetworkDraft = NetworkDraft(
            lanUrl = stripUrlUserInfo(service.lanUrl).orEmpty(),
            wanUrl = stripUrlUserInfo(service.wanUrl).orEmpty(),
            trustedSsids = service.trustedSsids,
            connectionPolicy = service.connectionPolicy,
        )
    }
}

/**
 * Dedicated network settings surface. Service identity, credentials and
 * ordering remain owned by the existing service editor; this screen only
 * edits route-related fields and persists them through the same store.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkSettingsScreen(
    store: ServiceConfigStore,
    wifiNetworkNameProvider: WifiNetworkNameProvider? = null,
    routeResolver: RouteResolver? = null,
    onRequestWifiPermissions: () -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val services by store.services.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val probeStates = remember { mutableStateMapOf<ProbeKey, ProbeUiState>() }
    var editorService by remember { mutableStateOf<ServiceConfig?>(null) }
    var editorDraft by remember { mutableStateOf<NetworkDraft?>(null) }
    var wifiNames by remember { mutableStateOf(WifiNetworkNameResult()) }
    var wifiNamesLoading by remember { mutableStateOf(false) }
    var wifiMenuExpanded by remember { mutableStateOf(false) }
    var editorSaving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun refreshWifiNames() {
        val provider = wifiNetworkNameProvider ?: return
        scope.launch {
            wifiNamesLoading = true
            wifiNames = runCatching { provider.load() }
                .getOrElse { WifiNetworkNameResult(statusMessage = "读取 Wi-Fi 名称失败，请手动输入。") }
            wifiNamesLoading = false
        }
    }

    fun openEditor(service: ServiceConfig) {
        editorService = service
        editorDraft = NetworkDraft.from(service)
        wifiMenuExpanded = false
        refreshWifiNames()
    }

    fun probe(service: ServiceConfig) {
        val resolver = routeResolver ?: return
        val probeKey = ProbeKey(service.id, service.toRouteConfig())
        probeStates[probeKey] = ProbeUiState.Checking
        scope.launch {
            val result = runCatching { resolver.resolve(probeKey.routeConfig) }
            probeStates[probeKey] = result.fold(
                onSuccess = { resolved ->
                    when (resolved) {
                        is RouteResolutionResult.Success -> ProbeUiState.Success(resolved.value)
                        is RouteResolutionResult.Failure -> ProbeUiState.Failure(resolved)
                    }
                },
                onFailure = {
                    ProbeUiState.Failure(
                        RouteResolutionResult.Failure(
                            code = xin.dponnood.remoteservice.core.network.NetworkErrorCode.IO_FAILURE,
                            attempted = emptyList(),
                            ssid = null,
                            ssidPermission = xin.dponnood.remoteservice.core.network.SsidPermissionState.UNAVAILABLE,
                        ),
                    )
                },
            )
        }
    }

    BackHandler(onBack = onBack)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.SettingsEthernet, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("远程网络", fontWeight = FontWeight.SemiBold)
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                            .border(
                                BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                CircleShape,
                            ),
                    ) {
                        Text("‹", style = MaterialTheme.typography.headlineMedium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("线路选择", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "自动策略只在可信 Wi-Fi 上探测内网；内网不可达时才回退公网。未知 Wi-Fi 不会探测内网地址。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (services.isEmpty()) {
                item {
                    Text("还没有服务，请先在服务列表中添加服务。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                items(services, key = { it.id }) { service ->
                    NetworkServiceCard(
                        service = service,
                        probeState = probeStates[ProbeKey(service.id, service.toRouteConfig())] ?: ProbeUiState.Idle,
                        canProbe = routeResolver != null,
                        onEdit = { openEditor(service) },
                        onProbe = { probe(service) },
                    )
                }
            }
            if (notice != null) {
                item {
                    Text(
                        notice.orEmpty(),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    val service = editorService
    val draft = editorDraft
    if (service != null && draft != null) {
        NetworkEditorDialog(
            service = service,
            draft = draft,
            wifiNames = wifiNames,
            wifiNamesLoading = wifiNamesLoading,
            wifiMenuExpanded = wifiMenuExpanded,
            saving = editorSaving,
            onDraftChange = { editorDraft = it },
            onWifiMenuChange = { wifiMenuExpanded = it },
            onRefreshWifiNames = ::refreshWifiNames,
            onDismiss = {
                if (!editorSaving) {
                    editorService = null
                    editorDraft = null
                }
            },
            onSave = {
                val validation = validateNetworkDraft(draft)
                if (validation != null) {
                    notice = validation
                } else {
                    editorSaving = true
                    scope.launch {
                        runCatching {
                            store.upsert(
                                service.copy(
                                    lanUrl = draft.lanUrl.trim().takeIf(String::isNotBlank),
                                    wanUrl = draft.wanUrl.trim().takeIf(String::isNotBlank),
                                    trustedSsids = draft.trustedSsids,
                                    connectionPolicy = draft.connectionPolicy,
                                ),
                            )
                        }.onSuccess {
                            probeStates.keys.removeAll { it.serviceId == service.id }
                            notice = "已保存 ${service.displayName} 的远程网络设置。"
                            editorService = null
                            editorDraft = null
                        }.onFailure {
                            notice = "保存失败：${it.message ?: "请稍后重试"}"
                        }
                        editorSaving = false
                    }
                }
            },
        )
    }
}

@Composable
private fun NetworkServiceCard(
    service: ServiceConfig,
    probeState: ProbeUiState,
    canProbe: Boolean,
    onEdit: () -> Unit,
    onProbe: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Wifi, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(service.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(policyLabel(service.connectionPolicy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "编辑线路") }
            }
            EndpointSummary(label = "内网", value = endpointSummary(service.lanUrl))
            EndpointSummary(label = "公网", value = endpointSummary(service.wanUrl))
            if (service.hasEmbeddedUrlCredentials()) {
                Text(
                    "旧地址中含有登录信息；请编辑移除，登录信息应单独保存在服务凭据中。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            EndpointSummary(
                label = "可信 Wi-Fi",
                value = service.trustedSsids.sorted().joinToString("、").ifBlank { "未设置（仅公网）" },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onProbe, enabled = canProbe && probeState !is ProbeUiState.Checking) {
                    if (probeState is ProbeUiState.Checking) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    } else {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(if (probeState is ProbeUiState.Checking) "探测中…" else "探测线路")
                }
                ProbeSummary(probeState)
            }
        }
    }
}

@Composable
private fun EndpointSummary(label: String, value: String?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.width(72.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value?.takeIf(String::isNotBlank) ?: "未设置", style = MaterialTheme.typography.bodySmall)
    }
}

private fun endpointSummary(value: String?): String? =
    if (urlContainsUserInfo(value)) "地址包含登录信息，请编辑移除" else value

@Composable
private fun ProbeSummary(state: ProbeUiState) {
    when (state) {
        ProbeUiState.Idle -> Text("尚未探测", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ProbeUiState.Checking -> Unit
        is ProbeUiState.Success -> {
            val value = state.resolution
            Text(
                buildString {
                    append("已选")
                    append(if (value.endpoint.kind == RouteKind.INTERNAL) "内网" else "公网")
                    if (value.fallbackUsed) append("（内网失败后回退）")
                    append(" · Wi-Fi ${value.ssid ?: "未知"} · 权限 ${value.ssidPermission.name}")
                    append(" · ${value.probe.elapsedMs} ms")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        is ProbeUiState.Failure -> Text(
            "不可用：${state.result.code.name}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkEditorDialog(
    service: ServiceConfig,
    draft: NetworkDraft,
    wifiNames: WifiNetworkNameResult,
    wifiNamesLoading: Boolean,
    wifiMenuExpanded: Boolean,
    saving: Boolean,
    onDraftChange: (NetworkDraft) -> Unit,
    onWifiMenuChange: (Boolean) -> Unit,
    onRefreshWifiNames: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val suggestions = prioritizeWifiNetworkNames(
        wifiNames.names + draft.trustedSsids,
        wifiNames.currentSsid,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("线路设置 · ${service.displayName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (service.hasEmbeddedUrlCredentials()) {
                    Text(
                        "旧地址中的登录信息已从输入框移除；保存后会清理旧配置。账号密码请单独保存在服务凭据中。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = draft.lanUrl,
                    onValueChange = { onDraftChange(draft.copy(lanUrl = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("内网地址") },
                    placeholder = { Text("http://192.168.1.1") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.wanUrl,
                    onValueChange = { onDraftChange(draft.copy(wanUrl = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("公网地址") },
                    placeholder = { Text("https://example.com") },
                    singleLine = true,
                )
                Text("连接策略", style = MaterialTheme.typography.labelLarge)
                var policyMenuExpanded by remember { mutableStateOf(false) }
                androidx.compose.foundation.layout.Box {
                    OutlinedButton(onClick = { policyMenuExpanded = true }) {
                        Text(policyLabel(draft.connectionPolicy))
                    }
                    DropdownMenu(
                        expanded = policyMenuExpanded,
                        onDismissRequest = { policyMenuExpanded = false },
                    ) {
                        ConnectionPolicy.entries.forEach { policy ->
                            DropdownMenuItem(
                                text = { Text(policyLabel(policy)) },
                                onClick = {
                                    policyMenuExpanded = false
                                    onDraftChange(draft.copy(connectionPolicy = policy))
                                },
                            )
                        }
                    }
                }
                androidx.compose.foundation.layout.Box {
                    OutlinedTextField(
                        value = draft.trustedSsids.sorted().joinToString("\n"),
                        onValueChange = { onDraftChange(draft.copy(trustedSsids = parseSsids(it))) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("可信 Wi-Fi 名称") },
                        placeholder = { Text("每行一个，也可用逗号分隔") },
                        minLines = 2,
                        maxLines = 4,
                        trailingIcon = {
                            TextButton(
                                onClick = {
                                    onWifiMenuChange(true)
                                    onRefreshWifiNames()
                                },
                            ) { Text("选择") }
                        },
                    )
                    DropdownMenu(
                        expanded = wifiMenuExpanded,
                        onDismissRequest = { onWifiMenuChange(false) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        when {
                            wifiNamesLoading -> DropdownMenuItem(text = { Text("正在读取 Wi-Fi 名称…") }, onClick = {})
                            suggestions.isEmpty() -> DropdownMenuItem(
                                text = { Text(wifiNames.statusMessage ?: "没有可用建议，请手动输入") },
                                onClick = { onWifiMenuChange(false) },
                            )
                            else -> suggestions.forEach { name ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        onDraftChange(draft.copy(trustedSsids = (draft.trustedSsids + name).map(String::trim).filter(String::isNotBlank).toSet()))
                                        onWifiMenuChange(false)
                                    },
                                )
                            }
                        }
                        DropdownMenuItem(text = { Text("刷新列表") }, onClick = onRefreshWifiNames)
                    }
                }
                Text(
                    "列表仅显示系统允许读取的已配置网络、当前连接 Wi-Fi，以及本服务已保存名称。Android 10+ 普通应用无法枚举系统完整的已保存 Wi-Fi；未显示的网络可手动输入。应用只保存名称，不读取密码。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                wifiNames.statusMessage?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = !saving) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } },
    )
}

private fun policyLabel(policy: ConnectionPolicy): String = when (policy) {
    ConnectionPolicy.AUTO -> "自动：可信 Wi-Fi 内网优先，失败回退公网"
    ConnectionPolicy.PUBLIC_ONLY -> "仅公网：始终使用公网地址"
    ConnectionPolicy.INTERNAL_ONLY -> "仅内网：只在可信 Wi-Fi 探测内网"
}

private fun parseSsids(value: String): Set<String> = value
    .split(',', ';', '\n')
    .map(String::trim)
    .filter(String::isNotBlank)
    .toSet()

internal fun validateNetworkDraft(draft: NetworkDraft): String? {
    if (draft.lanUrl.isBlank() && draft.wanUrl.isBlank()) return "至少保留一个内网或公网地址。"
    for ((value, label) in listOf(draft.lanUrl to "内网地址", draft.wanUrl to "公网地址")) {
        if (value.isBlank()) continue
        val uri = runCatching { URI(value.trim()) }.getOrNull()
        if (uri == null || uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            return "${label}必须是有效的 HTTP/HTTPS 地址。"
        }
        if (urlContainsUserInfo(value)) {
            return "${label}不能包含用户名或密码，请将登录信息保存在服务凭据中。"
        }
    }
    if (draft.connectionPolicy == ConnectionPolicy.PUBLIC_ONLY && draft.wanUrl.isBlank()) {
        return "仅公网策略需要填写公网地址。"
    }
    if (draft.connectionPolicy == ConnectionPolicy.INTERNAL_ONLY && draft.lanUrl.isBlank()) {
        return "仅内网策略需要填写内网地址。"
    }
    return null
}
