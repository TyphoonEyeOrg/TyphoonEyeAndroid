package seamain.org.typhoonEye.ui.util

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import seamain.org.typhoonEye.R
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.TyphoonPoint
import seamain.org.typhoonEye.ui.theme.IntensitySts
import seamain.org.typhoonEye.ui.theme.IntensitySty
import seamain.org.typhoonEye.ui.theme.IntensitySuper
import seamain.org.typhoonEye.ui.theme.IntensityTd
import seamain.org.typhoonEye.ui.theme.IntensityTs
import seamain.org.typhoonEye.ui.theme.IntensityTy
import java.util.Locale

enum class IntensityLevel(@param:StringRes val labelRes: Int, val shortLabel: String, val rank: Int) {
    TD(R.string.intensity_td, "TD", 1),
    TS(R.string.intensity_ts, "TS", 2),
    STS(R.string.intensity_sts, "STS", 3),
    TY(R.string.intensity_ty, "TY", 4),
    STY(R.string.intensity_sty, "STY", 5),
    SUPER(R.string.intensity_super, "SuperTY", 6),
    UNKNOWN(R.string.intensity_unknown, "?", 0)
}

fun IntensityLevel.label(context: Context): String = context.getString(labelRes)

@Composable
fun IntensityLevel.localizedLabel(): String = stringResource(labelRes)

fun resolveIntensity(strong: String, power: String = ""): IntensityLevel {
    val text = "$strong $power".lowercase()
    return when {
        text.contains("超强") || text.contains("超強") || text.contains("superty") -> IntensityLevel.SUPER
        text.contains("强台风") || text.contains("強烈颱風") || text.contains("强颱風") ||
            text.contains("sty") -> IntensityLevel.STY
        text.contains("强热带风暴") || text.contains("強烈熱帶風暴") || text.contains("sts") ->
            IntensityLevel.STS
        text.contains("台风") || text.contains("颱風") || text == "ty" || text.contains(" ty") ->
            IntensityLevel.TY
        text.contains("热带风暴") || text.contains("熱帶風暴") || text.contains("ts") ->
            IntensityLevel.TS
        text.contains("热带低压") || text.contains("熱帶低") || text.contains("td") ->
            IntensityLevel.TD
        else -> {
            val p = power.toIntOrNull()
            when {
                p == null -> IntensityLevel.UNKNOWN
                p >= 16 -> IntensityLevel.SUPER
                p >= 14 -> IntensityLevel.STY
                p >= 12 -> IntensityLevel.TY
                p >= 10 -> IntensityLevel.STS
                p >= 8 -> IntensityLevel.TS
                p > 0 -> IntensityLevel.TD
                else -> IntensityLevel.UNKNOWN
            }
        }
    }
}

fun intensityColor(level: IntensityLevel): Color = when (level) {
    IntensityLevel.TD -> IntensityTd
    IntensityLevel.TS -> IntensityTs
    IntensityLevel.STS -> IntensitySts
    IntensityLevel.TY -> IntensityTy
    IntensityLevel.STY -> IntensitySty
    IntensityLevel.SUPER -> IntensitySuper
    IntensityLevel.UNKNOWN -> Color(0xFF78909C)
}

fun Typhoon.currentIntensity(): IntensityLevel {
    val last = points.lastOrNull()
    return resolveIntensity(strong.ifBlank { last?.strong.orEmpty() }, last?.power.orEmpty())
}

fun Typhoon.latestPoint(): TyphoonPoint? = points.lastOrNull()

/** Prefer a readable storm name; Juhe uses intensity as name for unnamed lows. */
fun Typhoon.displayName(context: Context): String {
    val cn = name.trim()
    val en = englishName.trim()
    val looksLikeIntensity = cn.isBlank() ||
        cn == strong.trim() ||
        cn in INTENSITY_AS_NAME ||
        en.equals("NAMELESS", ignoreCase = true)
    return when {
        !looksLikeIntensity -> cn
        en.isNotBlank() && !en.equals("NAMELESS", ignoreCase = true) -> en
        id.isNotBlank() -> context.getString(R.string.display_name_low_id, id)
        cn.isNotBlank() -> cn
        else -> context.getString(R.string.display_name_active_fallback)
    }
}

/**
 * Map a provider intensity label (Juhe / QWeather mappers produce Chinese text such as
 * 强热带风暴) to a level, or null when the text is not a known category.
 * Text only, no wind-force fallback, so an unknown label is shown as-is.
 */
