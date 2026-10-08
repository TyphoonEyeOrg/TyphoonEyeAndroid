package seamain.org.typhoonEye

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.ui.DataMode
import seamain.org.typhoonEye.ui.TyphoonUiState
import seamain.org.typhoonEye.ui.screens.HomeScreen
import seamain.org.typhoonEye.ui.theme.TyphoonEyeTheme

/** English UI for the no-key (F-Droid) path and the demo-mode banner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class NoDataSourceComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setHome(
        state: TyphoonUiState,
        dataMode: DataMode = DataMode.Live,
        typhoons: List<Typhoon> = emptyList(),
        onLoadDemo: () -> Unit = {},
        onExitDemo: () -> Unit = {},
        onRefresh: () -> Unit = {},
        onOpenSettings: () -> Unit = {}
    ) {
        composeRule.setContent {
            Box(modifier = Modifier.size(412.dp, 915.dp)) {
                TyphoonEyeTheme {
                    HomeScreen(
                        uiState = state,
                        filteredTyphoons = typhoons,
                        isRefreshing = false,
                        query = "",
                        intensityFilter = null,
                        dataMode = dataMode,
                        lastUpdated = null,
                        onQueryChange = {},
                        onFilterChange = {},
                        onRefresh = onRefresh,
                        onLoadDemo = onLoadDemo,
                        onExitDemo = onExitDemo,
                        onOpenSettings = onOpenSettings,
                        onTyphoonClick = {},
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    @Test
    fun noDataSource_explainsAndOffersKeyEntryAndDemo() {
        var demoClicks = 0
        var retries = 0
        var settingsClicks = 0
        setHome(
            TyphoonUiState.NoDataSource,
            onLoadDemo = { demoClicks++ },
            onRefresh = { retries++ },
            onOpenSettings = { settingsClicks++ }
        )

        composeRule.onNodeWithText("No live data source in this build").assertIsDisplayed()
        // Small Robolectric window: the page scrolls, so bring each action into view first.
        composeRule.onNodeWithText("Enter your API key").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithText("View demo data").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Retry").performScrollTo().performClick()
        assertEquals(1, settingsClicks)
        assertEquals(1, demoClicks)
        assertEquals(1, retries)
        // Not presented as a load failure.
        assertEquals(
            0,
            composeRule.onAllNodesWithTextCount("Failed to load")
        )
    }

    @Test
    fun demoMode_showsPersistentSampleBannerWithExit() {
        var exits = 0
        val sample = Typhoon(id = "202609", name = "BAVI", englishName = "BAVI", status = "active")
        setHome(
            TyphoonUiState.Success(listOf(sample)),
            dataMode = DataMode.Demo,
            typhoons = listOf(sample),
            onExitDemo = { exits++ }
        )

        composeRule.onNodeWithText("Sample data — not real typhoons").assertIsDisplayed()
        composeRule.onNodeWithText("Exit demo").performClick()
        assertEquals(1, exits)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
