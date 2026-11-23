package seamain.org.typhoonEye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import seamain.org.typhoonEye.ui.share.SharePointKind
import seamain.org.typhoonEye.ui.share.ShareRawPoint
import seamain.org.typhoonEye.ui.share.ShareSourceKind
import seamain.org.typhoonEye.ui.share.buildTrackShareImage
import seamain.org.typhoonEye.ui.share.extraAttributionLines
import seamain.org.typhoonEye.ui.share.forecastDashIntervals
import seamain.org.typhoonEye.ui.share.formatShareUpdatedAt
import seamain.org.typhoonEye.ui.share.layoutSharePlot
import seamain.org.typhoonEye.ui.share.requiredBasemapAttribution
import seamain.org.typhoonEye.ui.share.sanitizeAttribution
import seamain.org.typhoonEye.ui.share.shareFrame
import seamain.org.typhoonEye.ui.share.shareIntensityArgb
import seamain.org.typhoonEye.ui.share.sharePngName
import seamain.org.typhoonEye.ui.share.shareSourceName
import seamain.org.typhoonEye.ui.share.trackShareTextLines
import seamain.org.typhoonEye.ui.util.AMAP_STYLE_ATTRIBUTION
import seamain.org.typhoonEye.ui.util.MapBasemapPolicy
import seamain.org.typhoonEye.ui.util.ResolvedBasemap
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.abs

class TrackShareImageTest {

    private val openCredit = "OpenFreeMap © OpenMapTiles Data from OpenStreetMap"
    private val userLat = 22.5
    private val userLng = 114.2

    @Test
    fun fieldsPresent_andUserLocationOmittedByDefault() {
        val model = sample(includeUser = false, userLat = userLat, userLng = userLng)
        assertFalse(model.includeUserLocation)
        assertFalse(model.showsUserLocation)
        assertNull(model.userLatitude)
        assertNull(model.userLongitude)
        val lines = trackShareTextLines(model, basemapTilesIncluded = false)
        assertTrue(lines.contains("Bavi"))
        assertTrue(lines.contains("ID: 202609"))
        assertTrue(lines.contains("Source: QWeather"))
        assertTrue(lines.contains("Data updated: 1999-07-10 18:00 America/Los_Angeles (UTC-07:00)"))
        assertTrue(lines.contains("Unofficial disclaimer"))
        assertTrue(lines.contains(openCredit))
        assertTrue(lines.contains("Basemap tiles were not loaded"))
        assertFalse(lines.any { it.contains("22.5") || it.contains("114.2") })
        assertFalse(lines.any { it.contains("km") })
        assertNull(layoutSharePlot(model, useGcj02 = false, 0f, 0f, 400f, 300f).userMarker)
    }

    @Test
    fun optIn_keepsUserFix_andDefaultsOffDoesNot() {
        val optedIn = sample(includeUser = true, userLat = userLat, userLng = userLng)
        assertTrue(optedIn.showsUserLocation)
        assertEquals(userLat, optedIn.userLatitude!!, 0.0)
        assertEquals(userLng, optedIn.userLongitude!!, 0.0)
        val plot = layoutSharePlot(optedIn, useGcj02 = false, 0f, 0f, 400f, 300f)
        assertNotNull(plot.userMarker)
        assertTrue(plot.observedMarkers.none { abs(it.x - plot.userMarker!!.x) < 0.5f && abs(it.y - plot.userMarker.y) < 0.5f && it.latestObserved })
        val frame = shareFrame(optedIn, useGcj02 = false)!!
        assertTrue(frame.west < userLng)
        val hidden = shareFrame(sample(includeUser = false, userLat = 0.0, userLng = 0.0), useGcj02 = false)!!
        assertTrue("user longitude must not widen the frame", hidden.west > 100.0)
        assertTrue(hidden.east < 140.0)
    }

    @Test
    fun forecastIsNotDrawnAsObserved_andMissingPointsAreNotFilled() {
        val model = sample(includeUser = false)
        assertEquals(2, model.observed.size)
        assertEquals(1, model.forecast.size)
        assertTrue(model.observed.all { it.kind == SharePointKind.Observed })
        assertTrue(model.forecast.all { it.kind == SharePointKind.Forecast })
        assertEquals(24.0, model.forecast.single().lat, 0.0)
        assertTrue(model.observed.none { it.lat == 24.0 })
        assertTrue(model.observed.none { it.lat == 99.0 })
        assertTrue(model.forecast.none { it.lat == 99.0 })
        val plot = layoutSharePlot(model, useGcj02 = false, 10f, 20f, 300f, 200f)
        assertEquals(2, plot.observedLine.size)
        assertTrue(plot.observedLine.all { it.kind == SharePointKind.Observed })
        assertTrue(plot.forecastLine.isEmpty())
        assertNotNull(plot.forecastConnector)
        assertEquals(SharePointKind.Forecast, plot.forecastConnector!!.second.kind)
        assertEquals(plot.observedMarkers.last().x, plot.forecastConnector.first.x, 0.01f)
        assertEquals(plot.forecastMarkers.single().x, plot.forecastConnector.second.x, 0.01f)
        assertTrue(plot.forecastMarkers.all { it.kind == SharePointKind.Forecast })
        assertNotEquals(plot.observedMarkers.first().intensityArgb, plot.forecastMarkers.single().intensityArgb)
        assertEquals(floatArrayOf(16f, 12f).toList(), forecastDashIntervals().toList())
    }

