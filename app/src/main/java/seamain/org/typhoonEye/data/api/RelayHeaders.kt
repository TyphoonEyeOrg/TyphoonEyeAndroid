package seamain.org.typhoonEye.data.api

import seamain.org.typhoonEye.domain.model.RelayLimitKind
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Header the TyphoonEye relay sets on HTTP 429 and on 503 `budget_exhausted`. */
internal const val RETRY_AFTER_HEADER = "Retry-After"

/** `1` when the relay answered 200 with an older cached body. */
internal const val RELAY_STALE_HEADER = "X-Relay-Stale"

/** A bogus or hostile Retry-After cannot freeze refresh for more than a day. */
internal const val MAX_RETRY_AFTER_MS: Long = 24L * 60 * 60 * 1000

internal fun relayLimitKind(httpCode: Int): RelayLimitKind? = when (httpCode) {
    429 -> RelayLimitKind.Busy
    503 -> RelayLimitKind.DailyQuotaExhausted
    else -> null
}

internal fun isRelayStale(header: String?): Boolean = header?.trim() == "1"

/**
 * `Retry-After` as an absolute epoch millis when another try is allowed.
 * Accepts delay-seconds (what the relay sends) and an HTTP-date.
 * Missing, non-positive, already-elapsed, or unparseable values return null.
 */
internal fun parseRetryAfterUntilMs(header: String?, nowMs: Long): Long? {
    val raw = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val until = raw.toLongOrNull()?.let { seconds ->
        if (seconds <= 0L) return null
        val capped = seconds.coerceAtMost(MAX_RETRY_AFTER_MS / 1000)
        nowMs + capped * 1000
    } ?: parseHttpDateMillis(raw)
    if (until == null || until <= nowMs) return null
    return until.coerceAtMost(nowMs + MAX_RETRY_AFTER_MS)
}

private fun parseHttpDateMillis(raw: String): Long? = try {
    ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
} catch (_: DateTimeParseException) {
    null
}
