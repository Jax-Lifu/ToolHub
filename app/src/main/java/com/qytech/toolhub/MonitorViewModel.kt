package com.qytech.toolhub

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qytech.toolhub.data.CpuTickSnapshot
import com.qytech.toolhub.data.DiskCountersSnapshot
import com.qytech.toolhub.data.LatestTelemetryDetails
import com.qytech.toolhub.data.NetworkCountersSnapshot
import com.qytech.toolhub.data.TelemetryPoint
import com.qytech.toolhub.data.TelemetryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

enum class SampleIntervalOption(val label: String, val millis: Long) {
    FAST("1s", 1000L),
    NORMAL("2s", 2000L),
    SLOW("5s", 5000L),
}

@Immutable
data class MonitorUiState(
    val points: List<TelemetryPoint> = emptyList(),
    val latestDetails: LatestTelemetryDetails = LatestTelemetryDetails(),
    val isRunning: Boolean = true,
    val interval: SampleIntervalOption = SampleIntervalOption.FAST,
    val maxPoints: Int = MAX_HISTORY_POINTS,
) {
    val latestPoint: TelemetryPoint?
        get() = points.lastOrNull()

    companion object {
        const val MAX_HISTORY_POINTS = 60
    }
}

class MonitorViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext

    private val _uiState = MutableStateFlow(MonitorUiState())
    val uiState: StateFlow<MonitorUiState> = _uiState.asStateFlow()

    private var prevCpuTicks: CpuTickSnapshot? = null
    private var prevNetCounters: NetworkCountersSnapshot? = null
    private var prevDiskCounters: DiskCountersSnapshot? = null
    private var samplingJob: Job? = null
    private var userManuallyPaused: Boolean = false

    init {
        startSampling()
    }

    fun toggleRunning() {
        val next = !_uiState.value.isRunning
        userManuallyPaused = !next
        _uiState.update { it.copy(isRunning = next) }
        if (next) {
            startSampling()
        } else {
            samplingJob?.cancel()
            samplingJob = null
        }
    }

    fun onLifecycleForeground() {
        if (!userManuallyPaused && samplingJob?.isActive != true) {
            _uiState.update { it.copy(isRunning = true) }
            startSampling()
        }
    }

    fun onLifecycleBackground() {
        if (!com.qytech.toolhub.data.StressTestRepository.state.value.isRunning) {
            samplingJob?.cancel()
            samplingJob = null
        }
    }

    fun setInterval(option: SampleIntervalOption) {
        if (_uiState.value.interval == option) return
        _uiState.update { it.copy(interval = option) }
        if (_uiState.value.isRunning) {
            startSampling()
        }
    }

    fun clearHistory() {
        _uiState.update { it.copy(points = emptyList()) }
    }

    private fun startSampling() {
        samplingJob?.cancel()
        samplingJob = viewModelScope.launch {
            // 首次采样先建立基线，确保第一条可视曲线点就有精准增量速率
            withContext(Dispatchers.IO) {
                if (prevCpuTicks == null) {
                    prevCpuTicks = TelemetryRepository.readCpuTickSnapshot()
                }
                if (prevNetCounters == null) {
                    prevNetCounters = TelemetryRepository.readNetworkCountersSnapshot()
                }
                if (prevDiskCounters == null) {
                    prevDiskCounters = TelemetryRepository.readDiskCountersSnapshot()
                }
            }
            delay(250L.milliseconds)

            while (isActive && _uiState.value.isRunning) {
                val sampled = withContext(Dispatchers.IO) { collectOneSample() }
                _uiState.update { state ->
                    val prevList = state.points
                    val updatedPoints = ArrayList<TelemetryPoint>(
                        minOf(prevList.size + 1, MonitorUiState.MAX_HISTORY_POINTS)
                    )
                    val dropCount =
                        (prevList.size + 1 - MonitorUiState.MAX_HISTORY_POINTS).coerceAtLeast(0)
                    for (i in dropCount until prevList.size) {
                        updatedPoints.add(prevList[i])
                    }
                    updatedPoints.add(sampled.first)
                    state.copy(
                        points = updatedPoints,
                        latestDetails = sampled.second,
                    )
                }
                delay(_uiState.value.interval.millis.milliseconds)
            }
        }
    }

    private fun collectOneSample(): Pair<TelemetryPoint, LatestTelemetryDetails> {
        val currCpu = TelemetryRepository.readCpuTickSnapshot()
        val cpu = TelemetryRepository.readCpuSample(prevCpuTicks, currCpu)
        prevCpuTicks = currCpu

        val gpu = TelemetryRepository.readGpuSample()
        val mem = TelemetryRepository.readMemorySample(appContext)

        val currNet = TelemetryRepository.readNetworkCountersSnapshot()
        val netRate = TelemetryRepository.computeNetworkRate(prevNetCounters, currNet)
        prevNetCounters = currNet

        val currDisk = TelemetryRepository.readDiskCountersSnapshot()
        val diskRate = TelemetryRepository.computeDiskRate(prevDiskCounters, currDisk)
        prevDiskCounters = currDisk

        val storage = TelemetryRepository.readStorageSpaceInfo()
        val gc = TelemetryRepository.readGcSample()
        val thermal = TelemetryRepository.readThermalSample()

        val jvmHeapUsedMb = (gc.jvmUsedBytes.toDouble() / (1024.0 * 1024.0)).toFloat()
        val point = TelemetryPoint(
            timestampMillis = System.currentTimeMillis(),
            cpuPercent = cpu.usagePercent,
            gpuPercent = gpu.usagePercent,
            gpuAvailable = gpu.available,
            memPercent = mem.usedPercent,
            diskReadBytesPerSec = diskRate.readBytesPerSec,
            diskWriteBytesPerSec = diskRate.writeBytesPerSec,
            diskIops = diskRate.readIops + diskRate.writeIops,
            jvmHeapUsedMB = jvmHeapUsedMb,
            gcPauseMs = gc.gcTimeMs.toFloat(),
            rxBytesPerSec = netRate.rxBytesPerSec,
            txBytesPerSec = netRate.txBytesPerSec,
            socTempCelsius = thermal.socCelsius,
            gpuTempCelsius = thermal.gpuCelsius,
        )

        val gpuFreqText = if (gpu.available && gpu.curFreqMHz != null) {
            if (gpu.maxFreqMHz != null) "${gpu.curFreqMHz} / ${gpu.maxFreqMHz} MHz"
            else "${gpu.curFreqMHz} MHz"
        } else {
            "-"
        }

        val zramText = if (mem.zramUsedBytes != null && mem.zramTotalBytes != null) {
            "${TelemetryRepository.formatBytesCompact(mem.zramUsedBytes)} / ${
                TelemetryRepository.formatBytesCompact(
                    mem.zramTotalBytes
                )
            }"
        } else {
            null
        }

        val details = LatestTelemetryDetails(
            cpuLoadAvg = cpu.loadAvg1m,
            cpuFreqSummary = cpu.primaryFreqText,
            cpuSource = cpu.sourceLabel,
            gpuNodeName = gpu.nodeName,
            gpuFreqSummary = gpuFreqText,
            gpuGovernor = gpu.governor,
            memUsedText = TelemetryRepository.formatBytesCompact(mem.usedBytes),
            memTotalText = TelemetryRepository.formatBytesCompact(mem.totalBytes),
            zramText = zramText,
            diskReadSpeedText = TelemetryRepository.formatBytesPerSec(diskRate.readBytesPerSec),
            diskWriteSpeedText = TelemetryRepository.formatBytesPerSec(diskRate.writeBytesPerSec),
            diskTotalReadText = TelemetryRepository.formatBytesCompact(diskRate.totalReadBytes),
            diskTotalWriteText = TelemetryRepository.formatBytesCompact(diskRate.totalWriteBytes),
            diskIopsText = String.format(
                Locale.US,
                "%.0f IOPS",
                diskRate.readIops + diskRate.writeIops
            ),
            storageSpaceText = "${TelemetryRepository.formatBytesCompact(storage.usedBytes)} / ${
                TelemetryRepository.formatBytesCompact(
                    storage.totalBytes
                )
            }",
            storagePercentText = String.format(Locale.US, "%.1f%%", storage.usedPercent),
            gcCountText = com.qytech.toolhub.data.AppLanguageManager.getString(
                getApplication(),
                R.string.gc_count_fmt,
                gc.gcCount
            ),
            gcTimeText = "${gc.gcTimeMs} ms",
            blockingGcText = com.qytech.toolhub.data.AppLanguageManager.getString(
                getApplication(),
                R.string.gc_count_fmt,
                gc.blockingGcCount
            ),
            jvmHeapText = "${TelemetryRepository.formatBytesCompact(gc.jvmUsedBytes)} / ${
                TelemetryRepository.formatBytesCompact(
                    gc.jvmMaxBytes
                )
            }",
            totalRxText = TelemetryRepository.formatBytesCompact(netRate.totalRxBytes),
            totalTxText = TelemetryRepository.formatBytesCompact(netRate.totalTxBytes),
            socTempLabel = thermal.socLabel,
            gpuTempLabel = thermal.gpuLabel,
        )
        return point to details
    }

    override fun onCleared() {
        super.onCleared()
        samplingJob?.cancel()
    }
}
