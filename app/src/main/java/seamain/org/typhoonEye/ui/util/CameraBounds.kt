package seamain.org.typhoonEye.ui.util

import kotlin.math.ln
import kotlin.math.max

/**
 * Pure camera-fit math for the track map (#14 / TYP-55), kept free of MapLibre types so
 * it can be unit-tested on the JVM.
 *
 * Longitudes are unwrapped relative to the storm's current position so the fit always
 * takes the shortest arc: a track going 178°E → 179°W becomes 178 → 181, never a
 * 357°-wide box. The returned east may therefore exceed 180 (or west be below -180);
 * MapLibre's `LatLngBounds.from(n, e, s, w)` accepts that (it does not wrap, it only
 * requires west <= east).
 */
data class GeoPoint(val lat: Double, val lng: Double)

sealed interface CameraFit {
    /** Single storm position with no meaningful extent: centre on it at a fixed zoom. */
    data class Center(val lat: Double, val lng: Double, val zoom: Double = SINGLE_POINT_ZOOM) : CameraFit

    /**
     * Bounding box in an unwrapped frame: south <= north, west <= east, east - west < 360,
     * west in [-180, 180). [userIncluded] is false when the user's position was dropped
     * because including it made the box too large.
     */
    data class Bounds(
        val south: Double,
        val west: Double,
        val north: Double,
        val east: Double,
        val userIncluded: Boolean
    ) : CameraFit {
        val latSpan: Double get() = north - south
        val lngSpan: Double get() = east - west
        val centerLat: Double get() = (north + south) / 2.0
        /** Centre longitude wrapped back to [-180, 180). */
        val centerLng: Double get() = wrapLng((west + east) / 2.0)
    }

    companion object {
        const val SINGLE_POINT_ZOOM = 5.5
    }
}

/** Above these spans the user's position is dropped and only the storm is fitted. */
const val MAX_FIT_LNG_SPAN_DEG = 100.0
const val MAX_FIT_LAT_SPAN_DEG = 60.0
private const val MIN_FIT_SPAN_DEG = 0.3
private const val SINGLE_POINT_SPAN_DEG = 0.4
private const val MAX_FIT_LAT = 85.0

/** Wrap a longitude into [-180, 180). */
fun wrapLng(lng: Double): Double {
    val w = ((lng + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    return if (w == -0.0) 0.0 else w
}

/** The longitude equivalent to [lng] that lies within (ref - 180, ref + 180]. */
fun unwrapLng(lng: Double, ref: Double): Double {
    var d = (lng - ref) % 360.0
    if (d > 180.0) d -= 360.0
    if (d <= -180.0) d += 360.0
    return ref + d
}

/**
 * @param track history + forecast points, in map display coordinates.
 * @param current the storm's latest observed position (padding centre and unwrap reference);
 *   falls back to the last track point, then to the user.
 * @param windRadiusKm largest wind radius at [current]; 0 for none.
 * @param user the user's position, or null.
 * @return null when there is nothing to fit.
 */
fun computeCameraFit(
    track: List<GeoPoint>,
    current: GeoPoint?,
    windRadiusKm: Double,
    user: GeoPoint?,
    maxLngSpan: Double = MAX_FIT_LNG_SPAN_DEG,
    maxLatSpan: Double = MAX_FIT_LAT_SPAN_DEG
): CameraFit? {
    val valid = track.filter { it.isValid() }
    val center = current?.takeIf { it.isValid() }
    val validUser = user?.takeIf { it.isValid() }
    val ref = center ?: valid.lastOrNull() ?: validUser ?: return null

    fun boundsWith(includeUser: Boolean): CameraFit.Bounds? {
        val pts = buildList {
            addAll(valid)
            if (includeUser && validUser != null) add(validUser)
        }
        if (pts.isEmpty() && center == null) return null
        val lngs = pts.map { unwrapLng(it.lng, ref.lng) }.toMutableList()
        val lats = pts.map { it.lat }.toMutableList()
        if (center != null) {
            val cLng = unwrapLng(center.lng, ref.lng)
            lngs += cLng
            lats += center.lat
            if (windRadiusKm > 0.0) {
                val (latPad, lngPad) = windRadiusPaddingDegrees(center.lat, windRadiusKm)
                lats += center.lat - latPad
                lats += center.lat + latPad
                lngs += cLng - lngPad
                lngs += cLng + lngPad
            }
        }
        return CameraFit.Bounds(
            south = lats.min(),
            west = lngs.min(),
            north = lats.max(),
            east = lngs.max(),
            userIncluded = includeUser && validUser != null
        )
    }

    val stormHasExtent = valid.isNotEmpty() || center != null
    var bounds = boundsWith(includeUser = true) ?: return null
    if (bounds.userIncluded && stormHasExtent &&
        (bounds.lngSpan > maxLngSpan || bounds.latSpan > maxLatSpan)
    ) {
        bounds = boundsWith(includeUser = false) ?: return null
    }

    val distinctTrack = (valid + listOfNotNull(center)).distinct()
    if (!bounds.userIncluded && distinctTrack.size <= 1 &&
        bounds.latSpan < SINGLE_POINT_SPAN_DEG && bounds.lngSpan < SINGLE_POINT_SPAN_DEG
    ) {
        val p = distinctTrack.firstOrNull() ?: ref
        return CameraFit.Center(p.lat, wrapLng(p.lng))
    }

    return bounds.normalized()
}

/** Rough zoom for a span, used only if MapLibre rejects the bounds. */
fun zoomForSpan(lngSpan: Double, latSpan: Double): Double {
    val span = max(max(lngSpan, latSpan * 1.6), MIN_FIT_SPAN_DEG)
    return (ln(360.0 / span) / ln(2.0)).coerceIn(1.5, 8.0)
}

private fun GeoPoint.isValid(): Boolean =
    lat in -90.0..90.0 && lng in -180.0..180.0 && !lat.isNaN() && !lng.isNaN()

/** Enforce minimum span, clamp latitude, and shift so west is in [-180, 180). */
private fun CameraFit.Bounds.normalized(): CameraFit.Bounds {
    var s = south
    var n = north
    if (n - s < MIN_FIT_SPAN_DEG) {
        val mid = (n + s) / 2.0
        s = mid - MIN_FIT_SPAN_DEG / 2.0
        n = mid + MIN_FIT_SPAN_DEG / 2.0
    }
    var w = west
    var e = east
    if (e - w < MIN_FIT_SPAN_DEG) {
        val mid = (e + w) / 2.0
        w = mid - MIN_FIT_SPAN_DEG / 2.0
        e = mid + MIN_FIT_SPAN_DEG / 2.0
    }
    val shift = wrapLng(w) - w
    return copy(
        south = s.coerceIn(-MAX_FIT_LAT, MAX_FIT_LAT),
        north = n.coerceIn(-MAX_FIT_LAT, MAX_FIT_LAT),
        west = w + shift,
        east = e + shift
    )
}
