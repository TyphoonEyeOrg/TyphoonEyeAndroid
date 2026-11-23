package seamain.org.typhoonEye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import seamain.org.typhoonEye.ui.share.shareSourceFlags

/** Foss source-flag rules. Not part of the shared share-image tests. */
class TrackShareSourceFlagsTest {

    @Test
    fun relay_enablesBothAgenciesEvenWithoutKeys() {
        val flags = shareSourceFlags(
            relayBaseUrl = "https://te-relay.seamain.org/",
            juheKey = "",
            qWeatherApiKey = "",
            qWeatherKid = "",
            qWeatherProjectId = "",
            qWeatherDeveloperId = "",
            qWeatherPrivateKey = ""
        )
        assertTrue(flags.viaRelay)
        assertTrue(flags.juheEnabled)
        assertTrue(flags.qWeatherEnabled)
    }

    @Test
    fun blankRelay_usesOnlyTheCredentialsThatArePresent() {
        val juheOnly = shareSourceFlags("", juheKey = "k", qWeatherApiKey = "", qWeatherKid = "", qWeatherProjectId = "", qWeatherDeveloperId = "", qWeatherPrivateKey = "")
        assertFalse(juheOnly.viaRelay)
        assertTrue(juheOnly.juheEnabled)
        assertFalse(juheOnly.qWeatherEnabled)

        val apiKey = shareSourceFlags("", juheKey = "", qWeatherApiKey = "qw", qWeatherKid = "kid", qWeatherProjectId = "", qWeatherDeveloperId = "", qWeatherPrivateKey = "")
        assertFalse(apiKey.juheEnabled)
        assertTrue(apiKey.qWeatherEnabled)

        val jwt = shareSourceFlags("", juheKey = "", qWeatherApiKey = "", qWeatherKid = "kid", qWeatherProjectId = "proj", qWeatherDeveloperId = "dev", qWeatherPrivateKey = "pem")
        assertTrue(jwt.qWeatherEnabled)

        val partialJwt = shareSourceFlags("", juheKey = "", qWeatherApiKey = "", qWeatherKid = "kid", qWeatherProjectId = "proj", qWeatherDeveloperId = "dev", qWeatherPrivateKey = "")
        assertFalse(partialJwt.qWeatherEnabled)
    }

    @Test
    fun nothingConfigured_enablesNothing() {
        val flags = shareSourceFlags("", "", "", "", "", "", "")
        assertEquals(false, flags.viaRelay)
        assertFalse(flags.juheEnabled)
        assertFalse(flags.qWeatherEnabled)
    }
}
