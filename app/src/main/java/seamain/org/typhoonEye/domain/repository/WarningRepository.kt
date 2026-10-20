package seamain.org.typhoonEye.domain.repository

import seamain.org.typhoonEye.domain.model.EmergencyAlert
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.UserLocation
import seamain.org.typhoonEye.domain.names.NameColumn

interface WarningRepository {
    /**
     * @param userLocation when non-null, official alerts are queried primarily at this point
     * (device GPS). Falls back to coastal watchpoints if null.
     */
    suspend fun fetchTyphoonAlerts(
        activeTyphoons: List<Typhoon>,
        userLocation: UserLocation? = null,
        /** Storm-name column for alert titles; null = current app column. */
        nameColumn: NameColumn? = null
    ): Result<List<EmergencyAlert>>

    fun demoAlerts(): List<EmergencyAlert>
}
