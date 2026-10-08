package seamain.org.typhoonEye.domain.model

/**
 * App-domain weather / typhoon emergency alert for notifications & UI.
 */
data class EmergencyAlert(
    val id: String,
    val title: String,
    val body: String,
    val sender: String = "",
    val eventName: String = "",
    val severity: AlertSeverity = AlertSeverity.Unknown,
    val colorCode: String = "",
    val issuedTime: String = "",
    val expireTime: String = "",
    val instruction: String = "",
    val source: AlertSource = AlertSource.Official,
    val relatedTyphoonId: String? = null,
    val isCancel: Boolean = false
)

/** Display labels are string resources (alert_severity_*), resolved in the notifier. */
enum class AlertSeverity(val rank: Int) {
    Extreme(4),
    Severe(3),
    Moderate(2),
    Minor(1),
    Unknown(0)
}

enum class AlertSource { Official, Intensity }
