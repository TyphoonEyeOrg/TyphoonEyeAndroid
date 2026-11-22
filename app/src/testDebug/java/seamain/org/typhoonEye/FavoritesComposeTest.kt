package seamain.org.typhoonEye

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import seamain.org.typhoonEye.domain.favorites.FavoriteTyphoon
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.TyphoonPoint
import seamain.org.typhoonEye.ui.DataMode
import seamain.org.typhoonEye.ui.TyphoonUiState
import seamain.org.typhoonEye.ui.screens.DetailScreen
import seamain.org.typhoonEye.ui.screens.FavoriteUnavailableScreen
import seamain.org.typhoonEye.ui.screens.FavoritesScreen
import seamain.org.typhoonEye.ui.screens.HomeScreen
import seamain.org.typhoonEye.ui.theme.TyphoonEyeTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w1080dp-h2400dp")
class FavoritesComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun home_hasFavoritesEntry() {
        var opened = 0
        composeRule.setContent {
            Box(modifier = Modifier.size(412.dp, 900.dp)) {
                TyphoonEyeTheme {
                    HomeScreen(
                        uiState = TyphoonUiState.Success(emptyList()),
                        filteredTyphoons = emptyList(),
                        isRefreshing = false,
                        query = "",
                        intensityFilter = null,
                        dataMode = DataMode.Live,
                        lastUpdated = null,
                        onQueryChange = {},
                        onFilterChange = {},
                        onRefresh = {},
                        onLoadDemo = {},
                        onOpenSettings = {},
                        onOpenFavorites = { opened += 1 },
                        onTyphoonClick = {},
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        composeRule.onNodeWithContentDescription("Favorites").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun favoritesList_rendersStoredMetadataWhenRemoteDataIsUnavailable() {
        var opened: String? = null
        composeRule.setContent {
            TyphoonEyeTheme {
                FavoritesScreen(
                    favorites = listOf(storedOldStorm),
                    onBack = {},
                    onOpen = { opened = it.stableId },
                    onUnfavorite = {}
                )
            }
        }
        composeRule.onNodeWithText("Favorites").assertIsDisplayed()
        composeRule.onNodeWithText("Bavi").assertIsDisplayed()
        composeRule.onNodeWithText("Year 2024 · 202418").assertIsDisplayed()
        composeRule.onNodeWithText(
            "A favorite stays in this list after the typhoon dissipates. " +
                "Saving one does not keep the remote record available. " +
                "History covers only the current and previous year (UTC+8); older years are rejected."
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open Bavi").performClick()
        assertEquals("2418", opened)
    }

    @Test
    fun outOfRange_explainsThatTheFavoriteIsKept() {
        composeRule.setContent {
            TyphoonEyeTheme {
                FavoriteUnavailableScreen(
                    name = "Bavi",
                    onBack = {},
                    onRemove = {}
                )
            }
        }
        composeRule.onNodeWithText("Remote record unavailable").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Bavi is still saved on this device. Its year is outside the historical data this app can request: " +
                "the current and previous year (UTC+8). Older years are rejected. " +
                "A favorite does not keep that remote data available.",
            substring = true
        ).assertIsDisplayed()
    }

    @Test
    fun detail_togglesFavorite() {
        var favorited = false
        composeRule.setContent {
            var saved by remember { mutableStateOf(false) }
            TyphoonEyeTheme {
                DetailScreen(
                    typhoon = Typhoon(
                        id = "202609",
                        name = "巴威",
                        englishName = "BAVI",
                        status = "active",
                        points = listOf(
                            TyphoonPoint("2026-07-10 14:00", 21.8, 126.9, 960, 40, "13", "TY")
                        )
                    ),
                    loading = false,
                    onBack = {},
                    onShare = {},
                    shareText = "share",
                    isFavorite = saved,
                    onToggleFavorite = {
                        saved = !saved
                        favorited = saved
                    }
                )
            }
        }
        composeRule.onNodeWithContentDescription("Favorite Bavi").performClick()
        assertTrue(favorited)
        composeRule.onNodeWithContentDescription("Unfavorite Bavi").assertIsDisplayed()
    }

    private val storedOldStorm = FavoriteTyphoon(
        stableId = "2418",
        sourceId = "NP_2418",
        trackId = "NP_2418",
        name = "巴威",
        englishName = "BAVI",
        year = 2024,
        basin = "NP",
        number = "202418",
        status = "dissipated",
        strong = "TY",
        favoritedAtEpochMs = 1L
    )
}
