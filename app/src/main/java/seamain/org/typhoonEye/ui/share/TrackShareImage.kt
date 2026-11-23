package seamain.org.typhoonEye.ui.share

import seamain.org.typhoonEye.domain.util.CoordTransform
import seamain.org.typhoonEye.ui.util.AMAP_STYLE_ATTRIBUTION
import seamain.org.typhoonEye.ui.util.IntensityLevel
import seamain.org.typhoonEye.ui.util.ResolvedBasemap
import seamain.org.typhoonEye.ui.util.resolveIntensity
import seamain.org.typhoonEye.ui.util.unwrapLng
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ln
import kotlin.math.tan

/**
 * Observed track and forecast are separate lists. A forecast point is never
 * copied into [TrackShareImageModel.observed].
 */
enum class SharePointKind { Observed, Forecast }

/** Where the storm record came from. The label is chosen from this, not guessed per point. */
enum class ShareSourceKind { Live, Historical, Demo }

data class ShareRawPoint(
    val lat: Double,
    val lng: Double,
    val strong: String = "",
    val power: String = ""
)

data class ShareTrackPoint(
    val lat: Double,
    val lng: Double,
    val kind: SharePointKind,
    /** Null when the source did not report an intensity we can name. Not a filled-in category. */
    val intensityArgb: Int?,
    val latestObserved: Boolean = false
)

/**
 * Everything the share image is allowed to say and draw.
 *
 * [includeUserLocation] defaults off. When it is off, [userLatitude] and [userLongitude]
 * are null even if the caller passed a fix — the picture has no user marker and no distance.
 */
data class TrackShareImageModel(
    val title: String,
    val stormId: String,
    val idLine: String,
    val sourceLine: String,
    val updatedLine: String,
    val fromCache: Boolean,
    val cacheLine: String?,
    val disclaimer: String,
    val attribution: String,
    val legendLine: String?,
    val demoLine: String?,
    val basemapUnavailableLine: String,
    val observed: List<ShareTrackPoint>,
    val forecast: List<ShareTrackPoint>,
    val includeUserLocation: Boolean = false,
    val userLatitude: Double? = null,
    val userLongitude: Double? = null
) {
    val showsUserLocation: Boolean
        get() = includeUserLocation && userLatitude != null && userLongitude != null

    val hasDrawableTrack: Boolean
        get() = observed.isNotEmpty() || forecast.isNotEmpty()
}

data class PlottedPoint(
    val x: Float,
    val y: Float,
    val kind: SharePointKind,
    val intensityArgb: Int?,
    val latestObserved: Boolean
)

/**
 * [forecastConnector] is the dashed segment from the latest observed point to the
 * first forecast point. It is not part of [observedLine].
 */
data class SharePlot(
    val observedLine: List<PlottedPoint>,
    val forecastLine: List<PlottedPoint>,
    val forecastConnector: Pair<PlottedPoint, PlottedPoint>?,
    val observedMarkers: List<PlottedPoint>,
    val forecastMarkers: List<PlottedPoint>,
    val userMarker: PlottedPoint?
)

/** Geographic frame the picture uses, after GCJ-02 (when requested) and antimeridian unwrap. */
data class ShareFrame(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double
)

/** Solid observed track. Forecast uses a dash so it cannot be read as the same line. */
const val FORECAST_DASH_ON = 16f
const val FORECAST_DASH_OFF = 12f

fun forecastDashIntervals(): FloatArray = floatArrayOf(FORECAST_DASH_ON, FORECAST_DASH_OFF)

private val INTENSITY_ARGB: Map<IntensityLevel, Int> = mapOf(
    IntensityLevel.TD to 0xFF64B5F6.toInt(),
    IntensityLevel.TS to 0xFF26A69A.toInt(),
    IntensityLevel.STS to 0xFFFFB74D.toInt(),
    IntensityLevel.TY to 0xFFFF8A65.toInt(),
    IntensityLevel.STY to 0xFFEF5350.toInt(),
    IntensityLevel.SUPER to 0xFFB71C1C.toInt()
)

