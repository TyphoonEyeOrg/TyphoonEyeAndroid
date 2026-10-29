package seamain.org.typhoonEye.live

/**
 * What [TyphoonLiveUpdateWorker] should tell WorkManager after one pass.
 * [Succeed] leaves the normal hourly schedule in place. [Retry] uses the
 * worker's exponential backoff, which starts at 15 minutes — too soon when
 * the relay's `Retry-After` is still in the future.
 */
internal enum class LiveUpdateAttempt { Succeed, Retry }

internal fun decideLiveUpdateAttempt(
    remoteFailed: Boolean,
    retryAfterStillActive: Boolean,
    runAttemptCount: Int,
    maxRetries: Int
): LiveUpdateAttempt = when {
    !remoteFailed || retryAfterStillActive -> LiveUpdateAttempt.Succeed
    runAttemptCount < maxRetries -> LiveUpdateAttempt.Retry
    else -> LiveUpdateAttempt.Succeed
}
