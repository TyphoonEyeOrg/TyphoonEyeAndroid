package seamain.org.typhoonEye

import org.junit.Assert.assertFalse
import org.junit.Test

/** TYP-58: the GitHub build sends coordinates to QWeather, so location alerts are opt-in. */
class LocationAlertsDefaultTest {

    @Test
    fun locationAlertsDefaultOff() {
        assertFalse(BuildConfig.LOCATION_ALERTS_DEFAULT)
    }
}
