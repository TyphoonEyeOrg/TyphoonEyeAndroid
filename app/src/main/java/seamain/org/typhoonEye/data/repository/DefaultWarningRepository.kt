package seamain.org.typhoonEye.data.repository

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import seamain.org.typhoonEye.R
import seamain.org.typhoonEye.data.api.QWeatherWarningApi
import seamain.org.typhoonEye.data.credentials.DataSourceCredentials
import seamain.org.typhoonEye.data.credentials.hasQWeather
import seamain.org.typhoonEye.data.model.isTyphoonRelated
import seamain.org.typhoonEye.data.model.toDomain
import seamain.org.typhoonEye.domain.model.AlertSeverity
import seamain.org.typhoonEye.domain.model.AlertSource
import seamain.org.typhoonEye.domain.model.EmergencyAlert
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.UserLocation
import seamain.org.typhoonEye.domain.repository.WarningRepository
import seamain.org.typhoonEye.domain.util.distanceKmFrom
import seamain.org.typhoonEye.domain.util.roundKm
import seamain.org.typhoonEye.ui.util.IntensityLevel
import seamain.org.typhoonEye.ui.util.currentIntensity
import seamain.org.typhoonEye.ui.util.label
import seamain.org.typhoonEye.ui.util.latestPoint
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Official typhoon alerts (QWeather) + intensity-based urgency tips.
 *
 * When [UserLocation] is available, official lookups prioritize the device position
 * so notifications match where the user actually is.
 */
