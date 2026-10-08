package seamain.org.typhoonEye

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import seamain.org.typhoonEye.data.api.QWeatherAuthInterceptor
import seamain.org.typhoonEye.data.credentials.BuildTimeCredentials
import seamain.org.typhoonEye.data.credentials.DefaultDataSourceCredentials
import seamain.org.typhoonEye.data.credentials.QWeatherCredentials
import seamain.org.typhoonEye.data.credentials.QWeatherHost
import seamain.org.typhoonEye.data.credentials.UserDataSourceKeys
import java.io.IOException

/**
 * Key and API host are resolved per request: a key / host saved in Settings applies to the
 * very next call on the same OkHttp client (no restart, no rebuilt Retrofit).
 */
class QWeatherAuthInterceptorTest {

    private lateinit var server: MockWebServer
    private var user = UserDataSourceKeys()
    private lateinit var client: OkHttpClient

    /** Retrofit base URL placeholder; the interceptor must redirect away from it. */
    private val placeholder = "${QWeatherHost.DEFAULT}/v7/tropical/storm-list?basin=NP&year=2026"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // MockWebServer is plain HTTP, which QWeatherHost.normalize rejects by design, so the
        // test resolves credentials itself (same per-call lambda the provider supplies).
        client = OkHttpClient.Builder()
            .addInterceptor(QWeatherAuthInterceptor { resolve(user) })
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun serverHost() = "http://${server.hostName}:${server.port}"

    /** Mirrors DefaultDataSourceCredentials for a user key, minus the HTTPS-only host check. */
    private fun resolve(keys: UserDataSourceKeys) = QWeatherCredentials(
        apiKey = keys.qWeatherApiKey.trim(),
        host = keys.qWeatherHost.ifBlank { QWeatherHost.DEFAULT }
    )

    private fun call() = client.newCall(Request.Builder().url(placeholder).build()).execute().use { it.code }

    @Test
    fun readsKeyAndHostPerRequest() {
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))

        user = UserDataSourceKeys(qWeatherApiKey = "first-key", qWeatherHost = serverHost())
        assertEquals(200, call())
        val first = server.takeRequest()
        assertEquals("first-key", first.getHeader(QWeatherAuthInterceptor.API_KEY_HEADER))
        assertEquals("/v7/tropical/storm-list?basin=NP&year=2026", first.path)
        assertNull(first.getHeader("Authorization"))

        // Same client, new key from Settings → used immediately.
        user = UserDataSourceKeys(qWeatherApiKey = "second-key", qWeatherHost = serverHost())
        assertEquals(200, call())
        assertEquals("second-key", server.takeRequest().getHeader(QWeatherAuthInterceptor.API_KEY_HEADER))
    }

    @Test
    fun noCredentials_throwsIOException_withoutHittingNetwork() {
        user = UserDataSourceKeys(qWeatherHost = serverHost())
        val error = runCatching { call() }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun providerWiring_userKeyReachesInterceptor() {
        // Real provider: key from Settings; host is https-only so only the header is checked here.
        var keys = UserDataSourceKeys(qWeatherApiKey = "via-provider")
        val provider = DefaultDataSourceCredentials({ keys }, BuildTimeCredentials())
        var seenKey: String? = null
        var seenHost: String? = null
        val probe = OkHttpClient.Builder()
            .addInterceptor(QWeatherAuthInterceptor(provider))
            .addInterceptor { chain ->
                seenKey = chain.request().header(QWeatherAuthInterceptor.API_KEY_HEADER)
                seenHost = chain.request().url.host
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("OK").body("".toResponseBody()).build()
            }
            .build()
        probe.newCall(Request.Builder().url(placeholder).build()).execute().close()
        assertEquals("via-provider", seenKey)
        assertEquals("devapi.qweather.com", seenHost)

        keys = UserDataSourceKeys(qWeatherApiKey = "next", qWeatherHost = "abc.re.qweatherapi.com")
        probe.newCall(Request.Builder().url(placeholder).build()).execute().close()
        assertEquals("next", seenKey)
        assertEquals("abc.re.qweatherapi.com", seenHost)
    }

    @Test
    fun keyRemoved_stopsSendingRequests() {
        server.enqueue(MockResponse().setBody("{}"))
        user = UserDataSourceKeys(qWeatherApiKey = "k", qWeatherHost = serverHost())
        assertEquals(200, call())

        user = UserDataSourceKeys()
        assertTrue(runCatching { call() }.exceptionOrNull() is IOException)
        assertEquals(1, server.requestCount)
    }
}
