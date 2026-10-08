package seamain.org.typhoonEye.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import seamain.org.typhoonEye.R
import seamain.org.typhoonEye.data.credentials.QWeatherHost
import seamain.org.typhoonEye.data.credentials.UserDataSourceKeys

/**
 * Settings → "Custom data source keys": QWeather API key + API host, Juhe key.
 * Keys are masked by default (eye toggle reveals them); values only leave this composable
 * through [onSave] into the on-device DataStore.
 */
@Composable
fun DataSourceKeysEditor(
    saved: UserDataSourceKeys,
    onSave: (UserDataSourceKeys) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // Re-seed the fields whenever the stored value changes (save / clear / first load).
    // Plain remember (not rememberSaveable): keys must not end up in the saved-state Bundle.
    var qWeatherKey by remember(saved) { mutableStateOf(saved.qWeatherApiKey) }
    var qWeatherHost by remember(saved) { mutableStateOf(saved.qWeatherHost) }
    var juheKey by remember(saved) { mutableStateOf(saved.juheKey) }

    val edited = UserDataSourceKeys(qWeatherKey, qWeatherHost, juheKey).trimmed()
    val hostValid = QWeatherHost.isValid(qWeatherHost)
    val canSave = hostValid && edited != saved.trimmed()
    val savedMsg = stringResource(R.string.data_source_keys_saved)
    val clearedMsg = stringResource(R.string.data_source_keys_cleared)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.data_source_keys_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SecretField(
            value = qWeatherKey,
            onValueChange = { qWeatherKey = it },
            label = stringResource(R.string.qweather_api_key_label),
            tag = "key_qweather"
        )
        OutlinedTextField(
            value = qWeatherHost,
            onValueChange = { qWeatherHost = it },
            label = { Text(stringResource(R.string.qweather_api_host_label)) },
            supportingText = {
                Text(
                    stringResource(
                        when {
                            !hostValid -> R.string.qweather_api_host_invalid
                            // Key without host: most new accounts can't use the legacy shared host.
                            qWeatherKey.isNotBlank() && qWeatherHost.isBlank() ->
                                R.string.qweather_api_host_missing_hint
                            else -> R.string.qweather_api_host_hint
                        }
                    )
                )
            },
            isError = !hostValid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("host_qweather")
        )
        SecretField(
            value = juheKey,
            onValueChange = { juheKey = it },
            label = stringResource(R.string.juhe_key_label),
            tag = "key_juhe"
        )
        Text(
            text = stringResource(R.string.data_source_keys_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onSave(edited)
                    Toast.makeText(context, savedMsg, Toast.LENGTH_SHORT).show()
                },
                enabled = canSave
            ) { Text(stringResource(R.string.action_save_keys)) }
            OutlinedButton(
                onClick = {
                    qWeatherKey = ""
                    qWeatherHost = ""
                    juheKey = ""
                    onClear()
                    Toast.makeText(context, clearedMsg, Toast.LENGTH_SHORT).show()
                },
                enabled = !saved.isEmpty
            ) { Text(stringResource(R.string.action_clear_keys)) }
        }
    }
}

@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    tag: String
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Next
        ),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = stringResource(if (visible) R.string.cd_hide_key else R.string.cd_show_key)
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
    )
}
