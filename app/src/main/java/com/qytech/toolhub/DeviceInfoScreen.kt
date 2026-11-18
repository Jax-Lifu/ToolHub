package com.qytech.toolhub

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qytech.toolhub.data.DeviceBasicInfo
import com.qytech.toolhub.data.DeviceRuntimeInfo
import com.qytech.toolhub.data.EthernetStatusInfo
import com.qytech.toolhub.data.HardwareRepository
import com.qytech.toolhub.data.WifiStatusInfo
import com.qytech.toolhub.ui.components.AotDialog
import com.qytech.toolhub.ui.components.CopyableInfoRow
import com.qytech.toolhub.ui.components.CrashDiagnosticsDialog
import com.qytech.toolhub.ui.components.NetworkDiagnosticsDialog
import com.qytech.toolhub.ui.components.QrCodeDialog
import com.qytech.toolhub.ui.components.ScreenTouchTestDialog
import com.qytech.toolhub.ui.components.SectionCard
import com.qytech.toolhub.ui.components.StatusPill
import com.qytech.toolhub.ui.components.SysPropBrowserDialog
import com.qytech.toolhub.ui.components.WebServerDialog
import com.qytech.toolhub.ui.components.copyTextToClipboard
import com.qytech.toolhub.ui.theme.semanticColors

@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceInfoScreen(
    viewModel: DeviceInfoViewModel,
    otgViewModel: OtgModeViewModel,
    hardwareViewModel: HardwareViewModel,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showQrCodeDialog by remember { mutableStateOf(false) }
    val onCopy: (String, String) -> Unit = { label, value ->
        copyTextToClipboard(context, label, value, onShowMessage)
    }

    if (uiState.isLoading && uiState.basicInfo == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val singleScreen3Col = this@BoxWithConstraints.maxWidth >= 880.dp && this@BoxWithConstraints.maxHeight >= 460.dp
        val isTwoCol = !singleScreen3Col && this@BoxWithConstraints.maxWidth >= 720.dp

        val containerModifier = if (singleScreen3Col) {
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        } else {
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 84.dp)
        }

        Column(
            modifier = containerModifier,
            verticalArrangement = Arrangement.spacedBy(if (singleScreen3Col) 8.dp else 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.widthIn(min = 200.dp, max = 300.dp),
                ) {
                    Text(
                        text = stringResource(R.string.device_header_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stringResource(R.string.device_header_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                FlowRow(
                    modifier = Modifier.weight(1.3f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val primaryIp = uiState.primaryIpAddress
                    val noIpText = stringResource(R.string.val_no_ip)
                    val btnPad = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    val btnH = 38.dp
                    val btnStyle = MaterialTheme.typography.labelLarge
                    if (!primaryIp.isNullOrBlank() && primaryIp != noIpText) {
                        OutlinedButton(
                            onClick = { showQrCodeDialog = true },
                            contentPadding = btnPad,
                            modifier = Modifier.height(btnH),
                        ) {
                            Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.home_adb_qr), style = btnStyle)
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            val snapshot = HardwareRepository.buildDiagnosticSnapshot(
                                otgState = otgViewModel.uiState.value,
                                deviceState = uiState,
                                hardwareState = hardwareViewModel.uiState.value,
                                context = context,
                            )
                            onCopy(context.getString(R.string.device_snapshot_label), snapshot)
                        },
                        contentPadding = btnPad,
                        modifier = Modifier.height(btnH),
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.device_copy_snapshot), style = btnStyle)
                    }
                    IconButton(
                        onClick = { viewModel.refresh() },
                        modifier = Modifier.size(btnH),
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.cd_refresh_device),
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
            }

            if (singleScreen3Col) {
                val wifiConnected = uiState.wifiInfo?.isConnected == true
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.weight(1.04f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        uiState.basicInfo?.let {
                            BasicInfoCard(info = it, compact = true, onCopy = onCopy)
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        uiState.wifiInfo?.let {
                            WifiInfoCard(info = it, compact = true, onCopy = onCopy)
                        }
                        if (!wifiConnected) {
                            if (uiState.ethernetList.isNotEmpty()) {
                                EthernetInfoCard(
                                    info = uiState.ethernetList.first(),
                                    compact = true,
                                    onCopy = onCopy,
                                )
                            } else {
                                EthernetInfoCard(info = null, compact = true, onCopy = onCopy)
                            }
                        }
                    }
                    Column(
                        modifier = Modifier.weight(0.96f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        uiState.runtimeInfo?.let {
                            RuntimeInfoCard(info = it, compact = true, onCopy = onCopy)
                        }
                        if (wifiConnected) {
                            if (uiState.ethernetList.isNotEmpty()) {
                                EthernetInfoCard(
                                    info = uiState.ethernetList.first(),
                                    compact = true,
                                    onCopy = onCopy,
                                )
                            } else {
                                EthernetInfoCard(info = null, compact = true, onCopy = onCopy)
                            }
                        }
                    }
                }
            } else if (isTwoCol) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        uiState.basicInfo?.let {
                            BasicInfoCard(
                                it,
                                compact = false,
                                onCopy = onCopy
                            )
                        }
                        uiState.runtimeInfo?.let {
                            RuntimeInfoCard(
                                it,
                                compact = false,
                                onCopy = onCopy
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        uiState.wifiInfo?.let { WifiInfoCard(it, compact = false, onCopy = onCopy) }
                        if (uiState.ethernetList.isNotEmpty()) {
                            uiState.ethernetList.forEach {
                                EthernetInfoCard(
                                    it,
                                    compact = false,
                                    onCopy = onCopy
                                )
                            }
                        } else {
                            EthernetInfoCard(null, compact = false, onCopy = onCopy)
                        }
                    }
                }
            } else {
                uiState.basicInfo?.let { BasicInfoCard(it, compact = false, onCopy = onCopy) }
                uiState.runtimeInfo?.let { RuntimeInfoCard(it, compact = false, onCopy = onCopy) }
                uiState.wifiInfo?.let { WifiInfoCard(it, compact = false, onCopy = onCopy) }
                if (uiState.ethernetList.isNotEmpty()) {
                    uiState.ethernetList.forEach {
                        EthernetInfoCard(
                            it,
                            compact = false,
                            onCopy = onCopy
                        )
                    }
                } else {
                    EthernetInfoCard(null, compact = false, onCopy = onCopy)
                }
            }
        }

        if (showQrCodeDialog) {
            val ip = uiState.primaryIpAddress ?: "127.0.0.1"
            val tcpPort = otgViewModel.uiState.value.usbProperties.adbTcpPort.ifBlank { "5555" }
            QrCodeDialog(
                title = stringResource(R.string.home_qr_title),
                content = "adb connect $ip:$tcpPort",
                hint = stringResource(R.string.home_qr_hint),
                onDismissRequest = { showQrCodeDialog = false },
            )
        }
    }
}

