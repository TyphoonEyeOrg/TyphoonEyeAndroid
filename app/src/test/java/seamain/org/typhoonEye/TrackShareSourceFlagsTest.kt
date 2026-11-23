package seamain.org.typhoonEye

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import seamain.org.typhoonEye.ui.share.shareSourceFlags

class TrackShareSourceFlagsTest {

    @Test
    fun jwtTripleWithEmptyDeveloperId_enablesQWeather() {
        val flags = shareSourceFlags(
            juheKey = "",
            qWeatherApiKey = "",
            qWeatherKid = "kid",
            qWeatherProjectId = "project",
            qWeatherDeveloperId = "",
            qWeatherPrivateKey = "private-key"
        )
        assertTrue(flags.qWeatherEnabled)
        assertFalse(flags.juheEnabled)
        assertFalse(flags.viaRelay)
    }

    @Test
    fun missingJwtPiece_doesNotEnableQWeather() {
        val complete = listOf("kid", "project", "private-key")
        complete.indices.forEach { drop ->
            val parts = complete.mapIndexed { index, value -> if (index == drop) "" else value }
            val flags = shareSourceFlags(
                juheKey = "",
                qWeatherApiKey = "",
                qWeatherKid = parts[0],
                qWeatherProjectId = parts[1],
                qWeatherDeveloperId = "",
                qWeatherPrivateKey = parts[2]
            )
            assertFalse("missing piece $drop", flags.qWeatherEnabled)
        }
    }

    @Test
    fun juheKey_enablesJuheOnly() {
        val flags = shareSourceFlags(
            juheKey = "juhe",
            qWeatherApiKey = "",
            qWeatherKid = "",
            qWeatherProjectId = "",
            qWeatherDeveloperId = "",
            qWeatherPrivateKey = ""
        )
        assertTrue(flags.juheEnabled)
        assertFalse(flags.qWeatherEnabled)
        assertFalse(flags.viaRelay)
    }
}
