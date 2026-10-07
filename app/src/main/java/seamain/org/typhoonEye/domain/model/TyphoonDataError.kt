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
 * This build has no weather data source configured: `JUHE_KEY` is blank and no
 * QWeather credentials were compiled in. Expected for the F-Droid build, which
 * ships without API keys. Not a network failure, so retrying alone won't help.
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
    /** Provider quota or rate limit reached. */
    QuotaExceeded,
    /** Provider answered with a non-success code. */
    ApiError,
    /** Request failed (no network, timeout, TLS, parse error, …). */
    Network
}

/**
 * @param code provider status code when known (e.g. Juhe `error_code`, QWeather `code`).
 * @param detail raw provider reason / exception message; shown only as secondary detail.
 */
data class SourceFailure(
    val source: DataSource,
    val kind: SourceFailureKind,
    val code: String? = null,
    val detail: String? = null
) {
    fun describe(): String = buildString {
        append(source.name).append(' ').append(kind.name)
        code?.takeIf { it.isNotBlank() }?.let { append(" [").append(it).append(']') }
        detail?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
    }
}
