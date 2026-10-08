package seamain.org.typhoonEye.ui.util

import android.content.Context
import android.telephony.TelephonyManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import seamain.org.typhoonEye.BuildConfig
import seamain.org.typhoonEye.data.preferences.AppLanguage
import java.util.Locale

/**
 * Basemap preference for the track map.
 * Docs: https://lbs.amap.com/api
 */
enum class MapBasemap {
    /** Mainland China → Amap, others → OpenStreet (default). */
    Auto,

    /** Amap Web raster tiles (GCJ-02). */
    Amap,

    /** OpenFreeMap vector style, OpenStreetMap data (WGS-84). See [OpenFreeMap]. */
    OpenStreet;

    companion object {
        fun fromStorage(value: String?): MapBasemap =
            entries.find { it.name.equals(value, ignoreCase = true) } ?: Auto
    }
}

/**
 * International basemap: OpenFreeMap public instance (https://openfreemap.org).
 * No API key, no registration. Its terms allow the public instance to stop without
 * notice, so the style URL lives only here; change [STYLES_BASE_URL] to switch.
 * Attribution comes from the style's sources and MapLibre shows it automatically.
 */
object OpenFreeMap {
    const val STYLES_BASE_URL = "https://tiles.openfreemap.org/styles/"
    const val LIGHT_STYLE = "liberty"
    const val DARK_STYLE = "dark"

    fun styleUrl(darkTheme: Boolean): String =
        STYLES_BASE_URL + if (darkTheme) DARK_STYLE else LIGHT_STYLE
}

/** Effective basemap after resolving [MapBasemap.Auto] / BuildConfig override. */
enum class ResolvedBasemap {
    Amap,
    OpenStreet
}

object MapBasemapPolicy {

    /**
     * Resolve the basemap actually loaded.
     * [BuildConfig.MAP_BASEMAP] force: `amap` | `open` wins over preference.
     * [MapBasemap.Auto] uses region (SIM / locale).
     */
    fun resolve(preferred: MapBasemap, context: Context? = null): ResolvedBasemap {
        when (BuildConfig.MAP_BASEMAP.lowercase(Locale.ROOT)) {
            "amap", "gaode", "cn" -> return ResolvedBasemap.Amap
            "open", "carto", "osm", "intl" -> return ResolvedBasemap.OpenStreet
        }
        return when (preferred) {
            MapBasemap.Amap -> ResolvedBasemap.Amap
            MapBasemap.OpenStreet -> ResolvedBasemap.OpenStreet
            MapBasemap.Auto -> {
                if (context != null && isMainlandChinaUser(context)) {
                    ResolvedBasemap.Amap
                } else {
                    ResolvedBasemap.OpenStreet
                }
            }
        }
    }

    fun usesGcj02(basemap: ResolvedBasemap): Boolean = basemap == ResolvedBasemap.Amap

    /**
     * Mainland China:
     * - SIM / network country `cn`, or
     * - App / system locale simplified Chinese, excluding TW/HK/MO.
     */
    fun isMainlandChinaUser(context: Context): Boolean {
        val simCountry = runCatching {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            tm?.networkCountryIso?.takeIf { it.isNotBlank() } ?: tm?.simCountryIso
        }.getOrNull()?.lowercase(Locale.ROOT)

        if (simCountry == "cn") return true
        if (simCountry in setOf("tw", "hk", "mo")) return false

        val appLang = AppLanguage.current()
        if (appLang == AppLanguage.ZhHant || appLang == AppLanguage.Yue) return false
        if (appLang == AppLanguage.ZhHans) return true

        val locales = AppCompatDelegate.getApplicationLocales().let {
            if (it.isEmpty) LocaleListCompat.getAdjustedDefault() else it
        }
        for (i in 0 until locales.size()) {
            val locale = locales[i] ?: continue
            val country = locale.country.uppercase(Locale.ROOT)
            if (country in setOf("TW", "HK", "MO")) return false
            if (country == "CN") return true
            if (locale.language.equals("zh", ignoreCase = true) &&
                !locale.script.equals("Hant", ignoreCase = true)
            ) {
                return true
            }
        }
        return false
    }

    fun amapStyleJson(darkTheme: Boolean): String {
        val key = BuildConfig.AMAP_KEY.trim()
        val keyQuery = if (key.isNotEmpty()) "&key=${key.urlEncode()}" else ""
        val styleCode = if (darkTheme) "8" else "7"
        val hosts = listOf("webrd01", "webrd02", "webrd03", "webrd04")
        val tiles = hosts.joinToString(",\n        ") { host ->
            "\"https://$host.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=$styleCode&x={x}&y={y}&z={z}$keyQuery\""
        }
        return """
            {
              "version": 8,
              "name": "TyphoonEye Amap",
              "sources": {
                "amap-tiles": {
                  "type": "raster",
                  "tiles": [
                    $tiles
                  ],
                  "tileSize": 256,
                  "attribution": "© 高德地图",
                  "maxzoom": 18
                }
              },
              "layers": [
                {
                  "id": "amap-tiles",
                  "type": "raster",
                  "source": "amap-tiles",
                  "minzoom": 0,
                  "maxzoom": 18
                }
              ]
            }
        """.trimIndent()
    }

    private fun String.urlEncode(): String =
        java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
}
