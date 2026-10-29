package seamain.org.typhoonEye.domain.model

/**
 * Typed failures from [seamain.org.typhoonEye.domain.repository.TyphoonRepository].
 *
 * Messages are English and meant for logs only. User-visible text is resolved
 * from string resources in the UI layer (see `ui/TyphoonErrorText.kt`), so
 * errors follow the app language instead of whatever the repository hard-coded.
 */
sealed class TyphoonDataError(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * This build has no weather data source configured: no relay URL, `JUHE_KEY` is blank and
 * no QWeather credentials were compiled in (e.g. a GitHub-flavor build made without
 * `local.properties` keys). Never happens in the F-Droid build, which uses TyphoonEye's
 * relay. Not a network failure, so retrying alone won't help.
 */
class NoDataSourceConfiguredError : TyphoonDataError(
    "No typhoon data source configured (JUHE_KEY and QWeather credentials are empty)"
)

/** At least one source is configured, but every configured source failed. */
class DataSourcesFailedError(
    val failures: List<SourceFailure>
) : TyphoonDataError(
    "All configured typhoon data sources failed: " +
        failures.joinToString("; ") { it.describe() }.ifBlank { "unknown" }
)

/** Detail for [typhoonId] could not be loaded from any configured source. */
class TyphoonDetailUnavailableError(
    val typhoonId: String,
    val failures: List<SourceFailure> = emptyList()
) : TyphoonDataError(
    "Typhoon detail unavailable for $typhoonId" +
        failures.takeIf { it.isNotEmpty() }
            ?.joinToString("; ", prefix = " (", postfix = ")") { it.describe() }
            .orEmpty()
)

enum class DataSource { Juhe, QWeather }

enum class SourceFailureKind {
    /** Key / credentials rejected by the provider. */
    InvalidKey,
    /** Provider quota (Juhe error_code, QWeather JSON status). Not a relay HTTP 429. */
    QuotaExceeded,
    /**
     * Relay HTTP 429: the shared data service is rate-limiting this client.
     * Copy stays calm and does not name an official forecast agency.
     */
    RelayBusy,
    /** Relay HTTP 503: the shared daily upstream quota is used up. */
    RelayDailyQuota,
    /** Provider answered with a non-success code. */
    ApiError,
    /** Request failed (no network, timeout, TLS, parse error, …). */
    Network
}

/**
 * @param code provider status code when known (e.g. Juhe `error_code`, QWeather `code`, HTTP status).
 * @param detail raw provider reason / exception message; shown only as secondary detail.
 * @param retryAfterEpochMs earliest epoch millis to call this source again, from a relay `Retry-After`.
 */
data class SourceFailure(
    val source: DataSource,
    val kind: SourceFailureKind,
    val code: String? = null,
    val detail: String? = null,
    val retryAfterEpochMs: Long? = null
) {
    fun describe(): String = buildString {
        append(source.name).append(' ').append(kind.name)
        code?.takeIf { it.isNotBlank() }?.let { append(" [").append(it).append(']') }
        detail?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
    }
}

/**
 * True when every failure carries a `Retry-After` that is still in the future.
 * One source without a wait (a timeout, for example) means a retry may still call that source.
 */
fun List<SourceFailure>.retryAfterStillActive(nowMs: Long): Boolean =
    isNotEmpty() && all { failure ->
        val until = failure.retryAfterEpochMs ?: return@all false
        nowMs < until
    }

/** Relay wait still in effect for this error, if every failed source asked us to wait. */
fun TyphoonDataError.retryAfterStillActive(nowMs: Long): Boolean = when (this) {
    is DataSourcesFailedError -> failures.retryAfterStillActive(nowMs)
    is TyphoonDetailUnavailableError -> failures.retryAfterStillActive(nowMs)
    else -> false
}
