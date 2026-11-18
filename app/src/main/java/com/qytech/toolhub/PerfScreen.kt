package com.qytech.toolhub

import android.annotation.SuppressLint
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qytech.toolhub.data.StressTestRepository
import com.qytech.toolhub.data.TelemetryRepository
import com.qytech.toolhub.ui.components.ChartSeries
import com.qytech.toolhub.ui.components.StressTestDialog
import com.qytech.toolhub.ui.components.TelemetryChartCard
import com.qytech.toolhub.ui.theme.semanticColors
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

enum class PerfCard4Mode(@param:androidx.annotation.StringRes val labelRes: Int) {
    DISK(R.string.perf_tab_disk),
    GC(R.string.perf_tab_gc),
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PerfScreen(
    viewModel: MonitorViewModel,
    modifier: Modifier = Modifier,
) {
    var showStressDialog by remember { mutableStateOf(false) }
    var card4Mode by remember { mutableStateOf(PerfCard4Mode.DISK) }

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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 紧凑顶部控制栏（仅订阅低频控制状态，避免 1s 遥测刷新引起整页重组）
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column(modifier = Modifier.weight(1f, fill = true)) {
                    Text(
                        text = stringResource(R.string.perf_header_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    PerfHeaderSubtitle(viewModel = viewModel, card4Mode = card4Mode)
                }

                PerfControlsRow(
                    viewModel = viewModel,
                    onOpenStressDialog = { showStressDialog = true },
                )
            }

            PerfChartsArea(
                viewModel = viewModel,
                card4Mode = card4Mode,
                onToggleCard4Mode = {
                    card4Mode = if (card4Mode == PerfCard4Mode.DISK) PerfCard4Mode.GC else PerfCard4Mode.DISK
                },
                singleScreen = singleScreen,
            )
        }
    }

    if (showStressDialog) {
        StressTestDialog(onDismissRequest = { showStressDialog = false })
    }
}

