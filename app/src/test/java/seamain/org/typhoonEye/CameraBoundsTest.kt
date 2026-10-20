package seamain.org.typhoonEye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import seamain.org.typhoonEye.ui.util.CameraFit
import seamain.org.typhoonEye.ui.util.GeoPoint
import seamain.org.typhoonEye.ui.util.computeCameraFit
import seamain.org.typhoonEye.ui.util.unwrapLng
import seamain.org.typhoonEye.ui.util.windRadiusPaddingDegrees
import seamain.org.typhoonEye.ui.util.wrapLng
import seamain.org.typhoonEye.ui.util.zoomForSpan

/** #14 / TYP-55: camera fit must take the shortest arc across 180°. */
class CameraBoundsTest {

    private val eps = 1e-9

    private fun bounds(fit: CameraFit?): CameraFit.Bounds {
        assertTrue("expected Bounds, got $fit", fit is CameraFit.Bounds)
        return fit as CameraFit.Bounds
    }

    private fun assertWellFormed(b: CameraFit.Bounds) {
        assertTrue(b.west <= b.east)
        assertTrue(b.south <= b.north)
        assertTrue(b.west >= -180.0 && b.west < 180.0)
        assertTrue(b.lngSpan < 360.0)
    }

    // Storm east of the Philippines, moving north-west.
    private val wpacTrack = listOf(
        GeoPoint(15.0, 135.0),
        GeoPoint(17.0, 132.0),
        GeoPoint(19.0, 129.0)
    )
    private val wpacCurrent = GeoPoint(19.0, 129.0)

    @Test
    fun unwrapAndWrap() {
        assertEquals(181.0, unwrapLng(-179.0, 178.0), eps)
        assertEquals(-181.0, unwrapLng(179.0, -178.0), eps)
        assertEquals(120.0, unwrapLng(120.0, 130.0), eps)
        assertEquals(-179.0, wrapLng(181.0), eps)
        assertEquals(-180.0, wrapLng(180.0), eps)
        assertEquals(170.0, wrapLng(-190.0), eps)
    }

    @Test
    fun trackCrossingAntimeridian_takesShortArc() {
        val track = listOf(
            GeoPoint(20.0, 175.0),
            GeoPoint(22.0, 178.5),
            GeoPoint(24.0, -179.0), // current
            GeoPoint(26.0, -176.0)  // forecast
        )
        val b = bounds(computeCameraFit(track, GeoPoint(24.0, -179.0), 0.0, null))
        assertWellFormed(b)
        assertEquals(175.0, b.west, eps)
        assertEquals(184.0, b.east, eps) // 176°W unwrapped, east > 180 on purpose
        assertEquals(9.0, b.lngSpan, eps)
        assertEquals(20.0, b.south, eps)
        assertEquals(26.0, b.north, eps)
        assertEquals(179.5, b.centerLng, eps)
    }

    @Test
    fun trackCrossingAntimeridian_referenceWestOf180_normalisesWest() {
        // Current position is already east of 180 (negative lng); west is shifted into range.
        val track = listOf(GeoPoint(30.0, 170.0), GeoPoint(32.0, -170.0))
        val b = bounds(computeCameraFit(track, GeoPoint(32.0, -170.0), 0.0, null))
        assertWellFormed(b)
        assertEquals(170.0, b.west, eps)
        assertEquals(190.0, b.east, eps)
    }

    @Test
    fun userInShanghai_isIncluded() {
        val shanghai = GeoPoint(31.23, 121.47)
        val b = bounds(computeCameraFit(wpacTrack, wpacCurrent, 0.0, shanghai))
        assertTrue(b.userIncluded)
        assertWellFormed(b)
        assertEquals(121.47, b.west, eps)
        assertEquals(135.0, b.east, eps)
        assertEquals(31.23, b.north, eps)
    }

    @Test
    fun userInAmericas_isDropped() {
        val losAngeles = GeoPoint(34.05, -118.24)
        val b = bounds(computeCameraFit(wpacTrack, wpacCurrent, 0.0, losAngeles))
        assertFalse(b.userIncluded)
        assertWellFormed(b)
        assertEquals(129.0, b.west, eps)
        assertEquals(135.0, b.east, eps)
        assertEquals(15.0, b.south, eps)
        assertEquals(19.0, b.north, eps)
    }

