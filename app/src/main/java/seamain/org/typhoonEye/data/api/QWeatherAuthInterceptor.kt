package seamain.org.typhoonEye.data.api

import okhttp3.Interceptor
import okhttp3.Response
import seamain.org.typhoonEye.data.util.JwtUtils
import java.io.IOException

/**
 * QWeather auth — matches Postman / official docs:
 * 1) Preferred simple path: `X-QW-Api-Key: <apiKey>`
 * 2) JWT: `Authorization: Bearer <EdDSA token>`
 *
 * Never crashes the OkHttp dispatcher on missing credentials — throws [IOException].
 */
class QWeatherAuthInterceptor(
    private val apiKey: String = "",
    private val kid: String = "",
    private val projectId: String = "",
    private val privateKeyPem: String = ""
) : Interceptor {

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var tokenExpiresAtMs: Long = 0L

    val hasCredentials: Boolean
        get() = apiKey.isNotBlank() || (kid.isNotBlank() && projectId.isNotBlank() && privateKeyPem.isNotBlank())

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!hasCredentials) {
            throw IOException(
                "QWeather credentials not configured. Set QWEATHER_API_KEY, or " +
                    "QWEATHER_KID + QWEATHER_PROJECT_ID + QWEATHER_PRIVATE_KEY (see local.properties.example)"
            )
        }

        val builder = chain.request().newBuilder()
        if (apiKey.isNotBlank()) {
            // Official API KEY header (also used in Postman as X-QW-Api-Key)
            builder.header("X-QW-Api-Key", apiKey)
        } else {
            builder.header("Authorization", "Bearer ${currentJwt()}")
        }
        return chain.proceed(builder.build())
    }

    private fun currentJwt(): String {
        val now = System.currentTimeMillis()
        val existing = cachedToken
        if (existing != null && now < tokenExpiresAtMs - 60_000) {
            return existing
        }
        return try {
            val jwt = JwtUtils.generateQWeatherJwt(
                kid = kid,
                projectId = projectId,
                privateKeyPem = privateKeyPem
            )
            cachedToken = jwt
            // Match JwtUtils default TTL (900s), refresh early
            tokenExpiresAtMs = now + 900_000
            jwt
        } catch (e: IllegalArgumentException) {
            throw IOException("QWeather JWT generation failed: ${e.message}", e)
        } catch (e: Exception) {
            throw IOException("QWeather JWT generation failed: ${e.message}", e)
        }
    }
}