/**
 * Color for a reported intensity. Blank strong and power stay null — no category is invented.
 * An unrecognized label also stays null rather than a stand-in color.
 */
fun shareIntensityArgb(strong: String, power: String): Int? {
    if (strong.isBlank() && power.isBlank()) return null
    val level = resolveIntensity(strong, power)
    if (level == IntensityLevel.UNKNOWN) return null
    return INTENSITY_ARGB[level]
}

fun requiredBasemapAttribution(basemap: ResolvedBasemap, openCredit: String): String =
    when (basemap) {
        ResolvedBasemap.OpenStreet -> openCredit
        ResolvedBasemap.Amap -> AMAP_STYLE_ATTRIBUTION
    }

/**
 * Live data may have come from more than one configured agency; the label names that
 * set instead of picking one. History is QWeather only. Demo is sample data.
 * A relay build says so, because that is the service the app actually called.
 */
fun shareSourceName(
    kind: ShareSourceKind,
    viaRelay: Boolean,
    juheEnabled: Boolean,
    qWeatherEnabled: Boolean,
    relayPattern: String,
    juheName: String,
    qWeatherName: String,
    demoName: String,
    unknownName: String
): String {
    if (kind == ShareSourceKind.Demo) return demoName
    val agencies = when (kind) {
        ShareSourceKind.Historical -> listOf(qWeatherName)
        ShareSourceKind.Live -> buildList {
            if (juheEnabled) add(juheName)
            if (qWeatherEnabled) add(qWeatherName)
        }
        ShareSourceKind.Demo -> emptyList()
    }
    val joined = agencies.joinToString(" / ").ifBlank { return unknownName }
    return if (viaRelay) relayPattern.format(joined) else joined
}

/**
 * Clock time plus the zone id and UTC offset. Null or non-positive epochs stay [unknown]
 * — the clock is not substituted.
 */
fun formatShareUpdatedAt(epochMs: Long?, zone: ZoneId, unknown: String): String {
    if (epochMs == null || epochMs <= 0L) return unknown
    val zoned = Instant.ofEpochMilli(epochMs).atZone(zone)
    val clock = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(zoned)
    val offsetId = zoned.offset.id
    val offsetLabel = if (offsetId == "Z") "UTC+00:00" else "UTC$offsetId"
    return "$clock ${zone.id} ($offsetLabel)"
}

/**
 * [updatedLine] is already formatted and includes the zone, or the localized
 * "unknown" label. This function does not invent a clock time.
 *
 * Forecast points stay in [TrackShareImageModel.forecast]. Invalid coordinates
 * are dropped and not replaced. User coordinates are kept only when
 * [includeUserLocation] is true (default false).
 */
fun buildTrackShareImage(
    title: String,
    stormId: String,
    observed: List<ShareRawPoint>,
    forecast: List<ShareRawPoint>,
    idLine: String,
    sourceLine: String,
    updatedLine: String,
    fromCache: Boolean,
    cacheLine: String?,
    disclaimer: String,
    attribution: String,
    legendObserved: String,
    legendForecast: String,
    legendBoth: String,
    demoLine: String?,
    basemapUnavailableLine: String,
    includeUserLocation: Boolean = false,
    userLatitude: Double? = null,
    userLongitude: Double? = null
): TrackShareImageModel {
    val observedPoints = observed.mapNotNull { it.toSharePoint(SharePointKind.Observed, latest = false) }
    val forecastPoints = forecast.mapNotNull { it.toSharePoint(SharePointKind.Forecast, latest = false) }
    val markedObserved = observedPoints.mapIndexed { index, point ->
        point.copy(latestObserved = index == observedPoints.lastIndex)
    }
    val showUser = includeUserLocation &&
        userLatitude != null &&
        userLongitude != null &&
        validLatLng(userLatitude, userLongitude)
    val legend = when {
        markedObserved.isNotEmpty() && forecastPoints.isNotEmpty() -> legendBoth
        markedObserved.isNotEmpty() -> legendObserved
        forecastPoints.isNotEmpty() -> legendForecast
        else -> null
    }
    return TrackShareImageModel(
        title = title.trim().ifBlank { stormId.trim() },
        stormId = stormId,
        idLine = idLine,
        sourceLine = sourceLine,
        updatedLine = updatedLine,
        fromCache = fromCache,
        cacheLine = if (fromCache) cacheLine else null,
        disclaimer = disclaimer,
        attribution = attribution,
        legendLine = legend,
        demoLine = demoLine,
        basemapUnavailableLine = basemapUnavailableLine,
        observed = markedObserved,
        forecast = forecastPoints,
        includeUserLocation = showUser,
        userLatitude = if (showUser) userLatitude else null,
        userLongitude = if (showUser) userLongitude else null
    )
}

