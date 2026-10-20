package seamain.org.typhoonEye

import org.junit.Assert.assertTrue
import org.junit.Test

/** TYP-58: the relay build never sends the position off the device, so the default stays on. */
class FdroidLocationDefaultTest {

    @Test
    fun locationAlertsDefaultOn() {
        assertTrue(BuildConfig.LOCATION_ALERTS_DEFAULT)
    }
}