fun intensityLevelForLabel(raw: String): IntensityLevel? {
    if (raw.isBlank()) return null
    return resolveIntensity(raw.trim()).takeIf { it != IntensityLevel.UNKNOWN }
}

/** Provider intensity text in the UI language (F-Droid !45561: no Chinese in English UI). */
fun localizeIntensityLabel(context: Context, raw: String): String =
    intensityLevelForLabel(raw)?.label(context) ?: raw.trim()

fun TyphoonPoint.displayIntensity(context: Context): String =
    if (strong.isNotBlank()) {
        localizeIntensityLabel(context, strong)
    } else if (power.isNotBlank()) {
        context.getString(R.string.power_level_format, power)
    } else {
        "—"
    }

/**
 * List-card subtitle: English name and id, each once. QWeather storms carry the id
 * in [Typhoon.englishName], which used to render as "NP_2629 · NP_2629".
 */
fun Typhoon.listSubtitle(): String {
    val en = englishName.trim()
    val showEnglish = en.isNotBlank() &&
        !en.equals(name.trim(), ignoreCase = true) &&
        !en.equals(id.trim(), ignoreCase = true)
    return buildList {
        if (showEnglish) add(en)
        if (id.isNotBlank()) add(id)
    }.joinToString(" · ")
}

/** Resolve raw direction text to a stable key used for localization. */
fun resolveDirectionKey(raw: String): String? {
    val t = raw.trim()
    if (t.isBlank()) return null
    val key = t.uppercase(Locale.ROOT)
    return DIRECTION_KEYS[key] ?: DIRECTION_KEYS[t]
}

fun localizeDirection(context: Context, raw: String): String {
    val key = resolveDirectionKey(raw) ?: return raw.trim()
    val res = DIRECTION_STRINGS[key] ?: return raw.trim()
    return context.getString(res)
}

/** @deprecated Prefer [localizeDirection] / [moveLabel] with Context. */
fun normalizeMoveDirection(raw: String): String {
    val key = resolveDirectionKey(raw) ?: return raw.trim()
    return FALLBACK_DIRECTION_ZH[key] ?: raw.trim()
}

fun TyphoonPoint.moveLabel(context: Context): String =
    listOf(
        localizeDirection(context, moveDirection).takeIf { it.isNotBlank() },
        moveSpeed.takeIf { it.isNotBlank() }?.let { speed ->
            when {
                speed.contains("km", ignoreCase = true) ||
                    speed.contains("公里") ||
                    speed.contains("千米") -> speed
                else -> "$speed km/h"
            }
        }
    ).filterNotNull().joinToString(" · ").ifBlank { "—" }

fun formatPressure(hPa: Int): String = "$hPa hPa"

/**
 * Human-friendly observation / bulletin time.
 *
 * Accepts common Juhe / QWeather forms:
 * - `2026-07-10 14:00:00`
 * - `2026-07-11T09:00+08:00`
 * - `2026-07-11T09:00:00Z`
 * - already-compact `2026-07-10 14:00`
 *
 * Output (default locale):
 * - same calendar day → `今天 14:00` / `Today 14:00`
 * - same year → `7月10日 14:00` / `Jul 10, 14:00`
 * - otherwise → `2026-07-10 14:00`
 */
/**
 * Human-friendly observation / bulletin time.
 *
 * Accepts common Juhe / QWeather forms:
 * - `2026-07-10 14:00:00`
 * - `2026-07-11T09:00+08:00`
 * - `2026-07-11T09:00:00Z`
 * - already-compact `2026-07-10 14:00`
 *
 * Output (default locale):
 * - same calendar day → `今天 14:00` / `Today 14:00`
 * - yesterday → `昨天 14:00` / `Yesterday 14:00`
 * - same year → `7月10日 14:00` / `Jul 10, 14:00`
 * - otherwise → `2026-07-10 14:00`
 */
fun formatObservationTime(
    raw: String,
    context: Context? = null,
    showRelative: Boolean = false
): String {
    val text = raw.trim()
    if (text.isEmpty()) return "—"
    val epochMs = parseFlexibleDateTime(text) ?: return fallbackStripSeconds(text)
    val formattedDate = formatDateTimeFriendly(epochMs, context)
    if (!showRelative) return formattedDate

    val deltaSec = ((System.currentTimeMillis() - epochMs) / 1000L)
    if (deltaSec in 0..86400 * 2) {
        val relativeLabel = when {
            deltaSec < 60L -> context?.getString(R.string.updated_just_now)
            deltaSec < 3600L -> {
                val mins = (deltaSec / 60L).toInt()
                context?.getString(R.string.time_minutes_ago, mins)
            }
            deltaSec < 86400L -> {
                val hours = (deltaSec / 3600L).toInt()
                context?.getString(R.string.time_hours_ago, hours)
            }
            else -> null
        }
        if (relativeLabel != null && context != null) {
            return context.getString(R.string.time_observed_with_relative, formattedDate, relativeLabel)
        }
    }
    return formattedDate
}

