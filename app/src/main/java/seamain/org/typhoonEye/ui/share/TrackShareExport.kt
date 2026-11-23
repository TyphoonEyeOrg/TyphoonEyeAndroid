package seamain.org.typhoonEye.ui.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import seamain.org.typhoonEye.BuildConfig
import seamain.org.typhoonEye.R
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.ui.util.MapBasemapPolicy
import seamain.org.typhoonEye.ui.util.OpenFreeMap
import seamain.org.typhoonEye.ui.util.ResolvedBasemap
import seamain.org.typhoonEye.ui.util.displayName
import seamain.org.typhoonEye.ui.util.unwrapLng
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.ZoneId
import kotlin.coroutines.resume

data class ShareSourceFlags(
    val viaRelay: Boolean,
    val juheEnabled: Boolean,
    val qWeatherEnabled: Boolean
)

sealed class TrackShareOutcome {
    data class Shared(val basemapTilesIncluded: Boolean) : TrackShareOutcome()
    data object NoTrack : TrackShareOutcome()
    data object ExportFailed : TrackShareOutcome()
    data object ShareSheetFailed : TrackShareOutcome()
}

/** Build-time source flags. Values are booleans only — keys are never copied into the image. */
fun shareSourceFlagsFromBuildConfig(): ShareSourceFlags {
    val viaRelay = BuildConfig.RELAY_BASE_URL.isNotBlank()
    val jwt = BuildConfig.QWEATHER_KID.isNotBlank() &&
        BuildConfig.QWEATHER_PROJECT_ID.isNotBlank() &&
        BuildConfig.QWEATHER_DEVELOPER_ID.isNotBlank() &&
        BuildConfig.QWEATHER_PRIVATE_KEY.isNotBlank()
    return ShareSourceFlags(
        viaRelay = viaRelay,
        juheEnabled = viaRelay || BuildConfig.JUHE_KEY.isNotBlank(),
        qWeatherEnabled = viaRelay || jwt || BuildConfig.QWEATHER_API_KEY.isNotBlank()
    )
}

/**
 * Content for the share image. User location is not a parameter: the picture
 * omits it unless a future caller passes [includeUserLocation] to [buildTrackShareImage].
 */
fun trackShareModel(
    context: Context,
    typhoon: Typhoon,
    sourceKind: ShareSourceKind,
    sourceFlags: ShareSourceFlags,
    updatedAtEpochMs: Long?,
    fromCache: Boolean,
    zone: ZoneId,
    basemap: ResolvedBasemap
): TrackShareImageModel {
    val name = typhoon.displayName(context)
    val english = typhoon.englishName.trim()
    val title = if (english.isBlank() || english.equals(name, ignoreCase = true)) {
        name
    } else {
        "$name ($english)"
    }
    val sourceName = shareSourceName(
        kind = sourceKind,
        viaRelay = sourceFlags.viaRelay,
        juheEnabled = sourceFlags.juheEnabled,
        qWeatherEnabled = sourceFlags.qWeatherEnabled,
        relayPattern = context.getString(R.string.share_image_source_relay),
        juheName = context.getString(R.string.source_juhe),
        qWeatherName = context.getString(R.string.source_qweather),
        demoName = context.getString(R.string.share_image_source_demo),
        unknownName = context.getString(R.string.share_image_source_unknown)
    )
    val stamp = formatShareUpdatedAt(
        epochMs = updatedAtEpochMs,
        zone = zone,
        unknown = context.getString(R.string.share_image_time_unknown)
    )
    val observedLabel = context.getString(R.string.legend_history)
    val forecastLabel = context.getString(R.string.legend_forecast)
    return buildTrackShareImage(
        title = title,
        stormId = typhoon.id,
        observed = typhoon.points.map { ShareRawPoint(it.lat, it.lng, it.strong, it.power) },
        forecast = typhoon.forecastPoints.map { ShareRawPoint(it.lat, it.lng, it.strong, it.power) },
        idLine = context.getString(R.string.share_id, typhoon.id),
        sourceLine = context.getString(R.string.share_image_source, sourceName),
        updatedLine = context.getString(R.string.share_image_updated, stamp),
        fromCache = fromCache,
        cacheLine = context.getString(R.string.share_image_cached),
        disclaimer = context.getString(R.string.share_image_disclaimer),
        attribution = requiredBasemapAttribution(
            basemap,
            context.getString(R.string.map_badge_open)
        ),
        legendObserved = observedLabel,
        legendForecast = forecastLabel,
        legendBoth = context.getString(R.string.share_image_legend, observedLabel, forecastLabel),
        demoLine = if (sourceKind == ShareSourceKind.Demo) {
            context.getString(R.string.share_demo_prefix)
        } else {
            null
        },
        basemapUnavailableLine = context.getString(R.string.share_image_basemap_unavailable),
        includeUserLocation = false
    )
}

