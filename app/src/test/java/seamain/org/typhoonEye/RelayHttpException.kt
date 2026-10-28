package seamain.org.typhoonEye

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException

/** HTTP error whose raw response can carry a relay `Retry-After` header. */
internal fun relayHttpException(code: Int, retryAfter: String? = null): HttpException {
    val body = """{"error":"relay"}""".toResponseBody("application/json".toMediaType())
    val raw = okhttp3.Response.Builder()
        .request(
            Request.Builder()
                .url("https://te-relay.seamain.org/v1/juhe/fapigw/typhoon/active")
                .build()
        )
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("error")
        .body(body)
        .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
        .build()
    return HttpException(retrofit2.Response.error<Any>(body, raw))
}
