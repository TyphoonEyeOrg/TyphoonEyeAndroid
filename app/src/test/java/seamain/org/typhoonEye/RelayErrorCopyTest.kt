package seamain.org.typhoonEye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import seamain.org.typhoonEye.domain.model.DataSource
import seamain.org.typhoonEye.domain.model.SourceFailure
import seamain.org.typhoonEye.domain.model.SourceFailureKind
import seamain.org.typhoonEye.ui.localized
import seamain.org.typhoonEye.ui.localizedDetails

/**
 * Issue #15: relay HTTP 429/503 get their own calm copy in every shipped locale.
 * A provider JSON quota code stays on the older "quota exceeded" line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelayErrorCopyTest {

    private val busy = SourceFailure(DataSource.Juhe, SourceFailureKind.RelayBusy, code = "429")
    private val daily = SourceFailure(DataSource.QWeather, SourceFailureKind.RelayDailyQuota, code = "503")

    private fun text(qualifiers: String, failure: SourceFailure): String {
        RuntimeEnvironment.setQualifiers(qualifiers)
        return failure.localized(RuntimeEnvironment.getApplication())
    }

    @Test
    fun relay429And503_areLocalizedInEveryShippedLocale() {
        assertEquals(
            "The shared data service is busy. Saved data is shown if we have it.",
            text("en-rUS", busy)
        )
        assertEquals(
            "The shared data service has reached its daily limit. Saved data is shown if we have it.",
            text("en-rUS", daily)
        )
        assertEquals("共享数据服务正忙。有已保存的数据时仍会显示。", text("zh-rCN", busy))
        assertEquals("共享数据服务今日额度已用完。有已保存的数据时仍会显示。", text("zh-rCN", daily))
        assertEquals("共享資料服務正忙。有已儲存的資料時仍會顯示。", text("zh-rTW", busy))
        assertEquals("共享資料服務今日額度已用完。有已儲存的資料時仍會顯示。", text("zh-rTW", daily))
        assertEquals("共享數據服務而家好忙。如果有存低嘅數據，仍然會顯示。", text("b+yue", busy))
        assertEquals("共享數據服務今日額度用完。如果有存低嘅數據，仍然會顯示。", text("b+yue", daily))
    }

    @Test
    fun relayCopy_doesNotUseScareOrAgencyLanguage() {
        val banned = listOf("warning", "emergency", "alert", "danger", "预警", "警報", "警告", "气象", "氣象")
        listOf("en-rUS", "zh-rCN", "zh-rTW", "b+yue").forEach { locale ->
            listOf(busy, daily).forEach { failure ->
                val line = text(locale, failure)
                banned.forEach { word ->
                    assertFalse("$locale copy contains '$word': $line", line.contains(word, ignoreCase = true))
                }
            }
        }
    }

    @Test
    fun providerQuotaCode_staysOnTheGenericQuotaLine() {
        val provider = SourceFailure(DataSource.QWeather, SourceFailureKind.QuotaExceeded, code = "429")
        assertEquals("QWeather: quota exceeded", text("en-rUS", provider))
    }

    @Test
    fun identicalRelayLines_areShownOnce() {
        RuntimeEnvironment.setQualifiers("en-rUS")
        val context = RuntimeEnvironment.getApplication()
        val lines = listOf(
            SourceFailure(DataSource.Juhe, SourceFailureKind.RelayBusy, code = "429"),
            SourceFailure(DataSource.QWeather, SourceFailureKind.RelayBusy, code = "429")
        ).localizedDetails(context)
        assertEquals(context.getString(R.string.source_failure_relay_busy), lines)
    }
}
