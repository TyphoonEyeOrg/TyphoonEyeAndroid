package seamain.org.typhoonEye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import seamain.org.typhoonEye.domain.history.HistoricalCoverage
import seamain.org.typhoonEye.domain.history.HistoricalNetworkException
import seamain.org.typhoonEye.domain.history.HistoricalSourceUnavailableError
import seamain.org.typhoonEye.domain.history.HistoricalStorm
import seamain.org.typhoonEye.domain.history.HistoricalStormFeed
import seamain.org.typhoonEye.domain.history.HistoricalTrack
import seamain.org.typhoonEye.domain.history.HistoryPhase
import seamain.org.typhoonEye.domain.history.TrackPhase
import seamain.org.typhoonEye.domain.history.UnsupportedHistoryYearError
import seamain.org.typhoonEye.domain.history.filterHistoricalStorms
import seamain.org.typhoonEye.domain.history.historicalNumber
import seamain.org.typhoonEye.domain.history.presentHistory
import seamain.org.typhoonEye.domain.history.presentTrack
import seamain.org.typhoonEye.domain.model.RelayLimitKind
import seamain.org.typhoonEye.domain.model.RelayLimitedException
import seamain.org.typhoonEye.domain.model.Typhoon
import java.time.OffsetDateTime

class HistoricalQueryTest {

    private val now = OffsetDateTime.parse("2026-11-12T08:00:00+08:00").toInstant().toEpochMilli()
    private val bavi = HistoricalStorm("NP_2609", "Bavi", 2026, "NP", false, "202609")
    private val mekkhala = HistoricalStorm("NP_2610", "Mekkhala", 2026, "NP", false, "202610")
    private val odd = HistoricalStorm("NP_200012", "Unnamed", 2026, "NP", false, null)

    @Test
    fun supportedYears_areCurrentAndPreviousUtc8Only() {
        assertEquals(listOf(2026, 2025), HistoricalCoverage.supportedYears(now))
        assertFalse(HistoricalCoverage.isSupported(2024, now))
        assertFalse(HistoricalCoverage.isSupported(2027, now))
        val still2025 = OffsetDateTime.parse("2025-12-31T23:30:00+08:00").toInstant().toEpochMilli()
        assertEquals(listOf(2025, 2024), HistoricalCoverage.supportedYears(still2025))
    }

    @Test
    fun number_isDerivedOnlyFromFourDigitIds() {
        assertEquals("202609", historicalNumber("NP_2609", 2026))
        assertNull(historicalNumber("NP_2609", 2025))
        assertNull(historicalNumber("NP_200012", 2020))
    }

    @Test
    fun filter_matchesNameAndNumber_andNotTheYearPrefix() {
        val storms = listOf(bavi, mekkhala, odd)
        assertEquals(listOf("NP_2610", "NP_2609", "NP_200012"), filterHistoricalStorms(storms, 2026, "").map { it.id })
        assertEquals(listOf("NP_2609"), filterHistoricalStorms(storms, 2026, "bavi").map { it.id })
        assertEquals(listOf("NP_2609"), filterHistoricalStorms(storms, 2026, "202609").map { it.id })
        assertEquals(listOf("NP_2609"), filterHistoricalStorms(storms, 2026, "09").map { it.id })
        assertTrue(filterHistoricalStorms(storms, 2026, "26").isEmpty())
        assertEquals(listOf("NP_200012"), filterHistoricalStorms(storms, 2026, "200012").map { it.id })
        assertTrue(filterHistoricalStorms(storms, 2025, "bavi").isEmpty())
    }

    @Test
    fun presentHistory_distinguishesEmptyFailureAndOffline() {
        val feed = HistoricalStormFeed(2026, listOf(bavi), fromCache = false, fetchedAtEpochMs = now)
        assertEquals(HistoryPhase.Results, presentHistory(2026, "", feed, null).phase)
        assertEquals(HistoryPhase.NoResults, presentHistory(2026, "no-such", feed, null).phase)
        assertEquals(HistoryPhase.NoResults, presentHistory(2026, "", feed.copy(storms = emptyList()), null).phase)

        val offline = feed.copy(fromCache = true, staleReason = HistoricalNetworkException("offline"))
        assertEquals(HistoryPhase.OfflineResults, presentHistory(2026, "", offline, null).phase)
        assertEquals(
            HistoryPhase.OfflineUnavailable,
            presentHistory(2026, "", feed.copy(storms = emptyList(), staleReason = HistoricalNetworkException("offline")), null).phase
        )

        val limited = RelayLimitedException(RelayLimitKind.Busy, now + 60_000)
        assertEquals(HistoryPhase.RateLimited, presentHistory(2026, "", null, limited).phase)
        assertEquals(HistoryPhase.RateLimited, presentHistory(2026, "", feed.copy(staleReason = limited), null).phase)
        assertEquals(HistoryPhase.UpstreamError, presentHistory(2026, "", null, Exception("QWeather HTTP 400")).phase)
        assertEquals(HistoryPhase.SourceUnavailable, presentHistory(2026, "", null, HistoricalSourceUnavailableError()).phase)
        assertEquals(HistoryPhase.UnsupportedYear, presentHistory(2026, "", null, UnsupportedHistoryYearError(2018)).phase)
    }

    @Test
    fun presentTrack_missingPathIsNotAFailure() {
        val withPath = HistoricalTrack(
            typhoon = Typhoon("NP_2609", "Bavi", "", "dissipated"),
            trackMissing = false,
            windRadiiMissing = true,
            fromCache = false
        )
        assertEquals(TrackPhase.Ready, presentTrack(withPath, null))
        assertEquals(TrackPhase.MissingPath, presentTrack(withPath.copy(trackMissing = true), null))
        assertEquals(
            TrackPhase.OfflineReady,
            presentTrack(withPath.copy(staleReason = HistoricalNetworkException("offline")), null)
        )
        assertEquals(
            TrackPhase.RateLimited,
            presentTrack(null, RelayLimitedException(RelayLimitKind.DailyQuotaExhausted, null))
        )
        assertEquals(TrackPhase.UpstreamError, presentTrack(null, Exception("QWeather HTTP 404")))
    }
}
