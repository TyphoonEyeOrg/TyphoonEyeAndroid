package seamain.org.typhoonEye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The F-Droid build carries no weather API keys, whatever local.properties or the
 * environment contain, and reaches typhoon data only through TyphoonEye's relay.
 */
class FdroidBuildConfigTest {

    @Test
    fun noKeysAreCompiledIn() {
        listOf(
            BuildConfig.JUHE_KEY,
            BuildConfig.QWEATHER_API_KEY,
            BuildConfig.QWEATHER_KID,
            BuildConfig.QWEATHER_PROJECT_ID,
            BuildConfig.QWEATHER_PRIVATE_KEY,
            BuildConfig.QWEATHER_HOST
        ).forEach { assertTrue("expected blank BuildConfig value", it.isEmpty()) }
    }

    @Test
    fun relayIsTheOwnSubdomain() {
        assertEquals("https://te-relay.seamain.org/", BuildConfig.RELAY_BASE_URL)
    }
}
