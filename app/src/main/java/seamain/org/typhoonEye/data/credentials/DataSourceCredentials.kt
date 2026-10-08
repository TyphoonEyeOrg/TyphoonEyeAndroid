package seamain.org.typhoonEye.data.credentials

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Keys the user typed into Settings → "Custom data source keys".
 * Stored only in an on-device DataStore ([DataStoreUserKeyStore]); never uploaded or logged.
 */
data class UserDataSourceKeys(
    val qWeatherApiKey: String = "",
    val qWeatherHost: String = "",
    val juheKey: String = ""
) {
    val isEmpty: Boolean
        get() = qWeatherApiKey.isBlank() && qWeatherHost.isBlank() && juheKey.isBlank()

    fun trimmed(): UserDataSourceKeys = UserDataSourceKeys(
        qWeatherApiKey = qWeatherApiKey.trim(),
        qWeatherHost = qWeatherHost.trim(),
        juheKey = juheKey.trim()
    )

    /** Never print secrets, even by accident (string templates, crash reports). */
    override fun toString(): String =
        "UserDataSourceKeys(qWeatherApiKey=${redacted(qWeatherApiKey)}, " +
            "qWeatherHost=$qWeatherHost, juheKey=${redacted(juheKey)})"
}

/** Keys baked in at build time (BuildConfig). Empty on the F-Droid flavor. */
data class BuildTimeCredentials(
    val juheKey: String = "",
    val qWeatherApiKey: String = "",
    val qWeatherHost: String = "",
    val qWeatherKid: String = "",
    val qWeatherProjectId: String = "",
    val qWeatherPrivateKeyPem: String = ""
) {
    override fun toString(): String =
        "BuildTimeCredentials(juheKey=${redacted(juheKey)}, " +
            "qWeatherApiKey=${redacted(qWeatherApiKey)}, qWeatherHost=$qWeatherHost, " +
            "jwt=${qWeatherKid.isNotBlank()})"
}

/** QWeather credentials resolved for a single request. */
data class QWeatherCredentials(
    val apiKey: String = "",
    /** Normalised base URL (scheme + host [+ port]), see [QWeatherHost.normalize]. */
    val host: String = QWeatherHost.DEFAULT,
    val kid: String = "",
    val projectId: String = "",
    val privateKeyPem: String = ""
) {
    val usesApiKey: Boolean get() = apiKey.isNotBlank()
    val usesJwt: Boolean
        get() = !usesApiKey && kid.isNotBlank() && projectId.isNotBlank() && privateKeyPem.isNotBlank()
    val isConfigured: Boolean get() = usesApiKey || usesJwt

    override fun toString(): String =
        "QWeatherCredentials(apiKey=${redacted(apiKey)}, host=$host, jwt=$usesJwt)"
}

/**
 * Source of data-source credentials. Implementations must be cheap to call: callers read
 * them per request / per call so a key entered in Settings applies without an app restart.
 */
interface DataSourceCredentials {
    fun juheKey(): String
    fun qWeather(): QWeatherCredentials
}

val DataSourceCredentials.hasJuhe: Boolean get() = juheKey().isNotBlank()
val DataSourceCredentials.hasQWeather: Boolean get() = qWeather().isConfigured
val DataSourceCredentials.hasAnyDataSource: Boolean get() = hasJuhe || hasQWeather

/**
 * Precedence: user-entered key (Settings) → build-time key (BuildConfig) → nothing.
 *
 * The QWeather API host is paired with the key it belongs to: a user key uses the user's host
 * (falling back to the build/default host when left blank); the build key uses the build host.
 */
class DefaultDataSourceCredentials(
    private val userKeys: () -> UserDataSourceKeys,
    private val buildTime: BuildTimeCredentials
) : DataSourceCredentials {

    override fun juheKey(): String =
        userKeys().juheKey.trim().ifBlank { buildTime.juheKey.trim() }

    override fun qWeather(): QWeatherCredentials {
        val user = userKeys()
        val buildHost = QWeatherHost.normalize(buildTime.qWeatherHost) ?: QWeatherHost.DEFAULT
        val userKey = user.qWeatherApiKey.trim()
        return if (userKey.isNotEmpty()) {
            QWeatherCredentials(
                apiKey = userKey,
                host = QWeatherHost.normalize(user.qWeatherHost) ?: buildHost
            )
        } else {
            QWeatherCredentials(
                apiKey = buildTime.qWeatherApiKey.trim(),
                host = buildHost,
                kid = buildTime.qWeatherKid.trim(),
                projectId = buildTime.qWeatherProjectId.trim(),
                privateKeyPem = buildTime.qWeatherPrivateKeyPem
            )
        }
    }
}

object QWeatherHost {
    /** Legacy shared host; new QWeather accounts get a per-account "API Host" instead. */
    const val DEFAULT = "https://devapi.qweather.com"

    /**
     * Accepts `abc123.re.qweatherapi.com`, `https://abc123.re.qweatherapi.com/` etc. and returns
     * `https://host[:port]`, or null when blank / invalid. Only HTTPS is accepted: the API key
     * travels in a header, so `http://` (or any other scheme) is rejected.
     * Any path, query or credentials part is dropped.
     */
    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        if (!withScheme.startsWith("https://", ignoreCase = true)) return null
        val url = withScheme.toHttpUrlOrNull() ?: return null
        if (url.scheme != "https") return null
        if (url.host.isBlank() || '.' !in url.host && url.host != "localhost") return null
        val defaultPort = HttpUrl.defaultPort(url.scheme)
        return buildString {
            append(url.scheme).append("://").append(url.host)
            if (url.port != defaultPort) append(':').append(url.port)
        }
    }

    fun isValid(raw: String): Boolean = raw.isBlank() || normalize(raw) != null
}

internal fun redacted(secret: String): String =
    if (secret.isBlank()) "<empty>" else "<redacted>"
