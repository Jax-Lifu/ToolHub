package com.qytech.toolhub

import android.annotation.SuppressLint
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qytech.toolhub.data.CpuPolicyInfo
import com.qytech.toolhub.data.GpuDevfreqInfo
import com.qytech.toolhub.data.StressTestRepository
import com.qytech.toolhub.data.ThermalZoneInfo
import com.qytech.toolhub.ui.components.ChartSeries
import com.qytech.toolhub.ui.components.SectionCard
import com.qytech.toolhub.ui.components.StatusPill
import com.qytech.toolhub.ui.components.StressTestDialog
import com.qytech.toolhub.ui.components.TelemetryChartCard
import com.qytech.toolhub.ui.theme.semanticColors
import java.util.Locale
import kotlin.math.ceil
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ThermalScreen(
    monitorViewModel: MonitorViewModel,
    hardwareViewModel: HardwareViewModel,
    modifier: Modifier = Modifier,
) {
    val hwState by hardwareViewModel.uiState.collectAsState()
    var showStressTestDialog by remember { mutableStateOf(false) }
    var showCpuGpuTuningDialog by remember { mutableStateOf(false) }

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
            // 顶部控制栏（仅订阅低频控制状态，避免 1s 遥测刷新引起热区与调频卡片重组）
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
                        text = stringResource(R.string.thermal_header_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stringResource(R.string.thermal_header_sub_fmt, hwState.thermalZones.size, hwState.cpuPolicies.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                ThermalControlsRow(
                    monitorViewModel = monitorViewModel,
                    onOpenStressDialog = { showStressTestDialog = true },
                    onRefreshHardware = { hardwareViewModel.refresh() },
                )
            }

            if (singleScreen) {
                ThermalChartSection(
                    monitorViewModel = monitorViewModel,
                    fillAvailableHeight = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.55f),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.45f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ThermalZonesSummaryCard(
                        thermalZones = hwState.thermalZones,
                        compact = true,
                        modifier = Modifier
                            .weight(1.15f)
                            .fillMaxHeight(),
                    )
                    CpuPoliciesSummaryCard(
                        cpuPolicies = hwState.cpuPolicies,
                        gpuNodes = hwState.gpuDevfreqNodes,
                        compact = true,
                        onOpenTuning = { showCpuGpuTuningDialog = true },
                        modifier = Modifier
                            .weight(0.85f)
                            .fillMaxHeight(),
                    )
                }
            } else {
                ThermalChartSection(
                    monitorViewModel = monitorViewModel,
                    fillAvailableHeight = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                ThermalZonesSummaryCard(
                    thermalZones = hwState.thermalZones,
                    compact = false,
                )
                CpuPoliciesSummaryCard(
                    cpuPolicies = hwState.cpuPolicies,
                    gpuNodes = hwState.gpuDevfreqNodes,
                    compact = false,
                    onOpenTuning = { showCpuGpuTuningDialog = true },
                )
            }
        }

        if (showStressTestDialog) {
            StressTestDialog(onDismissRequest = { showStressTestDialog = false })
        }

        if (showCpuGpuTuningDialog) {
            CpuGpuTuningDialog(
                cpuPolicies = hwState.cpuPolicies,
                gpuNodes = hwState.gpuDevfreqNodes,
                onSetAllCpuGov = { hardwareViewModel.setAllCpuGovernors(it) },
                onSetCpuGov = { p, g -> hardwareViewModel.setCpuGovernor(p, g) },
                onSetCpuBounds = { p, minK, maxK -> hardwareViewModel.setCpuFreqBounds(p, minK, maxK) },
                onSetGpuGov = { n, g -> hardwareViewModel.setGpuGovernor(n, g) },
                onSetGpuBounds = { n, minH, maxH -> hardwareViewModel.setGpuFreqBounds(n, minH, maxH) },
                onRefresh = { hardwareViewModel.refresh() },
                onDismissRequest = { showCpuGpuTuningDialog = false },
            )
        }
    }
}

