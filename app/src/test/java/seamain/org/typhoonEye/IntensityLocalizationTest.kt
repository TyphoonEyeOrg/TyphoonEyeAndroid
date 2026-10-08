package seamain.org.typhoonEye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import seamain.org.typhoonEye.data.model.QWeatherStormInfo
import seamain.org.typhoonEye.data.model.qWeatherTypeToStrong
import seamain.org.typhoonEye.data.model.toDomain
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.TyphoonPoint
import seamain.org.typhoonEye.ui.util.IntensityLevel
import seamain.org.typhoonEye.ui.util.displayIntensity
import seamain.org.typhoonEye.ui.util.intensityLevelForLabel
import seamain.org.typhoonEye.ui.util.listSubtitle
import seamain.org.typhoonEye.ui.util.localizeIntensityLabel

/**
 * F-Droid !45561 tester: English UI showed 强热带风暴 / 台风 in History and Forecast,
 * and QWeather list cards showed "NP_2629 · NP_2629".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IntensityLocalizationTest {

    private fun ctx(qualifiers: String) = RuntimeEnvironment.setQualifiers(qualifiers)
        .let { RuntimeEnvironment.getApplication() }

    private fun point(strong: String, power: String = "") =
        TyphoonPoint("2026-10-08 08:00", 20.0, 130.0, 980, 30, power, strong)

    @Test
    fun everyProviderLabel_mapsToALevel() {
        val expected = mapOf(
            "TD" to IntensityLevel.TD, "TS" to IntensityLevel.TS, "STS" to IntensityLevel.STS,
            "TY" to IntensityLevel.TY, "STY" to IntensityLevel.STY, "SuperTY" to IntensityLevel.SUPER
        )
        expected.forEach { (code, level) ->
            assertEquals(code, level, intensityLevelForLabel(qWeatherTypeToStrong(code)))
        }
        assertEquals(IntensityLevel.STS, intensityLevelForLabel("強烈熱帶風暴"))
        assertNull(intensityLevelForLabel(""))
        assertNull(intensityLevelForLabel("温带气旋"))
    }

    @Test
    fun englishUi_showsEnglishIntensity() {
        val c = ctx("en-rUS")
        assertEquals("Severe Tropical Storm", point("强热带风暴").displayIntensity(c))
        assertEquals("Typhoon", point("台风").displayIntensity(c))
        assertEquals("Typhoon", localizeIntensityLabel(c, "台风"))
    }

    @Test
    fun chineseUi_followsLocale() {
        assertEquals("强热带风暴", point("强热带风暴").displayIntensity(ctx("zh-rCN")))
        assertEquals("強烈熱帶風暴", point("强热带风暴").displayIntensity(ctx("zh-rTW")))
    }

    @Test
    fun unknownLabel_isShownAsIs_andBlankFallsBackToPower() {
        val c = ctx("en-rUS")
        assertEquals("温带气旋", point("温带气旋").displayIntensity(c))
        assertEquals("—", point("").displayIntensity(c))
    }

    @Test
    fun listSubtitle_showsIdOnce() {
        val qweather = Typhoon(id = "NP_2629", name = "小熊", englishName = "NP_2629", status = "active")
        assertEquals("NP_2629", qweather.listSubtitle())
        val juhe = Typhoon(id = "202629", name = "小熊", englishName = "Halong", status = "active")
        assertEquals("Halong · 202629", juhe.listSubtitle())
        val sameName = Typhoon(id = "202630", name = "Nolo", englishName = "nolo", status = "active")
        assertEquals("202630", sameName.listSubtitle())
    }

    @Test
    fun qweatherMapper_leavesEnglishNameBlank() {
        val t = QWeatherStormInfo(id = "NP_2629", name = "小熊", isActive = "1")
            .toDomain(track = emptyList(), now = null, forecast = emptyList())
        assertEquals("", t.englishName)
        assertEquals("NP_2629", t.listSubtitle())
    }
}
