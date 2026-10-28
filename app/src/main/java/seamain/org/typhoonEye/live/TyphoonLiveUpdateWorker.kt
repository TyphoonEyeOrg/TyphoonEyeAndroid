package seamain.org.typhoonEye.live

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import seamain.org.typhoonEye.data.location.LocationProvider
import seamain.org.typhoonEye.data.preferences.UserPreferencesRepository
import seamain.org.typhoonEye.domain.model.RelayLimitedException
import seamain.org.typhoonEye.domain.names.StormNames
import seamain.org.typhoonEye.domain.repository.TyphoonRepository
import seamain.org.typhoonEye.domain.repository.WarningRepository
import java.util.concurrent.TimeUnit

/**
 * Hourly refresh of active typhoons, Live notification, and emergency alerts.
 * Uses cached / last-known user location when location-based alerts are enabled.
 */
@HiltWorker
class TyphoonLiveUpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val preferences: UserPreferencesRepository,
    private val typhoonRepository: TyphoonRepository,
    private val warningRepository: WarningRepository,
    private val locationProvider: LocationProvider,
    private val liveNotifier: TyphoonLiveNotifier,
    private val alertNotifier: TyphoonAlertNotifier
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val settings = preferences.settings.first()
            // Names follow the saved app language, not this worker Context's locale.
            val nameColumn = StormNames.columnForPreference(settings.appLanguage)

            if (!settings.liveActivityEnabled && !settings.emergencyAlertsEnabled) {
                liveNotifier.cancel()
                alertNotifier.publish(emptyList(), enabled = false)
                return Result.success()
            }

            val result = typhoonRepository.getActiveTyphoons()
            result.onSuccess { feed ->
                val active = feed.typhoons.filter { it.status == "active" }
                if (settings.emergencyAlertsEnabled) {
                    val userLocation = if (settings.locationAlertsEnabled) {
                        // Prefer a fresh fix when OS still grants it in background;
                        // otherwise reuse last foreground cache.
                        val fresh = if (locationProvider.hasLocationPermission()) {
                            locationProvider.getLocation(timeoutMs = 8_000L)
                        } else {
                            null
                        }
                        fresh?.also { preferences.cacheUserLocation(it) }
                            ?: settings.cachedUserLocation
                            ?: preferences.getCachedUserLocation()
                    } else {
                        null
                    }
                    warningRepository.fetchTyphoonAlerts(active, userLocation, nameColumn)
                        .onSuccess { alerts ->
                            alertNotifier.publish(alerts, enabled = true)
                        }
                        .onFailure { err ->
                            Log.w(TAG, "Alert refresh failed: ${err.message}")
                        }
                } else {
                    alertNotifier.publish(emptyList(), enabled = false)
                }
                // Always refresh Live after alerts so it isn't swallowed by Alerting grouping.
                if (settings.liveActivityEnabled) {
                    liveNotifier.update(active, enabled = true, nameColumn = nameColumn)
                } else {
                    liveNotifier.cancel()
                }
            }
            result.onFailure { err ->
                Log.w(TAG, "Background refresh failed: ${err.message}")
            }
            val retryUntil = result.getOrNull()?.retryAfterUntilEpochMs
                ?: (result.exceptionOrNull() as? RelayLimitedException)?.retryAfterUntilEpochMs
            val remoteFailed = result.isFailure || result.getOrNull()?.staleMessage != null
            if (
                shouldHardRetryLiveUpdate(
                    runAttemptCount = runAttemptCount,
                    remoteFailed = remoteFailed,
                    retryAfterUntilMs = retryUntil,
                    nowMs = System.currentTimeMillis()
                )
            ) {
                Result.retry()
            } else {
                if (retryUntil != null && System.currentTimeMillis() < retryUntil) {
                    Log.i(TAG, "Relay Retry-After active until $retryUntil; not retrying early")
                }
                Result.success()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Worker error", e)
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val TAG = "TyphoonLiveWorker"
        internal const val MAX_RETRIES = 3
        const val PERIOD_MINUTES = 60L
        const val BACKOFF_MINUTES = 15L

        /**
         * New name for the 60-minute schedule. With KEEP, an install that already has the old
         * 30-minute work under [LEGACY_UNIQUE_WORK] would otherwise keep it forever.
         */
        const val UNIQUE_WORK = "typhoon_live_update_v2"
        private const val LEGACY_UNIQUE_WORK = "typhoon_live_update"

        /** Idempotent: KEEP leaves an existing schedule (and its next run time) untouched. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TyphoonLiveUpdateWorker>(
                PERIOD_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()
            val workManager = WorkManager.getInstance(context.applicationContext)
            workManager.cancelUniqueWork(LEGACY_UNIQUE_WORK)
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            val workManager = WorkManager.getInstance(context.applicationContext)
            workManager.cancelUniqueWork(UNIQUE_WORK)
            workManager.cancelUniqueWork(LEGACY_UNIQUE_WORK)
        }
    }
}

/**
 * WorkManager retry while a relay `Retry-After` is still in the future would hit the
 * network again before the relay asked us to. Suppress that; the periodic schedule
 * and pull-to-refresh both consult the same deadline.
 */
internal fun shouldHardRetryLiveUpdate(
    runAttemptCount: Int,
    remoteFailed: Boolean,
    retryAfterUntilMs: Long?,
    nowMs: Long,
    maxRetries: Int = TyphoonLiveUpdateWorker.MAX_RETRIES
): Boolean {
    if (retryAfterUntilMs != null && nowMs < retryAfterUntilMs) return false
    if (!remoteFailed) return false
    return runAttemptCount < maxRetries
}
