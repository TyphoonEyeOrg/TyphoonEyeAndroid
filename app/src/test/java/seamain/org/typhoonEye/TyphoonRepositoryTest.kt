package seamain.org.typhoonEye

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.atomic.AtomicInteger
import seamain.org.typhoonEye.data.api.JuheTyphoonApi
import seamain.org.typhoonEye.data.api.DataSourceConfig
import seamain.org.typhoonEye.data.api.QWeatherTyphoonApi
import seamain.org.typhoonEye.data.api.RelayHeaderCapture
import seamain.org.typhoonEye.data.api.RelayHeaders
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
import seamain.org.typhoonEye.domain.model.DataSource
import seamain.org.typhoonEye.domain.model.DataSourcesFailedError
import seamain.org.typhoonEye.domain.model.NoDataSourceConfiguredError
import seamain.org.typhoonEye.domain.model.SourceFailureKind
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.TyphoonPoint
import seamain.org.typhoonEye.domain.util.TyphoonActivity
import seamain.org.typhoonEye.domain.util.WallClock

class TyphoonRepositoryTest {

    private lateinit var juheApi: JuheTyphoonApi
    private lateinit var qWeatherApi: QWeatherTyphoonApi
    private lateinit var localDataSource: TyphoonLocalDataSource
    private lateinit var repository: DefaultTyphoonRepository
    private val juheKey = "juhe_key"
    private lateinit var syncStore: MemoryFeedSyncStore
    private lateinit var relayHeaders: RelayHeaderCapture
    private var nowMs: Long = 0L
    private val clock = WallClock { nowMs }

    private fun direct(juheKey: String, qWeatherConfigured: Boolean) =
        DataSourceConfig(juheKey = juheKey, qWeatherDirectConfigured = qWeatherConfigured)

    @Before
    fun setup() {
        juheApi = mock()
        qWeatherApi = mock()
        localDataSource = mock()
        syncStore = MemoryFeedSyncStore()
        relayHeaders = RelayHeaderCapture()
        // 2026-07-10 15:00 Beijing — one hour after the fixtures' latest observation.
        nowMs = TyphoonActivity.parseEpochMs("2026-07-10 15:00:00")!!
        repository = DefaultTyphoonRepository(
            juheApi = juheApi,
            qWeatherApi = qWeatherApi,
            config = direct(juheKey, qWeatherConfigured = true),
            localDataSource = localDataSource,
            syncStore = syncStore,
            clock = clock,
            relayHeaders = relayHeaders
        )
    }

