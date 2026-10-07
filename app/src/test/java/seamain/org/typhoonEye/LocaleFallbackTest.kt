package seamain.org.typhoonEye

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * F-Droid review !45561: a German device on "Follow system" showed Chinese.
 * Default resources are now English; Simplified Chinese lives in values-zh-rCN
 * and must still serve zh / zh-SG via script-based matching.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocaleFallbackTest {

    private fun appNameFor(qualifiers: String): String {
        RuntimeEnvironment.setQualifiers(qualifiers)
        return RuntimeEnvironment.getApplication().getString(R.string.app_name)
    }

    @Test
    fun unsupportedLanguages_fallBackToEnglish() {
        assertEquals("Typhoon Eye", appNameFor("de-rDE"))
        assertEquals("Typhoon Eye", appNameFor("fr"))
        assertEquals("Typhoon Eye", appNameFor("ja-rJP"))
    }

    @Test
    fun english_isEnglish() {
        assertEquals("Typhoon Eye", appNameFor("en-rUS"))
        assertEquals("Typhoon Eye", appNameFor("en-rGB"))
    }

    @Test
    fun simplifiedChinese_coversCnSgAndBareZh() {
        assertEquals("台风眼", appNameFor("zh-rCN"))
        assertEquals("台风眼", appNameFor("zh-rSG"))
        assertEquals("台风眼", appNameFor("zh"))
    }

    @Test
    fun traditionalChineseAndCantonese_unchanged() {
        assertEquals("颱風眼", appNameFor("zh-rTW"))
        assertEquals("颱風眼", appNameFor("b+yue"))
    }
}
