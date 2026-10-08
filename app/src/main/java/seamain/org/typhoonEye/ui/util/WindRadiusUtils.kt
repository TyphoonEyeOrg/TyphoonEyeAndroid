package seamain.org.typhoonEye.ui.util

import seamain.org.typhoonEye.domain.model.TyphoonPoint
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Four-quadrant wind radii in kilometres (NE | SE | SW | NW),
 * matching Juhe pipe strings and QWeather [toPipeString].
 */
data class WindRadiiKm(
    val ne: Double,
    val se: Double,
    val sw: Double,
    val nw: Double
) {
    val maxKm: Double get() = maxOf(ne, se, sw, nw)
    val hasAny: Boolean get() = maxKm > 0.0
    val isSymmetric: Boolean get() = ne == se && se == sw && sw == nw
    val avgKm: Double
        get() {
            val vals = listOf(ne, se, sw, nw).filter { it > 0.0 }
            return if (vals.isEmpty()) 0.0 else vals.average()
        }
}

/**
 * Parse `"ne|se|sw|nw"` (km). Also accepts a single number applied to all quadrants.
 * Empty / invalid → null.
 */
fun parseWindRadii(raw: String): WindRadiiKm? {
    val text = raw.trim()
    if (text.isEmpty() || text == "-" || text == "0" || text == "0|0|0|0") return null
    val parts = text.split('|', ',', '/', '，').map { it.trim() }.filter { it.isNotEmpty() }
    fun num(s: String): Double? =
        s.replace("km", "", ignoreCase = true)
            .replace("公里", "")
            .trim()
            .toDoubleOrNull()
            ?.takeIf { it > 0.0 && it < 5000.0 }

    return when (parts.size) {
        1 -> num(parts[0])?.let { WindRadiiKm(it, it, it, it) }
        4 -> {
            val ne = num(parts[0]) ?: 0.0
            val se = num(parts[1]) ?: 0.0
            val sw = num(parts[2]) ?: 0.0
            val nw = num(parts[3]) ?: 0.0
            WindRadiiKm(ne, se, sw, nw).takeIf { it.hasAny }
        }
        else -> null
    }
}

fun TyphoonPoint.windRadii7(): WindRadiiKm? = parseWindRadii(radius7)
fun TyphoonPoint.windRadii10(): WindRadiiKm? = parseWindRadii(radius10)
fun TyphoonPoint.windRadii12(): WindRadiiKm? = parseWindRadii(radius12)

/**
 * Build a closed ring of [lng, lat] pairs for an asymmetric wind circle.
 * Quadrants: NE 0–90°, SE 90–180°, SW 180–270°, NW 270–360° (bearing from north).
 */
fun windCircleRing(
    centerLat: Double,
    centerLng: Double,
    radii: WindRadiiKm,
    stepsPerQuadrant: Int = 18
): List<Pair<Double, Double>> {
    require(stepsPerQuadrant >= 4)
    val ring = ArrayList<Pair<Double, Double>>(stepsPerQuadrant * 4 + 1)

    fun radiusForBearing(bearingDeg: Double): Double {
        val b = ((bearingDeg % 360.0) + 360.0) % 360.0
        return when {
            b < 90.0 -> radii.ne
            b < 180.0 -> radii.se
            b < 270.0 -> radii.sw
            else -> radii.nw
        }
    }

    val step = 90.0 / stepsPerQuadrant
    var bearing = 0.0
    while (bearing < 360.0 - 1e-6) {
        val r = radiusForBearing(bearing)
        if (r > 0.0) {
            ring += destinationPoint(centerLat, centerLng, bearing, r)
        }
        bearing += step
    }
    if (ring.isEmpty()) return emptyList()
    // Close the ring.
    ring += ring.first()
    return ring
}

/** Destination from (lat,lng) travelling [distanceKm] along [bearingDeg] (0 = north). */
fun destinationPoint(
    lat: Double,
    lng: Double,
    bearingDeg: Double,
    distanceKm: Double
): Pair<Double, Double> {
    val r = 6371.0
    val δ = distanceKm / r
    val θ = Math.toRadians(bearingDeg)
    val φ1 = Math.toRadians(lat)
    val λ1 = Math.toRadians(lng)
    val sinφ1 = sin(φ1)
    val cosφ1 = cos(φ1)
    val sinδ = sin(δ)
    val cosδ = cos(δ)
    val φ2 = asin(sinφ1 * cosδ + cosφ1 * sinδ * cos(θ))
    val λ2 = λ1 + atan2(sin(θ) * sinδ * cosφ1, cosδ - sinφ1 * sin(φ2))
    return Math.toDegrees(φ2) to Math.toDegrees(λ2)
}

/** Localized single-line summary. */
fun WindRadiiKm.displayLabel(context: android.content.Context): String {
    val max = maxKm.toInt()
    if (!hasAny) return "—"
    return if (isSymmetric) {
        context.getString(seamain.org.typhoonEye.R.string.wind_radius_symmetric, max)
    } else {
        val avg = avgKm.toInt()
        if (avg > 0 && avg != max) {
            context.getString(seamain.org.typhoonEye.R.string.wind_radius_asymmetric_summary, max, avg)
        } else {
            "$max km"
        }
    }
}

data class WindQuadrantValue(
    val key: String,
    val labelRes: Int,
    val km: Int
)

fun WindRadiiKm.quadrants(): List<WindQuadrantValue> = listOf(
    WindQuadrantValue("NE", seamain.org.typhoonEye.R.string.direction_ne, ne.toInt()),
    WindQuadrantValue("SE", seamain.org.typhoonEye.R.string.direction_se, se.toInt()),
    WindQuadrantValue("SW", seamain.org.typhoonEye.R.string.direction_sw, sw.toInt()),
    WindQuadrantValue("NW", seamain.org.typhoonEye.R.string.direction_nw, nw.toInt())
)

/** Approximate lat/lng padding (degrees) for camera fit given max radius km. */
fun windRadiusPaddingDegrees(lat: Double, radiusKm: Double): Pair<Double, Double> {
    val latPad = radiusKm / 111.32
    val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(0.2)
    val lngPad = radiusKm / (111.32 * cosLat)
    return latPad to lngPad
}

