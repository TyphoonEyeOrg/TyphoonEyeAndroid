package seamain.org.typhoonEye.data.sync

import android.content.Context

/** Remembers when the active list was last fetched from the network (survives process death). */
interface FeedSyncStore {
    var lastListFetchAtMs: Long?
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

    private companion object {
        const val PREFS_NAME = "typhoon_feed_sync"
        const val KEY_LAST_LIST_FETCH = "last_list_fetch_at_ms"
    }
}
