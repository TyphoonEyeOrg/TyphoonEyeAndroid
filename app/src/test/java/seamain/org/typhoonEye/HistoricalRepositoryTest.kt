package seamain.org.typhoonEye

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import seamain.org.typhoonEye.data.api.DataSourceConfig
import seamain.org.typhoonEye.data.api.JuheTyphoonApi
import seamain.org.typhoonEye.data.api.QWeatherTyphoonApi
import seamain.org.typhoonEye.data.api.RelayHeaderCapture
import seamain.org.typhoonEye.data.local.MemoryHistoricalCache
import seamain.org.typhoonEye.data.local.TyphoonLocalDataSource
import seamain.org.typhoonEye.data.model.QWeatherStormInfo
import seamain.org.typhoonEye.data.model.QWeatherStormListResponse
import seamain.org.typhoonEye.data.model.QWeatherStormTrackResponse
import seamain.org.typhoonEye.data.model.QWeatherTrackPoint
import seamain.org.typhoonEye.data.repository.DefaultTyphoonRepository
import seamain.org.typhoonEye.domain.history.HistoricalCoverage
import seamain.org.typhoonEye.domain.model.HistoricalSourceUnavailableError
import seamain.org.typhoonEye.domain.model.SourceFailureKind
import seamain.org.typhoonEye.domain.model.TyphoonDetailUnavailableError
import seamain.org.typhoonEye.domain.model.UnsupportedHistoryYearError
import seamain.org.typhoonEye.domain.util.WallClock
import java.time.OffsetDateTime

class HistoricalRepositoryTest {

    private lateinit var juheApi: JuheTyphoonApi
    private lateinit var qWeatherApi: QWeatherTyphoonApi
    private lateinit var local: TyphoonLocalDataSource
    private lateinit var repository: DefaultTyphoonRepository
    private val now = OffsetDateTime.parse("2026-11-12T08:00:00+08:00").toInstant().toEpochMilli()

    @Before
    fun setup() {
        juheApi = mock()
        qWeatherApi = mock()
        local = mock()
        repository = repository(qWeather = true)
    }

    @Test
    fun list_usesQWeatherForSupportedYear_andDoesNotTouchTheActiveCache() = runTest {
        whenever(qWeatherApi.getStormList("NP", "2026")).thenReturn(
            QWeatherStormListResponse(
                code = "200",
                storm = listOf(
                    QWeatherStormInfo(id = "NP_2609", name = "Bavi", basin = "NP", year = "2026", isActive = "0"),
                    QWeatherStormInfo(id = "NP_2610", name = "Mekkhala", basin = "NP", year = "2026", isActive = "1"),
                    QWeatherStormInfo(id = "EP_2601", name = "Other", basin = "EP", year = "2026", isActive = "0")
                )
            )
        )

        val feed = repository.getHistoricalStorms(2026).getOrThrow()

        assertEquals(listOf("NP_2609", "NP_2610"), feed.storms.map { it.id })
        assertEquals("202609", feed.storms.first { it.id == "NP_2609" }.number)
        assertFalse(feed.storms.first { it.id == "NP_2609" }.active)
        assertTrue(feed.storms.first { it.id == "NP_2610" }.active)
        verify(juheApi, never()).getActiveTyphoons(any())
        verify(qWeatherApi, never()).getStormForecast(any())
        verify(local, never()).replaceAll(any())
        verify(local, never()).upsert(any())
    }

    @Test
    fun emptyList_isSuccess_notAnError() = runTest {
        whenever(qWeatherApi.getStormList(any(), any())).thenReturn(
            QWeatherStormListResponse(code = "200", storm = emptyList())
        )
        val feed = repository.getHistoricalStorms(2025).getOrThrow()
        assertTrue(feed.storms.isEmpty())
        assertFalse(feed.fromCache)
    }

    @Test
    fun unsupportedYear_doesNotCallUpstream() = runTest {
        val error = repository.getHistoricalStorms(2018).exceptionOrNull()
        assertTrue(error is UnsupportedHistoryYearError)
        verify(qWeatherApi, never()).getStormList(any(), any())
        verify(juheApi, never()).getActiveTyphoons(any())
    }

    @Test
    fun withoutQWeather_historyIsUnavailable() = runTest {
        val repo = repository(qWeather = false)
        val error = repo.getHistoricalStorms(2026).exceptionOrNull()
        assertTrue(error is HistoricalSourceUnavailableError)
        verify(qWeatherApi, never()).getStormList(any(), any())
        verify(juheApi, never()).getActiveTyphoons(any())
    }

    @Test
    fun rateLimitWithoutCache_isNotAnEmptyList() = runTest {
        whenever(qWeatherApi.getStormList(any(), any())).thenThrow(relayHttpException(429, "60"))
        val result = repository.getHistoricalStorms(2026)
        val failed = result.exceptionOrNull() as seamain.org.typhoonEye.domain.model.DataSourcesFailedError
        assertTrue(result.isFailure)
        assertEquals(SourceFailureKind.RelayBusy, failed.failures.single().kind)
    }

