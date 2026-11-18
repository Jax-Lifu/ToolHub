package com.qytech.toolhub

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qytech.toolhub.data.AppLanguage
import com.qytech.toolhub.data.AppLanguageManager
import com.qytech.toolhub.data.HardwareRepository
import com.qytech.toolhub.data.OtgMode
import com.qytech.toolhub.data.StressTestRepository
import com.qytech.toolhub.data.TelemetryRepository
import com.qytech.toolhub.data.WebReportServer
import com.qytech.toolhub.ui.components.AotDialog
import com.qytech.toolhub.ui.components.AudioToneDialog
import com.qytech.toolhub.ui.components.CrashDiagnosticsDialog
import com.qytech.toolhub.ui.components.NetworkDiagnosticsDialog
import com.qytech.toolhub.ui.components.QrCodeDialog
import com.qytech.toolhub.ui.components.ScreenTouchTestDialog
import com.qytech.toolhub.ui.components.SectionCard
import com.qytech.toolhub.ui.components.StatusPill
import com.qytech.toolhub.ui.components.StressTestDialog
import com.qytech.toolhub.ui.components.SysPropBrowserDialog
import com.qytech.toolhub.ui.components.WebServerDialog
import com.qytech.toolhub.ui.components.copyTextToClipboard
import com.qytech.toolhub.ui.components.formatTimeMillis
import com.qytech.toolhub.ui.theme.semanticColors

