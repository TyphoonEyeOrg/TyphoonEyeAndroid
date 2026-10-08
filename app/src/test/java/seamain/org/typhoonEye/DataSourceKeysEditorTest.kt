package seamain.org.typhoonEye

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import seamain.org.typhoonEye.data.credentials.UserDataSourceKeys
import seamain.org.typhoonEye.ui.components.DataSourceKeysEditor
import seamain.org.typhoonEye.ui.theme.TyphoonEyeTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class DataSourceKeysEditorTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Text as actually rendered (after the visual transformation). */
    private fun renderedText(tag: String): String {
        val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first().layoutInput.text.text
    }

    @Test
    fun keysAreMaskedByDefault_andToggleReveals() {
        composeRule.setContent {
            TyphoonEyeTheme {
                DataSourceKeysEditor(
                    saved = UserDataSourceKeys(qWeatherApiKey = "QWKEY", juheKey = "JHKEY"),
                    onSave = {},
                    onClear = {}
                )
            }
        }
        composeRule.onNodeWithText("QWeather API Key").assertIsDisplayed()
        composeRule.onNodeWithText("QWeather API Host").assertIsDisplayed()
        composeRule.onNodeWithText("Juhe key").assertIsDisplayed()
        assertEquals("\u2022".repeat(5), renderedText("key_qweather"))
        assertEquals("\u2022".repeat(5), renderedText("key_juhe"))
        composeRule.onAllNodes(hasText("QWKEY")).assertCountEquals(1) // only the editable value, not drawn

        composeRule.onAllNodesWithContentDescription("Show key")[0].performClick()
        assertEquals("QWKEY", renderedText("key_qweather"))
        assertEquals("\u2022".repeat(5), renderedText("key_juhe"))
        composeRule.onNodeWithContentDescription("Hide key").assertIsDisplayed()
    }

    @Test
    fun saveSendsTrimmedKeys_clearRemoves() {
        var saved: UserDataSourceKeys? = null
        var clears = 0
        composeRule.setContent {
            TyphoonEyeTheme {
                DataSourceKeysEditor(
                    saved = UserDataSourceKeys(juheKey = "old"),
                    onSave = { saved = it },
                    onClear = { clears++ }
                )
            }
        }
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
        composeRule.onNodeWithTag("key_qweather").performTextInput(" qw-key ")
        composeRule.onNodeWithTag("host_qweather").performTextInput("abc.re.qweatherapi.com")
        composeRule.onNodeWithText("Save").assertIsEnabled().performClick()
        assertEquals(
            UserDataSourceKeys(qWeatherApiKey = "qw-key", qWeatherHost = "abc.re.qweatherapi.com", juheKey = "old"),
            saved
        )

        composeRule.onNodeWithText("Remove keys").assertIsEnabled().performClick()
        assertEquals(1, clears)
    }

    @Test
    fun invalidHost_blocksSave() {
        composeRule.setContent {
            TyphoonEyeTheme {
                DataSourceKeysEditor(saved = UserDataSourceKeys(), onSave = {}, onClear = {})
            }
        }
        composeRule.onNodeWithText("Remove keys").assertIsNotEnabled()
        composeRule.onNodeWithTag("key_qweather").performTextInput("k")
        composeRule.onNodeWithTag("host_qweather").performTextInput("not a host")
        composeRule.onNodeWithText("Not a valid host name").assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun httpHost_isRejected_withInvalidHostMessage() {
        composeRule.setContent {
            TyphoonEyeTheme {
                DataSourceKeysEditor(saved = UserDataSourceKeys(), onSave = {}, onClear = {})
            }
        }
        composeRule.onNodeWithTag("key_qweather").performTextInput("k")
        composeRule.onNodeWithTag("host_qweather").performTextInput("http://abc.re.qweatherapi.com")
        composeRule.onNodeWithText("Not a valid host name").assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun keyWithoutHost_showsPerAccountHostHint() {
        val hint = "New QWeather accounts need their own API Host (see the QWeather console). " +
            "If left blank, the legacy host devapi.qweather.com is used."
        composeRule.setContent {
            TyphoonEyeTheme {
                DataSourceKeysEditor(saved = UserDataSourceKeys(), onSave = {}, onClear = {})
            }
        }
        composeRule.onAllNodes(hasText(hint)).assertCountEquals(0)
        composeRule.onNodeWithTag("key_qweather").performTextInput("k")
        composeRule.onNodeWithText(hint).assertIsDisplayed()
        // Still allowed to save (falls back to the default host).
        composeRule.onNodeWithText("Save").assertIsEnabled()

        composeRule.onNodeWithTag("host_qweather").performTextInput("abc.re.qweatherapi.com")
        composeRule.onAllNodes(hasText(hint)).assertCountEquals(0)
    }
}
