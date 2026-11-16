package seamain.org.typhoonEye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import seamain.org.typhoonEye.ui.about.AboutLinks

class AboutLinksTest {

    @Test
    fun repositoryAndLicensePointAtThisAndroidProject() {
        assertEquals(
            "https://github.com/TyphoonEyeOrg/TyphoonEyeAndroid",
            AboutLinks.REPOSITORY_URL
        )
        assertEquals("Apache-2.0", AboutLinks.LICENSE_NAME)
        assertEquals(
            "https://github.com/TyphoonEyeOrg/TyphoonEyeAndroid/blob/HEAD/LICENSE",
            AboutLinks.LICENSE_URL
        )
        assertTrue(AboutLinks.LICENSE_URL.startsWith(AboutLinks.REPOSITORY_URL))
        assertTrue(AboutLinks.LICENSE_URL.endsWith("/LICENSE"))
        assertEquals("https://www.qweather.com", AboutLinks.QWEATHER_URL)
        assertFalse(AboutLinks.REPOSITORY_URL.contains("Seamain"))
    }

    @Test
    fun versionPrefersBuildMetadata() {
        assertEquals("1.3.3", AboutLinks.displayedVersion("1.3.3", "9.9.9"))
        assertEquals("1.3.3", AboutLinks.displayedVersion(" 1.3.3 ", null))
        assertEquals("9.9.9", AboutLinks.displayedVersion(" ", "9.9.9"))
        assertEquals("", AboutLinks.displayedVersion("", null))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryAboutCopyTest {

    @Test
    fun fourLocales_keepHistoryStatesAndAboutDistinct() {
        for (qualifiers in listOf("en", "zh-rCN", "zh-rTW", "b+yue")) {
            val banner = text(qualifiers, R.string.history_banner)
            val noResults = text(qualifiers, R.string.history_no_results)
            val rate = text(qualifiers, R.string.history_rate_limit)
            val upstream = text(qualifiers, R.string.history_upstream_error)
            val offline = text(qualifiers, R.string.history_offline)
            val offlineEmpty = text(qualifiers, R.string.history_offline_empty)
            val missing = text(qualifiers, R.string.history_track_missing)
            val safety = text(qualifiers, R.string.about_safety)
            val license = text(qualifiers, R.string.about_license_subtitle)
            val distinct = listOf(banner, noResults, rate, upstream, offline, offlineEmpty, missing, safety)
            assertEquals(qualifiers, distinct.size, distinct.toSet().size)
            assertTrue(qualifiers, license.contains("Apache"))
            assertTrue(qualifiers, license.contains("2.0"))
            assertTrue(qualifiers, text(qualifiers, R.string.history_detail_banner).isNotBlank())
            assertTrue(qualifiers, text(qualifiers, R.string.about_summary).isNotBlank())
        }
    }

    @Test
    fun englishCopy_doesNotPromiseOlderYears() {
        val coverage = text("en", R.string.history_coverage)
        assertTrue(coverage.contains("previous year"))
        assertTrue(coverage.contains("Earlier years are not available"))
        assertFalse(coverage.contains("1945"))
    }

    private fun text(qualifiers: String, id: Int): String {
        RuntimeEnvironment.setQualifiers(qualifiers)
        return RuntimeEnvironment.getApplication().getString(id)
    }
}