fun renderTrackShareBitmap(
    model: TrackShareImageModel,
    textLines: List<String>,
    useGcj02: Boolean,
    basemap: Bitmap? = null,
    basemapPixels: ((lat: Double, lng: Double) -> Pair<Float, Float>)? = null,
    widthPx: Int = 1080,
    mapHeightPx: Int = 780
): Bitmap {
    val horizontal = 48
    val textWidth = (widthPx - horizontal * 2).coerceAtLeast(1)
    val titlePaint = textPaint(46f, Color.parseColor("#102A43"), bold = true)
    val bodyPaint = textPaint(30f, Color.parseColor("#37474F"), bold = false)
    val blocks = textLines.map { line ->
        val paint = if (line == model.title) titlePaint else bodyPaint
        wrapText(line, paint, textWidth)
    }
    val textHeight = blocks.sumOf { it.height } + (blocks.size * 12)
    val mapTop = 36 + (blocks.firstOrNull()?.height ?: 0) + 28
    val height = mapTop + mapHeightPx + 28 + textHeight + 36
    val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.parseColor("#F4F7FB"))
    var textY = 36f
    blocks.forEachIndexed { index, layout ->
        if (index == 0) {
            canvas.save()
            canvas.translate(horizontal.toFloat(), textY)
            layout.draw(canvas)
            canvas.restore()
            textY += layout.height + 16f
        }
    }
    val mapRect = RectF(36f, mapTop.toFloat(), (widthPx - 36).toFloat(), (mapTop + mapHeightPx).toFloat())
    val mapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D7E4F0") }
    canvas.drawRoundRect(mapRect, 28f, 28f, mapPaint)
    canvas.save()
    canvas.clipRect(mapRect)
    if (basemap != null) {
        canvas.drawBitmap(basemap, null, mapRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }
    val plot = if (basemap != null && basemapPixels != null) {
        plotOnBasemap(model, basemapPixels, basemap.width, basemap.height, mapRect)
    } else {
        layoutSharePlot(
            model,
            useGcj02,
            mapRect.left,
            mapRect.top,
            mapRect.width(),
            mapRect.height()
        )
    }
    drawPlot(canvas, plot)
    canvas.restore()
    textY = mapRect.bottom + 28f
    blocks.drop(1).forEach { layout ->
        canvas.save()
        canvas.translate(horizontal.toFloat(), textY)
        layout.draw(canvas)
        canvas.restore()
        textY += layout.height + 12f
    }
    return bitmap
}

suspend fun exportAndShareTrackImage(
    context: Context,
    model: TrackShareImageModel,
    basemap: ResolvedBasemap,
    darkTheme: Boolean,
    chooserTitle: String,
    subject: String
): TrackShareOutcome {
    if (!model.hasDrawableTrack) return TrackShareOutcome.NoTrack
    val useGcj02 = MapBasemapPolicy.usesGcj02(basemap)
    val captured = withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) {
        runCatching { captureBasemap(context, model, basemap, darkTheme, useGcj02) }.getOrNull()
    }
    val tiles = captured != null
    val lines = trackShareTextLines(
        model,
        basemapTilesIncluded = tiles,
        styleAttributions = captured?.attributions.orEmpty()
    )
    val bitmap = withContext(Dispatchers.Default) {
        renderTrackShareBitmap(
            model = model,
            textLines = lines,
            useGcj02 = useGcj02,
            basemap = captured?.bitmap,
            basemapPixels = captured?.pixels
        )
    }
    val uri = withContext(Dispatchers.IO) {
        runCatching { writeSharePng(context, bitmap, model.stormId) }.getOrNull()
    } ?: return TrackShareOutcome.ExportFailed
    bitmap.recycle()
    captured?.bitmap?.takeIf { it !== bitmap }?.recycle()
    val opened = withContext(Dispatchers.Main) {
        runCatching { launchShareSheet(context, uri, chooserTitle, subject) }.isSuccess
    }
    return if (opened) TrackShareOutcome.Shared(tiles) else TrackShareOutcome.ShareSheetFailed
}

