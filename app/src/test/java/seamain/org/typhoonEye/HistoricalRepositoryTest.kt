package seamain.org.typhoonEye

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
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
import retrofit2.Response
import seamain.org.typhoonEye.data.api.JuheTyphoonApi
import seamain.org.typhoonEye.data.api.QWeatherAuthInterceptor
import seamain.org.typhoonEye.data.api.QWeatherTyphoonApi
import seamain.org.typhoonEye.data.local.MemoryHistoricalCache
import seamain.org.typhoonEye.data.local.TyphoonLocalDataSource
import seamain.org.typhoonEye.data.model.QWeatherStormInfo
import seamain.org.typhoonEye.data.model.QWeatherStormListResponse
import seamain.org.typhoonEye.data.model.QWeatherStormTrackResponse
import seamain.org.typhoonEye.data.model.QWeatherTrackPoint
import seamain.org.typhoonEye.data.repository.DefaultTyphoonRepository
import seamain.org.typhoonEye.data.sync.FeedSyncStore
import seamain.org.typhoonEye.domain.history.HistoricalNetworkException
import seamain.org.typhoonEye.domain.history.HistoricalSourceUnavailableError
import seamain.org.typhoonEye.domain.history.UnsupportedHistoryYearError
import seamain.org.typhoonEye.domain.model.RelayLimitKind
import seamain.org.typhoonEye.domain.model.RelayLimitedException
import seamain.org.typhoonEye.domain.util.WallClock
import java.time.OffsetDateTime

class HistoricalRepositoryTest {

    private lateinit var juheApi: JuheTyphoonApi
    private lateinit var qWeatherApi: QWeatherTyphoonApi
    private lateinit var local: TyphoonLocalDataSource
    private val now = OffsetDateTime.parse("2026-11-12T08:00:00+08:00").toInstant().toEpochMilli()

    @Before
    fun setup() {
        juheApi = mock()
        qWeatherApi = mock()
        local = mock()
    }

    @Test
    fun list_usesQWeather_andDoesNotTouchTheActiveCache() = runTest {
        whenever(qWeatherApi.getStormList("NP", "2026")).thenReturn(
            Response.success(
                QWeatherStormListResponse(
                    code = "200",
                    storm = listOf(
                        QWeatherStormInfo("NP_2609", "Bavi", "NP", "2026", "0"),
                        QWeatherStormInfo("NP_2610", "Mekkhala", "NP", "2026", "1"),
                        QWeatherStormInfo("EP_2601", "Other", "EP", "2026", "0")
                    )
                )
            )
        )
        val feed = repository().getHistoricalStorms(2026).getOrThrow()
        assertEquals(listOf("NP_2609", "NP_2610"), feed.storms.map { it.id })
        assertEquals("202609", feed.storms.first().number)
        assertFalse(feed.storms.first().active)
        assertTrue(feed.storms[1].active)
        verify(juheApi, never()).getActiveTyphoons(any())
        verify(qWeatherApi, never()).getStormForecast(any())
        verify(local, never()).replaceAll(any())
        verify(local, never()).upsert(any())
    }

    @Test
    fun emptyList_isSuccess() = runTest {
        whenever(qWeatherApi.getStormList(any(), any())).thenReturn(
            Response.success(QWeatherStormListResponse(code = "200", storm = emptyList()))
        )
        val feed = repository().getHistoricalStorms(2025).getOrThrow()
        assertTrue(feed.storms.isEmpty())
        assertFalse(feed.fromCache)
    }

    @Test
    fun unsupportedYear_doesNotCallUpstream() = runTest {
        val error = repository().getHistoricalStorms(2018).exceptionOrNull()
        assertTrue(error is UnsupportedHistoryYearError)
        verify(qWeatherApi, never()).getStormList(any(), any())
    }

    @Test
    fun withoutQWeather_historyIsUnavailable() = runTest {
        val error = repository(qWeather = false).getHistoricalStorms(2026).exceptionOrNull()
        assertTrue(error is HistoricalSourceUnavailableError)
        verify(qWeatherApi, never()).getStormList(any(), any())
    }