    @Test
    fun `getActiveTyphoons should return Juhe data when successful`() = runTest {
        whenever(juheApi.getActiveTyphoons(juheKey)).thenReturn(
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
        )
        whenever(juheApi.getTyphoonDetail(eq(juheKey), eq("202609"))).thenReturn(
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
        )

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
        whenever(juheApi.getActiveTyphoons(any())).thenReturn(
            JuheActiveListResponse(reason = "limit reached", errorCode = 10012, result = null)
        )
        whenever(qWeatherApi.getStormList(any(), any())).thenReturn(
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
        )
        whenever(qWeatherApi.getStormTrack("NP_2609")).thenReturn(
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
        )
        whenever(qWeatherApi.getStormForecast("NP_2609")).thenReturn(
            QWeatherStormForecastResponse(code = "200", forecast = emptyList())
        )

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
        whenever(juheApi.getActiveTyphoons(juheKey)).thenReturn(
            JuheActiveListResponse(
                reason = "success",
                errorCode = 0,
                result = JuheActiveListResult(data = emptyList())
            )
        )

        val result = repository.getActiveTyphoons()

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()?.typhoons?.isEmpty() == true)
        assertFalse(result.getOrNull()?.fromCache == true)
        verify(qWeatherApi, never()).getStormList(any(), any())
        verify(localDataSource).replaceAll(emptyList())
    }

    private fun repoWith(juheKey: String, qWeatherConfigured: Boolean) = DefaultTyphoonRepository(
        juheApi = juheApi,
        qWeatherApi = qWeatherApi,
        config = direct(juheKey, qWeatherConfigured),
        localDataSource = localDataSource,
        syncStore = syncStore,
        clock = clock,
        relayHeaders = relayHeaders
    )

    @Test
    fun `getActiveTyphoons without any key returns typed NoDataSourceConfiguredError`() = runTest {
        // Direct-mode build made without keys: no JUHE_KEY, no QWeather credentials.
        val emptyRepo = repoWith(juheKey = "", qWeatherConfigured = false)
        whenever(localDataSource.getAll()).thenReturn(emptyList())

        val result = emptyRepo.getActiveTyphoons()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is NoDataSourceConfiguredError)
        assertFalse(emptyRepo.hasAnyDataSource)
        // No network attempts and no cache wipe.
        verify(juheApi, never()).getActiveTyphoons(any())
        verify(qWeatherApi, never()).getStormList(any(), any())
        verify(localDataSource, never()).replaceAll(any())
    }

    @Test
    fun `getActiveTyphoons without any key still serves existing Room cache`() = runTest {
        val emptyRepo = repoWith(juheKey = "", qWeatherConfigured = false)
        val cached = listOf(Typhoon(id = "202609", name = "BAVI", englishName = "BAVI", status = "active"))
        whenever(localDataSource.getAll()).thenReturn(cached)

        val feed = emptyRepo.getActiveTyphoons().getOrThrow()

        assertTrue(feed.fromCache)
        assertTrue(feed.staleReason is NoDataSourceConfiguredError)
        assertEquals(cached, feed.typhoons)
    }

    @Test
    fun `getActiveTyphoons with only Juhe configured reports typed Juhe failure`() = runTest {
        val juheOnly = repoWith(juheKey = juheKey, qWeatherConfigured = false)
        whenever(juheApi.getActiveTyphoons(juheKey)).thenReturn(
            JuheActiveListResponse(reason = "错误的请求KEY", errorCode = 10001, result = null)
        )
        whenever(localDataSource.getAll()).thenReturn(emptyList())

        val error = juheOnly.getActiveTyphoons().exceptionOrNull()

        assertTrue(error is DataSourcesFailedError)
        val failures = (error as DataSourcesFailedError).failures
        assertEquals(1, failures.size)
        assertEquals(DataSource.Juhe, failures.single().source)
        assertEquals(SourceFailureKind.InvalidKey, failures.single().kind)
        assertEquals("10001", failures.single().code)
        // Unconfigured QWeather is skipped, not reported as a failure.
        verify(qWeatherApi, never()).getStormList(any(), any())
    }

    @Test
    fun `getActiveTyphoons maps quota and network failures per source`() = runTest {
        whenever(juheApi.getActiveTyphoons(juheKey)).thenReturn(
            JuheActiveListResponse(reason = "超过每日可允许请求次数", errorCode = 10012, result = null)
        )
        whenever(qWeatherApi.getStormList(any(), any())).thenThrow(RuntimeException("timeout"))
        whenever(localDataSource.getAll()).thenReturn(emptyList())

        val error = repository.getActiveTyphoons().exceptionOrNull() as DataSourcesFailedError

        assertEquals(
            listOf(
                DataSource.Juhe to SourceFailureKind.QuotaExceeded,
                DataSource.QWeather to SourceFailureKind.Network
            ),
            error.failures.map { it.source to it.kind }
        )
    }

    @Test
    fun `typed error messages contain no CJK text`() = runTest {
        whenever(localDataSource.getAll()).thenReturn(emptyList())
        val noSource = repoWith(juheKey = "", qWeatherConfigured = false)
            .getActiveTyphoons().exceptionOrNull()!!
        whenever(juheApi.getActiveTyphoons(any())).thenThrow(RuntimeException("down"))
        whenever(qWeatherApi.getStormList(any(), any())).thenThrow(RuntimeException("down"))
        val failed = repository.getActiveTyphoons().exceptionOrNull()!!

        val cjk = Regex("[\\u4e00-\\u9fff]")
        assertFalse(noSource.message.orEmpty().contains(cjk))
        assertFalse(failed.message.orEmpty().contains(cjk))
    }

    @Test
    fun `getTyphoonDetail without any key returns typed error and no network`() = runTest {
        val emptyRepo = repoWith(juheKey = "", qWeatherConfigured = false)

        val result = emptyRepo.getTyphoonDetail("202609")

        assertTrue(result.exceptionOrNull() is NoDataSourceConfiguredError)
        verify(juheApi, never()).getTyphoonDetail(any(), any())
        verify(qWeatherApi, never()).getStormTrack(any())
    }

    @Test
    fun `getActiveTyphoons falls back when Juhe key invalid 10001`() = runTest {
        whenever(juheApi.getActiveTyphoons(juheKey)).thenReturn(
            JuheActiveListResponse(reason = "错误的请求KEY", errorCode = 10001, result = null)
        )
        whenever(qWeatherApi.getStormList(any(), any())).thenReturn(
            QWeatherStormListResponse(code = "200", storm = emptyList())
        )

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
        val reason = feed.staleReason as DataSourcesFailedError
        assertEquals(
            listOf(SourceFailureKind.Network, SourceFailureKind.Network),
            reason.failures.map { it.kind }
        )
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

    private fun juheDetail(tfid: String, vararg times: String) = JuheDetailResponse(
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
        assertNull(feed.staleReason)
        assertEquals(nowMs - 9 * listMinute, feed.fetchedAtEpochMs)
        assertEquals(1, feed.typhoons.size)
        verify(juheApi, never()).getActiveTyphoons(any())
        verify(qWeatherApi, never()).getStormList(any(), any())
    }

    @Test
    fun `stale list after 10 minutes fetches and records fetch time`() = runTest {
        syncStore.lastListFetchAtMs = nowMs - 11 * listMinute
        whenever(juheApi.getActiveTyphoons(juheKey)).thenReturn(
            JuheActiveListResponse(reason = "success", errorCode = 0, result = JuheActiveListResult(emptyList()))
        )

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
        whenever(juheApi.getActiveTyphoons(juheKey)).thenReturn(
            JuheActiveListResponse(reason = "success", errorCode = 0, result = JuheActiveListResult(emptyList()))
        )

        val feed = repository.getActiveTyphoons(forceRefresh = true).getOrThrow()

        assertFalse(feed.fromCache)
        verify(juheApi).getActiveTyphoons(juheKey)
    }

    @Test
    fun `detail is not refetched when list update time is unchanged`() = runTest {
        whenever(localDataSource.getAll()).thenReturn(
            listOf(fullCached("202609", "2026-07-10 14:00:00"), fullCached("202610", "2026-07-10 08:00:00"))
        )
        whenever(juheApi.getActiveTyphoons(juheKey)).thenReturn(
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
        )
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
        whenever(juheApi.getActiveTyphoons(juheKey)).thenReturn(
            JuheActiveListResponse(
                reason = "success",
                errorCode = 0,
                result = JuheActiveListResult(listOf(juheInfo("202609", "2026-07-10 14:00:00")))
            )
        )
        whenever(juheApi.getTyphoonDetail(eq(juheKey), eq("202609")))
            .thenReturn(juheDetail("202609", "2026-07-10 08:00:00", "2026-07-10 14:00:00"))

        repository.getActiveTyphoons().getOrThrow()

        verify(juheApi).getTyphoonDetail(juheKey, "202609")
    }

    @Test
    fun `remote storm without observation for over 24h is reported dissipated`() = runTest {
        whenever(juheApi.getActiveTyphoons(juheKey)).thenReturn(
            JuheActiveListResponse(
                reason = "success",
                errorCode = 0,
                result = JuheActiveListResult(
                    listOf(juheInfo("202609", "2026-07-10 14:00:00"), juheInfo("2026D07", "2026-07-09 14:00:00"))
                )
            )
        )
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
    fun `remote failure falls back to cache with stale reason and last fetch time`() = runTest {
        val fetchedAt = nowMs - 3 * 60 * listMinute
        syncStore.lastListFetchAtMs = fetchedAt
        whenever(juheApi.getActiveTyphoons(any())).thenThrow(RuntimeException("network down"))
        whenever(qWeatherApi.getStormList(any(), any())).thenThrow(RuntimeException("network down"))
        whenever(localDataSource.getAll()).thenReturn(listOf(fullCached("202609", "2026-07-10 14:00:00")))

        val feed = repository.getActiveTyphoons().getOrThrow()

        assertTrue(feed.fromCache)
        assertTrue(feed.staleReason is DataSourcesFailedError)
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

    // endregion

    @Test
    fun `retry after suppresses another refresh until it expires`() = runTest {
        val started = nowMs
        val limited = relayHttpException(503, retryAfter = "3600")
        whenever(juheApi.getActiveTyphoons(any())).thenThrow(limited)
        whenever(qWeatherApi.getStormList(any(), any())).thenThrow(limited)
        whenever(localDataSource.getAll()).thenReturn(emptyList())

        val first = repository.getActiveTyphoons(forceRefresh = true)
        val failures = (first.exceptionOrNull() as DataSourcesFailedError).failures
        assertEquals(
            listOf(SourceFailureKind.RelayDailyQuota, SourceFailureKind.RelayDailyQuota),
            failures.map { it.kind }
        )
        assertEquals(started + 3_600_000L, failures[0].retryAfterEpochMs)
        assertEquals("503", syncStore.relayThrottleHttpCode(DataSource.Juhe))

        // Past the 60s force-refresh floor, still inside Retry-After: no second socket.
        nowMs = started + 90_000L
        repository.getActiveTyphoons(forceRefresh = true)
        verify(juheApi, times(1)).getActiveTyphoons(any())
        verify(qWeatherApi, times(1)).getStormList(any(), any())

        whenever(juheApi.getActiveTyphoons(any())).thenReturn(
            JuheActiveListResponse(
                reason = "success",
                errorCode = 0,
                result = JuheActiveListResult(emptyList())
            )
        )
        nowMs = started + 3_600_000L + 1
        assertTrue(repository.getActiveTyphoons(forceRefresh = true).isSuccess)
        verify(juheApi, times(2)).getActiveTyphoons(any())
    }

    @Test
    fun `juhe retry after does not block qweather`() = runTest {
        whenever(juheApi.getActiveTyphoons(any())).thenThrow(relayHttpException(429, retryAfter = "600"))
        whenever(qWeatherApi.getStormList(any(), any())).thenReturn(QWeatherStormListResponse(code = "200"))

        repository.getActiveTyphoons(forceRefresh = true).getOrThrow()
        nowMs += 61_000L
        repository.getActiveTyphoons(forceRefresh = true).getOrThrow()

        verify(juheApi, times(1)).getActiveTyphoons(any())
        verify(qWeatherApi, times(2)).getStormList(any(), any())
    }

    @Test
    fun `x relay stale is cached data not a live fetch`() = runTest {
        relayHeaders.record(
            encodedPath = "/v1/juhe/${RelayHeaders.JUHE_ACTIVE_SUFFIX}",
            stale = true,
            retryAfter = null
        )
        whenever(juheApi.getActiveTyphoons(juheKey)).thenReturn(
            JuheActiveListResponse(
                reason = "success",
                errorCode = 0,
                result = JuheActiveListResult(
                    data = listOf(JuheActiveTyphoon(tfid = "202609", name = "巴威", enname = "BAVI"))
                )
            )
        )
        whenever(juheApi.getTyphoonDetail(any(), any())).thenReturn(
            JuheDetailResponse(reason = "missing", errorCode = 1, result = null)
        )

        val feed = repository.getActiveTyphoons().getOrThrow()

        assertTrue(feed.fromCache)
        assertNull(feed.staleReason)
        assertEquals("巴威", feed.typhoons.single().name)
        verify(qWeatherApi, never()).getStormList(any(), any())
    }

    @Test
    fun `qweather track and forecast stay within three in flight`() = runTest {
        stubJuheListDown()
        val ids = listOf("NP_1", "NP_2", "NP_3", "NP_4")
        whenever(qWeatherApi.getStormList(any(), any())).thenReturn(
            QWeatherStormListResponse(code = "200", storm = ids.map { activeStorm(it) })
        )
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val calls = AtomicInteger(0)
        suspend fun hold() {
            val now = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { current -> maxOf(current, now) }
            calls.incrementAndGet()
            try {
                delay(30)
            } finally {
                inFlight.decrementAndGet()
            }
        }
        whenever(qWeatherApi.getStormTrack(any())).doSuspendableAnswer {
            hold()
            qTrack()
        }
        whenever(qWeatherApi.getStormForecast(any())).doSuspendableAnswer {
            hold()
            QWeatherStormForecastResponse(code = "200")
        }

        val feed = repository.getActiveTyphoons().getOrThrow()

        assertEquals(ids.size, feed.typhoons.size)
        assertTrue(feed.typhoons.all { it.points.isNotEmpty() })
        assertEquals(ids.size * 2, calls.get())
        assertEquals(DefaultTyphoonRepository.QWEATHER_MAX_IN_FLIGHT, maxInFlight.get())
    }

    @Test
    fun `one qweather storm failure does not drop the others`() = runTest {
        stubJuheListDown()
        val ids = listOf("NP_OK1", "NP_BAD", "NP_OK2")
        whenever(qWeatherApi.getStormList(any(), any())).thenReturn(
            QWeatherStormListResponse(code = "200", storm = ids.map { activeStorm(it) })
        )
        whenever(qWeatherApi.getStormTrack(any())).doSuspendableAnswer { invocation ->
            val id = invocation.arguments[0] as String
            if (id == "NP_BAD") throw IllegalStateException("track down")
            delay(10)
            qTrack()
        }
        whenever(qWeatherApi.getStormForecast(any())).thenReturn(QWeatherStormForecastResponse(code = "200"))

        val feed = repository.getActiveTyphoons().getOrThrow()

        assertEquals(ids.toSet(), feed.typhoons.map { it.id }.toSet())
        assertTrue(feed.typhoons.single { it.id == "NP_BAD" }.points.isEmpty())
        assertTrue(feed.typhoons.filter { it.id != "NP_BAD" }.all { it.points.isNotEmpty() })
        assertNull(syncStore.relayNotBeforeEpochMs(DataSource.QWeather))
        verify(qWeatherApi, times(ids.size)).getStormTrack(any())
        verify(qWeatherApi, times(ids.size)).getStormForecast(any())
    }

    @Test
    fun `qweather 429 records retry after and does not fan out`() = runTest {
        assertRelayLimitStopsFanOut(code = 429, kind = SourceFailureKind.RelayBusy)
    }

    @Test
    fun `qweather 503 records retry after and does not fan out`() = runTest {
        assertRelayLimitStopsFanOut(code = 503, kind = SourceFailureKind.RelayDailyQuota)
    }

    @Test
    fun `qweather track stale and retry after reuse the list handling`() = runTest {
        stubJuheListDown()
        whenever(qWeatherApi.getStormList(any(), any())).thenReturn(
            QWeatherStormListResponse(code = "200", storm = listOf(activeStorm("NP_1")))
        )
        whenever(qWeatherApi.getStormTrack(any())).doSuspendableAnswer {
            relayHeaders.record(
                encodedPath = "/v1/qweather/${RelayHeaders.QWEATHER_TRACK_SUFFIX}",
                stale = true,
                retryAfter = "45"
            )
            qTrack()
        }
        whenever(qWeatherApi.getStormForecast(any())).thenReturn(QWeatherStormForecastResponse(code = "200"))

        val feed = repository.getActiveTyphoons().getOrThrow()

        assertTrue(feed.fromCache)
        assertNull(feed.staleReason)
        assertEquals(nowMs + 45_000L, syncStore.relayNotBeforeEpochMs(DataSource.QWeather))
        assertEquals("NP_1", feed.typhoons.single().id)
    }

    @Test
    fun `qweather with no active storms makes no track or forecast requests`() = runTest {
        stubJuheListDown()
        whenever(qWeatherApi.getStormList(any(), any())).thenReturn(
            QWeatherStormListResponse(
                code = "200",
                storm = listOf(QWeatherStormInfo(id = "NP_OLD", name = "old", isActive = "0"))
            )
        )

        val feed = repository.getActiveTyphoons().getOrThrow()

        assertTrue(feed.typhoons.isEmpty())
        assertFalse(feed.fromCache)
        verify(qWeatherApi, never()).getStormTrack(any())
        verify(qWeatherApi, never()).getStormForecast(any())
    }

    private suspend fun assertRelayLimitStopsFanOut(code: Int, kind: SourceFailureKind) {
        stubJuheListDown()
        val ids = listOf("NP_1", "NP_2", "NP_3", "NP_4")
        whenever(qWeatherApi.getStormList(any(), any())).thenReturn(
            QWeatherStormListResponse(code = "200", storm = ids.map { activeStorm(it) })
        )
        val calls = AtomicInteger(0)
        whenever(qWeatherApi.getStormTrack(any())).doSuspendableAnswer {
            calls.incrementAndGet()
            throw relayHttpException(code, retryAfter = "600")
        }
        whenever(qWeatherApi.getStormForecast(any())).doSuspendableAnswer {
            calls.incrementAndGet()
            throw relayHttpException(code, retryAfter = "600")
        }

        val feed = repository.getActiveTyphoons().getOrThrow()

        assertEquals(ids.size, feed.typhoons.size)
        assertTrue(calls.get() in 1..DefaultTyphoonRepository.QWEATHER_MAX_IN_FLIGHT)
        assertEquals(nowMs + 600_000L, syncStore.relayNotBeforeEpochMs(DataSource.QWeather))
        assertEquals(code.toString(), syncStore.relayThrottleHttpCode(DataSource.QWeather))

        whenever(localDataSource.getAll()).thenReturn(emptyList())
        nowMs += 61_000L
        val again = repository.getActiveTyphoons(forceRefresh = true).exceptionOrNull() as DataSourcesFailedError
        assertEquals(kind, again.failures.single { it.source == DataSource.QWeather }.kind)
        verify(qWeatherApi, times(1)).getStormList(any(), any())
    }

    private suspend fun stubJuheListDown() {
        whenever(juheApi.getActiveTyphoons(any())).thenReturn(
            JuheActiveListResponse(reason = "limit reached", errorCode = 10012, result = null)
        )
    }

    private fun activeStorm(id: String) = QWeatherStormInfo(
        id = id,
        name = id,
        basin = "NP",
        year = "2026",
        isActive = "1"
    )

    private fun qTrack() = QWeatherStormTrackResponse(
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
}
