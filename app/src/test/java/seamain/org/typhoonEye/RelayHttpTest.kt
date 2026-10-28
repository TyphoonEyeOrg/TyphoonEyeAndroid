package seamain.org.typhoonEye

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import seamain.org.typhoonEye.data.api.RelayHeaderCapture
import seamain.org.typhoonEye.data.api.RelayHeaderInterceptor
import seamain.org.typhoonEye.data.api.RelayHeaders
import seamain.org.typhoonEye.domain.model.DataSource
import seamain.org.typhoonEye.domain.model.DataSourcesFailedError
import seamain.org.typhoonEye.domain.model.SourceFailure
import seamain.org.typhoonEye.domain.model.SourceFailureKind
import seamain.org.typhoonEye.domain.model.retryAfterStillActive
import seamain.org.typhoonEye.live.LiveUpdateAttempt
import seamain.org.typhoonEye.live.decideLiveUpdateAttempt
import java.time.Instant

class RelayHttpTest {

    private val now = 1_700_000_000_000L

    @Test
    fun retryAfter_parsesDeltaSecondsAndHttpDate() {
        assertEquals(now + 120_000L, RelayHeaders.retryAfterEpochMs("120", now))
        assertEquals(now + 90_000L, RelayHeaders.retryAfterEpochMs("  90  ", now))
        assertNull(RelayHeaders.retryAfterEpochMs(null, now))
        assertNull(RelayHeaders.retryAfterEpochMs("   ", now))
        assertNull(RelayHeaders.retryAfterEpochMs("soon", now))
        assertNull(RelayHeaders.retryAfterEpochMs("-5", now))
        assertNull(RelayHeaders.retryAfterEpochMs("0", now))

        val until = Instant.parse("2015-10-21T07:28:00Z")
        val header = "Wed, 21 Oct 2015 07:28:00 GMT"
        assertEquals(until.toEpochMilli(), RelayHeaders.retryAfterEpochMs(header, until.toEpochMilli() - 5_000L))
        assertNull(RelayHeaders.retryAfterEpochMs(header, until.toEpochMilli() + 1L))

        val capped = RelayHeaders.retryAfterEpochMs((48L * 60 * 60 + 10).toString(), now)
        assertEquals(now + 48L * 60 * 60 * 1_000L, capped)
    }

    @Test
    fun staleHeader_isOnlyTheValueOne() {
        assertTrue(RelayHeaders.isStale("1"))
        assertTrue(RelayHeaders.isStale(" 1 "))
        assertFalse(RelayHeaders.isStale("0"))
        assertFalse(RelayHeaders.isStale("true"))
        assertFalse(RelayHeaders.isStale(null))
    }

    @Test
    fun interceptor_recordsStaleAndRetryAfterByPath() {
        val server = MockWebServer().apply { start() }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("X-Relay-Stale", "1")
                .setBody("{}")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "90")
                .setBody("{}")
        )
        val capture = RelayHeaderCapture()
        val client = OkHttpClient.Builder()
            .addInterceptor(RelayHeaderInterceptor(capture))
            .build()
        try {
            client.newCall(Request.Builder().url(server.url("/v1/juhe/fapigw/typhoon/active")).build())
                .execute().close()
            client.newCall(Request.Builder().url(server.url("/v1/qweather/v7/tropical/storm-list")).build())
                .execute().close()
        } finally {
            server.shutdown()
        }

        val stale = capture.consumeEndingWith(RelayHeaders.JUHE_ACTIVE_SUFFIX)
        assertEquals(true, stale?.stale)
        assertNull(stale?.retryAfter)
        val limited = capture.consumeEndingWith(RelayHeaders.QWEATHER_LIST_SUFFIX)
        assertEquals(false, limited?.stale)
        assertEquals("90", limited?.retryAfter)
        assertNull(capture.consumeEndingWith(RelayHeaders.JUHE_ACTIVE_SUFFIX))
    }

    @Test
    fun worker_doesNotHardRetryWhileRetryAfterIsActive() {
        val waiting = listOf(
            SourceFailure(DataSource.Juhe, SourceFailureKind.RelayBusy, code = "429", retryAfterEpochMs = now + 60_000),
            SourceFailure(DataSource.QWeather, SourceFailureKind.RelayDailyQuota, code = "503", retryAfterEpochMs = now + 3_600_000)
        )
        assertTrue(waiting.retryAfterStillActive(now))
        assertFalse(waiting.retryAfterStillActive(now + 3_600_000))
        assertEquals(
            LiveUpdateAttempt.Succeed,
            decideLiveUpdateAttempt(
                remoteFailed = true,
                retryAfterStillActive = DataSourcesFailedError(waiting).retryAfterStillActive(now),
                runAttemptCount = 0,
                maxRetries = 3
            )
        )

        val mixed = listOf(
            waiting[0],
            SourceFailure(DataSource.QWeather, SourceFailureKind.Network, detail = "timeout")
        )
        assertFalse(mixed.retryAfterStillActive(now))
        assertEquals(
            LiveUpdateAttempt.Retry,
            decideLiveUpdateAttempt(
                remoteFailed = true,
                retryAfterStillActive = false,
                runAttemptCount = 0,
                maxRetries = 3
            )
        )
        assertEquals(
            LiveUpdateAttempt.Succeed,
            decideLiveUpdateAttempt(
                remoteFailed = true,
                retryAfterStillActive = false,
                runAttemptCount = 3,
                maxRetries = 3
            )
        )
        assertEquals(
            LiveUpdateAttempt.Succeed,
            decideLiveUpdateAttempt(
                remoteFailed = false,
                retryAfterStillActive = false,
                runAttemptCount = 0,
                maxRetries = 3
            )
        )
    }
}
