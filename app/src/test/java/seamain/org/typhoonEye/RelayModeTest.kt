package seamain.org.typhoonEye

import android.content.Context
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import retrofit2.HttpException
import seamain.org.typhoonEye.data.api.DataSourceConfig
import seamain.org.typhoonEye.data.api.JuheTyphoonApi
import seamain.org.typhoonEye.data.api.QWeatherAuthInterceptor
import seamain.org.typhoonEye.data.api.QWeatherTyphoonApi
import seamain.org.typhoonEye.data.api.QWeatherWarningApi
import seamain.org.typhoonEye.data.api.RelayAlertsApi
import seamain.org.typhoonEye.data.local.TyphoonLocalDataSource
import seamain.org.typhoonEye.data.model.JuheActiveListResponse
import seamain.org.typhoonEye.data.model.JuheActiveListResult
import seamain.org.typhoonEye.data.model.QWeatherAlert
import seamain.org.typhoonEye.data.model.QWeatherAlertEventType
import seamain.org.typhoonEye.data.model.QWeatherStormListResponse
import seamain.org.typhoonEye.data.model.RelayAlertPoint
import seamain.org.typhoonEye.data.model.RelayAlertsResponse
import seamain.org.typhoonEye.data.repository.DefaultTyphoonRepository
import seamain.org.typhoonEye.data.repository.DefaultWarningRepository
import seamain.org.typhoonEye.data.sync.FeedSyncStore
import seamain.org.typhoonEye.di.NetworkModule
import seamain.org.typhoonEye.domain.model.DataSource
import seamain.org.typhoonEye.domain.model.DataSourcesFailedError
import seamain.org.typhoonEye.domain.model.SourceFailureKind
import seamain.org.typhoonEye.domain.model.UserLocation
import seamain.org.typhoonEye.domain.util.WallClock
import java.io.IOException

/**
 * F-Droid build: everything goes through TyphoonEye's relay, the app sends no key and no
 * location. These tests run in both flavors (the mode is a constructor argument).
 */
class RelayModeTest {

    private val relay = "https://te-relay.seamain.org/"
    private val relayConfig = DataSourceConfig(relayBaseUrl = relay)

    // region DataSourceConfig

    @Test
    fun relayConfig_alwaysHasBothSources_andNeverAKey() {
        val config = DataSourceConfig(relayBaseUrl = relay, juheKey = "LEAKED", qWeatherDirectConfigured = false)
        assertTrue(config.viaRelay)
        assertTrue(config.juheEnabled)
        assertTrue(config.qWeatherEnabled)
        assertTrue(config.hasAnyDataSource)
        assertNull(config.juheKeyParam)
        assertEquals("https://te-relay.seamain.org/v1/juhe/", config.juheBaseUrl)
        assertEquals("https://te-relay.seamain.org/v1/qweather/", config.qWeatherRelayBaseUrl)
        assertFalse(config.toString().contains("LEAKED"))
    }

    @Test
    fun directConfig_keepsKeysAndHosts() {
        val config = DataSourceConfig(juheKey = " k ", qWeatherDirectConfigured = false)
        assertFalse(config.viaRelay)
        assertEquals("k", config.juheKeyParam)
        assertTrue(config.juheEnabled)
        assertFalse(config.qWeatherEnabled)
        assertEquals(DataSourceConfig.JUHE_DIRECT_BASE_URL, config.juheBaseUrl)
        assertNull(config.qWeatherRelayBaseUrl)
        assertFalse(config.toString().contains("k)"))
        assertFalse(DataSourceConfig().hasAnyDataSource)
    }

    @Test
    fun relayUrl_isNormalised_andMustBeHttps() {
        assertEquals(relay, DataSourceConfig.normalizeRelayBaseUrl("https://te-relay.seamain.org"))
        assertEquals("", DataSourceConfig.normalizeRelayBaseUrl("  "))
        assertThrows(IllegalArgumentException::class.java) {
            DataSourceConfig.normalizeRelayBaseUrl("http://te-relay.seamain.org/")
        }
    }

    // endregion

    // region Wire format: what the app actually sends to the relay

