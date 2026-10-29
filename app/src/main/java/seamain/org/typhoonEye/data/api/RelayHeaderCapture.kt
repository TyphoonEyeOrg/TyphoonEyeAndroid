package seamain.org.typhoonEye.data.api

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Latest relay headers seen for each request path.
 *
 * Retrofit returns response bodies, not headers. The [RelayHeaderInterceptor] records
 * them here; the repository reads the snapshot for the call it just made. Unit tests
 * can [record] a snapshot directly because mocked APIs never touch OkHttp.
 *
 * Paths are distinct (`…/typhoon/active` vs `…/storm-list`), so a detail call cannot
 * overwrite the list snapshot the repository is about to read.
 */
@Singleton
class RelayHeaderCapture @Inject constructor() {
    data class Snapshot(
        val stale: Boolean = false,
        val retryAfter: String? = null
    )

    private val byPath = ConcurrentHashMap<String, Snapshot>()

    fun record(encodedPath: String, stale: Boolean, retryAfter: String?) {
        byPath[encodedPath] = Snapshot(stale = stale, retryAfter = retryAfter)
    }

    /** Removes and returns the snapshot whose path ends with [suffix], if one was recorded. */
    fun consumeEndingWith(suffix: String): Snapshot? {
        val normalized = suffix.trim('/')
        val key = byPath.keys.firstOrNull { path -> path.trim('/').endsWith(normalized) } ?: return null
        return byPath.remove(key)
    }
}

/** Copies `Retry-After` and `X-Relay-Stale` off the raw response before Retrofit drops them. */
class RelayHeaderInterceptor(
    private val capture: RelayHeaderCapture
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        capture.record(
            encodedPath = chain.request().url.encodedPath,
            stale = RelayHeaders.isStale(response.header(RelayHeaders.STALE)),
            retryAfter = response.header(RelayHeaders.RETRY_AFTER)
        )
        return response
    }
}
