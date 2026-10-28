package seamain.org.typhoonEye

import seamain.org.typhoonEye.data.sync.FeedSyncStore
import seamain.org.typhoonEye.domain.model.DataSource

/** In-memory [FeedSyncStore] for unit tests. */
class MemoryFeedSyncStore : FeedSyncStore {
    override var lastListFetchAtMs: Long? = null
    private val notBefore = mutableMapOf<DataSource, Long>()
    private val codes = mutableMapOf<DataSource, String?>()

    override fun relayNotBeforeEpochMs(source: DataSource): Long? = notBefore[source]

    override fun relayThrottleHttpCode(source: DataSource): String? = codes[source]

    override fun setRelayThrottle(source: DataSource, notBeforeEpochMs: Long, httpCode: String?) {
        notBefore[source] = notBeforeEpochMs
        codes[source] = httpCode
    }

    override fun clearRelayThrottle(source: DataSource) {
        notBefore.remove(source)
        codes.remove(source)
    }
}