    @Test
    fun userFarSouth_isDroppedByLatitudeSpan() {
        val hobart = GeoPoint(-42.88, 147.33) // 61.9° of latitude from the storm's north end
        val b = bounds(computeCameraFit(wpacTrack, wpacCurrent, 0.0, hobart))
        assertFalse(b.userIncluded)
        assertEquals(15.0, b.south, eps)
    }

    @Test
    fun userNearAntimeridianStorm_isIncludedOnShortArc() {
        // Storm near the date line, user in Fiji (178°E) → short arc, user kept.
        val track = listOf(GeoPoint(-10.0, -175.0), GeoPoint(-12.0, -178.0))
        val fiji = GeoPoint(-17.7, 178.0)
        val b = bounds(computeCameraFit(track, GeoPoint(-12.0, -178.0), 0.0, fiji))
        assertTrue(b.userIncluded)
        assertWellFormed(b)
        assertEquals(178.0, b.west, eps)
        assertEquals(185.0, b.east, eps)
    }

    @Test
    fun stormOnly() {
        val b = bounds(computeCameraFit(wpacTrack, wpacCurrent, 0.0, null))
        assertFalse(b.userIncluded)
        assertEquals(129.0, b.west, eps)
        assertEquals(135.0, b.east, eps)
    }

    @Test
    fun singlePoint_centres() {
        val p = GeoPoint(18.0, 130.0)
        val fit = computeCameraFit(listOf(p), p, 0.0, null)
        assertTrue(fit is CameraFit.Center)
        fit as CameraFit.Center
        assertEquals(18.0, fit.lat, eps)
        assertEquals(130.0, fit.lng, eps)
        assertEquals(CameraFit.SINGLE_POINT_ZOOM, fit.zoom, eps)
    }

    @Test
    fun singlePointWithWindRadius_fitsPaddedBox() {
        val p = GeoPoint(18.0, 130.0)
        val b = bounds(computeCameraFit(listOf(p), p, 300.0, null))
        val (latPad, lngPad) = windRadiusPaddingDegrees(18.0, 300.0)
        assertEquals(18.0 - latPad, b.south, eps)
        assertEquals(18.0 + latPad, b.north, eps)
        assertEquals(130.0 - lngPad, b.west, eps)
        assertEquals(130.0 + lngPad, b.east, eps)
    }

    @Test
    fun windPadding_appliedInUnwrappedFrame() {
        val current = GeoPoint(25.0, 179.5)
        val track = listOf(GeoPoint(23.0, 177.0), current)
        val b = bounds(computeCameraFit(track, current, 400.0, null))
        val (_, lngPad) = windRadiusPaddingDegrees(25.0, 400.0)
        assertWellFormed(b)
        assertEquals(179.5 - lngPad, b.west, eps)
        assertEquals(179.5 + lngPad, b.east, eps) // > 180, not wrapped to -17x
        assertTrue(b.east > 180.0)
        assertTrue(b.lngSpan < 10.0)
    }

    @Test
    fun windPadding_countsTowardsSpanLimit() {
        // Without padding a user at 85°E fits (span 50°); a huge radius would push it over.
        val track = listOf(GeoPoint(20.0, 135.0))
        val user = GeoPoint(20.0, 40.0)
        val b = bounds(computeCameraFit(track, GeoPoint(20.0, 135.0), 900.0, user))
        assertFalse(b.userIncluded)
    }

    @Test
    fun userOnly_noTrack() {
        val fit = computeCameraFit(emptyList(), null, 0.0, GeoPoint(31.0, 121.0))
        val b = bounds(fit)
        assertTrue(b.userIncluded)
        assertEquals(0.3, b.latSpan, 1e-6)
    }

    @Test
    fun nothingToFit() {
        assertNull(computeCameraFit(emptyList(), null, 0.0, null))
        assertNull(computeCameraFit(listOf(GeoPoint(95.0, 400.0)), null, 0.0, null))
    }

    @Test
    fun fallbackZoom_isSane() {
        assertTrue(zoomForSpan(9.0, 6.0) in 4.0..6.0)
        assertEquals(1.5, zoomForSpan(359.0, 80.0), eps)
        assertEquals(8.0, zoomForSpan(0.01, 0.01), eps)
    }
}