fun trackShareTextLines(
    model: TrackShareImageModel,
    basemapTilesIncluded: Boolean,
    styleAttributions: List<String> = emptyList()
): List<String> = buildList {
    add(model.title)
    add(model.idLine)
    model.demoLine?.let { add(it) }
    model.legendLine?.let { add(it) }
    add(model.sourceLine)
    add(model.updatedLine)
    model.cacheLine?.let { add(it) }
    if (!basemapTilesIncluded) add(model.basemapUnavailableLine)
    add(model.disclaimer)
    add(model.attribution)
    addAll(extraAttributionLines(model.attribution, styleAttributions))
}

/** Drops blank lines, secrets, and credits already covered by [required]. */
fun extraAttributionLines(required: String, styleLines: List<String>): List<String> {
    val seen = LinkedHashSet<String>()
    styleLines.forEach { raw ->
        val clean = sanitizeAttribution(raw) ?: return@forEach
        if (clean == required || required.contains(clean) || clean.contains(required)) return@forEach
        seen += clean
    }
    return seen.toList()
}

fun sanitizeAttribution(raw: String): String? {
    val text = raw.trim().replace(Regex("\\s+"), " ")
    if (text.isEmpty() || text.length > 200) return null
    val lower = text.lowercase()
    if (lower.contains("key=") ||
        lower.contains("api_key") ||
        lower.contains("access_token") ||
        lower.contains("begin private") ||
        lower.contains("secret")
    ) {
        return null
    }
    return text
}

fun shareFrame(model: TrackShareImageModel, useGcj02: Boolean): ShareFrame? {
    val display = displayPoints(model, useGcj02)
    if (display.isEmpty()) return null
    val ref = (model.observed.lastOrNull() ?: model.forecast.firstOrNull())?.let { point ->
        displayLngLat(point.lng, point.lat, useGcj02).first
    } ?: return null
    val unwrapped = display.map { (lng, lat) -> unwrapLng(lng, ref) to lat }
    var south = unwrapped.minOf { it.second }
    var north = unwrapped.maxOf { it.second }
    var west = unwrapped.minOf { it.first }
    var east = unwrapped.maxOf { it.first }
    val latSpan = (north - south).coerceAtLeast(MIN_SPAN_DEG)
    val lngSpan = (east - west).coerceAtLeast(MIN_SPAN_DEG)
    val latPad = latSpan * FRAME_PAD_FRACTION
    val lngPad = lngSpan * FRAME_PAD_FRACTION
    south -= latPad
    north += latPad
    west -= lngPad
    east += lngPad
    if (north - south < MIN_SPAN_DEG) {
        val mid = (north + south) / 2.0
        south = mid - MIN_SPAN_DEG / 2.0
        north = mid + MIN_SPAN_DEG / 2.0
    }
    if (east - west < MIN_SPAN_DEG) {
        val mid = (east + west) / 2.0
        west = mid - MIN_SPAN_DEG / 2.0
        east = mid + MIN_SPAN_DEG / 2.0
    }
    return ShareFrame(
        south = south.coerceAtLeast(-85.0),
        west = west,
        north = north.coerceAtMost(85.0),
        east = east
    )
}

