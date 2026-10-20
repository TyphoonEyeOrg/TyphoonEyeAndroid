package seamain.org.typhoonEye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import seamain.org.typhoonEye.ui.DebugDemoStorms
import seamain.org.typhoonEye.ui.util.GeoPoint
import seamain.org.typhoonEye.ui.util.WindRadiiKm
import seamain.org.typhoonEye.ui.util.trackReferenceLng
import seamain.org.typhoonEye.ui.util.unwrapRing
import seamain.org.typhoonEye.ui.util.unwrapTrack
import seamain.org.typhoonEye.ui.util.windCircleRing
import kotlin.math.abs

/** #14 / TYP-55: rendered geometry stays continuous across 180°. */
class TrackGeometryTest {

    private val eps = 1e-9

    private fun assertContinuous(lngs: List<Double>) {
        lngs.zipWithNext().forEach { (a, b) ->
            assertTrue("jump $a → $b", abs(b - a) < 180.0)
        }
    }

    @Test
    fun segment178E_to179W_staysShort() {
        val track = listOf(GeoPoint(20.0, 178.0), GeoPoint(21.0, -179.0))
        val out = unwrapTrack(track, refLng = -179.0)
        assertEquals(-182.0, out[0].lng, eps) // same frame as reference (179°W)
        assertEquals(-179.0, out[1].lng, eps)
        val out2 = unwrapTrack(track, refLng = 178.0)
        assertEquals(178.0, out2[0].lng, eps)
        assertEquals(181.0, out2[1].lng, eps)
        assertTrue(abs(out2[1].lng - out2[0].lng) < 180.0)
    }

    @Test
    fun fullTrackCrossing180_hasNoJumps() {
        val history = listOf(
            GeoPoint(16.0, 168.0), GeoPoint(17.6, 171.5), GeoPoint(19.3, 175.2),
            GeoPoint(20.8, 178.6), GeoPoint(22.0, -178.4)
        )
        val forecast = listOf(GeoPoint(23.2, -176.0), GeoPoint(24.6, -173.4), GeoPoint(27.0, -170.0))
        val ref = trackReferenceLng(history, forecast)
        assertEquals(-178.4, ref!!, eps)
        val h = unwrapTrack(history, ref)
        val f = unwrapTrack(forecast, ref)
        assertEquals(history.size, h.size)
        assertEquals(forecast.size, f.size)
        // Lines are history and current→forecast.
        assertContinuous(h.map { it.lng })
        assertContinuous((listOf(h.last()) + f).map { it.lng })
        // Latitudes untouched, longitudes equal modulo 360 (indices still match).
        history.indices.forEach { i ->
            assertEquals(history[i].lat, h[i].lat, eps)
            assertEquals(0.0, ((h[i].lng - history[i].lng) % 360.0 + 360.0) % 360.0, eps)
        }
    }

    @Test
    fun windRingAround179_5E_isContiguous() {
        val radii = WindRadiiKm(320.0, 300.0, 260.0, 280.0)
        val ring = windCircleRing(22.0, 179.5, radii)
        // Simulate a source that wraps to [-180, 180] (e.g. after GCJ-02 or normalisation).
        val wrapped = ring.map { (lat, lng) -> lat to (if (lng > 180.0) lng - 360.0 else lng) }
        assertTrue(wrapped.any { it.second < 0 } && wrapped.any { it.second > 0 })
        val out = unwrapRing(wrapped, refLng = 179.5)
        assertEquals(ring.size, out.size)
        assertContinuous(out.map { it.second })
        assertTrue(out.all { abs(it.second - 179.5) < 10.0 })
        assertEquals(out.first(), out.last()) // still closed
    }

    @Test
    fun trackFarFrom180_isUnchanged() {
        val track = listOf(GeoPoint(15.0, 135.0), GeoPoint(17.0, 132.0), GeoPoint(19.0, 129.0))
        assertEquals(track, unwrapTrack(track, refLng = 129.0))
        val ring = windCircleRing(19.0, 129.0, WindRadiiKm(200.0, 200.0, 200.0, 200.0))
        assertEquals(ring, unwrapRing(ring, refLng = 129.0))
    }

    @Test
    fun noReference_isNoOp() {
        val track = listOf(GeoPoint(20.0, 178.0), GeoPoint(21.0, -179.0))
        assertEquals(track, unwrapTrack(track, null))
        assertEquals(null, trackReferenceLng(emptyList(), emptyList()))
    }

    @Test
    fun debugDemoStorm_crossesAntimeridian() {
        // Unit tests run against the debug source set, where the sample exists.
        val storm = DebugDemoStorms.extra().single()
        val lngs = (storm.points + storm.forecastPoints).map { it.lng }
        assertTrue(lngs.any { it > 170.0 } && lngs.any { it < -170.0 + 1e-9 })
        val ref = storm.points.last().lng
        val all = unwrapTrack((storm.points + storm.forecastPoints).map { GeoPoint(it.lat, it.lng) }, ref)
        assertContinuous(all.map { it.lng })
    }
}
