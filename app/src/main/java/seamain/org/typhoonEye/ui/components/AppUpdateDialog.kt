package seamain.org.typhoonEye.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import seamain.org.typhoonEye.R
import seamain.org.typhoonEye.domain.model.AppUpdateInfo
import seamain.org.typhoonEye.domain.model.AppUpdateState

@Composable
fun AppUpdateHost(
    state: AppUpdateState,
    onDismiss: () -> Unit,
    onDownload: (AppUpdateInfo) -> Unit,
    onInstall: (String) -> Unit,
    onOpenPermissionSettings: () -> Unit,
    onOpenReleasePage: (AppUpdateInfo) -> Unit,
    canInstall: Boolean,
    /** When false, only actionable update states show (silent background check). */
    showStatusDialogs: Boolean = true
) {
    when (state) {
        is AppUpdateState.Available -> {
            UpdateAvailableDialog(
                info = state.info,
                onDismiss = onDismiss,
                onDownload = { onDownload(state.info) },
                onOpenReleasePage = { onOpenReleasePage(state.info) }
            )
        }
        is AppUpdateState.Downloading -> {
            UpdateDownloadingDialog(
                info = state.info,
                progress = state.progress,
                onDismiss = onDismiss
            )
        }
        is AppUpdateState.ReadyToInstall -> {
            UpdateReadyDialog(
                info = state.info,
                canInstall = canInstall,
                onDismiss = onDismiss,
                onInstall = { onInstall(state.apkPath) },
                onOpenPermissionSettings = onOpenPermissionSettings
            )
        }
        is AppUpdateState.UpToDate -> if (showStatusDialogs) {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.update_up_to_date_title)) },
                text = { Text(stringResource(R.string.update_up_to_date_body)) },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.update_ok))
                    }
                }
            )
        }
        is AppUpdateState.Error -> if (showStatusDialogs) {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.update_error_title)) },
                text = { Text(state.message) },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.update_ok))
                    }
                }
            )
        }
        AppUpdateState.Idle, AppUpdateState.Checking -> Unit
    }
}

@Composable
private fun UpdateAvailableDialog(
    info: AppUpdateInfo,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onOpenReleasePage: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.update_available_title, info.versionName))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = if (info.apkUrl.isBlank()) {
                        stringResource(R.string.update_no_compatible_apk)
                    } else {
                        stringResource(R.string.update_available_body, info.versionName)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (info.releaseNotes.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.update_release_notes),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = info.releaseNotes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (info.apkUrl.isBlank()) {
                // No APK matches this device's ABIs: manual download only (TYP-5).
                TextButton(onClick = onOpenReleasePage) {
                    Text(stringResource(R.string.update_view_release))
                }
            } else {
                TextButton(onClick = onDownload) {
                    Text(stringResource(R.string.update_download))
                }
            }
        },
        dismissButton = {
            if (info.apkUrl.isBlank()) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.update_later))
                }
            } else {
                TextButton(onClick = onOpenReleasePage) {
                    Text(stringResource(R.string.update_view_release))
                }
            }
        }
    )
}

@Composable
private fun UpdateDownloadingDialog(
    info: AppUpdateInfo,
    progress: Int,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_downloading_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.update_downloading_body, info.versionName, progress),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_background))
            }
        }
    )
}

@Composable
private fun UpdateReadyDialog(
    info: AppUpdateInfo,
    canInstall: Boolean,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
    onOpenPermissionSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_ready_title)) },
        text = {
            Text(
                text = if (canInstall) {
                    stringResource(R.string.update_ready_body, info.versionName)
                } else {
                    stringResource(R.string.update_need_install_permission)
                },
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (canInstall) onInstall() else onOpenPermissionSettings()
                }
            ) {
                Text(
                    if (canInstall) {
                        stringResource(R.string.update_install)
                    } else {
                        stringResource(R.string.update_grant_install)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_later))
            }
        }
    )
}
