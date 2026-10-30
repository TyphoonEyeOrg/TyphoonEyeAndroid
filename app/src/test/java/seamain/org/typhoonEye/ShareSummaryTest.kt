package seamain.org.typhoonEye

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.TyphoonPoint
import seamain.org.typhoonEye.ui.util.formatObservationTime
import seamain.org.typhoonEye.ui.util.moveLabel
import seamain.org.typhoonEye.ui.util.shareText
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * Share text uses [moveLabel] (localized direction and speed unit) and
 * [formatObservationTime] in the device time zone.
 *
 * Resource folders covered: default `values/` (via `fr`, which has no overlay),
 * `values-en`, `values-zh-rCN` (falls back to default for these strings),
 * `values-zh-rTW`, and `values-b+yue`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareSummaryTest {

    /** 08:00 UTC is 00:00 in America/Los_Angeles (PST, January). */
    private val observedRaw = "2020-01-15T08:00:00Z"
    private lateinit var previousZone: TimeZone

    private val openAttribution = "OpenFreeMap © OpenMapTiles Data from OpenStreetMap"

    @Before
    fun fixZone() {
        previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(previousZone)
    }

    @Test
    fun shareText_usesMoveLabelAndLocalObservationTime_inEveryLocale() {
        val cases = listOf(
            // default values/ — Simplified Chinese
            LocaleCase(
                qualifiers = "fr",
                move = "西北偏西 · 24 公里/小时",
                moveLine = "移动：西北偏西 · 24 公里/小时"
            ),
            LocaleCase(
                qualifiers = "zh-rCN",
                move = "西北偏西 · 24 公里/小时",
                moveLine = "移动：西北偏西 · 24 公里/小时"
            ),
            LocaleCase(
                qualifiers = "en",
                move = "WNW · 24 km/h",
                moveLine = "Motion: WNW · 24 km/h"
            ),
            LocaleCase(
                qualifiers = "zh-rTW",
                move = "西西北 · 24 公里/小時",
                moveLine = "移動：西西北 · 24 公里/小時"
            ),
            LocaleCase(
                qualifiers = "b+yue",
                move = "西西北 · 24 公里/小時",
                moveLine = "移動：西西北 · 24 公里/小時"
            )
        )
        val localClock = ZonedDateTime.parse(observedRaw)
            .withZoneSameInstant(ZoneId.of("America/Los_Angeles"))
            .toLocalTime()
            .toString()
        assertEquals("00:00", localClock)

        cases.forEach { expected ->
            RuntimeEnvironment.setQualifiers(expected.qualifiers)
            val context = RuntimeEnvironment.getApplication()
            val point = samplePoint()
            val summary = sampleTyphoon().shareText(context)

            assertEquals(expected.qualifiers, expected.move, point.moveLabel(context))
            val observed = formatObservationTime(point.time, context = context)
            val observedLine = context.getString(R.string.share_observed_time, observed)
            assertTrue(expected.qualifiers, summary.contains(expected.moveLine))
            assertTrue(expected.qualifiers, summary.contains(observedLine))
            assertTrue(expected.qualifiers, observed.contains("00:00"))
            assertFalse(expected.qualifiers, observed.contains("08:00"))
            assertFalse(expected.qualifiers, summary.contains(observedRaw))
            assertEquals(expected.qualifiers, openAttribution, context.getString(R.string.map_badge_open))
        }
    }

    @Test
    fun moveLabel_keepsASpeedThatAlreadyHasAUnit() {
        RuntimeEnvironment.setQualifiers("zh-rCN")
        val context = RuntimeEnvironment.getApplication()
        val point = samplePoint().copy(moveSpeed = "24 公里/小时")
        assertEquals("西北偏西 · 24 公里/小时", point.moveLabel(context))

        RuntimeEnvironment.setQualifiers("en")
        val english = RuntimeEnvironment.getApplication()
        assertEquals("WNW · 24 km/h", samplePoint().copy(moveSpeed = "24 km/h").moveLabel(english))
    }

    private fun samplePoint() = TyphoonPoint(
        time = observedRaw,
        lat = 21.8,
        lng = 126.9,
        pressure = 960,
        speed = 40,
        power = "13",
        moveDirection = "WNW",
        moveSpeed = "24"
    )

    private fun sampleTyphoon() = Typhoon(
        id = "202609",
        name = "巴威",
        englishName = "BAVI",
        status = "active",
        points = listOf(samplePoint())
    )

    private data class LocaleCase(
        val qualifiers: String,
        val move: String,
        val moveLine: String
    )
}
