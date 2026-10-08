package seamain.org.typhoonEye

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import seamain.org.typhoonEye.ui.screens.LicensesScreen
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import org.maplibre.android.MapLibre
import seamain.org.typhoonEye.data.preferences.ThemeMode
import seamain.org.typhoonEye.domain.model.NoDataSourceConfiguredError
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.util.typhoonIdsMatch
import seamain.org.typhoonEye.live.TyphoonLiveNotifier
import seamain.org.typhoonEye.live.TyphoonLiveUpdateWorker
import seamain.org.typhoonEye.domain.model.AppUpdateState
import seamain.org.typhoonEye.ui.DataMode
import seamain.org.typhoonEye.ui.TyphoonViewModel
import seamain.org.typhoonEye.ui.components.AppUpdateHost
import seamain.org.typhoonEye.ui.navigation.AppDestination
import seamain.org.typhoonEye.ui.screens.DetailScreen
import seamain.org.typhoonEye.ui.screens.HomeScreen
import seamain.org.typhoonEye.ui.screens.SettingsScreen
import seamain.org.typhoonEye.ui.theme.Motion
import seamain.org.typhoonEye.ui.theme.TyphoonEyeTheme
import seamain.org.typhoonEye.ui.util.formatObservationTime

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.ACCESS_FINE_LOCATION
)

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val pendingTyphoonId = MutableStateFlow<String?>(null)
    private val pendingLoadDemo = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MapLibre.getInstance(this)

        pendingTyphoonId.value = intent?.getStringExtra(TyphoonLiveNotifier.EXTRA_TYPHOON_ID)
        pendingLoadDemo.value = intent?.getBooleanExtra(EXTRA_LOAD_DEMO, false) == true

        setContent {
            // @AndroidEntryPoint supplies the Hilt ViewModel factory automatically.
            val typhoonVm: TyphoonViewModel = viewModel()
            val settings by typhoonVm.settings.collectAsStateWithLifecycle()
            val deepLinkId by pendingTyphoonId.collectAsStateWithLifecycle()
            val loadDemo by pendingLoadDemo.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (settings.themeMode) {
                ThemeMode.System -> systemDark
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }

            TyphoonEyeTheme(
                darkTheme = darkTheme,
                dynamicColor = settings.dynamicColorEnabled
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest
                ) {
                    TyphoonApp(
                        viewModel = typhoonVm,
                        deepLinkTyphoonId = deepLinkId,
                        onDeepLinkConsumed = { pendingTyphoonId.value = null },
                        loadDemoRequested = loadDemo,
                        onLoadDemoConsumed = { pendingLoadDemo.value = false }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingTyphoonId.value = intent.getStringExtra(TyphoonLiveNotifier.EXTRA_TYPHOON_ID)
        if (intent.getBooleanExtra(EXTRA_LOAD_DEMO, false)) {
            pendingLoadDemo.value = true
        }
    }

    companion object {
        /** Preview: adb … --ez extra_load_demo true */
        const val EXTRA_LOAD_DEMO = "extra_load_demo"
    }
}

@Composable
fun TyphoonApp(
    viewModel: TyphoonViewModel,
    deepLinkTyphoonId: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
    loadDemoRequested: Boolean = false,
    onLoadDemoConsumed: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val filtered by viewModel.filteredTyphoons.collectAsStateWithLifecycle()
    val selectedTyphoon by viewModel.selectedTyphoon.collectAsStateWithLifecycle()
    val detailLoading by viewModel.detailLoading.collectAsStateWithLifecycle()
    val detailError by viewModel.detailError.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val dataSourceKeys by viewModel.dataSourceKeys.collectAsStateWithLifecycle()
    val intensityFilter by viewModel.intensityFilter.collectAsStateWithLifecycle()
    val lastUpdatedAtMs by viewModel.lastUpdatedAtMs.collectAsStateWithLifecycle()
    val dataMode by viewModel.dataMode.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val lastUpdated = remember(lastUpdatedAtMs, settings.appLanguage) {
        lastUpdatedAtMs?.let { epoch ->
            val formatted = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm",
                java.util.Locale.getDefault()
            ).format(java.util.Date(epoch))
            formatObservationTime(formatted)
        }
    }
    val userLocation by viewModel.userLocation.collectAsStateWithLifecycle()
    val appUpdateState by viewModel.appUpdateState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val navController = rememberNavController()
    val inAppUpdatesEnabled = remember(context) {
        DistributionConfig.enableInAppUpdates(context)
    }

    var notificationsGranted by remember {
        mutableStateOf(viewModel.canPostLiveNotifications())
    }
    var locationGranted by remember {
        mutableStateOf(viewModel.hasLocationPermission())
    }
    var askedNotificationPermission by rememberSaveable { mutableStateOf(false) }
    var askedLocationPermission by rememberSaveable { mutableStateOf(false) }
    // Manual check from Settings shows up-to-date / error dialogs; silent auto-check does not.
    var updateStatusDialogs by rememberSaveable { mutableStateOf(false) }

    // After location dialog finishes, optionally continue to notification permission.
    var pendingNotificationAfterLocation by rememberSaveable { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsGranted = granted
        if (granted) {
            viewModel.refreshLiveActivity()
            TyphoonLiveUpdateWorker.schedule(context.applicationContext)
        }
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            notificationsGranted = true
            viewModel.refreshLiveActivity()
            TyphoonLiveUpdateWorker.schedule(context.applicationContext)
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            notificationsGranted = true
            viewModel.refreshLiveActivity()
            TyphoonLiveUpdateWorker.schedule(context.applicationContext)
        } else {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        locationGranted = result.values.any { it } || viewModel.hasLocationPermission()
        if (locationGranted) {
            viewModel.refreshUserLocation(force = true)
        }
        // Chain: never show two system permission dialogs at once.
        if (pendingNotificationAfterLocation) {
            pendingNotificationAfterLocation = false
            requestNotificationPermission()
        }
    }

    fun requestLocationPermission(thenRequestNotification: Boolean = false) {
        if (viewModel.hasLocationPermission()) {
            locationGranted = true
            viewModel.refreshUserLocation(force = true)
            if (thenRequestNotification) {
                requestNotificationPermission()
            }
            return
        }
        pendingNotificationAfterLocation = thenRequestNotification
        locationPermissionLauncher.launch(LOCATION_PERMISSIONS)
    }

    fun leaveDetail() {
        viewModel.selectTyphoon(null)
        navController.popBackStack()
    }

    LaunchedEffect(deepLinkTyphoonId) {
        if (!deepLinkTyphoonId.isNullOrBlank()) {
            viewModel.selectTyphoonById(deepLinkTyphoonId)
            navController.navigate(AppDestination.detail(deepLinkTyphoonId)) {
                launchSingleTop = true
            }
            onDeepLinkConsumed()
        }
    }

    LaunchedEffect(loadDemoRequested) {
        if (loadDemoRequested) {
            viewModel.loadDemoData()
            onLoadDemoConsumed()
        }
    }

    // Cold start: always request location first (distance / map / local alerts),
    // then notification if needed. Never launch two system dialogs at once.
    LaunchedEffect(Unit) {
        notificationsGranted = viewModel.canPostLiveNotifications()
        locationGranted = viewModel.hasLocationPermission()

        val needNotification =
            (settings.liveActivityEnabled || settings.emergencyAlertsEnabled) &&
                !notificationsGranted

        if (!locationGranted && !askedLocationPermission) {
            askedLocationPermission = true
            if (needNotification && !askedNotificationPermission) {
                askedNotificationPermission = true
                requestLocationPermission(thenRequestNotification = true)
            } else {
                requestLocationPermission(thenRequestNotification = false)
            }
        } else {
            if (locationGranted) {
                viewModel.refreshUserLocation(force = true)
            }
            if (needNotification && !askedNotificationPermission) {
                askedNotificationPermission = true
                requestNotificationPermission()
            } else if (notificationsGranted) {
                viewModel.refreshLiveActivity()
                TyphoonLiveUpdateWorker.schedule(context.applicationContext)
            }
        }
    }

    // If user later turns Live / emergency alerts on, ensure notification permission.
    LaunchedEffect(settings.liveActivityEnabled, settings.emergencyAlertsEnabled) {
        if (!settings.liveActivityEnabled && !settings.emergencyAlertsEnabled) return@LaunchedEffect
        val allowed = viewModel.canPostLiveNotifications()
        notificationsGranted = allowed
        if (allowed) {
            viewModel.refreshLiveActivity()
            TyphoonLiveUpdateWorker.schedule(context.applicationContext)
        } else if (!askedNotificationPermission) {
            askedNotificationPermission = true
            requestNotificationPermission()
        }
    }

    // If location permission was revoked while app is open, or user enables alerts.
    LaunchedEffect(settings.locationAlertsEnabled) {
        locationGranted = viewModel.hasLocationPermission()
        if (locationGranted) {
            viewModel.refreshUserLocation(force = true)
            return@LaunchedEffect
        }
        // Cold-start handles the first ask; only re-ask when user toggles location alerts on.
        if (settings.locationAlertsEnabled && !askedLocationPermission) {
            askedLocationPermission = true
            requestLocationPermission(thenRequestNotification = false)
        }
    }

    if (inAppUpdatesEnabled) {
        AppUpdateHost(
            state = appUpdateState,
            showStatusDialogs = updateStatusDialogs,
            canInstall = viewModel.canInstallAppPackages(),
            onDismiss = {
                viewModel.dismissAppUpdate()
                updateStatusDialogs = false
            },
            onDownload = viewModel::downloadAppUpdate,
            onInstall = { path ->
                runCatching { context.startActivity(viewModel.appInstallApkIntent(path)) }
            },
            onOpenPermissionSettings = {
                runCatching { context.startActivity(viewModel.appInstallPermissionIntent()) }
            },
            onOpenReleasePage = { info ->
                runCatching { context.startActivity(viewModel.appReleasePageIntent(info)) }
            }
        )
    }

    NavHost(
        navController = navController,
        startDestination = AppDestination.Home,
        modifier = Modifier.fillMaxSize(),
        enterTransition = {
            fadeIn(
                animationSpec = tween(Motion.DurationMedium2, easing = Motion.EmphasizedDecelerate)
            )
        },
        exitTransition = {
            fadeOut(
                animationSpec = tween(Motion.DurationShort4, easing = Motion.EmphasizedAccelerate)
            )
        },
        popEnterTransition = {
            fadeIn(
                animationSpec = tween(Motion.DurationMedium2, easing = Motion.EmphasizedDecelerate)
            )
        },
        popExitTransition = {
            fadeOut(
                animationSpec = tween(Motion.DurationShort4, easing = Motion.EmphasizedAccelerate)
            )
        }
    ) {
        composable(AppDestination.Home) {
            HomeScreen(
                uiState = uiState,
                filteredTyphoons = filtered,
                isRefreshing = isRefreshing,
                query = query,
                intensityFilter = intensityFilter,
                dataMode = dataMode,
                lastUpdated = lastUpdated,
                userLocation = userLocation,
                onQueryChange = viewModel::setQuery,
                onFilterChange = viewModel::setIntensityFilter,
                onRefresh = { viewModel.refresh() },
                onLoadDemo = viewModel::loadDemoData,
                onExitDemo = viewModel::exitDemo,
                onOpenSettings = {
                    navController.navigate(AppDestination.Settings) {
                        launchSingleTop = true
                    }
                },
                onTyphoonClick = { typhoon ->
                    viewModel.selectTyphoon(typhoon)
                    navController.navigate(AppDestination.detail(typhoon.id)) {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(AppDestination.Settings) {
            BackHandler { navController.popBackStack() }
            SettingsScreen(
                settings = settings,
                notificationsGranted = notificationsGranted,
                locationPermissionGranted = locationGranted,
                onBack = { navController.popBackStack() },
                onThemeModeChange = viewModel::setThemeMode,
                onAppLanguageChange = viewModel::setAppLanguage,
                onLiveActivityChange = { enabled ->
                    viewModel.setLiveActivityEnabled(enabled)
                    if (enabled) requestNotificationPermission()
                },
                onEmergencyAlertsChange = { enabled ->
                    viewModel.setEmergencyAlertsEnabled(enabled)
                    if (enabled) requestNotificationPermission()
                },
                onLocationAlertsChange = { enabled ->
                    viewModel.setLocationAlertsEnabled(enabled)
                    if (enabled) requestLocationPermission()
                },
                onDynamicColorChange = viewModel::setDynamicColorEnabled,
                onMapBasemapChange = viewModel::setMapBasemap,
                onRequestNotificationPermission = ::requestNotificationPermission,
                onRequestLocationPermission = ::requestLocationPermission,
                onOpenLicenses = {
                    navController.navigate(AppDestination.Licenses) {
                        launchSingleTop = true
                    }
                },
                inAppUpdatesEnabled = inAppUpdatesEnabled,
                onCheckForUpdates = {
                    updateStatusDialogs = true
                    viewModel.checkForAppUpdate(force = true)
                },
                isCheckingUpdates = appUpdateState is AppUpdateState.Checking,
                dataSourceKeys = dataSourceKeys,
                onSaveDataSourceKeys = viewModel::saveDataSourceKeys,
                onClearDataSourceKeys = viewModel::clearDataSourceKeys
            )
        }

        composable(AppDestination.Licenses) {
            BackHandler { navController.popBackStack() }
            LicensesScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = AppDestination.Detail,
            arguments = listOf(
                navArgument(AppDestination.ArgTyphoonId) { type = NavType.StringType }
            )
        ) { entry ->
            val typhoonId = entry.arguments?.getString(AppDestination.ArgTyphoonId).orEmpty()
            var detailRequested by remember(typhoonId) { mutableStateOf(false) }
            // Sticky snapshot so a mid-load id remap / list refresh cannot unmount DetailScreen.
            var displayedTyphoon by remember(typhoonId) { mutableStateOf<Typhoon?>(null) }

            LaunchedEffect(typhoonId) {
                if (typhoonId.isBlank()) return@LaunchedEffect
                detailRequested = true
                if (!typhoonIdsMatch(selectedTyphoon?.id, typhoonId)) {
                    viewModel.selectTyphoonById(typhoonId)
                }
            }

            val matched = selectedTyphoon?.takeIf { typhoonIdsMatch(it.id, typhoonId) }
            LaunchedEffect(matched) {
                if (matched != null) displayedTyphoon = matched
            }
            val typhoon = matched ?: displayedTyphoon
            BackHandler(onBack = ::leaveDetail)

            when {
                // No key (e.g. notification / deep link after removing it): explain and offer
                // the key settings instead of a stale cached storm or a generic error.
                detailError is NoDataSourceConfiguredError && dataMode == DataMode.Live && !detailLoading -> {
                    DetailNoDataSourcePlaceholder(
                        onBack = ::leaveDetail,
                        onEnterKey = {
                            navController.navigate(AppDestination.Settings) {
                                popUpTo(AppDestination.Home)
                                launchSingleTop = true
                            }
                        },
                        onViewDemo = {
                            viewModel.loadDemoData()
                            leaveDetail()
                        }
                    )
                }
                typhoon != null -> {
                    DetailScreen(
                        typhoon = typhoon,
                        loading = detailLoading,
                        onBack = ::leaveDetail,
                        shareText = viewModel.shareSummary(typhoon),
                        userLocation = userLocation,
                        mapBasemap = settings.mapBasemap,
                        isDemo = dataMode == DataMode.Demo,
                        onShare = { text ->
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_SUBJECT,
                                    context.getString(R.string.share_subject, typhoon.name)
                                )
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(
                                Intent.createChooser(
                                    intent,
                                    context.getString(R.string.share_chooser_title)
                                )
                            )
                        }
                    )
                }
                // First frames / in-flight fetch — never flash a blank surface.
                !detailRequested || detailLoading -> {
                    DetailLoadingPlaceholder(loading = true, onBack = ::leaveDetail)
                }
                else -> {
                    DetailLoadingPlaceholder(loading = false, onBack = ::leaveDetail)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailNoDataSourcePlaceholder(
    onBack: () -> Unit,
    onEnterKey: () -> Unit,
    onViewDemo: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.no_data_source_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.no_data_source_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onEnterKey) { Text(stringResource(R.string.action_enter_api_key)) }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onViewDemo) { Text(stringResource(R.string.action_view_demo)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailLoadingPlaceholder(
    loading: Boolean,
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (loading) {
                            stringResource(R.string.loading_detail)
                        } else {
                            stringResource(R.string.error_title)
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            if (loading) {
                CircularProgressIndicator()
            } else {
                Text(
                    text = stringResource(R.string.error_unknown),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