@Composable
private fun BasicInfoCard(
    info: DeviceBasicInfo,
    compact: Boolean,
    onCopy: (String, String) -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.device_specs_title),
        icon = Icons.Default.Smartphone,
        expandable = !compact,
        compact = compact,
    ) {
        CopyableInfoRow(
            stringResource(R.string.device_brand),
            info.brand,
            compact = compact,
            onCopy = onCopy
        )
        CopyableInfoRow(
            stringResource(R.string.device_manufacturer),
            info.manufacturer,
            compact = compact,
            onCopy = onCopy
        )
        CopyableInfoRow(
            stringResource(R.string.device_model),
            info.model,
            compact = compact,
            onCopy = onCopy
        )
        CopyableInfoRow(
            label = stringResource(R.string.device_board),
            value = "${info.boardName} / ${info.socPlatform}",
            monospaceValue = true,
            compact = compact,
            onCopy = onCopy,
        )
        CopyableInfoRow(
            label = stringResource(R.string.device_android_ver),
            value = "${info.androidVersion} (API ${info.sdkInt})",
            compact = compact,
            onCopy = onCopy,
        )
        CopyableInfoRow(
            label = stringResource(R.string.device_build_ver),
            value = info.buildNumber,
            monospaceValue = true,
            compact = compact,
            maxValueLines = if (compact) 2 else Int.MAX_VALUE,
            onCopy = onCopy,
        )
        CopyableInfoRow(
            stringResource(R.string.device_serial),
            info.serialNumber,
            monospaceValue = true,
            compact = compact,
            onCopy = onCopy
        )
        CopyableInfoRow(
            stringResource(R.string.device_cpu_abi),
            info.cpuAbi,
            monospaceValue = true,
            compact = compact,
            onCopy = onCopy
        )
        CopyableInfoRow(
            label = stringResource(R.string.device_uid),
            value = info.uidSummary,
            monospaceValue = true,
            showDivider = false,
            compact = compact,
            onCopy = onCopy,
        )
    }
}

@Composable
private fun RuntimeInfoCard(
    info: DeviceRuntimeInfo,
    compact: Boolean,
    onCopy: (String, String) -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.device_runtime_title),
        icon = Icons.Default.Memory,
        expandable = !compact,
        compact = compact,
    ) {
        CopyableInfoRow(
            stringResource(R.string.device_ram),
            info.ramSummary,
            compact = compact,
            onCopy = onCopy
        )
        CopyableInfoRow(
            stringResource(R.string.device_storage),
            info.storageSummary,
            compact = compact,
            onCopy = onCopy
        )
        CopyableInfoRow(
            stringResource(R.string.device_display),
            info.displaySummary,
            monospaceValue = true,
            compact = compact,
            onCopy = onCopy
        )
        CopyableInfoRow(
            stringResource(R.string.device_uptime),
            info.uptimeSummary,
            showDivider = false,
            compact = compact,
            onCopy = onCopy
        )
    }
}

