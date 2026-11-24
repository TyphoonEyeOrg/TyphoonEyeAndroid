package seamain.org.typhoonEye.ui.share

import seamain.org.typhoonEye.BuildConfig

/**
 * Build-time source flags for this GitHub build. Values are booleans only —
 * keys are never copied into the image.
 *
 * There is no relay host here, so [ShareSourceFlags.viaRelay] stays false.
 * QWeather is enabled when the JWT triple the auth interceptor already uses is
 * set (kid, project id, private key), or when an API key is set. Developer id
 * is not part of that check.
 */
fun shareSourceFlagsFromBuildConfig(): ShareSourceFlags = shareSourceFlags(
    juheKey = BuildConfig.JUHE_KEY,
    qWeatherApiKey = BuildConfig.QWEATHER_API_KEY,
    qWeatherKid = BuildConfig.QWEATHER_KID,
    qWeatherProjectId = BuildConfig.QWEATHER_PROJECT_ID,
    qWeatherDeveloperId = "",
    qWeatherPrivateKey = BuildConfig.QWEATHER_PRIVATE_KEY
)

/**
 * Pure source-label decision. Master does not route through the F-Droid relay.
 * Juhe is enabled when its key is set. QWeather is enabled from the API key or
 * from kid + project id + private key together. [qWeatherDeveloperId] is not
 * consulted: an empty value does not turn QWeather off.
 */
internal fun shareSourceFlags(
    juheKey: String,
    qWeatherApiKey: String,
    qWeatherKid: String,
    qWeatherProjectId: String,
    qWeatherDeveloperId: String,
    qWeatherPrivateKey: String
): ShareSourceFlags {
    val viaRelay = false
    val jwt = qWeatherKid.isNotBlank() &&
        qWeatherProjectId.isNotBlank() &&
        qWeatherPrivateKey.isNotBlank()
    return ShareSourceFlags(
        viaRelay = viaRelay,
        juheEnabled = viaRelay || juheKey.isNotBlank(),
        qWeatherEnabled = viaRelay || jwt || qWeatherApiKey.isNotBlank()
    )
}
