package seamain.org.typhoonEye.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import seamain.org.typhoonEye.BuildConfig
import seamain.org.typhoonEye.DistributionConfig
import seamain.org.typhoonEye.R
import seamain.org.typhoonEye.data.location.LocationProvider
import seamain.org.typhoonEye.data.preferences.AppLanguage
import seamain.org.typhoonEye.data.preferences.ThemeMode
import seamain.org.typhoonEye.data.preferences.UserPreferencesRepository
import seamain.org.typhoonEye.data.preferences.UserSettings
import seamain.org.typhoonEye.data.update.AppUpdateRepository
import seamain.org.typhoonEye.domain.model.AppUpdateInfo
import seamain.org.typhoonEye.domain.model.AppUpdateState
import seamain.org.typhoonEye.domain.model.DataSourcesFailedError
import seamain.org.typhoonEye.domain.model.NoDataSourceConfiguredError
import seamain.org.typhoonEye.domain.model.SourceFailure
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.TyphoonDataError
import seamain.org.typhoonEye.domain.model.TyphoonDetailUnavailableError
import seamain.org.typhoonEye.domain.model.TyphoonFeed
import seamain.org.typhoonEye.domain.model.TyphoonPoint
import seamain.org.typhoonEye.domain.model.UserLocation
import seamain.org.typhoonEye.domain.repository.TyphoonRepository
import seamain.org.typhoonEye.domain.repository.WarningRepository
import seamain.org.typhoonEye.domain.util.distanceKmFrom
import seamain.org.typhoonEye.domain.util.roundKm
import seamain.org.typhoonEye.domain.util.typhoonIdsMatch
import seamain.org.typhoonEye.live.TyphoonAlertNotifier
import seamain.org.typhoonEye.live.TyphoonLiveNotifier
import seamain.org.typhoonEye.live.TyphoonLiveUpdateWorker
import seamain.org.typhoonEye.ui.util.IntensityLevel
import seamain.org.typhoonEye.ui.util.MapBasemap
import seamain.org.typhoonEye.ui.util.currentIntensity
import seamain.org.typhoonEye.ui.util.formatObservationTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

sealed class TyphoonUiState {
    data object Loading : TyphoonUiState()
    data class Success(
        val typhoons: List<Typhoon>,
        val fromCache: Boolean = false,
        /** Why live data was unavailable when [fromCache]; localized by the UI. */
        val staleReason: TyphoonDataError? = null
    ) : TyphoonUiState()

    /**
     * This build has no weather API key (e.g. the F-Droid build). Not an error:
     * the UI explains it and offers clearly labeled demo data instead.
     */
    data object NoDataSource : TyphoonUiState()

    /**
     * Configured sources failed and nothing is cached.
     * @param failures per-source typed reasons (localized by the UI)
     * @param detail raw message for unexpected, untyped exceptions only
     */
    data class Error(
        val failures: List<SourceFailure> = emptyList(),
        val detail: String? = null
    ) : TyphoonUiState()
}

/** Maps a failed list load to UI state. Pure, so it is unit-testable. */
internal fun Throwable.toTyphoonUiState(): TyphoonUiState = when (this) {
    is NoDataSourceConfiguredError -> TyphoonUiState.NoDataSource
    is DataSourcesFailedError -> TyphoonUiState.Error(failures = failures)
    is TyphoonDetailUnavailableError -> TyphoonUiState.Error(failures = failures)
    else -> TyphoonUiState.Error(detail = message)
}

enum class DataMode { Live, Demo }

