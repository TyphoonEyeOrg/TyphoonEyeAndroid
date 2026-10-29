package seamain.org.typhoonEye.data.sync

import android.content.Context
import seamain.org.typhoonEye.domain.model.RelayLimitKind

/** Remembers when the active list was last fetched from the network (survives process death). */
interface FeedSyncStore {
    var lastListFetchAtMs: Long?

    /**
     * Epoch millis when a relay `Retry-After` elapses. While this is still in the
     * future, list refresh and the live-update worker must not call the network.
     */
    var relayRetryAfterUntilMs: Long?

    /** Which 429/503 produced [relayRetryAfterUntilMs], so the UI can keep the same copy. */
    var relayLimit: RelayLimitKind?
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

    override var relayRetryAfterUntilMs: Long?
        get() = prefs.getLong(KEY_RELAY_RETRY_AFTER_UNTIL, -1L).takeIf { it > 0L }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_RELAY_RETRY_AFTER_UNTIL)
                else putLong(KEY_RELAY_RETRY_AFTER_UNTIL, value)
            }.apply()
        }

    override var relayLimit: RelayLimitKind?
        get() = prefs.getString(KEY_RELAY_LIMIT, null)?.let { name ->
            runCatching { RelayLimitKind.valueOf(name) }.getOrNull()
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_RELAY_LIMIT) else putString(KEY_RELAY_LIMIT, value.name)
            }.apply()
        }

    private companion object {
        const val PREFS_NAME = "typhoon_feed_sync"
        const val KEY_LAST_LIST_FETCH = "last_list_fetch_at_ms"
        const val KEY_RELAY_RETRY_AFTER_UNTIL = "relay_retry_after_until_ms"
        const val KEY_RELAY_LIMIT = "relay_limit"
    }
}
