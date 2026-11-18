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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.qytech.toolhub.R
import com.qytech.toolhub.data.RemoteFleetRepository
import com.qytech.toolhub.data.TunnelConnectionStatus
import com.qytech.toolhub.ui.theme.semanticColors

@Composable
fun RemoteFleetDialog(
    onShowMessage: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val state by RemoteFleetRepository.state.collectAsState()
    val semantic = MaterialTheme.semanticColors

    var enabled by remember(state.config) { mutableStateOf(state.config.enabled) }
    var serverUrl by remember(state.config) { mutableStateOf(state.config.fleetServerUrl) }
    var deviceToken by remember(state.config) { mutableStateOf(state.config.deviceToken) }
    var sshHost by remember(state.config) { mutableStateOf(state.config.sshHost) }
    var sshPort by remember(state.config) { mutableStateOf(state.config.sshPort.toString()) }
    var sshUser by remember(state.config) { mutableStateOf(state.config.sshUser) }
    var remoteWebPort by remember(state.config) { mutableStateOf(state.config.remoteWebPort.toString()) }
    var remoteAdbPort by remember(state.config) { mutableStateOf(state.config.remoteAdbPort.toString()) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .padding(vertical = 12.dp),
        icon = {
            Icon(
                imageVector = Icons.Default.Cloud,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.fleet_dlg_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                val (statusLabel, statusColor) = when (state.tunnelStatus) {
                    TunnelConnectionStatus.CONNECTED -> stringResource(R.string.fleet_status_connected) to semantic.success
                    TunnelConnectionStatus.CONNECTING,
                    TunnelConnectionStatus.RECONNECTING -> stringResource(R.string.fleet_status_connecting) to semantic.warning
                    TunnelConnectionStatus.ERROR -> stringResource(R.string.fleet_status_error) to semantic.danger
                    TunnelConnectionStatus.DISABLED -> stringResource(R.string.fleet_status_disabled) to MaterialTheme.colorScheme.onSurfaceVariant
                }
                StatusPill(text = statusLabel, color = statusColor, compact = true)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "MAC: ${state.deviceMac} · SN: ${state.deviceSerial}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (!state.statusMessage.isNullOrBlank()) {
                            Text(
                                text = state.statusMessage!!,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.fleet_enable_switch),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }

                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text(stringResource(R.string.fleet_server_url)) },
                    placeholder = { Text("http://your-server:9090") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = deviceToken,
                    onValueChange = { deviceToken = it },
                    label = { Text(stringResource(R.string.fleet_device_token)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = sshHost,
                        onValueChange = { sshHost = it },
                        label = { Text(stringResource(R.string.fleet_ssh_host)) },
                        singleLine = true,
                        modifier = Modifier.weight(1.4f),
                    )
                    OutlinedTextField(
                        value = sshPort,
                        onValueChange = { sshPort = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.fleet_ssh_port)) },
                        singleLine = true,
                        modifier = Modifier.weight(0.6f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = sshUser,
                        onValueChange = { sshUser = it },
                        label = { Text(stringResource(R.string.fleet_ssh_user)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = remoteWebPort,
                        onValueChange = { remoteWebPort = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.fleet_web_port)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = remoteAdbPort,
                        onValueChange = { remoteAdbPort = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.fleet_adb_port)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                Text(
                    text = stringResource(R.string.fleet_pubkey_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = state.publicKeyOpenSsh.ifBlank { "-" },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 3,
                        modifier = Modifier.padding(10.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            copyTextToClipboard(
                                context,
                                "SSH Public Key",
                                state.publicKeyOpenSsh,
                                onShowMessage,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.fleet_copy_pubkey))
                    }
                    OutlinedButton(
                        onClick = {
                            RemoteFleetRepository.regenerateKeyPair(context)
                            onShowMessage(context.getString(R.string.fleet_pubkey_regenerated))
                        },
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.fleet_regen_key))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val newCfg = state.config.copy(
                        enabled = enabled,
                        fleetServerUrl = serverUrl,
                        deviceToken = deviceToken,
                        sshHost = sshHost,
                        sshPort = sshPort.toIntOrNull() ?: 22,
                        sshUser = sshUser,
                        remoteWebPort = remoteWebPort.toIntOrNull() ?: state.config.remoteWebPort,
                        remoteAdbPort = remoteAdbPort.toIntOrNull() ?: state.config.remoteAdbPort,
                    )
                    RemoteFleetRepository.updateConfig(context, newCfg)
                    onDismissRequest()
                },
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.fleet_save_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}