    private class Capture : Interceptor {
        val requests = mutableListOf<Request>()
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += request
            val body = when {
                request.url.encodedPath.endsWith("/v1/alerts") ->
                    """{"version":1,"updatedAtMs":1,"points":[]}"""
                request.url.encodedPath.contains("/v1/juhe/") ->
                    """{"reason":"success","error_code":0,"result":null}"""
                else -> """{"code":"200","storm":[],"track":[],"forecast":[]}"""
            }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    @Test
    fun relayRequests_haveFixedPaths_noKey_noAuthHeader_noLocation() = runTest {
        val capture = Capture()
        val json = NetworkModule.provideJson()
        val logging = HttpLoggingInterceptor()
        // A QWeather key is present on purpose: relay mode must not attach it.
        val auth = QWeatherAuthInterceptor(apiKey = "QW-SECRET")
        val qClient = NetworkModule.provideQWeatherOkHttp(auth, relayConfig, logging)
            .newBuilder().addInterceptor(capture).build()
        val plainClient = OkHttpClient.Builder().addInterceptor(capture).build()

        val juhe = NetworkModule.provideJuheTyphoonApi(
            NetworkModule.provideJuheRetrofit(plainClient, relayConfig, json)
        )
        val qWeather = NetworkModule.provideQWeatherTyphoonApi(
            NetworkModule.provideQWeatherRetrofit(qClient, relayConfig, json)
        )
        val alerts = NetworkModule.provideRelayAlertsApi(plainClient, relayConfig, json)

        juhe.getActiveTyphoons(relayConfig.juheKeyParam)
        juhe.getTyphoonDetail(relayConfig.juheKeyParam, "202609")
        qWeather.getStormList(basin = "NP", year = "2026")
        qWeather.getStormTrack("NP_2609")
        qWeather.getStormForecast("NP_2609")
        alerts.getAlerts()

        assertEquals(
            listOf(
                "https://te-relay.seamain.org/v1/juhe/fapigw/typhoon/active",
                "https://te-relay.seamain.org/v1/juhe/fapigw/typhoon/detail?tfid=202609",
                "https://te-relay.seamain.org/v1/qweather/v7/tropical/storm-list?basin=NP&year=2026",
                "https://te-relay.seamain.org/v1/qweather/v7/tropical/storm-track?stormid=NP_2609",
                "https://te-relay.seamain.org/v1/qweather/v7/tropical/storm-forecast?stormid=NP_2609",
                "https://te-relay.seamain.org/v1/alerts"
            ),
            capture.requests.map { it.url.toString() }
        )
        capture.requests.forEach { request ->
            assertNull(request.header(QWeatherAuthInterceptor.API_KEY_HEADER))
            assertNull(request.header("Authorization"))
            assertNull(request.url.queryParameter("key"))
            assertFalse(request.url.toString().contains("QW-SECRET"))
            assertFalse(request.url.encodedPath.contains("weatheralert"))
        }
    }

    // endregion

    // region Repository in relay mode

    private class FakeSyncStore(override var lastListFetchAtMs: Long? = null) : FeedSyncStore

    private fun relayRepo(juheApi: JuheTyphoonApi, qWeatherApi: QWeatherTyphoonApi): DefaultTyphoonRepository {
        val local: TyphoonLocalDataSource = mock()
        return DefaultTyphoonRepository(
            juheApi = juheApi,
            qWeatherApi = qWeatherApi,
            config = relayConfig,
            localDataSource = local,
            syncStore = FakeSyncStore(),
            clock = WallClock { 1_000_000L }
        )
    }

    @Test
    fun relayRepo_callsJuheWithoutKey() = runTest {
        val juheApi: JuheTyphoonApi = mock()
        val qWeatherApi: QWeatherTyphoonApi = mock()
        whenever(juheApi.getActiveTyphoons(anyOrNull())).thenReturn(
            JuheActiveListResponse(reason = "success", errorCode = 0, result = JuheActiveListResult(emptyList()))
        )
        val repo = relayRepo(juheApi, qWeatherApi)

        assertTrue(repo.hasAnyDataSource)
        val feed = repo.getActiveTyphoons().getOrThrow()

        assertFalse(feed.fromCache)
        verify(juheApi, times(1)).getActiveTyphoons(isNull())
        verify(qWeatherApi, never()).getStormList(any(), any())
    }

    @Test
    fun relayRepo_upstreamKeyRejection_isAServiceError_notTheUsersKey() = runTest {
        val juheApi: JuheTyphoonApi = mock()
        val qWeatherApi: QWeatherTyphoonApi = mock()
        whenever(juheApi.getActiveTyphoons(anyOrNull())).thenReturn(
            JuheActiveListResponse(reason = "错误的请求KEY", errorCode = 10001, result = null)
        )
        whenever(qWeatherApi.getStormList(any(), any())).thenReturn(QWeatherStormListResponse(code = "401"))

        val error = relayRepo(juheApi, qWeatherApi).getActiveTyphoons().exceptionOrNull()

        val failures = (error as DataSourcesFailedError).failures
        assertEquals(
            listOf(
                DataSource.Juhe to SourceFailureKind.ApiError,
                DataSource.QWeather to SourceFailureKind.ApiError
            ),
            failures.map { it.source to it.kind }
        )
    }

