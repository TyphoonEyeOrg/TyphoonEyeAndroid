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
        assertEquals("https://github.com/TyphoonEyeOrg/TyphoonEyeAndroid", AboutLinks.REPOSITORY_URL)
        assertEquals("Apache-2.0", AboutLinks.LICENSE_NAME)
        assertEquals(
            "https://github.com/TyphoonEyeOrg/TyphoonEyeAndroid/blob/HEAD/LICENSE",
            AboutLinks.LICENSE_URL
        )
        assertEquals("https://www.qweather.com", AboutLinks.QWEATHER_URL)
        assertFalse(AboutLinks.REPOSITORY_URL.contains("Seamain"))
    }

    @Test
    fun versionPrefersBuildMetadata() {
        assertEquals("1.3.3", AboutLinks.displayedVersion("1.3.3", "9.9.9"))
        assertEquals("9.9.9", AboutLinks.displayedVersion(" ", "9.9.9"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryAboutCopyTest {

    @Test
    fun fourLocales_keepHistoryStatesAndAboutDistinct() {
        for (qualifiers in listOf("en", "zh-rCN", "zh-rTW", "b+yue")) {
            val values = listOf(
                text(qualifiers, R.string.history_banner),
                text(qualifiers, R.string.history_no_results),
                text(qualifiers, R.string.history_rate_limit),
                text(qualifiers, R.string.history_upstream_error),
                text(qualifiers, R.string.history_offline),
                text(qualifiers, R.string.history_offline_empty),
                text(qualifiers, R.string.history_track_missing),
                text(qualifiers, R.string.about_safety)
            )
            assertEquals(qualifiers, values.size, values.toSet().size)
            assertTrue(qualifiers, text(qualifiers, R.string.about_license_subtitle).contains("Apache"))
            assertTrue(qualifiers, text(qualifiers, R.string.about_license_subtitle).contains("2.0"))
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
