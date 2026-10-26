package seamain.org.typhoonEye

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import seamain.org.typhoonEye.data.location.LocationProvider
import seamain.org.typhoonEye.data.preferences.UserPreferencesRepository
import seamain.org.typhoonEye.data.update.AppUpdateRepository
import seamain.org.typhoonEye.domain.model.AppUpdateState
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.TyphoonFeed
import seamain.org.typhoonEye.domain.repository.TyphoonRepository
import seamain.org.typhoonEye.domain.repository.WarningRepository
import seamain.org.typhoonEye.live.TyphoonAlertNotifier
import seamain.org.typhoonEye.live.TyphoonLiveNotifier
import seamain.org.typhoonEye.ui.DataMode
import seamain.org.typhoonEye.ui.TyphoonUiState
import seamain.org.typhoonEye.ui.TyphoonViewModel
import java.io.IOException

/**
 * Pull-to-refresh must always drop [TyphoonViewModel.isRefreshing] (#39).
 * Dispatchers.Main is the unconfined test dispatcher so it matches viewModelScope's
 * Main.immediate: a new pull can run its body before the cancelled pull finishes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TyphoonViewModelRefreshTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val viewModelStore = ViewModelStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `successful refresh clears the spinner`() {
        val repository = ControllableTyphoonRepository()
        val viewModel = viewModel(repository)

        assertTrue(viewModel.isRefreshing.value)
        assertFalse(repository.forceFlags.single())

        repository.completeNext(Result.success(feed("202609")))

        assertFalse(viewModel.isRefreshing.value)
        assertEquals("202609", viewModel.successIds().single())
    }

    @Test
    fun `timeout and server errors clear the spinner and allow another pull`() {
        val repository = ControllableTyphoonRepository()
        val viewModel = viewModel(repository)

        repository.completeNext(Result.failure(IOException("timeout")))

        assertFalse(viewModel.isRefreshing.value)
        val error = viewModel.uiState.value as TyphoonUiState.Error
        assertEquals("timeout", error.message)

        viewModel.refresh()
        assertTrue(viewModel.isRefreshing.value)
        assertTrue(repository.forceFlags.last())
        repository.completeNext(Result.success(feed("202610")))

        assertFalse(viewModel.isRefreshing.value)
        assertEquals(listOf("202610"), viewModel.successIds())

        viewModel.refresh()
        repository.completeNext(Result.failure(IOException("503")))

        assertFalse(viewModel.isRefreshing.value)
        assertEquals(listOf("202610"), viewModel.successIds())
    }

    @Test
    fun `cancelling an in-flight refresh clears the spinner`() {
        val repository = ControllableTyphoonRepository()
        val viewModel = viewModel(repository)
        repository.completeNext(Result.success(feed("202609")))

        viewModel.refresh()
        assertTrue(viewModel.isRefreshing.value)

        repository.cancelLatest()

        assertFalse(viewModel.isRefreshing.value)
        assertEquals(listOf("202609"), viewModel.successIds())
    }

    @Test
    fun `rapid re-pull keeps the spinner until the latest request finishes`() {
        val repository = ControllableTyphoonRepository(holdThroughCancellation = true)
        val viewModel = viewModel(repository)
        repository.completeNext(Result.success(feed("initial")))

        viewModel.refresh()
        val firstPull = repository.active
        viewModel.refresh()
        val secondPull = repository.active

        assertTrue(firstPull !== secondPull)
        assertTrue(viewModel.isRefreshing.value)

        firstPull.complete(Result.success(feed("stale")))

        assertTrue(viewModel.isRefreshing.value)
        assertEquals(listOf("initial"), viewModel.successIds())

        secondPull.complete(Result.success(feed("fresh")))

        assertFalse(viewModel.isRefreshing.value)
        assertEquals(listOf("fresh"), viewModel.successIds())
        assertEquals(DataMode.Live, viewModel.dataMode.value)
    }

    @Test
    fun `demo load clears the spinner and ignores a late live response`() {
        val repository = ControllableTyphoonRepository(holdThroughCancellation = true)
        val viewModel = viewModel(repository)
        repository.completeNext(Result.success(feed("initial")))

        viewModel.refresh()
        assertTrue(viewModel.isRefreshing.value)
        val inFlight = repository.active

        viewModel.loadDemoData()

        assertFalse(viewModel.isRefreshing.value)
        assertEquals(DataMode.Demo, viewModel.dataMode.value)

        inFlight.complete(Result.success(feed("late-live")))

        assertFalse(viewModel.isRefreshing.value)
        assertEquals(DataMode.Demo, viewModel.dataMode.value)
        assertFalse(viewModel.successIds().contains("late-live"))
    }

    private fun viewModel(repository: TyphoonRepository): TyphoonViewModel {
        val preferences = mock<UserPreferencesRepository>()
        whenever(preferences.settings).thenReturn(emptyFlow())
        val appUpdateRepository = mock<AppUpdateRepository>()
        whenever(appUpdateRepository.state).thenReturn(MutableStateFlow(AppUpdateState.Idle))
        runBlocking {
            whenever(appUpdateRepository.checkForUpdate(any())).thenReturn(AppUpdateState.Idle)
        }
        val warningRepository = mock<WarningRepository>()
        whenever(warningRepository.demoAlerts()).thenReturn(emptyList())
        // Constructed directly so the test does not need Hilt. The store is cleared in tearDown,
        // which cancels viewModelScope the same way the real owner does.
        val viewModel = TyphoonViewModel(
            appContext = mock(),
            repository = repository,
            warningRepository = warningRepository,
            preferences = preferences,
            locationProvider = mock<LocationProvider>(),
            liveNotifier = mock<TyphoonLiveNotifier>(),
            alertNotifier = mock<TyphoonAlertNotifier>(),
            appUpdateRepository = appUpdateRepository
        )
        viewModelStore.put("refresh-under-test", viewModel)
        return viewModel
    }

    private fun feed(id: String) = TyphoonFeed(
        typhoons = listOf(
            Typhoon(
                id = id,
                name = id,
                englishName = id,
                status = "active"
            )
        ),
        fetchedAtEpochMs = 1_700_000_000_000L
    )

    private fun TyphoonViewModel.successIds(): List<String> {
        val state = uiState.value as TyphoonUiState.Success
        return state.typhoons.map { it.id }
    }

    /**
     * @param holdThroughCancellation when true, a cancelled caller still receives the result
     * (Retrofit-style `catch (e: Exception)` swallowing [kotlinx.coroutines.CancellationException]).
     * That is the race where the older job must not clear the newer spinner.
     */
    private class ControllableTyphoonRepository(
        private val holdThroughCancellation: Boolean = false
    ) : TyphoonRepository {
        val forceFlags = mutableListOf<Boolean>()
        private val requests = mutableListOf<CompletableDeferred<Result<TyphoonFeed>>>()

        val active: CompletableDeferred<Result<TyphoonFeed>>
            get() = requests.last()

        fun completeNext(result: Result<TyphoonFeed>) {
            requests.first { !it.isCompleted }.complete(result)
        }

        fun cancelLatest() {
            requests.last().cancel()
        }

        override suspend fun getCachedFeed(): TyphoonFeed? = null

        override suspend fun getActiveTyphoons(forceRefresh: Boolean): Result<TyphoonFeed> {
            forceFlags += forceRefresh
            val gate = CompletableDeferred<Result<TyphoonFeed>>()
            requests += gate
            if (!holdThroughCancellation) return gate.await()
            // Match Retrofit/OkHttp call sites that catch Exception and therefore swallow
            // cancellation, then still deliver a body. The older job must not touch the spinner.
            return try {
                withContext(NonCancellable) { gate.await() }
            } catch (cancelled: CancellationException) {
                if (gate.isCompleted && !gate.isCancelled) gate.getCompleted() else throw cancelled
            }
        }

        override suspend fun getTyphoonDetail(id: String): Result<Typhoon> =
            Result.failure(IllegalStateException("unused"))
    }
}
