package seamain.org.typhoonEye

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import seamain.org.typhoonEye.data.credentials.DataStoreUserKeyStore
import seamain.org.typhoonEye.data.credentials.UserDataSourceKeys
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataStoreUserKeyStoreTest {

    @Test
    fun saveTrimsAndPersistsInDedicatedFile_clearRemovesEverything() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val store = DataStoreUserKeyStore(context)

        store.save(UserDataSourceKeys(qWeatherApiKey = " k ", qWeatherHost = " h.example.com ", juheKey = "j"))
        val expected = UserDataSourceKeys("k", "h.example.com", "j")
        assertEquals(expected, store.current())
        assertEquals(expected, store.keys.first())
        assertTrue(File(context.filesDir, DataStoreUserKeyStore.FILE_PATH).exists())

        store.clear()
        assertEquals(UserDataSourceKeys(), store.current())
        assertEquals(UserDataSourceKeys(), store.keys.first())
    }
}
