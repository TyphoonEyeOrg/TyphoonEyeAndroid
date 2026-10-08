package seamain.org.typhoonEye.ui.components

import android.graphics.Color as AndroidColor
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import seamain.org.typhoonEye.BuildConfig
import seamain.org.typhoonEye.R
import seamain.org.typhoonEye.domain.model.TyphoonPoint
import seamain.org.typhoonEye.domain.model.UserLocation
import seamain.org.typhoonEye.domain.util.CoordTransform
import seamain.org.typhoonEye.ui.util.MapBasemap
import seamain.org.typhoonEye.ui.util.MapBasemapPolicy
import seamain.org.typhoonEye.ui.util.OpenFreeMap
import seamain.org.typhoonEye.ui.util.ResolvedBasemap
import seamain.org.typhoonEye.ui.util.WindRadiiKm
import seamain.org.typhoonEye.ui.util.intensityColor
import seamain.org.typhoonEye.ui.util.resolveIntensity
import seamain.org.typhoonEye.ui.util.windCircleRing
import seamain.org.typhoonEye.ui.util.windRadii10
import seamain.org.typhoonEye.ui.util.windRadii12
import seamain.org.typhoonEye.ui.util.windRadii7
import seamain.org.typhoonEye.ui.util.windRadiusPaddingDegrees

private const val TAG = "TyphoonTrackMap"
private const val SOURCE_HISTORY = "ty-history-line"
private const val SOURCE_FORECAST = "ty-forecast-line"
private const val SOURCE_POINTS = "ty-points"
private const val SOURCE_WIND7 = "ty-wind-7"
private const val SOURCE_WIND10 = "ty-wind-10"
private const val SOURCE_WIND12 = "ty-wind-12"
private const val SOURCE_USER = "ty-user"
private const val SOURCE_USER_LINK = "ty-user-link"
private const val LAYER_HISTORY = "ty-history-layer"
private const val LAYER_FORECAST = "ty-forecast-layer"
private const val LAYER_POINTS = "ty-points-layer"
private const val LAYER_CURRENT = "ty-current-layer"
private const val LAYER_WIND7_FILL = "ty-wind-7-fill"
private const val LAYER_WIND7_LINE = "ty-wind-7-line"
private const val LAYER_WIND10_FILL = "ty-wind-10-fill"
private const val LAYER_WIND10_LINE = "ty-wind-10-line"
private const val LAYER_WIND12_FILL = "ty-wind-12-fill"
private const val LAYER_WIND12_LINE = "ty-wind-12-line"
private const val LAYER_USER_LINK = "ty-user-link-layer"
private const val LAYER_USER = "ty-user-layer"
private const val USER_COLOR = "#1E88E5"
private const val ASSET_STYLE_AMAP = "asset://map_style_amap.json"
private const val ASSET_STYLE_AMAP_DARK = "asset://map_style_amap_dark.json"
private const val DEMO_STYLE = "https://demotiles.maplibre.org/style.json"
/** Vector style needs style JSON + sprites before it reports loaded; give it longer than raster. */
private const val OPEN_STYLE_TIMEOUT_MS = 8000L

/** 七级风圈 — amber */
private const val WIND7_FILL = "#F9A825"
private const val WIND7_LINE = "#F57F17"
/** 十级风圈 — deep orange */
private const val WIND10_FILL = "#FF8A65"
private const val WIND10_LINE = "#E64A19"
/** 十二级风圈 — red */
private const val WIND12_FILL = "#EF5350"
private const val WIND12_LINE = "#C62828"

private fun isRobolectricRuntime(): Boolean =
    Build.FINGERPRINT.lowercase().contains("robolectric")

/**
 * Interactive MapLibre map. Falls back to [TrackCanvas] on preview / failure.
 *
 * Lifecycle note: MapView must not receive onStart/onResume before it is attached
 * to a window — that was crashing when opening the track tab.
 */
