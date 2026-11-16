package seamain.org.typhoonEye.domain.history

import seamain.org.typhoonEye.domain.model.DataSourcesFailedError
import seamain.org.typhoonEye.domain.model.HistoricalSourceUnavailableError
import seamain.org.typhoonEye.domain.model.NoDataSourceConfiguredError
import seamain.org.typhoonEye.domain.model.SourceFailureKind
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.TyphoonDataError
import seamain.org.typhoonEye.domain.model.TyphoonDetailUnavailableError
import seamain.org.typhoonEye.domain.model.UnsupportedHistoryYearError
import java.time.Instant
import java.time.ZoneOffset

/**
 * Northwest Pacific storm as returned by QWeather's storm list.
 * [number] is set only when [id] is `NP_YYNN` and that year matches [year].
 * It is a formatting of the id the source sent, not a separately observed field.
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
    val staleReason: TyphoonDataError? = null,
    val fetchedAtEpochMs: Long? = null
)

data class HistoricalTrack(
    val typhoon: Typhoon,
    val trackMissing: Boolean,
    val windRadiiMissing: Boolean,
    val fromCache: Boolean,
    val staleReason: TyphoonDataError? = null
)

/**
 * Years the existing QWeather storm-list documents, and that the foss relay already
 * accepts: the current and previous UTC+8 year, basin NP only.
 *
 * Juhe's typhoon product used here is the active list plus detail of an id on that
 * list. It is not queried for history.
 */
object HistoricalCoverage {
    const val BASIN = "NP"
    private val UTC8: ZoneOffset = ZoneOffset.ofHours(8)

    fun utc8Year(nowMs: Long): Int =
        Instant.ofEpochMilli(nowMs).atOffset(UTC8).year

    /** Newest first. */
    fun supportedYears(nowMs: Long): List<Int> {
        val year = utc8Year(nowMs)
        return listOf(year, year - 1)
    }

    fun isSupported(year: Int, nowMs: Long): Boolean = year in supportedYears(nowMs)
}

private val STORM_ID_YEAR_NUMBER = Regex("^NP_(\\d{2})(\\d{2})$", RegexOption.IGNORE_CASE)
private val STORM_ID_YEAR = Regex("^NP_(\\d{2})", RegexOption.IGNORE_CASE)

/** Year encoded by a QWeather id (`NP_2609` → 2026), or null when the id has no year prefix. */
fun historicalYearFromId(id: String): Int? {
    val match = STORM_ID_YEAR.find(id.trim()) ?: return null
    return 2000 + match.groupValues[1].toInt()
}

/**
 * Conventional 6-digit number (`202609`) when the id is exactly `NP_YYNN` and [year]
 * is that same year. Longer or non-numeric ids do not get a guessed number.
 */
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
    // Short digit queries are a serial ("9", "09"), not the year inside NP_26xx.
    if (query.length >= 4 && storm.id.filter { it.isDigit() }.contains(query)) return true
    return numberMatches(storm.number, query)
}

/**
 * Digit queries match the derived number exactly, its 4-digit `YYNN` tail, or its
 * 1–2 digit serial. They do not match the year prefix, so "26" does not return
 * every 2026 storm.
 */
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
    /** True when [rows] came from a saved copy after a failed refresh. */
    val showingSavedCopy: Boolean
)

fun historyFailurePhase(error: TyphoonDataError): HistoryPhase {
    if (error is HistoricalSourceUnavailableError || error is NoDataSourceConfiguredError) {
        return HistoryPhase.SourceUnavailable
    }
    if (error is UnsupportedHistoryYearError) return HistoryPhase.UnsupportedYear
    val failures = when (error) {
        is DataSourcesFailedError -> error.failures
        is TyphoonDetailUnavailableError -> error.failures
        else -> emptyList()
    }
    if (failures.any {
            it.kind == SourceFailureKind.RelayBusy ||
                it.kind == SourceFailureKind.RelayDailyQuota ||
                it.kind == SourceFailureKind.QuotaExceeded
        }
    ) {
        return HistoryPhase.RateLimited
    }
    if (failures.isNotEmpty() && failures.all { it.kind == SourceFailureKind.Network }) {
        return HistoryPhase.OfflineUnavailable
    }
    return HistoryPhase.UpstreamError
}

fun presentHistory(
    selectedYear: Int,
    query: String,
    feed: HistoricalStormFeed?,
    error: TyphoonDataError?
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
            HistoryPresentation(
                HistoryPhase.UpstreamError,
                if (rows.isNotEmpty()) rows else emptyList(),
                rows.isNotEmpty()
            )
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

fun presentTrack(track: HistoricalTrack?, error: TyphoonDataError?): TrackPhase {
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
