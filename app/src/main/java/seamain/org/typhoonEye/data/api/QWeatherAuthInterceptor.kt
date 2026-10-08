package seamain.org.typhoonEye.data.api

import okhttp3.Interceptor
import okhttp3.Response
import seamain.org.typhoonEye.data.util.JwtUtils
import java.io.IOException

/**
 * QWeather auth, as documented at https://dev.qweather.com/docs/configuration/authentication/:
 * 1) JWT `Authorization: Bearer <EdDSA token>` when KID, PROJECT_ID, DEVELOPER_ID and
 *    PRIVATE_KEY are all set (QWeather's recommended method; API KEY daily requests are
 *    limited from 2027-01-01);
 * 2) otherwise `X-QW-Api-Key: <apiKey>`.
 * Never both on one request: QWeather may reject mixed auth.
 *
 * Never crashes the OkHttp dispatcher on missing credentials — throws [IOException].
 */
class QWeatherAuthInterceptor(
    apiKey: String = "",
    kid: String = "",
    projectId: String = "",
    developerId: String = "",
    privateKeyPem: String = "",
    private val clock: () -> Long = System::currentTimeMillis
) : Interceptor {

    private val apiKey = apiKey.trim()
    private val kid = kid.trim()
    private val projectId = projectId.trim()
    private val developerId = developerId.trim()
    private val privateKeyPem = privateKeyPem.trim()

    private data class CachedJwt(val token: String, val expiresAtMs: Long, val fingerprint: String)

    @Volatile
    private var cached: CachedJwt? = null

    /** All four JWT parts are present; a partial set falls back to the API key. */
    val jwtConfigured: Boolean
        get() = kid.isNotEmpty() && projectId.isNotEmpty() && developerId.isNotEmpty() && privateKeyPem.isNotEmpty()

    val hasCredentials: Boolean
        get() = jwtConfigured || apiKey.isNotEmpty()

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!hasCredentials) {
            throw IOException(
                "和风天气凭证未配置。请在 local.properties 配置 QWEATHER_KID + QWEATHER_PROJECT_ID + " +
                    "QWEATHER_DEVELOPER_ID + QWEATHER_PRIVATE_KEY，或设置 QWEATHER_API_KEY（见 local.properties.example）"
            )
        }

        val builder = chain.request().newBuilder()
            .removeHeader("Authorization")
            .removeHeader("X-QW-Api-Key")
        if (jwtConfigured) {
            builder.header("Authorization", "Bearer ${currentJwt()}")
        } else {
            builder.header("X-QW-Api-Key", apiKey)
        }
        return chain.proceed(builder.build())
    }

    /** Cached until a minute before expiry; the cache is keyed on kid, sub, iss and the key. */
    internal fun currentJwt(): String {
        val now = clock()
        val fingerprint = "$kid|$projectId|$developerId|${privateKeyPem.hashCode()}"
        cached?.let { if (it.fingerprint == fingerprint && now < it.expiresAtMs - 60_000) return it.token }
        return try {
            val jwt = JwtUtils.generateQWeatherJwt(
                kid = kid,
                projectId = projectId,
                developerId = developerId,
                privateKeyPem = privateKeyPem,
                nowMs = now
            )
            // iat is now - 30 s, so the token expires TTL - 30 s from now.
            cached = CachedJwt(jwt, now + (JwtUtils.DEFAULT_TTL_SECONDS - 30) * 1000, fingerprint)
            jwt
        } catch (e: Exception) {
            // Message only: never echo key material.
            throw IOException("和风 JWT 生成失败: ${e.javaClass.simpleName}", e)
        }
    }
}
