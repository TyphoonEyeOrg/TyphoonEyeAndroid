package seamain.org.typhoonEye.domain.repository

import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.TyphoonFeed

/**
 * Single source of truth for typhoon list / detail.
 * Implementations may combine remote APIs with a local Room cache.
 */
interface TyphoonRepository {
    /**
     * Active list. Served from cache while it is younger than the list TTL (10 min);
     * [forceRefresh] (pull-to-refresh) shortens that to the minimum force interval (60 s).
     */
    suspend fun getActiveTyphoons(forceRefresh: Boolean = false): Result<TyphoonFeed>

    /** Cache-only read for instant first paint; never touches the network. */
    suspend fun getCachedFeed(): TyphoonFeed?

    suspend fun getTyphoonDetail(id: String): Result<Typhoon>
}
