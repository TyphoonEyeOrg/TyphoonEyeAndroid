package seamain.org.typhoonEye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import seamain.org.typhoonEye.data.credentials.BuildTimeCredentials
import seamain.org.typhoonEye.data.credentials.DefaultDataSourceCredentials
import seamain.org.typhoonEye.data.credentials.QWeatherHost
import seamain.org.typhoonEye.data.credentials.UserDataSourceKeys
import seamain.org.typhoonEye.data.credentials.hasAnyDataSource
import seamain.org.typhoonEye.data.credentials.hasJuhe
import seamain.org.typhoonEye.data.credentials.hasQWeather

/** Precedence: user key (Settings) → build-time key (BuildConfig) → none. */
class DataSourceCredentialsTest {

    private var user = UserDataSourceKeys()
    private val build = BuildTimeCredentials(
        juheKey = "build-juhe",
        qWeatherApiKey = "build-qw",
        qWeatherHost = "https://build.qweatherapi.com/"
    )

    @Test
    fun userKeyWinsOverBuildKey() {
        user = UserDataSourceKeys(qWeatherApiKey = " user-qw ", qWeatherHost = "abc.re.qweatherapi.com", juheKey = "user-juhe")
        val creds = DefaultDataSourceCredentials({ user }, build)

        assertEquals("user-juhe", creds.juheKey())
        val qw = creds.qWeather()
        assertEquals("user-qw", qw.apiKey)
        assertEquals("https://abc.re.qweatherapi.com", qw.host)
    }

    @Test
    fun buildKeyUsedWhenNoUserKey() {
        val creds = DefaultDataSourceCredentials({ user }, build)

        assertEquals("build-juhe", creds.juheKey())
        assertEquals("build-qw", creds.qWeather().apiKey)
        assertEquals("https://build.qweatherapi.com", creds.qWeather().host)
        assertTrue(creds.hasAnyDataSource)
    }

    @Test
    fun noKeysAnywhere_meansNoDataSource() {
        // F-Droid: BuildConfig keys are empty.
        val creds = DefaultDataSourceCredentials({ user }, BuildTimeCredentials())

        assertFalse(creds.hasJuhe)
        assertFalse(creds.hasQWeather)
        assertFalse(creds.hasAnyDataSource)
        assertEquals(QWeatherHost.DEFAULT, creds.qWeather().host)
    }

    @Test
    fun userHostOnlyAppliesTogetherWithUserKey_andFallsBackWhenBlank() {
        user = UserDataSourceKeys(qWeatherHost = "ignored.example.com")
        val creds = DefaultDataSourceCredentials({ user }, build)
        assertEquals("build-qw", creds.qWeather().apiKey)
        assertEquals("https://build.qweatherapi.com", creds.qWeather().host)

        user = UserDataSourceKeys(qWeatherApiKey = "user-qw")
        assertEquals("https://build.qweatherapi.com", creds.qWeather().host)
        val noBuild = DefaultDataSourceCredentials({ user }, BuildTimeCredentials())
        assertEquals(QWeatherHost.DEFAULT, noBuild.qWeather().host)
    }

    @Test
    fun valuesAreReadOnEveryCall() {
        val creds = DefaultDataSourceCredentials({ user }, BuildTimeCredentials())
        assertFalse(creds.hasAnyDataSource)
        user = UserDataSourceKeys(juheKey = "j")
        assertTrue(creds.hasJuhe)
        user = UserDataSourceKeys()
        assertFalse(creds.hasAnyDataSource)
    }

    @Test
    fun buildJwtStillWorksWhenNoApiKey() {
        val jwtBuild = BuildTimeCredentials(qWeatherKid = "kid", qWeatherProjectId = "pid", qWeatherPrivateKeyPem = "pem")
        val creds = DefaultDataSourceCredentials({ user }, jwtBuild)
        assertTrue(creds.qWeather().usesJwt)
        assertTrue(creds.hasQWeather)
    }

    @Test
    fun hostNormalisation() {
        assertEquals("https://abc.re.qweatherapi.com", QWeatherHost.normalize("abc.re.qweatherapi.com"))
        assertEquals("https://abc.re.qweatherapi.com", QWeatherHost.normalize(" https://abc.re.qweatherapi.com/v7/ "))
        assertEquals("https://abc.re.qweatherapi.com:8443", QWeatherHost.normalize("https://abc.re.qweatherapi.com:8443/"))
        // HTTPS only: the key travels in a header.
        assertNull(QWeatherHost.normalize("http://abc.re.qweatherapi.com"))
        assertNull(QWeatherHost.normalize("HTTP://abc.re.qweatherapi.com"))
        assertNull(QWeatherHost.normalize("ftp://abc.re.qweatherapi.com"))
        assertFalse(QWeatherHost.isValid("http://abc.re.qweatherapi.com"))
        assertNull(QWeatherHost.normalize(""))
        assertNull(QWeatherHost.normalize("not a host"))
        assertNull(QWeatherHost.normalize("nodot"))
        assertTrue(QWeatherHost.isValid(""))
        assertFalse(QWeatherHost.isValid("bad host"))
    }

    @Test
    fun savedHttpHostIsIgnored_fallsBackToBuildHost() {
        user = UserDataSourceKeys(qWeatherApiKey = "k", qWeatherHost = "http://abc.re.qweatherapi.com")
        val creds = DefaultDataSourceCredentials({ user }, build)
        assertEquals("https://build.qweatherapi.com", creds.qWeather().host)
    }

    @Test
    fun toStringNeverContainsSecrets() {
        user = UserDataSourceKeys(qWeatherApiKey = "SECRET1", juheKey = "SECRET2")
        val creds = DefaultDataSourceCredentials({ user }, build)
        listOf(user.toString(), build.toString(), creds.qWeather().toString()).forEach { text ->
            assertFalse(text, text.contains("SECRET"))
            assertFalse(text, text.contains("build-qw"))
            assertFalse(text, text.contains("build-juhe"))
        }
    }
}
