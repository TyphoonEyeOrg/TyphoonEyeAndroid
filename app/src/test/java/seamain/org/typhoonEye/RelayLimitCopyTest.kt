package seamain.org.typhoonEye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import seamain.org.typhoonEye.domain.model.RelayLimitKind
import seamain.org.typhoonEye.ui.userMessageRes

/**
 * 429/503 copy in the four shipped locales (default, zh-CN, zh-TW, yue) plus English.
 * The sentences name a shared data service and, when a cache is on screen, say so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelayLimitCopyTest {

    private fun text(qualifiers: String, kind: RelayLimitKind, showingCache: Boolean): String {
        RuntimeEnvironment.setQualifiers(qualifiers)
        return RuntimeEnvironment.getApplication().getString(kind.userMessageRes(showingCache))
    }

    @Test
    fun defaultAndSimplifiedChinese_sayBusyOrDailyLimit() {
        val busy = "共享数据服务正忙，请稍后再试。"
        val busyCached = "共享数据服务正忙，正在显示已保存的数据。"
        val quota = "共享数据服务的今日额度已用完，请明天再试。"
        val quotaCached = "共享数据服务的今日额度已用完，正在显示已保存的数据。"
        // `fr` has no overlay, so this resolves the default `values/` copy.
        assertEquals(busy, text("fr", RelayLimitKind.Busy, showingCache = false))
        assertEquals(busyCached, text("fr", RelayLimitKind.Busy, showingCache = true))
        assertEquals(quota, text("fr", RelayLimitKind.DailyQuotaExhausted, showingCache = false))
        assertEquals(quotaCached, text("fr", RelayLimitKind.DailyQuotaExhausted, showingCache = true))
        assertEquals(busy, text("zh-rCN", RelayLimitKind.Busy, showingCache = false))
        assertEquals(quotaCached, text("zh-rCN", RelayLimitKind.DailyQuotaExhausted, showingCache = true))
    }

    @Test
    fun traditionalChinese_usesTraditionalCopy() {
        assertEquals("共享資料服務正忙，請稍後再試。", text("zh-rTW", RelayLimitKind.Busy, false))
        assertEquals(
            "共享資料服務正忙，正在顯示已儲存的資料。",
            text("zh-rTW", RelayLimitKind.Busy, true)
        )
        assertEquals(
            "共享資料服務的今日額度已用完，請明天再試。",
            text("zh-rTW", RelayLimitKind.DailyQuotaExhausted, false)
        )
        assertEquals(
            "共享資料服務的今日額度已用完，正在顯示已儲存的資料。",
            text("zh-rTW", RelayLimitKind.DailyQuotaExhausted, true)
        )
    }

    @Test
    fun cantonese_usesColloquialCopy() {
        assertEquals("共享資料服務而家好忙，遲啲再試。", text("b+yue", RelayLimitKind.Busy, false))
        assertEquals(
            "共享資料服務而家好忙，顯示緊儲存咗嘅資料。",
            text("b+yue", RelayLimitKind.Busy, true)
        )
        assertEquals(
            "共享資料服務今日嘅額度用晒，聽日再試。",
            text("b+yue", RelayLimitKind.DailyQuotaExhausted, false)
        )
        assertEquals(
            "共享資料服務今日嘅額度用晒，顯示緊儲存咗嘅資料。",
            text("b+yue", RelayLimitKind.DailyQuotaExhausted, true)
        )
    }

    @Test
    fun english_saysBusyOrDailyLimitWithoutAgencyTone() {
        val busy = text("en", RelayLimitKind.Busy, false)
        val quota = text("en", RelayLimitKind.DailyQuotaExhausted, true)
        assertEquals("The shared data service is busy. Try again in a little while.", busy)
        assertEquals("The shared data service has reached its daily limit. Showing saved data.", quota)
        listOf(busy, quota).forEach { sentence ->
            assertFalse(sentence.contains("warning", ignoreCase = true))
            assertFalse(sentence.contains("official", ignoreCase = true))
            assertFalse(sentence.contains("agency", ignoreCase = true))
        }
    }
}
