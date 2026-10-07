package seamain.org.typhoonEye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import seamain.org.typhoonEye.data.preferences.AppLanguage

class AppLanguageTest {

    @Test
    fun `unsupported system languages resolve to English, not Simplified Chinese`() {
        assertEquals(AppLanguage.English, AppLanguage.resolveSupported("de-DE"))
        assertEquals(AppLanguage.English, AppLanguage.resolveSupported("fr"))
        assertEquals(AppLanguage.English, AppLanguage.resolveSupported("ja-JP"))
        assertEquals(AppLanguage.English, AppLanguage.resolveSupported(null))
        assertNull(AppLanguage.fromLocaleTags("de-DE"))
    }

    @Test
    fun `chinese variants keep their mapping`() {
        assertEquals(AppLanguage.ZhHans, AppLanguage.resolveSupported("zh-CN"))
        assertEquals(AppLanguage.ZhHans, AppLanguage.resolveSupported("zh-SG"))
        assertEquals(AppLanguage.ZhHans, AppLanguage.resolveSupported("zh"))
        assertEquals(AppLanguage.ZhHant, AppLanguage.resolveSupported("zh-TW"))
        assertEquals(AppLanguage.ZhHant, AppLanguage.resolveSupported("zh-Hant-MO"))
        assertEquals(AppLanguage.Yue, AppLanguage.resolveSupported("zh-HK"))
        assertEquals(AppLanguage.Yue, AppLanguage.resolveSupported("yue"))
        assertEquals(AppLanguage.English, AppLanguage.resolveSupported("en-GB"))
    }
}