@Composable
fun TyphoonTrackMap(
    history: List<TyphoonPoint>,
    forecast: List<TyphoonPoint>,
    historyColor: Color,
    forecastColor: Color,
    modifier: Modifier = Modifier,
    userLocation: UserLocation? = null,
    preferredBasemap: MapBasemap = MapBasemap.Auto
) {
    val inspection = LocalInspectionMode.current
    val context = LocalContext.current
    var useFallback by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    // Prefer resolved basemap from Auto (region) or explicit user choice.
    var basemap by remember(preferredBasemap) {
        mutableStateOf(MapBasemapPolicy.resolve(preferredBasemap, context))
    }
    val useGcj02 = MapBasemapPolicy.usesGcj02(basemap)

    if (inspection || isRobolectricRuntime() || useFallback ||
        (history.isEmpty() && forecast.isEmpty())
    ) {
        if (history.isEmpty() && forecast.isEmpty()) {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.track_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            TrackCanvas(
                history = history,
                forecast = forecast,
                historyColor = historyColor,
                forecastColor = forecastColor,
                modifier = modifier
            )
        }
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val darkTheme = isSystemInDarkTheme()
    var mapReady by remember(darkTheme) { mutableStateOf(false) }
    val mapViewRef = remember { mutableStateOf<MapView?>(null) }
    val mapRef = remember { mutableStateOf<MapLibreMap?>(null) }
    val styleRef = remember { mutableStateOf<Style?>(null) }

    val historyHex = historyColor.toHexRgb()
    val forecastHex = forecastColor.toHexRgb()
    val openStyleUri = OpenFreeMap.styleUrl(darkTheme)
    val amapAssetUri = if (darkTheme) ASSET_STYLE_AMAP_DARK else ASSET_STYLE_AMAP

    val userKey = userLocation?.let { "${it.latitude},${it.longitude}" }.orEmpty()

    // Refresh track + wind layers when detail data arrives / updates.
    LaunchedEffect(history, forecast, historyHex, forecastHex, mapReady, userKey, useGcj02) {
        val style = styleRef.value ?: return@LaunchedEffect
        val map = mapRef.value ?: return@LaunchedEffect
        if (!mapReady) return@LaunchedEffect
        runCatching {
            applyTrackLayers(
                style = style,
                history = history,
                forecast = forecast,
                historyColorHex = historyHex,
                forecastColorHex = forecastHex,
                userLocation = userLocation,
                useGcj02 = useGcj02
            )
            fitCamera(map, history, forecast, userLocation, useGcj02)
        }.onFailure { e ->
            Log.e(TAG, "Failed to refresh track layers", e)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val mapView = mapViewRef.value ?: return@LifecycleEventObserver
            if (!mapView.isAttachedToWindow) return@LifecycleEventObserver
            try {
                when (event) {
                    Lifecycle.Event.ON_START -> mapView.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                    Lifecycle.Event.ON_STOP -> mapView.onStop()
                    else -> Unit
                }
            } catch (e: Exception) {
                Log.e(TAG, "MapView lifecycle error: $event", e)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapViewRef.value?.let { mv ->
                try {
                    if (mv.isAttachedToWindow) {
                        mv.onPause()
                        mv.onStop()
                    }
                    mv.onDestroy()
                } catch (e: Exception) {
                    Log.e(TAG, "MapView destroy error", e)
                }
            }
            mapViewRef.value = null
            mapRef.value = null
            styleRef.value = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        // Key on preference + theme only — runtime fallback must not recreate MapView.
        key(darkTheme, preferredBasemap) {
            AndroidView(
                factory = { ctx ->
                    try {
                        MapLibre.getInstance(ctx.applicationContext)
                    } catch (e: Exception) {
                        Log.e(TAG, "MapLibre init failed", e)
                    }

                    MapView(ctx).apply {
                        mapViewRef.value = this
                        onCreate(null)

                        setOnTouchListener { v, event ->
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                                    v.parent?.requestDisallowInterceptTouchEvent(true)
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                    v.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                            false
                        }

                        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(v: View) {
                                try {
                                    onStart()
                                    onResume()
                                } catch (e: Exception) {
                                    Log.e(TAG, "attach lifecycle failed", e)
                                    useFallback = true
                                }
                            }

                            override fun onViewDetachedFromWindow(v: View) {
                                try {
                                    onPause()
                                    onStop()
                                } catch (e: Exception) {
                                    Log.e(TAG, "detach lifecycle failed", e)
                                }
                            }
                        })

                        getMapAsync { map ->
                            try {
                                mapRef.value = map
                                // Hide stock MapLibre logo; we draw a basemap badge instead.
                                map.uiSettings.isLogoEnabled = false
                                map.uiSettings.isAttributionEnabled = true
                                map.uiSettings.isCompassEnabled = true
                                map.uiSettings.setAllGesturesEnabled(true)
                                // Keep attribution clear of our bottom-start badge.
                                runCatching {
                                    map.uiSettings.setAttributionMargins(12, 0, 12, 36)
                                }

                                fun bindStyle(style: Style) {
                                    try {
                                        styleRef.value = style
                                        // Read live basemap state (may change after Amap → open fallback).
                                        val gcj = MapBasemapPolicy.usesGcj02(basemap)
                                        applyTrackLayers(
                                            style = style,
                                            history = history,
                                            forecast = forecast,
                                            historyColorHex = historyHex,
                                            forecastColorHex = forecastHex,
                                            userLocation = userLocation,
                                            useGcj02 = gcj
                                        )
                                        fitCamera(map, history, forecast, userLocation, gcj)
                                        post { mapReady = true }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to draw track layers", e)
                                        post {
                                            loadError = ctx.getString(R.string.map_track_draw_failed)
                                            useFallback = true
                                        }
                                    }
                                }

                                fun loadOpenOrCustom() {
                                    val styleUri = BuildConfig.MAPLIBRE_STYLE_URL.trim()
                                        .ifBlank { openStyleUri }
                                    map.setStyle(Style.Builder().fromUri(styleUri)) { style ->
                                        bindStyle(style)
                                    }
                                }

                                when (basemap) {
                                    ResolvedBasemap.Amap -> {
                                        // Prefer runtime JSON so AMAP_KEY can be injected.
                                        val json = MapBasemapPolicy.amapStyleJson(darkTheme)
                                        map.setStyle(Style.Builder().fromJson(json)) { style ->
                                            bindStyle(style)
                                        }
                                        // Fallback chain: asset amap → OpenFreeMap → demo tiles
                                        postDelayed({
                                            if (!mapReady && !useFallback) {
                                                Log.w(TAG, "Amap style slow/failed, trying asset then open basemap")
                                                map.setStyle(Style.Builder().fromUri(amapAssetUri)) { style ->
                                                    if (!mapReady) bindStyle(style)
                                                }
                                                postDelayed({
                                                    if (!mapReady && !useFallback) {
                                                        // Fall back to international tiles; badge follows.
                                                        basemap = ResolvedBasemap.OpenStreet
                                                        loadOpenOrCustom()
                                                    }
                                                }, 3500)
                                            }
                                        }, 4500)
                                    }
                                    ResolvedBasemap.OpenStreet -> {
                                        loadOpenOrCustom()
                                        postDelayed({
                                            if (!mapReady && !useFallback) {
                                                map.setStyle(Style.Builder().fromUri(DEMO_STYLE)) { style ->
                                                    bindStyle(style)
                                                }
                                            }
                                        }, OPEN_STYLE_TIMEOUT_MS)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "getMapAsync failed", e)
                                post {
                                    loadError = e.message ?: ctx.getString(R.string.map_load_failed)
                                    useFallback = true
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { /* layers refreshed via LaunchedEffect */ }
            )
        }

        if (!mapReady && !useFallback) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary
            )
        }
        loadError?.takeIf { !useFallback }?.let { err ->
            Text(
                text = err,
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Bottom-start provider badge — replaces MapLibre logo after Auto resolve.
        if (!useFallback) {
            MapProviderBadge(
                basemap = basemap,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 10.dp, bottom = 10.dp)
            )
        }
    }
}

@Composable
private fun MapProviderBadge(
    basemap: ResolvedBasemap,
    modifier: Modifier = Modifier
) {
    val label = when (basemap) {
        ResolvedBasemap.Amap -> stringResource(R.string.map_badge_amap)
        ResolvedBasemap.OpenStreet -> stringResource(R.string.map_badge_open)
    }
    val icon = when (basemap) {
        ResolvedBasemap.Amap -> Icons.Outlined.Map
        ResolvedBasemap.OpenStreet -> Icons.Outlined.Public
    }
    val cd = stringResource(R.string.cd_map_provider_badge, label)
    Surface(
        modifier = modifier.semantics { contentDescription = cd },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun applyTrackLayers(
    style: Style,
    history: List<TyphoonPoint>,
    forecast: List<TyphoonPoint>,
    historyColorHex: String,
    forecastColorHex: String,
    userLocation: UserLocation? = null,
    useGcj02: Boolean = false
) {
    val layersToRemove = listOf(
        LAYER_USER, LAYER_USER_LINK,
        LAYER_CURRENT, LAYER_POINTS,
        LAYER_FORECAST, LAYER_HISTORY,
        LAYER_WIND12_LINE, LAYER_WIND12_FILL,
        LAYER_WIND10_LINE, LAYER_WIND10_FILL,
        LAYER_WIND7_LINE, LAYER_WIND7_FILL
    )
    layersToRemove.forEach { id -> runCatching { style.removeLayer(id) } }
    listOf(
        SOURCE_USER, SOURCE_USER_LINK,
        SOURCE_HISTORY, SOURCE_FORECAST, SOURCE_POINTS,
        SOURCE_WIND7, SOURCE_WIND10, SOURCE_WIND12
    ).forEach { id -> runCatching { style.removeSource(id) } }

    // Wind circles under tracks — around the latest observed point.
    val current = history.lastOrNull()
    if (current != null) {
        addWindCircleLayer(
            style = style,
            sourceId = SOURCE_WIND7,
            fillLayerId = LAYER_WIND7_FILL,
            lineLayerId = LAYER_WIND7_LINE,
            center = current,
            radii = current.windRadii7(),
            fillColor = WIND7_FILL,
            lineColor = WIND7_LINE,
            fillOpacity = 0.18f,
            lineOpacity = 0.75f,
            useGcj02 = useGcj02
        )
        addWindCircleLayer(
            style = style,
            sourceId = SOURCE_WIND10,
            fillLayerId = LAYER_WIND10_FILL,
            lineLayerId = LAYER_WIND10_LINE,
            center = current,
            radii = current.windRadii10(),
            fillColor = WIND10_FILL,
            lineColor = WIND10_LINE,
            fillOpacity = 0.22f,
            lineOpacity = 0.8f,
            useGcj02 = useGcj02
        )
        addWindCircleLayer(
            style = style,
            sourceId = SOURCE_WIND12,
            fillLayerId = LAYER_WIND12_FILL,
            lineLayerId = LAYER_WIND12_LINE,
            center = current,
            radii = current.windRadii12(),
            fillColor = WIND12_FILL,
            lineColor = WIND12_LINE,
            fillOpacity = 0.28f,
            lineOpacity = 0.85f,
            useGcj02 = useGcj02
        )
    }

    if (history.size >= 2) {
        style.addSource(GeoJsonSource(SOURCE_HISTORY, lineString(history, useGcj02)))
        style.addLayer(
            LineLayer(LAYER_HISTORY, SOURCE_HISTORY).withProperties(
                PropertyFactory.lineColor(historyColorHex),
                PropertyFactory.lineWidth(3.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineOpacity(0.92f)
            )
        )
    }

    val forecastLine = buildList {
        if (history.isNotEmpty() && forecast.isNotEmpty()) add(history.last())
        addAll(forecast)
    }
    if (forecastLine.size >= 2) {
        style.addSource(GeoJsonSource(SOURCE_FORECAST, lineString(forecastLine, useGcj02)))
        style.addLayer(
            LineLayer(LAYER_FORECAST, SOURCE_FORECAST).withProperties(
                PropertyFactory.lineColor(forecastColorHex),
                PropertyFactory.lineWidth(3f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
                PropertyFactory.lineOpacity(0.9f)
            )
        )
    }

    val pointFeatures = ArrayList<Feature>()
    history.forEachIndexed { index, point ->
        val level = resolveIntensity(point.strong, point.power)
        val color = intensityColor(level)
        val isCurrent = index == history.lastIndex
        val (lng, lat) = mapLngLat(point.lng, point.lat, useGcj02)
        val feature = Feature.fromGeometry(Point.fromLngLat(lng, lat))
        feature.addBooleanProperty("current", isCurrent)
        feature.addStringProperty("color", color.toHexRgb())
        pointFeatures += feature
    }
    forecast.forEach { point ->
        val level = resolveIntensity(point.strong, point.power)
        val color = if (level.rank > 0) intensityColor(level) else null
        val (lng, lat) = mapLngLat(point.lng, point.lat, useGcj02)
        val feature = Feature.fromGeometry(Point.fromLngLat(lng, lat))
        feature.addBooleanProperty("current", false)
        feature.addStringProperty("color", color?.toHexRgb() ?: forecastColorHex)
        pointFeatures += feature
    }

    if (pointFeatures.isNotEmpty()) {
        style.addSource(
            GeoJsonSource(SOURCE_POINTS, FeatureCollection.fromFeatures(pointFeatures))
        )
        style.addLayer(
            CircleLayer(LAYER_POINTS, SOURCE_POINTS)
                .withFilter(
                    Expression.neq(
                        Expression.get("current"),
                        Expression.literal(true)
                    )
                )
                .withProperties(
                    PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                    PropertyFactory.circleRadius(5f),
                    PropertyFactory.circleStrokeColor(AndroidColor.WHITE),
                    PropertyFactory.circleStrokeWidth(1.5f),
                    PropertyFactory.circleOpacity(0.95f)
                )
        )
        style.addLayer(
            CircleLayer(LAYER_CURRENT, SOURCE_POINTS)
                .withFilter(
                    Expression.eq(
                        Expression.get("current"),
                        Expression.literal(true)
                    )
                )
                .withProperties(
                    PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                    PropertyFactory.circleRadius(9f),
                    PropertyFactory.circleStrokeColor(AndroidColor.WHITE),
                    PropertyFactory.circleStrokeWidth(2.5f),
                    PropertyFactory.circleOpacity(0.95f)
                )
        )
    }

    // User location + dashed link to current storm center.
    val user = userLocation?.takeIf { it.isValid }
    val stormNow = history.lastOrNull()
    if (user != null) {
        val (uLng, uLat) = mapLngLat(user.longitude, user.latitude, useGcj02)
        if (stormNow != null) {
            val (sLng, sLat) = mapLngLat(stormNow.lng, stormNow.lat, useGcj02)
            val link = LineString.fromLngLats(
                listOf(
                    Point.fromLngLat(uLng, uLat),
                    Point.fromLngLat(sLng, sLat)
                )
            )
            style.addSource(GeoJsonSource(SOURCE_USER_LINK, link))
            style.addLayer(
                LineLayer(LAYER_USER_LINK, SOURCE_USER_LINK).withProperties(
                    PropertyFactory.lineColor(USER_COLOR),
                    PropertyFactory.lineWidth(2.2f),
                    PropertyFactory.lineDasharray(arrayOf(1.5f, 1.5f)),
                    PropertyFactory.lineOpacity(0.85f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
                )
            )
        }
        val userFeature = Feature.fromGeometry(Point.fromLngLat(uLng, uLat))
        style.addSource(GeoJsonSource(SOURCE_USER, userFeature))
        style.addLayer(
            CircleLayer(LAYER_USER, SOURCE_USER).withProperties(
                PropertyFactory.circleColor(USER_COLOR),
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleStrokeColor(AndroidColor.WHITE),
                PropertyFactory.circleStrokeWidth(2.5f),
                PropertyFactory.circleOpacity(0.95f)
            )
        )
    }
}

private fun addWindCircleLayer(
    style: Style,
    sourceId: String,
    fillLayerId: String,
    lineLayerId: String,
    center: TyphoonPoint,
    radii: WindRadiiKm?,
    fillColor: String,
    lineColor: String,
    fillOpacity: Float,
    lineOpacity: Float,
    useGcj02: Boolean = false
) {
    if (radii == null || !radii.hasAny) return
    val ring = windCircleRing(center.lat, center.lng, radii)
    if (ring.size < 4) return

    val coords = ArrayList<Point>(ring.size)
    ring.forEach { (lat, lng) ->
        val (mLng, mLat) = mapLngLat(lng, lat, useGcj02)
        coords.add(Point.fromLngLat(mLng, mLat))
    }
    val polygon = Polygon.fromLngLats(listOf(coords))
    style.addSource(GeoJsonSource(sourceId, polygon))
    style.addLayer(
        FillLayer(fillLayerId, sourceId).withProperties(
            PropertyFactory.fillColor(fillColor),
            PropertyFactory.fillOpacity(fillOpacity),
            PropertyFactory.fillAntialias(true)
        )
    )
    style.addLayer(
        LineLayer(lineLayerId, sourceId).withProperties(
            PropertyFactory.lineColor(lineColor),
            PropertyFactory.lineWidth(1.6f),
            PropertyFactory.lineOpacity(lineOpacity),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
}

private fun fitCamera(
    map: MapLibreMap,
    history: List<TyphoonPoint>,
    forecast: List<TyphoonPoint>,
    userLocation: UserLocation? = null,
    useGcj02: Boolean = false
) {
    val points = history + forecast
    if (points.isEmpty() && userLocation?.isValid != true) return
    val valid = points.filter { it.lat in -90.0..90.0 && it.lng in -180.0..180.0 }
    val user = userLocation?.takeIf { it.isValid }

    if (valid.isEmpty() && user == null) return

    fun ptLatLng(lng: Double, lat: Double): LatLng {
        val (mLng, mLat) = mapLngLat(lng, lat, useGcj02)
        return LatLng(mLat, mLng)
    }

    val mapped = valid.map { ptLatLng(it.lng, it.lat) }
    val userLl = user?.let { ptLatLng(it.longitude, it.latitude) }

    var minLat = mapped.minOfOrNull { it.latitude } ?: userLl!!.latitude
    var maxLat = mapped.maxOfOrNull { it.latitude } ?: userLl!!.latitude
    var minLng = mapped.minOfOrNull { it.longitude } ?: userLl!!.longitude
    var maxLng = mapped.maxOfOrNull { it.longitude } ?: userLl!!.longitude

    if (userLl != null) {
        minLat = minOf(minLat, userLl.latitude)
        maxLat = maxOf(maxLat, userLl.latitude)
        minLng = minOf(minLng, userLl.longitude)
        maxLng = maxOf(maxLng, userLl.longitude)
    }

    history.lastOrNull()?.let { current ->
        val maxR = listOfNotNull(
            current.windRadii7()?.maxKm,
            current.windRadii10()?.maxKm,
            current.windRadii12()?.maxKm
        ).maxOrNull() ?: 0.0
        if (maxR > 0.0) {
            val center = ptLatLng(current.lng, current.lat)
            val (latPad, lngPad) = windRadiusPaddingDegrees(center.latitude, maxR)
            minLat = minOf(minLat, center.latitude - latPad)
            maxLat = maxOf(maxLat, center.latitude + latPad)
            minLng = minOf(minLng, center.longitude - lngPad)
            maxLng = maxOf(maxLng, center.longitude + lngPad)
        }
    }

    if (mapped.size <= 1 && userLl == null && maxLat - minLat < 0.4 && maxLng - minLng < 0.4) {
        val p = mapped.firstOrNull() ?: return
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(p, 5.5))
        return
    }

    val latSpan = (maxLat - minLat).coerceAtLeast(0.3)
    val lngSpan = (maxLng - minLng).coerceAtLeast(0.3)
    val midLat = (minLat + maxLat) / 2.0
    val midLng = (minLng + maxLng) / 2.0

    runCatching {
        val builder = LatLngBounds.Builder()
        builder.include(LatLng(midLat - latSpan / 2, midLng - lngSpan / 2))
        builder.include(LatLng(midLat + latSpan / 2, midLng + lngSpan / 2))
        mapped.forEach { builder.include(it) }
        if (userLl != null) builder.include(userLl)
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 72))
    }.onFailure {
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(midLat, midLng), 4.5))
    }
}

/** Map display coordinates: WGS-84 as-is, or GCJ-02 when basemap is Amap. */
private fun mapLngLat(lng: Double, lat: Double, useGcj02: Boolean): Pair<Double, Double> {
    if (!useGcj02) return lng to lat
    val p = CoordTransform.wgs84ToGcj02(lng, lat)
    return p.lng to p.lat
}

private fun lineString(points: List<TyphoonPoint>, useGcj02: Boolean = false): LineString {
    val coords = ArrayList<Point>(points.size)
    points.forEach {
        val (lng, lat) = mapLngLat(it.lng, it.lat, useGcj02)
        coords.add(Point.fromLngLat(lng, lat))
    }
    return LineString.fromLngLats(coords)
}

private fun Color.toHexRgb(): String {
    val argb = toArgb()
    return String.format("#%06X", 0xFFFFFF and argb)
}