    @Test
    fun rateLimitWithoutCache_isNotAnEmptyList() = runTest {
        whenever(qWeatherApi.getStormList(any(), any())).thenReturn(
            Response.error(429, "{}".toResponseBody("application/json".toMediaType()))
        )
        val error = repository().getHistoricalStorms(2026).exceptionOrNull()
        assertTrue(error is RelayLimitedException)
        assertEquals(RelayLimitKind.Busy, (error as RelayLimitedException).kind)
    }

    @Test
    fun networkFailureAfterTtl_keepsTheSavedList() = runTest {
        val cache = MemoryHistoricalCache()
        val first = repository(cache = cache, nowMs = now)
        whenever(qWeatherApi.getStormList(any(), any())).thenReturn(
            Response.success(
                QWeatherStormListResponse(
                    code = "200",
                    storm = listOf(QWeatherStormInfo("NP_2503", "Old", "NP", "2025", "0"))
                )
            )
        )
        assertTrue(first.getHistoricalStorms(2025).isSuccess)
        whenever(qWeatherApi.getStormList(any(), any())).thenThrow(RuntimeException("offline"))
        val later = repository(cache = cache, nowMs = now + 11L * 60L * 1000L)
        val feed = later.getHistoricalStorms(2025, forceRefresh = true).getOrThrow()
        assertTrue(feed.fromCache)
        assertTrue(feed.staleReason is HistoricalNetworkException)
        assertEquals("NP_2503", feed.storms.single().id)
        verify(local, never()).replaceAll(any())
    }

    @Test
    fun track_blankPositionIsDropped_andEmptyTrackIsMissing() = runTest {
        whenever(qWeatherApi.getStormTrack("NP_2609")).thenReturn(
            Response.success(
                QWeatherStormTrackResponse(
                    code = "200",
                    isActive = "0",
                    track = listOf(
                        QWeatherTrackPoint(lat = "", lon = ""),
                        QWeatherTrackPoint(lat = "15.0", lon = "130.0", type = "TS", pressure = "990", windSpeed = "20")
                    )
                )
            )
        )
        val track = repository().getHistoricalTrack("NP_2609", "Bavi").getOrThrow()
        assertFalse(track.trackMissing)
        assertEquals(1, track.typhoon.points.size)
        assertEquals(15.0, track.typhoon.points.single().lat, 0.0)
        assertTrue(track.windRadiiMissing)
        assertTrue(track.typhoon.forecastPoints.isEmpty())
        verify(qWeatherApi, never()).getStormForecast(any())

        whenever(qWeatherApi.getStormTrack("NP_2608")).thenReturn(
            Response.success(QWeatherStormTrackResponse(code = "200", isActive = "0", track = emptyList()))
        )
        val missing = repository().getHistoricalTrack("NP_2608", "Higos").getOrThrow()
        assertTrue(missing.trackMissing)
        assertTrue(missing.typhoon.points.isEmpty())
        assertEquals("Higos", missing.typhoon.name)
    }

    @Test
    fun track_http404_isUpstreamFailure() = runTest {
        whenever(qWeatherApi.getStormTrack(any())).thenReturn(
            Response.error(404, "{}".toResponseBody("application/json".toMediaType()))
        )
        val error = repository().getHistoricalTrack("NP_2604", "Hagupit").exceptionOrNull()
        assertTrue(error is Exception)
        assertFalse(error is RelayLimitedException)
        assertFalse(error is HistoricalNetworkException)
        assertTrue(error?.message?.contains("404") == true)
    }

    private fun repository(
        qWeather: Boolean = true,
        cache: MemoryHistoricalCache = MemoryHistoricalCache(),
        nowMs: Long = now
    ) = DefaultTyphoonRepository(
        juheApi = juheApi,
        qWeatherApi = qWeatherApi,
        juheKey = "juhe",
        qWeatherAuth = if (qWeather) QWeatherAuthInterceptor(apiKey = "test-key") else QWeatherAuthInterceptor(),
        localDataSource = local,
        syncStore = object : FeedSyncStore {
            override var lastListFetchAtMs: Long? = null
            override var relayRetryAfterUntilMs: Long? = null
            override var relayLimit: RelayLimitKind? = null
        },
        clock = WallClock { nowMs },
        historyCache = cache
    )
}
