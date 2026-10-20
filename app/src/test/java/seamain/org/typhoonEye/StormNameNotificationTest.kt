package seamain.org.typhoonEye

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import seamain.org.typhoonEye.data.api.DataSourceConfig
import seamain.org.typhoonEye.data.preferences.AppLanguage
import seamain.org.typhoonEye.data.repository.DefaultWarningRepository
import seamain.org.typhoonEye.domain.util.WallClock
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.TyphoonPoint
import seamain.org.typhoonEye.domain.names.NameColumn
import seamain.org.typhoonEye.domain.names.StormNames
import seamain.org.typhoonEye.live.TyphoonLiveNotifier

/** #19: background notifications pick the name column from the saved app language. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StormNameNotificationTest {

    private fun englishSystemContext(): Context =
        RuntimeEnvironment.setQualifiers("en-rUS").let { RuntimeEnvironment.getApplication() }

    private val halong = Typhoon(
        id = "202618", name = "夏浪", englishName = "HALONG", status = "active", strong = "强台风",
        points = listOf(TyphoonPoint(time = "2026-10-20 08:00", lat = 20.0, lng = 125.0, pressure = 940, speed = 50,
            power = "15", strong = "强台风"))
    )

    @Test fun preference_cantonese_over_english_system_uses_hko() =
        assertEquals(NameColumn.ZhHK, StormNames.columnForPreference(AppLanguage.Yue, systemTag = "en-US"))

    @Test fun preference_follow_system_uses_system_locale() {
        assertEquals(NameColumn.English, StormNames.columnForPreference(AppLanguage.System, "en-US"))
        assertEquals(NameColumn.ZhHK, StormNames.columnForPreference(AppLanguage.System, "zh-Hant-HK"))
        assertEquals(NameColumn.ZhHans, StormNames.columnForPreference(AppLanguage.System, "zh-Hans-HK"))
        assertEquals(NameColumn.ZhHantTW, StormNames.columnForPreference(AppLanguage.ZhHant, "en-US"))
    }

    @Test fun live_notification_title_english_system_cantonese_pref_is_hko_name() {
        val ctx = englishSystemContext()
        val column = StormNames.columnForPreference(AppLanguage.Yue, systemTag = "en-US")
        assertEquals("夏浪", TyphoonLiveNotifier(ctx).liveTitle(halong, column))
        val tw = StormNames.columnForPreference(AppLanguage.ZhHant, systemTag = "en-US")
        assertEquals("哈隆", TyphoonLiveNotifier(ctx).liveTitle(halong, tw))
    }

    @Test fun intensity_alert_title_english_system_cantonese_pref_is_hko_name() = runTest {
        val ctx = englishSystemContext()
        val repo = DefaultWarningRepository(
            appContext = ctx, warningApi = mock(), relayAlertsApi = mock(),
            config = DataSourceConfig(juheKey = "k"), clock = WallClock { 0L }
        )
        val hk = repo.fetchTyphoonAlerts(listOf(halong.copy(name = "米娜", englishName = "MITAG")), null,
            StormNames.columnForPreference(AppLanguage.Yue, "en-US")).getOrThrow()
        assertTrue(hk.isNotEmpty())
        assertTrue(hk.first().title, hk.first().title.contains("米娜"))
        val tw = repo.fetchTyphoonAlerts(listOf(halong.copy(name = "米娜", englishName = "MITAG")), null,
            StormNames.columnForPreference(AppLanguage.ZhHant, "en-US")).getOrThrow()
        assertTrue(tw.first().title, tw.first().title.contains("米塔"))
    }
}
