package seamain.org.typhoonEye

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import seamain.org.typhoonEye.data.api.JuheTyphoonApi
import seamain.org.typhoonEye.data.api.QWeatherAuthInterceptor
import seamain.org.typhoonEye.data.api.QWeatherTyphoonApi
import seamain.org.typhoonEye.data.local.TyphoonLocalDataSource
import seamain.org.typhoonEye.data.model.JuheActiveListResponse
import seamain.org.typhoonEye.data.model.JuheActiveListResult
import seamain.org.typhoonEye.data.model.JuheActiveTyphoon
import seamain.org.typhoonEye.data.model.JuheDetailData
import seamain.org.typhoonEye.data.model.JuheDetailResponse
import seamain.org.typhoonEye.data.model.JuheDetailResult
import seamain.org.typhoonEye.data.model.JuheTrackPoint
import seamain.org.typhoonEye.data.model.QWeatherStormForecastResponse
import seamain.org.typhoonEye.data.model.QWeatherStormInfo
import seamain.org.typhoonEye.data.model.QWeatherStormListResponse
import seamain.org.typhoonEye.data.model.QWeatherStormTrackResponse
import seamain.org.typhoonEye.data.model.QWeatherTrackPoint
import seamain.org.typhoonEye.data.repository.DefaultTyphoonRepository
import seamain.org.typhoonEye.data.sync.FeedSyncStore
import seamain.org.typhoonEye.domain.model.RelayLimitKind
import seamain.org.typhoonEye.domain.model.RelayLimitedException
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.TyphoonPoint
import seamain.org.typhoonEye.domain.util.TyphoonActivity
import seamain.org.typhoonEye.domain.util.WallClock
import seamain.org.typhoonEye.ui.DataMode
import seamain.org.typhoonEye.ui.SummarySourceChip
import seamain.org.typhoonEye.ui.summarySourceChips

class TyphoonRepositoryTest {

    private lateinit var juheApi: JuheTyphoonApi
    private lateinit var qWeatherApi: QWeatherTyphoonApi
    private lateinit var localDataSource: TyphoonLocalDataSource
    private lateinit var repository: DefaultTyphoonRepository
    private val juheKey = "juhe_key"
    private lateinit var syncStore: FakeSyncStore
    private var nowMs: Long = 0L
    private val clock = WallClock { nowMs }

    private class FakeSyncStore(
        override var lastListFetchAtMs: Long? = null,
        override var relayRetryAfterUntilMs: Long? = null,
        override var relayLimit: RelayLimitKind? = null
    ) : FeedSyncStore

    private fun auth(configured: Boolean = true): QWeatherAuthInterceptor =
        if (configured) {
            QWeatherAuthInterceptor(apiKey = "test-key")
        } else {
            QWeatherAuthInterceptor()
        }

    @Before
    fun setup() {
        juheApi = mock()
        qWeatherApi = mock()
        localDataSource = mock()
        syncStore = FakeSyncStore()
        // 2026-07-10 15:00 Beijing — one hour after the fixtures' latest observation.
        nowMs = TyphoonActivity.parseEpochMs("2026-07-10 15:00:00")!!
        repository = DefaultTyphoonRepository(
            juheApi = juheApi,
            qWeatherApi = qWeatherApi,
            juheKey = juheKey,
            qWeatherAuth = auth(configured = true),
            localDataSource = localDataSource,
            syncStore = syncStore,
            clock = clock
        )
    }

    @Test
    fun `getActiveTyphoons should return Juhe data when successful`() = runTest {
        whenever(juheApi.getActiveTyphoons(juheKey)).thenReturn(httpOk(
            JuheActiveListResponse(
                reason = "success",
                errorCode = 0,
                result = JuheActiveListResult(
                    data = listOf(
                        JuheActiveTyphoon(
                            tfid = "202609",
                            name = "巴威",
                            enname = "BAVI",
                            strong = "台风",
                            lat = "21.80",
                            lng = "126.90",
                            speed = "40",
                            pressure = "960",
                            power = "13"
                        )
                    )
                )
            )
        ))
        whenever(juheApi.getTyphoonDetail(eq(juheKey), eq("202609"))).thenReturn(httpOk(
            JuheDetailResponse(
                reason = "success",
                errorCode = 0,
                result = JuheDetailResult(
                    data = JuheDetailData(
                        tfid = "202609",
                        name = "巴威",
                        enname = "BAVI",
                        strong = "台风",
                        points = listOf(
                            JuheTrackPoint(
                                time = "2026-07-10 14:00:00",
                                lat = "21.80",
                                lng = "126.90",
                                speed = "40",
                                pressure = "960",
                                power = "13",
                                strong = "台风"
                            )
                        )
                    )
                )
            )
        ))

        val result = repository.getActiveTyphoons()

        assertTrue(result.isSuccess)
        val feed = result.getOrNull()!!
        assertFalse(feed.fromCache)
        assertEquals(1, feed.typhoons.size)
        assertEquals("巴威", feed.typhoons.first().name)
        assertEquals(1, feed.typhoons.first().points.size)
        verify(juheApi).getActiveTyphoons(juheKey)
        verify(juheApi).getTyphoonDetail(juheKey, "202609")
        verify(qWeatherApi, never()).getStormList(any(), any())
        verify(localDataSource).replaceAll(any())
    }

