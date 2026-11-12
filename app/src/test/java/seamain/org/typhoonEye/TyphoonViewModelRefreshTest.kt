package seamain.org.typhoonEye

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import seamain.org.typhoonEye.domain.model.DataSourcesFailedError
import seamain.org.typhoonEye.domain.model.NoDataSourceConfiguredError
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
 * Pull-to-refresh must show [TyphoonViewModel.isRefreshing] immediately, keep it until the
 * current fetch ends, and must not restart a user fetch that is already running. A pull that
 * replaces the automatic load must ignore that load's late result.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class TyphoonViewModelRefreshTest {

    private class ScriptedRepository : TyphoonRepository {
        var behavior: suspend () -> Result<TyphoonFeed> = {
            Result.failure(NoDataSourceConfiguredError())
        }
        var cached: TyphoonFeed? = null
        val forceRefreshCalls = mutableListOf<Boolean>()

        override suspend fun getActiveTyphoons(forceRefresh: Boolean): Result<TyphoonFeed> {
            forceRefreshCalls += forceRefresh
            return behavior()
        }
        override suspend fun getCachedFeed(): TyphoonFeed? = cached
        override suspend fun getTyphoonDetail(id: String): Result<Typhoon> =
            Result.failure(NoDataSourceConfiguredError())
    }

    private val repository = ScriptedRepository()
    private val preferences: UserPreferencesRepository = mock()
    private val warningRepository: WarningRepository = mock()
    private val appUpdateRepository: AppUpdateRepository = mock()
    private val storm = Typhoon(id = "202609", name = "巴威", englishName = "BAVI", status = "active")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        whenever(preferences.settings).thenReturn(emptyFlow())
        whenever(warningRepository.demoAlerts()).thenReturn(emptyList())
        whenever(appUpdateRepository.state).thenReturn(MutableStateFlow(AppUpdateState.Idle))
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
    fun success_clearsRefreshingAndPublishesFeed() {
        val gate = CompletableDeferred<Result<TyphoonFeed>>()
        repository.behavior = { gate.await() }

        val vm = viewModel()

        assertTrue(vm.isRefreshing.value)
        assertTrue(vm.uiState.value is TyphoonUiState.Loading)

        gate.complete(Result.success(TyphoonFeed(listOf(storm), fetchedAtEpochMs = 5L)))

        assertFalse(vm.isRefreshing.value)
        val state = vm.uiState.value as TyphoonUiState.Success
        assertEquals(listOf(storm), state.typhoons)
        assertEquals(5L, vm.lastUpdatedAtMs.value)
    }

    @Test
    fun sourceFailure_clearsRefreshingAndShowsError() {
        repository.behavior = { Result.failure(DataSourcesFailedError(emptyList())) }

        val vm = viewModel()

        assertFalse(vm.isRefreshing.value)
        assertTrue(vm.uiState.value is TyphoonUiState.Error)
    }

    @Test
    fun thrownTimeout_clearsRefreshingAndShowsError() {
        repository.behavior = { throw RuntimeException("timeout") }

        val vm = viewModel()

        assertFalse(vm.isRefreshing.value)
        val state = vm.uiState.value as TyphoonUiState.Error
        assertEquals("timeout", state.detail)
    }

    @Test
    fun failureWithDataOnScreen_clearsRefreshingAndKeepsList() {
        var calls = 0
        repository.behavior = {
            when (++calls) {
                1 -> Result.success(TyphoonFeed(listOf(storm), fetchedAtEpochMs = 1L))
                else -> Result.failure(DataSourcesFailedError(emptyList()))
            }
        }
        val vm = viewModel()

        vm.refresh()

        assertFalse(vm.isRefreshing.value)
        val state = vm.uiState.value as TyphoonUiState.Success
        assertEquals(listOf(storm), state.typhoons)
    }

    @Test
    fun cancelMidRefresh_clearsRefreshing() {
        val gate = CompletableDeferred<Result<TyphoonFeed>>()
        repository.behavior = { gate.await() }
        val vm = viewModel()
        assertTrue(vm.isRefreshing.value)

        vm.viewModelScope.coroutineContext.cancelChildren()

        assertFalse(vm.isRefreshing.value)
    }

    @Test
    fun loadDemoDuringRefresh_clearsRefreshingAndKeepsDemo() {
        val gate = CompletableDeferred<Result<TyphoonFeed>>()
        repository.behavior = { gate.await() }
        val vm = viewModel()
        assertTrue(vm.isRefreshing.value)

        vm.loadDemoData()

        assertFalse(vm.isRefreshing.value)
        assertEquals(DataMode.Demo, vm.dataMode.value)
        assertTrue(vm.uiState.value is TyphoonUiState.Success)
        // A late result from the cancelled load must not kick the user out of demo.
        gate.complete(Result.success(TyphoonFeed(listOf(storm), fetchedAtEpochMs = 9L)))
        assertEquals(DataMode.Demo, vm.dataMode.value)
        assertFalse(vm.isRefreshing.value)
    }

    @Test
    fun refresh_setsRefreshingBeforeTheLoadIsDispatched() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        repository.behavior = {
            Result.success(TyphoonFeed(listOf(storm), fetchedAtEpochMs = 1L))
        }
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.isRefreshing.value)

        val gate = CompletableDeferred<Result<TyphoonFeed>>()
        repository.behavior = { gate.await() }
        vm.refresh()

        assertTrue(vm.isRefreshing.value)
        dispatcher.scheduler.runCurrent()
        assertTrue(vm.isRefreshing.value)
        assertEquals(listOf(false, true), repository.forceRefreshCalls)

        gate.complete(Result.success(TyphoonFeed(listOf(storm), fetchedAtEpochMs = 2L)))
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.isRefreshing.value)
        assertEquals(2L, vm.lastUpdatedAtMs.value)
    }

    @Test
    fun secondPull_keepsTheInFlightUserFetch() {
        val gate = CompletableDeferred<Result<TyphoonFeed>>()
        var calls = 0
        repository.behavior = {
            calls++
            gate.await()
        }
        val vm = viewModel()
        assertEquals(1, calls)
        assertTrue(vm.isRefreshing.value)

        vm.refresh()
        assertEquals(2, calls)
        assertEquals(listOf(false, true), repository.forceRefreshCalls)
        assertTrue(vm.isRefreshing.value)

        vm.refresh()
        assertEquals(2, calls)
        assertTrue(vm.isRefreshing.value)

        gate.complete(Result.success(TyphoonFeed(listOf(storm), fetchedAtEpochMs = 8L)))

        assertFalse(vm.isRefreshing.value)
        val state = vm.uiState.value as TyphoonUiState.Success
        assertEquals("202609", state.typhoons.single().id)
        assertEquals(8L, vm.lastUpdatedAtMs.value)
    }

    @Test
    fun pullDuringAutomaticLoad_ignoresTheCancelledResult() {
        val first = CompletableDeferred<Result<TyphoonFeed>>()
        val second = CompletableDeferred<Result<TyphoonFeed>>()
        var calls = 0
        repository.behavior = {
            when (++calls) {
                1 -> first.await()
                else -> second.await()
            }
        }
        val vm = viewModel()
        assertEquals(listOf(false), repository.forceRefreshCalls)
        assertTrue(vm.isRefreshing.value)

        vm.refresh()

        assertEquals(listOf(false, true), repository.forceRefreshCalls)
        assertTrue(vm.isRefreshing.value)
        first.complete(Result.failure(DataSourcesFailedError(emptyList())))
        assertTrue(vm.isRefreshing.value)
        assertTrue(vm.uiState.value is TyphoonUiState.Loading)

        val newer = Typhoon(id = "202610", name = "美莎克", englishName = "MAYSAK", status = "active")
        second.complete(Result.success(TyphoonFeed(listOf(newer), fetchedAtEpochMs = 11L)))

        assertFalse(vm.isRefreshing.value)
        val state = vm.uiState.value as TyphoonUiState.Success
        assertEquals(listOf(newer), state.typhoons)
        assertEquals(11L, vm.lastUpdatedAtMs.value)
    }

    @Test
    fun refreshAfterThePreviousPullFinished_requestsAgain() {
        repository.behavior = {
            Result.success(TyphoonFeed(listOf(storm), fetchedAtEpochMs = 1L))
        }
        val vm = viewModel()
        assertFalse(vm.isRefreshing.value)

        val gate = CompletableDeferred<Result<TyphoonFeed>>()
        repository.behavior = { gate.await() }
        vm.refresh()
        assertTrue(vm.isRefreshing.value)
        assertEquals(listOf(false, true), repository.forceRefreshCalls)

        gate.complete(Result.success(TyphoonFeed(listOf(storm), fetchedAtEpochMs = 4L)))

        assertFalse(vm.isRefreshing.value)
        assertEquals(4L, vm.lastUpdatedAtMs.value)
    }
}
