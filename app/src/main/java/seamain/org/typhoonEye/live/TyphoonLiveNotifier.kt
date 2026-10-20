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
import androidx.core.graphics.drawable.IconCompat
import seamain.org.typhoonEye.MainActivity
import seamain.org.typhoonEye.R
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.TyphoonPoint
import seamain.org.typhoonEye.ui.util.IntensityLevel
import seamain.org.typhoonEye.ui.util.currentIntensity
import seamain.org.typhoonEye.ui.util.displayName
import seamain.org.typhoonEye.domain.names.NameColumn
import seamain.org.typhoonEye.domain.names.StormNames
import seamain.org.typhoonEye.ui.util.formatCoordinate
import seamain.org.typhoonEye.ui.util.formatObservationTime
import seamain.org.typhoonEye.ui.util.latestPoint
import seamain.org.typhoonEye.ui.util.label
import seamain.org.typhoonEye.ui.util.localizeDirection
import seamain.org.typhoonEye.ui.util.moveLabel

/**
 * Ongoing Live Update styled like an Apple Live Activity.
 *
 * Kept on its own notification group and marked silent so emergency alerts
 * cannot pull it into the Alerting aggregate section.
 */
class TyphoonLiveNotifier(private val context: Context) {

    private val appContext = context.applicationContext