    @Test
    fun singleObservedPoint_doesNotInventALine_andBlankForecastStaysBlank() {
        val model = build(
            observed = listOf(ShareRawPoint(15.0, 130.0, "台风", "12")),
            forecast = emptyList(),
            fromCache = false
        )
        assertTrue(model.forecast.isEmpty())
        assertEquals(1, model.observed.size)
        assertEquals("History", model.legendLine)
        val plot = layoutSharePlot(model, useGcj02 = false, 0f, 0f, 200f, 200f)
        assertTrue(plot.observedLine.isEmpty())
        assertTrue(plot.forecastLine.isEmpty())
        assertNull(plot.forecastConnector)
        assertEquals(1, plot.observedMarkers.size)
        assertTrue(plot.forecastMarkers.isEmpty())
    }

    @Test
    fun blankIntensity_isNotGivenACategoryColor() {
        assertNull(shareIntensityArgb("", ""))
        assertNull(shareIntensityArgb("not-a-storm", ""))
        assertNotNull(shareIntensityArgb("台风", "13"))
        assertNotEquals(shareIntensityArgb("热带风暴", "8"), shareIntensityArgb("超强台风", "17"))
        val model = build(
            observed = listOf(ShareRawPoint(15.0, 130.0, "", "")),
            forecast = listOf(ShareRawPoint(16.0, 131.0, "", "")),
            fromCache = false
        )
        assertNull(model.observed.single().intensityArgb)
        assertNull(model.forecast.single().intensityArgb)
    }

    @Test
    fun cachedCopy_isLabeled_andUnknownTimeIsNotInvented() {
        val cached = build(fromCache = true, updatedLine = "Data updated: Unknown")
        assertEquals("Saved copy", cached.cacheLine)
        assertTrue(trackShareTextLines(cached, basemapTilesIncluded = true).contains("Saved copy"))
        val live = build(fromCache = false, updatedLine = "Data updated: Unknown")
        assertNull(live.cacheLine)
        assertFalse(trackShareTextLines(live, basemapTilesIncluded = true).any { it.contains("Saved copy") })
        assertFalse(trackShareTextLines(live, basemapTilesIncluded = true).any { it.contains("Basemap tiles") })
        val zone = ZoneId.of("America/Los_Angeles")
        assertEquals("Unknown", formatShareUpdatedAt(null, zone, "Unknown"))
        assertEquals("Unknown", formatShareUpdatedAt(0L, zone, "Unknown"))
        val epoch = OffsetDateTime.parse("1999-07-11T09:00:00+08:00").toInstant().toEpochMilli()
        assertEquals(
            "1999-07-10 18:00 America/Los_Angeles (UTC-07:00)",
            formatShareUpdatedAt(epoch, zone, "Unknown")
        )
        assertEquals(
            "1999-07-11 09:00 Asia/Shanghai (UTC+08:00)",
            formatShareUpdatedAt(epoch, ZoneId.of("Asia/Shanghai"), "Unknown")
        )
        assertEquals(
            "1999-07-11 01:00 UTC (UTC+00:00)",
            formatShareUpdatedAt(epoch, ZoneId.of("UTC"), "Unknown")
        )
    }

    @Test
    fun sourceLabel_doesNotPickAnAgencyTheBuildDidNotUse() {
        assertEquals(
            "TyphoonEye relay (Juhe / QWeather)",
            shareSourceName(
                ShareSourceKind.Live, viaRelay = true, juheEnabled = true, qWeatherEnabled = true,
                relayPattern = "TyphoonEye relay (%1\$s)",
                juheName = "Juhe", qWeatherName = "QWeather",
                demoName = "Sample", unknownName = "Source not recorded"
            )
        )
        assertEquals(
            "TyphoonEye relay (QWeather)",
            shareSourceName(
                ShareSourceKind.Historical, viaRelay = true, juheEnabled = true, qWeatherEnabled = true,
                relayPattern = "TyphoonEye relay (%1\$s)",
                juheName = "Juhe", qWeatherName = "QWeather",
                demoName = "Sample", unknownName = "Source not recorded"
            )
        )
        assertEquals(
            "QWeather",
            shareSourceName(
                ShareSourceKind.Historical, viaRelay = false, juheEnabled = true, qWeatherEnabled = true,
                relayPattern = "TyphoonEye relay (%1\$s)",
                juheName = "Juhe", qWeatherName = "QWeather",
                demoName = "Sample", unknownName = "Source not recorded"
            )
        )
        assertEquals(
            "Sample",
            shareSourceName(
                ShareSourceKind.Demo, viaRelay = true, juheEnabled = true, qWeatherEnabled = true,
                relayPattern = "TyphoonEye relay (%1\$s)",
                juheName = "Juhe", qWeatherName = "QWeather",
                demoName = "Sample", unknownName = "Source not recorded"
            )
        )
        assertEquals(
            "Source not recorded",
            shareSourceName(
                ShareSourceKind.Live, viaRelay = false, juheEnabled = false, qWeatherEnabled = false,
                relayPattern = "TyphoonEye relay (%1\$s)",
                juheName = "Juhe", qWeatherName = "QWeather",
                demoName = "Sample", unknownName = "Source not recorded"
            )
        )
    }

