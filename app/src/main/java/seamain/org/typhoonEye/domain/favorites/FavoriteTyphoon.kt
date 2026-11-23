package seamain.org.typhoonEye.domain.favorites

import seamain.org.typhoonEye.domain.history.HistoricalCoverage
import seamain.org.typhoonEye.domain.history.HistoricalStorm
import seamain.org.typhoonEye.domain.history.historicalNumber
import seamain.org.typhoonEye.domain.history.historicalYearFromId
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.util.normalizeTyphoonId
import seamain.org.typhoonEye.domain.util.typhoonIdsMatch

/**
 * A typhoon the user saved on this device.
 *
 * [stableId] is [normalizeTyphoonId] of the source id, so Juhe `202609` and
 * QWeather `NP_2609` are one favorite. The row is independent of the live cache:
 * a storm leaving the active list does not delete it. [year] and the names are
 * kept so the list can render when the remote history record cannot be loaded.
 */
data class FavoriteTyphoon(
    val stableId: String,
    val sourceId: String,
    val trackId: String,
    val name: String,
    val englishName: String,
    val year: Int?,
    val basin: String,
    val number: String?,
    val status: String,
    val strong: String,
    val favoritedAtEpochMs: Long
)

/** Fields captured at the moment the user favorites a storm. */
data class FavoriteDraft(
    val sourceId: String,
    val name: String,
    val englishName: String = "",
    val year: Int? = null,
    val basin: String = HistoricalCoverage.BASIN,
    val number: String? = null,
    val status: String = "",
    val strong: String = "",
    val startTime: String = ""
)

fun Typhoon.toFavoriteDraft(): FavoriteDraft {
    val resolvedYear = resolveFavoriteYear(id, startTime, explicitYear = null)
    return FavoriteDraft(
        sourceId = id,
        name = name,
        englishName = englishName,
        year = resolvedYear,
        basin = HistoricalCoverage.BASIN,
        number = resolveFavoriteNumber(id, resolvedYear),
        status = status,
        strong = strong,
        startTime = startTime
    )
}

fun HistoricalStorm.toFavoriteDraft(): FavoriteDraft = FavoriteDraft(
    sourceId = id,
    name = name,
    englishName = "",
    year = year,
    basin = basin.ifBlank { HistoricalCoverage.BASIN },
    number = number ?: resolveFavoriteNumber(id, year),
    status = if (active) "active" else "dissipated"
)

/**
 * Year to store with a favorite. Prefers an explicit year, then a QWeather
 * `NP_YY…` id, then a 6-digit Juhe id (`202609` → 2026), then a `yyyy` prefix
 * on the storm start time.
 */
fun resolveFavoriteYear(sourceId: String, startTime: String = "", explicitYear: Int? = null): Int? {
    explicitYear?.takeIf { it in YEAR_MIN..YEAR_MAX }?.let { return it }
    historicalYearFromId(sourceId)?.let { return it }
    val digits = sourceId.filter { it.isDigit() }
    if (digits.length >= 6) {
        digits.take(4).toIntOrNull()?.takeIf { it in YEAR_MIN..YEAR_MAX }?.let { return it }
    }
    startTime.trim().take(4).toIntOrNull()?.takeIf { it in YEAR_MIN..YEAR_MAX }?.let { return it }
    return null
}

/** Conventional 6-digit number when the id encodes one. */
fun resolveFavoriteNumber(sourceId: String, year: Int?): String? {
    if (year == null) return null
    historicalNumber(sourceId, year)?.let { return it }
    val digits = sourceId.filter { it.isDigit() }
    if (digits.length == 6 && digits.take(4).toIntOrNull() == year) return digits
    return null
}

/**
 * Id passed to the historical track API. QWeather ids stay `NP_…`.
 * A numeric Juhe id becomes `NP_` plus its stable 4-digit tail (`202609` → `NP_2609`).
 */
fun favoriteTrackId(sourceId: String): String {
    val trimmed = sourceId.trim()
    if (trimmed.startsWith("NP_", ignoreCase = true)) {
        return "NP_" + trimmed.substring(3)
    }
    val stable = normalizeTyphoonId(trimmed)
    return if (stable.length == 4 && stable.all { it.isDigit() }) "NP_$stable" else trimmed
}

fun FavoriteDraft.toFavorite(savedAtEpochMs: Long): FavoriteTyphoon? {
    val stableId = normalizeTyphoonId(sourceId)
    if (stableId.isEmpty()) return null
    val resolvedYear = resolveFavoriteYear(sourceId, startTime, year)
    return FavoriteTyphoon(
        stableId = stableId,
        sourceId = sourceId.trim(),
        trackId = favoriteTrackId(sourceId),
        name = name.trim(),
        englishName = englishName.trim(),
        year = resolvedYear,
        basin = basin.ifBlank { HistoricalCoverage.BASIN },
        number = number ?: resolveFavoriteNumber(sourceId, resolvedYear),
        status = status.trim(),
        strong = strong.trim(),
        favoritedAtEpochMs = savedAtEpochMs
    )
}

fun FavoriteTyphoon.matchesId(id: String): Boolean =
    typhoonIdsMatch(sourceId, id) || typhoonIdsMatch(stableId, id) || typhoonIdsMatch(trackId, id)

fun List<FavoriteTyphoon>.containsStorm(id: String): Boolean = any { it.matchesId(id) }

fun FavoriteTyphoon.toHistoricalStorm(): HistoricalStorm {
    val resolvedYear = year
        ?: historicalYearFromId(trackId)
        ?: historicalYearFromId(sourceId)
        ?: 0
    return HistoricalStorm(
        id = trackId.ifBlank { sourceId },
        name = name.ifBlank { englishName }.ifBlank { sourceId },
        year = resolvedYear,
        basin = basin.ifBlank { HistoricalCoverage.BASIN },
        active = status == "active",
        number = number
    )
}

sealed class FavoriteOpenPlan {
    /** Still present in the loaded live list, including a storm already marked dissipated. */
    data class Live(val typhoon: Typhoon) : FavoriteOpenPlan()

    /** Not in the live list, and its year is inside the history window. */
    data class History(val storm: HistoricalStorm) : FavoriteOpenPlan()

    /**
     * History only covers the current and previous UTC+8 year. The favorite stays;
     * the remote record is not requested.
     */
    data class OutOfRange(val year: Int?) : FavoriteOpenPlan()
}

fun planFavoriteOpen(
    favorite: FavoriteTyphoon,
    active: List<Typhoon>,
    nowMs: Long
): FavoriteOpenPlan {
    val live = active.firstOrNull { favorite.matchesId(it.id) }
    if (live != null) return FavoriteOpenPlan.Live(live)
    val resolvedYear = favorite.year
        ?: historicalYearFromId(favorite.trackId)
        ?: historicalYearFromId(favorite.sourceId)
    if (resolvedYear == null || !HistoricalCoverage.isSupported(resolvedYear, nowMs)) {
        return FavoriteOpenPlan.OutOfRange(resolvedYear)
    }
    return FavoriteOpenPlan.History(favorite.toHistoricalStorm())
}

private const val YEAR_MIN = 1945
private const val YEAR_MAX = 2100
