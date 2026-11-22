package seamain.org.typhoonEye.domain.favorites

import seamain.org.typhoonEye.domain.history.HistoricalCoverage
import seamain.org.typhoonEye.domain.history.HistoricalStorm
import seamain.org.typhoonEye.domain.history.historicalNumber
import seamain.org.typhoonEye.domain.history.historicalYearFromId
import seamain.org.typhoonEye.domain.model.FavoriteTyphoon
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.util.normalizeTyphoonId
import seamain.org.typhoonEye.domain.util.typhoonIdsMatch

private val NP_ID = Regex("^NP_(\\d{2})(\\d{2})$", RegexOption.IGNORE_CASE)
private val JUHE_ID = Regex("^(\\d{4})(\\d{2})$")
private val YEAR_PREFIX = Regex("^(\\d{4})")

/**
 * Stable id shared by a Juhe tfid (`202418`) and the QWeather id (`NP_2418`).
 * This is the favorites primary key, so the same storm cannot be stored twice.
 */
fun canonicalFavoriteId(rawId: String): String {
    val trimmed = rawId.trim()
    if (trimmed.isEmpty()) return ""
    val upper = trimmed.uppercase()
    if (NP_ID.matches(upper)) return upper
    val juhe = JUHE_ID.matchEntire(trimmed)
    if (juhe != null) {
        val yy = juhe.groupValues[1].toInt() % 100
        return "NP_%02d%s".format(yy, juhe.groupValues[2])
    }
    val normalized = normalizeTyphoonId(trimmed)
    if (normalized.length == 4 && normalized.all { it.isDigit() }) {
        return "NP_$normalized"
    }
    return upper
}

/**
 * Year stored with the favorite. An explicit year wins, then the year encoded
 * in the stable id, then a leading year on [startTime]. Zero means unknown.
 */
fun favoriteYear(
    rawId: String,
    startTime: String = "",
    explicitYear: Int? = null
): Int {
    if (explicitYear != null && explicitYear in 1900..2200) return explicitYear
    historicalYearFromId(canonicalFavoriteId(rawId))?.let { return it }
    val fromStart = YEAR_PREFIX.find(startTime.trim())?.groupValues?.get(1)?.toIntOrNull()
    if (fromStart != null && fromStart in 1900..2200) return fromStart
    return 0
}

fun favoriteFrom(typhoon: Typhoon, nowMs: Long): FavoriteTyphoon =
    FavoriteTyphoon(
        id = canonicalFavoriteId(typhoon.id),
        name = typhoon.name.trim().ifBlank { typhoon.englishName.trim() }.ifBlank { typhoon.id.trim() },
        englishName = typhoon.englishName.trim(),
        year = favoriteYear(typhoon.id, typhoon.startTime),
        basin = HistoricalCoverage.BASIN,
        favoritedAtEpochMs = nowMs
    )

fun favoriteFrom(storm: HistoricalStorm, nowMs: Long): FavoriteTyphoon =
    FavoriteTyphoon(
        id = canonicalFavoriteId(storm.id),
        name = storm.name.trim().ifBlank { storm.id.trim() },
        englishName = "",
        year = if (storm.year in 1900..2200) storm.year else favoriteYear(storm.id),
        basin = storm.basin.trim().ifBlank { HistoricalCoverage.BASIN }.uppercase(),
        favoritedAtEpochMs = nowMs
    )

sealed interface FavoriteOpen {
    /** Still on the active feed. Open the live detail. */
    data class Active(val typhoon: Typhoon) : FavoriteOpen

    /** Not active, and the year is inside the QWeather window. Open the historical track. */
    data class Historical(val storm: HistoricalStorm) : FavoriteOpen

    /**
     * The remote history window does not cover this storm. The favorite itself
     * is unchanged; callers show a message instead of pretending the track loaded.
     */
    data class OutOfRange(val favorite: FavoriteTyphoon) : FavoriteOpen
}

/**
 * Where a saved favorite should open.
 * A storm that is no longer active is not dropped: it opens as history when the
 * year is still served, and as [FavoriteOpen.OutOfRange] when it is not.
 */
fun resolveFavoriteOpen(
    favorite: FavoriteTyphoon,
    activeTyphoons: List<Typhoon>,
    nowMs: Long
): FavoriteOpen {
    val live = activeTyphoons.firstOrNull { typhoon ->
        typhoon.status == "active" && typhoonIdsMatch(typhoon.id, favorite.id)
    }
    if (live != null) return FavoriteOpen.Active(live)
    val idYear = historicalYearFromId(favorite.id)
    if (idYear != null && HistoricalCoverage.isSupported(idYear, nowMs)) {
        return FavoriteOpen.Historical(
            HistoricalStorm(
                id = favorite.id,
                name = favorite.name,
                year = idYear,
                basin = favorite.basin.ifBlank { HistoricalCoverage.BASIN },
                active = false,
                number = historicalNumber(favorite.id, idYear)
            )
        )
    }
    return FavoriteOpen.OutOfRange(favorite)
}
