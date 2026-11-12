package seamain.org.typhoonEye

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import seamain.org.typhoonEye.data.preferences.UserSettings
import seamain.org.typhoonEye.domain.history.HistoricalStorm
import seamain.org.typhoonEye.domain.history.HistoricalStormFeed
import seamain.org.typhoonEye.domain.history.HistoryPhase
import seamain.org.typhoonEye.domain.history.HistoryPresentation
import seamain.org.typhoonEye.domain.history.presentHistory
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.TyphoonPoint
import seamain.org.typhoonEye.ui.HistoryUi
import seamain.org.typhoonEye.ui.about.AboutLinks
import seamain.org.typhoonEye.ui.screens.DetailScreen
import seamain.org.typhoonEye.ui.screens.HistoryScreen
import seamain.org.typhoonEye.ui.screens.SettingsScreen
import seamain.org.typhoonEye.ui.theme.TyphoonEyeTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w1080dp-h2400dp")
class HistoryAboutComposeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val storms = listOf(
        HistoricalStorm("NP_2609", "Bavi", 2026, "NP", false, "202609"),
        HistoricalStorm("NP_2610", "Mekkhala", 2026, "NP", false, "202610")
    )

    @Test
    fun about_showsBuildVersion_andOpensRepoAndLicense() {
        composeRule.setContent {
            Box(modifier = Modifier.size(412.dp, 900.dp)) {
                TyphoonEyeTheme {
                    SettingsScreen(
                        settings = UserSettings(),
                        notificationsGranted = true,
                        locationPermissionGranted = true,
                        onBack = {},
                        onThemeModeChange = {},
                        onAppLanguageChange = {},
                        onLiveActivityChange = {},
                        onEmergencyAlertsChange = {},
                        onLocationAlertsChange = {},
                        onDynamicColorChange = {},
                        onMapBasemapChange = {},
                        onRequestNotificationPermission = {},
                        onRequestLocationPermission = {},
                        inAppUpdatesEnabled = false,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        assertHasText("Version ${BuildConfig.VERSION_NAME}")
        assertHasText("Unofficial meteorological product.")
        assertHasText("Warnings and disaster-prevention actions follow releases from your local authorities.")

        composeRule.onNodeWithText(AboutLinks.REPOSITORY_URL).performScrollTo().performClick()
        val repo = Shadows.shadowOf(composeRule.activity).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_VIEW, repo.action)
        assertEquals(AboutLinks.REPOSITORY_URL, repo.dataString)

        composeRule.onNodeWithText(AboutLinks.LICENSE_NAME).performScrollTo().performClick()
        val license = Shadows.shadowOf(composeRule.activity).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_VIEW, license.action)
        assertEquals(AboutLinks.LICENSE_URL, license.dataString)
    }

    @Test
    fun history_filtersByNameAndNumber_andYearClick() {
        var opened: String? = null
        var selectedYear = 2026
        composeRule.setContent {
            var year by remember { mutableStateOf(2026) }
            var query by remember { mutableStateOf("") }
            TyphoonEyeTheme {
                HistoryScreen(
                    state = HistoryUi(
                        years = listOf(2026, 2025),
                        year = year,
                        query = query,
                        loading = false,
                        presentation = presentHistory(
                            year,
                            query,
                            HistoricalStormFeed(2026, storms, fromCache = false),
                            null
                        )
                    ),
                    onBack = {},
                    onYearChange = {
                        year = it
                        selectedYear = it
                    },
                    onQueryChange = { query = it },
                    onRefresh = {},
                    onOpenStorm = { opened = it.id },
                    onOpenAttribution = {}
                )
            }
        }

        assertHasText("Historical records. Not a current warning.")
        assertHasText("Bavi")
        composeRule.onNodeWithContentDescription("Search historical storms by name or number")
            .performTextReplacement("Mekkhala")
        assertHasText("Mekkhala")
        assertEquals(0, composeRule.onAllNodesWithText("Bavi").fetchSemanticsNodes().size)
        composeRule.onNodeWithContentDescription("Search historical storms by name or number")
            .performTextReplacement("202609")
        composeRule.onNodeWithText("Bavi").performClick()
        assertEquals("NP_2609", opened)

        composeRule.onNodeWithTag("history-year-2025").performClick()
        assertEquals(2025, selectedYear)
    }

    @Test
    fun history_noResults_isNotARateLimit() {
        show(HistoryPresentation(HistoryPhase.NoResults, emptyList(), false))
        assertHasText("No storms match this year and search.")
        assertEquals(
            0,
            composeRule.onAllNodesWithText("The data service is limiting requests. No new list was loaded.")
                .fetchSemanticsNodes().size
        )
    }

    @Test
    fun history_rateLimit_isNotNoResults() {
        show(HistoryPresentation(HistoryPhase.RateLimited, emptyList(), false))
        assertHasText("The data service is limiting requests. No new list was loaded.")
        assertEquals(
            0,
            composeRule.onAllNodesWithText("No storms match this year and search.").fetchSemanticsNodes().size
        )
    }

    @Test
    fun history_offline_keepsTheSavedRow() {
        show(
            HistoryPresentation(
                HistoryPhase.OfflineResults,
                listOf(storms.first()),
                true
            )
        )
        assertHasText("Showing the saved list because the network request failed.")
        assertHasText("Bavi")
    }

    @Test
    fun detail_missingTrack_isExplicit_andARealTrackIsShown() {
        composeRule.setContent {
            TyphoonEyeTheme {
                DetailScreen(
                    typhoon = Typhoon(
                        id = "NP_2608",
                        name = "Higos",
                        englishName = "",
                        status = "dissipated"
                    ),
                    loading = false,
                    onBack = {},
                    onShare = {},
                    shareText = "",
                    historicalRecord = true,
                    trackMissing = true,
                    windRadiiMissing = true
                )
            }
        }
        assertHasText("QWeather did not return a track for this storm. No path was added.")
        assertHasText("Not a current warning.")
    }

    @Test
    fun detail_realTrack_showsObservation_andMissingWindRadii() {
        composeRule.setContent {
            TyphoonEyeTheme {
                DetailScreen(
                    typhoon = Typhoon(
                        id = "NP_2609",
                        name = "Bavi",
                        englishName = "",
                        status = "dissipated",
                        points = listOf(
                            TyphoonPoint(
                                time = "2026-07-01T06:00+08:00",
                                lat = 15.0,
                                lng = 130.0,
                                pressure = 990,
                                speed = 20,
                                power = "TS",
                                strong = "TS"
                            )
                        )
                    ),
                    loading = false,
                    onBack = {},
                    onShare = {},
                    shareText = "",
                    historicalRecord = true,
                    trackMissing = false,
                    windRadiiMissing = true
                )
            }
        }
        assertHasText("QWeather did not include wind-circle radii for this track.")
        assertHasText("20 m/s")
        assertEquals(
            0,
            composeRule.onAllNodesWithText("QWeather did not return a track for this storm. No path was added.")
                .fetchSemanticsNodes().size
        )
    }

    private fun assertHasText(text: String) {
        composeRule.waitForIdle()
        val count = composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size
        assertTrue("$text was not shown", count >= 1)
    }

    private fun show(presentation: HistoryPresentation) {
        composeRule.setContent {
            TyphoonEyeTheme {
                HistoryScreen(
                    state = HistoryUi(
                        years = listOf(2026, 2025),
                        year = 2026,
                        query = "",
                        loading = false,
                        presentation = presentation
                    ),
                    onBack = {},
                    onYearChange = {},
                    onQueryChange = {},
                    onRefresh = {},
                    onOpenStorm = {},
                    onOpenAttribution = {}
                )
            }
        }
    }
}
