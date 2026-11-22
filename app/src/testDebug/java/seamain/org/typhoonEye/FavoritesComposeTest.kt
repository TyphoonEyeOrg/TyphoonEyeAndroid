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
import androidx.compose.ui.test.onAllNodesWithText
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
import seamain.org.typhoonEye.domain.model.FavoriteTyphoon
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.ui.DataMode
import seamain.org.typhoonEye.ui.TyphoonUiState
import seamain.org.typhoonEye.ui.screens.DetailScreen
import seamain.org.typhoonEye.ui.screens.FavoriteUnavailableScreen
import seamain.org.typhoonEye.ui.screens.FavoritesScreen
import seamain.org.typhoonEye.ui.screens.HomeScreen
import seamain.org.typhoonEye.ui.theme.TyphoonEyeTheme

/**
 * Favorites UI on JVM via Robolectric. src/testDebug so the Compose test
 * activity stays out of the release APK (#38).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class FavoritesComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val mangkhut = FavoriteTyphoon(
        id = "NP_1822",
        name = "山竹",
        englishName = "MANGKHUT",
        year = 2018,
        basin = "NP",
        favoritedAtEpochMs = 1L
    )

    @Test
    fun home_favoritesEntry_isAlwaysAvailable() {
        var opened = false
        composeRule.setContent {
            Box(modifier = Modifier.size(412.dp, 915.dp)) {
                TyphoonEyeTheme {
                    HomeScreen(
                        uiState = TyphoonUiState.Error("网络不可用"),
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
                        onOpenFavorites = { opened = true },
                        onTyphoonClick = {},
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        composeRule.onNodeWithContentDescription("收藏的台风").performClick()
        assertTrue(opened)
    }

    @Test
    fun favoritesList_rendersStoredMetadata_withoutRemoteData() {
        var opened: String? = null
        composeRule.setContent {
            Box(modifier = Modifier.size(412.dp, 915.dp)) {
                TyphoonEyeTheme {
                    FavoritesScreen(
                        favorites = listOf(mangkhut),
                        onBack = {},
                        onOpen = { opened = it.id },
                        onRemove = {},
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        composeRule.onNodeWithText("山竹").assertIsDisplayed()
        composeRule.onNodeWithText("2018 年 · MANGKHUT · NP_1822 · 本机收藏", substring = true)
            .assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithText("收藏不等于远端记录一直打得开", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        )
        composeRule.onNodeWithText("山竹").performClick()
        assertEquals("NP_1822", opened)
    }

    @Test
    fun outOfRange_explainsThatTheFavoriteStays() {
        composeRule.setContent {
            Box(modifier = Modifier.size(412.dp, 915.dp)) {
                TyphoonEyeTheme {
                    FavoriteUnavailableScreen(
                        name = "山竹",
                        yearLabel = "2018",
                        onBack = {},
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        composeRule.onNodeWithText("远端记录超出可查范围").assertIsDisplayed()
        composeRule.onNodeWithText("山竹（2018）仍在收藏里。", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("今年和上一年（UTC+8）", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("也不保证以后还能取到远端路径", substring = true).assertIsDisplayed()
    }

    @Test
    fun detailStar_togglesFavorite() {
        var favorited = false
        composeRule.setContent {
            var saved by remember { mutableStateOf(false) }
            Box(modifier = Modifier.size(412.dp, 915.dp)) {
                TyphoonEyeTheme {
                    DetailScreen(
                        typhoon = Typhoon(
                            id = "202609",
                            name = "巴威",
                            englishName = "BAVI",
                            status = "active"
                        ),
                        loading = false,
                        onBack = {},
                        onShare = {},
                        shareText = "",
                        isFavorite = saved,
                        onToggleFavorite = {
                            saved = !saved
                            favorited = saved
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        composeRule.onNodeWithContentDescription("收藏 巴威").performClick()
        assertTrue(favorited)
        composeRule.onNodeWithContentDescription("取消收藏 巴威").assertIsDisplayed()
    }
}
