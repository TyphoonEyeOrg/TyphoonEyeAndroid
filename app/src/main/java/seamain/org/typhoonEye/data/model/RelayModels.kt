package seamain.org.typhoonEye.data.model

import kotlinx.serialization.Serializable

/**
 * `GET {relay}/v1/alerts` — see `relay/README.md`.
 *
 * @param updatedAtMs when the relay last refreshed the list (epoch millis); 0 = never.
 * @param points fixed coastal watch points; each carries the QWeather alerts (unchanged
 *   upstream JSON) that were active there at [updatedAtMs].
 */
@Serializable
data class RelayAlertsResponse(
    val version: Int = 1,
    val updatedAtMs: Long = 0L,
    val points: List<RelayAlertPoint> = emptyList()
)

@Serializable
data class RelayAlertPoint(
    val id: String = "",
    val name: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val alerts: List<QWeatherAlert> = emptyList()
)
