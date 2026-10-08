package seamain.org.typhoonEye.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import seamain.org.typhoonEye.MainActivity
import seamain.org.typhoonEye.R
import seamain.org.typhoonEye.domain.model.AlertSeverity
import seamain.org.typhoonEye.domain.model.EmergencyAlert
import seamain.org.typhoonEye.data.preferences.UserPreferencesRepository
import kotlin.math.absoluteValue

/**
 * Emergency warning notifications (official / intensity).
 *
 * Kept at DEFAULT importance and never CATEGORY_ALARM so OEM Alerting
 * aggregates cannot swallow the Live Update ongoing notification.
 */
class TyphoonAlertNotifier(
    context: Context,
    private val preferences: UserPreferencesRepository
) {
    private val appContext = context.applicationContext

    init {
        ensureChannel()
    }

    suspend fun publish(alerts: List<EmergencyAlert>, enabled: Boolean) {
        if (!enabled) {
            cancelAllTracked()
            dismissActiveOnChannel()
            return
        }
        if (!areNotificationsAllowed()) {
            Log.w(TAG, "Cannot post alerts: notifications disabled or permission missing")
            return
        }

        val active = alerts.filter { !it.isCancel }
        val cancels = alerts.filter { it.isCancel }
        val previouslyNotified = preferences.getNotifiedAlertIds().toMutableSet()

        cancels.forEach { alert ->
            cancelAlert(alert.id)
            previouslyNotified.remove(alert.id)
        }

        // Clear prior alert posts (and OEM Aggregate_Alerting summaries on this channel).
        dismissActiveOnChannel()

        active.forEach { alert ->
            val alreadySeen = alert.id in previouslyNotified
            postAlert(alert, quiet = alreadySeen)
            if (!alreadySeen) {
                previouslyNotified += alert.id
                Log.i(TAG, "Posted emergency alert ${alert.id}: ${alert.title}")
            }
        }

        if (active.isNotEmpty()) {
            postGroupSummary(active.size)
        }

        val activeIds = active.map { it.id }.toSet()
        val pruned = previouslyNotified.filter { id ->
            id.startsWith("intensity-") && id !in activeIds
        }
        pruned.forEach { id ->
            cancelAlert(id)
            previouslyNotified.remove(id)
        }

        preferences.setNotifiedAlertIds(previouslyNotified)
    }

    /** Force-post demo alerts (ignores dedupe once). */
    suspend fun publishDemo(alerts: List<EmergencyAlert>) {
        if (!areNotificationsAllowed()) return
        ensureChannel()
        dismissActiveOnChannel()
        alerts.forEach { postAlert(it, quiet = false) }
        if (alerts.isNotEmpty()) postGroupSummary(alerts.size)
        val ids = preferences.getNotifiedAlertIds().toMutableSet()
        ids += alerts.map { it.id }
        preferences.setNotifiedAlertIds(ids)
    }

    fun canPostNotifications(): Boolean = areNotificationsAllowed()

    private fun postAlert(alert: EmergencyAlert, quiet: Boolean) {
        val openIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ALERT_ID, alert.id)
            alert.relatedTyphoonId?.let { putExtra(TyphoonLiveNotifier.EXTRA_TYPHOON_ID, it) }
        }
        val pending = PendingIntent.getActivity(
            appContext,
            notificationId(alert.id),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val color = colorFor(alert)
        val title = alert.title
        val body = buildString {
            append(alert.body.take(280))
            if (alert.sender.isNotBlank()) {
                append("\n— ")
                append(alert.sender)
            }
        }
        val big = buildString {
            append(alert.body)
            if (alert.instruction.isNotBlank()) {
                append("\n\n")
                append(appContext.getString(R.string.alert_guidance_label))
                append("\n")
                append(alert.instruction)
            }
            if (alert.sender.isNotBlank()) {
                append("\n\n")
                append(appContext.getString(R.string.alert_issuer_label, alert.sender))
            }
        }

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_typhoon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big).setSummaryText(severityLabel(alert)))
            .setPriority(priorityFor(alert.severity))
            // Never CATEGORY_ALARM — OEMs pull the whole package into Aggregate_AlertingSection.
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .setColor(color)
            .setColorized(false)
            .setShowWhen(true)
            .setGroup(GROUP_ALERTS)
            .setGroupSummary(false)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)

        if (quiet) {
            builder.setSilent(true)
        }

        try {
            NotificationManagerCompat.from(appContext)
                .notify(notificationId(alert.id), builder.build())
        } catch (e: SecurityException) {
            Log.e(TAG, "notify alert blocked", e)
        }
    }

    private fun postGroupSummary(count: Int) {
        val summary = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_typhoon)
            .setContentTitle(appContext.getString(R.string.alert_channel_name))
            .setContentText(appContext.getString(R.string.alert_group_summary, count))
            .setStyle(
                NotificationCompat.InboxStyle()
                    .setSummaryText(appContext.getString(R.string.alert_group_summary, count))
            )
            .setGroup(GROUP_ALERTS)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(appContext).notify(SUMMARY_ID, summary)
        } catch (e: SecurityException) {
            Log.e(TAG, "notify alert summary blocked", e)
        }
    }

    private fun cancelAlert(id: String) {
        NotificationManagerCompat.from(appContext).cancel(notificationId(id))
    }

    private suspend fun cancelAllTracked() {
        val ids = preferences.getNotifiedAlertIds()
        ids.forEach { cancelAlert(it) }
        NotificationManagerCompat.from(appContext).cancel(SUMMARY_ID)
        preferences.setNotifiedAlertIds(emptySet())
    }

    /** Drop alert-channel posts (and OEM AutoGroup summaries) so Live isn't swallowed. */
    private fun dismissActiveOnChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val nm = appContext.getSystemService(NotificationManager::class.java) ?: return
        nm.activeNotifications
            .filter { sbn ->
                sbn.packageName == appContext.packageName &&
                    (
                        sbn.notification.channelId == CHANNEL_ID ||
                            sbn.notification.channelId == LEGACY_CHANNEL_ID ||
                            sbn.notification.group == GROUP_ALERTS ||
                            sbn.notification.group == "Aggregate_AlertingSection" ||
                            (
                                (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0 &&
                                    sbn.id != TyphoonLiveNotifier.NOTIFICATION_ID
                                )
                        )
            }
            .forEach { sbn ->
                // Never cancel the Live Update notification.
                if (sbn.id == TyphoonLiveNotifier.NOTIFICATION_ID) return@forEach
                nm.cancel(sbn.tag, sbn.id)
            }
        NotificationManagerCompat.from(appContext).cancel(SUMMARY_ID)
    }

    private fun areNotificationsAllowed(): Boolean {
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        // Drop HIGH/ALARM legacy channel that caused Aggregate_Alerting to eat Live.
        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.alert_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = appContext.getString(R.string.alert_channel_desc)
            enableVibration(true)
            enableLights(true)
            lightColor = Color.RED
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    private fun priorityFor(severity: AlertSeverity): Int = when (severity) {
        AlertSeverity.Extreme, AlertSeverity.Severe -> NotificationCompat.PRIORITY_HIGH
        AlertSeverity.Moderate -> NotificationCompat.PRIORITY_DEFAULT
        else -> NotificationCompat.PRIORITY_DEFAULT
    }

    private fun severityLabel(alert: EmergencyAlert): String {
        val color = when (alert.colorCode.lowercase()) {
            "red" -> appContext.getString(R.string.alert_color_red)
            "orange" -> appContext.getString(R.string.alert_color_orange)
            "yellow" -> appContext.getString(R.string.alert_color_yellow)
            "blue" -> appContext.getString(R.string.alert_color_blue)
            else -> appContext.getString(alert.severity.labelRes())
        }
        return if (alert.eventName.isNotBlank()) {
            appContext.getString(R.string.alert_summary_format, alert.eventName, color)
        } else {
            color
        }
    }

    private fun AlertSeverity.labelRes(): Int = when (this) {
        AlertSeverity.Extreme -> R.string.alert_severity_extreme
        AlertSeverity.Severe -> R.string.alert_severity_severe
        AlertSeverity.Moderate -> R.string.alert_severity_moderate
        AlertSeverity.Minor -> R.string.alert_severity_minor
        AlertSeverity.Unknown -> R.string.alert_severity_unknown
    }

    private fun colorFor(alert: EmergencyAlert): Int = when (alert.colorCode.lowercase()) {
        "red" -> Color.parseColor("#C62828")
        "orange" -> Color.parseColor("#EF6C00")
        "yellow" -> Color.parseColor("#F9A825")
        "blue" -> Color.parseColor("#1565C0")
        else -> when (alert.severity) {
            AlertSeverity.Extreme -> Color.parseColor("#C62828")
            AlertSeverity.Severe -> Color.parseColor("#EF6C00")
            AlertSeverity.Moderate -> Color.parseColor("#F9A825")
            else -> Color.parseColor("#1565C0")
        }
    }

    private fun notificationId(alertId: String): Int =
        BASE_ID + (alertId.hashCode().absoluteValue % 50_000)

    companion object {
        private const val TAG = "TyphoonAlertNotifier"
        private const val LEGACY_CHANNEL_ID = "typhoon_emergency_alerts"
        const val CHANNEL_ID = "typhoon_emergency_alerts_v2"
        const val GROUP_ALERTS = "typhoon_alerts_group"
        private const val BASE_ID = 2000
        private const val SUMMARY_ID = 1999
        const val EXTRA_ALERT_ID = "extra_alert_id"
    }
}
