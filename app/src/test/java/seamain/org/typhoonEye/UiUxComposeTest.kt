package seamain.org.typhoonEye

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import seamain.org.typhoonEye.data.preferences.UserSettings
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.TyphoonPoint
import seamain.org.typhoonEye.ui.DataMode
import seamain.org.typhoonEye.ui.TyphoonUiState
import seamain.org.typhoonEye.ui.screens.DetailScreen
import seamain.org.typhoonEye.ui.screens.HomeScreen
import seamain.org.typhoonEye.ui.screens.SettingsScreen
import seamain.org.typhoonEye.ui.theme.TyphoonEyeTheme

/**
 * Material 3 UI/UX checks on JVM via Robolectric (reliable without device unlock).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class UiUxComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sampleTyphoons = listOf(
        Typhoon(
            id = "202609",
            name = "巴威",
            englishName = "BAVI",
            status = "active",
            strong = "台风",
            positionDesc = "距离浙闽交界东南方向约890公里",
            forecastText = "向西北方向移动",
            points = listOf(
                TyphoonPoint("2026-07-10 14:00", 21.8, 126.9, 960, 40, "13", "台风", "北西", "22")
            ),
            forecastPoints = listOf(
                TyphoonPoint("2026-07-11 02:00", 23.0, 125.5, 955, 42, "13", "台风")
            )
        ),
        Typhoon(
            id = "202610",
            name = "美莎克",
            englishName = "MEKKHALA",
            status = "active",
            strong = "热带风暴",
            positionDesc = "菲律宾以东洋面",
            points = listOf(
                TyphoonPoint("2026-07-10 14:00", 12.5, 135.2, 998, 18, "8", "热带风暴", "NW", "20")
            )
        )
    )

    private fun assertHasText(text: String) {
        assertTrue(
            "Expected text node: $text",
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        )
    }

    private fun assertHasContentDescription(description: String) {
        composeRule.onNodeWithContentDescription(description, useUnmergedTree = true)
            .assertExists("Could not find node with contentDescription: $description")
    }

    @Test
    fun homeScreen_showsBrandTitleAndTyphoonCards() {
        composeRule.setContent {
            Box(modifier = Modifier.size(412.dp, 915.dp)) {
                TyphoonEyeTheme {
                    HomeScreen(
                        uiState = TyphoonUiState.Success(sampleTyphoons),
                        filteredTyphoons = sampleTyphoons,
                        isRefreshing = false,
                        query = "",
                        intensityFilter = null,
                        dataMode = DataMode.Demo,
                        lastUpdated = "14:32",
                        onQueryChange = {},
                        onFilterChange = {},
                        onRefresh = {},
                        onLoadDemo = {},
                        onOpenSettings = {},
                        onTyphoonClick = {},
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        assertHasText("台风眼")
        assertHasText("巴威")
        assertHasText("共 2 · 活跃 2")
        assertHasContentDescription("搜索台风")
        assertHasContentDescription("设置")
        assertHasContentDescription("台风 巴威，台风")
    }

    @Test
    fun homeScreen_emptyState_showsReloadAction() {
        composeRule.setContent {
            Box(modifier = Modifier.size(412.dp, 915.dp)) {
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
                        onTyphoonClick = {},
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        assertHasText("当前暂无活跃台风")
        assertHasText("重新加载")
        assertHasText("加载演示数据")
    }

    @Test
    fun homeScreen_errorState_showsRetry() {
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
                        onTyphoonClick = {},
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        composeRule.onNodeWithText("加载失败").assertIsDisplayed()
        composeRule.onNodeWithText("网络不可用").assertIsDisplayed()
        composeRule.onNodeWithText("重试").assertIsDisplayed()
    }

    @Test
    fun homeScreen_searchField_acceptsInput() {
        var query = ""
        composeRule.setContent {
            Box(modifier = Modifier.size(412.dp, 915.dp)) {
                TyphoonEyeTheme {
                    HomeScreen(
                        uiState = TyphoonUiState.Success(sampleTyphoons),
                        filteredTyphoons = sampleTyphoons,
                        isRefreshing = false,
                        query = query,
                        intensityFilter = null,
                        dataMode = DataMode.Live,
                        lastUpdated = null,
                        onQueryChange = { query = it },
                        onFilterChange = {},
                        onRefresh = {},
                        onLoadDemo = {},
                        onOpenSettings = {},
                        onTyphoonClick = {},
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("搜索台风").performTextInput("巴威")
        assert(query == "巴威")
    }

    @Test
    fun homeScreen_typhoonCard_isClickable() {
        var clickedId: String? = null
        composeRule.setContent {
            Box(modifier = Modifier.size(412.dp, 915.dp)) {
                TyphoonEyeTheme {
                    HomeScreen(
                        uiState = TyphoonUiState.Success(sampleTyphoons),
                        filteredTyphoons = sampleTyphoons,
                        isRefreshing = false,
                        query = "",
                        intensityFilter = null,
                        dataMode = DataMode.Demo,
                        lastUpdated = null,
                        onQueryChange = {},
                        onFilterChange = {},
                        onRefresh = {},
                        onLoadDemo = {},
                        onOpenSettings = {},
                        onTyphoonClick = { clickedId = it.id },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("台风 巴威，台风").performClick()
        assert(clickedId == "202609")
    }

    @Test
    fun settingsScreen_showsLiveActivityToggle() {
        composeRule.setContent {
            Box(modifier = Modifier.size(412.dp, 915.dp)) {
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
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        assertHasText("设置")
        assertHasText("台风 Live 状态")
        assertHasContentDescription("台风 Live 状态开关")
        assertHasText("基于定位推送")
        assertHasContentDescription("基于定位推送预警开关")
        assertHasText("主题")
    }

    @Test
    fun detailScreen_showsOverviewMetricsAndTabs() {
        composeRule.setContent {
            Box(modifier = Modifier.size(412.dp, 915.dp)) {
                TyphoonEyeTheme {
                    DetailScreen(
                        typhoon = sampleTyphoons.first(),
                        loading = false,
                        onBack = {},
                        onShare = {},
                        shareText = "share",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        assertHasText("巴威")
        assertHasText("概况")
        assertHasText("路径图")
        assertHasText("历史")
        assertHasText("预报")
        assertHasText("中心风速")
        assertHasText("40 m/s")
        assertHasText("预报要点")
        assertHasContentDescription("返回")
        assertHasContentDescription("分享概况")
    }

    @Test
    fun detailScreen_tabSwitch_showsTrackMap() {
        composeRule.setContent {
            Box(modifier = Modifier.size(412.dp, 915.dp)) {
                TyphoonEyeTheme {
                    DetailScreen(
                        typhoon = sampleTyphoons.first(),
                        loading = false,
                        onBack = {},
                        onShare = {},
                        shareText = "share",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        composeRule.onNodeWithText("路径图").performClick()
        assertHasText("路径地图")
    }

    @Test
    fun detailScreen_loading_showsProgressSemantics() {
        composeRule.setContent {
            Box(modifier = Modifier.size(412.dp, 915.dp)) {
                TyphoonEyeTheme {
                    DetailScreen(
                        typhoon = sampleTyphoons.first(),
                        loading = true,
                        onBack = {},
                        onShare = {},
                        shareText = "share",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        assertHasContentDescription("正在加载路径详情")
    }
}
