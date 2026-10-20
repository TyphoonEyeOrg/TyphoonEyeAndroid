package seamain.org.typhoonEye.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import seamain.org.typhoonEye.R

/**
 * TYP-58: shown once to upgraders whose distance alerts were on by the old default
 * and are now opt-in. "Turn on" re-enables them (then the permission flow runs);
 * dismissing stores an explicit "off".
 */
@Composable
fun LocationOptInNoticeDialog(
    onTurnOn: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.MyLocation, contentDescription = null) },
        title = { Text(stringResource(R.string.location_opt_in_notice_title)) },
        text = {
            Text(
                stringResource(
                    R.string.location_opt_in_notice_body,
                    stringResource(R.string.app_name)
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onTurnOn) {
                Text(stringResource(R.string.location_opt_in_notice_turn_on))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.location_opt_in_notice_dismiss))
            }
        }
    )
}
