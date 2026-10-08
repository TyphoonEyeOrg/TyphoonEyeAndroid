package seamain.org.typhoonEye.data.api

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * How this build reaches typhoon data. Fixed at build time (see `app/build.gradle.kts`).
 *
 * - **Direct** (GitHub build): Juhe / QWeather are called directly with build-time keys
 *   (`local.properties` or CI secrets). A source without a key is skipped.
 * - **Relay** (F-Droid build, [relayBaseUrl] non-blank): every request goes to TyphoonEye's
 *   relay (`relay/` in this repo), which holds the API keys server-side. The app carries no
 *   keys and never sends the user's location: official warnings come as one shared list
 *   (`GET /v1/alerts`) that is filtered on the device.
 */
class DataSourceConfig(
    relayBaseUrl: String = "",
    juheKey: String = "",
    qWeatherDirectConfigured: Boolean = false
) {
    /** Normalised relay base URL ending in `/`, or blank in direct mode. */
    val relayBaseUrl: String = normalizeRelayBaseUrl(relayBaseUrl)

    val viaRelay: Boolean = this.relayBaseUrl.isNotBlank()

    /** Never used in relay mode, even if a key was passed in. */
    private val juheKey: String = if (viaRelay) "" else juheKey.trim()

    val juheEnabled: Boolean = viaRelay || this.juheKey.isNotBlank()

    val qWeatherEnabled: Boolean = viaRelay || qWeatherDirectConfigured

    val hasAnyDataSource: Boolean get() = juheEnabled || qWeatherEnabled

    /**
     * Value for Juhe's `key` query parameter. Null in relay mode, so Retrofit omits the
     * parameter entirely (the relay adds its own key).
     */
    val juheKeyParam: String? get() = juheKey.takeIf { it.isNotBlank() }

    /** Base URL for the Juhe Retrofit service. */
    val juheBaseUrl: String get() = if (viaRelay) "${relayBaseUrl}v1/juhe/" else JUHE_DIRECT_BASE_URL

    /** Base URL for the QWeather Retrofit services, or null to use the build's QWeather host. */
    val qWeatherRelayBaseUrl: String? get() = if (viaRelay) "${relayBaseUrl}v1/qweather/" else null

    /** Never print the key, even by accident (string templates, crash reports). */
    override fun toString(): String =
        "DataSourceConfig(relay=${relayBaseUrl.ifBlank { "<none>" }}, " +
            "juhe=$juheEnabled, qWeather=$qWeatherEnabled)"

    companion object {
        const val JUHE_DIRECT_BASE_URL = "https://apis.juhe.cn/"

        /** Base URL for Retrofit services that are only called in relay mode. */
        const val UNUSED_RELAY_PLACEHOLDER = "https://relay.invalid/"

        /** Blank stays blank; otherwise must be an absolute HTTPS URL (ends with `/`). */
        fun normalizeRelayBaseUrl(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            val withSlash = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
            val url = requireNotNull(withSlash.toHttpUrlOrNull()) { "Invalid relay URL" }
            require(url.scheme == "https") { "Relay URL must use HTTPS" }
            return url.toString()
        }
    }
}
