package seamain.org.typhoonEye.data.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import seamain.org.typhoonEye.data.credentials.DataSourceCredentials
import seamain.org.typhoonEye.data.credentials.QWeatherCredentials
import seamain.org.typhoonEye.data.util.JwtUtils
import java.io.IOException

/**
 * QWeather auth + host, resolved **per request** from [credentials] so a key / API host
 * entered in Settings applies immediately (no restart, no rebuilt Retrofit):
 * 1) Rewrites scheme/host/port of every request to the configured API host
 *    (new QWeather accounts use a per-account host; Retrofit's base URL is a placeholder).
 * 2) `X-QW-Api-Key: <apiKey>` (user key or build key).
 * 3) Build-time only: `Authorization: Bearer <EdDSA JWT>` when no API key exists.
 *
 * Never crashes the OkHttp dispatcher on missing credentials — throws [IOException].
 * Never logs credentials.
 */
class QWeatherAuthInterceptor(
    private val credentials: () -> QWeatherCredentials
) : Interceptor {

    constructor(provider: DataSourceCredentials) : this(provider::qWeather)

    private class CachedJwt(val identity: String, val token: String, val expiresAtMs: Long)

    @Volatile
    private var cachedJwt: CachedJwt? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val creds = credentials()
        if (!creds.isConfigured) {
            throw IOException(
                "QWeather credentials not configured (enter a key in Settings, or set " +
                    "QWEATHER_API_KEY at build time; see local.properties.example)"
            )
        }
        val target = creds.host.toHttpUrl()
        val original = chain.request()
        val url = original.url.newBuilder()
            .scheme(target.scheme)
            .host(target.host)
            .port(target.port)
            .build()
        val builder = original.newBuilder().url(url)
        if (creds.usesApiKey) {
            builder.header(API_KEY_HEADER, creds.apiKey)
        } else {
            builder.header("Authorization", "Bearer ${currentJwt(creds)}")
        }
        return chain.proceed(builder.build())
    }

    private fun currentJwt(creds: QWeatherCredentials): String {
        val now = System.currentTimeMillis()
        val identity = "${creds.kid}/${creds.projectId}"
        cachedJwt?.let { if (it.identity == identity && now < it.expiresAtMs - 60_000) return it.token }
        return try {
            val jwt = JwtUtils.generateQWeatherJwt(
                kid = creds.kid,
                projectId = creds.projectId,
                privateKeyPem = creds.privateKeyPem
            )
            // Match JwtUtils default TTL (900s), refresh early
            cachedJwt = CachedJwt(identity, jwt, now + 900_000)
            jwt
        } catch (e: Exception) {
            throw IOException("QWeather JWT generation failed: ${e.javaClass.simpleName}", e)
        }
    }

    companion object {
        const val API_KEY_HEADER = "X-QW-Api-Key"
    }
}
