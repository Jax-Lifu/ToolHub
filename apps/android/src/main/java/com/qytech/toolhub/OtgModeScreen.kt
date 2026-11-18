package com.qytech.toolhub

import android.annotation.SuppressLint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qytech.toolhub.data.BootExecutionRecord
import com.qytech.toolhub.data.ConnectedUsbDevice
import com.qytech.toolhub.data.OtgMode
import com.qytech.toolhub.data.OtgNodeInfo
import com.qytech.toolhub.ui.components.CopyableInfoRow
import com.qytech.toolhub.ui.components.SectionCard
import com.qytech.toolhub.ui.components.StatusPill
import com.qytech.toolhub.ui.components.copyTextToClipboard
import com.qytech.toolhub.ui.components.formatTimeMillis
import com.qytech.toolhub.ui.theme.semanticColors
import java.io.File

@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OtgModeScreen(
    viewModel: OtgModeViewModel,
    deviceInfoViewModel: DeviceInfoViewModel,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val deviceState by deviceInfoViewModel.uiState.collectAsState()
    val context = LocalContext.current

    var showHistoryDialog by remember { mutableStateOf(false) }
    var showReadOnlyDialog by remember { mutableStateOf(false) }

    val onCopy: (String, String) -> Unit = { label, value ->
        copyTextToClipboard(context, label, value, onShowMessage)
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val singleScreen = this@BoxWithConstraints.maxWidth >= 720.dp && this@BoxWithConstraints.maxHeight >= 460.dp

        val containerModifier = if (singleScreen) {
            Modifier
                .fillMaxSize()
                .padding(12.dp)
        } else {
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 84.dp)
        }

        Column(
            modifier = containerModifier,
            verticalArrangement = Arrangement.spacedBy(if (singleScreen) 10.dp else 14.dp),
        ) {
            // 顶部控制栏：标题 + 批量动作合一栏（宽屏下压缩为单行）
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column(modifier = Modifier.weight(1f, fill = true)) {
                    Text(
                        text = stringResource(R.string.otg_header_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stringResource(
                            R.string.otg_header_sub_fmt,
                            uiState.controllableNodes.size,
                            uiState.readOnlyNodes.size
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (singleScreen) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { viewModel.setAllControllableMode(OtgMode.OTG) },
                        enabled = !uiState.isApplyingBatch && uiState.controllableNodes.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.defaultMinSize(minHeight = 40.dp),
                    ) {
                        Icon(
                            Icons.Default.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.otg_all_otg))
                    }
                    FilledTonalButton(
                        onClick = { viewModel.setAllControllableMode(OtgMode.HOST) },
                        enabled = !uiState.isApplyingBatch && uiState.controllableNodes.isNotEmpty(),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.defaultMinSize(minHeight = 40.dp),
                    ) {
                        Text(stringResource(R.string.otg_all_host))
                    }
                    OutlinedButton(
                        onClick = { viewModel.applyBootPolicyNow() },
                        enabled = !uiState.isApplyingBatch,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.defaultMinSize(minHeight = 40.dp),
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.otg_apply_policy))
                    }
                    IconButton(
                        onClick = { viewModel.refreshAll() },
                        enabled = !uiState.isScanning,
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.cd_refresh_nodes)
                        )
                    }
                }
            }

            if (singleScreen) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 左栏：可控节点 + 只读节点摘要 + USB Host 已接入外设
                    Column(
                        modifier = Modifier
                            .weight(1.04f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        NodesSection(
                            uiState = uiState,
                            compact = true,
                            onModeSelected = viewModel::setNodeMode,
                            onDismissError = viewModel::clearNodeError,
                            onCopy = onCopy,
                            onShowReadOnlyDialog = { showReadOnlyDialog = true },
                        )
                        UsbHostDevicesCard(
                            devices = uiState.connectedUsbDevices,
                            compact = true,
                            onRefresh = viewModel::refreshUsbDevicesOnly,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                    }

                    // 右栏：ADB 与 USB 属性状态 + 开机自启策略与最近日志
                    Column(
                        modifier = Modifier
                            .weight(0.96f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AdbAndUsbStatusCard(
                            uiState = uiState,
                            primaryIp = deviceState.primaryIpAddress,
                            compact = true,
                            onToggleAdb = viewModel::toggleAdbEnabled,
                            onCopy = onCopy,
                        )
                        BootPolicyAndHistoryCard(
                            uiState = uiState,
                            compact = true,
                            onToggleAutoOtg = viewModel::setAutoOtgOnBoot,
                            onToggleAutoAdb = viewModel::setAutoAdbOnBoot,
                            onToggleSyncAdb = viewModel::setSyncAdbWhenSwitchOtg,
                            onClearHistory = viewModel::clearBootHistory,
                            onOpenAllHistory = { showHistoryDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                    }
                }
            } else {
                NodesSection(
                    uiState = uiState,
                    compact = false,
                    onModeSelected = viewModel::setNodeMode,
                    onDismissError = viewModel::clearNodeError,
                    onCopy = onCopy,
                    onShowReadOnlyDialog = { showReadOnlyDialog = true },
                )
                AdbAndUsbStatusCard(
                    uiState = uiState,
                    primaryIp = deviceState.primaryIpAddress,
                    compact = false,
                    onToggleAdb = viewModel::toggleAdbEnabled,
                    onCopy = onCopy,
                )
                BootPolicyAndHistoryCard(
                    uiState = uiState,
                    compact = false,
                    onToggleAutoOtg = viewModel::setAutoOtgOnBoot,
                    onToggleAutoAdb = viewModel::setAutoAdbOnBoot,
                    onToggleSyncAdb = viewModel::setSyncAdbWhenSwitchOtg,
                    onClearHistory = viewModel::clearBootHistory,
                    onOpenAllHistory = { showHistoryDialog = true },
                )
                UsbHostDevicesCard(
                    devices = uiState.connectedUsbDevices,
                    compact = false,
                    onRefresh = viewModel::refreshUsbDevicesOnly,
                )
            }
        }
    }

    if (showHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showHistoryDialog = false },
            title = { Text(stringResource(R.string.otg_boot_logs_fmt, uiState.bootHistory.size)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (uiState.bootHistory.isEmpty()) {
                        Text(
                            text = stringResource(R.string.otg_no_logs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        uiState.bootHistory.forEach { record ->
                            BootHistoryItem(record = record, compact = false)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHistoryDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
            dismissButton = {
                if (uiState.bootHistory.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            viewModel.clearBootHistory()
                            showHistoryDialog = false
                        },
                    ) {
                        Text(stringResource(R.string.otg_clear_logs))
                    }
                }
            },
        )
    }

    if (showReadOnlyDialog) {
        AlertDialog(
            onDismissRequest = { showReadOnlyDialog = false },
            title = {
                Text(
                    stringResource(
                        R.string.otg_readonly_title_fmt,
                        uiState.readOnlyNodes.size
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.otg_readonly_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val roPathLabel = stringResource(R.string.otg_readonly_path_label)
                    uiState.readOnlyNodes.forEachIndexed { idx, node ->
                        ReadOnlyOtgNodeRow(
                            node = node,
                            onCopyPath = { onCopy(roPathLabel, node.path) },
                        )
                        if (idx < uiState.readOnlyNodes.lastIndex) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                thickness = 0.6.dp,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showReadOnlyDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
}

@Composable
private fun NodesSection(
    uiState: OtgModeUiState,
    compact: Boolean,
    onModeSelected: (File, OtgMode) -> Unit,
    onDismissError: (File) -> Unit,
    onCopy: (String, String) -> Unit,
    onShowReadOnlyDialog: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
        if (uiState.isScanning) {
            SectionCard(
                title = stringResource(R.string.otg_controllable_title),
                icon = Icons.Default.Usb,
                compact = compact
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(R.string.otg_scanning),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else if (uiState.controllableNodes.isEmpty()) {
            SectionCard(
                title = stringResource(R.string.otg_controllable_title),
                icon = Icons.Default.Usb,
                compact = compact
            ) {
                Text(
                    text = if (uiState.nodes.isEmpty()) {
                        stringResource(R.string.otg_none_found)
                    } else {
                        stringResource(R.string.otg_no_write_perm_fmt, uiState.nodes.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.semanticColors.danger,
                )
            }
        } else {
            val nodePathLabel = stringResource(R.string.otg_node_path_label)
            uiState.controllableNodes.forEach { node ->
                ControllableOtgNodeCard(
                    node = node,
                    compact = compact,
                    onModeSelected = { mode -> onModeSelected(node.file, mode) },
                    onDismissError = { onDismissError(node.file) },
                    onCopyPath = { onCopy(nodePathLabel, node.path) },
                )
            }
        }

        if (uiState.readOnlyNodes.isNotEmpty()) {
            if (compact) {
                val summary = uiState.readOnlyNodes.joinToString(" · ") {
                    "${it.nodeName}=${it.rawValue ?: "?"}"
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onShowReadOnlyDialog),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = stringResource(
                                R.string.otg_readonly_summary_fmt,
                                uiState.readOnlyNodes.size,
                                summary
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.otg_details),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            } else {
                SectionCard(
                    title = stringResource(
                        R.string.otg_readonly_card_title_fmt,
                        uiState.readOnlyNodes.size
                    ),
                    subtitle = stringResource(R.string.otg_readonly_card_sub),
                    icon = Icons.Default.Lock,
                    expandable = true,
                    defaultExpanded = false,
                ) {
                    val roPathLabel = stringResource(R.string.otg_readonly_path_label)
                    uiState.readOnlyNodes.forEachIndexed { idx, node ->
                        ReadOnlyOtgNodeRow(
                            node = node,
                            onCopyPath = { onCopy(roPathLabel, node.path) },
                        )
                        if (idx < uiState.readOnlyNodes.lastIndex) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                thickness = 0.6.dp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ControllableOtgNodeCard(
    node: OtgNodeInfo,
    compact: Boolean,
    onModeSelected: (OtgMode) -> Unit,
    onDismissError: () -> Unit,
    onCopyPath: () -> Unit,
) {
    val semantic = MaterialTheme.semanticColors
    val (modeColor, modeIcon) = when (node.currentMode) {
        OtgMode.OTG -> semantic.success to Icons.Default.CheckCircle
        OtgMode.HOST -> semantic.warning to Icons.Default.Usb
        OtgMode.PERIPHERAL -> semantic.info to Icons.Default.Info
        null -> semantic.danger to Icons.Default.ErrorOutline
    }
    val modeLabel = node.currentMode?.label ?: node.rawValue ?: when (node.readError) {
        "permission_denied", "无读取权限" -> stringResource(R.string.err_no_read_perm)
        "read_failed", "读取失败" -> stringResource(R.string.otg_read_failed)
        else -> node.readError ?: stringResource(R.string.otg_read_failed)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(if (compact) 12.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = node.nodeName,
                        style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (node.isOtgPortHint) {
                        StatusPill(
                            text = "OTG-Port",
                            color = semantic.info,
                            icon = Icons.Default.Usb,
                            compact = compact,
                        )
                    }
                }
                StatusPill(text = modeLabel, color = modeColor, icon = modeIcon, compact = compact)
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = if (compact) 34.dp else 44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onCopyPath),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = 10.dp,
                        vertical = if (compact) 6.dp else 10.dp,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = node.path,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (compact) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(
                            R.string.cd_copy_node_path_fmt,
                            node.nodeName
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            val switchModes = listOf(OtgMode.OTG, OtgMode.HOST)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                switchModes.forEachIndexed { index, mode ->
                    val selected = node.currentMode == mode
                    SegmentedButton(
                        selected = selected,
                        onClick = { if (!selected && !node.isLoading) onModeSelected(mode) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = switchModes.size,
                        ),
                        enabled = !node.isLoading,
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        icon = {
                            if (node.isLoading && !selected) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                SegmentedButtonDefaults.Icon(active = selected)
                            }
                        },
                    ) {
                        Text(
                            text = if (mode == OtgMode.OTG) stringResource(R.string.otg_mode_otg_desc) else stringResource(
                                R.string.otg_mode_host_desc
                            ),
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
            }

            if (node.writeError != null) {
                Surface(
                    color = semantic.danger.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = node.writeError,
                            style = MaterialTheme.typography.bodySmall,
                            color = semantic.danger,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismissError) {
                            Text(stringResource(R.string.action_close))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadOnlyOtgNodeRow(
    node: OtgNodeInfo,
    onCopyPath: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onCopyPath)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = node.nodeName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = node.path,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusPill(
            text = node.rawValue ?: stringResource(R.string.otg_unreadable),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            icon = Icons.Default.Lock,
            compact = true,
        )
    }
}

@Composable
private fun AdbAndUsbStatusCard(
    uiState: OtgModeUiState,
    primaryIp: String?,
    compact: Boolean,
    onToggleAdb: (Boolean) -> Unit,
    onCopy: (String, String) -> Unit,
) {
    val semantic = MaterialTheme.semanticColors
    val props = uiState.usbProperties
    val tcpDisplay = if (props.adbTcpPort.isNotBlank() && !primaryIp.isNullOrBlank()) {
        "adb connect $primaryIp:${props.adbTcpPort}"
    } else {
        props.adbTcpPort.ifBlank { stringResource(R.string.otg_tcp_disabled) }
    }

    SectionCard(
        title = stringResource(R.string.otg_adb_usb_title),
        icon = Icons.Default.DeveloperMode,
        compact = compact,
        badge = {
            StatusPill(
                text = if (uiState.adbEnabled) "ADB=1" else "ADB=0",
                color = if (uiState.adbEnabled) semantic.success else semantic.danger,
                icon = if (uiState.adbEnabled) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                compact = compact,
            )
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = if (compact) 38.dp else 48.dp)
                .padding(bottom = if (compact) 4.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.otg_adb_master),
                    style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.otg_adb_master_sub),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = uiState.adbEnabled,
                onCheckedChange = onToggleAdb,
                enabled = !uiState.isAdbUpdating,
            )
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
            thickness = 0.6.dp,
        )

        if (compact) {
            Spacer(modifier = Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CompactPropTile(
                        label = "sys.usb.config",
                        value = props.usbConfig.ifBlank { "-" },
                        onClick = { onCopy("sys.usb.config", props.usbConfig.ifBlank { "-" }) },
                        modifier = Modifier.weight(1f),
                    )
                    CompactPropTile(
                        label = "sys.usb.state",
                        value = props.usbState.ifBlank { "-" },
                        onClick = { onCopy("sys.usb.state", props.usbState.ifBlank { "-" }) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CompactPropTile(
                        label = "sys.usb.controller",
                        value = props.usbController.ifBlank { "-" },
                        onClick = {
                            onCopy(
                                "sys.usb.controller",
                                props.usbController.ifBlank { "-" })
                        },
                        modifier = Modifier.weight(1f),
                    )
                    val netAdbLabel = stringResource(R.string.otg_net_adb)
                    CompactPropTile(
                        label = netAdbLabel,
                        value = tcpDisplay,
                        onClick = { onCopy(netAdbLabel, tcpDisplay) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            CopyableInfoRow(
                label = "sys.usb.config",
                value = props.usbConfig.ifBlank { "-" },
                monospaceValue = true,
                onCopy = onCopy,
            )
            CopyableInfoRow(
                label = "sys.usb.state",
                value = props.usbState.ifBlank { "-" },
                monospaceValue = true,
                onCopy = onCopy,
            )
            CopyableInfoRow(
                label = "sys.usb.controller",
                value = props.usbController.ifBlank { "-" },
                monospaceValue = true,
                onCopy = onCopy,
            )
            CopyableInfoRow(
                label = stringResource(R.string.otg_net_adb),
                value = tcpDisplay,
                monospaceValue = true,
                showDivider = false,
                onCopy = onCopy,
            )
        }
    }
}

@Composable
private fun CompactPropTile(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BootPolicyAndHistoryCard(
    uiState: OtgModeUiState,
    compact: Boolean,
    onToggleAutoOtg: (Boolean) -> Unit,
    onToggleAutoAdb: (Boolean) -> Unit,
    onToggleSyncAdb: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
    onOpenAllHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = stringResource(R.string.otg_boot_card_title),
        subtitle = if (compact) null else stringResource(R.string.otg_boot_card_sub),
        icon = Icons.Default.PowerSettingsNew,
        compact = compact,
        headerAction = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (compact && uiState.bootHistory.isNotEmpty()) {
                    TextButton(
                        onClick = onOpenAllHistory,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.defaultMinSize(minHeight = 32.dp),
                    ) {
                        Text(
                            stringResource(R.string.otg_all_logs_fmt, uiState.bootHistory.size),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                if (uiState.bootHistory.isNotEmpty()) {
                    IconButton(onClick = onClearHistory) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = stringResource(R.string.cd_clear_boot_logs)
                        )
                    }
                }
            }
        },
        modifier = modifier,
    ) {
        BootPolicySwitchRow(
            title = stringResource(R.string.otg_pref_boot_otg),
            subtitle = stringResource(R.string.otg_pref_boot_otg_sub),
            checked = uiState.bootSettings.autoOtgOnBoot,
            compact = compact,
            onCheckedChange = onToggleAutoOtg,
        )
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
            thickness = 0.6.dp,
        )
        BootPolicySwitchRow(
            title = stringResource(R.string.otg_pref_boot_adb),
            subtitle = stringResource(R.string.otg_pref_boot_adb_sub),
            checked = uiState.bootSettings.autoAdbOnBoot,
            compact = compact,
            onCheckedChange = onToggleAutoAdb,
        )
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
            thickness = 0.6.dp,
        )
        BootPolicySwitchRow(
            title = stringResource(R.string.otg_pref_sync_adb),
            subtitle = stringResource(R.string.otg_pref_sync_adb_sub),
            checked = uiState.bootSettings.syncAdbWhenSwitchOtg,
            compact = compact,
            onCheckedChange = onToggleSyncAdb,
        )

        Spacer(modifier = Modifier.height(if (compact) 6.dp else 10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = stringResource(R.string.otg_recent_logs_fmt, uiState.bootHistory.size),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(modifier = Modifier.height(5.dp))

        if (uiState.bootHistory.isEmpty()) {
            Text(
                text = stringResource(R.string.otg_no_logs_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val shown = if (compact) uiState.bootHistory.take(1) else uiState.bootHistory.take(5)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                shown.forEach { record ->
                    BootHistoryItem(record = record, compact = compact)
                }
            }
        }
    }
}

@Composable
private fun BootPolicySwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    compact: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = if (compact) 38.dp else 52.dp)
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = if (compact) 3.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun BootHistoryItem(
    record: BootExecutionRecord,
    compact: Boolean,
) {
    val semantic = MaterialTheme.semanticColors
    val color = if (record.success) semantic.success else semantic.danger

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.32f)),
    ) {
        Column(
            modifier = Modifier.padding(if (compact) 8.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = if (record.success) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = record.trigger,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = formatTimeMillis(record.timestampMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (compact) {
                val combined = (record.nodeSummaries + record.adbSummary)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                Text(
                    text = combined,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                record.nodeSummaries.forEach { summary ->
                    Text(
                        text = "• $summary",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "• ${record.adbSummary}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UsbHostDevicesCard(
    devices: List<ConnectedUsbDevice>,
    compact: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = stringResource(R.string.otg_usb_title),
        subtitle = if (compact) null else stringResource(R.string.otg_usb_sub),
        icon = Icons.Default.Usb,
        compact = compact,
        badge = {
            StatusPill(
                text = stringResource(R.string.otg_usb_count_fmt, devices.size),
                color = if (devices.isNotEmpty()) MaterialTheme.semanticColors.info
                else MaterialTheme.colorScheme.onSurfaceVariant,
                icon = Icons.Default.Usb,
                compact = compact,
            )
        },
        headerAction = {
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.cd_refresh_usb)
                )
            }
        },
        modifier = modifier,
    ) {
        if (devices.isEmpty()) {
            Text(
                text = stringResource(R.string.otg_no_usb),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val maxItems = if (compact) 3 else devices.size
            devices.take(maxItems).forEachIndexed { idx, dev ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = if (compact) 3.dp else 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val name =
                            if (dev.displayName == "USB 外设" || dev.displayName == "USB Device") {
                                stringResource(R.string.usb_device_fallback)
                            } else {
                                dev.displayName
                            }
                        Text(
                            text = name,
                            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(
                                R.string.otg_usb_item_sub_fmt,
                                dev.vidPidHex,
                                dev.interfaceCount,
                                dev.deviceName
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    StatusPill(
                        text = dev.classLabel,
                        color = MaterialTheme.semanticColors.info,
                        icon = Icons.Default.Usb,
                        compact = compact,
                    )
                }
                if (idx < minOf(devices.size, maxItems) - 1) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                        thickness = 0.6.dp,
                    )
                }
            }
            if (compact && devices.size > maxItems) {
                Text(
                    text = stringResource(R.string.otg_usb_collapsed_fmt, devices.size - maxItems),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