    @Test
    fun `getActiveTyphoons should fallback to QWeather when Juhe limit reached`() = runTest {
        whenever(juheApi.getActiveTyphoons(any())).thenReturn(httpOk(
            JuheActiveListResponse(reason = "limit reached", errorCode = 10012, result = null)
        ))
        whenever(qWeatherApi.getStormList(any(), any())).thenReturn(httpOk(
            QWeatherStormListResponse(
                code = "200",
                storm = listOf(
                    QWeatherStormInfo(
                        id = "NP_2609",
                        name = "巴威",
                        basin = "NP",
                        year = "2026",
                        isActive = "1"
                    ),
                    QWeatherStormInfo(
                        id = "NP_2601",
                        name = "洛鞍",
                        isActive = "0"
                    )
                )
            )
        ))
        whenever(qWeatherApi.getStormTrack("NP_2609")).thenReturn(httpOk(
            QWeatherStormTrackResponse(
                code = "200",
                isActive = "1",
                track = listOf(
                    QWeatherTrackPoint(
                        time = "2026-07-11T09:00+08:00",
                        lat = "25.2",
                        lon = "124.5",
                        type = "STY",
                        pressure = "950",
                        windSpeed = "42",
                        moveDir = "NW",
                        moveSpeed = "30"
                    )
                )
            )
        ))
        whenever(qWeatherApi.getStormForecast("NP_2609")).thenReturn(httpOk(
            QWeatherStormForecastResponse(code = "200", forecast = emptyList())
        ))

        val result = repository.getActiveTyphoons()

        assertTrue(result.isSuccess)
        val feed = result.getOrNull()!!
        assertEquals(1, feed.typhoons.size)
        assertEquals("巴威", feed.typhoons.first().name)
        assertEquals("强台风", feed.typhoons.first().strong)
        verify(juheApi).getActiveTyphoons(juheKey)
        verify(qWeatherApi).getStormList(eq("NP"), any())
        verify(qWeatherApi).getStormTrack("NP_2609")
        verify(localDataSource).replaceAll(any())
    }

