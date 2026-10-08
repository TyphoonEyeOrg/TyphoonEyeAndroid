package seamain.org.typhoonEye.data.credentials

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Persists [UserDataSourceKeys] on the device. */
interface UserKeyStore {
    val keys: Flow<UserDataSourceKeys>

    /** Latest keys, synchronously (used per network request). */
    fun current(): UserDataSourceKeys

    suspend fun save(keys: UserDataSourceKeys)

    suspend fun clear()
}

/**
 * Dedicated DataStore file ([FILE_NAME]) so it can be excluded from cloud backup and
 * device-to-device transfer (res/xml/backup_rules.xml, data_extraction_rules.xml).
 * Values are never logged.
 */
class DataStoreUserKeyStore(context: Context) : UserKeyStore {

    private val dataStore: DataStore<Preferences> = context.applicationContext.keyDataStore

    @Volatile
    private var cached: UserDataSourceKeys? = null

    override val keys: Flow<UserDataSourceKeys> = dataStore.data.map { it.toKeys() }

    init {
        // Keep an in-memory copy in sync so per-request reads never touch the disk.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            keys.collect { cached = it }
        }
    }

    override fun current(): UserDataSourceKeys =
        cached ?: runBlocking(Dispatchers.IO) { keys.first() }.also { cached = it }

    override suspend fun save(keys: UserDataSourceKeys) {
        val clean = keys.trimmed()
        dataStore.edit { prefs ->
            prefs.putOrRemove(QWEATHER_API_KEY, clean.qWeatherApiKey)
            prefs.putOrRemove(QWEATHER_HOST, clean.qWeatherHost)
            prefs.putOrRemove(JUHE_KEY, clean.juheKey)
        }
        cached = clean
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
        cached = UserDataSourceKeys()
    }

    companion object {
        /** DataStore file name; on disk: files/datastore/data_source_keys.preferences_pb */
        const val FILE_NAME = "data_source_keys"
        const val FILE_PATH = "datastore/$FILE_NAME.preferences_pb"

        private val QWEATHER_API_KEY = stringPreferencesKey("qweather_api_key")
        private val QWEATHER_HOST = stringPreferencesKey("qweather_api_host")
        private val JUHE_KEY = stringPreferencesKey("juhe_key")

        private fun Preferences.toKeys() = UserDataSourceKeys(
            qWeatherApiKey = this[QWEATHER_API_KEY].orEmpty(),
            qWeatherHost = this[QWEATHER_HOST].orEmpty(),
            juheKey = this[JUHE_KEY].orEmpty()
        )

        private fun androidx.datastore.preferences.core.MutablePreferences.putOrRemove(
            key: Preferences.Key<String>,
            value: String
        ) {
            if (value.isEmpty()) remove(key) else this[key] = value
        }
    }
}

private val Context.keyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = DataStoreUserKeyStore.FILE_NAME
)
