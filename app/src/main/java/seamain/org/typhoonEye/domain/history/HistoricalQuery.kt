package seamain.org.typhoonEye.domain.history

import seamain.org.typhoonEye.domain.model.RelayLimitedException
import seamain.org.typhoonEye.domain.model.Typhoon
import java.time.Instant
import java.time.ZoneOffset

/**
 * Northwest Pacific storm as returned by QWeather's storm list.
 * [number] is set only when [id] is `NP_YYNN` and that year matches [year].
 */
data class HistoricalStorm(
    val id: String,
    val name: String,
    val year: Int,
    val basin: String,
    val active: Boolean,
    val number: String?
)

data class HistoricalStormFeed(
    val year: Int,
    val storms: List<HistoricalStorm>,
    val fromCache: Boolean,
    val staleReason: Throwable? = null,
    val fetchedAtEpochMs: Long? = null
)

data class HistoricalTrack(
    val typhoon: Typhoon,
    val trackMissing: Boolean,
    val windRadiiMissing: Boolean,
    val fromCache: Boolean,
    val staleReason: Throwable? = null
)

/** This build can reach Juhe or nothing, but not QWeather, so a historical list cannot be loaded. */
class HistoricalSourceUnavailableError : Exception(
    "Historical typhoon list requires QWeather, which is not configured"
)

/** [year] is outside the current and previous UTC+8 year QWeather documents. */
class UnsupportedHistoryYearError(val year: Int) : Exception(
    "Historical typhoon year $year is outside the supported QWeather window"
)

/** The device could not reach the data source. Distinct from an empty list and from HTTP 429/503. */
class HistoricalNetworkException(message: String) : Exception(message)

/**
 * Years QWeather's storm-list documents and the foss relay already accepts:
 * the current and previous UTC+8 year, basin NP only.
 */
object HistoricalCoverage {
    const val BASIN = "NP"
    private val UTC8: ZoneOffset = ZoneOffset.ofHours(8)

    fun utc8Year(nowMs: Long): Int =
        Instant.ofEpochMilli(nowMs).atOffset(UTC8).year

    fun supportedYears(nowMs: Long): List<Int> {
        val year = utc8Year(nowMs)
        return listOf(year, year - 1)
    }

    fun isSupported(year: Int, nowMs: Long): Boolean = year in supportedYears(nowMs)
}

private val STORM_ID_YEAR_NUMBER = Regex("^NP_(\\d{2})(\\d{2})$", RegexOption.IGNORE_CASE)
private val STORM_ID_YEAR = Regex("^NP_(\\d{2})", RegexOption.IGNORE_CASE)

fun historicalYearFromId(id: String): Int? {
    val match = STORM_ID_YEAR.find(id.trim()) ?: return null
    return 2000 + match.groupValues[1].toInt()
}

fun historicalNumber(id: String, year: Int): String? {
    val match = STORM_ID_YEAR_NUMBER.matchEntire(id.trim()) ?: return null
    val fullYear = 2000 + match.groupValues[1].toInt()
    if (fullYear != year) return null
    return "$fullYear${match.groupValues[2]}"
}

fun filterHistoricalStorms(
    storms: List<HistoricalStorm>,
    year: Int,
    rawQuery: String
): List<HistoricalStorm> {
    val query = rawQuery.trim()
    return storms
        .asSequence()
        .filter { it.year == year && it.basin.equals(HistoricalCoverage.BASIN, ignoreCase = true) }
        .filter { query.isEmpty() || matchesHistoricalQuery(it, query) }
        .sortedWith(compareByDescending<HistoricalStorm> { it.number ?: "" }.thenBy { it.id })
        .toList()
}

fun matchesHistoricalQuery(storm: HistoricalStorm, rawQuery: String): Boolean {
    val query = rawQuery.trim()
    if (query.isEmpty()) return true
    if (storm.name.contains(query, ignoreCase = true)) return true
    val digitsOnly = query.all { it.isDigit() }
    if (!digitsOnly && storm.id.contains(query, ignoreCase = true)) return true
    if (!digitsOnly) return false
    if (query.length >= 4 && storm.id.filter { it.isDigit() }.contains(query)) return true
    return numberMatches(storm.number, query)
}

