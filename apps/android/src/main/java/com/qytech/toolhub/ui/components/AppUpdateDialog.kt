package com.qytech.toolhub.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qytech.toolhub.R
import com.qytech.toolhub.data.AppUpdateRepository
import com.qytech.toolhub.data.AppUpdateStatus
import com.qytech.toolhub.ui.theme.semanticColors

@Composable
fun AppUpdateDialog(
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val state by AppUpdateRepository.state.collectAsState()
    val semantic = MaterialTheme.semanticColors

    LaunchedEffect(Unit) {
        if (state.status == AppUpdateStatus.IDLE) {
            AppUpdateRepository.checkForUpdate(context, silent = false)
        }
    }

    val isBusy = state.status == AppUpdateStatus.CHECKING ||
        state.status == AppUpdateStatus.DOWNLOADING ||
        state.status == AppUpdateStatus.INSTALLING

    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismissRequest() },
        icon = {
            Icon(
                imageVector = Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                text = stringResource(R.string.update_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.update_current_ver_fmt, state.currentVersion),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                        state.latestRelease?.let { rel ->
                            Text(
                                text = stringResource(R.string.update_latest_ver_fmt, rel.versionName),
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = if (state.status == AppUpdateStatus.UPDATE_AVAILABLE) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                state.message?.let { msg ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val icon = when (state.status) {
                            AppUpdateStatus.UP_TO_DATE -> Icons.Default.CheckCircle
                            AppUpdateStatus.ERROR -> Icons.Default.ErrorOutline
                            else -> Icons.Default.SystemUpdate
                        }
                        val tint = when (state.status) {
                            AppUpdateStatus.UP_TO_DATE -> semantic.success
                            AppUpdateStatus.ERROR -> semantic.danger
                            else -> MaterialTheme.colorScheme.primary
                        }
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = tint,
                        )
                    }
                }

                if (state.status == AppUpdateStatus.CHECKING || state.status == AppUpdateStatus.INSTALLING) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else if (state.status == AppUpdateStatus.DOWNLOADING) {
                    LinearProgressIndicator(
                        progress = { (state.downloadProgressPct / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                val notes = state.latestRelease?.releaseNotes.orEmpty()
                if (notes.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.update_release_notes),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp),
                    ) {
                        Text(
                            text = notes,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            val rel = state.latestRelease
            if (state.status == AppUpdateStatus.UPDATE_AVAILABLE && rel != null) {
                Button(
                    onClick = { AppUpdateRepository.downloadAndInstall(context, rel) },
                    enabled = !isBusy,
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.update_download_install))
                }
            } else {
                OutlinedButton(
                    onClick = { AppUpdateRepository.checkForUpdate(context, silent = false) },
                    enabled = !isBusy,
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.update_check_btn))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                enabled = !isBusy,
            ) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}
