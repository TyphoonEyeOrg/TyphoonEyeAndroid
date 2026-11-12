package seamain.org.typhoonEye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import seamain.org.typhoonEye.domain.history.HistoricalCoverage
import seamain.org.typhoonEye.domain.history.HistoricalStorm
import seamain.org.typhoonEye.domain.history.HistoricalStormFeed
import seamain.org.typhoonEye.domain.history.HistoricalTrack
import seamain.org.typhoonEye.domain.history.HistoryPhase
import seamain.org.typhoonEye.domain.history.TrackPhase
import seamain.org.typhoonEye.domain.history.filterHistoricalStorms
import seamain.org.typhoonEye.domain.history.historicalNumber
import seamain.org.typhoonEye.domain.history.presentHistory
import seamain.org.typhoonEye.domain.history.presentTrack
import seamain.org.typhoonEye.domain.model.DataSource
import seamain.org.typhoonEye.domain.model.DataSourcesFailedError
import seamain.org.typhoonEye.domain.model.HistoricalSourceUnavailableError
import seamain.org.typhoonEye.domain.model.SourceFailure
import seamain.org.typhoonEye.domain.model.SourceFailureKind
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.UnsupportedHistoryYearError
import java.time.OffsetDateTime

class HistoricalQueryTest {

    private val now = OffsetDateTime.parse("2026-11-12T08:00:00+08:00").toInstant().toEpochMilli()

    private val bavi = storm("NP_2609", "Bavi", "202609")
    private val mekkhala = storm("NP_2610", "Mekkhala", "202610")
    private val odd = HistoricalStorm("NP_200012", "Unnamed", 2026, "NP", false, null)

    @Test
    fun supportedYears_areCurrentAndPreviousUtc8Only() {
        assertEquals(listOf(2026, 2025), HistoricalCoverage.supportedYears(now))
        assertTrue(HistoricalCoverage.isSupported(2026, now))
        assertTrue(HistoricalCoverage.isSupported(2025, now))
        assertFalse(HistoricalCoverage.isSupported(2024, now))
        assertFalse(HistoricalCoverage.isSupported(2027, now))
        val still2025 = OffsetDateTime.parse("2025-12-31T23:30:00+08:00").toInstant().toEpochMilli()
        assertEquals(listOf(2025, 2024), HistoricalCoverage.supportedYears(still2025))
    }

    @Test
    fun number_isDerivedOnlyFromFourDigitIds() {
        assertEquals("202609", historicalNumber("NP_2609", 2026))
        assertEquals("202609", historicalNumber("np_2609", 2026))
        assertNull(historicalNumber("NP_2609", 2025))
        assertNull(historicalNumber("NP_200012", 2020))
    }

    @Test
    fun filter_matchesNameAndNumber_andNotTheYearPrefix() {
        val storms = listOf(bavi, mekkhala, odd)
        assertEquals(listOf("NP_2610", "NP_2609", "NP_200012"), filterHistoricalStorms(storms, 2026, "").map { it.id })
        assertEquals(listOf("NP_2609"), filterHistoricalStorms(storms, 2026, "bavi").map { it.id })
        assertEquals(listOf("NP_2609"), filterHistoricalStorms(storms, 2026, "202609").map { it.id })
        assertEquals(listOf("NP_2609"), filterHistoricalStorms(storms, 2026, "2609").map { it.id })
        assertEquals(listOf("NP_2609"), filterHistoricalStorms(storms, 2026, "09").map { it.id })
        assertEquals(listOf("NP_2609"), filterHistoricalStorms(storms, 2026, "9").map { it.id })
        assertTrue(filterHistoricalStorms(storms, 2026, "26").isEmpty())
        assertTrue(filterHistoricalStorms(storms, 2026, "2026").isEmpty())
        assertEquals(listOf("NP_200012"), filterHistoricalStorms(storms, 2026, "200012").map { it.id })
        assertTrue(filterHistoricalStorms(storms, 2025, "bavi").isEmpty())
    }