    @Test
    fun relayRepo_rateLimitAndOffline_areTyped() = runTest {
        val juheApi: JuheTyphoonApi = mock()
        val qWeatherApi: QWeatherTyphoonApi = mock()
        val tooMany = HttpException(
            retrofit2.Response.error<Any>(
                429,
                """{"error":"rate_limited"}""".toResponseBody("application/json".toMediaType())
            )
        )
        whenever(juheApi.getActiveTyphoons(anyOrNull())).thenAnswer { throw tooMany }
        whenever(qWeatherApi.getStormList(any(), any())).thenAnswer { throw IOException("offline") }

        val failures = (relayRepo(juheApi, qWeatherApi).getActiveTyphoons().exceptionOrNull()
            as DataSourcesFailedError).failures

        assertEquals(SourceFailureKind.QuotaExceeded, failures[0].kind)
        assertEquals("429", failures[0].code)
        assertEquals(SourceFailureKind.Network, failures[1].kind)
    }

    // endregion

    // region Alerts: shared list, filtered on the device

    private val now = 10_000_000_000L

    private fun alert(id: String, code: String = "1001", name: String = "台风") =
        QWeatherAlert(id = id, headline = "h$id", eventType = QWeatherAlertEventType(name = name, code = code))

    private val shenzhen = RelayAlertPoint("cn-shenzhen", "深圳", 22.54, 114.06, listOf(alert("sz"), alert("shared")))
    private val hongKong = RelayAlertPoint("hk", "香港", 22.30, 114.17, listOf(alert("shared")))
    private val shanghai = RelayAlertPoint(
        "cn-shanghai", "上海", 31.23, 121.47,
        listOf(alert("sh"), alert("rain", code = "1003", name = "暴雨"))
    )
    private val list = RelayAlertsResponse(updatedAtMs = now - 60_000, points = listOf(shenzhen, hongKong, shanghai))

    @Test
    fun nearbyRelayAlerts_withLocation_keepsOnlyNearbyPoints() {
        val user = UserLocation(22.55, 114.10) // Shenzhen
        val ids = DefaultWarningRepository.nearbyRelayAlerts(list, user, now).map { it.id }
        assertEquals(listOf("sz", "shared"), ids)
    }

    @Test
    fun nearbyRelayAlerts_withoutLocation_keepsAllTyphoonAlerts() {
        val ids = DefaultWarningRepository.nearbyRelayAlerts(list, null, now).map { it.id }
        assertEquals(listOf("sz", "shared", "sh"), ids)
    }

    @Test
    fun nearbyRelayAlerts_farFromEveryPoint_isEmpty() {
        val urumqi = UserLocation(43.83, 87.62)
        assertTrue(DefaultWarningRepository.nearbyRelayAlerts(list, urumqi, now).isEmpty())
    }

    @Test
    fun nearbyRelayAlerts_staleOrNeverRefreshedList_isIgnored() {
        val stale = list.copy(updatedAtMs = now - DefaultWarningRepository.RELAY_MAX_AGE_MS - 1)
        assertTrue(DefaultWarningRepository.nearbyRelayAlerts(stale, null, now).isEmpty())
        assertTrue(DefaultWarningRepository.nearbyRelayAlerts(list.copy(updatedAtMs = 0), null, now).isEmpty())
    }

    @Test
    fun warningRepo_relayMode_neverQueriesByLocation() = runTest {
        val warningApi: QWeatherWarningApi = mock()
        val relayApi: RelayAlertsApi = mock()
        whenever(relayApi.getAlerts()).thenReturn(list)
        val repo = DefaultWarningRepository(
            appContext = mock<Context>(),
            warningApi = warningApi,
            relayAlertsApi = relayApi,
            config = relayConfig,
            clock = WallClock { now }
        )

        val alerts = repo.fetchTyphoonAlerts(emptyList(), UserLocation(31.20, 121.50)).getOrThrow()

        assertEquals(listOf("sh"), alerts.map { it.id })
        verify(relayApi, times(1)).getAlerts()
        verify(warningApi, never()).getCurrentAlerts(any(), any(), any(), any())
    }

    @Test
    fun warningRepo_relayDown_returnsNoOfficialAlertsInsteadOfFailing() = runTest {
        val relayApi: RelayAlertsApi = mock()
        whenever(relayApi.getAlerts()).thenAnswer { throw IOException("offline") }
        val repo = DefaultWarningRepository(
            appContext = mock<Context>(),
            warningApi = mock(),
            relayAlertsApi = relayApi,
            config = relayConfig,
            clock = WallClock { now }
        )

        assertEquals(emptyList<Any>(), repo.fetchTyphoonAlerts(emptyList(), null).getOrThrow())
    }

    @Test
    fun warningRepo_directMode_neverCallsRelay() = runTest {
        val warningApi: QWeatherWarningApi = mock()
        val relayApi: RelayAlertsApi = mock()
        val repo = DefaultWarningRepository(
            appContext = mock<Context>(),
            warningApi = warningApi,
            relayAlertsApi = relayApi,
            config = DataSourceConfig(juheKey = "k"),
            clock = WallClock { now }
        )

        repo.fetchTyphoonAlerts(emptyList(), null).getOrThrow()

        verify(relayApi, never()).getAlerts()
        verify(warningApi, never()).getCurrentAlerts(any(), any(), any(), any())
    }

    // endregion
}
