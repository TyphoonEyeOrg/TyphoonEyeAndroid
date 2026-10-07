package seamain.org.typhoonEye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.TyphoonPoint
import seamain.org.typhoonEye.domain.util.TyphoonActivity

class TyphoonActivityTest {

    private val hour = 60L * 60 * 1000

    private fun storm(
        vararg times: String,
        status: String = "active",
        endTime: String = ""
    ) = Typhoon(
        id = "202609",
        name = "巴威",
        englishName = "BAVI",
        status = status,
        endTime = endTime,
        points = times.map { TyphoonPoint(it, 21.8, 126.9, 960, 40, "13") }
    )

    private fun at(text: String) = TyphoonActivity.parseEpochMs(text)!!

    @Test
    fun `parses Juhe Beijing local times and QWeather offset times to the same instant`() {
        assertEquals(at("2026-07-11T09:00+08:00"), at("2026-07-11 09:00:00"))
        assertEquals(at("2026-07-11 09:00"), at("2026-07-11 09:00:00"))
        assertEquals(at("2026-07-11T01:00Z"), at("2026-07-11 09:00"))
    }

    @Test
    fun `Juhe local times are read as Beijing time regardless of device timezone`() {
        val original = java.util.TimeZone.getDefault()
        val expected = at("2026-07-11T01:00Z")
        try {
            for (zone in listOf("America/New_York", "Europe/London", "Asia/Singapore", "Pacific/Auckland")) {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone))
                assertEquals(zone, expected, TyphoonActivity.parseEpochMs("2026-07-11 09:00:00"))
                // 24h rule must give the same answer on every device.
                val typhoon = storm("2026-07-10 09:00:00")
                assertEquals(zone, "active", TyphoonActivity.resolve(typhoon, expected).status)
                assertEquals(zone, "dissipated", TyphoonActivity.resolve(typhoon, expected + hour).status)
            }
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }

    @Test
    fun `unparseable time returns null`() {
        assertNull(TyphoonActivity.parseEpochMs(""))
        assertNull(TyphoonActivity.parseEpochMs("8月19日 23:00"))
    }

    @Test
    fun `latest observation within 24h stays active`() {
        val typhoon = storm("2026-07-10 08:00:00", "2026-07-10 14:00:00")
        val now = at("2026-07-11 13:59:00")
        assertEquals("active", TyphoonActivity.resolve(typhoon, now).status)
    }

    @Test
    fun `latest observation exactly 24h old stays active`() {
        val typhoon = storm("2026-07-10 14:00:00")
        assertEquals("active", TyphoonActivity.resolve(typhoon, at("2026-07-10 14:00") + 24 * hour).status)
    }

    @Test
    fun `latest observation over 24h old is dissipated`() {
        // Screenshot case: TD07 last point 08-19 23:00, still listed on 10-02.
        val typhoon = storm("2026-08-19 17:00:00", "2026-08-19 23:00:00")
        assertEquals("dissipated", TyphoonActivity.resolve(typhoon, at("2026-10-02 11:33")).status)
        assertEquals(
            "dissipated",
            TyphoonActivity.resolve(typhoon, at("2026-08-19 23:00") + 24 * hour + 1).status
        )
    }

    @Test
    fun `uses the newest point even if track is unordered`() {
        val typhoon = storm("2026-07-10 14:00:00", "2026-07-08 08:00:00")
        assertEquals("active", TyphoonActivity.resolve(typhoon, at("2026-07-11 08:00")).status)
    }

    @Test
    fun `non blank endTime alone does not mean dissipated`() {
        // Juhe endtime is the latest observation time of an active storm.
        val typhoon = storm(endTime = "2026-07-10 14:00:00")
        assertEquals("active", TyphoonActivity.resolve(typhoon, at("2026-07-10 15:00")).status)
    }

    @Test
    fun `list snapshot without points falls back to endTime`() {
        val typhoon = storm(endTime = "2026-07-08 14:00:00")
        assertEquals("dissipated", TyphoonActivity.resolve(typhoon, at("2026-07-10 15:00")).status)
    }

    @Test
    fun `unparseable times keep upstream status`() {
        val typhoon = storm("not-a-time")
        assertEquals("active", TyphoonActivity.resolve(typhoon, at("2030-01-01 00:00")).status)
    }

    @Test
    fun `upstream dissipated is never revived`() {
        val typhoon = storm("2026-07-10 14:00:00", status = "dissipated")
        assertEquals("dissipated", TyphoonActivity.resolve(typhoon, at("2026-07-10 15:00")).status)
    }
}
