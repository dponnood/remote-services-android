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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xin.dponnood.remoteservice.core.logging.FeedbackPackage
import xin.dponnood.remoteservice.core.logging.FeedbackService
import xin.dponnood.remoteservice.core.logging.LogRepository
import xin.dponnood.remoteservice.core.logging.FeedbackUploadResult
import xin.dponnood.remoteservice.feature.update.UpdateHostState
import xin.dponnood.remoteservice.core.update.UpdateCheckResult

/** Navigation contract owned by the settings feature. */
object SettingsFeature {
    const val route = "settings"
}

private enum class FeedbackUiState {
    IDLE,
    PREVIEW,
    UPLOADING,
    SUCCESS,
    FAILURE,
}

/**
 * App settings surface. The host owns navigation and update actions; this feature owns the
 * explicit feedback consent, sanitized preview, upload progress, and log controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentVersionLabel: String,
    updateState: UpdateHostState,
    logRepository: LogRepository,
    feedbackService: FeedbackService,
    onCheckUpdates: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onBack: () -> Unit,
    onOpenNetworkSettings: () -> Unit = {},
    hasIStoreService: Boolean = false,
    onOpenIStoreLogin: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var recentLogs by remember(logRepository) { mutableStateOf(logRepository.readRecent(80)) }
    var showLogViewer by remember { mutableStateOf(false) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    var feedbackState by remember { mutableStateOf(FeedbackUiState.IDLE) }
    var feedbackPackage by remember { mutableStateOf<FeedbackPackage?>(null) }
    var feedbackDescription by remember { mutableStateOf("") }
    var feedbackResult by remember { mutableStateOf<FeedbackUploadResult?>(null) }
    val scope = rememberCoroutineScope()

    fun refreshLogs() {
        recentLogs = logRepository.readRecent(80)
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
                        Icon(Icons.Outlined.Settings, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text("设置", fontWeight = FontWeight.SemiBold)
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
                    ) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
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
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("远程网络", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Card(
                    onClick = onOpenNetworkSettings,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Outlined.Wifi, contentDescription = null)
                        Column(Modifier.weight(1f)) {
                            Text("线路与 Wi-Fi", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "管理内网/公网地址、可信 Wi-Fi、自动探测和回退策略。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = onOpenNetworkSettings) { Text("打开") }
                    }
                }
            }
            item {
                Text("在线升级", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("当前版本：$currentVersionLabel", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            updateStatusText(updateState),
                            color = if (updateState.ui.error != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        updateState.ui.available?.let { info ->
                            Text("新版本：${info.versionName}（${info.versionCode}）", style = MaterialTheme.typography.titleMedium)
                            if (info.releaseNotes.isNotBlank()) {
                                Text(info.releaseNotes, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (updateState.ui.checking) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onCheckUpdates, enabled = !updateState.ui.checking) {
                                Icon(Icons.Outlined.Refresh, contentDescription = null)
                                Spacer(Modifier.padding(horizontal = 2.dp))
                                Text(if (updateState.ui.checking) "检测中…" else "手动检测更新")
                            }
                            if (updateState.ui.available != null && !updateState.ui.checking) {
                                OutlinedButton(onClick = onDownloadUpdate, enabled = !updateState.ui.downloading) {
                                    Text(if (updateState.ui.downloading) "下载中…" else "立即升级")
                                }
                            }
                        }
                    }
                }
            }
            item {
                Text("iStore 总登录", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (hasIStoreService) {
                                "应用启动时自动建立 iStore 会话；系统概览和应用内网页共用登录状态。"
                            } else {
                                "请先添加一个 iStoreOS 服务，再设置应用级登录。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(
                            onClick = onOpenIStoreLogin,
                            enabled = hasIStoreService,
                        ) {
                            Text("设置 iStore 登录")
                        }
                    }
                }
            }
            item {
                Text("本地日志", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("已保存 ${recentLogs.size} 条记录，占用 ${logRepository.sizeBytes()} 字节。")
                        Text("日志仅保存在本机，达到容量上限后自动保留较新的记录。", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { refreshLogs(); showLogViewer = true }) {
                                Text("查看日志")
                            }
                            OutlinedButton(
                                onClick = { showClearConfirmation = true },
                                enabled = recentLogs.isNotEmpty(),
                            ) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                                Spacer(Modifier.padding(horizontal = 2.dp))
                                Text("清除日志")
                            }
                        }
                    }
                }
            }
            item {
                Text("错误反馈", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("只有你明确确认后，才会整理最近的脱敏日志并上传。不会包含密码、Cookie、令牌、完整服务地址或私网 IP。")
                        Button(onClick = {
                            feedbackResult = null
                            feedbackPackage = feedbackService.prepare(feedbackDescription)
                            feedbackState = FeedbackUiState.PREVIEW
                        }) {
                            Icon(Icons.Outlined.Feedback, contentDescription = null)
                            Spacer(Modifier.padding(horizontal = 2.dp))
                            Text("错误反馈")
                        }
                        feedbackResult?.let { result ->
                            Text(
                                result.message,
                                color = if (result.succeeded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showLogViewer) {
        AlertDialog(
            onDismissRequest = { showLogViewer = false },
            title = { Text("最近日志（已脱敏）") },
            text = {
                Column {
                    if (recentLogs.isEmpty()) Text("暂无日志")
                    else Text(recentLogs.takeLast(30).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showLogViewer = false }) { Text("关闭") } },
        )
    }
    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("清除本地日志？") },
            text = { Text("清除后无法恢复，但不会影响服务配置和登录信息。") },
            confirmButton = {
                TextButton(onClick = {
                    logRepository.clear()
                    refreshLogs()
                    showClearConfirmation = false
                }) { Text("清除") }
            },
            dismissButton = { TextButton(onClick = { showClearConfirmation = false }) { Text("取消") } },
        )
    }
    if (feedbackState == FeedbackUiState.PREVIEW || feedbackState == FeedbackUiState.UPLOADING) {
        val prepared = feedbackPackage
        AlertDialog(
            onDismissRequest = { if (feedbackState != FeedbackUiState.UPLOADING) feedbackState = FeedbackUiState.IDLE },
            title = { Text("确认上传错误反馈") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = feedbackDescription,
                        onValueChange = {
                            feedbackDescription = it
                            if (feedbackState == FeedbackUiState.PREVIEW) {
                                feedbackPackage = feedbackService.prepare(it)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("问题说明（可选）") },
                        minLines = 2,
                        maxLines = 4,
                    )
                    Text("将上传 ${prepared?.logCount ?: 0} 条脱敏日志，约 ${prepared?.sizeBytes ?: 0} 字节。")
                    Text("上传地址为 HTTPS；仅在点击“确认上传”后发送。", style = MaterialTheme.typography.bodySmall)
                    prepared?.let {
                        Text(
                            "预览：\n" + recentLogs.takeLast(5).joinToString("\n").ifBlank { "暂无日志" },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (feedbackState == FeedbackUiState.UPLOADING) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).height(18.dp))
                            Text("正在上传…")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = feedbackState == FeedbackUiState.PREVIEW && prepared != null,
                    onClick = {
                        val packageToUpload = feedbackPackage ?: return@TextButton
                        feedbackState = FeedbackUiState.UPLOADING
                        scope.launch(Dispatchers.IO) {
                            val result = feedbackService.upload(packageToUpload)
                            scope.launch(Dispatchers.Main) {
                                refreshLogs()
                                feedbackResult = result
                                feedbackState = if (result.succeeded) FeedbackUiState.SUCCESS else FeedbackUiState.FAILURE
                            }
                        }
                    },
                ) { Text("确认上传") }
            },
            dismissButton = {
                TextButton(
                    enabled = feedbackState != FeedbackUiState.UPLOADING,
                    onClick = { feedbackState = FeedbackUiState.IDLE },
                ) { Text("取消") }
            },
        )
    }
}

private fun updateStatusText(state: UpdateHostState): String {
    if (state.ui.checking) return "正在检测更新…"
    state.ui.error?.let { return "检测失败：${it.message ?: "网络错误"}" }
    return when (val result = state.lastCheck) {
        is UpdateCheckResult.Available -> "检测完成，发现新版本"
        UpdateCheckResult.NoUpdate -> "已是最新版本，未检测到更新"
        is UpdateCheckResult.Throttled -> "检测已完成，未检测到更新"
        is UpdateCheckResult.Failed -> "检测失败：${result.error.message ?: "网络错误"}"
        null -> if (state.ui.available == null) "尚未检测更新" else "发现新版本"
    }
}
