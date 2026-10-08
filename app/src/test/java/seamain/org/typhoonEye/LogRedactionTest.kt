package seamain.org.typhoonEye

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import seamain.org.typhoonEye.data.api.LogRedaction
import seamain.org.typhoonEye.data.api.QWeatherAuthInterceptor
import seamain.org.typhoonEye.data.api.redactingLoggingInterceptor

class LogRedactionTest {

    @Test
    fun masksKeyQueryParamsAndAuthHeaders() {
        assertEquals(
            "--> GET https://apis.juhe.cn/typhoon/list?key=****&id=1",
            LogRedaction.redact("--> GET https://apis.juhe.cn/typhoon/list?key=abc123SECRET&id=1")
        )
        assertEquals(
            "GET https://h/x?location=1&KEY=****",
            LogRedaction.redact("GET https://h/x?location=1&KEY=SECRET")
        )
        assertEquals("X-QW-Api-Key: ****", LogRedaction.redact("X-QW-Api-Key: SECRET"))
        assertEquals("Authorization: ****", LogRedaction.redact("Authorization: Bearer SECRET"))
        // Unrelated params stay readable.
        assertEquals("GET https://h/x?monkey=1", LogRedaction.redact("GET https://h/x?monkey=1"))
    }

    @Test
    fun httpLoggingAtBasicAndHeaders_neverPrintsKeys() {
        listOf(HttpLoggingInterceptor.Level.BASIC, HttpLoggingInterceptor.Level.HEADERS).forEach { level ->
            val server = MockWebServer().apply { start() }
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().setBody("{}"))
            val lines = mutableListOf<String>()
            val client = OkHttpClient.Builder()
                .addInterceptor(QWeatherAuthInterceptor(apiKey = "QW-SECRET-123"))
                .addInterceptor(redactingLoggingInterceptor(level) { lines += it })
                .build()
            // Juhe-style key in the query + QWeather header key.
            client.newCall(Request.Builder().url(server.url("/typhoon/list?key=JUHE-SECRET-456")).build())
                .execute().close()
            client.newCall(Request.Builder().url(server.url("/v7/warning/now?location=1")).build())
                .execute().close()
            server.shutdown()

            val log = lines.joinToString("\n")
            assertTrue(log, log.contains("--> GET"))
            assertFalse(log, log.contains("JUHE-SECRET-456"))
            assertFalse(log, log.contains("QW-SECRET-123"))
            if (level == HttpLoggingInterceptor.Level.HEADERS) {
                assertTrue(log, log.contains("X-QW-Api-Key: ██") || log.contains("X-QW-Api-Key: ****"))
            }
        }
    }
}