    @Test
    fun attribution_matchesStyleCredit_andDropsSecrets() {
        assertEquals(openCredit, requiredBasemapAttribution(ResolvedBasemap.OpenStreet, openCredit))
        assertEquals(AMAP_STYLE_ATTRIBUTION, requiredBasemapAttribution(ResolvedBasemap.Amap, openCredit))
        val json = MapBasemapPolicy.amapStyleJson(darkTheme = false)
        assertTrue(json.contains(AMAP_STYLE_ATTRIBUTION))
        if (BuildConfig.AMAP_KEY.isBlank()) {
            assertFalse(json.contains("key="))
        }
        assertNull(sanitizeAttribution("© Map key=abc123"))
        assertNull(sanitizeAttribution("BEGIN PRIVATE KEY"))
        val extras = extraAttributionLines(
            openCredit,
            listOf(
                "© OpenStreetMap contributors",
                openCredit,
                "tiles?key=secret",
                "  "
            )
        )
        assertEquals(listOf("© OpenStreetMap contributors"), extras)
        val lines = trackShareTextLines(
            sample(includeUser = false),
            basemapTilesIncluded = true,
            styleAttributions = listOf("© OpenStreetMap contributors", "style?key=hidden")
        )
        assertTrue(lines.contains(openCredit))
        assertTrue(lines.contains("© OpenStreetMap contributors"))
        assertFalse(lines.any { it.contains("key=") || it.contains("hidden") })
    }

    @Test
    fun antimeridianTrack_staysShortOnTheCanvas() {
        val model = build(
            observed = listOf(
                ShareRawPoint(20.0, 178.0, "台风", "12"),
                ShareRawPoint(21.0, -179.0, "台风", "12")
            ),
            forecast = emptyList(),
            fromCache = false
        )
        val frame = shareFrame(model, useGcj02 = false)!!
        assertTrue("frame span ${frame.east - frame.west}", frame.east - frame.west < 20.0)
        val plot = layoutSharePlot(model, useGcj02 = false, 0f, 0f, 400f, 200f)
        assertEquals(2, plot.observedLine.size)
        assertTrue(plot.observedLine.all { it.kind == SharePointKind.Observed })
    }

    @Test
    fun fileName_stripsSeparators() {
        assertEquals("track-NP_2609-9.png", sharePngName("NP_../2609", 9L))
        assertFalse(sharePngName("a/b", 1L).contains("/"))
    }

    private fun sample(includeUser: Boolean, userLat: Double? = null, userLng: Double? = null) = build(
        observed = listOf(
            ShareRawPoint(20.0, 120.0, "热带风暴", "8"),
            ShareRawPoint(22.0, 122.0, "台风", "13")
        ),
        forecast = listOf(
            ShareRawPoint(24.0, 124.0, "强热带风暴", "10"),
            ShareRawPoint(99.0, 10.0, "台风", "12")
        ),
        fromCache = false,
        includeUser = includeUser,
        userLat = userLat,
        userLng = userLng
    )

    private fun build(
        observed: List<ShareRawPoint> = listOf(ShareRawPoint(20.0, 120.0, "台风", "12")),
        forecast: List<ShareRawPoint> = emptyList(),
        fromCache: Boolean,
        updatedLine: String = "Data updated: 1999-07-10 18:00 America/Los_Angeles (UTC-07:00)",
        includeUser: Boolean = false,
        userLat: Double? = null,
        userLng: Double? = null
    ) = buildTrackShareImage(
        title = "Bavi",
        stormId = "202609",
        observed = observed,
        forecast = forecast,
        idLine = "ID: 202609",
        sourceLine = "Source: QWeather",
        updatedLine = updatedLine,
        fromCache = fromCache,
        cacheLine = "Saved copy",
        disclaimer = "Unofficial disclaimer",
        attribution = openCredit,
        legendObserved = "History",
        legendForecast = "Forecast",
        legendBoth = "History · Forecast",
        demoLine = null,
        basemapUnavailableLine = "Basemap tiles were not loaded",
        includeUserLocation = includeUser,
        userLatitude = userLat,
        userLongitude = userLng
    )
}