class DefaultWarningRepository @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val warningApi: QWeatherWarningApi,
    private val credentials: DataSourceCredentials
) : WarningRepository {

    /** Per call: a QWeather key entered in Settings applies without restart. */
    private val qWeatherConfigured: Boolean
        get() = credentials.hasQWeather

    override suspend fun fetchTyphoonAlerts(
        activeTyphoons: List<Typhoon>,
        userLocation: UserLocation?
    ): Result<List<EmergencyAlert>> {
        return runCatching {
            val official = if (qWeatherConfigured) {
                fetchOfficialAlerts(activeTyphoons, userLocation)
            } else {
                emptyList()
            }
            val intensity = synthesizeIntensityAlerts(activeTyphoons, userLocation)
            (official + intensity)
                .distinctBy { it.id }
                .sortedByDescending { it.severity.rank }
        }
    }

    private suspend fun fetchOfficialAlerts(
        activeTyphoons: List<Typhoon>,
        userLocation: UserLocation?
    ): List<EmergencyAlert> {
        val points = buildWatchPoints(activeTyphoons, userLocation)
        if (points.isEmpty()) return emptyList()

        return coroutineScope {
            points.map { (lat, lng, label) ->
                async {
                    runCatching {
                        val latStr = String.format(Locale.US, "%.2f", lat)
                        val lngStr = String.format(Locale.US, "%.2f", lng)
                        val response = warningApi.getCurrentAlerts(latStr, lngStr)
                        response.alerts
                            .filter { it.isTyphoonRelated() }
                            .map { it.toDomain() }
                    }.onFailure { e ->
                        Log.w(TAG, "Alert fetch failed for $label ($lat,$lng): ${e.message}")
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
                .distinctBy { it.id }
        }
    }

    private fun synthesizeIntensityAlerts(
        activeTyphoons: List<Typhoon>,
        userLocation: UserLocation?
    ): List<EmergencyAlert> {
        return activeTyphoons.mapNotNull { typhoon ->
            val level = typhoon.currentIntensity()
            val last = typhoon.latestPoint()
            val distanceKm = typhoon.distanceKmFrom(userLocation)

            // With user location: only notify intensity for storms within range
            // (or still Super typhoons which are regionally significant).
            if (userLocation != null && distanceKm != null) {
                val nearby = distanceKm <= NEARBY_STORM_KM
                val isSuper = level == IntensityLevel.SUPER
                if (!nearby && !isSuper) return@mapNotNull null
            }

            val severity = when (level) {
                IntensityLevel.SUPER -> AlertSeverity.Extreme
                IntensityLevel.STY -> AlertSeverity.Severe
                IntensityLevel.TY -> AlertSeverity.Moderate
                else -> {
                    // Near-user weaker systems still get a minor heads-up.
                    if (userLocation != null && distanceKm != null && distanceKm <= NEARBY_STORM_KM) {
                        AlertSeverity.Minor
                    } else {
                        return@mapNotNull null
                    }
                }
            }

            val color = when (severity) {
                AlertSeverity.Extreme -> "red"
                AlertSeverity.Severe -> "orange"
                AlertSeverity.Moderate -> "yellow"
                else -> "blue"
            }
            val intensityLabel = level.label(appContext)
            val distanceText = distanceKm?.let { km ->
                appContext.getString(R.string.alert_distance_km, km.roundKm())
            }.orEmpty()

            EmergencyAlert(
                id = "intensity-${typhoon.id}-${level.name}",
                title = appContext.getString(
                    R.string.intensity_alert_title,
                    typhoon.name,
                    intensityLabel
                ),
                body = buildString {
                    append(appContext.getString(R.string.intensity_alert_body, intensityLabel))
                    if (distanceText.isNotBlank()) {
                        append("，").append(distanceText)
                    }
                    last?.let {
                        append("，${appContext.getString(R.string.wind_speed)} ${it.speed} m/s，")
                        append("${appContext.getString(R.string.pressure)} ${it.pressure} hPa")
                    }
                    if (typhoon.positionDesc.isNotBlank()) {
                        append("。${typhoon.positionDesc}")
                    }
                    userLocation?.label?.takeIf { it.isNotBlank() }?.let { place ->
                        append("。")
                        append(appContext.getString(R.string.alert_relative_to_you, place))
                    }
                },
                sender = appContext.getString(R.string.app_name),
                eventName = appContext.getString(R.string.intensity_ty),
                severity = severity,
                colorCode = color,
                source = AlertSource.Intensity,
                relatedTyphoonId = typhoon.id
            )
        }
    }

    /**
     * Sample alerts for previewing notification styling (Settings → test alert).
     * Localized and explicitly marked as not real; never attributed to a real
     * meteorological agency.
     */
    override fun demoAlerts(): List<EmergencyAlert> = listOf(
        EmergencyAlert(
            id = "demo-typhoon-red-202609",
            title = appContext.getString(R.string.demo_alert_red_title),
            body = appContext.getString(R.string.demo_alert_red_body),
            sender = appContext.getString(R.string.demo_alert_sender),
            eventName = appContext.getString(R.string.intensity_ty),
            severity = AlertSeverity.Extreme,
            colorCode = "red",
            instruction = appContext.getString(R.string.demo_alert_red_instruction),
            source = AlertSource.Intensity,
            relatedTyphoonId = "202609"
        ),
        EmergencyAlert(
            id = "demo-typhoon-orange-202609",
            title = appContext.getString(R.string.demo_alert_orange_title),
            body = appContext.getString(R.string.demo_alert_orange_body),
            sender = appContext.getString(R.string.demo_alert_sender),
            eventName = appContext.getString(R.string.intensity_ty),
            severity = AlertSeverity.Severe,
            colorCode = "orange",
            source = AlertSource.Intensity,
            relatedTyphoonId = "202609"
        )
    )

    companion object {
        private const val TAG = "WarningRepository"
        /** Intensity push radius when user location is known. */
        private const val NEARBY_STORM_KM = 800.0

        /** SE China / nearby coastal watchpoints when GPS is unavailable. */
        private val COASTAL_WATCHPOINTS = listOf(
            Triple(22.28, 114.16, "香港"),
            Triple(22.54, 114.06, "深圳"),
            Triple(23.13, 113.26, "广州"),
            Triple(21.27, 110.36, "湛江"),
            Triple(20.03, 110.35, "海口"),
            Triple(24.48, 118.09, "厦门"),
            Triple(26.08, 119.30, "福州"),
            Triple(28.00, 120.65, "温州"),
            Triple(30.00, 122.10, "舟山"),
            Triple(31.23, 121.47, "上海"),
            Triple(25.03, 121.57, "台北"),
            Triple(35.10, 129.04, "釜山")
        )

        private fun buildWatchPoints(
            activeTyphoons: List<Typhoon>,
            userLocation: UserLocation?
        ): List<Triple<Double, Double, String>> {
            val fromStorms = activeTyphoons.mapNotNull { t ->
                val p = t.latestPoint() ?: return@mapNotNull null
                if (p.lat == 0.0 && p.lng == 0.0) return@mapNotNull null
                Triple(
                    (p.lat * 100).roundToInt() / 100.0,
                    (p.lng * 100).roundToInt() / 100.0,
                    t.name
                )
            }

            if (userLocation != null && userLocation.isValid) {
                val userPoint = Triple(
                    (userLocation.latitude * 100).roundToInt() / 100.0,
                    (userLocation.longitude * 100).roundToInt() / 100.0,
                    userLocation.label.ifBlank { "me" }
                )
                // User position first + storm centers only (skip full coastal fan-out).
                return (listOf(userPoint) + fromStorms).distinctBy { "${it.first}|${it.second}" }
            }

            return (fromStorms + COASTAL_WATCHPOINTS).distinctBy { "${it.first}|${it.second}" }
        }
    }
}
