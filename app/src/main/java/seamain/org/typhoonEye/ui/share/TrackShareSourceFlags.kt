package seamain.org.typhoonEye.ui.share

import seamain.org.typhoonEye.BuildConfig

/**
 * Build-time source flags for this distribution. Values are booleans only —
 * keys are never copied into the image.
 *
 * Foss reaches Juhe and QWeather through the relay, so a non-blank relay URL
 * enables both. A direct build enables a source only when its own credentials
 * are present. QWeather counts as configured when the API key is set, or when
 * all four JWT parts are set.
 */
fun shareSourceFlagsFromBuildConfig(): ShareSourceFlags = shareSourceFlags(
    relayBaseUrl = BuildConfig.RELAY_BASE_URL,
    juheKey = BuildConfig.JUHE_KEY,
    qWeatherApiKey = BuildConfig.QWEATHER_API_KEY,
    qWeatherKid = BuildConfig.QWEATHER_KID,
    qWeatherProjectId = BuildConfig.QWEATHER_PROJECT_ID,
    qWeatherDeveloperId = BuildConfig.QWEATHER_DEVELOPER_ID,
    qWeatherPrivateKey = BuildConfig.QWEATHER_PRIVATE_KEY
)

internal fun shareSourceFlags(
    relayBaseUrl: String,
    juheKey: String,
    qWeatherApiKey: String,
    qWeatherKid: String,
    qWeatherProjectId: String,
    qWeatherDeveloperId: String,
    qWeatherPrivateKey: String
): ShareSourceFlags {
    val viaRelay = relayBaseUrl.isNotBlank()
    val jwt = qWeatherKid.isNotBlank() &&
        qWeatherProjectId.isNotBlank() &&
        qWeatherDeveloperId.isNotBlank() &&
        qWeatherPrivateKey.isNotBlank()
    return ShareSourceFlags(
        viaRelay = viaRelay,
        juheEnabled = viaRelay || juheKey.isNotBlank(),
        qWeatherEnabled = viaRelay || jwt || qWeatherApiKey.isNotBlank()
    )
}