fun numberMatches(number: String?, rawQuery: String): Boolean {
    if (number == null) return false
    val query = rawQuery.trim()
    if (query.isEmpty() || query.any { !it.isDigit() }) return false
    if (number == query) return true
    if (query.length == 4 && number.takeLast(4) == query) return true
    if (query.length <= 2) {
        val serial = number.takeLast(2).toIntOrNull() ?: return false
        val wanted = query.toIntOrNull() ?: return false
        return serial == wanted
    }
    return false
}

enum class HistoryPhase {
    Loading,
    Results,
    NoResults,
    RateLimited,
    UpstreamError,
    OfflineResults,
    OfflineUnavailable,
    SourceUnavailable,
    UnsupportedYear
}

data class HistoryPresentation(
    val phase: HistoryPhase,
    val rows: List<HistoricalStorm>,
    val showingSavedCopy: Boolean
)

fun historyFailurePhase(error: Throwable): HistoryPhase = when (error) {
    is HistoricalSourceUnavailableError -> HistoryPhase.SourceUnavailable
    is UnsupportedHistoryYearError -> HistoryPhase.UnsupportedYear
    is RelayLimitedException -> HistoryPhase.RateLimited
    is HistoricalNetworkException -> HistoryPhase.OfflineUnavailable
    else -> HistoryPhase.UpstreamError
}

fun presentHistory(
    selectedYear: Int,
    query: String,
    feed: HistoricalStormFeed?,
    error: Throwable?
): HistoryPresentation {
    val applicable = feed?.takeIf { it.year == selectedYear }
    if (applicable == null) {
        val phase = if (error == null) HistoryPhase.Loading else historyFailurePhase(error)
        return HistoryPresentation(phase, emptyList(), false)
    }
    val rows = filterHistoricalStorms(applicable.storms, selectedYear, query)
    val reason = applicable.staleReason
    if (reason == null) {
        val phase = if (rows.isEmpty()) HistoryPhase.NoResults else HistoryPhase.Results
        return HistoryPresentation(phase, rows, false)
    }
    return when (historyFailurePhase(reason)) {
        HistoryPhase.OfflineUnavailable -> when {
            rows.isNotEmpty() -> HistoryPresentation(HistoryPhase.OfflineResults, rows, true)
            applicable.storms.isEmpty() -> HistoryPresentation(HistoryPhase.OfflineUnavailable, emptyList(), false)
            else -> HistoryPresentation(HistoryPhase.NoResults, emptyList(), true)
        }
        HistoryPhase.RateLimited ->
            HistoryPresentation(HistoryPhase.RateLimited, rows, rows.isNotEmpty())
        HistoryPhase.UpstreamError ->
            HistoryPresentation(HistoryPhase.UpstreamError, rows, rows.isNotEmpty())
        else -> {
            val phase = if (rows.isEmpty()) HistoryPhase.NoResults else HistoryPhase.Results
            HistoryPresentation(phase, rows, false)
        }
    }
}

enum class TrackPhase {
    Loading,
    Ready,
    MissingPath,
    OfflineReady,
    OfflineUnavailable,
    RateLimited,
    UpstreamError,
    SourceUnavailable,
    UnsupportedYear
}

fun presentTrack(track: HistoricalTrack?, error: Throwable?): TrackPhase {
    if (track == null && error == null) return TrackPhase.Loading
    if (track == null) {
        return when (historyFailurePhase(error!!)) {
            HistoryPhase.RateLimited -> TrackPhase.RateLimited
            HistoryPhase.OfflineUnavailable -> TrackPhase.OfflineUnavailable
            HistoryPhase.SourceUnavailable -> TrackPhase.SourceUnavailable
            HistoryPhase.UnsupportedYear -> TrackPhase.UnsupportedYear
            else -> TrackPhase.UpstreamError
        }
    }
    val failure = track.staleReason?.let { historyFailurePhase(it) }
    return when (failure) {
        HistoryPhase.RateLimited -> TrackPhase.RateLimited
        HistoryPhase.OfflineUnavailable ->
            if (track.trackMissing) TrackPhase.MissingPath else TrackPhase.OfflineReady
        HistoryPhase.UpstreamError -> TrackPhase.UpstreamError
        HistoryPhase.SourceUnavailable -> TrackPhase.SourceUnavailable
        else -> if (track.trackMissing) TrackPhase.MissingPath else TrackPhase.Ready
    }
}
