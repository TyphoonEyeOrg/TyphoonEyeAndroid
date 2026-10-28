package seamain.org.typhoonEye.data.sync

import android.content.Context
import seamain.org.typhoonEye.domain.model.DataSource

/**
 * Remembers when the active list was last fetched, and when the relay asked us
 * not to call a source again (`Retry-After`). Both survive process death so a
 * restarted worker does not hammer the relay.
 */
interface FeedSyncStore {
    var lastListFetchAtMs: Long?

    fun relayNotBeforeEpochMs(source: DataSource): Long?

    /** HTTP status that started the wait, when it was a relay 429 or 503. */
    fun relayThrottleHttpCode(source: DataSource): String?

    fun setRelayThrottle(source: DataSource, notBeforeEpochMs: Long, httpCode: String?)

    fun clearRelayThrottle(source: DataSource)
}

class SharedPrefsFeedSyncStore(context: Context) : FeedSyncStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var lastListFetchAtMs: Long?
        get() = prefs.getLong(KEY_LAST_LIST_FETCH, -1L).takeIf { it > 0L }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_LAST_LIST_FETCH) else putLong(KEY_LAST_LIST_FETCH, value)
            }.apply()
        }

    override fun relayNotBeforeEpochMs(source: DataSource): Long? =
        prefs.getLong(notBeforeKey(source), -1L).takeIf { it > 0L }

    override fun relayThrottleHttpCode(source: DataSource): String? =
        prefs.getString(codeKey(source), null)

    override fun setRelayThrottle(source: DataSource, notBeforeEpochMs: Long, httpCode: String?) {
        prefs.edit()
            .putLong(notBeforeKey(source), notBeforeEpochMs)
            .putString(codeKey(source), httpCode)
            .apply()
    }

    override fun clearRelayThrottle(source: DataSource) {
        prefs.edit()
            .remove(notBeforeKey(source))
            .remove(codeKey(source))
            .apply()
    }

    private fun notBeforeKey(source: DataSource) = "relay_not_before_${source.name}"

    private fun codeKey(source: DataSource) = "relay_throttle_code_${source.name}"

    private companion object {
        const val PREFS_NAME = "typhoon_feed_sync"
        const val KEY_LAST_LIST_FETCH = "last_list_fetch_at_ms"
    }
}
