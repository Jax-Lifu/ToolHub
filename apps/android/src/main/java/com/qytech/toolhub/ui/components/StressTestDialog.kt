package com.qytech.toolhub.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.qytech.toolhub.R
import com.qytech.toolhub.data.StressTestRepository
import com.qytech.toolhub.data.StressTestType
import com.qytech.toolhub.ui.theme.semanticColors
import java.util.Locale

private enum class SatDisplayTab(
    val type: StressTestType,
    @param:androidx.annotation.StringRes val labelRes: Int
) {
    SAT(StressTestType.SAT, R.string.stress_mode_sat),
    CPU(StressTestType.CPU, R.string.stress_mode_cpu),
    MEMORY(StressTestType.MEMORY, R.string.stress_mode_mem),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StressTestDialog(
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val state by StressTestRepository.state.collectAsState()
    val semantic = MaterialTheme.semanticColors

    val maxCores = remember { Runtime.getRuntime().availableProcessors().coerceAtLeast(1) }
    var selectedType by remember {
        mutableStateOf(if (state.type == StressTestType.COMBINED) StressTestType.SAT else state.type)
    }
    var selectedThreads by remember { mutableIntStateOf(state.targetThreads.coerceIn(1, maxCores)) }
    var selectedRamMB by remember { mutableIntStateOf(state.allocatedRamMB) }
    var selectedDuration by remember { mutableIntStateOf(state.durationSeconds) }
    var showAgingReportDialog by remember { mutableStateOf(false) }

    val activeType = if (state.isRunning) {
        if (state.type == StressTestType.COMBINED) StressTestType.SAT else state.type
    } else {
        selectedType
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(min = 520.dp, max = 640.dp)
            .fillMaxWidth(0.72f)
            .padding(12.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    tint = if (state.isRunning) semantic.danger else MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.dlg_stress_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 模式选择（3个宽选项，避免文字换行）
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SatDisplayTab.entries.forEachIndexed { index, tab ->
                        val selected = activeType == tab.type
                        SegmentedButton(
                            selected = selected,
                            onClick = { if (!state.isRunning) selectedType = tab.type },
                            enabled = !state.isRunning,
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = SatDisplayTab.entries.size
                            ),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        ) {
                            Text(
                                text = stringResource(tab.labelRes),
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 11.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }

                // CPU 并发核心配置
                if (activeType != StressTestType.MEMORY) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.stress_cpu_cores),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(68.dp),
                        )
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val threadOptions = buildList {
                                add(1)
                                if (maxCores >= 4) add(maxCores / 2)
                                add(maxCores)
                            }.distinct()

                            threadOptions.forEach { t ->
                                FilterChip(
                                    selected = selectedThreads == t,
                                    onClick = { if (!state.isRunning) selectedThreads = t },
                                    enabled = !state.isRunning,
                                    label = {
                                        Text(
                                            if (t == maxCores) stringResource(
                                                R.string.stress_all_cores_fmt,
                                                t
                                            ) else stringResource(R.string.stress_cores_fmt, t),
                                            fontSize = 11.sp
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    ),
                                )
                            }
                        }
                    }
                }

                // 内存参数配置
                if (activeType != StressTestType.CPU) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.stress_mem_size),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(68.dp),
                        )
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            listOf(64, 128, 256, 512).forEach { mb ->
                                FilterChip(
                                    selected = selectedRamMB == mb,
                                    onClick = { if (!state.isRunning) selectedRamMB = mb },
                                    enabled = !state.isRunning,
                                    label = { Text("${mb}MB", fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    ),
                                )
                            }
                        }
                    }
                }

                // 持续时间配置
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.stress_duration),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(68.dp),
                    )
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(
                            20 to stringResource(R.string.stress_dur_20s),
                            60 to stringResource(R.string.stress_dur_1m),
                            180 to stringResource(R.string.stress_dur_3m),
                            0 to stringResource(R.string.stress_dur_cont)
                        ).forEach { (sec, label) ->
                            FilterChip(
                                selected = selectedDuration == sec,
                                onClick = { if (!state.isRunning) selectedDuration = sec },
                                enabled = !state.isRunning,
                                label = { Text(label, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                ),
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // 实时压测状态面板
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (state.isRunning) semantic.danger.copy(alpha = 0.08f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(
                        1.dp,
                        if (state.isRunning) semantic.danger.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
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
                                StatusPill(
                                    text = if (state.isRunning) stringResource(R.string.stress_running) else stringResource(
                                        R.string.stress_idle
                                    ),
                                    color = if (state.isRunning) semantic.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                                    compact = true,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.stress_elapsed_fmt,
                                        state.elapsedSeconds
                                    ) + if (state.durationSeconds > 0) " / ${state.durationSeconds}s" else "",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            state.currentTempCelsius?.let { temp ->
                                StatusPill(
                                    text = String.format(Locale.US, "%.1f °C", temp),
                                    color = if (temp >= 85f) semantic.danger else if (temp >= 72f) semantic.warning else semantic.success,
                                    icon = Icons.Default.Thermostat,
                                    compact = true,
                                )
                            }
                        }

                        if (state.type == StressTestType.CPU || state.type == StressTestType.COMBINED) {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    stringResource(R.string.stress_cpu_stats_fmt),
                                    state.cpuMflops,
                                    state.cpuIterations,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        if (state.type == StressTestType.SAT) {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    stringResource(R.string.stress_sat_stats_fmt),
                                    state.memThroughputMBs,
                                    state.memErrors,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = if (state.memErrors > 0) semantic.danger else MaterialTheme.colorScheme.onSurface,
                            )
                        } else if (state.type == StressTestType.MEMORY || state.type == StressTestType.COMBINED) {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    stringResource(R.string.stress_mem_stats_fmt),
                                    state.memThroughputMBs,
                                    state.memLoops,
                                    state.memErrors,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = if (state.memErrors > 0) semantic.danger else MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        val statusMsg =
                            if (state.statusMessage == "就绪" || state.statusMessage == "Ready") {
                                stringResource(R.string.status_ready)
                            } else {
                                state.statusMessage
                            }
                        Text(
                            text = statusMsg,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = if (state.overheatAborted || state.memErrors > 0) semantic.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        // SAT 实时终端输出
                        if (state.recentLogs.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                border = BorderStroke(
                                    0.5.dp,
                                    MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp),
                            ) {
                                Column(modifier = Modifier.padding(4.dp)) {
                                    state.recentLogs.takeLast(3).forEach { line ->
                                        Text(
                                            text = line,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 9.sp,
                                                lineHeight = 12.sp,
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.lastAgingReport != null) {
                    OutlinedButton(
                        onClick = { showAgingReportDialog = true },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Assignment,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.dlg_stress_report))
                    }
                }
                if (!state.isRunning) {
                    Button(
                        onClick = {
                            StressTestRepository.startStressTest(
                                context = context,
                                type = selectedType,
                                threads = selectedThreads,
                                ramMB = selectedRamMB,
                                durationSeconds = selectedDuration,
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = semantic.danger),
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.dlg_stress_start))
                    }
                } else {
                    Button(
                        onClick = { StressTestRepository.stopStressTest() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.dlg_stress_stop))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(if (state.isRunning) R.string.dlg_stress_bg else R.string.action_close))
            }
        },
    )

    if (showAgingReportDialog && state.lastAgingReport != null) {
        AgingReportDialog(
            report = state.lastAgingReport!!,
            onDismissRequest = { showAgingReportDialog = false },
        )
    }
}