@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    otgViewModel: OtgModeViewModel,
    deviceInfoViewModel: DeviceInfoViewModel,
    hardwareViewModel: HardwareViewModel,
    monitorViewModel: MonitorViewModel,
    onNavigateToOtg: () -> Unit,
    onNavigateToPerf: () -> Unit,
    onNavigateToThermal: () -> Unit,
    onNavigateToAudio: () -> Unit,
    onNavigateToDeviceInfo: () -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val otgState by otgViewModel.uiState.collectAsState()
    val deviceState by deviceInfoViewModel.uiState.collectAsState()
    val hardwareState by hardwareViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val semantic = MaterialTheme.semanticColors
    var showRebootDialog by remember { mutableStateOf(false) }
    var showStressDialog by remember { mutableStateOf(false) }
    var showQrCodeDialog by remember { mutableStateOf(false) }
    var qrCodeContent by remember { mutableStateOf("") }
    var showNetworkDiagDialog by remember { mutableStateOf(false) }
    var showCrashDiagDialog by remember { mutableStateOf(false) }
    var showWebServerDialog by remember { mutableStateOf(false) }
    var showAotDialog by remember { mutableStateOf(false) }
    var showSysPropDialog by remember { mutableStateOf(false) }
    var showScreenTestDialog by remember { mutableStateOf(false) }
    var showAudioToneDialog by remember { mutableStateOf(false) }
    var showLangMenu by remember { mutableStateOf(false) }
    val webServerState by WebReportServer.state.collectAsState()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 84.dp),
    ) {
        val isWide = this@BoxWithConstraints.maxWidth >= 720.dp
        val basic = deviceState.basicInfo

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // 顶部设备与工具栏
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f, fill = true)) {
                    Text(
                        text = stringResource(R.string.home_header_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    val versionTag = "v${BuildConfig.VERSION_NAME} - ${BuildConfig.BUILD_TYPE}"
                    Text(
                        text = if (basic != null) {
                            "$versionTag · ${basic.model} · Android ${basic.androidVersion} (API ${basic.sdkInt}) · ${basic.socPlatform}"
                        } else {
                            "$versionTag · ${stringResource(R.string.home_loading_device)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HomeStressBadgeButton(onClick = { showStressDialog = true })
                    if (basic != null) {
                        StatusPill(
                            text = basic.uidSummary,
                            color = if (basic.uidSummary.contains("1000")) semantic.success else semantic.warning,
                            icon = if (basic.uidSummary.contains("1000")) Icons.Default.CheckCircle else Icons.Default.Warning,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            val snapshot = HardwareRepository.buildDiagnosticSnapshot(
                                otgState = otgState,
                                deviceState = deviceState,
                                hardwareState = hardwareState,
                                context = context,
                            )
                            copyTextToClipboard(context, "Snapshot", snapshot, onShowMessage)
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.home_copy_snapshot))
                    }
                    OutlinedButton(
                        onClick = { showCrashDiagDialog = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                    ) {
                        Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.home_crash_trace))
                    }
                    OutlinedButton(
                        onClick = { showRebootDialog = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = null,
                            tint = semantic.danger,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.action_reboot), color = semantic.danger)
                    }
                    Box {
                        val currentLang = remember { AppLanguageManager.getSavedLanguage(context) }
                        IconButton(onClick = { showLangMenu = true }) {
                            Icon(Icons.Default.Translate, contentDescription = stringResource(R.string.action_language))
                        }
                        DropdownMenu(
                            expanded = showLangMenu,
                            onDismissRequest = { showLangMenu = false },
                        ) {
                            AppLanguage.entries.forEach { lang ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = if (lang == currentLang) "✓ ${lang.displayName}" else lang.displayName,
                                            fontWeight = if (lang == currentLang) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    },
                                    onClick = {
                                        showLangMenu = false
                                        (context as? Activity)?.let { act ->
                                            AppLanguageManager.setLanguage(act, lang)
                                        }
                                    },
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = {
                            otgViewModel.refreshAll()
                            deviceInfoViewModel.refresh()
                            hardwareViewModel.refresh()
                        },
                        enabled = !otgState.isScanning && !otgState.isApplyingBatch,
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.cd_refresh_all))
                    }
                }
            }

            // 核心三态控制台
            if (isWide) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    OtgControlTile(
                        otgState = otgState,
                        onSetAllMode = otgViewModel::setAllControllableMode,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    AdbControlTile(
                        otgState = otgState,
                        onToggleAdb = otgViewModel::toggleAdbEnabled,
                        onToggleWifiAdb = otgViewModel::toggleWifiAdb,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    BootAndStayAwakeTile(
                        otgState = otgState,
                        onApplyBootNow = otgViewModel::applyBootPolicyNow,
                        onToggleStayAwake = otgViewModel::toggleStayAwake,
                        onNavigateToOtg = onNavigateToOtg,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            } else {
                OtgControlTile(
                    otgState = otgState,
                    onSetAllMode = otgViewModel::setAllControllableMode,
                )
                AdbControlTile(
                    otgState = otgState,
                    onToggleAdb = otgViewModel::toggleAdbEnabled,
                    onToggleWifiAdb = otgViewModel::toggleWifiAdb,
                )
                BootAndStayAwakeTile(
                    otgState = otgState,
                    onApplyBootNow = otgViewModel::applyBootPolicyNow,
                    onToggleStayAwake = otgViewModel::toggleStayAwake,
                    onNavigateToOtg = onNavigateToOtg,
                )
            }

            // 下半区双栏：开发机体检 + 网络/音频/外设摘要
            if (isWide) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    DevHealthCheckCard(
                        otgState = otgState,
                        modifier = Modifier.weight(1f),
                    )
                    NetworkAndHardwareQuickCard(
                        otgState = otgState,
                        deviceState = deviceState,
                        hardwareState = hardwareState,
                        monitorViewModel = monitorViewModel,
                        onCopy = { label, value ->
                            copyTextToClipboard(context, label, value, onShowMessage)
                        },
                        onOpenSystemSettings = { openSystemSettings(context, onShowMessage) },
                        onOpenDeveloperSettings = { openDeveloperSettings(context, onShowMessage) },
                        onNavigateToPerf = onNavigateToPerf,
                        onNavigateToThermal = onNavigateToThermal,
                        onNavigateToAudio = onNavigateToAudio,
                        onNavigateToDeviceInfo = onNavigateToDeviceInfo,
                        onShowQrCode = { content ->
                            qrCodeContent = content
                            showQrCodeDialog = true
                        },
                        onShowNetworkDiag = { showNetworkDiagDialog = true },
                        onShowCrashDiagnostics = { showCrashDiagDialog = true },
                        onShowWebServer = { showWebServerDialog = true },
                        onShowAot = { showAotDialog = true },
                        onShowSysProp = { showSysPropDialog = true },
                        onShowScreenTest = { showScreenTestDialog = true },
                        onShowAudioTone = { showAudioToneDialog = true },
                        isWebServerRunning = webServerState.isRunning,
                        webServerPort = webServerState.port,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                DevHealthCheckCard(otgState = otgState)
                NetworkAndHardwareQuickCard(
                    otgState = otgState,
                    deviceState = deviceState,
                    hardwareState = hardwareState,
                    monitorViewModel = monitorViewModel,
                    onCopy = { label, value ->
                        copyTextToClipboard(context, label, value, onShowMessage)
                    },
                    onOpenSystemSettings = { openSystemSettings(context, onShowMessage) },
                    onOpenDeveloperSettings = { openDeveloperSettings(context, onShowMessage) },
                    onNavigateToPerf = onNavigateToPerf,
                    onNavigateToThermal = onNavigateToThermal,
                    onNavigateToAudio = onNavigateToAudio,
                    onNavigateToDeviceInfo = onNavigateToDeviceInfo,
                    onShowQrCode = { content ->
                        qrCodeContent = content
                        showQrCodeDialog = true
                    },
                    onShowNetworkDiag = { showNetworkDiagDialog = true },
                    onShowCrashDiagnostics = { showCrashDiagDialog = true },
                    onShowWebServer = { showWebServerDialog = true },
                    onShowAot = { showAotDialog = true },
                    onShowSysProp = { showSysPropDialog = true },
                    onShowScreenTest = { showScreenTestDialog = true },
                    onShowAudioTone = { showAudioToneDialog = true },
                    isWebServerRunning = webServerState.isRunning,
                    webServerPort = webServerState.port,
                )
            }
        }
    }

    if (showRebootDialog) {
        AlertDialog(
            onDismissRequest = { showRebootDialog = false },
            title = { Text(stringResource(R.string.home_reboot_confirm_title)) },
            text = {
                Text(
                    text = if (otgState.bootSettings.autoOtgOnBoot) {
                        stringResource(R.string.home_reboot_msg_auto)
                    } else {
                        stringResource(R.string.home_reboot_msg_manual)
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRebootDialog = false
                        otgViewModel.rebootDevice()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = semantic.danger),
                ) {
                    Text(stringResource(R.string.home_reboot_confirm_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebootDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showQrCodeDialog) {
        QrCodeDialog(
            title = stringResource(R.string.home_qr_title),
            content = qrCodeContent,
            hint = stringResource(R.string.home_qr_hint),
            onDismissRequest = { showQrCodeDialog = false },
        )
    }

    if (showNetworkDiagDialog) {
        NetworkDiagnosticsDialog(
            onDismissRequest = { showNetworkDiagDialog = false },
        )
    }

    if (showStressDialog) {
        StressTestDialog(
            onDismissRequest = { showStressDialog = false },
        )
    }

    if (showCrashDiagDialog) {
        CrashDiagnosticsDialog(
            onDismissRequest = { showCrashDiagDialog = false },
        )
    }

    if (showWebServerDialog) {
        WebServerDialog(
            ipAddress = deviceState.primaryIpAddress,
            onDismissRequest = { showWebServerDialog = false },
        )
    }

    if (showAotDialog) {
        AotDialog(
            onDismissRequest = { showAotDialog = false },
        )
    }

    if (showSysPropDialog) {
        SysPropBrowserDialog(
            onCopy = { label, value ->
                copyTextToClipboard(context, label, value, onShowMessage)
            },
            onDismissRequest = { showSysPropDialog = false },
        )
    }

    if (showScreenTestDialog) {
        ScreenTouchTestDialog(
            onDismissRequest = { showScreenTestDialog = false },
        )
    }

    if (showAudioToneDialog) {
        AudioToneDialog(
            onDismissRequest = { showAudioToneDialog = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OtgControlTile(
    otgState: OtgModeUiState,
    onSetAllMode: (OtgMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = MaterialTheme.semanticColors
    val controllable = otgState.controllableNodes
    val allOtg = controllable.isNotEmpty() && controllable.all { it.currentMode == OtgMode.OTG }
    val allHost = controllable.isNotEmpty() && controllable.all { it.currentMode == OtgMode.HOST }

    val (modeColor, modeIcon) = when {
        allOtg -> semantic.success to Icons.Default.CheckCircle
        allHost -> semantic.warning to Icons.Default.Usb
        controllable.isEmpty() -> semantic.danger to Icons.Default.ErrorOutline
        else -> semantic.info to Icons.Default.Usb
    }

    DashboardTileCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.home_otg_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            StatusPill(
                text = otgState.aggregateModeLabel(LocalContext.current),
                color = modeColor,
                icon = modeIcon,
            )
        }

        Text(
            text = stringResource(R.string.home_otg_nodes_fmt, controllable.size, otgState.readOnlyNodes.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.home_usb_devices_fmt, otgState.connectedUsbDevices.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.weight(1f))

        val modes = listOf(OtgMode.OTG, OtgMode.HOST)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                val selected = (mode == OtgMode.OTG && allOtg) || (mode == OtgMode.HOST && allHost)
                SegmentedButton(
                    selected = selected,
                    onClick = { if (!selected && !otgState.isApplyingBatch) onSetAllMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    enabled = !otgState.isApplyingBatch && controllable.isNotEmpty(),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    icon = {
                        if (otgState.isApplyingBatch && !selected) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            SegmentedButtonDefaults.Icon(active = selected)
                        }
                    },
                ) {
                    Text(mode.label, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun AdbControlTile(
    otgState: OtgModeUiState,
    onToggleAdb: (Boolean) -> Unit,
    onToggleWifiAdb: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = MaterialTheme.semanticColors
    DashboardTileCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.home_adb_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            StatusPill(
                text = if (otgState.adbEnabled) "ADB=1" else "ADB=0",
                color = if (otgState.adbEnabled) semantic.success else semantic.danger,
                icon = if (otgState.adbEnabled) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.home_adb_switch),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            Switch(
                checked = otgState.adbEnabled,
                onCheckedChange = onToggleAdb,
                enabled = !otgState.isAdbUpdating,
            )
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
            thickness = 0.6.dp,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_wifi_adb_switch),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "sys.usb.config: ${otgState.usbProperties.usbConfig.ifBlank { "-" }}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = otgState.wifiAdbEnabled,
                onCheckedChange = onToggleWifiAdb,
                enabled = !otgState.isWifiAdbUpdating,
            )
        }
    }
}

@Composable
private fun BootAndStayAwakeTile(
    otgState: OtgModeUiState,
    onApplyBootNow: () -> Unit,
    onToggleStayAwake: (Boolean) -> Unit,
    onNavigateToOtg: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = MaterialTheme.semanticColors
    val bootActive = otgState.bootSettings.autoOtgOnBoot || otgState.bootSettings.autoAdbOnBoot
    val lastRecord = otgState.bootHistory.firstOrNull()

    DashboardTileCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.home_boot_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            StatusPill(
                text = if (bootActive) stringResource(R.string.home_boot_on) else stringResource(R.string.home_boot_off),
                color = if (bootActive) semantic.info else MaterialTheme.colorScheme.onSurfaceVariant,
                icon = Icons.Default.PowerSettingsNew,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_stay_awake),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (lastRecord != null) {
                        stringResource(R.string.home_last_boot_fmt, formatTimeMillis(lastRecord.timestampMillis))
                    } else {
                        stringResource(R.string.home_stay_awake_sub)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = otgState.stayAwakeEnabled,
                onCheckedChange = onToggleStayAwake,
                enabled = !otgState.isStayAwakeUpdating,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = onApplyBootNow,
                enabled = !otgState.isApplyingBatch,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier.defaultMinSize(minHeight = 44.dp),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.home_verify_boot))
            }
            TextButton(
                onClick = onNavigateToOtg,
                modifier = Modifier.defaultMinSize(minHeight = 44.dp),
            ) {
                Text(stringResource(R.string.home_view_logs))
            }
        }
    }
}

@Composable
private fun DevHealthCheckCard(
    otgState: OtgModeUiState,
    modifier: Modifier = Modifier,
) {
    val semantic = MaterialTheme.semanticColors
    val checks = otgState.healthChecks
    val allPassed = checks.isNotEmpty() && checks.all { it.passed }

    SectionCard(
        title = stringResource(R.string.home_health_title),
        subtitle = stringResource(R.string.home_health_sub),
        icon = Icons.AutoMirrored.Filled.FactCheck,
        badge = {
            if (checks.isNotEmpty()) {
                StatusPill(
                    text = if (allPassed) stringResource(R.string.home_all_ready) else stringResource(R.string.home_checks_passed_fmt, checks.count { it.passed }, checks.size),
                    color = if (allPassed) semantic.success else semantic.warning,
                    icon = if (allPassed) Icons.Default.CheckCircle else Icons.Default.Warning,
                )
            }
        },
        modifier = modifier,
    ) {
        checks.forEachIndexed { idx, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = if (item.passed) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (item.passed) semantic.success else semantic.warning,
                    modifier = Modifier.size(18.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = item.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (idx < checks.lastIndex) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    thickness = 0.6.dp,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NetworkAndHardwareQuickCard(
    otgState: OtgModeUiState,
    deviceState: DeviceInfoUiState,
    hardwareState: HardwareUiState,
    monitorViewModel: MonitorViewModel,
    onCopy: (String, String) -> Unit,
    onOpenSystemSettings: () -> Unit,
    onOpenDeveloperSettings: () -> Unit,
    onNavigateToPerf: () -> Unit,
    onNavigateToThermal: () -> Unit,
    onNavigateToAudio: () -> Unit,
    onNavigateToDeviceInfo: () -> Unit,
    onShowQrCode: (String) -> Unit,
    onShowNetworkDiag: () -> Unit,
    onShowCrashDiagnostics: () -> Unit,
    onShowWebServer: () -> Unit,
    onShowAot: () -> Unit,
    onShowSysProp: () -> Unit,
    onShowScreenTest: () -> Unit,
    onShowAudioTone: () -> Unit,
    isWebServerRunning: Boolean,
    webServerPort: Int,
    modifier: Modifier = Modifier,
) {
    val ip = deviceState.primaryIpAddress
    val tcpPort = otgState.usbProperties.adbTcpPort.ifBlank { "5555" }
    val activeAudioCards = hardwareState.alsaCards.count { it.hasActiveStream }
    val mainTemp = hardwareState.thermalZones.firstOrNull { it.celsiusApprox != null }

    SectionCard(
        title = stringResource(R.string.home_quick_title),
        subtitle = deviceState.primaryIpSummary,
        icon = Icons.Default.Lan,
        modifier = modifier,
        headerAction = {
            TextButton(
                onClick = onNavigateToDeviceInfo,
                modifier = Modifier.defaultMinSize(minHeight = 44.dp),
            ) {
                Text(stringResource(R.string.home_device_detail))
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        },
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val ipAddrLabel = stringResource(R.string.label_ip_address)
            val adbCmdLabel = stringResource(R.string.label_adb_cmd)
            val noIpText = stringResource(R.string.val_no_ip)
            if (!ip.isNullOrBlank() && ip != noIpText) {
                OutlinedButton(
                    onClick = { onCopy(ipAddrLabel, ip) },
                    modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.home_copy_ip_fmt, ip), fontFamily = FontFamily.Monospace)
                }
                OutlinedButton(
                    onClick = { onCopy(adbCmdLabel, "adb connect $ip:$tcpPort") },
                    modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("adb connect :$tcpPort", fontFamily = FontFamily.Monospace)
                }
                OutlinedButton(
                    onClick = { onShowQrCode("adb connect $ip:$tcpPort") },
                    modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                ) {
                    Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.home_adb_qr))
                }
            }
            OutlinedButton(
                onClick = onShowNetworkDiag,
                modifier = Modifier.defaultMinSize(minHeight = 44.dp),
            ) {
                Icon(Icons.Default.Lan, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.home_network_diag))
            }
            OutlinedButton(
                onClick = onShowCrashDiagnostics,
                modifier = Modifier.defaultMinSize(minHeight = 44.dp),
            ) {
                Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.home_crash_trace_full))
            }
            OutlinedButton(
                onClick = onShowWebServer,
                modifier = Modifier.defaultMinSize(minHeight = 44.dp),
            ) {
                Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isWebServerRunning) stringResource(R.string.home_web_report_port_fmt, webServerPort) else stringResource(R.string.home_web_report))
            }
            OutlinedButton(
                onClick = onShowAot,
                modifier = Modifier.defaultMinSize(minHeight = 44.dp),
            ) {
                Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.home_aot_opt))
            }
            OutlinedButton(
                onClick = onShowSysProp,
                modifier = Modifier.defaultMinSize(minHeight = 44.dp),
            ) {
                Icon(Icons.Default.SettingsSuggest, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.home_btn_sysprop))
            }
            OutlinedButton(
                onClick = onShowScreenTest,
                modifier = Modifier.defaultMinSize(minHeight = 44.dp),
            ) {
                Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.home_btn_screen_tp))
            }
            OutlinedButton(
                onClick = onShowAudioTone,
                modifier = Modifier.defaultMinSize(minHeight = 44.dp),
            ) {
                Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.tone_btn_open))
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        HomeLivePerfSummaryText(monitorViewModel = monitorViewModel)
        val audioSummary = stringResource(
            R.string.home_audio_summary_fmt,
            hardwareState.alsaCards.size,
            activeAudioCards,
            mainTemp?.let { " · ${it.type} ${it.formattedTemp}" } ?: "",
        )
        Text(
            text = audioSummary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = onNavigateToPerf,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                modifier = Modifier.defaultMinSize(minHeight = 44.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ShowChart, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.home_btn_perf))
            }
            FilledTonalButton(
                onClick = onNavigateToThermal,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.defaultMinSize(minHeight = 44.dp),
            ) {
                Icon(Icons.Default.Thermostat, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.home_btn_thermal))
            }
            FilledTonalButton(
                onClick = onNavigateToAudio,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.defaultMinSize(minHeight = 44.dp),
            ) {
                Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.home_btn_audio))
            }
            OutlinedButton(
                onClick = onOpenDeveloperSettings,
                modifier = Modifier.defaultMinSize(minHeight = 44.dp),
            ) {
                Icon(Icons.Default.DeveloperMode, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.home_btn_dev_opts))
            }
            OutlinedButton(
                onClick = onOpenSystemSettings,
                modifier = Modifier.defaultMinSize(minHeight = 44.dp),
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.home_btn_sys_settings))
            }
            OutlinedButton(
                onClick = onNavigateToDeviceInfo,
                modifier = Modifier.defaultMinSize(minHeight = 44.dp),
            ) {
                Icon(Icons.Default.Devices, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.home_btn_device_specs))
            }
        }
    }
}

