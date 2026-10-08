package seamain.org.typhoonEye.domain.repository

import seamain.org.typhoonEye.domain.model.EmergencyAlert
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.UserLocation

interface WarningRepository {
    /**
     * @param userLocation GitHub build: when non-null, official alerts are queried primarily at
     * this point (device GPS); falls back to coastal watchpoints if null.
     * F-Droid build: never sent anywhere; only used on the device to pick nearby alerts from
     * the relay's shared list.
     */
    suspend fun fetchTyphoonAlerts(
        activeTyphoons: List<Typhoon>,
        userLocation: UserLocation? = null
    ): Result<List<EmergencyAlert>>

    fun demoAlerts(): List<EmergencyAlert>
}