/** Relative / clock label for "data refreshed at" (epoch millis). */
fun formatDataUpdatedAt(context: Context, epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (epochMs <= 0L) return "—"
    val deltaSec = ((nowMs - epochMs) / 1000L).coerceAtLeast(0L)
    return when {
        deltaSec < 45L -> context.getString(R.string.updated_just_now)
        deltaSec < 60L * 60L -> {
            val mins = (deltaSec / 60L).coerceAtLeast(1L).toInt()
            context.getString(R.string.updated_minutes_ago, mins)
        }
        isSameLocalDay(epochMs, nowMs) -> {
            val clock = formatClockHm(epochMs)
            context.getString(R.string.updated_today_at, clock)
        }
        else -> {
            val stamp = formatDateTimeFriendly(epochMs, context)
            context.getString(R.string.updated_at, stamp)
        }
    }
}

private fun fallbackStripSeconds(text: String): String =
    text.replace(Regex("""(\d{1,2}:\d{2}):\d{2}\s*$"""), "$1")
        .replace('T', ' ')
        .replace(Regex("""([+-]\d{2}:?\d{2}|Z)$"""), "")
        .trim()

private fun parseFlexibleDateTime(raw: String): Long? {
    val candidates = buildList {
        add(raw)
        // Normalize space to T for ISO parsers
        if (' ' in raw && 'T' !in raw) add(raw.replace(' ', 'T'))
        // Drop trailing zone for some formatters
        add(raw.replace(Regex("""([+-]\d{2}:\d{2}|Z)$"""), ""))
        add(raw.replace(Regex("""([+-]\d{2}:\d{2}|Z)$"""), "").replace(' ', 'T'))
    }.distinct()

    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ssXX",
        "yyyy-MM-dd'T'HH:mmXXX",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy/MM/dd HH:mm",
        "yyyyMMddHHmm",
        "yyyyMMddHH"
    )
    for (value in candidates) {
        for (pattern in patterns) {
            runCatching {
                val formatter = java.time.format.DateTimeFormatter.ofPattern(pattern)
                    .withZone(java.time.ZoneId.systemDefault())
                // Prefer OffsetDateTime when zone present
                if (pattern.contains("X")) {
                    val odt = java.time.OffsetDateTime.parse(value, java.time.format.DateTimeFormatter.ofPattern(pattern))
                    return odt.toInstant().toEpochMilli()
                }
                val ldt = java.time.LocalDateTime.parse(value, formatter)
                return ldt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        }
        runCatching {
            return java.time.Instant.parse(value).toEpochMilli()
        }
        runCatching {
            return java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
        }
    }
    return null
}

private fun formatDateTimeFriendly(
    epochMs: Long,
    context: Context? = null,
    nowMs: Long = System.currentTimeMillis()
): String {
    val zone = java.time.ZoneId.systemDefault()
    val dt = java.time.Instant.ofEpochMilli(epochMs).atZone(zone)
    val now = java.time.Instant.ofEpochMilli(nowMs).atZone(zone)
    val locale = Locale.getDefault()
    val timeStr = dt.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

    val todayDate = now.toLocalDate()
    val yesterdayDate = todayDate.minusDays(1)
    val targetDate = dt.toLocalDate()

    return when {
        targetDate == todayDate -> {
            context?.getString(R.string.time_today_format, timeStr)
                ?: if (locale.language == "zh" || locale.language == "yue") "今天 $timeStr" else "Today $timeStr"
        }
        targetDate == yesterdayDate -> {
            context?.getString(R.string.time_yesterday_format, timeStr)
                ?: if (locale.language == "zh" || locale.language == "yue") "昨天 $timeStr" else "Yesterday $timeStr"
        }
        dt.year == now.year && (locale.language == "zh" || locale.language == "yue") -> {
            dt.format(java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm"))
        }
        dt.year == now.year -> {
            dt.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm", locale))
        }
        locale.language == "zh" || locale.language == "yue" -> {
            dt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm"))
        }
        else -> {
            dt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        }
    }
}

private fun formatClockHm(epochMs: Long): String {
    val dt = java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault())
    return dt.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
}

