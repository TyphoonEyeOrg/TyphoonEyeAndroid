package seamain.org.typhoonEye

import org.junit.Assert.assertEquals
import org.junit.Test

/** The GitHub build keeps calling the providers directly with its build-time keys. */
class GithubBuildConfigTest {

    @Test
    fun noRelay() {
        assertEquals("", BuildConfig.RELAY_BASE_URL)
    }
}