fun layoutSharePlot(
    model: TrackShareImageModel,
    useGcj02: Boolean,
    left: Float,
    top: Float,
    width: Float,
    height: Float
): SharePlot {
    val frame = shareFrame(model, useGcj02)
    if (frame == null || width <= 0f || height <= 0f) {
        return emptyPlot()
    }
    fun project(lat: Double, lng: Double): Pair<Float, Float> {
        val (displayLng, displayLat) = displayLngLat(lng, lat, useGcj02)
        val ref = frame.west + (frame.east - frame.west) / 2.0
        val xLng = unwrapLng(displayLng, ref)
        val x = left + ((xLng - frame.west) / (frame.east - frame.west)).toFloat() * width
        val yNorth = mercatorY(frame.north)
        val ySouth = mercatorY(frame.south)
        val span = (yNorth - ySouth).takeIf { it != 0.0 } ?: 1.0
        val y = top + ((yNorth - mercatorY(displayLat)) / span).toFloat() * height
        return x to y
    }
    val observed = model.observed.map { point ->
        val (x, y) = project(point.lat, point.lng)
        PlottedPoint(x, y, SharePointKind.Observed, point.intensityArgb, point.latestObserved)
    }
    val forecast = model.forecast.map { point ->
        val (x, y) = project(point.lat, point.lng)
        PlottedPoint(x, y, SharePointKind.Forecast, point.intensityArgb, latestObserved = false)
    }
    val user = if (model.showsUserLocation) {
        val (x, y) = project(model.userLatitude!!, model.userLongitude!!)
        PlottedPoint(x, y, SharePointKind.Observed, intensityArgb = null, latestObserved = false)
    } else {
        null
    }
    return assemblePlot(observed, forecast, user)
}

/** Lines, markers, and the forecast connector. Shared by the canvas fallback and tests. */
fun assemblePlot(
    observed: List<PlottedPoint>,
    forecast: List<PlottedPoint>,
    userMarker: PlottedPoint?
): SharePlot {
    val connector = if (observed.isNotEmpty() && forecast.isNotEmpty()) {
        observed.last() to forecast.first()
    } else {
        null
    }
    return SharePlot(
        observedLine = if (observed.size >= 2) observed else emptyList(),
        forecastLine = if (forecast.size >= 2) forecast else emptyList(),
        forecastConnector = connector,
        observedMarkers = observed,
        forecastMarkers = forecast,
        userMarker = userMarker
    )
}

fun displayLngLat(lng: Double, lat: Double, useGcj02: Boolean): Pair<Double, Double> {
    if (!useGcj02) return lng to lat
    val mapped = CoordTransform.wgs84ToGcj02(lng, lat)
    return mapped.lng to mapped.lat
}

private fun displayPoints(model: TrackShareImageModel, useGcj02: Boolean): List<Pair<Double, Double>> {
    val track = (model.observed + model.forecast).map { displayLngLat(it.lng, it.lat, useGcj02) }
    val user = if (model.showsUserLocation) {
        listOf(displayLngLat(model.userLongitude!!, model.userLatitude!!, useGcj02))
    } else {
        emptyList()
    }
    return track + user
}

private fun ShareRawPoint.toSharePoint(kind: SharePointKind, latest: Boolean): ShareTrackPoint? {
    if (!validLatLng(lat, lng)) return null
    return ShareTrackPoint(
        lat = lat,
        lng = lng,
        kind = kind,
        intensityArgb = shareIntensityArgb(strong, power),
        latestObserved = latest
    )
}

private fun validLatLng(lat: Double, lng: Double): Boolean =
    !lat.isNaN() && !lng.isNaN() && lat in -90.0..90.0 && lng in -180.0..180.0

private fun mercatorY(lat: Double): Double {
    val clamped = lat.coerceIn(-85.05112878, 85.05112878)
    val radians = Math.toRadians(clamped)
    return ln(tan(Math.PI / 4.0 + radians / 2.0))
}

private fun emptyPlot() = SharePlot(
    observedLine = emptyList(),
    forecastLine = emptyList(),
    forecastConnector = null,
    observedMarkers = emptyList(),
    forecastMarkers = emptyList(),
    userMarker = null
)

private const val MIN_SPAN_DEG = 0.8
private const val FRAME_PAD_FRACTION = 0.18
