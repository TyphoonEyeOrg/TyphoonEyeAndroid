package seamain.org.typhoonEye.ui.util

/**
 * Continuous track geometry across the antimeridian (#14 / TYP-55).
 *
 * Every rendered coordinate (track lines, track points, wind-radius rings, the user
 * marker and its link) is unwrapped with [unwrapLng] against one reference: the storm's
 * current position, the same reference [computeCameraFit] uses. Values may leave
 * [-180, 180] (e.g. 179°W becomes 181); GeoJSON sources in MapLibre render that as
 * the short segment instead of a line across the whole world.
 *
 * Because each point is within ±180° of the reference, consecutive points of any
 * realistic track (< 180° wide) are less than 180° apart.
 */

/** Reference longitude: latest observed point, else first forecast point. */
fun trackReferenceLng(history: List<GeoPoint>, forecast: List<GeoPoint>): Double? =
    (history.lastOrNull() ?: forecast.firstOrNull())?.lng

/** Same points, same order and size (so index lookups still match the source list). */
fun unwrapTrack(points: List<GeoPoint>, refLng: Double?): List<GeoPoint> =
    if (refLng == null) points else points.map { it.copy(lng = unwrapLng(it.lng, refLng)) }

/** A (lat, lng) ring such as [windCircleRing], unwrapped against [refLng]. */
fun unwrapRing(ring: List<Pair<Double, Double>>, refLng: Double?): List<Pair<Double, Double>> =
    if (refLng == null) ring else ring.map { (lat, lng) -> lat to unwrapLng(lng, refLng) }
