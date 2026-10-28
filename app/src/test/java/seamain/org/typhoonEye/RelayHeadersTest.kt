package seamain.org.typhoonEye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import seamain.org.typhoonEye.data.api.MAX_RETRY_AFTER_MS
import seamain.org.typhoonEye.data.api.isRelayStale
import seamain.org.typhoonEye.data.api.parseRetryAfterUntilMs
import seamain.org.typhoonEye.data.api.relayLimitKind
import seamain.org.typhoonEye.domain.model.RelayLimitKind
import seamain.org.typhoonEye.live.TyphoonLiveUpdateWorker
import seamain.org.typhoonEye.live.shouldHardRetryLiveUpdate

class RelayHeadersTest {

    private val now = 1_700_000_000_000L

    @Test
    fun httpStatusMapsOnly429And503() {
        assertEquals(RelayLimitKind.Busy, relayLimitKind(429))
        assertEquals(RelayLimitKind.DailyQuotaExhausted, relayLimitKind(503))
        assertNull(relayLimitKind(404))
        assertNull(relayLimitKind(500))
        assertNull(relayLimitKind(200))
    }

    @Test
    fun retryAfterDelaySecondsBecomesAnAbsoluteDeadline() {
        assertEquals(now + 60_000, parseRetryAfterUntilMs("60", now))
        assertEquals(now + 60_000, parseRetryAfterUntilMs("  60  ", now))
        assertNull(parseRetryAfterUntilMs(null, now))
        assertNull(parseRetryAfterUntilMs("", now))
        assertNull(parseRetryAfterUntilMs("0", now))
        assertNull(parseRetryAfterUntilMs("-5", now))
        assertNull(parseRetryAfterUntilMs("soon", now))
    }

    @Test
    fun retryAfterIsCappedAtOneDay() {
        val huge = parseRetryAfterUntilMs("99999999", now)
        assertEquals(now + MAX_RETRY_AFTER_MS, huge)
    }

    @Test
    fun retryAfterHttpDateIsHonoredWhenItIsStillInTheFuture() {
        val until = parseRetryAfterUntilMs("Thu, 01 Jan 1970 00:01:00 GMT", nowMs = 0L)
        assertEquals(60_000L, until)
        assertNull(parseRetryAfterUntilMs("Thu, 01 Jan 1970 00:01:00 GMT", nowMs = 60_000L))
    }

    @Test
    fun relayStaleHeaderIsOnlyTheValueOne() {
        assertTrue(isRelayStale("1"))
        assertTrue(isRelayStale(" 1 "))
        assertFalse(isRelayStale("0"))
        assertFalse(isRelayStale(null))
        assertFalse(isRelayStale("true"))
    }

    @Test
    fun retryAfterSuppressesAnEarlyWorkerRetry() {
        val until = now + 3_600_000
        assertFalse(
            shouldHardRetryLiveUpdate(
                runAttemptCount = 0,
                remoteFailed = true,
                retryAfterUntilMs = until,
                nowMs = now
            )
        )
        assertTrue(
            shouldHardRetryLiveUpdate(
                runAttemptCount = 0,
                remoteFailed = true,
                retryAfterUntilMs = until,
                nowMs = until
            )
        )
        assertFalse(
            shouldHardRetryLiveUpdate(
                runAttemptCount = 0,
                remoteFailed = false,
                retryAfterUntilMs = null,
                nowMs = now
            )
        )
        assertTrue(
            shouldHardRetryLiveUpdate(
                runAttemptCount = TyphoonLiveUpdateWorker.MAX_RETRIES - 1,
                remoteFailed = true,
                retryAfterUntilMs = null,
                nowMs = now
            )
        )
        assertFalse(
            shouldHardRetryLiveUpdate(
                runAttemptCount = TyphoonLiveUpdateWorker.MAX_RETRIES,
                remoteFailed = true,
                retryAfterUntilMs = null,
                nowMs = now
            )
        )
    }
}