@Composable
private fun PerfHeaderSubtitle(
    viewModel: MonitorViewModel,
    card4Mode: PerfCard4Mode,
) {
    val countFlow = remember(viewModel) {
        viewModel.uiState.map { it.points.size to it.maxPoints }.distinctUntilChanged()
    }
    val (pointCount, maxPoints) = countFlow.collectAsState(
        initial = viewModel.uiState.value.let { it.points.size to it.maxPoints }
    ).value
    Text(
        text = stringResource(
            R.string.perf_header_sub_fmt,
            if (card4Mode == PerfCard4Mode.DISK) stringResource(R.string.perf_card4_disk) else stringResource(
                R.string.perf_card4_gc
            ),
            pointCount,
            maxPoints
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PerfControlsRow(
    viewModel: MonitorViewModel,
    onOpenStressDialog: () -> Unit,
) {
    val controlFlow = remember(viewModel) {
        viewModel.uiState.map { it.interval to it.isRunning }.distinctUntilChanged()
    }
    val (interval, isRunning) = controlFlow.collectAsState(
        initial = viewModel.uiState.value.let { it.interval to it.isRunning }
    ).value

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SingleChoiceSegmentedButtonRow {
            SampleIntervalOption.entries.forEachIndexed { idx, opt ->
                SegmentedButton(
                    selected = interval == opt,
                    onClick = { viewModel.setInterval(opt) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = idx,
                        count = SampleIntervalOption.entries.size,
                    ),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                ) {
                    Text(opt.label)
                }
            }
        }

        FilledTonalButton(
            onClick = { viewModel.toggleRunning() },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            modifier = Modifier.defaultMinSize(minHeight = 40.dp),
        ) {
            Icon(
                imageVector = if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(if (isRunning) R.string.action_pause else R.string.action_resume))
        }

        PerfStressButton(onClick = onOpenStressDialog)

        IconButton(onClick = { viewModel.clearHistory() }) {
            Icon(
                Icons.Default.DeleteSweep,
                contentDescription = stringResource(R.string.cd_clear_chart)
            )
        }
    }
}

@Composable
private fun PerfStressButton(onClick: () -> Unit) {
    val stressState by StressTestRepository.state.collectAsState()
    val semantic = MaterialTheme.semanticColors
    Button(
        onClick = onClick,
        colors = if (stressState.isRunning) {
            ButtonDefaults.buttonColors(containerColor = semantic.danger)
        } else {
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        },
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        modifier = Modifier.defaultMinSize(minHeight = 40.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Bolt,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            if (stressState.isRunning) {
                stringResource(
                    R.string.status_stress_running_fmt,
                    stressState.elapsedSeconds
                )
            } else {
                stringResource(R.string.action_stress_test)
            }
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PerfChartsArea(
    viewModel: MonitorViewModel,
    card4Mode: PerfCard4Mode,
    onToggleCard4Mode: () -> Unit,
    singleScreen: Boolean,
) {
    val uiState by viewModel.uiState.collectAsState()
    val points = uiState.points
    val latest = uiState.latestPoint
    val details = uiState.latestDetails
    val semantic = MaterialTheme.semanticColors

    val cpuValues = remember(points) { points.map { it.cpuPercent } }
    val gpuValues = remember(points) { points.map { it.gpuPercent } }
    val memValues = remember(points) { points.map { it.memPercent } }
    val diskReadValues = remember(points) { points.map { it.diskReadBytesPerSec } }
    val diskWriteValues = remember(points) { points.map { it.diskWriteBytesPerSec } }
    val niceDiskMax = remember(diskReadValues, diskWriteValues) {
        computeNiceDiskScale(
            maxOf(
                diskReadValues.maxOrNull() ?: 0f,
                diskWriteValues.maxOrNull() ?: 0f,
            )
        )
    }
    val gcHeapValues = remember(points) { points.map { it.jvmHeapUsedMB } }
    val gcPauseValues = remember(points) { points.map { it.gcPauseMs } }
    val niceGcMax = remember(gcHeapValues) {
        computeNiceGcScale(gcHeapValues.maxOrNull() ?: 64f)
    }

            val cpuCard: @Composable (Modifier) -> Unit = { mod ->
                val cur = latest?.cpuPercent ?: 0f
                val cpuColor = when {
                    cur >= 85f -> semantic.danger
                    cur >= 60f -> semantic.warning
                    else -> MaterialTheme.colorScheme.primary
                }
                TelemetryChartCard(
                    title = stringResource(R.string.perf_cpu_title),
                    icon = Icons.Default.Memory,
                    badgeText = formatPct(cur),
                    badgeColor = cpuColor,
                    seriesList = listOf(
                        ChartSeries(
                            label = "CPU (${details.cpuSource})",
                            color = MaterialTheme.colorScheme.primary,
                            values = cpuValues,
                            currentValueText = formatPct(cur),
                        ),
                    ),
                    minY = 0f,
                    maxY = 100f,
                    maxSlots = uiState.maxPoints,
                    yAxisTopLabel = "100%",
                    yAxisMidLabel = "50%",
                    yAxisBottomLabel = "0%",
                    fillAvailableHeight = singleScreen,
                    extraStatsText = formatPeakAvgPct(cpuValues),
                    footerText = stringResource(
                        R.string.perf_cpu_footer_fmt,
                        details.cpuLoadAvg,
                        details.cpuFreqSummary
                    ),
                    modifier = mod,
                )
            }

            val gpuCard: @Composable (Modifier) -> Unit = { mod ->
                val cur = latest?.gpuPercent ?: 0f
                val gpuAvail = latest?.gpuAvailable != false
                val gpuColor = when {
                    !gpuAvail -> MaterialTheme.colorScheme.onSurfaceVariant
                    cur >= 85f -> semantic.danger
                    cur >= 60f -> semantic.warning
                    else -> semantic.info
                }
                TelemetryChartCard(
                    title = stringResource(R.string.perf_gpu_title),
                    icon = Icons.Default.GraphicEq,
                    badgeText = if (gpuAvail) formatPct(cur) else stringResource(R.string.perf_unavailable),
                    badgeColor = gpuColor,
                    seriesList = listOf(
                        ChartSeries(
                            label = if (gpuAvail && details.gpuNodeName != "none" && details.gpuNodeName.isNotBlank() && details.gpuNodeName != "未检测到 GPU devfreq") {
                                details.gpuNodeName
                            } else {
                                stringResource(R.string.gpu_no_devfreq)
                            },
                            color = semantic.info,
                            values = gpuValues,
                            currentValueText = if (gpuAvail) formatPct(cur) else "-",
                        ),
                    ),
                    minY = 0f,
                    maxY = 100f,
                    maxSlots = uiState.maxPoints,
                    yAxisTopLabel = "100%",
                    yAxisMidLabel = "50%",
                    yAxisBottomLabel = "0%",
                    fillAvailableHeight = singleScreen,
                    extraStatsText = formatPeakAvgPct(gpuValues),
                    footerText = stringResource(
                        R.string.perf_gpu_footer_fmt,
                        details.gpuFreqSummary,
                        details.gpuGovernor
                    ),
                    modifier = mod,
                )
            }

            val memCard: @Composable (Modifier) -> Unit = { mod ->
                val cur = latest?.memPercent ?: 0f
                val memColor = when {
                    cur >= 88f -> semantic.danger
                    cur >= 72f -> semantic.warning
                    else -> semantic.success
                }
                val zramSuffix = details.zramText?.let { " · ZRAM $it" }.orEmpty()
                TelemetryChartCard(
                    title = stringResource(R.string.perf_ram_title),
                    icon = Icons.Default.Storage,
                    badgeText = formatPct(cur),
                    badgeColor = memColor,
                    seriesList = listOf(
                        ChartSeries(
                            label = stringResource(R.string.perf_ram_used),
                            color = semantic.success,
                            values = memValues,
                            currentValueText = "${details.memUsedText} / ${details.memTotalText}",
                        ),
                    ),
                    minY = 0f,
                    maxY = 100f,
                    maxSlots = uiState.maxPoints,
                    yAxisTopLabel = "100%",
                    yAxisMidLabel = "50%",
                    yAxisBottomLabel = "0%",
                    fillAvailableHeight = singleScreen,
                    extraStatsText = formatPeakAvgPct(memValues),
                    footerText = stringResource(
                        R.string.perf_ram_footer_fmt,
                        details.memUsedText,
                        details.memTotalText,
                        zramSuffix
                    ),
                    modifier = mod,
                )
            }

            val diskCard: @Composable (Modifier) -> Unit = { mod ->
                TelemetryChartCard(
                    title = stringResource(R.string.perf_disk_title),
                    icon = Icons.Default.Storage,
                    badgeText = stringResource(
                        R.string.perf_disk_badge_fmt,
                        details.diskReadSpeedText,
                        details.diskWriteSpeedText
                    ),
                    badgeColor = semantic.info,
                    seriesList = listOf(
                        ChartSeries(
                            label = stringResource(R.string.perf_disk_read),
                            color = semantic.info,
                            values = diskReadValues,
                            currentValueText = details.diskReadSpeedText,
                            fillArea = true,
                        ),
                        ChartSeries(
                            label = stringResource(R.string.perf_disk_write),
                            color = semantic.warning,
                            values = diskWriteValues,
                            currentValueText = details.diskWriteSpeedText,
                            fillArea = false,
                        ),
                    ),
                    minY = 0f,
                    maxY = niceDiskMax,
                    maxSlots = uiState.maxPoints,
                    yAxisTopLabel = TelemetryRepository.formatBytesPerSec(niceDiskMax),
                    yAxisMidLabel = TelemetryRepository.formatBytesPerSec(niceDiskMax / 2f),
                    yAxisBottomLabel = "0",
                    fillAvailableHeight = singleScreen,
                    extraStatsText = stringResource(
                        R.string.perf_disk_extra_fmt,
                        TelemetryRepository.formatBytesPerSec(diskReadValues.maxOrNull() ?: 0f),
                        TelemetryRepository.formatBytesPerSec(diskWriteValues.maxOrNull() ?: 0f),
                        details.diskIopsText,
                    ),
                    footerText = stringResource(
                        R.string.perf_disk_footer_fmt,
                        details.storageSpaceText,
                        details.storagePercentText,
                        details.diskTotalReadText,
                        details.diskTotalWriteText,
                    ),
                    headerAction = {
                        FilterChip(
                            selected = false,
                            onClick = onToggleCard4Mode,
                            label = {
                                Text(
                                    stringResource(R.string.perf_switch_to_gc),
                                    fontSize = 10.sp
                                )
                            },
                            modifier = Modifier.height(26.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            ),
                        )
                    },
                    modifier = mod,
                )
            }

            val gcCard: @Composable (Modifier) -> Unit = { mod ->
                val curHeap = latest?.jvmHeapUsedMB ?: 0f
                TelemetryChartCard(
                    title = stringResource(R.string.perf_gc_title),
                    icon = Icons.Default.DeleteSweep,
                    badgeText = "GC ${details.gcCountText} (${details.gcTimeText})",
                    badgeColor = MaterialTheme.colorScheme.tertiary,
                    seriesList = listOf(
                        ChartSeries(
                            label = stringResource(R.string.perf_gc_heap),
                            color = MaterialTheme.colorScheme.tertiary,
                            values = gcHeapValues,
                            currentValueText = String.format(Locale.US, "%.1f MB", curHeap),
                            fillArea = true,
                        ),
                        ChartSeries(
                            label = stringResource(R.string.perf_gc_pause),
                            color = semantic.danger,
                            values = gcPauseValues,
                            currentValueText = details.gcTimeText,
                            fillArea = false,
                        ),
                    ),
                    minY = 0f,
                    maxY = niceGcMax,
                    maxSlots = uiState.maxPoints,
                    yAxisTopLabel = String.format(Locale.US, "%.0f MB", niceGcMax),
                    yAxisMidLabel = String.format(Locale.US, "%.0f MB", niceGcMax / 2f),
                    yAxisBottomLabel = "0",
                    fillAvailableHeight = singleScreen,
                    extraStatsText = stringResource(
                        R.string.perf_gc_extra_fmt,
                        details.gcCountText,
                        details.gcTimeText,
                        details.blockingGcText
                    ),
                    footerText = stringResource(
                        R.string.perf_gc_footer_fmt,
                        details.jvmHeapText,
                        String.format(
                            Locale.US,
                            "%.1f MB",
                            if (gcHeapValues.isNotEmpty()) gcHeapValues.average() else 0.0
                        )
                    ),
                    headerAction = {
                        FilterChip(
                            selected = false,
                            onClick = onToggleCard4Mode,
                            label = {
                                Text(
                                    stringResource(R.string.perf_switch_to_disk),
                                    fontSize = 10.sp
                                )
                            },
                            modifier = Modifier.height(26.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            ),
                        )
                    },
                    modifier = mod,
                )
            }

            val card4: @Composable (Modifier) -> Unit =
                if (card4Mode == PerfCard4Mode.DISK) diskCard else gcCard

            if (singleScreen) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    cpuCard(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    gpuCard(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    memCard(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    card4(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            } else {
                cpuCard(Modifier.fillMaxWidth())
                gpuCard(Modifier.fillMaxWidth())
                memCard(Modifier.fillMaxWidth())
                card4(Modifier.fillMaxWidth())
            }
}

private fun formatPct(value: Float): String = String.format(Locale.US, "%.1f%%", value)

@Composable
private fun formatPeakAvgPct(values: List<Float>): String? {
    if (values.isEmpty()) return null
    return stringResource(R.string.perf_peak_avg_pct_fmt, values.max(), values.average())
}

private fun computeNiceDiskScale(peakBytesPerSec: Float): Float {
    val minFloor = 256f * 1024f
    val target = maxOf(peakBytesPerSec * 1.25f, minFloor)
    val steps = listOf(
        256f * 1024f,
        512f * 1024f,
        1024f * 1024f,
        2f * 1024f * 1024f,
        5f * 1024f * 1024f,
        10f * 1024f * 1024f,
        25f * 1024f * 1024f,
        50f * 1024f * 1024f,
        100f * 1024f * 1024f,
        250f * 1024f * 1024f,
        500f * 1024f * 1024f,
    )
    return steps.firstOrNull { it >= target } ?: target
}

private fun computeNiceGcScale(peakMb: Float): Float {
    val minFloor = 64f
    val target = maxOf(peakMb * 1.25f, minFloor)
    val steps = listOf(64f, 128f, 256f, 512f, 1024f, 2048f)
    return steps.firstOrNull { it >= target } ?: target
}

private fun computeNiceNetworkScale(peakBytesPerSec: Float): Float {
    val minFloor = 64f * 1024f
    val target = maxOf(peakBytesPerSec * 1.25f, minFloor)
    val steps = listOf(
        64f * 1024f,
        128f * 1024f,
        256f * 1024f,
        512f * 1024f,
        1024f * 1024f,
        2f * 1024f * 1024f,
        5f * 1024f * 1024f,
        10f * 1024f * 1024f,
        25f * 1024f * 1024f,
        50f * 1024f * 1024f,
        100f * 1024f * 1024f,
    )
    return steps.firstOrNull { it >= target } ?: target
}
