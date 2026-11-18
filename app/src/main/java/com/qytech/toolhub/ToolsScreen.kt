package com.qytech.toolhub

import android.annotation.SuppressLint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qytech.toolhub.data.AudioToneGenerator
import com.qytech.toolhub.data.HardwareRepository
import com.qytech.toolhub.data.StressTestRepository
import com.qytech.toolhub.data.WebReportServer
import com.qytech.toolhub.ui.components.AotDialog
import com.qytech.toolhub.ui.components.AudioToneDialog
import com.qytech.toolhub.ui.components.CrashDiagnosticsDialog
import com.qytech.toolhub.ui.components.NetworkDiagnosticsDialog
import com.qytech.toolhub.ui.components.QrCodeDialog
import com.qytech.toolhub.ui.components.ScreenTouchTestDialog
import com.qytech.toolhub.ui.components.StatusPill
import com.qytech.toolhub.ui.components.StressTestDialog
import com.qytech.toolhub.ui.components.SysPropBrowserDialog
import com.qytech.toolhub.ui.components.WebServerDialog
import com.qytech.toolhub.ui.theme.semanticColors

@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolsScreen(
    deviceInfoViewModel: DeviceInfoViewModel,
    otgViewModel: OtgModeViewModel,
    hardwareViewModel: HardwareViewModel,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showStressDialog by remember { mutableStateOf(false) }
    var showAotDialog by remember { mutableStateOf(false) }
    var showSysPropDialog by remember { mutableStateOf(false) }
    var showScreenTestDialog by remember { mutableStateOf(false) }
    var showNetworkDiagDialog by remember { mutableStateOf(false) }
    var showCrashDiagDialog by remember { mutableStateOf(false) }
    var showWebServerDialog by remember { mutableStateOf(false) }
    var showAudioToneDialog by remember { mutableStateOf(false) }
    var showQrCodeDialog by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val cols = when {
            this@BoxWithConstraints.maxWidth >= 880.dp -> 3
            this@BoxWithConstraints.maxWidth >= 560.dp -> 2
            else -> 1
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 84.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(min = 240.dp)
                        .weight(1f, fill = true),
                ) {
                    Text(
                        text = stringResource(R.string.tools_header_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stringResource(R.string.tools_header_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OutlinedButton(
                    onClick = {
                        val snapshot = HardwareRepository.buildDiagnosticSnapshot(
                            otgState = otgViewModel.uiState.value,
                            deviceState = deviceInfoViewModel.uiState.value,
                            hardwareState = hardwareViewModel.uiState.value,
                            context = context,
                        )
                        com.qytech.toolhub.ui.components.copyTextToClipboard(
                            context,
                            context.getString(R.string.device_snapshot_label),
                            snapshot,
                            onShowMessage,
                        )
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(38.dp),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.device_copy_snapshot), style = MaterialTheme.typography.labelLarge)
                }
            }

            val items: List<@Composable (Modifier) -> Unit> = listOf(
                { mod ->
                    ToolLaunchCard(
                        title = stringResource(R.string.action_stress_test),
                        description = stringResource(R.string.tools_desc_stress),
                        icon = Icons.Default.Bolt,
                        tint = MaterialTheme.semanticColors.danger,
                        badge = { StressToolBadge() },
                        onClick = { showStressDialog = true },
                        modifier = mod,
                    )
                },
                { mod ->
                    ToolLaunchCard(
                        title = stringResource(R.string.device_aot_short),
                        description = stringResource(R.string.tools_desc_aot),
                        icon = Icons.Default.Speed,
                        tint = MaterialTheme.colorScheme.primary,
                        onClick = { showAotDialog = true },
                        modifier = mod,
                    )
                },
                { mod ->
                    ToolLaunchCard(
                        title = stringResource(R.string.home_btn_sysprop),
                        description = stringResource(R.string.tools_desc_sysprop),
                        icon = Icons.Default.SettingsSuggest,
                        tint = MaterialTheme.colorScheme.tertiary,
                        onClick = { showSysPropDialog = true },
                        modifier = mod,
                    )
                },
                { mod ->
                    ToolLaunchCard(
                        title = stringResource(R.string.home_btn_screen_tp),
                        description = stringResource(R.string.tools_desc_screen),
                        icon = Icons.Default.TouchApp,
                        tint = MaterialTheme.colorScheme.secondary,
                        onClick = { showScreenTestDialog = true },
                        modifier = mod,
                    )
                },
                { mod ->
                    ToolLaunchCard(
                        title = stringResource(R.string.device_network_diag),
                        description = stringResource(R.string.tools_desc_net),
                        icon = Icons.Default.Lan,
                        tint = MaterialTheme.colorScheme.primary,
                        onClick = { showNetworkDiagDialog = true },
                        modifier = mod,
                    )
                },
                { mod ->
                    ToolLaunchCard(
                        title = stringResource(R.string.home_crash_trace),
                        description = stringResource(R.string.tools_desc_crash),
                        icon = Icons.Default.BugReport,
                        tint = MaterialTheme.semanticColors.warning,
                        onClick = { showCrashDiagDialog = true },
                        modifier = mod,
                    )
                },
                { mod ->
                    ToolLaunchCard(
                        title = stringResource(R.string.device_web_short),
                        description = stringResource(R.string.tools_desc_web),
                        icon = Icons.Default.Language,
                        tint = MaterialTheme.semanticColors.success,
                        badge = { WebToolBadge() },
                        onClick = { showWebServerDialog = true },
                        modifier = mod,
                    )
                },
                { mod ->
                    ToolLaunchCard(
                        title = stringResource(R.string.tone_btn_open),
                        description = stringResource(R.string.tools_desc_audio),
                        icon = Icons.Default.GraphicEq,
                        tint = MaterialTheme.colorScheme.tertiary,
                        badge = { AudioToneBadge() },
                        onClick = { showAudioToneDialog = true },
                        modifier = mod,
                    )
                },
                { mod ->
                    ToolLaunchCard(
                        title = stringResource(R.string.home_adb_qr),
                        description = stringResource(R.string.tools_desc_qr),
                        icon = Icons.Default.QrCode2,
                        tint = MaterialTheme.colorScheme.secondary,
                        onClick = { showQrCodeDialog = true },
                        modifier = mod,
                    )
                },
            )

            items.chunked(cols).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    rowItems.forEach { renderItem ->
                        renderItem(Modifier.weight(1f))
                    }
                    repeat(cols - rowItems.size) {
                        Box(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    if (showStressDialog) {
        StressTestDialog(onDismissRequest = { showStressDialog = false })
    }
    if (showAotDialog) {
        AotDialog(onDismissRequest = { showAotDialog = false })
    }
    if (showSysPropDialog) {
        SysPropBrowserDialog(
            onCopy = { label, value ->
                com.qytech.toolhub.ui.components.copyTextToClipboard(context, label, value, onShowMessage)
            },
            onDismissRequest = { showSysPropDialog = false },
        )
    }
    if (showScreenTestDialog) {
        ScreenTouchTestDialog(onDismissRequest = { showScreenTestDialog = false })
    }
    if (showNetworkDiagDialog) {
        NetworkDiagnosticsDialog(onDismissRequest = { showNetworkDiagDialog = false })
    }
    if (showCrashDiagDialog) {
        CrashDiagnosticsDialog(onDismissRequest = { showCrashDiagDialog = false })
    }
    if (showWebServerDialog) {
        val primaryIp = deviceInfoViewModel.uiState.value.primaryIpAddress
        WebServerDialog(
            ipAddress = primaryIp,
            onDismissRequest = { showWebServerDialog = false },
        )
    }
    if (showAudioToneDialog) {
        AudioToneDialog(onDismissRequest = { showAudioToneDialog = false })
    }
    if (showQrCodeDialog) {
        val ip = deviceInfoViewModel.uiState.value.primaryIpAddress ?: "127.0.0.1"
        val tcpPort = otgViewModel.uiState.value.usbProperties.adbTcpPort.ifBlank { "5555" }
        QrCodeDialog(
            title = stringResource(R.string.home_qr_title),
            content = "adb connect $ip:$tcpPort",
            hint = stringResource(R.string.home_qr_hint),
            onDismissRequest = { showQrCodeDialog = false },
        )
    }
}

@Composable
private fun ToolLaunchCard(
    title: String,
    description: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: (@Composable () -> Unit)? = null,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = tint.copy(alpha = 0.14f),
                    modifier = Modifier.size(38.dp),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                badge?.invoke()
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StressToolBadge() {
    val stressState by StressTestRepository.state.collectAsState()
    if (!stressState.isRunning) return
    StatusPill(
        text = stringResource(R.string.status_stress_running_fmt, stressState.elapsedSeconds),
        color = MaterialTheme.semanticColors.danger,
        compact = true,
    )
}

@Composable
private fun WebToolBadge() {
    val webState by WebReportServer.state.collectAsState()
    if (!webState.isRunning) return
    StatusPill(
        text = ":${webState.port}",
        color = MaterialTheme.semanticColors.success,
        compact = true,
    )
}

@Composable
private fun AudioToneBadge() {
    val toneState by AudioToneGenerator.state.collectAsState()
    if (!toneState.isPlaying) return
    StatusPill(
        text = "${toneState.sampleRateHz / 1000}kHz",
        color = MaterialTheme.colorScheme.tertiary,
        compact = true,
    )
}