@Composable
private fun ThermalControlsRow(
    monitorViewModel: MonitorViewModel,
    onOpenStressDialog: () -> Unit,
    onRefreshHardware: () -> Unit,
) {
    val controlFlow = remember(monitorViewModel) {
        monitorViewModel.uiState.map { it.interval to it.isRunning }.distinctUntilChanged()
    }
    val (interval, isRunning) = controlFlow.collectAsState(
        initial = monitorViewModel.uiState.value.let { it.interval to it.isRunning }
    ).value

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SingleChoiceSegmentedButtonRow {
            SampleIntervalOption.entries.forEachIndexed { idx, opt ->
                SegmentedButton(
                    selected = interval == opt,
                    onClick = { monitorViewModel.setInterval(opt) },
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
            onClick = { monitorViewModel.toggleRunning() },
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

        ThermalStressButton(onClick = onOpenStressDialog)

        IconButton(onClick = { monitorViewModel.clearHistory() }) {
            Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.cd_clear_chart))
        }
        IconButton(onClick = onRefreshHardware) {
            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.cd_refresh_thermal))
        }
    }
}

@Composable
private fun ThermalStressButton(onClick: () -> Unit) {
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
                stringResource(R.string.status_stress_running_fmt, stressState.elapsedSeconds) +
                    if (stressState.durationSeconds > 0) "/${stressState.durationSeconds}s" else ""
            } else {
                stringResource(R.string.action_stress_test)
            }
        )
    }
}

