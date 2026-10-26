package seamain.org.typhoonEye

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
 * Issue #39: pull-to-refresh must always drop [TyphoonViewModel.isRefreshing], including when a
 * newer pull cancels the previous job. The superseded job must not clear the spinner or publish
 * its result after the newer job has taken over.
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

        override suspend fun getActiveTyphoons(forceRefresh: Boolean): Result<TyphoonFeed> = behavior()
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
    fun secondPull_keepsSpinnerUntilTheNewerLoadFinishes() {
        val gates = ArrayDeque<CompletableDeferred<Result<TyphoonFeed>>>()
        repository.behavior = {
            val gate = CompletableDeferred<Result<TyphoonFeed>>()
            gates.addLast(gate)
            gate.await()
        }
        val vm = viewModel()
        assertEquals(1, gates.size)
        assertTrue(vm.isRefreshing.value)

        vm.refresh()

        assertEquals(2, gates.size)
        assertTrue(vm.isRefreshing.value)

        gates.last().complete(Result.success(TyphoonFeed(listOf(storm), fetchedAtEpochMs = 8L)))

        assertFalse(vm.isRefreshing.value)
        val state = vm.uiState.value as TyphoonUiState.Success
        assertEquals("202609", state.typhoons.single().id)
        assertEquals(8L, vm.lastUpdatedAtMs.value)
    }

    @Test
    fun supersededRefresh_doesNotClearSpinnerOrPublishItsFailure() {
        val newer = CompletableDeferred<Result<TyphoonFeed>>()
        var calls = 0
        lateinit var vm: TyphoonViewModel
        repository.behavior = {
            when (++calls) {
                1 -> Result.failure(DataSourcesFailedError(emptyList()))
                2 -> {
                    // Still inside the first pull when the user pulls again. The first call
                    // then returns a failure; that stale failure must not win.
                    vm.refresh()
                    Result.failure(DataSourcesFailedError(emptyList()))
                }
                else -> newer.await()
            }
        }
        vm = viewModel()
        assertFalse(vm.isRefreshing.value)
        assertTrue(vm.uiState.value is TyphoonUiState.Error)

        vm.refresh()

        assertEquals(3, calls)
        assertTrue(vm.isRefreshing.value)
        assertTrue(vm.uiState.value is TyphoonUiState.Loading)

        newer.complete(Result.success(TyphoonFeed(listOf(storm), fetchedAtEpochMs = 11L)))

        assertFalse(vm.isRefreshing.value)
        val state = vm.uiState.value as TyphoonUiState.Success
        assertEquals(listOf(storm), state.typhoons)
    }
}
