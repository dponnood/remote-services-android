package xin.dponnood.remoteservice

import android.Manifest
import android.app.DownloadManager
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.WebView
import android.webkit.ValueCallback
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.key
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import xin.dponnood.remoteservice.core.database.DataStoreServiceConfigStore
import xin.dponnood.remoteservice.core.database.DataStoreDashboardCardPreferencesStore
import xin.dponnood.remoteservice.core.database.DashboardCardPreferencesStore
import xin.dponnood.remoteservice.core.database.ServiceConfigStore
import xin.dponnood.remoteservice.core.database.createServiceDataStore
import xin.dponnood.remoteservice.core.designsystem.RemoteServicesTheme
import xin.dponnood.remoteservice.core.logging.AndroidInstallationIdProvider
import xin.dponnood.remoteservice.core.logging.AndroidLogRepository
import xin.dponnood.remoteservice.core.logging.FeedbackService
import xin.dponnood.remoteservice.core.logging.LogLevel
import xin.dponnood.remoteservice.core.logging.LogRepository
import xin.dponnood.remoteservice.core.model.ServiceConfig
import xin.dponnood.remoteservice.core.model.stripUrlUserInfo
import xin.dponnood.remoteservice.core.model.ConnectionPolicy
import xin.dponnood.remoteservice.core.model.ServiceType
import xin.dponnood.remoteservice.core.network.AndroidSsidProvider
import xin.dponnood.remoteservice.core.network.AndroidWifiNetworkNameProvider
import xin.dponnood.remoteservice.core.network.HttpsHealthProbe
import xin.dponnood.remoteservice.core.network.RouteKind
import xin.dponnood.remoteservice.core.network.RouteResolver
import xin.dponnood.remoteservice.core.network.WifiNetworkNameProvider
import xin.dponnood.remoteservice.core.security.CredentialStore
import xin.dponnood.remoteservice.core.security.KeystoreCredentialStore
import xin.dponnood.remoteservice.core.security.ServiceCredentials
import xin.dponnood.remoteservice.core.update.InstallResultStore
import xin.dponnood.remoteservice.core.update.InstallLaunchResult
import xin.dponnood.remoteservice.feature.update.UpdateHostCoordinator
import xin.dponnood.remoteservice.feature.update.UpdateHostState
import xin.dponnood.remoteservice.feature.update.UpdatePromptAction
import xin.dponnood.remoteservice.core.update.UpdateCheckResult
import xin.dponnood.remoteservice.adapter.luci.LuciDomRequest
import xin.dponnood.remoteservice.adapter.luci.LuciAuthAdapter
import xin.dponnood.remoteservice.adapter.luci.LuciWebViewHook
import xin.dponnood.remoteservice.feature.services.ServicesScreen
import xin.dponnood.remoteservice.feature.services.OpenClashNodeSwitcher
import xin.dponnood.remoteservice.feature.services.OpenClashNodeLatencyTester
import xin.dponnood.remoteservice.feature.services.SystemInfoProvider
import xin.dponnood.remoteservice.feature.settings.SettingsScreen
import xin.dponnood.remoteservice.feature.settings.NetworkSettingsScreen
import xin.dponnood.remoteservice.feature.web.SecureWebViewController
import xin.dponnood.remoteservice.feature.web.ServiceWebCoordinator
import xin.dponnood.remoteservice.feature.web.ServiceWebOpenResult
import xin.dponnood.remoteservice.feature.web.WebDownloadRequest
import xin.dponnood.remoteservice.feature.web.WebSessionKey
import xin.dponnood.remoteservice.feature.web.WebTarget
import xin.dponnood.remoteservice.feature.web.WebViewHostContract
import xin.dponnood.remoteservice.feature.web.WebViewHostHandlers
import xin.dponnood.remoteservice.feature.web.WebViewEventListener
import xin.dponnood.remoteservice.feature.web.RootWebViewEventTarget
import xin.dponnood.remoteservice.feature.web.PredictiveWebBackRegistration
import xin.dponnood.remoteservice.feature.web.WebViewLayoutCompatibility
import xin.dponnood.remoteservice.feature.web.InAppWindowHandler

private sealed interface AppScreen {
    data object Services : AppScreen
    data object Settings : AppScreen
    data object NetworkSettings : AppScreen
    data class Resolving(val serviceName: String) : AppScreen
    data class Web(val target: WebTarget, val service: ServiceConfig) : AppScreen
    data class Error(val service: ServiceConfig, val message: String) : AppScreen
}

private data class WebPageError(
    val url: String,
    val message: String,
)

/** A visible WebView tab owned by one service page. */
private data class WebWindowInstance(
    val id: Int,
    val webView: WebView,
)

class MainActivity : ComponentActivity() {
    private val appScope: CoroutineScope = MainScope()
    private val screenState = mutableStateOf<AppScreen>(AppScreen.Services)
    private val credentialsService = mutableStateOf<ServiceConfig?>(null)
    private val credentialsIsGlobal = mutableStateOf(false)
    private val iStoreServices = mutableStateOf<List<ServiceConfig>>(emptyList())
    private val iStoreCredentialServiceIds = AtomicReference<Set<String>>(emptySet())
    private val dismissedUpdateVersion = mutableStateOf<Long?>(null)
    private var wifiNamesPermissionRefreshToken by mutableIntStateOf(0)
    private var pendingPermissionService: ServiceConfig? = null
    private var wifiPermissionPrompted = false
    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null
    private var lastShownInstallSessionId: Int = -1
    private var pendingRestoredOpenClashService: ServiceConfig? = null

    private val serviceStore: ServiceConfigStore by lazy {
        DataStoreServiceConfigStore(createServiceDataStore(applicationContext))
    }
    private val dashboardCardPreferencesStore: DashboardCardPreferencesStore by lazy {
        DataStoreDashboardCardPreferencesStore(createServiceDataStore(applicationContext))
    }
    private val credentialStore: CredentialStore by lazy { KeystoreCredentialStore(applicationContext) }
    private val logRepository: LogRepository by lazy { AndroidLogRepository(applicationContext) }
    private val iStoreSessionManager: IStoreSessionManager by lazy {
        IStoreSessionManager(
            credentialStore = credentialStore,
            globalCredentialServiceIds = { iStoreCredentialServiceIds.get() },
            logRepository = logRepository,
        )
    }
    private val feedbackService: FeedbackService by lazy {
        FeedbackService(logRepository, AndroidInstallationIdProvider(applicationContext))
    }
    private val updateHost: UpdateHostCoordinator by lazy {
        UpdateHostCoordinator(applicationContext, scope = appScope)
    }
    private val installResultStore: InstallResultStore by lazy { InstallResultStore(applicationContext) }
    private val routeResolver: RouteResolver by lazy {
        RouteResolver(AndroidSsidProvider(this), HttpsHealthProbe())
    }
    private val openClashControllerStatusClient: OpenClashControllerStatusClient by lazy {
        OpenClashControllerStatusClient(logRepository = logRepository)
    }
    private val iStoreSystemInfoProvider: IStoreSystemInfoProvider by lazy {
        IStoreSystemInfoProvider(
            _context = applicationContext,
            routeResolver = routeResolver,
            sessionManager = iStoreSessionManager,
            logRepository = logRepository,
        )
    }
    private val openClashDashboardResolver: OpenClashDashboardResolver by lazy {
        OpenClashDashboardResolver(
            routeResolver = routeResolver,
            sessionManager = iStoreSessionManager,
            statusClient = openClashControllerStatusClient,
            logRepository = logRepository,
        )
    }
    private val openClashSystemInfoProvider: OpenClashSystemInfoProvider by lazy {
        OpenClashSystemInfoProvider(
            routeResolver = routeResolver,
            sessionManager = iStoreSessionManager,
            controllerStatusClient = openClashControllerStatusClient,
            logRepository = logRepository,
        )
    }
    private val systemInfoProvider: SystemInfoProvider by lazy {
        RoutedSystemInfoProvider(iStoreSystemInfoProvider, openClashSystemInfoProvider)
    }
    private val webCoordinator: ServiceWebCoordinator by lazy {
        ServiceWebCoordinator(routeResolver)
    }
    private val wifiNetworkNameProvider: WifiNetworkNameProvider by lazy {
        AndroidWifiNetworkNameProvider(applicationContext)
    }

