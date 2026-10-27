package seamain.org.typhoonEye

import org.junit.Assert.assertTrue
import org.junit.Test
import seamain.org.typhoonEye.ui.DebugDemoStorms
import seamain.org.typhoonEye.ui.util.GeoPoint
import seamain.org.typhoonEye.ui.util.unwrapTrack
import kotlin.math.abs

/**
 * #38: the antimeridian sample exists only in src/debug. Release's
 * [DebugDemoStorms.extra] is empty, so `.single()` fails if this lives in
 * src/test. testDebug still runs it under testDebugUnitTest.
 */
class DebugDemoStormTrackTest {

    @Test
    fun debugDemoStorm_crossesAntimeridian() {
        val storm = DebugDemoStorms.extra().single()
        val lngs = (storm.points + storm.forecastPoints).map { it.lng }
        assertTrue(lngs.any { it > 170.0 } && lngs.any { it < -170.0 + 1e-9 })
        val ref = storm.points.last().lng
        val all = unwrapTrack((storm.points + storm.forecastPoints).map { GeoPoint(it.lat, it.lng) }, ref)
        assertContinuous(all.map { it.lng })
    }

    private fun assertContinuous(lngs: List<Double>) {
        lngs.zipWithNext().forEach { (a, b) ->
            assertTrue("jump $a → $b", abs(b - a) < 180.0)
        }
    }
}