private fun isSameLocalDay(aMs: Long, bMs: Long): Boolean {
    val zone = java.time.ZoneId.systemDefault()
    val a = java.time.Instant.ofEpochMilli(aMs).atZone(zone).toLocalDate()
    val b = java.time.Instant.ofEpochMilli(bMs).atZone(zone).toLocalDate()
    return a == b
}

fun formatCoordinate(lat: Double, lng: Double): String {
    val latDir = if (lat >= 0) "N" else "S"
    val lngDir = if (lng >= 0) "E" else "W"
    return String.format("%.1f°%s, %.1f°%s", kotlin.math.abs(lat), latDir, kotlin.math.abs(lng), lngDir)
}

private val INTENSITY_AS_NAME = setOf(
    "热带低压", "热带风暴", "强热带风暴", "台风", "强台风", "超强台风",
    "熱帶低氣壓", "熱帶性低氣壓", "熱帶風暴", "強烈熱帶風暴", "颱風", "強烈颱風", "超強颱風",
    "Tropical Depression", "Tropical Storm", "Severe Tropical Storm",
    "Typhoon", "Severe Typhoon", "Super Typhoon"
)

private val DIRECTION_KEYS = mapOf(
    "N" to "N", "S" to "S", "E" to "E", "W" to "W",
    "NE" to "NE", "NW" to "NW", "SE" to "SE", "SW" to "SW",
    "NNE" to "NNE", "NNW" to "NNW", "ENE" to "ENE", "ESE" to "ESE",
    "SSE" to "SSE", "SSW" to "SSW", "WSW" to "WSW", "WNW" to "WNW",
    "北" to "N", "南" to "S", "东" to "E", "東" to "E", "西" to "W",
    "东北" to "NE", "東北" to "NE", "西北" to "NW",
    "东南" to "SE", "東南" to "SE", "西南" to "SW",
    "北东北" to "NNE", "北東北" to "NNE", "北西北" to "NNW",
    "东东北" to "ENE", "東東北" to "ENE", "东东南" to "ESE", "東東南" to "ESE",
    "南东南" to "SSE", "南東南" to "SSE", "南西南" to "SSW",
    "西西南" to "WSW", "西西北" to "WNW",
    "北西" to "NW", "北东" to "NE", "北東" to "NE",
    "南西" to "SW", "南东" to "SE", "南東" to "SE",
    "西北西" to "NWW", "东北东" to "NEE", "東北東" to "NEE",
    "西南西" to "SWW", "东南东" to "SEE", "東南東" to "SEE",
    "西北偏西" to "NWW", "东北偏东" to "NEE", "東北偏東" to "NEE",
    "西南偏西" to "SWW", "东南偏东" to "SEE", "東南偏東" to "SEE"
)

private val DIRECTION_STRINGS = mapOf(
    "N" to R.string.direction_n,
    "S" to R.string.direction_s,
    "E" to R.string.direction_e,
    "W" to R.string.direction_w,
    "NE" to R.string.direction_ne,
    "NW" to R.string.direction_nw,
    "SE" to R.string.direction_se,
    "SW" to R.string.direction_sw,
    "NNE" to R.string.direction_nne,
    "NNW" to R.string.direction_nnw,
    "ENE" to R.string.direction_ene,
    "ESE" to R.string.direction_ese,
    "SSE" to R.string.direction_sse,
    "SSW" to R.string.direction_ssw,
    "WSW" to R.string.direction_wsw,
    "WNW" to R.string.direction_wnw,
    "NWW" to R.string.direction_nww,
    "NEE" to R.string.direction_nee,
    "SWW" to R.string.direction_sww,
    "SEE" to R.string.direction_see
)

private val FALLBACK_DIRECTION_ZH = mapOf(
    "N" to "北", "S" to "南", "E" to "东", "W" to "西",
    "NE" to "东北", "NW" to "西北", "SE" to "东南", "SW" to "西南",
    "NNE" to "北东北", "NNW" to "北西北", "ENE" to "东东北", "ESE" to "东东南",
    "SSE" to "南东南", "SSW" to "南西南", "WSW" to "西西南", "WNW" to "西西北",
    "NWW" to "西北偏西", "NEE" to "东北偏东", "SWW" to "西南偏西", "SEE" to "东南偏东"
)
