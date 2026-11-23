package seamain.org.typhoonEye

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import seamain.org.typhoonEye.ui.util.displayName
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import org.maplibre.android.MapLibre
import seamain.org.typhoonEye.data.preferences.ThemeMode
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.util.typhoonIdsMatch
import seamain.org.typhoonEye.live.TyphoonLiveNotifier
import seamain.org.typhoonEye.live.TyphoonLiveUpdateWorker
import seamain.org.typhoonEye.domain.model.AppUpdateState
import seamain.org.typhoonEye.domain.favorites.FavoriteOpenPlan
import seamain.org.typhoonEye.domain.favorites.FavoriteTyphoon
import seamain.org.typhoonEye.domain.favorites.containsStorm
import seamain.org.typhoonEye.domain.favorites.matchesId
import seamain.org.typhoonEye.domain.favorites.toFavoriteDraft
import seamain.org.typhoonEye.ui.DataMode
import seamain.org.typhoonEye.ui.FavoritesViewModel
import seamain.org.typhoonEye.ui.HistoryViewModel
import seamain.org.typhoonEye.ui.TyphoonUiState
import seamain.org.typhoonEye.ui.TyphoonViewModel
import seamain.org.typhoonEye.ui.components.AppUpdateHost
import seamain.org.typhoonEye.ui.components.LocationOptInNoticeDialog
import seamain.org.typhoonEye.ui.navigation.AppDestination
import seamain.org.typhoonEye.domain.history.TrackPhase
import seamain.org.typhoonEye.ui.screens.DetailScreen
import seamain.org.typhoonEye.ui.screens.FavoriteUnavailableScreen
import seamain.org.typhoonEye.ui.screens.FavoritesScreen
import seamain.org.typhoonEye.ui.screens.HistoryDetailStatus
import seamain.org.typhoonEye.ui.screens.HistoryScreen
import seamain.org.typhoonEye.ui.screens.HomeScreen
import seamain.org.typhoonEye.ui.screens.localizedTitle
import seamain.org.typhoonEye.ui.screens.SettingsScreen
import seamain.org.typhoonEye.ui.theme.Motion
import seamain.org.typhoonEye.ui.theme.TyphoonEyeTheme
import seamain.org.typhoonEye.ui.util.formatObservationTime
import seamain.org.typhoonEye.ui.share.ShareSourceKind
import seamain.org.typhoonEye.ui.share.TrackShareOutcome
import seamain.org.typhoonEye.ui.share.exportAndShareTrackImage
import seamain.org.typhoonEye.ui.share.shareSourceFlagsFromBuildConfig
import seamain.org.typhoonEye.ui.share.trackShareModel
import seamain.org.typhoonEye.ui.util.MapBasemapPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.ZoneId

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
            val historyVm: HistoryViewModel = viewModel()
            val favoritesVm: FavoritesViewModel = viewModel()
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
                        historyViewModel = historyVm,
                        favoritesViewModel = favoritesVm,
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
    historyViewModel: HistoryViewModel,
    favoritesViewModel: FavoritesViewModel,
    deepLinkTyphoonId: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
    loadDemoRequested: Boolean = false,
    onLoadDemoConsumed: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val filtered by viewModel.filteredTyphoons.collectAsStateWithLifecycle()
    val selectedTyphoon by viewModel.selectedTyphoon.collectAsStateWithLifecycle()
    val detailLoading by viewModel.detailLoading.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
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
    val historyUi by historyViewModel.ui.collectAsStateWithLifecycle()
    val historyDetail by historyViewModel.detailUi.collectAsStateWithLifecycle()
    val favorites by favoritesViewModel.favorites.collectAsStateWithLifecycle()
    val loadedTyphoons = (uiState as? TyphoonUiState.Success)?.typhoons.orEmpty()
    val context = LocalContext.current
    val navController = rememberNavController()
    val shareScope = rememberCoroutineScope()
    var shareImageBusy by remember { mutableStateOf(false) }
    val shareSourceFlags = remember { shareSourceFlagsFromBuildConfig() }
    val shareDarkTheme = when (settings.themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    fun shareTrackImage(
        typhoon: Typhoon,
        sourceKind: ShareSourceKind,
        updatedAtEpochMs: Long?,
        fromCache: Boolean
    ) {
        if (shareImageBusy) return
        shareImageBusy = true
        val resolvedBasemap = MapBasemapPolicy.resolve(settings.mapBasemap, context)
        val zone = ZoneId.systemDefault()
        shareScope.launch {
            try {
                val model = trackShareModel(
                    context = context,
                    typhoon = typhoon,
                    sourceKind = sourceKind,
                    sourceFlags = shareSourceFlags,
                    updatedAtEpochMs = updatedAtEpochMs,
                    fromCache = fromCache,
                    zone = zone,
                    basemap = resolvedBasemap
                )
                val outcome = exportAndShareTrackImage(
                    context = context,
                    model = model,
                    basemap = resolvedBasemap,
                    darkTheme = shareDarkTheme,
                    chooserTitle = context.getString(R.string.share_image_chooser),
                    subject = context.getString(R.string.share_subject, typhoon.displayName(context))
                )
                val message = when (outcome) {
                    is TrackShareOutcome.Shared ->
                        if (outcome.basemapTilesIncluded) {
                            null
                        } else {
                            context.getString(R.string.share_image_basemap_unavailable)
                        }
                    TrackShareOutcome.NoTrack -> context.getString(R.string.share_image_no_track)
                    TrackShareOutcome.ExportFailed -> context.getString(R.string.share_image_export_failed)
                    TrackShareOutcome.ShareSheetFailed -> context.getString(R.string.share_image_share_failed)
                }
                if (message != null) {
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(
                    context,
                    context.getString(R.string.share_image_export_failed),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                shareImageBusy = false
            }
        }
    }
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

    // TYP-58: the ONLY entry point for the system location prompt. It must stay
    // user-initiated (Settings toggle / "grant" button / upgrade notice "Turn on");
    // never call it from a cold-start or settings-observing effect.
    // TYP-37 hook: once the first-launch disclaimer is approved, gate this function on it
    // (show the disclaimer and continue here only after it is acknowledged), so the
    // disclaimer always precedes any location prompt.
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

    fun toggleFavorite(sourceId: String, save: () -> Unit) {
        if (favorites.containsStorm(sourceId)) {
            favoritesViewModel.unfavorite(sourceId)
        } else {
            save()
        }
    }

    fun openFavorite(favorite: FavoriteTyphoon) {
        when (val plan = favoritesViewModel.planOpen(favorite, loadedTyphoons)) {
            is FavoriteOpenPlan.Live -> {
                viewModel.selectTyphoon(plan.typhoon)
                navController.navigate(AppDestination.detail(plan.typhoon.id)) {
                    launchSingleTop = true
                }
            }
            is FavoriteOpenPlan.History -> {
                historyViewModel.loadDetail(plan.storm)
                navController.navigate(AppDestination.historyDetail(plan.storm.id)) {
                    launchSingleTop = true
                }
            }
            is FavoriteOpenPlan.OutOfRange -> {
                navController.navigate(AppDestination.favoriteUnavailable(favorite.stableId)) {
                    launchSingleTop = true
                }
            }
        }
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

    // Cold start (TYP-58): never prompt for location. Only refresh a fix when the user
    // has opted in and permission is already granted; still ask for notifications.
    // TYP-37 hook: the first-launch disclaimer dialog will be shown here, before anything
    // else on first run; it must come before any location prompt.
    LaunchedEffect(Unit) {
        notificationsGranted = viewModel.canPostLiveNotifications()
        locationGranted = viewModel.hasLocationPermission()

        if (locationGranted) {
            // No-op unless location alerts are on (checked against the stored value).
            viewModel.refreshUserLocation()
        }

        val needNotification =
            (settings.liveActivityEnabled || settings.emergencyAlertsEnabled) &&
                !notificationsGranted
        if (needNotification && !askedNotificationPermission) {
            askedNotificationPermission = true
            requestNotificationPermission()
        } else if (notificationsGranted) {
            viewModel.refreshLiveActivity()
            TyphoonLiveUpdateWorker.schedule(context.applicationContext)
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

    // Keep permission state fresh and refresh the fix when alerts are on. Does NOT
    // request permission (TYP-58): the toggle's onLocationAlertsChange does that.
    LaunchedEffect(settings.locationAlertsEnabled) {
        locationGranted = viewModel.hasLocationPermission()
        if (locationGranted && settings.locationAlertsEnabled) {
            viewModel.refreshUserLocation()
        }
    }

    // TYP-58: one-time notice for github-flavor upgraders (never fresh installs / F-Droid).
    val showLocationOptInNotice by viewModel.showLocationOptInNotice.collectAsStateWithLifecycle()
    if (showLocationOptInNotice) {
        LocationOptInNoticeDialog(
            onTurnOn = {
                viewModel.acceptLocationOptInNotice()
                requestLocationPermission()
            },
            onDismiss = viewModel::dismissLocationOptInNotice
        )
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
                onOpenHistory = {
                    navController.navigate(AppDestination.History) {
                        launchSingleTop = true
                    }
                },
                onOpenFavorites = {
                    navController.navigate(AppDestination.Favorites) {
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
                isCheckingUpdates = appUpdateState is AppUpdateState.Checking
            )
        }

        composable(AppDestination.History) {
            BackHandler { navController.popBackStack() }
            HistoryScreen(
                state = historyUi,
                onBack = { navController.popBackStack() },
                onYearChange = historyViewModel::setYear,
                onQueryChange = historyViewModel::setQuery,
                onRefresh = historyViewModel::refresh,
                onOpenStorm = { storm ->
                    historyViewModel.loadDetail(storm)
                    navController.navigate(AppDestination.historyDetail(storm.id)) {
                        launchSingleTop = true
                    }
                },
                onOpenAttribution = { url ->
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    }
                },
                isFavorite = { id -> favorites.containsStorm(id) },
                onToggleFavorite = { storm ->
                    toggleFavorite(storm.id) { favoritesViewModel.favorite(storm.toFavoriteDraft()) }
                }
            )
        }

        composable(
            route = AppDestination.HistoryDetail,
            arguments = listOf(
                navArgument(AppDestination.ArgTyphoonId) { type = NavType.StringType }
            )
        ) { entry ->
            val typhoonId = entry.arguments?.getString(AppDestination.ArgTyphoonId).orEmpty()
            val track = historyDetail.track?.takeIf { typhoonIdsMatch(it.typhoon.id, typhoonId) }
            val leaveHistoryDetail = {
                navController.popBackStack()
                Unit
            }
            BackHandler(onBack = leaveHistoryDetail)
            val savedFavorite = favorites.firstOrNull { it.matchesId(typhoonId) }
            val statusMessage = when (historyDetail.phase) {
                TrackPhase.OfflineReady -> stringResource(R.string.history_offline)
                TrackPhase.RateLimited -> stringResource(R.string.history_rate_limit)
                TrackPhase.UpstreamError -> stringResource(R.string.history_upstream_error)
                else -> null
            }
            when {
                track != null -> {
                    DetailScreen(
                        typhoon = track.typhoon,
                        loading = historyDetail.loading,
                        onBack = leaveHistoryDetail,
                        shareText = stringResource(R.string.history_detail_banner) +
                            "\n" + track.typhoon.name + " " + track.typhoon.id,
                        userLocation = null,
                        mapBasemap = settings.mapBasemap,
                        historicalRecord = true,
                        trackMissing = track.trackMissing,
                        windRadiiMissing = track.windRadiiMissing,
                        statusMessage = statusMessage,
                        isFavorite = favorites.containsStorm(track.typhoon.id),
                        onToggleFavorite = {
                            toggleFavorite(track.typhoon.id) {
                                favoritesViewModel.favorite(track.typhoon.toFavoriteDraft())
                            }
                        },
                        onShare = { text ->
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            runCatching {
                                context.startActivity(
                                    Intent.createChooser(
                                        intent,
                                        context.getString(R.string.share_chooser_title)
                                    )
                                )
                            }
                        },
                        onShareImage = {
                            shareTrackImage(
                                typhoon = track.typhoon,
                                sourceKind = ShareSourceKind.Historical,
                                updatedAtEpochMs = track.fetchedAtEpochMs,
                                fromCache = track.fromCache
                            )
                        }
                    )
                }
                historyDetail.loading -> {
                    DetailLoadingPlaceholder(loading = true, onBack = leaveHistoryDetail)
                }
                else -> {
                    HistoryDetailStatus(
                        phase = historyDetail.phase,
                        onBack = leaveHistoryDetail,
                        footnote = savedFavorite?.let { favorite ->
                            stringResource(
                                R.string.favorites_remote_unavailable_body,
                                favorite.localizedTitle()
                            )
                        }
                    )
                }
            }
        }

        composable(AppDestination.Favorites) {
            BackHandler { navController.popBackStack() }
            FavoritesScreen(
                favorites = favorites,
                onBack = { navController.popBackStack() },
                onOpen = ::openFavorite,
                onUnfavorite = { favorite -> favoritesViewModel.unfavorite(favorite.sourceId) }
            )
        }

        composable(
            route = AppDestination.FavoriteUnavailable,
            arguments = listOf(
                navArgument(AppDestination.ArgStableId) { type = NavType.StringType }
            )
        ) { entry ->
            val stableId = entry.arguments?.getString(AppDestination.ArgStableId).orEmpty()
            val favorite = favorites.firstOrNull { it.stableId == stableId }
            val leave = {
                navController.popBackStack()
                Unit
            }
            BackHandler(onBack = leave)
            FavoriteUnavailableScreen(
                name = favorite?.localizedTitle() ?: stableId,
                onBack = leave,
                onRemove = {
                    favoritesViewModel.unfavorite(favorite?.sourceId ?: stableId)
                    navController.popBackStack()
                }
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
                typhoon != null -> {
                    DetailScreen(
                        typhoon = typhoon,
                        loading = detailLoading,
                        onBack = ::leaveDetail,
                        shareText = viewModel.shareSummary(typhoon),
                        userLocation = userLocation,
                        mapBasemap = settings.mapBasemap,
                        isDemo = dataMode == DataMode.Demo,
                        isFavorite = favorites.containsStorm(typhoon.id),
                        onToggleFavorite = {
                            toggleFavorite(typhoon.id) {
                                favoritesViewModel.favorite(typhoon.toFavoriteDraft())
                            }
                        },
                        onShare = { text ->
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_SUBJECT,
                                    context.getString(R.string.share_subject, typhoon.displayName(context))
                                )
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(
                                Intent.createChooser(
                                    intent,
                                    context.getString(R.string.share_chooser_title)
                                )
                            )
                        },
                        onShareImage = {
                            val live = uiState as? TyphoonUiState.Success
                            shareTrackImage(
                                typhoon = typhoon,
                                sourceKind = if (dataMode == DataMode.Demo) {
                                    ShareSourceKind.Demo
                                } else {
                                    ShareSourceKind.Live
                                },
                                updatedAtEpochMs = lastUpdatedAtMs,
                                fromCache = live?.fromCache == true
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