@Composable
private fun HomeStressBadgeButton(onClick: () -> Unit) {
    val stressState by StressTestRepository.state.collectAsState()
    if (!stressState.isRunning) return
    val semantic = MaterialTheme.semanticColors
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = semantic.danger),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        modifier = Modifier.defaultMinSize(minHeight = 44.dp),
    ) {
        Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(stringResource(R.string.status_stress_running_fmt, stressState.elapsedSeconds))
    }
}

@Composable
private fun HomeLivePerfSummaryText(monitorViewModel: MonitorViewModel) {
    val monitorState by monitorViewModel.uiState.collectAsState()
    val latestPoint = monitorState.latestPoint
    if (latestPoint != null) {
        val gpuPart = if (latestPoint.gpuAvailable) " · GPU %.0f%%".format(java.util.Locale.US, latestPoint.gpuPercent) else ""
        val netDown = TelemetryRepository.formatBytesPerSec(latestPoint.rxBytesPerSec)
        val perfSummary = "CPU %.0f%%%s · RAM %.0f%% · ↓%s".format(
            java.util.Locale.US,
            latestPoint.cpuPercent,
            gpuPart,
            latestPoint.memPercent,
            netDown,
        )
        Text(
            text = perfSummary,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun DashboardTileCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
internal fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

private fun openSystemSettings(context: Context, onShowMessage: (String) -> Unit) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        onShowMessage(context.getString(R.string.home_open_settings_err_fmt, it.message ?: ""))
    }
}

private fun openDeveloperSettings(context: Context, onShowMessage: (String) -> Unit) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        openSystemSettings(context, onShowMessage)
    }
}
