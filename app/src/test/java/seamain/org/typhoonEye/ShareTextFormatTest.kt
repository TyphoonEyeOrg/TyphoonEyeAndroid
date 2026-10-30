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
import seamain.org.typhoonEye.domain.model.NoDataSourceConfiguredError
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.TyphoonFeed
import seamain.org.typhoonEye.domain.model.TyphoonPoint
import seamain.org.typhoonEye.domain.repository.TyphoonRepository
import seamain.org.typhoonEye.domain.repository.WarningRepository
import seamain.org.typhoonEye.live.TyphoonAlertNotifier
import seamain.org.typhoonEye.live.TyphoonLiveNotifier
import seamain.org.typhoonEye.ui.TyphoonViewModel
import seamain.org.typhoonEye.ui.util.formatObservationTime
import seamain.org.typhoonEye.ui.util.moveLabel
import java.util.Locale
import java.util.TimeZone

/**
 * Issue #16: share text uses [moveLabel] (locale unit) and [formatObservationTime]
 * (device zone). `en` is the default `values/` catalog; there is no `values-en`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class ShareTextFormatTest {

    private val observedRaw = "1999-07-11T09:00+08:00"
    private val point = TyphoonPoint(
        time = observedRaw,
        lat = 21.8,
        lng = 126.9,
        pressure = 960,
        speed = 40,
        power = "13",
        moveDirection = "WNW",
        moveSpeed = "24"
    )
    private val typhoon = Typhoon(
        id = "202609",
        name = "Bavi",
        englishName = "BAVI",
        status = "active",
        points = listOf(point)
    )

    private val originalLocale = Locale.getDefault()
    private val originalZone = TimeZone.getDefault()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        Locale.setDefault(originalLocale)
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun shareSummary_formatsMoveAndLocalTime_inAllFourLocales() {
        val vm = viewModel()
        val cases = listOf(
            LocaleCase(
                qualifiers = "en",
                locale = Locale.ENGLISH,
                move = "WNW · 24 km/h",
                observed = "1999-07-10 18:00"
            ),
            LocaleCase(
                qualifiers = "zh-rCN",
                locale = Locale.SIMPLIFIED_CHINESE,
                move = "西北偏西 · 24 公里/小时",
                observed = "1999年7月10日 18:00"
            ),
            LocaleCase(
                qualifiers = "zh-rTW",
                locale = Locale.TRADITIONAL_CHINESE,
                move = "西西北 · 24 公里/小時",
                observed = "1999年7月10日 18:00"
            ),
            LocaleCase(
                qualifiers = "b+yue",
                locale = Locale.forLanguageTag("yue"),
                move = "西西北 · 24 公里/小時",
                observed = "1999年7月10日 18:00"
            )
        )

        cases.forEach { spec ->
            RuntimeEnvironment.setQualifiers(spec.qualifiers)
            Locale.setDefault(spec.locale)
            val context = RuntimeEnvironment.getApplication()

            assertEquals(spec.qualifiers, spec.move, point.moveLabel(context))
            assertEquals(spec.qualifiers, spec.observed, formatObservationTime(point.time, context))

            val summary = vm.shareSummary(typhoon)
            assertTrue(
                spec.qualifiers,
                summary.contains(context.getString(R.string.share_move, spec.move))
            )
            assertTrue(
                spec.qualifiers,
                summary.contains(context.getString(R.string.share_observed_time, spec.observed))
            )
            assertFalse(summary.contains(observedRaw))
            assertFalse(summary.contains("+08:00"))
            assertFalse(summary.contains("T09:00"))
        }
    }

    private fun viewModel(): TyphoonViewModel {
        val repository = object : TyphoonRepository {
            override suspend fun getActiveTyphoons(forceRefresh: Boolean) =
                Result.failure<TyphoonFeed>(NoDataSourceConfiguredError())
            override suspend fun getCachedFeed(): TyphoonFeed? = null
            override suspend fun getTyphoonDetail(id: String) =
                Result.failure<Typhoon>(NoDataSourceConfiguredError())
        }
        val preferences: UserPreferencesRepository = mock()
        val updates: AppUpdateRepository = mock()
        whenever(preferences.settings).thenReturn(emptyFlow())
        whenever(updates.state).thenReturn(MutableStateFlow(AppUpdateState.Idle))
        return TyphoonViewModel(
            appContext = RuntimeEnvironment.getApplication(),
            repository = repository,
            warningRepository = mock<WarningRepository>().also {
                whenever(it.demoAlerts()).thenReturn(emptyList())
            },
            preferences = preferences,
            locationProvider = mock<LocationProvider>(),
            liveNotifier = mock<TyphoonLiveNotifier>(),
            alertNotifier = mock<TyphoonAlertNotifier>(),
            appUpdateRepository = updates
        )
    }

    private data class LocaleCase(
        val qualifiers: String,
        val locale: Locale,
        val move: String,
        val observed: String
    )
}
