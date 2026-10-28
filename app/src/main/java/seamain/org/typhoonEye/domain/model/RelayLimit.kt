package seamain.org.typhoonEye.domain.model

/**
 * Relay (or any typhoon HTTP) limit the UI can explain without treating the
 * payload as live.
 *
 * [Busy] is HTTP 429 (shared service is rate-limiting).
 * [DailyQuotaExhausted] is HTTP 503 (daily upstream budget used up).
 */
enum class RelayLimitKind {
    Busy,
    DailyQuotaExhausted
}

/**
 * Thrown when a list/detail refresh cannot run because the relay answered
 * 429/503, or a previous `Retry-After` is still in effect, and there is no
 * cached list to show. The message is for logs; the UI maps [kind].
 */
class RelayLimitedException(
    val kind: RelayLimitKind,
    val retryAfterUntilEpochMs: Long?
) : Exception(
    when (kind) {
        RelayLimitKind.Busy -> "Shared data service busy (429)"
        RelayLimitKind.DailyQuotaExhausted -> "Shared data service daily limit reached (503)"
    }
)
