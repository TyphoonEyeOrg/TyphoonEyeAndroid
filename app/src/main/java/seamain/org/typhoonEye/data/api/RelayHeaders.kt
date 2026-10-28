package seamain.org.typhoonEye.data.api

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * TyphoonEye relay response headers (issue #15).
 *
 * - `Retry-After`: delta-seconds or an HTTP-date. Callers must not hit that source again before it.
 * - `X-Relay-Stale: 1`: HTTP 200 whose body is older cached data, not a live observation.
 */
object RelayHeaders {
    const val RETRY_AFTER = "Retry-After"
    const val STALE = "X-Relay-Stale"

    const val JUHE_ACTIVE_SUFFIX = "fapigw/typhoon/active"
    const val QWEATHER_LIST_SUFFIX = "v7/tropical/storm-list"

    /** A daily budget wait is at most a day; cap a bogus header so it cannot lock refresh for years. */
    private const val MAX_DELAY_SECONDS = 48L * 60 * 60

    /**
     * @return epoch millis when a retry is allowed, or null when the header is absent,
     * unparseable, or already expired.
     */
    fun retryAfterEpochMs(header: String?, nowMs: Long): Long? {
        val raw = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val until = raw.toLongOrNull()?.let { seconds ->
            if (seconds < 0) return null
            nowMs + seconds.coerceAtMost(MAX_DELAY_SECONDS) * 1_000L
        } ?: httpDateEpochMs(raw)
        if (until == null || until <= nowMs) return null
        return until.coerceAtMost(nowMs + MAX_DELAY_SECONDS * 1_000L)
    }

    fun isStale(header: String?): Boolean = header?.trim() == "1"

    private fun httpDateEpochMs(raw: String): Long? = try {
        ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}