@Composable
private fun WifiInfoCard(
    info: WifiStatusInfo,
    compact: Boolean,
    onCopy: (String, String) -> Unit,
) {
    val semantic = MaterialTheme.semanticColors
    SectionCard(
        title = stringResource(R.string.device_wifi_title),
        icon = Icons.Default.Wifi,
        badge = {
            StatusPill(
                text = if (info.isConnected) stringResource(R.string.status_connected) else stringResource(
                    R.string.status_disconnected
                ),
                color = if (info.isConnected) semantic.success else semantic.danger,
                icon = if (info.isConnected) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                compact = compact,
            )
        },
        expandable = !compact,
        compact = compact,
    ) {
        if (!info.isConnected) {
            CopyableInfoRow(
                label = stringResource(R.string.device_mac),
                value = info.macAddress,
                monospaceValue = true,
                showDivider = false,
                compact = compact,
                onCopy = onCopy,
            )
        } else {
            val rows = buildList {
                add(Triple("SSID", info.ssid, false))
                add(Triple("BSSID", info.bssid, true))
                add(Triple(stringResource(R.string.device_ipv4), info.ipAddress, true))
                add(Triple(stringResource(R.string.device_gateway), info.gateway, true))
                add(Triple("DNS", info.dns, true))
                add(Triple(stringResource(R.string.device_mac), info.macAddress, true))
                if (info.signalStrength != 0) {
                    add(
                        Triple(
                            stringResource(R.string.device_signal),
                            "${info.signalStrength} dBm (${wifiSignalLevel(info.signalStrength)})",
                            false
                        )
                    )
                }
                if (info.linkSpeed > 0) {
                    add(
                        Triple(
                            stringResource(R.string.device_link_speed),
                            "${info.linkSpeed} Mbps",
                            false
                        )
                    )
                }
                if (info.frequency > 0) {
                    add(
                        Triple(
                            stringResource(R.string.device_freq_band),
                            "${info.frequency} MHz (${wifiBand(info.frequency)})",
                            false
                        )
                    )
                }
            }
            rows.forEachIndexed { idx, (label, value, mono) ->
                CopyableInfoRow(
                    label = label,
                    value = value,
                    monospaceValue = mono,
                    showDivider = idx < rows.lastIndex,
                    compact = compact,
                    onCopy = onCopy,
                )
            }
        }
    }
}

@Composable
private fun EthernetInfoCard(
    info: EthernetStatusInfo?,
    compact: Boolean,
    onCopy: (String, String) -> Unit,
) {
    val semantic = MaterialTheme.semanticColors
    val connected = info?.isConnected == true

    SectionCard(
        title = stringResource(
            R.string.device_eth_title,
            info?.let { " (${it.interfaceName})" } ?: ""),
        icon = Icons.Default.SettingsEthernet,
        badge = {
            StatusPill(
                text = if (connected) stringResource(R.string.status_connected) else stringResource(
                    R.string.status_disconnected
                ),
                color = if (connected) semantic.success else semantic.danger,
                icon = if (connected) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                compact = compact,
            )
        },
        expandable = !compact,
        compact = compact,
    ) {
        if (info == null) {
            Text(
                text = stringResource(R.string.device_no_eth),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            CopyableInfoRow(
                stringResource(R.string.device_iface_name),
                info.interfaceName,
                monospaceValue = true,
                compact = compact,
                onCopy = onCopy
            )
            CopyableInfoRow(
                stringResource(R.string.device_ipv4),
                info.ipAddress,
                monospaceValue = true,
                compact = compact,
                onCopy = onCopy
            )
            if (connected) {
                CopyableInfoRow(
                    stringResource(R.string.device_gateway),
                    info.gateway,
                    monospaceValue = true,
                    compact = compact,
                    onCopy = onCopy
                )
                CopyableInfoRow(
                    "DNS",
                    info.dns,
                    monospaceValue = true,
                    compact = compact,
                    onCopy = onCopy
                )
            }
            CopyableInfoRow(
                label = stringResource(R.string.device_mac),
                value = info.macAddress,
                monospaceValue = true,
                showDivider = false,
                compact = compact,
                onCopy = onCopy,
            )
        }
    }
}

@Composable
private fun wifiSignalLevel(rssi: Int): String = when {
    rssi >= -50 -> stringResource(R.string.signal_excellent)
    rssi >= -60 -> stringResource(R.string.signal_good)
    rssi >= -70 -> stringResource(R.string.signal_fair)
    else -> stringResource(R.string.signal_poor)
}

private fun wifiBand(freqMhz: Int): String = when {
    freqMhz >= 5925 -> "6 GHz"
    freqMhz >= 4900 -> "5 GHz"
    else -> "2.4 GHz"
}
