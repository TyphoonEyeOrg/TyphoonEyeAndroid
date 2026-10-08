package seamain.org.typhoonEye

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import seamain.org.typhoonEye.data.location.LocationProvider
import seamain.org.typhoonEye.data.preferences.UserPreferencesRepository
import seamain.org.typhoonEye.data.update.AppUpdateRepository
import seamain.org.typhoonEye.domain.model.AppUpdateState
import seamain.org.typhoonEye.domain.model.DataSource
import seamain.org.typhoonEye.domain.model.DataSourcesFailedError
import seamain.org.typhoonEye.domain.model.NoDataSourceConfiguredError
import seamain.org.typhoonEye.domain.model.SourceFailure
import seamain.org.typhoonEye.domain.model.SourceFailureKind
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.TyphoonFeed
import seamain.org.typhoonEye.domain.repository.TyphoonRepository
import seamain.org.typhoonEye.domain.repository.WarningRepository
import seamain.org.typhoonEye.live.TyphoonAlertNotifier
import seamain.org.typhoonEye.live.TyphoonLiveNotifier
import seamain.org.typhoonEye.ui.DataMode
import seamain.org.typhoonEye.ui.TyphoonUiState
import seamain.org.typhoonEye.ui.TyphoonViewModel

/**
 * F-Droid review !45561: a build without API keys must land on the dedicated
 * NoDataSource state, and demo data is only loaded when the user asks for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class TyphoonViewModelDataSourceTest {

    /** Hand-written fake: Result is a value class, which Mockito stubs unreliably for suspend funs. */
    private class FakeRepository : TyphoonRepository {
        var active: Result<TyphoonFeed> = Result.failure(NoDataSourceConfiguredError())
        var cached: TyphoonFeed? = null
        val forceRefreshCalls = mutableListOf<Boolean>()
        override suspend fun getActiveTyphoons(forceRefresh: Boolean): Result<TyphoonFeed> {
            forceRefreshCalls += forceRefresh
            return active
        }
        override suspend fun getCachedFeed(): TyphoonFeed? = cached
        override suspend fun getTyphoonDetail(id: String): Result<Typhoon> =
            Result.failure(NoDataSourceConfiguredError())
    }

    private val repository = FakeRepository()
    private val warningRepository: WarningRepository = mock()
    private val preferences: UserPreferencesRepository = mock()
    private val appUpdateRepository: AppUpdateRepository = mock()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // Never emits: keeps the alerts / Live / WorkManager collector idle in JVM tests.
        whenever(preferences.settings).thenReturn(emptyFlow())
        whenever(appUpdateRepository.state).thenReturn(MutableStateFlow(AppUpdateState.Idle))
        whenever(warningRepository.demoAlerts()).thenReturn(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = TyphoonViewModel(
        appContext = RuntimeEnvironment.getApplication(),
        repository = repository,
        warningRepository = warningRepository,
        preferences = preferences,
        locationProvider = mock<LocationProvider>(),
        liveNotifier = mock<TyphoonLiveNotifier>(),
        alertNotifier = mock<TyphoonAlertNotifier>(),
        appUpdateRepository = appUpdateRepository
    )

    @Test
    fun noDataSource_mapsToNoDataSourceState_withoutAutoLoadingDemo() {
        repository.active = Result.failure(NoDataSourceConfiguredError())

        val vm = viewModel()

        assertEquals(TyphoonUiState.NoDataSource, vm.uiState.value)
        assertEquals(DataMode.Live, vm.dataMode.value)
    }

    @Test
    fun noDataSource_viewDemo_thenExitDemo_returnsToNoDataSource() {
        repository.active = Result.failure(NoDataSourceConfiguredError())
        val vm = viewModel()

        vm.loadDemoData()
        val demo = vm.uiState.value
        assertTrue(demo is TyphoonUiState.Success)
        assertTrue((demo as TyphoonUiState.Success).typhoons.isNotEmpty())
        assertEquals(DataMode.Demo, vm.dataMode.value)
        // Demo share text is explicitly marked as sample data.
        assertTrue(vm.shareSummary(demo.typhoons.first()).startsWith("[Sample data"))

        vm.exitDemo()
        assertEquals(TyphoonUiState.NoDataSource, vm.uiState.value)
        assertEquals(DataMode.Live, vm.dataMode.value)
        // Leaving demo respects the list TTL instead of forcing a network fetch.
        assertEquals(false, repository.forceRefreshCalls.last())
    }

    @Test
    fun liveFeed_reportsRealFetchTime_andLiveMode() {
        val fetchedAt = 1_752_130_800_000L
        repository.active = Result.success(TyphoonFeed(typhoons = emptyList(), fetchedAtEpochMs = fetchedAt))

        val vm = viewModel()

        assertEquals(TyphoonUiState.Success(typhoons = emptyList()), vm.uiState.value)
        assertEquals(fetchedAt, vm.lastUpdatedAtMs.value)
        assertEquals(DataMode.Live, vm.dataMode.value)
    }

    @Test
    fun noDataSource_withLeftoverCache_showsCacheWithTypedStaleReason() {
        val reason = NoDataSourceConfiguredError()
        val cachedTyphoon = Typhoon(id = "202609", name = "Bavi", englishName = "BAVI", status = "active")
        repository.active = Result.success(
            TyphoonFeed(listOf(cachedTyphoon), fromCache = true, staleReason = reason, fetchedAtEpochMs = 1L)
        )

        val vm = viewModel()

        val state = vm.uiState.value as TyphoonUiState.Success
        assertTrue(state.fromCache)
        assertEquals(reason, state.staleReason)
        assertEquals(1L, vm.lastUpdatedAtMs.value)
    }

    @Test
    fun pullToRefreshInDemoWithoutKeys_staysInDemo() {
        repository.active = Result.failure(NoDataSourceConfiguredError())
        val vm = viewModel()
        vm.loadDemoData()

        vm.refresh()

        assertTrue(vm.uiState.value is TyphoonUiState.Success)
        assertEquals(DataMode.Demo, vm.dataMode.value)
    }

    @Test
    fun realSourceFailure_mapsToErrorWithTypedFailures() {
        val failures = listOf(SourceFailure(DataSource.QWeather, SourceFailureKind.InvalidKey, code = "401"))
        repository.active = Result.failure(DataSourcesFailedError(failures))

        val vm = viewModel()

        assertEquals(TyphoonUiState.Error(failures = failures), vm.uiState.value)
    }
}
