package seamain.org.typhoonEye.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import seamain.org.typhoonEye.R
import seamain.org.typhoonEye.domain.model.TyphoonPoint
import seamain.org.typhoonEye.domain.model.UserLocation
import seamain.org.typhoonEye.ui.util.MapBasemap
import seamain.org.typhoonEye.ui.util.WindRadiiKm
import seamain.org.typhoonEye.ui.util.intensityColor
import seamain.org.typhoonEye.ui.util.resolveIntensity
import seamain.org.typhoonEye.ui.util.windCircleRing
import seamain.org.typhoonEye.ui.util.windRadii10
import seamain.org.typhoonEye.ui.util.windRadii12
import seamain.org.typhoonEye.ui.util.windRadii7

private val Wind7Color = Color(0xFFF9A825)
private val Wind10Color = Color(0xFFFF8A65)
private val Wind12Color = Color(0xFFEF5350)

@Composable
fun TrackMapCard(
    history: List<TyphoonPoint>,
    forecast: List<TyphoonPoint>,
    modifier: Modifier = Modifier,
    userLocation: UserLocation? = null,
    mapBasemap: MapBasemap = MapBasemap.Auto
) {
    val historyColor = MaterialTheme.colorScheme.primary
    val forecastColor = MaterialTheme.colorScheme.tertiary
    val youColor = MaterialTheme.colorScheme.secondary
    val current = history.lastOrNull()
    val hasWind = current?.let {
        it.windRadii7() != null || it.windRadii10() != null || it.windRadii12() != null
    } == true
    val hasYou = userLocation?.isValid == true

    Card(
        modifier = modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp)
        ) {
            Text(
                text = stringResource(R.string.track_map_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = when {
                    hasYou -> stringResource(R.string.track_map_hint_with_you)
                    hasWind -> stringResource(R.string.track_map_hint_with_wind)
                    else -> stringResource(R.string.track_map_hint_no_wind)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                if (history.isEmpty() && forecast.isEmpty()) {
                    Text(
                        text = stringResource(R.string.track_no_data),
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    TyphoonTrackMap(
                        history = history,
                        forecast = forecast,
                        historyColor = historyColor,
                        forecastColor = forecastColor,
                        userLocation = userLocation,
                        preferredBasemap = mapBasemap,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LegendDot(color = historyColor, label = stringResource(R.string.legend_history))
                Spacer(modifier = Modifier.width(12.dp))
                LegendDot(color = forecastColor, label = stringResource(R.string.legend_forecast))
                if (hasYou) {
                    Spacer(modifier = Modifier.width(12.dp))
                    LegendDot(color = youColor, label = stringResource(R.string.legend_you))
                }
                if (hasWind) {
                    Spacer(modifier = Modifier.width(12.dp))
                    LegendDot(color = Wind7Color, label = stringResource(R.string.wind_radius_7))
                    Spacer(modifier = Modifier.width(12.dp))
                    LegendDot(color = Wind10Color, label = stringResource(R.string.wind_radius_10))
                    Spacer(modifier = Modifier.width(12.dp))
                    LegendDot(color = Wind12Color, label = stringResource(R.string.wind_radius_12))
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Offline / preview schematic projection — used when MapLibre is unavailable. */
@Composable
fun TrackCanvas(
    history: List<TyphoonPoint>,
    forecast: List<TyphoonPoint>,
    modifier: Modifier = Modifier,
    historyColor: Color = Color(0xFF4FC3F7),
    forecastColor: Color = Color(0xFF00838F)
) {
    val all = history + forecast
    if (all.isEmpty()) return

    Canvas(modifier = modifier) {
        val pad = 12f
        val current = history.lastOrNull()
        val windRings = buildList {
            current?.let { c ->
                c.windRadii7()?.let { add(Wind7Color to it) }
                c.windRadii10()?.let { add(Wind10Color to it) }
                c.windRadii12()?.let { add(Wind12Color to it) }
            }
        }

        // Expand bounds to include wind circles so they fit on canvas.
        var minLat = all.minOf { it.lat }
        var maxLat = all.maxOf { it.lat }
        var minLng = all.minOf { it.lng }
        var maxLng = all.maxOf { it.lng }
        if (current != null && windRings.isNotEmpty()) {
            windRings.forEach { (_, radii) ->
                windCircleRing(current.lat, current.lng, radii, stepsPerQuadrant = 8).forEach { (lat, lng) ->
                    minLat = minOf(minLat, lat)
                    maxLat = maxOf(maxLat, lat)
                    minLng = minOf(minLng, lng)
                    maxLng = maxOf(maxLng, lng)
                }
            }
        }

        val latSpan = (maxLat - minLat).coerceAtLeast(0.5)
        val lngSpan = (maxLng - minLng).coerceAtLeast(0.5)

        fun project(lat: Double, lng: Double): Offset {
            val x = pad + ((lng - minLng) / lngSpan * (size.width - pad * 2)).toFloat()
            val y = pad + ((maxLat - lat) / latSpan * (size.height - pad * 2)).toFloat()
            return Offset(x, y)
        }

        fun drawWind(radii: WindRadiiKm, color: Color) {
            if (current == null) return
            val ring = windCircleRing(current.lat, current.lng, radii)
            if (ring.size < 3) return
            val path = Path()
            val first = project(ring.first().first, ring.first().second)
            path.moveTo(first.x, first.y)
            for (i in 1 until ring.size) {
                val p = project(ring[i].first, ring[i].second)
                path.lineTo(p.x, p.y)
            }
            path.close()
            drawPath(path = path, color = color.copy(alpha = 0.18f))
            drawPath(
                path = path,
                color = color.copy(alpha = 0.8f),
                style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        // Largest wind circle first.
        windRings.forEach { (color, radii) -> drawWind(radii, color) }

        fun drawPolyline(points: List<TyphoonPoint>, color: Color, dashed: Boolean) {
            if (points.size < 2) return
            val path = Path()
            val first = project(points.first().lat, points.first().lng)
            path.moveTo(first.x, first.y)
            for (i in 1 until points.size) {
                val p = project(points[i].lat, points[i].lng)
                path.lineTo(p.x, p.y)
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(
                    width = 5f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = if (dashed) {
                        PathEffect.dashPathEffect(floatArrayOf(16f, 12f))
                    } else null
                )
            )
        }

        if (history.isNotEmpty() && forecast.isNotEmpty()) {
            val a = project(history.last().lat, history.last().lng)
            val b = project(forecast.first().lat, forecast.first().lng)
            drawLine(
                color = forecastColor.copy(alpha = 0.45f),
                start = a,
                end = b,
                strokeWidth = 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
            )
        }

        drawPolyline(history, historyColor, dashed = false)
        drawPolyline(forecast, forecastColor, dashed = true)

        history.forEachIndexed { index, point ->
            val o = project(point.lat, point.lng)
            val level = resolveIntensity(point.strong, point.power)
            val c = intensityColor(level)
            val radius = if (index == history.lastIndex) 10f else 5f
            drawCircle(color = c.copy(alpha = 0.25f), radius = radius + 6f, center = o)
            drawCircle(color = c, radius = radius, center = o)
            if (index == history.lastIndex) {
                drawCircle(color = Color.White, radius = 3.5f, center = o)
            }
        }

        forecast.forEach { point ->
            val o = project(point.lat, point.lng)
            val level = resolveIntensity(point.strong, point.power)
            val c = if (level.rank > 0) intensityColor(level) else forecastColor
            drawCircle(color = c.copy(alpha = 0.9f), radius = 4.5f, center = o)
            drawCircle(color = Color.White.copy(alpha = 0.7f), radius = 1.8f, center = o)
        }
    }
}