@Composable
private fun ThermalChartSection(
    monitorViewModel: MonitorViewModel,
    fillAvailableHeight: Boolean,
    modifier: Modifier = Modifier,
) {
    val monState by monitorViewModel.uiState.collectAsState()
    val points = monState.points
    val latest = monState.latestPoint
    val details = monState.latestDetails
    val semantic = MaterialTheme.semanticColors

    val socTemps = remember(points) { points.mapNotNull { it.socTempCelsius } }
    val gpuTemps = remember(points) { points.mapNotNull { it.gpuTempCelsius } }
    val curSoc = latest?.socTempCelsius
    val curGpu = latest?.gpuTempCelsius
    val maxTempSeen = remember(socTemps, gpuTemps) {
        maxOf(
            socTemps.maxOrNull() ?: 60f,
            gpuTemps.maxOrNull() ?: 60f,
        )
    }
    val minScale = 20f
    val maxScale = remember(maxTempSeen) {
        (ceil((maxTempSeen + 10f) / 10f) * 10f).coerceIn(80f, 110f)
    }
    val tempBadgeColor = when {
        curSoc == null -> MaterialTheme.colorScheme.onSurfaceVariant
        curSoc >= 80f -> semantic.danger
        curSoc >= 68f -> semantic.warning
        else -> semantic.success
    }
    val seriesList = buildList {
        add(
            ChartSeries(
                label = details.socTempLabel,
                color = semantic.danger,
                values = socTemps,
                currentValueText = curSoc?.let { String.format(Locale.US, "%.1f °C", it) } ?: "-",
                fillArea = true,
            )
        )
        if (gpuTemps.isNotEmpty() && details.gpuTempLabel != details.socTempLabel) {
            add(
                ChartSeries(
                    label = details.gpuTempLabel,
                    color = semantic.warning,
                    values = gpuTemps,
                    currentValueText = curGpu?.let { String.format(Locale.US, "%.1f °C", it) } ?: "-",
                    fillArea = false,
                )
            )
        }
    }

    TelemetryChartCard(
        title = stringResource(R.string.thermal_chart_title),
        icon = Icons.Default.Thermostat,
        badgeText = curSoc?.let { String.format(Locale.US, "%.1f °C", it) } ?: "-",
        badgeColor = tempBadgeColor,
        seriesList = seriesList,
        minY = minScale,
        maxY = maxScale,
        maxSlots = monState.maxPoints,
        yAxisTopLabel = "${maxScale.toInt()}°C",
        yAxisMidLabel = "${((minScale + maxScale) / 2f).toInt()}°C",
        yAxisBottomLabel = "${minScale.toInt()}°C",
        fillAvailableHeight = fillAvailableHeight,
        extraStatsText = if (socTemps.isNotEmpty()) {
            stringResource(
                R.string.thermal_chart_stats_fmt,
                socTemps.max(),
                socTemps.average(),
                points.size,
                monState.maxPoints,
            )
        } else null,
        footerText = stringResource(R.string.thermal_chart_footer),
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThermalZonesSummaryCard(
    thermalZones: List<ThermalZoneInfo>,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val semantic = MaterialTheme.semanticColors
    SectionCard(
        title = stringResource(R.string.thermal_sensors_title),
        subtitle = "/sys/class/thermal/thermal_zone* (${thermalZones.size})",
        icon = Icons.Default.Thermostat,
        compact = compact,
        modifier = modifier,
    ) {
        if (thermalZones.isEmpty()) {
            Text(
                text = stringResource(R.string.thermal_no_sensors),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                thermalZones.forEach { zone ->
                    val tempColor = when {
                        zone.celsiusApprox == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        zone.celsiusApprox >= 80.0 -> semantic.danger
                        zone.celsiusApprox >= 68.0 -> semantic.warning
                        else -> semantic.success
                    }
                    StatusPill(
                        text = "${zone.type}: ${zone.formattedTemp}",
                        color = tempColor,
                        icon = Icons.Default.Thermostat,
                        compact = compact,
                    )
                }
            }
        }
    }
}

@Composable
private fun CpuPoliciesSummaryCard(
    cpuPolicies: List<CpuPolicyInfo>,
    gpuNodes: List<GpuDevfreqInfo>,
    compact: Boolean,
    onOpenTuning: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = MaterialTheme.semanticColors
    SectionCard(
        title = stringResource(R.string.thermal_cpufreq_title),
        subtitle = stringResource(R.string.thermal_cpufreq_sub),
        icon = Icons.Default.Memory,
        compact = compact,
        modifier = modifier,
        headerAction = {
            OutlinedButton(
                onClick = onOpenTuning,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(30.dp),
            ) {
                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.cpufreq_btn_tune), fontSize = 11.sp)
            }
        },
    ) {
        if (cpuPolicies.isEmpty()) {
            Text(
                text = stringResource(R.string.thermal_no_cpufreq),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            cpuPolicies.forEachIndexed { idx, policy ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onOpenTuning() }
                        .padding(vertical = if (compact) 4.dp else 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${policy.policyName} (cpu ${policy.relatedCpus})",
                            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${policy.rangeText} · ${policy.governor}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusPill(
                        text = policy.currentFreqText,
                        color = if (policy.governor == "performance") semantic.warning else semantic.info,
                        compact = compact,
                    )
                }
                if (idx < cpuPolicies.lastIndex || gpuNodes.isNotEmpty()) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        thickness = 0.6.dp,
                    )
                }
            }
            gpuNodes.forEach { gpu ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onOpenTuning() }
                        .padding(vertical = if (compact) 4.dp else 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "GPU (${gpu.nodeName})",
                            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${gpu.rangeText} · ${gpu.governor}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusPill(
                        text = gpu.currentFreqText,
                        color = semantic.info,
                        compact = compact,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CpuGpuTuningDialog(
    cpuPolicies: List<CpuPolicyInfo>,
    gpuNodes: List<GpuDevfreqInfo>,
    onSetAllCpuGov: (String) -> Unit,
    onSetCpuGov: (String, String) -> Unit,
    onSetCpuBounds: (String, Long, Long) -> Unit,
    onSetGpuGov: (String, String) -> Unit,
    onSetGpuBounds: (String, Long, Long) -> Unit,
    onRefresh: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val semantic = MaterialTheme.semanticColors
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.cpufreq_dlg_title), fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh), modifier = Modifier.size(18.dp))
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 全局一键模式
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.cpufreq_all_clusters),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            listOf("performance", "schedutil", "powersave", "interactive", "ondemand").forEach { gov ->
                                OutlinedButton(
                                    onClick = { onSetAllCpuGov(gov) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp),
                                ) {
                                    Text(gov, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }

                // 各 CPU Policy 详细调频与锁频
                cpuPolicies.forEach { policy ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${policy.policyName} (CPU ${policy.relatedCpus})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                StatusPill(
                                    text = "${policy.currentFreqText} · ${policy.governor}",
                                    color = if (policy.governor == "performance") semantic.warning else semantic.info,
                                    compact = true,
                                )
                            }

                            val govs = policy.availableGovernors.ifEmpty {
                                listOf("schedutil", "performance", "powersave", "userspace")
                            }
                            Text(
                                text = stringResource(R.string.cpufreq_governor_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                govs.forEach { gov ->
                                    FilterChip(
                                        selected = policy.governor == gov,
                                        onClick = { onSetCpuGov(policy.policyName, gov) },
                                        label = { Text(gov, fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                                        modifier = Modifier.height(28.dp),
                                    )
                                }
                            }

                            if (policy.availableFrequenciesKHz.isNotEmpty()) {
                                val minF = policy.availableFrequenciesKHz.first()
                                val maxF = policy.availableFrequenciesKHz.last()
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "${stringResource(R.string.cpufreq_lock_freq_label)} (${policy.rangeText})",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    TextButton(
                                        onClick = { onSetCpuBounds(policy.policyName, minF, maxF) },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                        modifier = Modifier.height(26.dp),
                                    ) {
                                        Text(stringResource(R.string.cpufreq_unlock_range), fontSize = 11.sp)
                                    }
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    policy.availableFrequenciesKHz.forEach { khz ->
                                        val isLocked = policy.minKHz == khz && policy.maxKHz == khz
                                        FilterChip(
                                            selected = isLocked,
                                            onClick = { onSetCpuBounds(policy.policyName, khz, khz) },
                                            label = {
                                                Text(
                                                    CpuPolicyInfo.formatFreqKHz(khz),
                                                    fontSize = 10.5.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                )
                                            },
                                            modifier = Modifier.height(26.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // GPU Devfreq 调频与锁频
                gpuNodes.forEach { gpu ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "GPU (${gpu.nodeName})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                StatusPill(
                                    text = "${gpu.currentFreqText} · ${gpu.governor}",
                                    color = semantic.info,
                                    compact = true,
                                )
                            }

                            if (gpu.availableGovernors.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    gpu.availableGovernors.forEach { gov ->
                                        FilterChip(
                                            selected = gpu.governor == gov,
                                            onClick = { onSetGpuGov(gpu.nodeName, gov) },
                                            label = { Text(gov, fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                                            modifier = Modifier.height(28.dp),
                                        )
                                    }
                                }
                            }

                            if (gpu.availableFrequenciesHz.isNotEmpty()) {
                                val minH = gpu.availableFrequenciesHz.first()
                                val maxH = gpu.availableFrequenciesHz.last()
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "${stringResource(R.string.cpufreq_lock_freq_label)} (${gpu.rangeText})",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    TextButton(
                                        onClick = { onSetGpuBounds(gpu.nodeName, minH, maxH) },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                        modifier = Modifier.height(26.dp),
                                    ) {
                                        Text(stringResource(R.string.cpufreq_unlock_range), fontSize = 11.sp)
                                    }
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    gpu.availableFrequenciesHz.forEach { hz ->
                                        val isLocked = gpu.minHz == hz && gpu.maxHz == hz
                                        FilterChip(
                                            selected = isLocked,
                                            onClick = { onSetGpuBounds(gpu.nodeName, hz, hz) },
                                            label = {
                                                Text(
                                                    GpuDevfreqInfo.formatFreqHz(hz),
                                                    fontSize = 10.5.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                )
                                            },
                                            modifier = Modifier.height(26.dp),
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
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}
