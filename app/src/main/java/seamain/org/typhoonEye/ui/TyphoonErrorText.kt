package seamain.org.typhoonEye.ui

import android.content.Context
import seamain.org.typhoonEye.R
import seamain.org.typhoonEye.domain.model.DataSource
import seamain.org.typhoonEye.domain.model.DataSourcesFailedError
import seamain.org.typhoonEye.domain.model.NoDataSourceConfiguredError
import seamain.org.typhoonEye.domain.model.SourceFailure
import seamain.org.typhoonEye.domain.model.SourceFailureKind
import seamain.org.typhoonEye.domain.model.TyphoonDataError
import seamain.org.typhoonEye.domain.model.TyphoonDetailUnavailableError

/**
 * Resolves typed repository errors to localized, user-facing text.
 * Repository / domain code never builds UI strings itself.
 */
fun DataSource.localizedName(context: Context): String = when (this) {
    DataSource.Juhe -> context.getString(R.string.source_juhe)
    DataSource.QWeather -> context.getString(R.string.source_qweather)
}

fun SourceFailure.localized(context: Context): String {
    val name = source.localizedName(context)
    return when (kind) {
        SourceFailureKind.InvalidKey -> context.getString(R.string.source_failure_invalid_key, name)
        SourceFailureKind.QuotaExceeded -> context.getString(R.string.source_failure_quota, name)
        SourceFailureKind.ApiError -> context.getString(
            R.string.source_failure_api,
            name,
            code?.takeIf { it.isNotBlank() } ?: "?"
        )
        SourceFailureKind.Network -> context.getString(R.string.source_failure_network, name)
    }
}

/** One line per failed source, or null when there is nothing specific to say. */
fun List<SourceFailure>.localizedDetails(context: Context): String? =
    takeIf { it.isNotEmpty() }?.joinToString("\n") { it.localized(context) }

/** Short localized explanation for a stale-cache banner or error body. */
fun TyphoonDataError.localizedMessage(context: Context): String = when (this) {
    is NoDataSourceConfiguredError -> context.getString(R.string.no_data_source_title)
    is DataSourcesFailedError -> listOfNotNull(
        context.getString(R.string.error_sources_failed),
        failures.localizedDetails(context)
    ).joinToString("\n")
    is TyphoonDetailUnavailableError -> listOfNotNull(
        context.getString(R.string.error_sources_failed),
        failures.localizedDetails(context)
    ).joinToString("\n")
}
