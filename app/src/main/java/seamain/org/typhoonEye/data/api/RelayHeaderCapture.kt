package seamain.org.typhoonEye.data.api

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
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
 * overwrite the list snapshot the repository is about to read. Several storms can
 * hit `storm-track` at once, so each path keeps a queue instead of one slot.
 */
@Singleton
class RelayHeaderCapture @Inject constructor() {
    data class Snapshot(
        val stale: Boolean = false,
        val retryAfter: String? = null
    )

    private val byPath = ConcurrentHashMap<String, ConcurrentLinkedQueue<Snapshot>>()

    fun record(encodedPath: String, stale: Boolean, retryAfter: String?) {
        byPath.computeIfAbsent(encodedPath) { ConcurrentLinkedQueue() }
            .add(Snapshot(stale = stale, retryAfter = retryAfter))
    }

    /** Removes and returns the oldest snapshot whose path ends with [suffix], if one was recorded. */
    fun consumeEndingWith(suffix: String): Snapshot? {
        val normalized = suffix.trim('/')
        for (key in byPath.keys) {
            if (!key.trim('/').endsWith(normalized)) continue
            val snap = byPath[key]?.poll() ?: continue
            return snap
        }
        return null
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
