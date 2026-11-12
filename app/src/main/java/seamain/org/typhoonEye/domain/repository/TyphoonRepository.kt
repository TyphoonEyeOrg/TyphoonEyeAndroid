package seamain.org.typhoonEye.domain.repository

import seamain.org.typhoonEye.domain.history.HistoricalStormFeed
import seamain.org.typhoonEye.domain.history.HistoricalTrack
import seamain.org.typhoonEye.domain.model.HistoricalSourceUnavailableError
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
     * A relay `Retry-After` suppresses network calls to that source until it expires,
     * including pull-to-refresh.
     */
    suspend fun getActiveTyphoons(forceRefresh: Boolean = false): Result<TyphoonFeed>

    /** Cache-only read for instant first paint; never touches the network. */
    suspend fun getCachedFeed(): TyphoonFeed?

    suspend fun getTyphoonDetail(id: String): Result<Typhoon>

    /**
     * QWeather northwest Pacific list for one supported year (current or previous UTC+8 year).
     * Does not replace the active-typhoon cache. Default keeps fakes of the live API compiling.
     */
    suspend fun getHistoricalStorms(
        year: Int,
        forceRefresh: Boolean = false
    ): Result<HistoricalStormFeed> = Result.failure(HistoricalSourceUnavailableError())

    /**
     * QWeather track for one storm from that list. Forecast is not requested.
     * An empty track is a successful result with [HistoricalTrack.trackMissing], not a guessed path.
     */
    suspend fun getHistoricalTrack(id: String, name: String): Result<HistoricalTrack> =
        Result.failure(HistoricalSourceUnavailableError())
}
