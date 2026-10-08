package seamain.org.typhoonEye

import net.i2p.crypto.eddsa.KeyPairGenerator
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import seamain.org.typhoonEye.data.api.QWeatherAuthInterceptor
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64

class QWeatherAuthInterceptorTest {

    private val pem: String = KeyPairGenerator().apply { initialize(256, SecureRandom()) }.generateKeyPair().let {
        "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(it.private.encoded) + "\n-----END PRIVATE KEY-----"
    }

    private fun send(interceptor: QWeatherAuthInterceptor): Request {
        val request = Request.Builder().url("https://devapi.qweather.com/v7/tropical/storm-list?basin=NP&year=2026").build()
        val chain = mock<Interceptor.Chain> {
            on { request() } doReturn request
            on { proceed(any()) } doReturn Response.Builder()
                .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").build()
        }
        interceptor.intercept(chain)
        val sent = argumentCaptor<Request>()
        verify(chain).proceed(sent.capture())
        return sent.firstValue
    }

    private fun payload(bearer: String) =
        JSONObject(String(Base64.getUrlDecoder().decode(bearer.removePrefix("Bearer ").split(".")[1])))

    @Test
    fun fullJwtSet_usesJwtOnly_evenWithAnApiKey() {
        val sent = send(QWeatherAuthInterceptor("api-key", "kid", "project", "developer", pem))
        assertNull(sent.header("X-QW-Api-Key"))
        val auth = sent.header("Authorization")!!
        assertTrue(auth.startsWith("Bearer "))
        assertEquals("developer", payload(auth).getString("iss"))
        assertEquals("project", payload(auth).getString("sub"))
    }

    @Test
    fun missingDeveloperId_fallsBackToApiKeyOnly() {
        val interceptor = QWeatherAuthInterceptor("api-key", "kid", "project", "  ", pem)
        assertFalse(interceptor.jwtConfigured)
        val sent = send(interceptor)
        assertEquals("api-key", sent.header("X-QW-Api-Key"))
        assertNull(sent.header("Authorization"))
    }

    @Test
    fun partialJwtWithoutApiKey_isNotConfigured() {
        val interceptor = QWeatherAuthInterceptor(kid = "kid", projectId = "project", privateKeyPem = pem)
        assertFalse(interceptor.hasCredentials)
        assertThrows(IOException::class.java) { send(interceptor) }
    }

    @Test
    fun token_isCachedUntilAMinuteBeforeExpiry() {
        var now = 1_760_000_000_000L
        val interceptor = QWeatherAuthInterceptor("", "kid", "project", "developer", pem) { now }
        val first = interceptor.currentJwt()
        now += 13 * 60_000L // 13 min: token (valid 14.5 min from now) still has > 1 min left
        assertEquals(first, interceptor.currentJwt())
        now += 60_000L // 14 min: within the last minute, renewed
        assertNotEquals(first, interceptor.currentJwt())
    }

    @Test
    fun issComesFromTheConfiguredDeveloperId() {
        val now = 1_760_000_000_000L
        val a = QWeatherAuthInterceptor("", "kid", "project", "developer-a", pem) { now }.currentJwt()
        val b = QWeatherAuthInterceptor("", "kid", "project", "developer-b", pem) { now }.currentJwt()
        assertEquals("developer-a", payload(a).getString("iss"))
        assertEquals("developer-b", payload(b).getString("iss"))
    }

    @Test
    fun brokenKey_failsWithoutEchoingIt() {
        val interceptor = QWeatherAuthInterceptor("", "kid", "project", "developer", "-----BEGIN PRIVATE KEY-----\nnot-base64!!\n-----END PRIVATE KEY-----")
        val e = assertThrows(IOException::class.java) { send(interceptor) }
        assertFalse(e.message!!.contains("not-base64"))
    }
}