    private val wifiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val service = pendingPermissionService
        pendingPermissionService = null
        if (service != null) {
            val allGranted = requiredWifiPermissions().all { result[it] == true }
            if (!allGranted) {
                // Let a later tap request again after the user changes the
                // permission choice in Android Settings. RouteResolver still
                // safely falls back to the public endpoint while denied.
                wifiPermissionPrompted = false
                Toast.makeText(this, "未授予 Wi-Fi 读取权限，将使用公网地址", Toast.LENGTH_SHORT).show()
            }
            resolveAndOpen(service)
        }
    }

    private val wifiNamePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        wifiNamesPermissionRefreshToken++
        val granted = requiredWifiPermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (!granted) {
            Toast.makeText(this, "未授予 Wi-Fi 读取权限，仍可手动输入名称", Toast.LENGTH_SHORT).show()
        }
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        pendingFileChooser?.onReceiveValue(uri?.let { selected -> arrayOf(selected) })
        pendingFileChooser = null
    }

    private val unknownSourcesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (packageManager.canRequestPackageInstalls()) {
            startUpdateDownload()
        } else {
            Toast.makeText(this, "未允许安装未知应用，更新已取消", Toast.LENGTH_SHORT).show()
        }
    }

    private val webHostHandlers by lazy {
        WebViewHostHandlers(
            object : WebViewHostContract {
                override fun chooseFile(
                    callback: ValueCallback<Array<Uri>>?,
                    acceptTypes: Array<String>,
                    capture: Boolean,
                ): Boolean {
                    pendingFileChooser?.onReceiveValue(null)
                    pendingFileChooser = callback
                    val mimeTypes = acceptTypes.map(String::trim)
                        .filter(String::isNotBlank)
                        .ifEmpty { listOf("*/*") }
                    fileChooserLauncher.launch(mimeTypes.toTypedArray())
                    return true
                }

                override fun enqueueDownload(request: WebDownloadRequest) {
                    enqueueDownloadRequest(request)
                }

                override fun openExternal(uri: Uri) {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }.onFailure {
                        Toast.makeText(this@MainActivity, "没有可用的外部浏览器", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreScreen(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val selectedServiceId = rememberSaveable { mutableStateOf<String?>(null) }
            RemoteServicesTheme(
                ambientMotionEnabled = screenState.value !is AppScreen.Web &&
                    screenState.value !is AppScreen.Resolving,
            ) {
                RemoteServicesApp(
                    screen = screenState.value,
                    selectedServiceId = selectedServiceId.value,
                    onSelectedServiceIdChanged = { selectedServiceId.value = it },
                    store = serviceStore,
                    dashboardCardPreferencesStore = dashboardCardPreferencesStore,
                    credentialStore = credentialStore,
                    currentVersionLabel = currentVersionLabel(),
                    credentialsService = credentialsService.value,
                    credentialsIsGlobal = credentialsIsGlobal.value,
                    updateState = updateHost.state.collectAsState().value,
                    showOptionalUpdate = updateHost.shouldShowOptionalPrompt(),
                    dismissedUpdateVersion = dismissedUpdateVersion.value,
                    onOpenService = ::requestOpenService,
                    onOpenSettings = { screenState.value = AppScreen.Settings },
                    onOpenNetworkSettings = { screenState.value = AppScreen.NetworkSettings },
                    onOpenIStoreLogin = ::openIStoreLogin,
                    hasIStoreService = iStoreServices.value.isNotEmpty(),
                    onManageCredentials = {
                        credentialsIsGlobal.value = false
                        credentialsService.value = it
                    },
                    onCredentialsChanged = ::onCredentialsChanged,
                    onAuthenticationDisabled = ::clearServiceCredentials,
                    onServiceDeleted = ::clearServiceCredentials,
                    onDismissCredentials = {
                        credentialsService.value = null
                        credentialsIsGlobal.value = false
                    },
                    onRetry = ::resolveAndOpen,
                    onBack = ::showServices,
                    onBackToSettings = { screenState.value = AppScreen.Settings },
                    onCheckUpdates = ::checkUpdates,
                    onDownloadUpdate = ::startUpdateDownload,
                    onRemindUpdateLater = ::remindUpdateLater,
                    onSkipUpdate = ::skipUpdate,
                    webHostHandlers = webHostHandlers,
                    logRepository = logRepository,
                    feedbackService = feedbackService,
                    wifiNetworkNameProvider = wifiNetworkNameProvider,
                    routeResolver = routeResolver,
                    systemInfoProvider = systemInfoProvider,
                    openClashNodeSwitcher = openClashSystemInfoProvider,
                    openClashNodeLatencyTester = openClashSystemInfoProvider,
                    wifiPermissionRefreshToken = wifiNamesPermissionRefreshToken,
                    onRequestWifiPermissions = ::requestWifiNamePermissions,
                )
            }
        }
        pendingRestoredOpenClashService?.also { restoredService ->
            pendingRestoredOpenClashService = null
            // A Zashboard URL contains the short-lived controller secret. It
            // is intentionally regenerated after process/config restoration
            // instead of being copied into the saved-instance Bundle.
            resolveAndOpen(restoredService)
        }
        monitorUpdateCheck(updateHost.startupCheck())
        startIStoreSessionWarmup()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        saveScreen(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (requiredWifiPermissions().any { permission ->
                ContextCompat.checkSelfPermission(this, permission) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }) {
            // Permission may have been changed in Android Settings while the
            // Activity was paused; allow the next service tap to request it.
            wifiPermissionPrompted = false
        }
        updateHost.refreshInstallResult()
        val result = installResultStore.get()
        // STATUS_PENDING_USER_ACTION is an intermediate callback. The
        // receiver launches Android's confirmation Intent, so do not report
        // it as a failure or consume the session id before the final result.
        if (result != null && result.isTerminal && result.sessionId != lastShownInstallSessionId) {
            lastShownInstallSessionId = result.sessionId
            val message = if (result.succeeded) "远程服务已更新" else "更新安装失败：${result.message.orEmpty()}"
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        updateHost.close()
        appScope.cancel()
        super.onDestroy()
    }

    private fun requestOpenService(service: ServiceConfig) {
        warnIfLocationServicesDisabled(service)
        val permissions = requiredWifiPermissions()
        val shouldRequest = service.trustedSsids.isNotEmpty() &&
            !permissions.all { permission ->
                ContextCompat.checkSelfPermission(this, permission) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            } &&
            !wifiPermissionPrompted
        if (shouldRequest) {
            wifiPermissionPrompted = true
            pendingPermissionService = service
            wifiPermissionLauncher.launch(permissions)
        } else {
            resolveAndOpen(service)
        }
    }

    private fun clearServiceCredentials(service: ServiceConfig) {
        runOnUiThread {
            iStoreSessionManager.invalidate(service)
            runCatching {
                credentialRouteKeys(service).forEach { routeKey ->
                    credentialStore.delete(service.id, routeKey)
                }
            }.onFailure {
                Toast.makeText(this, "清除登录凭据失败，请重试", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onCredentialsChanged(service: ServiceConfig) {
        iStoreSessionManager.invalidate(service)
        appScope.launch {
            runCatching { iStoreSessionManager.warmUp(service, routeResolver) }
        }
    }

    private fun openIStoreLogin() {
        val service = iStoreServices.value.firstOrNull()
        if (service == null) {
            Toast.makeText(this, "请先添加 iStoreOS 服务", Toast.LENGTH_SHORT).show()
        } else {
            credentialsIsGlobal.value = true
            credentialsService.value = service
        }
    }

    /**
     * Start one application-level login warm-up for every configured LuCI/
     * iStore service. The dashboard also calls ensure() defensively, so a
     * service added while the app is open is covered without a restart.
     */
    private fun startIStoreSessionWarmup() {
        appScope.launch {
            serviceStore.services.collectLatest { services ->
                val globalServices = services.filter {
                    it.serviceType == ServiceType.ISTORE || it.serviceType == ServiceType.LUCI
                }
                iStoreCredentialServiceIds.set(globalServices.map { it.id }.toSet())
                iStoreServices.value = globalServices
                services
                    .filter {
                        it.serviceType == ServiceType.ISTORE ||
                            it.serviceType == ServiceType.LUCI ||
                            it.serviceType == ServiceType.OPENCLASH_PANEL ||
                            it.serviceType == ServiceType.OPENCLASH ||
                            it.serviceType == ServiceType.DOCKER
                    }
                    .forEach { service ->
                        launch {
                            runCatching { iStoreSessionManager.warmUp(service, routeResolver) }
                        }
                    }
            }
        }
    }

    /**
     * On Android 8.0 and later, reading the current SSID is coupled to the
     * system location toggle. A disabled toggle must not block opening a service:
     * the route resolver will continue with its public-address fallback.
     */
    private fun warnIfLocationServicesDisabled(service: ServiceConfig) {
        if (service.trustedSsids.isEmpty() ||
            isLocationServicesEnabled()
        ) {
            return
        }
        Toast.makeText(
            this,
            "定位服务已关闭，无法读取 Wi-Fi 名称，将使用公网地址",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun isLocationServicesEnabled(): Boolean {
        val locationManager = getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            runCatching {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }.getOrDefault(false)
        }
    }

    private fun requiredWifiPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ uses Nearby devices for Wi-Fi APIs, while SSID and
            // scan results remain location-sensitive and are redacted without
            // precise location. Request both only when routing/suggestions
            // actually need them; manual SSID entry never requires either.
            arrayOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        } else {
            // Android 12+ requires requesting coarse and fine together when an
            // app needs precise Wi-Fi/SSID access; an approximate-only choice
            // must gracefully fall back to the public route.
            arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }

    private fun requestWifiNamePermissions() {
        val permissions = requiredWifiPermissions()
        if (permissions.all { permission ->
                ContextCompat.checkSelfPermission(this, permission) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }) {
            wifiNamesPermissionRefreshToken++
        } else {
            wifiNamePermissionLauncher.launch(permissions)
        }
    }

    private fun resolveAndOpen(service: ServiceConfig) {
        screenState.value = AppScreen.Resolving(service.displayName)
        appScope.launch {
            // Do not rely on the background warm-up collector having emitted
            // before the user taps OpenClash.  Read the catalogue once at the
            // point of use so the Settings > iStore credential scope is
            // always available for this login attempt.
            val configuredServices = runCatching { serviceStore.services.first() }.getOrDefault(emptyList())
            iStoreCredentialServiceIds.set(
                configuredServices
                    .filter { it.serviceType == ServiceType.ISTORE || it.serviceType == ServiceType.LUCI }
                    .map { it.id }
                    .toSet(),
            )
            if (service.serviceType == ServiceType.OPENCLASH) {
                val dashboardResult = runCatching { openClashDashboardResolver.resolve(service) }
                screenState.value = dashboardResult.fold(
                    onSuccess = { openResult ->
                        when (openResult) {
                            is OpenClashDashboardTargetResult.Ready -> AppScreen.Web(
                                target = openResult.target,
                                service = service,
                            ).also { showRouteToast(openResult.resolution) }
                            is OpenClashDashboardTargetResult.Unavailable -> {
                                logRepository.append(
                                    LogLevel.WARN,
                                    "OPENCLASH_DASHBOARD_UNAVAILABLE",
                                    openResult.message,
                                    context = mapOf("service_id" to service.id),
                                )
                                AppScreen.Error(service, openResult.message)
                            }
                        }
                    },
                    onFailure = { error ->
                        logRepository.append(
                            LogLevel.ERROR,
                            "OPENCLASH_DASHBOARD_RESOLVE_FAILED",
                            error.message ?: "打开 OpenClash Zashboard 失败",
                            context = mapOf("service_id" to service.id),
                            error = error,
                        )
                        AppScreen.Error(service, error.message ?: "打开 OpenClash Zashboard 失败")
                    },
                )
                return@launch
            }
            if (
                service.serviceType == ServiceType.ISTORE ||
                service.serviceType == ServiceType.LUCI ||
                service.serviceType == ServiceType.OPENCLASH_PANEL ||
                service.serviceType == ServiceType.OPENCLASH ||
                service.serviceType == ServiceType.DOCKER
            ) {
                // Warm the shared session before creating the visible WebView.
                // A failure is intentionally non-blocking: the page can still
                // be opened for manual login and troubleshooting.
                runCatching { iStoreSessionManager.warmUp(service, routeResolver) }
            }
            val result = runCatching {
                webCoordinator.resolve(service, service.trustedSsids)
            }
            screenState.value = result.fold(
                onSuccess = { openResult ->
                    when (openResult) {
                        is ServiceWebOpenResult.Ready -> {
                            logRepository.append(
                                LogLevel.INFO,
                                "ROUTE_SELECTED",
                                "已选择服务访问线路",
                                context = buildMap {
                                    put("service_id", service.id)
                                    put("service_type", service.serviceType.name)
                                    put("route", openResult.resolution.endpoint.kind.name)
                                    put("fallback_used", openResult.resolution.fallbackUsed.toString())
                                    put("connection_policy", service.connectionPolicy.name)
                                    put("trusted_ssid_count", service.trustedSsids.size.toString())
                                    put("ssid_permission", openResult.resolution.ssidPermission.name)
                                    put("ssid_present", (!openResult.resolution.ssid.isNullOrBlank()).toString())
                                    openResult.resolution.probe.statusCode?.let { put("probe_status", it.toString()) }
                                    ServiceWebCoordinator.webOrigin(openResult.resolution.endpoint.url)
                                        ?.substringAfter("://")
                                        ?.let { put("endpoint_origin", it) }
                                },
                            )
                            AppScreen.Web(
                                target = openResult.target,
                                service = service,
                            ).also { showRouteToast(openResult.resolution) }
                        }
                        is ServiceWebOpenResult.Unavailable -> {
                            logRepository.append(
                                LogLevel.WARN,
                                "ROUTE_UNAVAILABLE",
                                openResult.message,
                                context = mapOf("service_id" to service.id, "service_type" to service.serviceType.name),
                            )
                            AppScreen.Error(service, openResult.message)
                        }
                    }
                },
                onFailure = { error ->
                    logRepository.append(
                        LogLevel.ERROR,
                        "ROUTE_RESOLVE_FAILED",
                        error.message ?: "打开服务失败",
                        context = mapOf("service_id" to service.id, "service_type" to service.serviceType.name),
                        error = error,
                    )
                    AppScreen.Error(service, error.message ?: "打开服务失败")
                },
            )
        }
    }

    private fun showRouteToast(resolution: xin.dponnood.remoteservice.core.network.RouteResolution) {
        val route = if (resolution.endpoint.kind == RouteKind.INTERNAL) "内网" else "公网"
        val suffix = if (resolution.fallbackUsed) "（内网不可达，已回退）" else ""
        Toast.makeText(this, "已选择${route}线路${suffix}", Toast.LENGTH_SHORT).show()
    }

    private fun checkUpdates() {
        dismissedUpdateVersion.value = null
        monitorUpdateCheck(updateHost.manualCheck())
    }

    /** Keeps startup and user-triggered check failures visible in the local diagnostic log. */
    private fun monitorUpdateCheck(checkJob: Job) {
        appScope.launch {
            checkJob.join()
            when (val result = updateHost.state.value.lastCheck) {
                is UpdateCheckResult.Failed -> logRepository.append(
                    LogLevel.ERROR,
                    "UPDATE_CHECK_FAILED",
                    result.error.message ?: "检查更新失败",
                    error = result.error,
                )
                else -> Unit
            }
        }
    }

    private fun remindUpdateLater() {
        dismissedUpdateVersion.value = updateHost.state.value.ui.available?.versionCode
    }

    private fun skipUpdate() {
        updateHost.applyPromptAction(UpdatePromptAction.SKIP_VERSION)
        dismissedUpdateVersion.value = null
    }

    private fun startUpdateDownload() {
        appScope.launch {
            val launchResult = updateHost.downloadAndInstall()
            when (val result = launchResult.getOrNull()) {
                is InstallLaunchResult.RequiresUnknownSourcesPermission -> {
                    unknownSourcesLauncher.launch(result.intent)
                }
                is InstallLaunchResult.Submitted -> {
                    Toast.makeText(this@MainActivity, "已提交系统安装，请确认升级", Toast.LENGTH_LONG).show()
                }
                is InstallLaunchResult.Failed -> {
                    logRepository.append(LogLevel.ERROR, "UPDATE_INSTALL_FAILED", result.error.message ?: "提交安装失败", error = result.error)
                    Toast.makeText(this@MainActivity, "提交安装失败", Toast.LENGTH_LONG).show()
                }
                null -> {
                    val error = launchResult.exceptionOrNull()
                    logRepository.append(
                        LogLevel.ERROR,
                        "UPDATE_DOWNLOAD_FAILED",
                        error?.message ?: "下载或校验更新失败",
                        error = error,
                    )
                    Toast.makeText(this@MainActivity, "更新下载或校验失败", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun enqueueDownloadRequest(request: WebDownloadRequest) {
        val uri = runCatching { Uri.parse(request.url) }.getOrNull()
        if (uri == null || !uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
            Toast.makeText(this, "已阻止不安全下载地址", Toast.LENGTH_SHORT).show()
            return
        }
        val suggestedName = request.contentDisposition
            ?.substringAfter("filename=", "")
            ?.trim()
            ?.trim('"', '\'', ';')
            ?.takeIf(String::isNotBlank)
            ?: uri.lastPathSegment?.takeIf(String::isNotBlank)
            ?: "remote-service-download"
        val safeName = suggestedName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        runCatching {
            val download = DownloadManager.Request(uri)
                .setTitle(safeName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, safeName)
            request.userAgent?.takeIf(String::isNotBlank)?.let { download.addRequestHeader("User-Agent", it) }
            request.mimeType?.takeIf(String::isNotBlank)?.let(download::setMimeType)
            getSystemService(DownloadManager::class.java).enqueue(download)
            Toast.makeText(this, "已加入下载队列", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "下载失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveScreen(outState: Bundle) {
        val web = screenState.value as? AppScreen.Web ?: return
        val isEphemeralOpenClashTarget = web.service.serviceType == ServiceType.OPENCLASH &&
            web.target.url.contains("/ui/zashboard/", ignoreCase = true)
        outState.putString(KEY_SCREEN, SCREEN_WEB)
        outState.putBoolean(KEY_TARGET_EPHEMERAL, isEphemeralOpenClashTarget)
        if (!isEphemeralOpenClashTarget) {
            outState.putString(KEY_TARGET_URL, stripUrlUserInfo(web.target.url))
        }
        outState.putString(KEY_TARGET_ROUTE, web.target.sessionKey.routeKind.name)
        outState.putString(KEY_TARGET_HOST, web.target.sessionKey.host)
        outState.putStringArrayList(KEY_TARGET_ORIGINS, ArrayList(web.target.allowedOrigins))
        outState.putString(KEY_SERVICE_ID, web.service.id)
        outState.putString(KEY_SERVICE_NAME, web.service.displayName)
        outState.putString(KEY_SERVICE_LAN, stripUrlUserInfo(web.service.lanUrl))
        outState.putString(KEY_SERVICE_WAN, stripUrlUserInfo(web.service.wanUrl))
        outState.putString(KEY_SERVICE_GROUP, web.service.group)
        outState.putInt(KEY_SERVICE_ORDER, web.service.sortOrder)
        outState.putString(KEY_SERVICE_ICON, web.service.iconKey)
        outState.putStringArrayList(KEY_SERVICE_SSIDS, ArrayList(web.service.trustedSsids))
        outState.putString(KEY_SERVICE_TYPE, web.service.serviceType.name)
        outState.putBoolean(KEY_SERVICE_AUTH, web.service.authEnabled)
        outState.putString(KEY_SERVICE_CONNECTION_POLICY, web.service.connectionPolicy.name)
    }

    private fun restoreScreen(state: Bundle?) {
        if (state?.getString(KEY_SCREEN) != SCREEN_WEB) return
        val serviceId = state.getString(KEY_SERVICE_ID) ?: return
        val serviceType = state.getString(KEY_SERVICE_TYPE)
            ?.let { runCatching { ServiceType.valueOf(it) }.getOrNull() }
            ?: ServiceType.GENERIC
        val service = ServiceConfig(
            id = serviceId,
            displayName = state.getString(KEY_SERVICE_NAME).orEmpty(),
            lanUrl = stripUrlUserInfo(state.getString(KEY_SERVICE_LAN)),
            wanUrl = stripUrlUserInfo(state.getString(KEY_SERVICE_WAN)),
            group = state.getString(KEY_SERVICE_GROUP),
            sortOrder = state.getInt(KEY_SERVICE_ORDER, 0),
            iconKey = state.getString(KEY_SERVICE_ICON).orEmpty().ifBlank { "service" },
            trustedSsids = state.getStringArrayList(KEY_SERVICE_SSIDS)?.toSet().orEmpty(),
            serviceType = serviceType,
            authEnabled = state.getBoolean(KEY_SERVICE_AUTH, false),
            connectionPolicy = state.getString(KEY_SERVICE_CONNECTION_POLICY)
                ?.let { runCatching { ConnectionPolicy.valueOf(it) }.getOrNull() }
                ?: ConnectionPolicy.AUTO,
        )
        val url = stripUrlUserInfo(state.getString(KEY_TARGET_URL))
        if (url == null && state.getBoolean(KEY_TARGET_EPHEMERAL, false) &&
            service.serviceType == ServiceType.OPENCLASH
        ) {
            pendingRestoredOpenClashService = service
            screenState.value = AppScreen.Resolving(service.displayName)
            return
        }
        val targetUrl = url ?: return
        val routeKind = state.getString(KEY_TARGET_ROUTE)
            ?.let { runCatching { RouteKind.valueOf(it) }.getOrNull() } ?: return
        val host = state.getString(KEY_TARGET_HOST) ?: return
        val origins = state.getStringArrayList(KEY_TARGET_ORIGINS)?.toSet().orEmpty()
        screenState.value = AppScreen.Web(
            target = WebTarget(
                url = targetUrl,
                sessionKey = WebSessionKey(service.id, routeKind, host),
                allowedOrigins = origins.ifEmpty { setOf(host) },
            ),
            service = service,
        )
    }

    private fun showServices() {
        screenState.value = AppScreen.Services
    }

    private fun currentVersionLabel(): String {
        val info = packageManager.getPackageInfo(packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return "${info.versionName.orEmpty()}（versionCode $versionCode）"
    }

    private companion object {
        const val KEY_SCREEN = "app_screen"
        const val SCREEN_WEB = "web"
        const val KEY_TARGET_URL = "target_url"
        const val KEY_TARGET_EPHEMERAL = "target_ephemeral"
        const val KEY_TARGET_ROUTE = "target_route"
        const val KEY_TARGET_HOST = "target_host"
        const val KEY_TARGET_ORIGINS = "target_origins"
        const val KEY_SERVICE_ID = "service_id"
        const val KEY_SERVICE_NAME = "service_name"
        const val KEY_SERVICE_LAN = "service_lan"
        const val KEY_SERVICE_WAN = "service_wan"
        const val KEY_SERVICE_GROUP = "service_group"
        const val KEY_SERVICE_ORDER = "service_order"
        const val KEY_SERVICE_ICON = "service_icon"
        const val KEY_SERVICE_SSIDS = "service_ssids"
        const val KEY_SERVICE_TYPE = "service_type"
        const val KEY_SERVICE_AUTH = "service_auth"
        const val KEY_SERVICE_CONNECTION_POLICY = "service_connection_policy"
    }
}

@Composable
private fun RemoteServicesApp(
    screen: AppScreen,
    selectedServiceId: String?,
    onSelectedServiceIdChanged: (String?) -> Unit,
    store: ServiceConfigStore,
    dashboardCardPreferencesStore: DashboardCardPreferencesStore,
    credentialStore: CredentialStore,
    currentVersionLabel: String,
    logRepository: LogRepository,
    feedbackService: FeedbackService,
    credentialsService: ServiceConfig?,
    credentialsIsGlobal: Boolean,
    updateState: UpdateHostState,
    showOptionalUpdate: Boolean,
    dismissedUpdateVersion: Long?,
    onOpenService: (ServiceConfig) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNetworkSettings: () -> Unit,
    onOpenIStoreLogin: () -> Unit,
    hasIStoreService: Boolean,
    onManageCredentials: (ServiceConfig) -> Unit,
    onCredentialsChanged: (ServiceConfig) -> Unit,
    onAuthenticationDisabled: (ServiceConfig) -> Unit,
    onServiceDeleted: (ServiceConfig) -> Unit,
    onDismissCredentials: () -> Unit,
    onRetry: (ServiceConfig) -> Unit,
    onBack: () -> Unit,
    onBackToSettings: () -> Unit,
    onCheckUpdates: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onRemindUpdateLater: () -> Unit,
    onSkipUpdate: () -> Unit,
    webHostHandlers: WebViewHostHandlers,
    wifiNetworkNameProvider: WifiNetworkNameProvider,
    routeResolver: RouteResolver,
    systemInfoProvider: SystemInfoProvider,
    openClashNodeSwitcher: OpenClashNodeSwitcher,
    openClashNodeLatencyTester: OpenClashNodeLatencyTester,
    wifiPermissionRefreshToken: Int,
    onRequestWifiPermissions: () -> Unit,
) {
    when (screen) {
        AppScreen.Services -> ServicesScreen(
            store = store,
            restoredSelectedServiceId = selectedServiceId,
            onSelectedServiceIdChanged = onSelectedServiceIdChanged,
            dashboardCardPreferencesStore = dashboardCardPreferencesStore,
            onOpenService = onOpenService,
            onManageCredentials = onManageCredentials,
            onAuthenticationDisabled = onAuthenticationDisabled,
            onServiceDeleted = onServiceDeleted,
            onCheckUpdates = onCheckUpdates,
            onOpenSettings = onOpenSettings,
            wifiNetworkNameProvider = wifiNetworkNameProvider,
            systemInfoProvider = systemInfoProvider,
            openClashNodeSwitcher = openClashNodeSwitcher,
            openClashNodeLatencyTester = openClashNodeLatencyTester,
            wifiPermissionRefreshToken = wifiPermissionRefreshToken,
            onRequestWifiPermissions = onRequestWifiPermissions,
        )
        AppScreen.Settings -> SettingsScreen(
            currentVersionLabel = currentVersionLabel,
            updateState = updateState,
            logRepository = logRepository,
            feedbackService = feedbackService,
            onCheckUpdates = onCheckUpdates,
            onDownloadUpdate = onDownloadUpdate,
            onBack = onBack,
            onOpenNetworkSettings = onOpenNetworkSettings,
            onOpenIStoreLogin = onOpenIStoreLogin,
            hasIStoreService = hasIStoreService,
        )
        AppScreen.NetworkSettings -> NetworkSettingsScreen(
            store = store,
            wifiNetworkNameProvider = wifiNetworkNameProvider,
            routeResolver = routeResolver,
            onRequestWifiPermissions = onRequestWifiPermissions,
            onBack = onBackToSettings,
        )
        is AppScreen.Resolving -> ResolvingScreen(screen.serviceName)
        is AppScreen.Web -> ServiceWebScreen(
            target = screen.target,
            service = screen.service,
            credentialStore = credentialStore,
            logRepository = logRepository,
            onBack = onBack,
            onRetry = { onRetry(screen.service) },
            webHostHandlers = webHostHandlers,
        )
        is AppScreen.Error -> ErrorScreen(
            service = screen.service,
            message = screen.message,
            onRetry = { onRetry(screen.service) },
            onBack = onBack,
        )
    }

    credentialsService?.let { service ->
        CredentialDialog(
            service = service,
            credentialStore = credentialStore,
            isGlobalLogin = credentialsIsGlobal,
            onSaved = { onCredentialsChanged(service) },
            onDismiss = onDismissCredentials,
        )
    }

    val info = updateState.ui.available
    if (info != null && (updateState.ui.mandatory || (showOptionalUpdate && dismissedUpdateVersion != info.versionCode))) {
        UpdateDialog(
            state = updateState,
            onDownload = onDownloadUpdate,
            onRemindLater = onRemindUpdateLater,
            onSkip = onSkipUpdate,
        )
    }
}

@Composable
private fun ResolvingScreen(serviceName: String) {
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("正在选择线路：$serviceName", style = MaterialTheme.typography.bodyLarge)
            Text("内网不可达时会自动回退公网", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun UpdateDialog(
    state: UpdateHostState,
    onDownload: () -> Unit,
    onRemindLater: () -> Unit,
    onSkip: () -> Unit,
) {
    val info = state.ui.available ?: return
    val mandatory = state.ui.mandatory
    val progress = if (state.ui.totalBytes > 0L) {
        (state.ui.downloadedBytes.toFloat() / state.ui.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    AlertDialog(
        onDismissRequest = { if (!mandatory) onRemindLater() },
        title = { Text(if (mandatory) "必须升级" else "发现新版本") },
        text = {
            Column {
                Text("远程服务 ${info.versionName}", style = MaterialTheme.typography.titleMedium)
                if (info.releaseNotes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(info.releaseNotes, style = MaterialTheme.typography.bodyMedium)
                }
                if (mandatory) {
                    Spacer(Modifier.height(8.dp))
                    Text("此版本要求最低支持版本 ${info.minimumVersionCode}。", color = MaterialTheme.colorScheme.error)
                }
                if (state.ui.downloading) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { progress })
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "正在下载 ${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (state.unknownSourcesIntent != null) {
                    Spacer(Modifier.height(8.dp))
                    Text("下载已校验，请允许安装未知应用后继续。")
                }
                state.ui.error?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    Text("更新失败：${error.message}", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !state.ui.downloading,
                onClick = onDownload,
            ) { Text(if (state.unknownSourcesIntent != null) "继续安装" else "立即升级") }
        },
        dismissButton = {
            Row {
                if (!mandatory) {
                    TextButton(onClick = onSkip) { Text("跳过此版本") }
                    TextButton(onClick = onRemindLater) { Text("稍后") }
                }
            }
        },
    )
}

@Composable
private fun ErrorScreen(
    service: ServiceConfig,
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    Box(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("暂时无法打开 ${service.displayName}", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRetry) { Text("重试") }
            TextButton(onClick = onBack) { Text("关闭页面") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceWebScreen(
    target: WebTarget,
    service: ServiceConfig,
    credentialStore: CredentialStore,
    logRepository: LogRepository,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    webHostHandlers: WebViewHostHandlers,
) {
    val activity = LocalActivity.current
    var finishedUrl by remember(target.sessionKey) { mutableStateOf<String?>(null) }
    var attemptedLoginUrl by remember(target.sessionKey) { mutableStateOf<String?>(null) }
    var pageError by remember(target.sessionKey) { mutableStateOf<WebPageError?>(null) }
    var pageNotice by remember(target.sessionKey) { mutableStateOf<String?>(null) }
    var pageProgress by remember(target.sessionKey) { mutableIntStateOf(0) }
    val windows = remember(target.sessionKey) { mutableStateListOf<WebWindowInstance>() }
    var activeWindowId by remember(target.sessionKey) { mutableIntStateOf(0) }
    var nextWindowId by remember(target.sessionKey) { mutableIntStateOf(1) }
    var showWindowPicker by remember(target.sessionKey) { mutableStateOf(false) }
    var rootWebView by remember(target.sessionKey) { mutableStateOf<WebView?>(null) }
    val isHttpTarget = remember(target.url) {
        runCatching { Uri.parse(target.url).scheme.equals("http", ignoreCase = true) }.getOrDefault(false)
    }
    val isOpenClashDashboardTarget = remember(target.url, service.serviceType) {
        service.serviceType == ServiceType.OPENCLASH &&
            target.url.contains("/ui/zashboard/", ignoreCase = true)
    }
    val luciHook = remember(
        target.sessionKey,
        service.id,
        service.serviceType,
        service.authEnabled,
        isHttpTarget,
        isOpenClashDashboardTarget,
    ) {
        if (!isHttpTarget && service.authEnabled &&
            !isOpenClashDashboardTarget &&
            (
                service.serviceType == ServiceType.LUCI ||
                    service.serviceType == ServiceType.ISTORE ||
                    service.serviceType == ServiceType.OPENCLASH_PANEL ||
                    service.serviceType == ServiceType.OPENCLASH
                )
        ) {
            LuciWebViewHook(LuciAuthAdapter(credentialStore))
        } else {
            null
        }
    }
    // The controller is created before the window callback is assigned.  A
    // one-slot holder keeps the callback stable while allowing it to refer to
    // the current Compose state and controller without rebuilding WebView
    // clients on every recomposition.
    val windowHandlerHolder = remember(target.sessionKey) { arrayOfNulls<InAppWindowHandler>(1) }
    val controller = remember(target.sessionKey) {
        SecureWebViewController(
            externalLinkHandler = webHostHandlers.externalLinkHandler,
            inAppWindowHandler = InAppWindowHandler { parent, childTarget, resultMsg ->
                windowHandlerHolder[0]?.open(parent, childTarget, resultMsg) == true
            },
            fileChooserHandler = webHostHandlers.fileChooserHandler,
            downloadHandler = webHostHandlers.downloadHandler,
            eventListener = object : WebViewEventListener {
                override fun onLoading(view: WebView, url: String?, progress: Int) {
                    if (!RootWebViewEventTarget.accepts(rootWebView, view)) return
                    pageProgress = progress.coerceIn(0, 100)
                    if (progress == 0) {
                        pageError = null
                        pageNotice = null
                    }
                    if (progress >= 100) finishedUrl = url
                }

                override fun onPageError(view: WebView, url: String?, description: String?) {
                    if (!RootWebViewEventTarget.accepts(rootWebView, view)) return
                    logRepository.append(
                        LogLevel.ERROR,
                        "WEB_LOAD_FAILED",
                        description ?: "页面加载失败",
                        context = mapOf("service_id" to service.id, "service_type" to service.serviceType.name),
                    )
                    pageError = WebPageError(
                        url = url ?: target.url,
                        message = description?.takeIf(String::isNotBlank) ?: "页面加载失败",
                    )
                }

                override fun onHttpError(
                    view: WebView,
                    url: String?,
                    statusCode: Int,
                    responseHeaders: Map<String, String>,
                ) {
                    if (!RootWebViewEventTarget.accepts(rootWebView, view)) return
                    val loginRequiredHeader = responseHeaders.entries.any { (name, value) ->
                        name.equals("X-LuCI-Login-Required", ignoreCase = true) &&
                            value.equals("yes", ignoreCase = true)
                    }
                    val isAuthenticationResponse = loginRequiredHeader || statusCode == 401 || statusCode == 403
                    logRepository.append(
                        if (isAuthenticationResponse) LogLevel.WARN else LogLevel.ERROR,
                        "WEB_HTTP_ERROR",
                        "服务返回 HTTP $statusCode",
                        context = buildMap {
                            put("service_id", service.id)
                            put("status_code", statusCode.toString())
                            if (loginRequiredHeader) put("luci_login_required", "true")
                        },
                    )
                    if (isAuthenticationResponse) {
                        // LuCI deliberately returns 403 together with the
                        // login form when credentials/session/challenge are
                        // rejected. Keep that form visible so the user can
                        // correct the credentials instead of replacing it
                        // with an empty-looking WebView error state.
                        pageNotice = if (loginRequiredHeader) {
                            "路由器要求重新登录（HTTP $statusCode），请检查用户名、密码或登录验证后重试"
                        } else {
                            "登录未通过（HTTP $statusCode），请检查用户名、密码或路由器登录验证后重试"
                        }
                    } else {
                        pageError = WebPageError(
                            url = url ?: target.url,
                            message = "服务返回 HTTP $statusCode",
                        )
                    }
                }

                override fun onRenderProcessGone(view: WebView, didCrash: Boolean) {
                    if (!RootWebViewEventTarget.accepts(rootWebView, view)) return
                    val message = if (didCrash) {
                        "系统 WebView 渲染进程异常退出，请更新 Android System WebView 后重试"
                    } else {
                        "系统 WebView 渲染进程被系统回收，请重试"
                    }
                    logRepository.append(
                        LogLevel.ERROR,
                        "WEB_RENDER_PROCESS_GONE",
                        message,
                        context = mapOf("service_id" to service.id, "did_crash" to didCrash.toString()),
                    )
                    pageError = WebPageError(target.url, message)
                }

                override fun onTlsError(view: WebView, url: String?) {
                    if (!RootWebViewEventTarget.accepts(rootWebView, view)) return
                    logRepository.append(
                        LogLevel.ERROR,
                        "WEB_TLS_ERROR",
                        "HTTPS 证书校验失败",
                        context = mapOf("service_id" to service.id),
                    )
                    pageError = WebPageError(
                        url = url ?: target.url,
                        message = "HTTPS 证书校验失败，出于安全原因已停止加载",
                    )
                }
            },
        )
    }
    val windowHandler = remember(target.sessionKey, controller) {
        InAppWindowHandler { parent, childTarget, resultMsg ->
            val transport = resultMsg.obj as? WebView.WebViewTransport
            if (transport == null) {
                logRepository.append(
                    LogLevel.WARN,
                    "WEB_WINDOW_REJECTED",
                    "系统未提供可用的 WebView 窗口传输对象",
                    context = mapOf("service_id" to service.id),
                )
                false
            } else {
                val child = runCatching { WebView(parent.context) }.getOrNull()
                if (child == null) {
                    logRepository.append(
                        LogLevel.ERROR,
                        "WEB_WINDOW_CREATE_FAILED",
                        "创建应用内 WebView 窗口失败",
                        context = mapOf("service_id" to service.id),
                    )
                    false
                } else {
                    var addedWindow: WebWindowInstance? = null
                    runCatching {
                        controller.configure(child, childTarget)
                        val id = nextWindowId
                        nextWindowId += 1
                        val window = WebWindowInstance(id, child)
                        addedWindow = window
                        windows += window
                        activeWindowId = id
                        transport.webView = child
                        resultMsg.sendToTarget()
                        logRepository.append(
                            LogLevel.INFO,
                            "WEB_WINDOW_CREATED",
                            "已在应用内打开用户请求的新窗口",
                            context = mapOf(
                                "service_id" to service.id,
                                "window_id" to id.toString(),
                            ),
                        )
                    }.onFailure { error ->
                        addedWindow?.let(windows::remove)
                        runCatching { child.destroy() }
                        logRepository.append(
                            LogLevel.ERROR,
                            "WEB_WINDOW_CREATE_FAILED",
                            error.message ?: "应用内 WebView 窗口初始化失败",
                            context = mapOf("service_id" to service.id),
                            error = error,
                        )
                    }.isSuccess
                }
            }
        }
    }
    windowHandlerHolder[0] = windowHandler
    var webView by remember(target.sessionKey) { mutableStateOf<WebView?>(null) }

    LaunchedEffect(finishedUrl, webView, service.serviceType, service.authEnabled, target.sessionKey) {
        val pageUrl = finishedUrl ?: return@LaunchedEffect
        val view = webView ?: return@LaunchedEffect
        WebViewLayoutCompatibility.applyIfNeeded(view, service.serviceType) { applied ->
            if (applied) {
                logRepository.append(
                    LogLevel.INFO,
                    "WEB_LAYOUT_COMPAT_APPLIED",
                    "已为 LuCI/iStore 页面修复折叠的根容器高度",
                    context = mapOf(
                        "service_id" to service.id,
                        "service_type" to service.serviceType.name,
                        "route" to target.sessionKey.routeKind.name,
                    ),
                )
            }
            val hook = luciHook
            if (hook != null && pageUrl != attemptedLoginUrl) {
                attemptedLoginUrl = pageUrl
                val request = LuciDomRequest(
                    service = service,
                    baseUrl = target.sessionKey.host,
                    routeKey = target.sessionKey.host,
                    pageUrl = pageUrl,
                )
                view.evaluateJavascript(LuciWebViewHook.DOM_SNAPSHOT_SCRIPT) { encodedHtml ->
                    val submitted = hook.onDomSnapshot(view, request, encodedHtml)
                    if (submitted) {
                        logRepository.append(
                            LogLevel.INFO,
                            "LUCI_AUTO_LOGIN_SUBMITTED",
                            "已向当前 HTTPS LuCI 页面提交一次自动登录",
                            context = mapOf("service_id" to service.id, "route" to target.sessionKey.routeKind.name),
                        )
                    }
                }
            }
        }
    }

    // A WebView can fail before it emits a useful page callback (for example
    // an OEM WebView provider crash or a stalled cleartext connection). Keep
    // that failure observable instead of leaving a white canvas forever.
    LaunchedEffect(target.sessionKey) {
        delay(WEB_LOAD_TIMEOUT_MS)
        if (pageProgress < 100 && pageError == null) {
            val timeoutMessage = "页面加载超时，请检查地址、网络和系统 WebView"
            logRepository.append(
                LogLevel.ERROR,
                "WEB_LOAD_TIMEOUT",
                timeoutMessage,
                context = mapOf("service_id" to service.id, "route" to target.sessionKey.routeKind.name),
            )
            pageError = WebPageError(target.url, timeoutMessage)
        }
    }

    val activeWindow = windows.firstOrNull { it.id == activeWindowId }
        ?: windows.firstOrNull()
    var titleBarExpanded by remember(target.sessionKey) { mutableStateOf(true) }

    LaunchedEffect(target.sessionKey, activeWindowId) {
        titleBarExpanded = true
        delay(WEB_PAGE_TOOLBAR_AUTO_HIDE_MS)
        titleBarExpanded = false
    }

    fun closeWindow(windowId: Int) {
        if (windowId == 0) return
        val window = windows.firstOrNull { it.id == windowId } ?: return
        val previousIndex = (windows.indexOf(window) - 1).coerceAtLeast(0)
        windows.remove(window)
        runCatching {
            window.webView.stopLoading()
            window.webView.destroy()
        }
        if (activeWindowId == windowId) {
            activeWindowId = windows.getOrNull(previousIndex)?.id ?: 0
        }
        logRepository.append(
            LogLevel.INFO,
            "WEB_WINDOW_CLOSED",
            "已关闭应用内 WebView 窗口",
            context = mapOf("service_id" to service.id, "window_id" to windowId.toString()),
        )
    }

    fun closeCurrentPage() {
        val currentWindow = activeWindow
        if (currentWindow != null && currentWindow.id != 0) {
            // A child WebView is a tab within the service page; close only
            // that tab and return to its parent instead of leaving the app.
            closeWindow(currentWindow.id)
        } else {
            logRepository.append(
                LogLevel.INFO,
                "WEB_PAGE_CLOSED",
                "用户关闭服务网页",
                context = mapOf("service_id" to service.id),
            )
            onBack()
        }
    }

    fun handleWebBack() {
        val view = activeWindow?.webView
        if (view?.canGoBack() == true) {
            view.goBack()
        } else if (activeWindow != null && activeWindow.id != 0) {
            closeWindow(activeWindow.id)
        } else {
            onBack()
        }
    }

    BackHandler(enabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        handleWebBack()
    }
    DisposableEffect(target, activeWindow?.webView, activeWindowId) {
        val effectWebView = activeWindow?.webView
        val predictiveRegistration = if (activity != null && effectWebView != null) {
            PredictiveWebBackRegistration.register(activity, effectWebView) {
                handleWebBack()
            }
        } else {
            null
        }
        onDispose { predictiveRegistration?.close() }
    }
    DisposableEffect(target, webView) {
        // Capture the instance owned by this effect. The first effect is
        // created before AndroidView's factory assigns the WebView; when that
        // assignment triggers recomposition, this effect is disposed. Reading
        // the mutable state from onDispose would destroy the newly-created
        // view and leave the screen permanently white.
        val effectWebView = webView
        onDispose {
            // The first effect is disposed when AndroidView's factory assigns
            // the root WebView. At that point there is nothing to clean up;
            // touching the mutable window list here would destroy the fresh
            // root and recreate the historical white-screen bug.
            if (effectWebView == null) return@onDispose
            controller.flushCookies()
            val children = windows.toList().filter { it.webView !== effectWebView }
            windows.clear()
            children.forEach { child ->
                runCatching {
                    child.webView.stopLoading()
                    child.webView.destroy()
                }
            }
            effectWebView?.stopLoading()
            effectWebView?.destroy()
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            AnimatedVisibility(
                visible = titleBarExpanded,
                enter = fadeIn(animationSpec = tween(220)) +
                    slideInVertically(animationSpec = tween(240), initialOffsetY = { -it }),
                exit = fadeOut(animationSpec = tween(180)) +
                    slideOutVertically(animationSpec = tween(240), targetOffsetY = { -it }),
            ) {
                TopAppBar(
                    title = {
                        Text(
                            if (windows.size > 1) {
                                "${service.displayName} · 窗口 ${windows.indexOfFirst { it.id == activeWindow?.id } + 1}"
                            } else {
                                service.displayName
                            },
                        )
                    },
                    // Back navigates the active WebView history. The separate
                    // close action exits the root page or closes a child tab.
                    navigationIcon = { TextButton(onClick = { handleWebBack() }) { Text("返回") } },
                    actions = {
                        TextButton(onClick = { closeCurrentPage() }) { Text("关闭页面") }
                        TextButton(onClick = { showWindowPicker = true }) {
                            Text("窗口${windows.size}")
                        }
                    },
                )
            }
        },
    ) { contentPadding ->
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            if (isHttpTarget) {
                Text(
                    text = "HTTP 未加密：连接内容可能被窃听或篡改",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            pageNotice?.let { notice ->
                Text(
                    text = notice,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            val error = pageError
            if (error != null) {
                WebPageErrorPanel(
                    error = error,
                    onRetry = onRetry,
                    onBack = onBack,
                )
            } else {
                if (pageProgress < 100) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "正在加载服务页面（${pageProgress}%）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // Keep the root AndroidView in the composition for the
                    // whole screen lifetime. This avoids detaching/recreating
                    // the original WebView when the first child tab is added.
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { viewContext ->
                            WebView(viewContext).also { created ->
                                webView = created
                                rootWebView = created
                                controller.configure(created, target)
                                windows += WebWindowInstance(0, created)
                                controller.load(created, target)
                            }
                        },
                        update = { view ->
                            view.visibility = if (activeWindow?.id == 0) View.VISIBLE else View.GONE
                            if (view.url == null) controller.load(view, target)
                        },
                    )
                    windows.filter { it.id != 0 }.forEach { window ->
                        key(window.id) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { window.webView },
                                update = { view ->
                                    view.visibility = if (window.id == activeWindow?.id) {
                                        View.VISIBLE
                                    } else {
                                        View.GONE
                                    }
                                },
                            )
                        }
                    }
                    CollapsedWebPageControls(
                        visible = !titleBarExpanded && pageError == null,
                        windowCount = windows.size,
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        onBack = { handleWebBack() },
                        onOpenWindowPicker = { showWindowPicker = true },
                        onClosePage = { closeCurrentPage() },
                    )
                }
            }
        }
    }
    if (showWindowPicker) {
        AlertDialog(
            onDismissRequest = { showWindowPicker = false },
            title = { Text("应用内窗口") },
            text = {
                Column {
                    windows.forEachIndexed { index, window ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = {
                                    activeWindowId = window.id
                                    showWindowPicker = false
                                },
                            ) {
                                Text(
                                    if (window.id == 0) "主窗口" else "新窗口 ${index + 1}",
                                )
                            }
                            if (window.id != 0) {
                                TextButton(onClick = { closeWindow(window.id) }) { Text("关闭") }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showWindowPicker = false }) { Text("完成") }
            },
        )
    }
}

@Composable
private fun CollapsedWebPageControls(
    visible: Boolean,
    windowCount: Int,
    onBack: () -> Unit,
    onOpenWindowPicker: () -> Unit,
    onClosePage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(220)) + slideInVertically(tween(220), initialOffsetY = { -it / 2 }),
        exit = fadeOut(tween(120)),
    ) {
        Box {
            Surface(
                shape = RoundedCornerShape(50.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.24f),
                tonalElevation = 0.dp,
            ) {
                IconButton(onClick = { menuExpanded = true }) {
                    Text(
                        text = "⋮",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    )
                }
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("返回上一页") },
                    onClick = {
                        menuExpanded = false
                        onBack()
                    },
                )
                DropdownMenuItem(
                    text = { Text("切换窗口（$windowCount）") },
                    onClick = {
                        menuExpanded = false
                        onOpenWindowPicker()
                    },
                )
                DropdownMenuItem(
                    text = { Text("关闭页面") },
                    onClick = {
                        menuExpanded = false
                        onClosePage()
                    },
                )
            }
        }
    }
}

private const val WEB_LOAD_TIMEOUT_MS = 20_000L
private const val WEB_PAGE_TOOLBAR_AUTO_HIDE_MS = 900L

@Composable
private fun WebPageErrorPanel(
    error: WebPageError,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("页面打开失败", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(error.message, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            Text("访问地址：${stripUrlUserInfo(error.url).orEmpty()}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRetry) { Text("重试") }
            TextButton(onClick = onBack) { Text("关闭页面") }
        }
    }
}

private const val DEFAULT_CREDENTIAL_ROUTE = "default"

private fun credentialRouteKeys(service: ServiceConfig): Set<String> = buildSet {
    service.lanUrl?.let { ServiceWebCoordinator.webOrigin(it) }?.let(::add)
    service.wanUrl?.let { ServiceWebCoordinator.webOrigin(it) }?.let(::add)
    add(DEFAULT_CREDENTIAL_ROUTE)
}

@Composable
private fun CredentialDialog(
    service: ServiceConfig,
    credentialStore: CredentialStore,
    isGlobalLogin: Boolean,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    var username by remember(service.id, isGlobalLogin) { mutableStateOf("") }
    var password by remember(service.id, isGlobalLogin) { mutableStateOf("") }
    var hasStored by remember(service.id, isGlobalLogin) { mutableStateOf(false) }
    var error by remember(service.id, isGlobalLogin) { mutableStateOf<String?>(null) }
    val routeKeys = remember(service.id, service.lanUrl, service.wanUrl) {
        credentialRouteKeys(service)
    }
    val credentialServiceIds = remember(service.id, isGlobalLogin) {
        if (isGlobalLogin) {
            listOf(IStoreSessionManager.GLOBAL_CREDENTIAL_SERVICE_ID, service.id).distinct()
        } else {
            listOf(service.id)
        }
    }

    LaunchedEffect(service.id, routeKeys, credentialServiceIds) {
        val credentials = credentialServiceIds.asSequence().flatMap { serviceId ->
            routeKeys.asSequence().mapNotNull { routeKey ->
                runCatching { credentialStore.get(serviceId, routeKey) }.getOrNull()
            }
        }.firstOrNull()
        username = credentials?.username.orEmpty()
        hasStored = credentials != null
        if (credentials == null && routeKeys.isNotEmpty()) {
            val readFailed = credentialServiceIds.any { serviceId ->
                routeKeys.any { routeKey ->
                    runCatching { credentialStore.get(serviceId, routeKey) }.isFailure
                }
            }
            if (readFailed) error = "读取安全存储失败"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (isGlobalLogin) "iStore 总登录" else "登录凭据 · ${service.displayName}")
        },
        text = {
            Column {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; error = null },
                    label = { Text("用户名") },
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text(if (hasStored) "新密码（留空不修改）" else "密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                )
                if (hasStored) {
                    Spacer(Modifier.height(6.dp))
                    Text("已存在加密凭据；密码不会回显。", style = MaterialTheme.typography.bodySmall)
                }
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = username.isNotBlank() && (password.isNotBlank() || hasStored),
                onClick = {
                    runCatching {
                        val current = credentialServiceIds.asSequence().flatMap { serviceId ->
                            routeKeys.asSequence().mapNotNull { routeKey ->
                                runCatching { credentialStore.get(serviceId, routeKey) }.getOrNull()
                            }
                        }.firstOrNull()
                        val credentials = ServiceCredentials(
                            username = username.trim(),
                            password = password.ifBlank { current?.password.orEmpty() },
                        )
                        credentialServiceIds.forEach { serviceId ->
                            routeKeys.forEach { routeKey ->
                                credentialStore.put(serviceId, routeKey, credentials)
                            }
                        }
                    }.onSuccess {
                        onSaved()
                        onDismiss()
                    }.onFailure { error = "保存安全凭据失败" }
                },
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (hasStored) {
                    TextButton(
                        onClick = {
                            runCatching {
                                credentialServiceIds.forEach { serviceId ->
                                    routeKeys.forEach { routeKey ->
                                        credentialStore.delete(serviceId, routeKey)
                                    }
                                }
                            }.onSuccess { onDismiss() }.onFailure { error = "清除安全凭据失败" }
                        },
                    ) { Text("清除") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
