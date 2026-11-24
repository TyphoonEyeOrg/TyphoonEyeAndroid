package seamain.org.typhoonEye

import android.graphics.Bitmap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.TyphoonPoint
import seamain.org.typhoonEye.ui.share.ShareSourceFlags
import seamain.org.typhoonEye.ui.share.ShareSourceKind
import seamain.org.typhoonEye.ui.share.renderTrackShareBitmap
import seamain.org.typhoonEye.ui.share.trackShareModel
import seamain.org.typhoonEye.ui.share.trackShareTextLines
import seamain.org.typhoonEye.ui.util.AMAP_STYLE_ATTRIBUTION
import seamain.org.typhoonEye.ui.util.ResolvedBasemap
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * On-image sentences for the four app locales. The picture is built from these lines.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackShareImageLocaleTest {

    private val epoch = OffsetDateTime.parse("1999-07-11T09:00:00+08:00").toInstant().toEpochMilli()
    private val zone = ZoneId.of("America/Los_Angeles")
    private val stamp = "1999-07-10 18:00 America/Los_Angeles (UTC-07:00)"
    private val openCredit = "OpenFreeMap © OpenMapTiles Data from OpenStreetMap"
    private val relayFlags = ShareSourceFlags(viaRelay = true, juheEnabled = true, qWeatherEnabled = true)

    private val original = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    @Test
    fun onImageText_matchesAllFourLocales() {
        val cases = listOf(
            LocaleCase(
                qualifiers = "en",
                locale = Locale.ENGLISH,
                source = "Source: TyphoonEye relay (Juhe / QWeather)",
                historySource = "Source: TyphoonEye relay (QWeather)",
                demoSource = "Source: Sample data (not a real typhoon)",
                updated = "Data updated: $stamp",
                cache = "Saved copy. The time above is when this data was stored, not a new observation.",
                disclaimer = "Typhoon Eye is an unofficial app, not an official meteorological product. Follow your local authorities.",
                basemapMissing = "Basemap tiles were not loaded. This picture shows the track only.",
                legend = "History · Forecast",
                demoBanner = "[Sample data — not a real typhoon]",
                idLine = "ID: 202609"
            ),
            LocaleCase(
                qualifiers = "zh-rCN",
                locale = Locale.SIMPLIFIED_CHINESE,
                source = "数据来源：台风眼中转（聚合数据 / 和风天气）",
                historySource = "数据来源：台风眼中转（和风天气）",
                demoSource = "数据来源：示例数据（不是真实台风）",
                updated = "数据更新时间：$stamp",
                cache = "这是已保存的副本。上方时间是这份数据写入的时间，不是新的观测。",
                disclaimer = "台风眼是非官方应用，不是官方气象产品。请遵从当地主管部门。",
                basemapMissing = "底图未加载。此图只显示路径。",
                legend = "历史 · 预报",
                demoBanner = "【示例数据，非真实台风】",
                idLine = "编号：202609"
            ),
            LocaleCase(
                qualifiers = "zh-rTW",
                locale = Locale.TRADITIONAL_CHINESE,
                source = "資料來源：颱風眼轉送（聚合數據 / 和風天氣）",
                historySource = "資料來源：颱風眼轉送（和風天氣）",
                demoSource = "資料來源：範例資料（不是真實颱風）",
                updated = "資料更新時間：$stamp",
                cache = "這是已儲存的副本。上方時間是這份資料寫入的時間，不是新的觀測。",
                disclaimer = "颱風眼是非官方應用程式，不是官方氣象產品。請遵從當地主管機關。",
                basemapMissing = "底圖未載入。此圖只顯示路徑。",
                legend = "歷史 · 預報",
                demoBanner = "【範例資料，非真實颱風】",
                idLine = "編號：202609"
            ),
            LocaleCase(
                qualifiers = "b+yue",
                locale = Locale.forLanguageTag("yue"),
                source = "資料來源：颱風眼轉送（聚合數據 / 和風天氣）",
                historySource = "資料來源：颱風眼轉送（和風天氣）",
                demoSource = "資料來源：範例資料（唔係真實颱風）",
                updated = "資料更新時間：$stamp",
                cache = "呢份係儲存咗嘅副本。上面嘅時間係寫入呢份資料嘅時間，唔係新嘅觀測。",
                disclaimer = "颱風眼係非官方應用程式，唔係官方氣象產品。請跟從當地部門。",
                basemapMissing = "底圖未載入。呢張圖只顯示路徑。",
                legend = "歷史 · 預報",
                demoBanner = "【範例資料，唔係真實颱風】",
                idLine = "編號：202609"
            )
        )
        cases.forEach { spec -> assertLocale(spec) }
    }

    @Test
    fun render_producesPngWithoutNeedingTheMapView() {
        RuntimeEnvironment.setQualifiers("en")
        Locale.setDefault(Locale.ENGLISH)
        val context = RuntimeEnvironment.getApplication()
        val model = trackShareModel(
            context = context,
            typhoon = storm(),
            sourceKind = ShareSourceKind.Live,
            sourceFlags = relayFlags,
            updatedAtEpochMs = epoch,
            fromCache = true,
            zone = zone,
            basemap = ResolvedBasemap.OpenStreet
        )
        val lines = trackShareTextLines(model, basemapTilesIncluded = false)
        val bitmap = renderTrackShareBitmap(
            model = model,
            textLines = lines,
            useGcj02 = false,
            widthPx = 360,
            mapHeightPx = 200
        )
        assertEquals(360, bitmap.width)
        assertTrue(bitmap.height > 200)
        val png = java.io.ByteArrayOutputStream()
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, png))
        assertTrue(png.size() > 200)
        bitmap.recycle()
    }

    private fun assertLocale(spec: LocaleCase) {
        RuntimeEnvironment.setQualifiers(spec.qualifiers)
        Locale.setDefault(spec.locale)
        val context = RuntimeEnvironment.getApplication()
        val live = trackShareModel(
            context, storm(), ShareSourceKind.Live, relayFlags, epoch,
            fromCache = true, zone = zone, basemap = ResolvedBasemap.OpenStreet
        )
        val liveLines = trackShareTextLines(live, basemapTilesIncluded = false)
        assertTrue(spec.qualifiers, liveLines.contains(spec.idLine))
        assertTrue(spec.qualifiers, liveLines.contains(spec.source))
        assertTrue(spec.qualifiers, liveLines.contains(spec.updated))
        assertTrue(spec.qualifiers, liveLines.contains(spec.cache))
        assertTrue(spec.qualifiers, liveLines.contains(spec.disclaimer))
        assertTrue(spec.qualifiers, liveLines.contains(openCredit))
        assertTrue(spec.qualifiers, liveLines.contains(spec.basemapMissing))
        assertTrue(spec.qualifiers, liveLines.contains(spec.legend))
        assertFalse(spec.qualifiers, liveLines.any { it.contains("22.5") || it.contains("114.2") })
        assertFalse(spec.qualifiers, liveLines.any { it.contains("key=") })

        val history = trackShareModel(
            context, storm(), ShareSourceKind.Historical, relayFlags, epoch,
            fromCache = false, zone = zone, basemap = ResolvedBasemap.Amap
        )
        val historyLines = trackShareTextLines(history, basemapTilesIncluded = true)
        assertTrue(spec.qualifiers, historyLines.contains(spec.historySource))
        assertFalse(spec.qualifiers, historyLines.contains(spec.basemapMissing))
        assertTrue(spec.qualifiers, historyLines.contains(AMAP_STYLE_ATTRIBUTION))
        assertFalse(spec.qualifiers + " history must not claim Juhe", historyLines.any { it.contains("Juhe") || it.contains("聚合") })

        val demo = trackShareModel(
            context, storm(), ShareSourceKind.Demo, relayFlags, null,
            fromCache = false, zone = zone, basemap = ResolvedBasemap.OpenStreet
        )
        val demoLines = trackShareTextLines(demo, basemapTilesIncluded = false)
        assertTrue(spec.qualifiers, demoLines.contains(spec.demoSource))
        assertTrue(spec.qualifiers, demoLines.contains(spec.demoBanner))
        assertTrue(spec.qualifiers, demoLines.any { it.contains(context.getString(R.string.share_image_time_unknown)) })
    }

    private fun storm() = Typhoon(
        id = "202609",
        name = "Bavi",
        englishName = "BAVI",
        status = "active",
        points = listOf(
            TyphoonPoint("1999-07-10T18:00+08:00", 20.0, 120.0, 990, 20, "8", "热带风暴")
        ),
        forecastPoints = listOf(
            TyphoonPoint("1999-07-11T06:00+08:00", 22.0, 122.0, 985, 25, "10", "强热带风暴")
        )
    )

    private data class LocaleCase(
        val qualifiers: String,
        val locale: Locale,
        val source: String,
        val historySource: String,
        val demoSource: String,
        val updated: String,
        val cache: String,
        val disclaimer: String,
        val basemapMissing: String,
        val legend: String,
        val demoBanner: String,
        val idLine: String
    )
}