    @Test
    fun `getActiveTyphoons returns empty list when Juhe has no active typhoons`() = runTest {
        whenever(juheApi.getActiveTyphoons(juheKey)).thenReturn(httpOk(
            JuheActiveListResponse(
                reason = "success",
                errorCode = 0,
                result = JuheActiveListResult(data = emptyList())
            )
        ))

        val result = repository.getActiveTyphoons()

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()?.typhoons?.isEmpty() == true)
        assertFalse(result.getOrNull()?.fromCache == true)
        verify(qWeatherApi, never()).getStormList(any(), any())
        verify(localDataSource).replaceAll(emptyList())
    }

    @Test
    fun `getActiveTyphoons reports missing credentials without crashing`() = runTest {
        val emptyRepo = DefaultTyphoonRepository(
            juheApi = juheApi,
            qWeatherApi = qWeatherApi,
            juheKey = "",
            qWeatherAuth = auth(configured = false),
            localDataSource = localDataSource,
            syncStore = syncStore,
            clock = clock
        )
        whenever(localDataSource.getAll()).thenReturn(emptyList())

        val result = emptyRepo.getActiveTyphoons()
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("JUHE_KEY"))
        assertTrue(message.contains("和风"))
        verify(juheApi, never()).getActiveTyphoons(any())
        verify(qWeatherApi, never()).getStormList(any(), any())
    }

    @Test
    fun `getActiveTyphoons falls back when Juhe key invalid 10001`() = runTest {
        whenever(juheApi.getActiveTyphoons(juheKey)).thenReturn(httpOk(
            JuheActiveListResponse(reason = "错误的请求KEY", errorCode = 10001, result = null)
        ))
        whenever(qWeatherApi.getStormList(any(), any())).thenReturn(httpOk(
            QWeatherStormListResponse(code = "200", storm = emptyList())
        ))

        val result = repository.getActiveTyphoons()

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()?.typhoons?.isEmpty() == true)
        verify(qWeatherApi).getStormList(eq("NP"), any())
    }

    @Test
    fun `getActiveTyphoons serves Room cache when remote fails`() = runTest {
        whenever(juheApi.getActiveTyphoons(any())).thenThrow(RuntimeException("network down"))
        whenever(qWeatherApi.getStormList(any(), any())).thenThrow(RuntimeException("network down"))
        val cached = listOf(
            Typhoon(
                id = "202609",
                name = "巴威",
                englishName = "BAVI",
                status = "active",
                strong = "台风",
                points = listOf(
                    TyphoonPoint("2026-07-10 14:00", 21.8, 126.9, 960, 40, "13", "台风")
                )
            )
        )
        whenever(localDataSource.getAll()).thenReturn(cached)

        val result = repository.getActiveTyphoons()

        assertTrue(result.isSuccess)
        val feed = result.getOrNull()!!
        assertTrue(feed.fromCache)
        assertEquals(1, feed.typhoons.size)
        assertEquals("巴威", feed.typhoons.first().name)
        assertTrue(feed.staleMessage?.isNotBlank() == true)
        verify(localDataSource).getAll()
        verify(localDataSource, never()).replaceAll(any())
    }

    @Test
    fun `getTyphoonDetail returns cache when remote fails`() = runTest {
        whenever(juheApi.getTyphoonDetail(any(), any())).thenThrow(RuntimeException("timeout"))
        val cached = Typhoon(
            id = "202609",
            name = "巴威",
            englishName = "BAVI",
            status = "active",
            points = listOf(
                TyphoonPoint("2026-07-10 14:00", 21.8, 126.9, 960, 40, "13", "台风")
            )
        )
        whenever(localDataSource.getById("202609")).thenReturn(cached)

        val result = repository.getTyphoonDetail("202609")

        assertTrue(result.isSuccess)
        assertEquals("巴威", result.getOrNull()?.name)
        verify(localDataSource).getById("202609")
    }

    // region Freshness (TYP-51)

    private val listMinute = 60_000L

    private fun juheInfo(tfid: String, endtime: String) = JuheActiveTyphoon(
        tfid = tfid,
        name = "巴威",
        enname = "BAVI",
        starttime = "2026-07-02 08:00:00",
        endtime = endtime,
        lat = "21.80",
        lng = "126.90",
        strong = "台风"
    )

    private fun <T> httpOk(body: T, vararg headerPairs: String): Response<T> =
        if (headerPairs.isEmpty()) {
            Response.success(body)
        } else {
            Response.success(body, Headers.headersOf(*headerPairs))
        }

    private fun <T> httpError(code: Int, retryAfter: String? = null): Response<T> {
        val raw = okhttp3.Response.Builder()
            .request(Request.Builder().url("https://te-relay.seamain.org/v1/juhe/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("error")
            .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
            .body("{}".toResponseBody("application/json".toMediaType()))
            .build()
        return Response.error("{}".toResponseBody("application/json".toMediaType()), raw)
    }

    private fun juheDetail(tfid: String, vararg times: String) = httpOk(
        JuheDetailResponse(
            reason = "success",
            errorCode = 0,
            result = JuheDetailResult(
                data = JuheDetailData(
                    tfid = tfid,
                    name = "巴威",
                    enname = "BAVI",
                    strong = "台风",
                    points = times.map {
                        JuheTrackPoint(time = it, lat = "21.8", lng = "126.9", speed = "40", pressure = "960")
                    }
                )
            )
        )
    )

    private fun fullCached(id: String, endTime: String) = Typhoon(
        id = id,
        name = "巴威",
        englishName = "BAVI",
        status = "active",
        endTime = endTime,
        points = listOf(
            TyphoonPoint("2026-07-10 08:00", 20.5, 128.0, 965, 38, "12", "台风"),
            TyphoonPoint("2026-07-10 14:00", 21.8, 126.9, 960, 40, "13", "台风")
        )
    )

    @Test
    fun `fresh list within 10 minutes is served from Room without network`() = runTest {
        syncStore.lastListFetchAtMs = nowMs - 9 * listMinute
        whenever(localDataSource.getAll()).thenReturn(listOf(fullCached("202609", "2026-07-10 14:00:00")))

        val feed = repository.getActiveTyphoons().getOrThrow()

        assertTrue(feed.fromCache)
        assertNull(feed.staleMessage)
        assertEquals(nowMs - 9 * listMinute, feed.fetchedAtEpochMs)
        assertEquals(1, feed.typhoons.size)
        verify(juheApi, never()).getActiveTyphoons(any())
        verify(qWeatherApi, never()).getStormList(any(), any())
    }

    @Test
    fun `stale list after 10 minutes fetches and records fetch time`() = runTest {
        syncStore.lastListFetchAtMs = nowMs - 11 * listMinute
        whenever(juheApi.getActiveTyphoons(juheKey)).thenReturn(httpOk(
            JuheActiveListResponse(reason = "success", errorCode = 0, result = JuheActiveListResult(emptyList()))
        ))

        val feed = repository.getActiveTyphoons().getOrThrow()

        assertFalse(feed.fromCache)
        assertEquals(nowMs, feed.fetchedAtEpochMs)
        assertEquals(nowMs, syncStore.lastListFetchAtMs)
        verify(juheApi).getActiveTyphoons(juheKey)
    }

    @Test
    fun `empty fresh list is also cached so no-storm periods do not refetch`() = runTest {
        syncStore.lastListFetchAtMs = nowMs - 2 * listMinute
        whenever(localDataSource.getAll()).thenReturn(emptyList())

        val feed = repository.getActiveTyphoons().getOrThrow()

        assertTrue(feed.typhoons.isEmpty())
        verify(juheApi, never()).getActiveTyphoons(any())
    }

    @Test
    fun `force refresh within 60 seconds is throttled to cache`() = runTest {
        syncStore.lastListFetchAtMs = nowMs - 30_000L
        whenever(localDataSource.getAll()).thenReturn(emptyList())

        repository.getActiveTyphoons(forceRefresh = true).getOrThrow()

        verify(juheApi, never()).getActiveTyphoons(any())
    }

    @Test
    fun `force refresh after 60 seconds bypasses the 10 minute TTL`() = runTest {
        syncStore.lastListFetchAtMs = nowMs - 2 * listMinute
        whenever(juheApi.getActiveTyphoons(juheKey)).thenReturn(httpOk(
            JuheActiveListResponse(reason = "success", errorCode = 0, result = JuheActiveListResult(emptyList()))
        ))

        val feed = repository.getActiveTyphoons(forceRefresh = true).getOrThrow()

        assertFalse(feed.fromCache)
        verify(juheApi).getActiveTyphoons(juheKey)
    }

    @Test
    fun `detail is not refetched when list update time is unchanged`() = runTest {
        whenever(localDataSource.getAll()).thenReturn(
            listOf(fullCached("202609", "2026-07-10 14:00:00"), fullCached("202610", "2026-07-10 08:00:00"))
        )
        whenever(juheApi.getActiveTyphoons(juheKey)).thenReturn(httpOk(
            JuheActiveListResponse(
                reason = "success",
                errorCode = 0,
                result = JuheActiveListResult(
                    listOf(
                        juheInfo("202609", "2026-07-10 14:00:00"), // unchanged
                        juheInfo("202610", "2026-07-10 14:00:00"), // new advisory
                        juheInfo("202611", "2026-07-10 14:00:00") // not cached yet
                    )
                )
            )
        ))
        whenever(juheApi.getTyphoonDetail(eq(juheKey), eq("202610")))
            .thenReturn(juheDetail("202610", "2026-07-10 08:00:00", "2026-07-10 14:00:00"))
        whenever(juheApi.getTyphoonDetail(eq(juheKey), eq("202611")))
            .thenReturn(juheDetail("202611", "2026-07-10 14:00:00"))

        val feed = repository.getActiveTyphoons().getOrThrow()

        assertEquals(3, feed.typhoons.size)
        verify(juheApi, never()).getTyphoonDetail(juheKey, "202609")
        verify(juheApi).getTyphoonDetail(juheKey, "202610")
        verify(juheApi).getTyphoonDetail(juheKey, "202611")
        verify(juheApi, times(2)).getTyphoonDetail(any(), any())
        // Fresh details are keyed by the list's update time.
        assertEquals("2026-07-10 14:00:00", feed.typhoons.first { it.id == "202610" }.endTime)
    }

    @Test
    fun `list-only snapshot in cache does not suppress the detail fetch`() = runTest {
        val thin = fullCached("202609", "2026-07-10 14:00:00").let { it.copy(points = it.points.take(1)) }
        whenever(localDataSource.getAll()).thenReturn(listOf(thin))
        whenever(juheApi.getActiveTyphoons(juheKey)).thenReturn(httpOk(
            JuheActiveListResponse(
                reason = "success",
                errorCode = 0,
                result = JuheActiveListResult(listOf(juheInfo("202609", "2026-07-10 14:00:00")))
            )
        ))
        whenever(juheApi.getTyphoonDetail(eq(juheKey), eq("202609")))
            .thenReturn(juheDetail("202609", "2026-07-10 08:00:00", "2026-07-10 14:00:00"))

        repository.getActiveTyphoons().getOrThrow()

        verify(juheApi).getTyphoonDetail(juheKey, "202609")
    }

    @Test
    fun `remote storm without observation for over 24h is reported dissipated`() = runTest {
        whenever(juheApi.getActiveTyphoons(juheKey)).thenReturn(httpOk(
            JuheActiveListResponse(
                reason = "success",
                errorCode = 0,
                result = JuheActiveListResult(
                    listOf(juheInfo("202609", "2026-07-10 14:00:00"), juheInfo("2026D07", "2026-07-09 14:00:00"))
                )
            )
        ))
        whenever(juheApi.getTyphoonDetail(eq(juheKey), eq("202609")))
            .thenReturn(juheDetail("202609", "2026-07-10 08:00:00", "2026-07-10 14:00:00"))
        whenever(juheApi.getTyphoonDetail(eq(juheKey), eq("2026D07")))
            .thenReturn(juheDetail("2026D07", "2026-07-09 08:00:00", "2026-07-09 14:00:00"))

        val byId = repository.getActiveTyphoons().getOrThrow().typhoons.associateBy { it.id }

        assertEquals("active", byId.getValue("202609").status)
        assertEquals("dissipated", byId.getValue("2026D07").status)
    }

    @Test
    fun `cached feed reports real fetch time and re-resolves status with current clock`() = runTest {
        val fetchedAt = nowMs - 5 * listMinute
        syncStore.lastListFetchAtMs = fetchedAt
        whenever(localDataSource.getAll()).thenReturn(listOf(fullCached("202609", "2026-07-10 14:00:00")))
        nowMs += 25 * 60 * listMinute // 25 h later the same cache row is no longer active

        val feed = repository.getCachedFeed()!!

        assertTrue(feed.fromCache)
        assertEquals(fetchedAt, feed.fetchedAtEpochMs)
        assertEquals("dissipated", feed.typhoons.single().status)
    }

    @Test
    fun `remote failure falls back to cache with stale message and last fetch time`() = runTest {
        val fetchedAt = nowMs - 3 * 60 * listMinute
        syncStore.lastListFetchAtMs = fetchedAt
        whenever(juheApi.getActiveTyphoons(any())).thenThrow(RuntimeException("network down"))
        whenever(qWeatherApi.getStormList(any(), any())).thenThrow(RuntimeException("network down"))
        whenever(localDataSource.getAll()).thenReturn(listOf(fullCached("202609", "2026-07-10 14:00:00")))

        val feed = repository.getActiveTyphoons().getOrThrow()

        assertTrue(feed.fromCache)
        assertTrue(feed.staleMessage?.isNotBlank() == true)
        assertEquals(fetchedAt, feed.fetchedAtEpochMs)
        assertEquals(fetchedAt, syncStore.lastListFetchAtMs)
    }

    @Test
    fun `fresh full detail is served from cache without network`() = runTest {
        whenever(localDataSource.getById("202609")).thenReturn(fullCached("202609", "2026-07-10 14:00:00"))
        whenever(localDataSource.getCachedAtMs("202609")).thenReturn(nowMs - 10 * listMinute)

        val result = repository.getTyphoonDetail("202609")

        assertTrue(result.isSuccess)
        verify(juheApi, never()).getTyphoonDetail(any(), any())
    }

    @Test
    fun `detail older than 30 minutes is refetched`() = runTest {
        whenever(localDataSource.getById("202609")).thenReturn(fullCached("202609", "2026-07-10 14:00:00"))
        whenever(localDataSource.getCachedAtMs("202609")).thenReturn(nowMs - 31 * listMinute)
        whenever(juheApi.getTyphoonDetail(eq(juheKey), eq("202609")))
            .thenReturn(juheDetail("202609", "2026-07-10 08:00:00", "2026-07-10 14:00:00"))

        repository.getTyphoonDetail("202609").getOrThrow()

        verify(juheApi).getTyphoonDetail(juheKey, "202609")
    }

    @Test
    fun `cancelled cache read propagates instead of looking like an empty list`() = runTest {
        whenever(localDataSource.getAll()).thenThrow(CancellationException("cancelled"))
        whenever(juheApi.getActiveTyphoons(any())).thenReturn(httpOk(
            JuheActiveListResponse(errorCode = 0, result = JuheActiveListResult())
        ))

        try {
            repository.getActiveTyphoons()
            fail("cancellation must propagate out of getActiveTyphoons")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
        verify(juheApi, never()).getActiveTyphoons(any())
    }

    @Test
    fun `cancelled detail cache read propagates instead of hitting the network`() = runTest {
        whenever(localDataSource.getById("202609")).thenThrow(CancellationException("cancelled"))

        try {
            repository.getTyphoonDetail("202609")
            fail("cancellation must propagate out of getTyphoonDetail")
        } catch (_: CancellationException) {
            // expected
        }
        verify(juheApi, never()).getTyphoonDetail(any(), any())
    }

    @Test
    fun `cancelled in-flight list fetch releases listMutex and does not fall through`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val juhe = object : JuheTyphoonApi {
            var calls = 0
            override suspend fun getActiveTyphoons(apiKey: String): Response<JuheActiveListResponse> {
                calls++
                if (calls == 1) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                return httpOk(JuheActiveListResponse(errorCode = 0, result = JuheActiveListResult()))
            }

            override suspend fun getTyphoonDetail(
                apiKey: String,
                typhoonId: String
            ): Response<JuheDetailResponse> = error("unused")
        }
        whenever(localDataSource.getAll()).thenReturn(emptyList())
        val repo = DefaultTyphoonRepository(
            juheApi = juhe,
            qWeatherApi = qWeatherApi,
            juheKey = juheKey,
            qWeatherAuth = auth(configured = true),
            localDataSource = localDataSource,
            syncStore = FakeSyncStore(),
            clock = clock
        )

        val first = async { repo.getActiveTyphoons() }
        entered.await()
        val second = async { repo.getActiveTyphoons() }
        yield()
        assertFalse(second.isCompleted)

        first.cancel()
        val secondResult = second.await()

        assertTrue(secondResult.isSuccess)
        verify(qWeatherApi, never()).getStormList(any(), any())
        try {
            first.await()
            fail("cancelled list fetch must not come back as a source Result")
        } catch (_: CancellationException) {
            // expected: the lock was released by propagating cancellation
        }
    }

    @Test
    fun `429 with Retry-After serves cache and blocks a second refresh`() = runTest {
        whenever(localDataSource.getAll()).thenReturn(listOf(fullCached("202609", "2026-07-10 14:00:00")))
        whenever(juheApi.getActiveTyphoons(any())).thenReturn(httpError(429, "120"))
        whenever(qWeatherApi.getStormList(any(), any())).thenReturn(httpError(429, "90"))

        val feed = repository.getActiveTyphoons(forceRefresh = true).getOrThrow()

        assertEquals(RelayLimitKind.Busy, feed.relayLimit)
        assertTrue(feed.fromCache)
        assertNull(feed.staleMessage)
        assertEquals(nowMs + 120_000, feed.retryAfterUntilEpochMs)
        assertEquals(nowMs + 120_000, syncStore.relayRetryAfterUntilMs)
        assertEquals(
            setOf(SummarySourceChip.Cached),
            summarySourceChips(fromCache = true, dataMode = DataMode.Live)
        )

        repository.getActiveTyphoons(forceRefresh = true).getOrThrow()
        verify(juheApi, times(1)).getActiveTyphoons(any())
        verify(qWeatherApi, times(1)).getStormList(any(), any())
    }

    @Test
    fun `503 with Retry-After is the daily limit and outlasts a shorter 429`() = runTest {
        whenever(localDataSource.getAll()).thenReturn(listOf(fullCached("202609", "2026-07-10 14:00:00")))
        whenever(juheApi.getActiveTyphoons(any())).thenReturn(httpError(429, "60"))
        whenever(qWeatherApi.getStormList(any(), any())).thenReturn(httpError(503, "7200"))

        val feed = repository.getActiveTyphoons().getOrThrow()

        assertEquals(RelayLimitKind.DailyQuotaExhausted, feed.relayLimit)
        assertEquals(nowMs + 7_200_000, feed.retryAfterUntilEpochMs)
        assertTrue(feed.fromCache)
    }

    @Test
    fun `expired Retry-After allows the next force refresh`() = runTest {
        syncStore.relayRetryAfterUntilMs = nowMs - 1_000
        syncStore.relayLimit = RelayLimitKind.Busy
        whenever(juheApi.getActiveTyphoons(juheKey)).thenReturn(httpOk(
            JuheActiveListResponse(reason = "success", errorCode = 0, result = JuheActiveListResult(emptyList()))
        ))

        val feed = repository.getActiveTyphoons(forceRefresh = true).getOrThrow()

        assertFalse(feed.fromCache)
        assertNull(feed.relayLimit)
        assertNull(syncStore.relayRetryAfterUntilMs)
        verify(juheApi).getActiveTyphoons(juheKey)
    }

    @Test
    fun `fresh fallback clears a Retry-After recorded by the source that failed`() = runTest {
        whenever(juheApi.getActiveTyphoons(any())).thenReturn(httpError(429, "3600"))
        whenever(qWeatherApi.getStormList(any(), any())).thenReturn(httpOk(
            QWeatherStormListResponse(code = "200", storm = emptyList())
        ))

        val feed = repository.getActiveTyphoons().getOrThrow()

        assertFalse(feed.fromCache)
        assertNull(feed.relayLimit)
        assertNull(syncStore.relayRetryAfterUntilMs)
        verify(qWeatherApi).getStormList(any(), any())
    }

    @Test
    fun `X-Relay-Stale marks the list cached instead of live`() = runTest {
        whenever(juheApi.getActiveTyphoons(juheKey)).thenReturn(
            httpOk(
                JuheActiveListResponse(
                    reason = "success",
                    errorCode = 0,
                    result = JuheActiveListResult(emptyList())
                ),
                "X-Relay-Stale",
                "1"
            )
        )

        val feed = repository.getActiveTyphoons().getOrThrow()

        assertTrue(feed.fromCache)
        assertNull(feed.staleMessage)
        assertNull(feed.relayLimit)
        assertEquals(
            setOf(SummarySourceChip.Cached),
            summarySourceChips(feed.fromCache, DataMode.Live)
        )
        assertFalse(summarySourceChips(feed.fromCache, DataMode.Live).contains(SummarySourceChip.Live))
        verify(qWeatherApi, never()).getStormList(any(), any())
    }

    @Test
    fun `404 is not a relay limit and does not throttle the next refresh`() = runTest {
        whenever(localDataSource.getAll()).thenReturn(listOf(fullCached("202609", "2026-07-10 14:00:00")))
        whenever(juheApi.getActiveTyphoons(any())).thenReturn(httpError(404))
        whenever(qWeatherApi.getStormList(any(), any())).thenReturn(httpError(404))

        val feed = repository.getActiveTyphoons().getOrThrow()

        assertNull(feed.relayLimit)
        assertNull(feed.retryAfterUntilEpochMs)
        assertTrue(feed.staleMessage?.contains("404") == true)
        assertNull(syncStore.relayRetryAfterUntilMs)
    }

    @Test
    fun `429 with no cache fails as relay busy and still records Retry-After`() = runTest {
        whenever(localDataSource.getAll()).thenReturn(emptyList())
        whenever(juheApi.getActiveTyphoons(any())).thenReturn(httpError(429, "60"))
        whenever(qWeatherApi.getStormList(any(), any())).thenReturn(httpError(429, "60"))

        val error = repository.getActiveTyphoons().exceptionOrNull()

        assertTrue(error is RelayLimitedException)
        val limited = error as RelayLimitedException
        assertEquals(RelayLimitKind.Busy, limited.kind)
        assertEquals(nowMs + 60_000, limited.retryAfterUntilEpochMs)
        assertEquals(nowMs + 60_000, syncStore.relayRetryAfterUntilMs)
    }

    @Test
    fun `detail fetch honors Retry-After instead of calling the network`() = runTest {
        syncStore.relayRetryAfterUntilMs = nowMs + 60_000
        syncStore.relayLimit = RelayLimitKind.DailyQuotaExhausted
        whenever(localDataSource.getById("202609")).thenReturn(fullCached("202609", "2026-07-10 14:00:00"))
        whenever(localDataSource.getCachedAtMs("202609")).thenReturn(nowMs - 31 * listMinute)

        val result = repository.getTyphoonDetail("202609")

        assertTrue(result.isSuccess)
        verify(juheApi, never()).getTyphoonDetail(any(), any())
        verify(qWeatherApi, never()).getStormTrack(any())
    }

    @Test
    fun `qweather with no active storms does not request track or forecast`() = runTest {
        whenever(juheApi.getActiveTyphoons(any())).thenReturn(httpOk(
            JuheActiveListResponse(reason = "limit reached", errorCode = 10012, result = null)
        ))
        whenever(qWeatherApi.getStormList(any(), any())).thenReturn(httpOk(
            QWeatherStormListResponse(
                code = "200",
                storm = listOf(
                    QWeatherStormInfo(id = "NP_2601", name = "洛鞍", isActive = "0")
                )
            )
        ))

        val feed = repository.getActiveTyphoons().getOrThrow()

        assertTrue(feed.typhoons.isEmpty())
        verify(qWeatherApi, never()).getStormTrack(any())
        verify(qWeatherApi, never()).getStormForecast(any())
    }

    @Test
    fun `one qweather storm failure still returns the others`() = runTest {
        whenever(juheApi.getActiveTyphoons(any())).thenReturn(httpOk(
            JuheActiveListResponse(reason = "limit reached", errorCode = 10012, result = null)
        ))
        whenever(qWeatherApi.getStormList(any(), any())).thenReturn(httpOk(
            QWeatherStormListResponse(
                code = "200",
                storm = listOf(
                    QWeatherStormInfo(id = "NP_OK", name = "巴威", isActive = "1"),
                    QWeatherStormInfo(id = "NP_BAD", name = "美莎克", isActive = "1")
                )
            )
        ))
        whenever(qWeatherApi.getStormTrack("NP_OK")).thenReturn(httpOk(
            QWeatherStormTrackResponse(
                code = "200",
                isActive = "1",
                track = listOf(
                    QWeatherTrackPoint(
                        time = "2026-07-10T14:00+08:00",
                        lat = "21.8",
                        lon = "126.9",
                        type = "TY",
                        pressure = "960",
                        windSpeed = "40"
                    )
                )
            )
        ))
        whenever(qWeatherApi.getStormTrack("NP_BAD")).thenThrow(IllegalStateException("track failed"))
        whenever(qWeatherApi.getStormForecast(any())).thenReturn(httpOk(
            QWeatherStormForecastResponse(code = "200", forecast = emptyList())
        ))

        val feed = repository.getActiveTyphoons().getOrThrow()
        val byId = feed.typhoons.associateBy { it.id }

        assertEquals(setOf("NP_OK", "NP_BAD"), byId.keys)
        assertEquals(1, byId.getValue("NP_OK").points.size)
        assertTrue(byId.getValue("NP_BAD").points.isEmpty())
        verify(qWeatherApi).getStormForecast("NP_OK")
        verify(qWeatherApi).getStormForecast("NP_BAD")
    }

    @Test
    fun `parallel qweather detail keeps stale flag and Retry-After`() = runTest {
        whenever(juheApi.getActiveTyphoons(any())).thenReturn(httpOk(
            JuheActiveListResponse(reason = "limit reached", errorCode = 10012, result = null)
        ))
        whenever(qWeatherApi.getStormList(any(), any())).thenReturn(httpOk(
            QWeatherStormListResponse(
                code = "200",
                storm = listOf(
                    QWeatherStormInfo(id = "NP_STALE", name = "巴威", isActive = "1"),
                    QWeatherStormInfo(id = "NP_LIMIT", name = "美莎克", isActive = "1")
                )
            )
        ))
        whenever(qWeatherApi.getStormTrack("NP_STALE")).thenReturn(httpOk(
            QWeatherStormTrackResponse(
                code = "200",
                isActive = "1",
                track = listOf(
                    QWeatherTrackPoint(
                        time = "2026-07-10T14:00+08:00",
                        lat = "21.8",
                        lon = "126.9",
                        type = "TY",
                        pressure = "960",
                        windSpeed = "40"
                    )
                )
            ),
            "X-Relay-Stale",
            "1"
        ))
        whenever(qWeatherApi.getStormTrack("NP_LIMIT")).thenReturn(httpError(429, "120"))
        whenever(qWeatherApi.getStormForecast(any())).thenReturn(httpOk(
            QWeatherStormForecastResponse(code = "200")
        ))

        val feed = repository.getActiveTyphoons().getOrThrow()

        assertEquals(2, feed.typhoons.size)
        assertTrue(feed.fromCache)
        assertEquals(RelayLimitKind.Busy, feed.relayLimit)
        assertEquals(nowMs + 120_000, feed.retryAfterUntilEpochMs)
        assertEquals(nowMs + 120_000, syncStore.relayRetryAfterUntilMs)
        assertEquals(RelayLimitKind.Busy, syncStore.relayLimit)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `qweather track and forecast stay within three in flight`() = runTest {
        whenever(juheApi.getActiveTyphoons(any())).thenReturn(httpOk(
            JuheActiveListResponse(reason = "limit reached", errorCode = 10012, result = null)
        ))
        val gate = QWeatherGate()
        val repo = DefaultTyphoonRepository(
            juheApi = juheApi,
            qWeatherApi = gate,
            juheKey = juheKey,
            qWeatherAuth = auth(configured = true),
            localDataSource = localDataSource,
            syncStore = syncStore,
            clock = clock
        )

        val load = async { repo.getActiveTyphoons() }
        advanceUntilIdle()

        assertEquals(3, gate.started.get())
        assertEquals(3, gate.maxInFlight.get())
        assertEquals(3, gate.inFlight.get())

        gate.release.complete(Unit)
        val feed = load.await().getOrThrow()

        assertEquals(4, feed.typhoons.size)
        assertEquals(8, gate.started.get())
        assertTrue(gate.maxInFlight.get() <= 3)
        assertEquals(0, gate.inFlight.get())
    }

    private inner class QWeatherGate : QWeatherTyphoonApi {
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val started = AtomicInteger(0)
        val release = CompletableDeferred<Unit>()
        private val ids = listOf("NP_2601", "NP_2602", "NP_2603", "NP_2604")

        override suspend fun getStormList(
            basin: String,
            year: String
        ): Response<QWeatherStormListResponse> = httpOk(
            QWeatherStormListResponse(
                code = "200",
                storm = ids.map { id ->
                    QWeatherStormInfo(id = id, name = id, isActive = "1")
                }
            )
        )

        override suspend fun getStormTrack(stormId: String): Response<QWeatherStormTrackResponse> {
            occupy()
            return httpOk(
                QWeatherStormTrackResponse(
                    code = "200",
                    isActive = "1",
                    track = listOf(
                        QWeatherTrackPoint(
                            time = "2026-07-10T14:00+08:00",
                            lat = "21.8",
                            lon = "126.9",
                            type = "TY",
                            pressure = "960",
                            windSpeed = "40"
                        )
                    )
                )
            )
        }

        override suspend fun getStormForecast(stormId: String): Response<QWeatherStormForecastResponse> {
            occupy()
            return httpOk(QWeatherStormForecastResponse(code = "200"))
        }

        private suspend fun occupy() {
            val now = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { peak -> maxOf(peak, now) }
            started.incrementAndGet()
            try {
                release.await()
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    // endregion
}