internal fun sharePngName(stormId: String, nowMs: Long): String {
    val safe = stormId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(48).ifBlank { "storm" }
    return "track-$safe-$nowMs.png"
}

private data class BasemapShot(
    val bitmap: Bitmap,
    val pixels: (lat: Double, lng: Double) -> Pair<Float, Float>,
    val attributions: List<String>
)

private suspend fun captureBasemap(
    context: Context,
    model: TrackShareImageModel,
    basemap: ResolvedBasemap,
    darkTheme: Boolean,
    useGcj02: Boolean
): BasemapShot? {
    if (Build.FINGERPRINT.orEmpty().lowercase().contains("robolectric")) return null
    val frame = shareFrame(model, useGcj02) ?: return null
    return withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val bounds = runCatching {
                LatLngBounds.from(frame.north, frame.east, frame.south, frame.west)
            }.getOrNull()
            if (bounds == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            runCatching { MapLibre.getInstance(context.applicationContext) }
            val options = MapSnapshotter.Options(SNAPSHOT_WIDTH, SNAPSHOT_HEIGHT)
                .withPixelRatio(1f)
                .withRegion(bounds)
                .withLogo(false)
                .withStyleBuilder(styleBuilder(basemap, darkTheme))
            val snapshotter = try {
                MapSnapshotter(context.applicationContext, options)
            } catch (_: Exception) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            val main = Handler(Looper.getMainLooper())
            cont.invokeOnCancellation {
                main.post { runCatching { snapshotter.cancel() } }
            }
            val refLng = (frame.west + frame.east) / 2.0
            try {
                snapshotter.start(
                    MapSnapshotter.SnapshotReadyCallback { snapshot ->
                        if (!cont.isActive) return@SnapshotReadyCallback
                        val shot = runCatching { shotFrom(snapshot, model, useGcj02, refLng) }.getOrNull()
                        if (cont.isActive) cont.resume(shot)
                    },
                    MapSnapshotter.ErrorHandler {
                        if (cont.isActive) cont.resume(null)
                    }
                )
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}

private fun shotFrom(
    snapshot: MapSnapshot,
    model: TrackShareImageModel,
    useGcj02: Boolean,
    refLng: Double
): BasemapShot {
    val copy = snapshot.bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val computed = HashMap<Pair<Double, Double>, Pair<Float, Float>>()
    fun record(lat: Double, lng: Double) {
        val (displayLng, displayLat) = displayLngLat(lng, lat, useGcj02)
        val point = snapshot.pixelForLatLng(LatLng(displayLat, unwrapLng(displayLng, refLng)))
        computed[lat to lng] = point.x to point.y
    }
    model.observed.forEach { record(it.lat, it.lng) }
    model.forecast.forEach { record(it.lat, it.lng) }
    if (model.showsUserLocation) record(model.userLatitude!!, model.userLongitude!!)
    return BasemapShot(
        bitmap = copy,
        pixels = { lat, lng -> computed[lat to lng] ?: (0f to 0f) },
        attributions = snapshot.attributions?.toList().orEmpty()
    )
}

private fun styleBuilder(basemap: ResolvedBasemap, darkTheme: Boolean): Style.Builder =
    when (basemap) {
        ResolvedBasemap.Amap -> Style.Builder().fromJson(MapBasemapPolicy.amapStyleJson(darkTheme))
        ResolvedBasemap.OpenStreet -> {
            val custom = BuildConfig.MAPLIBRE_STYLE_URL.trim()
            val uri = custom.ifBlank { OpenFreeMap.styleUrl(darkTheme) }
            Style.Builder().fromUri(uri)
        }
    }

private fun plotOnBasemap(
    model: TrackShareImageModel,
    pixels: (lat: Double, lng: Double) -> Pair<Float, Float>,
    basemapWidth: Int,
    basemapHeight: Int,
    mapRect: RectF
): SharePlot {
    fun place(lat: Double, lng: Double, kind: SharePointKind, color: Int?, latest: Boolean): PlottedPoint {
        val (x, y) = pixels(lat, lng)
        val sx = if (basemapWidth > 0) mapRect.width() / basemapWidth else 1f
        val sy = if (basemapHeight > 0) mapRect.height() / basemapHeight else 1f
        return PlottedPoint(
            x = mapRect.left + x * sx,
            y = mapRect.top + y * sy,
            kind = kind,
            intensityArgb = color,
            latestObserved = latest
        )
    }
    val observed = model.observed.map {
        place(it.lat, it.lng, SharePointKind.Observed, it.intensityArgb, it.latestObserved)
    }
    val forecast = model.forecast.map {
        place(it.lat, it.lng, SharePointKind.Forecast, it.intensityArgb, latest = false)
    }
    val user = if (model.showsUserLocation) {
        place(model.userLatitude!!, model.userLongitude!!, SharePointKind.Observed, null, latest = false)
    } else {
        null
    }
    return assemblePlot(observed, forecast, user)
}

private fun drawPlot(canvas: Canvas, plot: SharePlot) {
    val observedPaint = strokePaint(Color.parseColor("#1565C0"), 8f, dashed = false)
    val forecastPaint = strokePaint(Color.parseColor("#00838F"), 7f, dashed = true)
    if (plot.observedLine.size >= 2) {
        canvas.drawPath(pathOf(plot.observedLine), observedPaint)
    }
    plot.forecastConnector?.let { (start, end) ->
        canvas.drawLine(start.x, start.y, end.x, end.y, forecastPaint)
    }
    if (plot.forecastLine.size >= 2) {
        canvas.drawPath(pathOf(plot.forecastLine), forecastPaint)
    }
    plot.forecastMarkers.forEach { marker ->
        drawMarker(canvas, marker, radius = 8f, ring = true)
    }
    plot.observedMarkers.forEach { marker ->
        val radius = if (marker.latestObserved) 16f else 9f
        drawMarker(canvas, marker, radius, ring = marker.latestObserved)
    }
    plot.userMarker?.let { marker ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E88E5")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(marker.x, marker.y, 12f, paint)
    }
}

private fun drawMarker(canvas: Canvas, marker: PlottedPoint, radius: Float, ring: Boolean) {
    val fill = marker.intensityArgb
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    if (fill == null) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.parseColor("#546E7A")
        canvas.drawCircle(marker.x, marker.y, radius, paint)
        return
    }
    paint.style = Paint.Style.FILL
    paint.color = fill
    canvas.drawCircle(marker.x, marker.y, radius, paint)
    if (ring) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.WHITE
        canvas.drawCircle(marker.x, marker.y, radius, paint)
    }
}

