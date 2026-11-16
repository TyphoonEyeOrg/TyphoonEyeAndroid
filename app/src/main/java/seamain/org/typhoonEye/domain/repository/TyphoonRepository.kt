package seamain.org.typhoonEye.domain.repository

import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.TyphoonFeed

/**
 * Single source of truth for typhoon list / detail.
 * Implementations may combine remote APIs with a local Room cache.
 */
interface TyphoonRepository {
    /**
     * Active list. A non-forced read is served from cache while it is younger than the
     * list TTL (10 min). [forceRefresh] (pull-to-refresh) always attempts a network fetch.
     * An in-flight fetch is shared, so a repeated pull or the background worker does not
     * start a second one or turn into a cache read when the first fetch records its time.
     * A relay `Retry-After` still suppresses network calls until it expires, including
     * pull-to-refresh.
     */
    suspend fun getActiveTyphoons(forceRefresh: Boolean = false): Result<TyphoonFeed>

    /** Cache-only read for instant first paint; never touches the network. */
    suspend fun getCachedFeed(): TyphoonFeed?

    suspend fun getTyphoonDetail(id: String): Result<Typhoon>
}
