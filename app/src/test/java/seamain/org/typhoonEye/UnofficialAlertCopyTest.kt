package seamain.org.typhoonEye

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import seamain.org.typhoonEye.live.TyphoonAlertNotifier

/**
 * #24: alert copy is an unofficial relay and still points people to the
 * local meteorological authority. Settings row titles stay as they were.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnofficialAlertCopyTest {

    private fun text(qualifiers: String, resId: Int): String {
        RuntimeEnvironment.setQualifiers(qualifiers)
        return RuntimeEnvironment.getApplication().getString(resId)
    }

    @Test
    fun channelId_isUnchanged() {
        assertEquals("typhoon_emergency_alerts_v2", TyphoonAlertNotifier.CHANNEL_ID)
    }

    @Test
    fun defaultAndSimplifiedChinese_matchFinalCopy() {
        val keys = zhHans()
        // `fr` has no overlay, so this resolves the default `values/` copy.
        assertLocale("fr", keys)
        assertLocale("zh-rCN", keys)
    }

    @Test
    fun traditionalChinese_matchesFinalCopy() {
        assertLocale(
            "zh-rTW",
            mapOf(
                R.string.emergency_alerts_on to "非官方轉發：預警訊號與強颱風提示；請以當地氣象部門為準",
                R.string.alert_channel_desc to "非官方轉發的颱風預警訊號與高強度提示；請以當地氣象部門為準",
                R.string.alert_settings_on to "收到轉發的颱風預警或強颱風提示時立即通知（非官方）；請以當地氣象部門為準",
                R.string.location_alerts_on to "依你的位置查詢附近預警與風暴提示（非官方）；請以當地氣象部門為準",
                R.string.location_opt_in_notice_body to
                    "%1\$s 不再預設使用你的位置。依定位推播（距離提醒與你附近的預警資訊，非官方）在你自行開啟前會維持關閉，之後也可以隨時在設定中變更。",
                R.string.alert_channel_name to "颱風緊急預警（非官方）",
                R.string.emergency_alerts_title to "緊急預警",
                R.string.alert_settings_title to "緊急預警通知"
            )
        )
    }

    @Test
    fun cantonese_matchesFinalCopy() {
        assertLocale(
            "b+yue",
            mapOf(
                R.string.emergency_alerts_on to "非官方轉發：預警訊號同強颱風提示；請以當地氣象部門為準",
                R.string.alert_channel_desc to "非官方轉發嘅颱風預警訊號同高強度提示；請以當地氣象部門為準",
                R.string.alert_settings_on to "收到轉發嘅颱風預警或者強颱風提示就即刻通知（非官方）；請以當地氣象部門為準",
                R.string.location_alerts_on to "按你嘅位置查附近預警同風暴提示（非官方）；請以當地氣象部門為準",
                R.string.location_opt_in_notice_body to
                    "%1\$s 而家唔會預設用你嘅位置。按定位推送（距離提醒同你附近嘅預警資訊，非官方）喺你自己開返之前會一直熄咗，之後都可以隨時喺設定度改。",
                R.string.alert_channel_name to "颱風緊急預警（非官方）",
                R.string.emergency_alerts_title to "緊急預警",
                R.string.alert_settings_title to "緊急預警通知"
            )
        )
    }

    @Test
    fun english_matchesFinalCopy() {
        assertLocale(
            "en",
            mapOf(
                R.string.emergency_alerts_on to
                    "Unofficial relay of warning signals and intense-typhoon alerts; always follow your local meteorological authority",
                R.string.alert_channel_desc to
                    "Unofficial relay of typhoon warning signals and high-intensity alerts; follow your local meteorological authority",
                R.string.alert_settings_on to
                    "Notify right away for relayed typhoon warnings or intense-typhoon alerts (unofficial); follow your local meteorological authority",
                R.string.location_alerts_on to
                    "Show nearby warning and storm alerts at your location (unofficial); follow your local meteorological authority",
                R.string.location_opt_in_notice_body to
                    "%1\$s no longer uses your location by default. Location-based alerts (distance alerts and nearby warnings, unofficial) stay off until you turn them on. You can change this any time in Settings.",
                R.string.alert_channel_name to "Typhoon emergency alerts (unofficial)",
                R.string.emergency_alerts_title to "Emergency alerts",
                R.string.alert_settings_title to "Emergency alert notifications"
            )
        )
    }

    private fun zhHans(): Map<Int, String> = mapOf(
        R.string.emergency_alerts_on to "非官方转发：预警信号与强台风提示；请以当地气象部门为准",
        R.string.alert_channel_desc to "非官方转发的台风预警信号与高强度提示；请以当地气象部门为准",
        R.string.alert_settings_on to "收到转发的台风预警或强台风提示时立即通知（非官方）；请以当地气象部门为准",
        R.string.location_alerts_on to "按你的位置查询附近预警与风暴提示（非官方）；请以当地气象部门为准",
        R.string.location_opt_in_notice_body to
            "%1\$s 不再默认使用你的位置。基于定位推送（距离提醒和你附近的预警信息，非官方）在你手动开启前保持关闭，之后也可以随时在设置里更改。",
        R.string.alert_channel_name to "台风紧急预警（非官方）",
        R.string.emergency_alerts_title to "紧急预警",
        R.string.alert_settings_title to "紧急预警通知"
    )

    private fun assertLocale(qualifiers: String, expected: Map<Int, String>) {
        expected.forEach { (resId, value) ->
            assertEquals(qualifiers, value, text(qualifiers, resId))
        }
    }
}