    @Test
    fun presentHistory_distinguishesEmptyFailureAndOffline() {
        val feed = HistoricalStormFeed(2026, listOf(bavi), fromCache = false, fetchedAtEpochMs = now)
        assertEquals(HistoryPhase.Results, presentHistory(2026, "", feed, null).phase)
        assertEquals(HistoryPhase.NoResults, presentHistory(2026, "no-such", feed, null).phase)
        assertFalse(presentHistory(2026, "no-such", feed, null).showingSavedCopy)

        val empty = feed.copy(storms = emptyList())
        assertEquals(HistoryPhase.NoResults, presentHistory(2026, "", empty, null).phase)

        val offline = feed.copy(
            fromCache = true,
            staleReason = DataSourcesFailedError(listOf(SourceFailure(DataSource.QWeather, SourceFailureKind.Network)))
        )
        val offlineView = presentHistory(2026, "", offline, null)
        assertEquals(HistoryPhase.OfflineResults, offlineView.phase)
        assertTrue(offlineView.showingSavedCopy)
        assertEquals(HistoryPhase.OfflineUnavailable, presentHistory(2026, "", empty.copy(fromCache = true, staleReason = offline.staleReason), null).phase)

        val limited = DataSourcesFailedError(
            listOf(SourceFailure(DataSource.QWeather, SourceFailureKind.RelayBusy, code = "429"))
        )
        assertEquals(HistoryPhase.RateLimited, presentHistory(2026, "", null, limited).phase)
        assertTrue(presentHistory(2026, "", null, limited).rows.isEmpty())
        val limitedWithCopy = presentHistory(2026, "", feed.copy(staleReason = limited), null)
        assertEquals(HistoryPhase.RateLimited, limitedWithCopy.phase)
        assertEquals(1, limitedWithCopy.rows.size)

        val daily = DataSourcesFailedError(
            listOf(SourceFailure(DataSource.QWeather, SourceFailureKind.RelayDailyQuota, code = "503"))
        )
        assertEquals(HistoryPhase.RateLimited, presentHistory(2026, "", null, daily).phase)
        assertEquals(
            HistoryPhase.UpstreamError,
            presentHistory(
                2026,
                "",
                null,
                DataSourcesFailedError(listOf(SourceFailure(DataSource.QWeather, SourceFailureKind.ApiError, code = "400")))
            ).phase
        )
        assertEquals(HistoryPhase.SourceUnavailable, presentHistory(2026, "", null, HistoricalSourceUnavailableError()).phase)
        assertEquals(HistoryPhase.UnsupportedYear, presentHistory(2026, "", null, UnsupportedHistoryYearError(2018)).phase)
        assertEquals(HistoryPhase.Loading, presentHistory(2026, "", null, null).phase)
    }

    @Test
    fun presentTrack_missingPathIsNotAFailure() {
        val withPath = track(missing = false)
        assertEquals(TrackPhase.Ready, presentTrack(withPath, null))
        assertEquals(TrackPhase.MissingPath, presentTrack(track(missing = true), null))
        val offline = withPath.copy(
            fromCache = true,
            staleReason = DataSourcesFailedError(listOf(SourceFailure(DataSource.QWeather, SourceFailureKind.Network)))
        )
        assertEquals(TrackPhase.OfflineReady, presentTrack(offline, null))
        assertEquals(
            TrackPhase.OfflineUnavailable,
            presentTrack(null, DataSourcesFailedError(listOf(SourceFailure(DataSource.QWeather, SourceFailureKind.Network))))
        )
        assertEquals(
            TrackPhase.RateLimited,
            presentTrack(null, DataSourcesFailedError(listOf(SourceFailure(DataSource.QWeather, SourceFailureKind.QuotaExceeded, code = "429"))))
        )
        assertEquals(
            TrackPhase.UpstreamError,
            presentTrack(null, DataSourcesFailedError(listOf(SourceFailure(DataSource.QWeather, SourceFailureKind.ApiError, code = "404"))))
        )
    }

    private fun storm(id: String, name: String, number: String?) =
        HistoricalStorm(id, name, 2026, "NP", false, number)

    private fun track(missing: Boolean) = HistoricalTrack(
        typhoon = Typhoon(id = "NP_2609", name = "Bavi", englishName = "", status = "dissipated"),
        trackMissing = missing,
        windRadiiMissing = missing,
        fromCache = false
    )
}