@HiltViewModel
class TyphoonViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: TyphoonRepository,
    private val warningRepository: WarningRepository,
    private val preferences: UserPreferencesRepository,
    private val locationProvider: LocationProvider,
    private val liveNotifier: TyphoonLiveNotifier,
    private val alertNotifier: TyphoonAlertNotifier,
    private val appUpdateRepository: AppUpdateRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<TyphoonUiState>(TyphoonUiState.Loading)
    val uiState: StateFlow<TyphoonUiState> = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _selectedTyphoon = MutableStateFlow<Typhoon?>(null)
    val selectedTyphoon: StateFlow<Typhoon?> = _selectedTyphoon.asStateFlow()

    private val _detailLoading = MutableStateFlow(false)
    val detailLoading: StateFlow<Boolean> = _detailLoading.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _intensityFilter = MutableStateFlow<IntensityLevel?>(null)
    val intensityFilter: StateFlow<IntensityLevel?> = _intensityFilter.asStateFlow()

    private var refreshJob: Job? = null
    private var detailJob: Job? = null

    private val _dataMode = MutableStateFlow(DataMode.Live)
    val dataMode: StateFlow<DataMode> = _dataMode.asStateFlow()

    /** Epoch millis of last successful live/demo refresh; UI formats relatively. */
    private val _lastUpdatedAtMs = MutableStateFlow<Long?>(null)
    val lastUpdatedAtMs: StateFlow<Long?> = _lastUpdatedAtMs.asStateFlow()

    @Deprecated("Use lastUpdatedAtMs", ReplaceWith("lastUpdatedAtMs"))
    val lastUpdated: StateFlow<String?> = _lastUpdatedAtMs
        .map<Long?, String?> { epoch ->
            epoch?.let {
                val formatted = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(java.util.Date(it))
                formatObservationTime(formatted)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _allTyphoons = MutableStateFlow<List<Typhoon>>(emptyList())

    val settings: StateFlow<UserSettings> = preferences.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    val appUpdateState: StateFlow<AppUpdateState> = appUpdateRepository.state

    /** Latest device fix (live) or DataStore cache — used for distance UI + map. */
    private val _liveUserLocation = MutableStateFlow<UserLocation?>(null)
    val userLocation: StateFlow<UserLocation?> = combine(
        settings,
        _liveUserLocation
    ) { prefs, live ->
        live?.takeIf { it.isValid } ?: prefs.cachedUserLocation
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val filteredTyphoons: StateFlow<List<Typhoon>> = combine(
        _allTyphoons,
        _query,
        _intensityFilter
    ) { list, q, filter ->
        list.filter { typhoon ->
            val matchesQuery = q.isBlank() ||
                typhoon.name.contains(q, ignoreCase = true) ||
                typhoon.englishName.contains(q, ignoreCase = true) ||
                typhoon.id.contains(q, ignoreCase = true)
            val matchesFilter = filter == null || typhoon.currentIntensity() == filter
            matchesQuery && matchesFilter
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        loadTyphoons(forceRefresh = false)
        // Quiet daily GitHub Releases check (disabled on F-Droid flavor).
        if (DistributionConfig.enableInAppUpdates(appContext)) {
            viewModelScope.launch {
                runCatching { appUpdateRepository.checkForUpdate(force = false) }
            }
        }
        viewModelScope.launch {
            combine(_allTyphoons, preferences.settings) { typhoons, prefs ->
                typhoons to prefs
            }.collect { (typhoons, prefs) ->
                val active = typhoons.filter { it.status == "active" }
                // Alerts first, then Live — so Live stays out of Alerting aggregate and stays current.
                refreshEmergencyAlerts(active, prefs.emergencyAlertsEnabled)
                liveNotifier.update(liveNotificationTyphoons(active), prefs.liveActivityEnabled)
            }
        }
        // (Re)schedule the worker only when the Live / alert toggles actually change,
        // not on every data or settings emission.
        viewModelScope.launch {
            preferences.settings
                .map { it.liveActivityEnabled || it.emergencyAlertsEnabled }
                .distinctUntilChanged()
                .collect { enabled -> syncBackgroundWorker(enabled) }
        }
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setIntensityFilter(level: IntensityLevel?) {
        _intensityFilter.value = when {
            level == null -> null
            _intensityFilter.value == level -> null
            else -> level
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { preferences.setThemeMode(mode) }
    }

    fun setAppLanguage(language: AppLanguage) {
        viewModelScope.launch { preferences.setAppLanguage(language) }
    }

    fun setMapBasemap(basemap: MapBasemap) {
        viewModelScope.launch { preferences.setMapBasemap(basemap) }
    }

    fun checkForAppUpdate(force: Boolean = true) {
        viewModelScope.launch { appUpdateRepository.checkForUpdate(force = force) }
    }

    fun downloadAppUpdate(info: AppUpdateInfo) {
        viewModelScope.launch { appUpdateRepository.downloadUpdate(info) }
    }

    fun dismissAppUpdate() {
        appUpdateRepository.dismiss()
    }

    fun canInstallAppPackages(): Boolean = appUpdateRepository.canInstallPackages()

    fun appInstallPermissionIntent() = appUpdateRepository.installPermissionSettingsIntent()

    fun appInstallApkIntent(path: String) = appUpdateRepository.installApk(path)

    fun appReleasePageIntent(info: AppUpdateInfo) = appUpdateRepository.openReleasePage(info)

    fun setLiveActivityEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setLiveActivityEnabled(enabled)
            if (enabled) {
                syncBackgroundWorker(true)
                refreshLiveActivity()
            } else {
                liveNotifier.cancel()
                if (!settings.value.emergencyAlertsEnabled) {
                    TyphoonLiveUpdateWorker.cancel(appContext)
                }
            }
        }
    }

    fun setEmergencyAlertsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setEmergencyAlertsEnabled(enabled)
            if (enabled) {
                syncBackgroundWorker(true)
                refreshEmergencyAlerts(
                    _allTyphoons.value.filter { it.status == "active" },
                    enabled = true
                )
            } else {
                alertNotifier.publish(emptyList(), enabled = false)
                if (!settings.value.liveActivityEnabled) {
                    TyphoonLiveUpdateWorker.cancel(appContext)
                }
            }
        }
    }

    fun setLocationAlertsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setLocationAlertsEnabled(enabled)
            if (enabled) {
                refreshUserLocation(force = true)
                if (settings.value.emergencyAlertsEnabled) {
                    refreshEmergencyAlerts(
                        _allTyphoons.value.filter { it.status == "active" },
                        enabled = true
                    )
                }
            }
        }
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setDynamicColorEnabled(enabled) }
    }

    fun refreshLiveActivity() {
        val active = _allTyphoons.value.filter { it.status == "active" }
        liveNotifier.update(liveNotificationTyphoons(active), settings.value.liveActivityEnabled)
    }

    /**
     * Fictional demo storms must never show up as an ongoing system notification
     * in release builds, where they could be mistaken for real typhoons.
     * Debug builds keep the preview for development.
     */
    private fun liveNotificationTyphoons(active: List<Typhoon>): List<Typhoon> =
        if (_dataMode.value == DataMode.Demo && !BuildConfig.DEBUG) emptyList() else active

    fun canPostLiveNotifications(): Boolean = liveNotifier.canPostNotifications()

    fun hasLocationPermission(): Boolean = locationProvider.hasLocationPermission()

    /**
     * Refresh GPS fix when permission is available and location alerts are on.
     * Caches the result for background Worker.
     */
    fun refreshUserLocation(force: Boolean = false) {
        viewModelScope.launch {
            val prefs = settings.value
            if (!prefs.locationAlertsEnabled && !force) return@launch
            if (!locationProvider.hasLocationPermission()) return@launch
            val fix = locationProvider.getLocation() ?: return@launch
            preferences.cacheUserLocation(fix)
            _liveUserLocation.value = fix
        }
    }

    /**
     * Load bundled, clearly labeled sample typhoons so the UI can be explored
     * without any API key (F-Droid build) or offline. Only ever user-initiated.
     *
     * Release builds post no system notifications for demo data (no sample
     * alerts, no Live notification); use Settings → "Send test alert" to
     * preview alert styling. Debug builds keep the full notification preview.
     */
    fun loadDemoData() {
        refreshJob?.cancel()
        viewModelScope.launch {
            val mock = getMockTyphoons()
            // Mode first so the alerts/Live collector never treats sample data as live.
            _dataMode.value = DataMode.Demo
            _allTyphoons.value = mock
            _uiState.value = TyphoonUiState.Success(mock, fromCache = false)
            _lastUpdatedAtMs.value = System.currentTimeMillis()
            _query.value = ""
            _intensityFilter.value = null
            _selectedTyphoon.value = null
            _isRefreshing.value = false
            if (BuildConfig.DEBUG) {
                if (settings.value.emergencyAlertsEnabled) {
                    alertNotifier.publishDemo(warningRepository.demoAlerts())
                }
                // Re-assert Live after alerts so OEM Alerting aggregate cannot suppress it.
                liveNotifier.update(mock.filter { it.status == "active" }, settings.value.liveActivityEnabled)
            } else {
                liveNotifier.update(emptyList(), settings.value.liveActivityEnabled)
            }
        }
    }

    /** Leave demo mode and try live data again (lands on NoDataSource without keys). */
    fun exitDemo() {
        refreshJob?.cancel()
        _dataMode.value = DataMode.Live
        _allTyphoons.value = emptyList()
        _selectedTyphoon.value = null
        _lastUpdatedAtMs.value = null
        _query.value = ""
        _intensityFilter.value = null
        _uiState.value = TyphoonUiState.Loading
        // Normal TTL rules: leaving demo should not force an extra network fetch.
        loadTyphoons(forceRefresh = false)
    }

    /** Settings: push sample emergency alerts for preview. */
    fun sendDemoEmergencyAlerts() {
        viewModelScope.launch {
            alertNotifier.publishDemo(warningRepository.demoAlerts())
        }
    }

    private suspend fun refreshEmergencyAlerts(active: List<Typhoon>, enabled: Boolean) {
        if (!enabled) {
            alertNotifier.publish(emptyList(), enabled = false)
            return
        }
        if (_dataMode.value == DataMode.Demo) {
            // Demo uses explicit publishDemo; avoid re-spam from intensity synthesis.
            return
        }
        val userLocation = resolveUserLocationForAlerts()
        warningRepository.fetchTyphoonAlerts(active, userLocation)
            .onSuccess { alerts -> alertNotifier.publish(alerts, enabled = true) }
            .onFailure { err ->
                android.util.Log.w("TyphoonViewModel", "Alert refresh failed: ${err.message}")
            }
    }

    private suspend fun resolveUserLocationForAlerts(): UserLocation? {
        val prefs = settings.value
        if (!prefs.locationAlertsEnabled) return null
        // Fresh fix when possible; otherwise last cached position for offline/Worker parity.
        if (locationProvider.hasLocationPermission()) {
            locationProvider.getLocation()?.let { fix ->
                preferences.cacheUserLocation(fix)
                _liveUserLocation.value = fix
                return fix
            }
        }
        return userLocation.value
            ?: prefs.cachedUserLocation
            ?: preferences.getCachedUserLocation()
    }

    private fun syncBackgroundWorker(enabled: Boolean) {
        if (enabled) {
            TyphoonLiveUpdateWorker.schedule(appContext)
        } else {
            TyphoonLiveUpdateWorker.cancel(appContext)
        }
    }

    /** Pull-to-refresh / retry: forces a fetch unless the last one was under 60 s ago. */
    fun refresh() {
        loadTyphoons(forceRefresh = true)
    }

    private fun loadTyphoons(forceRefresh: Boolean) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            // Stale-while-revalidate: paint the Room cache first, then let the repository
            // decide (by TTL) whether a network fetch is needed.
            if (_uiState.value !is TyphoonUiState.Success) {
                repository.getCachedFeed()
                    ?.takeIf { it.typhoons.isNotEmpty() }
                    ?.let { applyFeed(it) }
            }
            val hadData = _uiState.value is TyphoonUiState.Success
            _isRefreshing.value = true
            if (!hadData) {
                _uiState.value = TyphoonUiState.Loading
            }

            repository.getActiveTyphoons(forceRefresh = forceRefresh)
                .onSuccess { feed -> applyFeed(feed) }
                .onFailure { error ->
                    if (!hadData) {
                        _uiState.value = error.toTyphoonUiState()
                    }
                }
            _isRefreshing.value = false
        }
    }

    private fun applyFeed(feed: TyphoonFeed) {
        // Mode first so the alerts/Live collector never sees live data tagged as demo.
        _dataMode.value = DataMode.Live
        _allTyphoons.value = feed.typhoons
        _uiState.value = TyphoonUiState.Success(
            typhoons = feed.typhoons,
            fromCache = feed.fromCache,
            staleReason = feed.staleReason
        )
        // Real fetch time, not "now" — cached data must not look freshly fetched.
        _lastUpdatedAtMs.value = feed.fetchedAtEpochMs
        _selectedTyphoon.value?.let { selected ->
            val fromFeed = feed.typhoons.find { typhoonIdsMatch(it.id, selected.id) }
            _selectedTyphoon.value = when {
                // Keep in-memory detail if the list snapshot is thinner.
                fromFeed == null -> selected
                selected.points.size > fromFeed.points.size -> selected
                // Preserve the id the user navigated with (Juhe vs NP_*).
                else -> fromFeed.copy(id = selected.id)
            }
        }
    }

    fun selectTyphoon(typhoon: Typhoon?) {
        _selectedTyphoon.value = typhoon
        if (typhoon != null && _dataMode.value == DataMode.Live && needsDetailFetch(typhoon)) {
            loadDetail(typhoon.id)
        }
    }

    fun selectTyphoonById(id: String) {
        val found = _allTyphoons.value.find { typhoonIdsMatch(it.id, id) }
        if (found != null) {
            // Keep nav/deep-link id so Detail route matching stays stable.
            selectTyphoon(if (found.id == id) found else found.copy(id = id))
        } else {
            // Eager flag so Detail route never flashes an empty/error frame.
            _detailLoading.value = true
            loadDetail(id)
        }
    }

    /** List snapshots are often a single “now” point — still need full track/forecast. */
    private fun needsDetailFetch(typhoon: Typhoon): Boolean {
        if (typhoon.points.size <= 1) return true
        // Sparse history without forecast is usually a list fallback, not a full detail payload.
        if (typhoon.forecastPoints.isEmpty() && typhoon.points.size < 4) return true
        return false
    }

    fun loadDetail(id: String) {
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            _detailLoading.value = true
            try {
                repository.getTyphoonDetail(id)
                    .onSuccess { detail ->
                        // Always keep the id used by navigation / selection.
                        val stable = if (detail.id == id) detail else detail.copy(id = id)
                        _selectedTyphoon.value = stable
                        _allTyphoons.value = _allTyphoons.value.map {
                            if (typhoonIdsMatch(it.id, id) || typhoonIdsMatch(it.id, detail.id)) {
                                stable
                            } else {
                                it
                            }
                        }
                        val state = _uiState.value
                        if (state is TyphoonUiState.Success) {
                            _uiState.value = state.copy(
                                typhoons = state.typhoons.map {
                                    if (typhoonIdsMatch(it.id, id) || typhoonIdsMatch(it.id, detail.id)) {
                                        stable
                                    } else {
                                        it
                                    }
                                }
                            )
                        }
                    }
            } finally {
                _detailLoading.value = false
            }
        }
    }

    fun shareSummary(typhoon: Typhoon): String {
        val last = typhoon.points.lastOrNull()
        return buildString {
            if (_dataMode.value == DataMode.Demo) {
                // Never let a fictional storm be forwarded as if it were real.
                appendLine(appContext.getString(R.string.share_demo_prefix))
            }
            appendLine(
                appContext.getString(R.string.share_header, typhoon.name, typhoon.englishName)
            )
            appendLine(appContext.getString(R.string.share_id, typhoon.id))
            if (typhoon.strong.isNotBlank()) {
                appendLine(appContext.getString(R.string.share_intensity, typhoon.strong))
            }
            if (typhoon.positionDesc.isNotBlank()) {
                appendLine(appContext.getString(R.string.share_position, typhoon.positionDesc))
            }
            typhoon.distanceKmFrom(userLocation.value)?.let { km ->
                appendLine(appContext.getString(R.string.distance_from_you, km.roundKm()))
            }
            if (last != null) {
                appendLine(
                    appContext.getString(
                        R.string.share_wind_pressure,
                        last.speed,
                        last.pressure
                    )
                )
                appendLine(
                    appContext.getString(
                        R.string.share_move,
                        "${last.moveDirection} ${last.moveSpeed}".trim()
                    )
                )
                appendLine(appContext.getString(R.string.share_observed_time, last.time))
            }
            if (typhoon.forecastText.isNotBlank()) {
                appendLine(appContext.getString(R.string.share_forecast, typhoon.forecastText))
            }
        }.trim()
    }

    override fun onCleared() {
        // Keep ongoing Live notification when leaving UI; only cancel via settings.
        super.onCleared()
    }

    private fun nowLabel(): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    /**
     * Fictional sample storms (names/texts localized; intensity and direction
     * values stay in the Juhe data format so the normal label mapping applies).
     */
    private fun getMockTyphoons(): List<Typhoon> = listOf(
        Typhoon(
            id = "202609",
            name = appContext.getString(R.string.demo_bavi_name),
            englishName = "BAVI",
            status = "active",
            strong = "台风",
            positionDesc = appContext.getString(R.string.demo_bavi_position),
            forecastText = appContext.getString(R.string.demo_bavi_forecast),
            startTime = "2026-07-02 08:00",
            endTime = "2026-07-10 14:00",
            points = listOf(
                TyphoonPoint("2026-07-02 08:00", 11.0, 160.1, 998, 18, "8", "热带风暴", "西北偏西", "20"),
                TyphoonPoint("2026-07-04 08:00", 13.3, 153.0, 945, 48, "15", "强台风", "西", "22"),
                TyphoonPoint("2026-07-07 08:00", 16.2, 139.1, 920, 60, "17", "超强台风", "西北", "24"),
                TyphoonPoint("2026-07-10 08:00", 20.5, 128.0, 965, 38, "12", "台风", "西北", "20"),
                TyphoonPoint(
                    time = "2026-07-10 14:00",
                    lat = 21.8,
                    lng = 126.9,
                    pressure = 960,
                    speed = 40,
                    power = "13",
                    strong = "台风",
                    moveDirection = "西北",
                    moveSpeed = "22",
                    radius7 = "280|250|220|260",
                    radius10 = "120|100|90|110",
                    radius12 = "50|40|35|45"
                )
            ),
            forecastPoints = listOf(
                TyphoonPoint("2026-07-11 02:00", 23.0, 125.5, 955, 42, "13", "台风"),
                TyphoonPoint("2026-07-11 14:00", 24.5, 124.0, 950, 45, "14", "强台风"),
                TyphoonPoint("2026-07-12 14:00", 27.0, 122.0, 975, 30, "11", "强热带风暴")
            )
        ),
        Typhoon(
            id = "202610",
            name = appContext.getString(R.string.demo_mekkhala_name),
            englishName = "MEKKHALA",
            status = "active",
            strong = "热带风暴",
            positionDesc = appContext.getString(R.string.demo_mekkhala_position),
            forecastText = appContext.getString(R.string.demo_mekkhala_forecast),
            points = listOf(
                TyphoonPoint("2026-07-09 14:00", 11.2, 137.8, 1000, 16, "8", "热带风暴", "NW", "18"),
                TyphoonPoint(
                    time = "2026-07-10 14:00",
                    lat = 12.5,
                    lng = 135.2,
                    pressure = 998,
                    speed = 18,
                    power = "8",
                    strong = "热带风暴",
                    moveDirection = "NW",
                    moveSpeed = "20",
                    radius7 = "180|160|150|170",
                    radius10 = "60|50|45|55"
                )
            ),
            forecastPoints = listOf(
                TyphoonPoint("2026-07-11 14:00", 13.8, 133.0, 995, 20, "9", "热带风暴")
            )
        ),
        Typhoon(
            id = "202605",
            name = appContext.getString(R.string.demo_hagupit_name),
            englishName = "HAGUPIT",
            status = "dissipated",
            strong = "热带低压",
            points = listOf(
                TyphoonPoint("2026-06-15 08:00", 18.0, 120.0, 1002, 12, "7", "热带低压", "北", "10")
            )
        )
    )
}