    init {
        ensureChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.getSystemService(NotificationManager::class.java)
                ?.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        }
    }

    fun update(activeTyphoons: List<Typhoon>, enabled: Boolean, nameColumn: NameColumn? = null) {
        val column = nameColumn ?: StormNames.currentColumn()
        if (!enabled || activeTyphoons.isEmpty()) {
            cancel()
            return
        }
        if (!areNotificationsAllowed()) {
            Log.w(TAG, "Cannot post: notifications disabled or permission missing")
            return
        }

        val primary = activeTyphoons.maxByOrNull { it.currentIntensity().rank } ?: return
        val level = primary.currentIntensity()
        val last = primary.latestPoint()
        val content = buildLiveContent(primary, level, last, activeTyphoons.size, column)

        val openIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TYPHOON_ID, primary.id)
        }
        val contentPending = PendingIntent.getActivity(
            appContext,
            REQUEST_OPEN,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val progress = ((level.rank.coerceIn(1, 6)) * 100) / 6
        val progressStyle = NotificationCompat.ProgressStyle()
            .setProgress(progress)
            .setStyledByProgress(true)
            .setProgressTrackerIcon(
                IconCompat.createWithResource(appContext, R.drawable.ic_stat_typhoon)
            )

        // No app group — some OEMs override grouped posts into Aggregate_AlertingSection
        // and suppress children (mSuppressedVisualEffects=511), which hides Live Update.
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_typhoon)
            .setContentTitle(content.title)
            .setContentText(content.collapsed)
            .setSubText(content.header)
            .setStyle(progressStyle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentPending)
            .setColor(content.accentColor)
            .setColorized(false)
            .setShowWhen(true)
            .setUsesChronometer(false)
            .setSortKey("0")
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(content.chip)
            .setSound(null)
            .setVibrate(longArrayOf(0L))

        // Public lock-screen copy when device is locked.
        builder.setPublicVersion(
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_typhoon)
                .setContentTitle(content.title)
                .setContentText(content.collapsed)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setRequestPromotedOngoing(true)
                .build()
        )

        val notification = builder.build()
        val promotable = try {
            notification.hasPromotableCharacteristics()
        } catch (_: Throwable) {
            null
        }
        val canPromote = canPostPromotedNotifications()
        Log.i(
            TAG,
            "Live update ${liveTitle(primary, column)} promotable=$promotable canPromote=$canPromote chip=${content.chip}"
        )

        try {
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
            Log.i(
                TAG,
                "Posted live update for ${liveTitle(primary, column)} (${activeTyphoons.size} active)"
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "notify() blocked", e)
        }
    }

    /** Notification title; column comes from the saved app language (see the worker). */
    internal fun liveTitle(typhoon: Typhoon, column: NameColumn): String =
        typhoon.displayName(appContext, column)

    fun cancel() {
        NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
    }

    fun canPostNotifications(): Boolean = areNotificationsAllowed()

    fun areNotificationsAllowed(): Boolean {
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun canPostPromotedNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        val nm = appContext.getSystemService(NotificationManager::class.java) ?: return false
        return try {
            nm.canPostPromotedNotifications()
        } catch (_: Throwable) {
            false
        }
    }

    private fun buildLiveContent(
        typhoon: Typhoon,
        level: IntensityLevel,
        last: TyphoonPoint?,
        activeCount: Int,
        column: NameColumn
    ): LiveContent {
        val title = liveTitle(typhoon, column)
        val intensity = level.label(appContext)
        val wind = last?.let { formatWind(it.speed) }
        val pressure = last?.takeIf { it.pressure > 0 }?.let { "${it.pressure} hPa" }
        val move = last?.moveLabel(appContext)?.takeIf { it != "—" }
        val position = when {
            typhoon.positionDesc.isNotBlank() -> typhoon.positionDesc
            last != null && (last.lat != 0.0 || last.lng != 0.0) ->
                formatCoordinate(last.lat, last.lng)
            else -> null
        }
        val observed = last?.time?.takeIf { it.isNotBlank() }?.let { formatObservationTime(it) }

        val chip = when {
            wind != null -> chipWind(last!!.speed)
            level != IntensityLevel.UNKNOWN -> level.shortLabel.take(6)
            else -> title.take(4)
        }

        val collapsed = buildList {
            add(intensity)
            wind?.let { add(it) }
            pressure?.let { add(it) }
            move?.let { add(compactMove(last!!)) }
        }.joinToString(" · ").ifBlank {
            appContext.getString(R.string.live_activity_watching)
        }

        val expanded = buildString {
            appendLine(intensity)
            appendLine()
            wind?.let {
                appendLine(appContext.getString(R.string.live_activity_row_wind, it))
            }
            pressure?.let {
                appendLine(appContext.getString(R.string.live_activity_row_pressure, it))
            }
            move?.let {
                appendLine(appContext.getString(R.string.live_activity_row_move, it))
            }
            position?.let {
                appendLine(appContext.getString(R.string.live_activity_row_position, it))
            }
            observed?.let {
                appendLine()
                appendLine(appContext.getString(R.string.live_activity_row_observed, it))
            }
            if (activeCount > 1) {
                appendLine()
                append(appContext.getString(R.string.live_activity_more, activeCount - 1))
            }
        }.trimEnd()

        val header = if (activeCount > 1) {
            appContext.getString(R.string.live_activity_active_count, activeCount)
        } else {
            appContext.getString(R.string.live_activity_header)
        }

        return LiveContent(
            title = title,
            header = header,
            collapsed = collapsed,
            expanded = expanded,
            chip = chip,
            accentColor = intensityArgb(level)
        )
    }

    private fun formatWind(speedMs: Int): String =
        appContext.getString(R.string.live_activity_wind_value, speedMs)

    private fun chipWind(speedMs: Int): String =
        appContext.getString(R.string.live_activity_chip_wind, speedMs)

    private fun compactMove(point: TyphoonPoint): String {
        val dir = localizeDirection(appContext, point.moveDirection).ifBlank { return "—" }
        val speed = point.moveSpeed.trim()
        return if (speed.isBlank()) dir else "$dir $speed"
    }

    private fun intensityArgb(level: IntensityLevel): Int = when (level) {
        IntensityLevel.TD -> Color.parseColor("#64B5F6")
        IntensityLevel.TS -> Color.parseColor("#26A69A")
        IntensityLevel.STS -> Color.parseColor("#FFB74D")
        IntensityLevel.TY -> Color.parseColor("#FF8A65")
        IntensityLevel.STY -> Color.parseColor("#EF5350")
        IntensityLevel.SUPER -> Color.parseColor("#B71C1C")
        IntensityLevel.UNKNOWN -> Color.parseColor("#78909C")
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        // Drop older channels that OEMs may have demoted / auto-grouped with alerts.
        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_V2) }
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.live_activity_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = appContext.getString(R.string.live_activity_channel_desc)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private data class LiveContent(
        val title: String,
        val header: String,
        val collapsed: String,
        val expanded: String,
        val chip: String,
        val accentColor: Int
    )

    companion object {
        private const val TAG = "TyphoonLiveNotifier"
        private const val LEGACY_CHANNEL_ID = "typhoon_live_activity"
        private const val LEGACY_CHANNEL_V2 = "typhoon_live_updates_v2"
        const val CHANNEL_ID = "typhoon_live_updates_v3"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_TYPHOON_ID = "extra_typhoon_id"
        private const val REQUEST_OPEN = 2001
    }
}
