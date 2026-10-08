package seamain.org.typhoonEye.data.api

import okhttp3.logging.HttpLoggingInterceptor

/**
 * Masks credentials in HTTP log lines. Debug builds log at BASIC, which prints full URLs
 * (Juhe passes its key as `?key=`), so every line goes through [redact] before it is logged.
 */
object LogRedaction {
    const val MASK = "****"

    /** Header names whose values are also masked by HttpLoggingInterceptor.redactHeader. */
    val SENSITIVE_HEADERS = listOf(QWeatherAuthInterceptor.API_KEY_HEADER, "Authorization")

    private val queryParam = Regex(
        """(?i)([?&;](?:key|apikey|api_key|api-key|appkey|token|access_token)=)[^&#\s]*"""
    )
    private val headerLine = Regex("""(?i)\b(x-qw-api-key|authorization)(\s*[:=]\s*)\S.*""")

    fun redact(message: String): String =
        headerLine.replace(queryParam.replace(message) { "${it.groupValues[1]}$MASK" }) {
            "${it.groupValues[1]}${it.groupValues[2]}$MASK"
        }
}

class RedactingHttpLogger(
    private val delegate: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger.DEFAULT
) : HttpLoggingInterceptor.Logger {
    override fun log(message: String) = delegate.log(LogRedaction.redact(message))
}

/** HttpLoggingInterceptor that never prints API keys (query params or auth headers). */
fun redactingLoggingInterceptor(
    level: HttpLoggingInterceptor.Level,
    logger: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger.DEFAULT
): HttpLoggingInterceptor =
    HttpLoggingInterceptor(RedactingHttpLogger(logger)).apply {
        LogRedaction.SENSITIVE_HEADERS.forEach(::redactHeader)
        this.level = level
    }
