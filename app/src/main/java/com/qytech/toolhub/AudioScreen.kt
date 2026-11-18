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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.qytech.toolhub.data.AlsaCardInfo
import com.qytech.toolhub.data.AudioOutputDeviceSummary
import com.qytech.toolhub.data.AudioSystemProperties
import com.qytech.toolhub.data.BoardSysfsNode
import com.qytech.toolhub.ui.components.AudioToneDialog
import com.qytech.toolhub.ui.components.SectionCard
import com.qytech.toolhub.ui.components.StatusPill
import com.qytech.toolhub.ui.components.copyTextToClipboard
import com.qytech.toolhub.ui.theme.semanticColors

@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioScreen(
    viewModel: HardwareViewModel,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var editingNode by remember { mutableStateOf<BoardSysfsNode?>(null) }
    var editingValue by remember { mutableStateOf("") }
    var showAddNodeDialog by remember { mutableStateOf(false) }
    var showToneDialog by remember { mutableStateOf(false) }
    var newNodePath by remember { mutableStateOf("") }
    var selectedAlsaCard by remember { mutableStateOf<AlsaCardInfo?>(null) }
    var selectedOutput by remember { mutableStateOf<AudioOutputDeviceSummary?>(null) }

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
            // 顶部控制栏
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(min = 220.dp)
                        .weight(1f, fill = true),
                ) {
                    Text(
                        text = stringResource(R.string.audio_header_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stringResource(
                            R.string.audio_header_sub_fmt,
                            state.alsaCards.size,
                            state.audioOutputs.size,
                            state.boardNodes.size
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.autoRefresh,
                        onClick = { viewModel.setAutoRefresh(!state.autoRefresh) },
                        label = {
                            Text(
                                if (state.autoRefresh) stringResource(R.string.audio_polling) else stringResource(
                                    R.string.audio_auto_refresh
                                )
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                    OutlinedButton(
                        onClick = { showToneDialog = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.defaultMinSize(minHeight = 40.dp),
                    ) {
                        Icon(
                            Icons.Default.GraphicEq,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.tone_btn_open))
                    }
                    OutlinedButton(
                        onClick = { showAddNodeDialog = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.defaultMinSize(minHeight = 40.dp),
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.audio_custom_node))
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.cd_refresh_audio)
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
                    Column(
                        modifier = Modifier
                            .weight(1.02f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AlsaCardsSection(
                            alsaCards = state.alsaCards,
                            compact = true,
                            onSelectCard = { selectedAlsaCard = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(0.56f),
                        )
                        AudioOutputsAndPropsSection(
                            outputs = state.audioOutputs,
                            audioProps = state.audioProperties,
                            compact = true,
                            onSelectOutput = { selectedOutput = it },
                            onCopy = { label, value ->
                                copyTextToClipboard(context, label, value, onShowMessage)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(0.44f),
                        )
                    }

                    BoardSysfsNodesSection(
                        nodes = state.boardNodes,
                        compact = true,
                        onEditNode = { node ->
                            editingNode = node
                            editingValue = node.value.orEmpty()
                        },
                        onCopyNode = { label, value ->
                            copyTextToClipboard(context, label, value, onShowMessage)
                        },
                        onRemoveCustom = { viewModel.removeCustomSysfsPath(it.path) },
                        modifier = Modifier
                            .weight(0.98f)
                            .fillMaxHeight(),
                    )
                }
            } else {
                AlsaCardsSection(
                    alsaCards = state.alsaCards,
                    compact = false,
                    onSelectCard = { selectedAlsaCard = it },
                )
                AudioOutputsAndPropsSection(
                    outputs = state.audioOutputs,
                    audioProps = state.audioProperties,
                    compact = false,
                    onSelectOutput = { selectedOutput = it },
                    onCopy = { label, value ->
                        copyTextToClipboard(context, label, value, onShowMessage)
                    },
                )
                BoardSysfsNodesSection(
                    nodes = state.boardNodes,
                    compact = false,
                    onEditNode = { node ->
                        editingNode = node
                        editingValue = node.value.orEmpty()
                    },
                    onCopyNode = { label, value ->
                        copyTextToClipboard(context, label, value, onShowMessage)
                    },
                    onRemoveCustom = { viewModel.removeCustomSysfsPath(it.path) },
                )
            }
        }
    }

    if (showToneDialog) {
        AudioToneDialog(onDismissRequest = { showToneDialog = false })
    }

    // 写入板级 sysfs 节点弹窗
    val nodeToEdit = editingNode
    if (nodeToEdit != null) {
        AlertDialog(
            onDismissRequest = { editingNode = null },
            title = { Text(stringResource(R.string.audio_edit_node_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val groupLabel =
                        if (nodeToEdit.isCustom || nodeToEdit.group == "custom" || nodeToEdit.group == "自定义") {
                            stringResource(R.string.audio_group_custom)
                        } else {
                            nodeToEdit.group
                        }
                    Text(
                        text = "$groupLabel / ${nodeToEdit.name}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = nodeToEdit.path,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = editingValue,
                        onValueChange = { editingValue = it },
                        label = { Text(stringResource(R.string.audio_write_val_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("0", "1", "255").forEach { preset ->
                            OutlinedButton(
                                onClick = { editingValue = preset },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.defaultMinSize(minHeight = 36.dp),
                            ) {
                                Text(preset, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.writeSysfsNode(nodeToEdit, editingValue)
                        editingNode = null
                    },
                ) {
                    Text(stringResource(R.string.audio_btn_write))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (nodeToEdit.isCustom) {
                        TextButton(
                            onClick = {
                                viewModel.removeCustomSysfsPath(nodeToEdit.path)
                                editingNode = null
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text(stringResource(R.string.audio_btn_delete_node))
                        }
                    }
                    TextButton(onClick = { editingNode = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
        )
    }

    // 添加自定义节点弹窗
    if (showAddNodeDialog) {
        AlertDialog(
            onDismissRequest = { showAddNodeDialog = false },
            title = { Text(stringResource(R.string.audio_add_node_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.audio_add_node_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = newNodePath,
                        onValueChange = { newNodePath = it },
                        label = { Text(stringResource(R.string.audio_add_node_example)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = newNodePath.trim()
                        if (trimmed.isNotEmpty()) {
                            viewModel.addCustomSysfsPath(trimmed)
                            newNodePath = ""
                            showAddNodeDialog = false
                        }
                    },
                ) {
                    Text(stringResource(R.string.audio_btn_add))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddNodeDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // ALSA 声卡全量 PCM 流详情弹窗
    val cardDetail = selectedAlsaCard
    if (cardDetail != null) {
        AlertDialog(
            onDismissRequest = { selectedAlsaCard = null },
            title = {
                Text(
                    stringResource(
                        R.string.audio_alsa_card_fmt,
                        cardDetail.index,
                        cardDetail.id
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = cardDetail.description.ifBlank { cardDetail.name },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (cardDetail.streams.isEmpty()) {
                        Text(
                            text = stringResource(R.string.audio_no_pcm),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        cardDetail.streams.forEach { stream ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant
                                ),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(
                                        text = "${stream.pcmId} · ${stream.streamName.ifBlank { stream.statusText }}",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    val fmt =
                                        stream.format?.takeIf { it.isNotBlank() && it != "null" }
                                            ?: "-"
                                    val rate =
                                        stream.rate?.takeIf { it.isNotBlank() && it != "null" }
                                            ?: "-"
                                    val ch =
                                        stream.channels?.takeIf { it.isNotBlank() && it != "null" }
                                            ?: "-"
                                    Text(
                                        text = stringResource(
                                            R.string.audio_pcm_status_fmt,
                                            stream.statusText,
                                            fmt,
                                            rate,
                                            ch
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    val periodText = stream.periodSize.orEmpty()
                                    val bufferText = stream.bufferSize.orEmpty()
                                    if (periodText.isNotBlank() || bufferText.isNotBlank()) {
                                        Text(
                                            text = "period=${periodText.ifBlank { "-" }} · buffer=${bufferText.ifBlank { "-" }}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedAlsaCard = null }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }

    // AudioManager 输出设备详情弹窗
    val outputDetail = selectedOutput
    if (outputDetail != null) {
        AlertDialog(
            onDismissRequest = { selectedOutput = null },
            title = { Text("${outputDetail.typeLabel} (ID ${outputDetail.id})") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.audio_out_name_fmt, outputDetail.productName),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(R.string.audio_out_rate_fmt, outputDetail.sampleRates),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        stringResource(R.string.audio_out_enc_fmt, outputDetail.encodings),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedOutput = null }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
}

@Composable
private fun AlsaCardsSection(
    alsaCards: List<AlsaCardInfo>,
    compact: Boolean,
    onSelectCard: (AlsaCardInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = MaterialTheme.semanticColors
    val activeCount = alsaCards.count { it.hasActiveStream }
    SectionCard(
        title = stringResource(R.string.audio_alsa_title),
        subtitle = stringResource(R.string.audio_alsa_sub_fmt, activeCount, alsaCards.size),
        icon = Icons.Default.GraphicEq,
        compact = compact,
        badge = {
            StatusPill(
                text = if (activeCount > 0) stringResource(
                    R.string.audio_active_cnt_fmt,
                    activeCount
                ) else stringResource(R.string.audio_idle),
                color = if (activeCount > 0) semantic.success else MaterialTheme.colorScheme.onSurfaceVariant,
                compact = compact,
            )
        },
        modifier = modifier,
    ) {
        if (alsaCards.isEmpty()) {
            Text(
                text = stringResource(R.string.audio_no_cards),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val rows = if (compact) alsaCards.chunked(2) else alsaCards.map { listOf(it) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEach { rowCards ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowCards.forEach { card ->
                            val runningStream =
                                card.streams.firstOrNull { it.isActive && !it.format.isNullOrBlank() && it.format != "null" }
                            val cardSubText = if (runningStream != null) {
                                "${runningStream.format} · ${runningStream.rate ?: "-"} · ${runningStream.channels ?: "-"}ch"
                            } else if (card.streams.isNotEmpty()) {
                                stringResource(R.string.audio_card_standby_fmt, card.streams.size)
                            } else {
                                card.description.ifBlank { card.name }
                            }
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = if (compact) 52.dp else 56.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onSelectCard(card) },
                                shape = RoundedCornerShape(10.dp),
                                color = if (card.hasActiveStream) {
                                    semantic.success.copy(alpha = 0.08f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                },
                                border = BorderStroke(
                                    1.dp,
                                    if (card.hasActiveStream) semantic.success.copy(alpha = 0.45f)
                                    else MaterialTheme.colorScheme.outlineVariant,
                                ),
                            ) {
                                Column(
                                    modifier = Modifier.padding(
                                        horizontal = 10.dp,
                                        vertical = 6.dp
                                    ),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            text = "card${card.index} · ${card.id}",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            text = if (card.hasActiveStream) "RUNNING" else "${card.streams.size} PCM",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (card.hasActiveStream) semantic.success
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        text = cardSubText,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        if (compact && rowCards.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudioOutputsAndPropsSection(
    outputs: List<AudioOutputDeviceSummary>,
    audioProps: AudioSystemProperties,
    compact: Boolean,
    onSelectOutput: (AudioOutputDeviceSummary) -> Unit,
    onCopy: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = MaterialTheme.semanticColors
    SectionCard(
        title = stringResource(R.string.audio_props_title),
        subtitle = stringResource(R.string.audio_props_sub),
        icon = Icons.Default.Speaker,
        compact = compact,
        modifier = modifier,
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (outputs.isEmpty()) {
                StatusPill(
                    text = stringResource(R.string.audio_no_outputs),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    compact = compact,
                )
            } else {
                outputs.forEach { dev ->
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelectOutput(dev) },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                        ),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                            Text(
                                text = dev.typeLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                text = "${dev.productName} · ${dev.sampleRates}".take(42),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        val propItems = listOf(
            "USB Audio Info" to audioProps.usbAudioInfo,
            stringResource(R.string.audio_prop_usb_excl) to audioProps.usbExclusive,
            stringResource(R.string.audio_prop_direct) to audioProps.audioDirectDevice,
            stringResource(R.string.audio_prop_i2s) to audioProps.i2sMode,
        ).filter { it.second.isNotBlank() }

        if (propItems.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                propItems.forEach { (k, v) ->
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onCopy(k, v) },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Text(
                            text = "$k: $v",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (v == "true" || v == "1") semantic.success else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BoardSysfsNodesSection(
    nodes: List<BoardSysfsNode>,
    compact: Boolean,
    onEditNode: (BoardSysfsNode) -> Unit,
    onCopyNode: (String, String) -> Unit,
    onRemoveCustom: (BoardSysfsNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = MaterialTheme.semanticColors
    val writableCount = nodes.count { it.isWritable }
    val denseMode = compact && nodes.size > 12

    SectionCard(
        title = stringResource(R.string.audio_sysfs_title),
        subtitle = stringResource(R.string.audio_sysfs_sub_fmt, nodes.size, writableCount),
        icon = Icons.Default.DeveloperBoard,
        compact = compact,
        badge = {
            StatusPill(
                text = stringResource(R.string.audio_writable_cnt_fmt, writableCount),
                color = if (writableCount > 0) semantic.info else MaterialTheme.colorScheme.onSurfaceVariant,
                compact = compact,
            )
        },
        modifier = modifier,
    ) {
        if (nodes.isEmpty()) {
            Text(
                text = stringResource(R.string.audio_no_nodes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val rows = if (compact) nodes.chunked(2) else nodes.map { listOf(it) }
            Column(
                verticalArrangement = Arrangement.spacedBy(if (denseMode) 4.dp else 6.dp),
            ) {
                rows.forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        pair.forEach { node ->
                            val groupLabel =
                                if (node.isCustom || node.group == "custom" || node.group == "自定义") {
                                    stringResource(R.string.audio_group_custom)
                                } else {
                                    node.group
                                }
                            val titleLabel = "$groupLabel/${node.name}"
                            val valueLabel = node.value?.takeIf { it.isNotBlank() }
                                ?: when (node.readError) {
                                    "path_not_found", "路径不存在" -> stringResource(R.string.err_path_not_found)
                                    "permission_denied", "无读取权限" -> stringResource(R.string.err_no_read_perm)
                                    "read_failed", "读取失败" -> stringResource(R.string.otg_read_failed)
                                    else -> node.readError?.takeIf { it.isNotBlank() } ?: "-"
                                }
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = if (denseMode) 36.dp else 44.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        if (node.isWritable) {
                                            onEditNode(node)
                                        } else {
                                            onCopyNode(titleLabel, "${node.path} = $valueLabel")
                                        }
                                    },
                                shape = RoundedCornerShape(8.dp),
                                color = if (node.isWritable) {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                                border = BorderStroke(
                                    1.dp,
                                    if (node.isWritable) MaterialTheme.colorScheme.primary.copy(
                                        alpha = 0.32f
                                    )
                                    else MaterialTheme.colorScheme.outlineVariant,
                                ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(
                                        horizontal = 8.dp,
                                        vertical = if (denseMode) 4.dp else 6.dp,
                                    ),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = titleLabel,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = valueLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (node.isWritable) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (node.isCustom && !denseMode) {
                                        IconButton(
                                            onClick = { onRemoveCustom(node) },
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = stringResource(
                                                    R.string.cd_delete_custom_node_fmt,
                                                    titleLabel
                                                ),
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                    Icon(
                                        imageVector = if (node.isWritable) Icons.Default.Edit else Icons.Default.Lock,
                                        contentDescription = if (node.isWritable) stringResource(
                                            R.string.cd_edit_node_fmt,
                                            titleLabel
                                        ) else stringResource(R.string.cd_readonly),
                                        tint = if (node.isWritable) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                        if (compact && pair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