    @Test
    fun networkFailure_usesSavedList_andDoesNotReplaceItWithAnError() = runTest {
        whenever(qWeatherApi.getStormList(any(), any())).thenReturn(
            QWeatherStormListResponse(
                code = "200",
                storm = listOf(QWeatherStormInfo(id = "NP_2601", name = "Test", basin = "NP", year = "2026", isActive = "0"))
            )
        )
        assertTrue(repository.getHistoricalStorms(2026).isSuccess)
        whenever(qWeatherApi.getStormList(any(), any())).thenThrow(RuntimeException("offline"))
        val again = repository.getHistoricalStorms(2026, forceRefresh = true)
        // Inside the minimum force interval the saved list is served without a new call.
        assertTrue(again.isSuccess)
        assertTrue(again.getOrThrow().fromCache)
        assertEquals("NP_2601", again.getOrThrow().storms.single().id)
    }

    @Test
    fun networkFailureAfterTtl_keepsTheSavedListDistinctFromNoResults() = runTest {
        val cache = MemoryHistoricalCache()
        val clock = WallClock { now }
        val repo = DefaultTyphoonRepository(
            juheApi = juheApi,
            qWeatherApi = qWeatherApi,
            config = DataSourceConfig(juheKey = "k", qWeatherDirectConfigured = true),
            localDataSource = local,
            syncStore = MemoryFeedSyncStore(),
            clock = clock,
            relayHeaders = RelayHeaderCapture(),
            historyCache = cache
        )
        whenever(qWeatherApi.getStormList(any(), any())).thenReturn(
            QWeatherStormListResponse(
                code = "200",
                storm = listOf(QWeatherStormInfo(id = "NP_2503", name = "Old", basin = "NP", year = "2025", isActive = "0"))
            )
        )
        assertTrue(repo.getHistoricalStorms(2025).isSuccess)
        whenever(qWeatherApi.getStormList(any(), any())).thenThrow(RuntimeException("offline"))
        val clockLater = WallClock { now + HistoricalCoverage.supportedYears(now).size * 60L * 60L * 1000L }
        val later = DefaultTyphoonRepository(
            juheApi = juheApi,
            qWeatherApi = qWeatherApi,
            config = DataSourceConfig(juheKey = "k", qWeatherDirectConfigured = true),
            localDataSource = local,
            syncStore = MemoryFeedSyncStore(),
            clock = clockLater,
            relayHeaders = RelayHeaderCapture(),
            historyCache = cache
        )
        val feed = later.getHistoricalStorms(2025, forceRefresh = true).getOrThrow()
        assertTrue(feed.fromCache)
        assertEquals(SourceFailureKind.Network, (feed.staleReason as seamain.org.typhoonEye.domain.model.DataSourcesFailedError).failures.single().kind)
        assertEquals("NP_2503", feed.storms.single().id)
        verify(local, never()).replaceAll(any())
    }

    @Test
    fun track_emptyIsMissingPath_andBlankPositionIsDropped() = runTest {
        whenever(qWeatherApi.getStormTrack("NP_2609")).thenReturn(
            QWeatherStormTrackResponse(
                code = "200",
                isActive = "0",
                track = listOf(
                    QWeatherTrackPoint(time = "2026-07-01T00:00+08:00", lat = "", lon = "", type = "TS", pressure = "", windSpeed = ""),
                    QWeatherTrackPoint(
                        time = "2026-07-01T06:00+08:00",
                        lat = "15.0",
                        lon = "130.0",
                        type = "TS",
                        pressure = "990",
                        windSpeed = "20"
                    )
                )
            )
        )
        val track = repository.getHistoricalTrack("NP_2609", "Bavi").getOrThrow()
        assertFalse(track.trackMissing)
        assertEquals(1, track.typhoon.points.size)
        assertEquals(15.0, track.typhoon.points.single().lat, 0.0)
        assertTrue(track.typhoon.points.single().pressureReported)
        assertTrue(track.windRadiiMissing)
        assertTrue(track.typhoon.forecastPoints.isEmpty())
        assertEquals("dissipated", track.typhoon.status)
        verify(qWeatherApi, never()).getStormForecast(any())
        verify(local, never()).upsert(any())
    }

    @Test
    fun track_allBlankPositions_isMissingPathNotAFabricatedPoint() = runTest {
        whenever(qWeatherApi.getStormTrack("NP_2608")).thenReturn(
            QWeatherStormTrackResponse(code = "200", isActive = "0", track = emptyList())
        )
        val track = repository.getHistoricalTrack("NP_2608", "Higos").getOrThrow()
        assertTrue(track.trackMissing)
        assertTrue(track.typhoon.points.isEmpty())
        assertEquals("Higos", track.typhoon.name)
    }

    @Test
    fun track_http404_isUpstreamFailure_notAMissingPath() = runTest {
        whenever(qWeatherApi.getStormTrack(any())).thenThrow(relayHttpException(404))
        val error = repository.getHistoricalTrack("NP_2604", "Hagupit").exceptionOrNull()
        assertTrue(error is TyphoonDetailUnavailableError)
    }

    private fun repository(qWeather: Boolean) = DefaultTyphoonRepository(
        juheApi = juheApi,
        qWeatherApi = qWeatherApi,
        config = DataSourceConfig(juheKey = "k", qWeatherDirectConfigured = qWeather),
        localDataSource = local,
        syncStore = MemoryFeedSyncStore(),
        clock = WallClock { now },
        relayHeaders = RelayHeaderCapture(),
        historyCache = MemoryHistoricalCache()
    )
}