private fun pathOf(points: List<PlottedPoint>): Path {
    val path = Path()
    points.forEachIndexed { index, point ->
        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    return path
}

private fun strokePaint(color: Int, width: Float, dashed: Boolean): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        if (dashed) pathEffect = DashPathEffect(forecastDashIntervals(), 0f)
    }

private fun textPaint(size: Float, color: Int, bold: Boolean): TextPaint =
    TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

private fun wrapText(text: String, paint: TextPaint, width: Int): StaticLayout =
    StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(false)
        .build()

private fun writeSharePng(context: Context, bitmap: Bitmap, stormId: String): android.net.Uri {
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    val file = File(dir, sharePngName(stormId, System.currentTimeMillis()))
    FileOutputStream(file).use { out ->
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
            throw IOException("empty png")
        }
    }
    dir.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".png") && it != file }
        ?.sortedByDescending { it.lastModified() }
        ?.drop(2)
        ?.forEach { runCatching { it.delete() } }
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
}

private fun launchShareSheet(context: Context, uri: android.net.Uri, chooserTitle: String, subject: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, subject)
        clipData = ClipData.newRawUri("track", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    @Suppress("DEPRECATION")
    val targets = context.packageManager.queryIntentActivities(send, PackageManager.MATCH_DEFAULT_ONLY)
    targets.forEach { info ->
        context.grantUriPermission(
            info.activityInfo.packageName,
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }
    val chooser = Intent.createChooser(send, chooserTitle).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(chooser)
}

private const val SNAPSHOT_TIMEOUT_MS = 8_000L
private const val SNAPSHOT_WIDTH = 1008
private const val SNAPSHOT_HEIGHT = 780
