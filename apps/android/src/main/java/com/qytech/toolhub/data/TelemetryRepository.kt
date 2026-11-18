package com.qytech.toolhub.data

import android.app.ActivityManager
import android.content.Context
import android.net.TrafficStats
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import java.io.File
import java.io.FileInputStream
import java.util.Locale

data class CpuTickSnapshot(
    val idleTicks: Long,
    val totalTicks: Long,
)

data class CpuSample(
    val usagePercent: Float,
    val loadAvg1m: String,
    val primaryFreqText: String,
    val sourceLabel: String,
)

data class GpuSample(
    val available: Boolean,
    val usagePercent: Float,
    val curFreqMHz: Long?,
    val maxFreqMHz: Long?,
    val governor: String,
    val nodeName: String,
)

data class MemorySample(
    val usedPercent: Float,
    val usedBytes: Long,
    val totalBytes: Long,
    val zramUsedBytes: Long?,
    val zramTotalBytes: Long?,
)

data class NetworkCountersSnapshot(
    val rxBytes: Long,
    val txBytes: Long,
    val timestampNanos: Long,
)

data class NetworkRateSample(
    val rxBytesPerSec: Float,
    val txBytesPerSec: Float,
    val totalRxBytes: Long,
    val totalTxBytes: Long,
)

data class DiskCountersSnapshot(
    val readBytes: Long,
    val writeBytes: Long,
    val readOps: Long,
    val writeOps: Long,
    val timestampNanos: Long,
)

data class DiskRateSample(
    val readBytesPerSec: Float,
    val writeBytesPerSec: Float,
    val totalReadBytes: Long,
    val totalWriteBytes: Long,
    val readIops: Float,
    val writeIops: Float,
)

data class StorageSpaceInfo(
    val totalBytes: Long,
    val usedBytes: Long,
    val availBytes: Long,
    val usedPercent: Float,
)

data class GcSample(
    val gcCount: Long,
    val gcTimeMs: Long,
    val blockingGcCount: Long,
    val blockingGcTimeMs: Long,
    val bytesAllocated: Long,
    val bytesFreed: Long,
    val jvmUsedBytes: Long,
    val jvmMaxBytes: Long,
    val jvmTotalBytes: Long,
)

data class ThermalSample(
    val socCelsius: Float?,
    val gpuCelsius: Float?,
    val socLabel: String,
    val gpuLabel: String,
)

@Immutable
data class TelemetryPoint(
    val timestampMillis: Long,
    val cpuPercent: Float,
    val gpuPercent: Float,
    val gpuAvailable: Boolean,
    val memPercent: Float,
    val diskReadBytesPerSec: Float = 0f,
    val diskWriteBytesPerSec: Float = 0f,
    val diskIops: Float = 0f,
    val jvmHeapUsedMB: Float = 0f,
    val gcPauseMs: Float = 0f,
    val rxBytesPerSec: Float = 0f,
    val txBytesPerSec: Float = 0f,
    val socTempCelsius: Float? = null,
    val gpuTempCelsius: Float? = null,
)

data class LatestTelemetryDetails(
    val cpuLoadAvg: String = "-",
    val cpuFreqSummary: String = "-",
    val cpuSource: String = "Stat",
    val gpuNodeName: String = "-",
    val gpuFreqSummary: String = "-",
    val gpuGovernor: String = "-",
    val memUsedText: String = "-",
    val memTotalText: String = "-",
    val zramText: String? = null,
    val diskReadSpeedText: String = "0 B/s",
    val diskWriteSpeedText: String = "0 B/s",
    val diskTotalReadText: String = "-",
    val diskTotalWriteText: String = "-",
    val diskIopsText: String = "0 IOPS",
    val storageSpaceText: String = "-",
    val storagePercentText: String = "-",
    val gcCountText: String = "0",
    val gcTimeText: String = "0 ms",
    val blockingGcText: String = "0",
    val jvmHeapText: String = "-",
    val totalRxText: String = "-",
    val totalTxText: String = "-",
    val socTempLabel: String = "SoC",
    val gpuTempLabel: String = "GPU",
)

object TelemetryRepository {

    private val whitespaceRegex = Regex("\\s+")
    private val diskDevRegex = Regex("^(mmcblk[0-9]+|sd[a-z]|nvme[0-9]+n[0-9]+)$")

    @Volatile
    private var cachedGpuDevfreqDir: File? = null

    @Volatile
    private var gpuDevfreqProbed: Boolean = false

    @Volatile
    private var cachedStorageSpaceInfo: StorageSpaceInfo? = null

    @Volatile
    private var cachedStorageTimestampMs: Long = 0L

    internal fun readSmallFileText(file: File): String? {
        if (!file.exists()) return null
        return runCatching {
            val buf = ByteArray(512)
            val len = FileInputStream(file).use { it.read(buf) }
            if (len <= 0) "" else String(buf, 0, len, Charsets.UTF_8).trim()
        }.getOrNull()
    }

    fun readCpuTickSnapshot(procStatFile: File = File("/proc/stat")): CpuTickSnapshot? =
        runCatching {
            if (!procStatFile.canRead()) return@runCatching null
            val firstLine = readSmallFileText(procStatFile)?.lineSequence()?.firstOrNull()
                ?: procStatFile.bufferedReader().use { it.readLine() }
                ?: return@runCatching null
            parseProcStatCpuLine(firstLine)
        }.getOrNull()

    internal fun parseProcStatCpuLine(line: String): CpuTickSnapshot? {
        val parts = line.trim().split(whitespaceRegex)
        if (parts.size < 5 || parts[0] != "cpu") return null
        val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
        if (values.size < 4) return null
        val user = values[0]
        val nice = values[1]
        val system = values[2]
        val idle = values[3]
        val iowait = values.getOrElse(4) { 0L }
        val irq = values.getOrElse(5) { 0L }
        val softirq = values.getOrElse(6) { 0L }
        val steal = values.getOrElse(7) { 0L }

        val idleAll = idle + iowait
        val nonIdle = user + nice + system + irq + softirq + steal
        return CpuTickSnapshot(
            idleTicks = idleAll,
            totalTicks = idleAll + nonIdle,
        )
    }

    fun computeCpuPercent(prev: CpuTickSnapshot?, curr: CpuTickSnapshot?): Float? {
        if (prev == null || curr == null) return null
        val totalDelta = curr.totalTicks - prev.totalTicks
        if (totalDelta <= 0L) return null
        val idleDelta = (curr.idleTicks - prev.idleTicks).coerceAtLeast(0L)
        val activeDelta = (totalDelta - idleDelta).coerceAtLeast(0L)
        return ((activeDelta.toDouble() / totalDelta.toDouble()) * 100.0).toFloat()
            .coerceIn(0f, 100f)
    }

    fun readCpuSample(
        prevTicks: CpuTickSnapshot?,
        currTicks: CpuTickSnapshot?,
    ): CpuSample {
        val policies = HardwareRepository.readCpuPoliciesFast()
        val freqText = policies.joinToString(" · ") { "${it.policyName}:${it.currentFreqText}" }
            .ifEmpty { "-" }
        val loadAvg = runCatching {
            val raw = readSmallFileText(File("/proc/loadavg")) ?: ""
            if (raw.isEmpty()) "-" else raw.split(whitespaceRegex).take(3).joinToString(" / ")
        }.getOrDefault("-")

        val statPct = computeCpuPercent(prevTicks, currTicks)
        if (statPct != null) {
            return CpuSample(
                usagePercent = statPct,
                loadAvg1m = loadAvg,
                primaryFreqText = freqText,
                sourceLabel = "/proc/stat",
            )
        }

        // 兜底：若 /proc/stat 不可读，按 CPU 频率比例估算负载
        val freqRatios = policies.mapNotNull { p ->
            val cur = p.currentKHz ?: return@mapNotNull null
            val max = p.maxKHz?.takeIf { it > 0L } ?: return@mapNotNull null
            (cur.toFloat() / max.toFloat() * 100f).coerceIn(0f, 100f)
        }
        val fallbackPct = if (freqRatios.isNotEmpty()) freqRatios.average().toFloat() else 0f
        return CpuSample(
            usagePercent = fallbackPct,
            loadAvg1m = loadAvg,
            primaryFreqText = freqText,
            sourceLabel = "cpufreq",
        )
    }

    private fun resolveGpuDevfreqDir(devfreqRoot: File): File? {
        val isDefaultRoot = devfreqRoot.path == "/sys/class/devfreq"
        if (isDefaultRoot && gpuDevfreqProbed) {
            return cachedGpuDevfreqDir
        }
        val dirs = devfreqRoot.listFiles { file ->
            file.isDirectory && file.name.contains("gpu", ignoreCase = true)
        }?.sortedBy { it.name } ?: emptyList()
        val found = dirs.firstOrNull()
        if (isDefaultRoot) {
            cachedGpuDevfreqDir = found
            gpuDevfreqProbed = true
        }
        return found
    }

    fun readGpuSample(devfreqRoot: File = File("/sys/class/devfreq")): GpuSample {
        val gpuDir = resolveGpuDevfreqDir(devfreqRoot) ?: return GpuSample(
            available = false,
            usagePercent = 0f,
            curFreqMHz = null,
            maxFreqMHz = null,
            governor = "-",
            nodeName = "none",
        )

        val loadRaw = readSmallFileText(File(gpuDir, "load"))
        val curHz = readSmallFileText(File(gpuDir, "cur_freq"))?.toLongOrNull()
        val maxHz = readSmallFileText(File(gpuDir, "max_freq"))?.toLongOrNull()
        val governor = readSmallFileText(File(gpuDir, "governor"))?.ifEmpty { "-" } ?: "-"

        val loadPct = parseDevfreqLoad(loadRaw) ?: run {
            if (curHz != null && maxHz != null && maxHz > 0L) {
                (curHz.toDouble() / maxHz.toDouble() * 100.0).toFloat().coerceIn(0f, 100f)
            } else {
                0f
            }
        }

        return GpuSample(
            available = true,
            usagePercent = loadPct,
            curFreqMHz = curHz?.let { it / 1_000_000L },
            maxFreqMHz = maxHz?.let { it / 1_000_000L },
            governor = governor,
            nodeName = gpuDir.name,
        )
    }

    internal fun parseDevfreqLoad(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        val pctPart = raw.substringBefore("@").trim().removeSuffix("%").trim()
        return pctPart.toFloatOrNull()?.coerceIn(0f, 100f)
    }

    fun readMemorySample(
        context: Context,
        procMemInfoFile: File = File("/proc/meminfo")
    ): MemorySample {
        val am = context.getSystemService(ActivityManager::class.java)
        val memInfo = ActivityManager.MemoryInfo()
        var usedBytes = 0L
        var totalBytes = 0L
        var usedPct = 0f

        if (am != null) {
            am.getMemoryInfo(memInfo)
            totalBytes = memInfo.totalMem.coerceAtLeast(1L)
            usedBytes = (memInfo.totalMem - memInfo.availMem).coerceAtLeast(0L)
            usedPct =
                (usedBytes.toDouble() / totalBytes.toDouble() * 100.0).toFloat().coerceIn(0f, 100f)
        }

        var zramUsed: Long? = null
        var zramTotal: Long? = null
        runCatching {
            if (procMemInfoFile.canRead()) {
                var swapTotalKb: Long? = null
                var swapFreeKb: Long? = null
                procMemInfoFile.bufferedReader(Charsets.UTF_8, 2048).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        when {
                            line.startsWith("SwapTotal:") -> swapTotalKb = parseMeminfoKb(line)
                            line.startsWith("SwapFree:") -> swapFreeKb = parseMeminfoKb(line)
                        }
                        if (swapTotalKb != null && swapFreeKb != null) break
                    }
                }
                val st = swapTotalKb
                val sf = swapFreeKb
                if (st != null && sf != null && st > 0L) {
                    zramTotal = st * 1024L
                    zramUsed = (st - sf).coerceAtLeast(0L) * 1024L
                }
            }
        }

        return MemorySample(
            usedPercent = usedPct,
            usedBytes = usedBytes,
            totalBytes = totalBytes,
            zramUsedBytes = zramUsed,
            zramTotalBytes = zramTotal,
        )
    }

    private fun parseMeminfoKb(line: String): Long? =
        line.substringAfter(":").trim().substringBefore(" ").toLongOrNull()

    fun readNetworkCountersSnapshot(netRoot: File = File("/sys/class/net")): NetworkCountersSnapshot {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val trafficRx = TrafficStats.getTotalRxBytes()
        val trafficTx = TrafficStats.getTotalTxBytes()
        if (trafficRx != TrafficStats.UNSUPPORTED.toLong() && trafficTx != TrafficStats.UNSUPPORTED.toLong() &&
            trafficRx >= 0L && trafficTx >= 0L
        ) {
            return NetworkCountersSnapshot(
                rxBytes = trafficRx,
                txBytes = trafficTx,
                timestampNanos = nowNs,
            )
        }

        var rxSum = 0L
        var txSum = 0L
        netRoot.listFiles()?.forEach { nic ->
            val name = nic.name
            if (name == "lo" || name.startsWith("dummy") || name.startsWith("ifb") || name.startsWith(
                    "sit"
                )
            ) {
                return@forEach
            }
            val statsDir = File(nic, "statistics")
            if (!statsDir.isDirectory) return@forEach
            rxSum += readSmallFileText(File(statsDir, "rx_bytes"))?.toLongOrNull() ?: 0L
            txSum += readSmallFileText(File(statsDir, "tx_bytes"))?.toLongOrNull() ?: 0L
        }
        return NetworkCountersSnapshot(
            rxBytes = rxSum,
            txBytes = txSum,
            timestampNanos = nowNs,
        )
    }

    fun computeNetworkRate(
        prev: NetworkCountersSnapshot?,
        curr: NetworkCountersSnapshot
    ): NetworkRateSample {
        if (prev == null) {
            return NetworkRateSample(0f, 0f, curr.rxBytes, curr.txBytes)
        }
        val elapsedNs = curr.timestampNanos - prev.timestampNanos
        if (elapsedNs <= 0L) {
            return NetworkRateSample(0f, 0f, curr.rxBytes, curr.txBytes)
        }
        val elapsedSec = elapsedNs.toDouble() / 1_000_000_000.0
        val rxDelta = (curr.rxBytes - prev.rxBytes).coerceAtLeast(0L)
        val txDelta = (curr.txBytes - prev.txBytes).coerceAtLeast(0L)
        return NetworkRateSample(
            rxBytesPerSec = (rxDelta / elapsedSec).toFloat(),
            txBytesPerSec = (txDelta / elapsedSec).toFloat(),
            totalRxBytes = curr.rxBytes,
            totalTxBytes = curr.txBytes,
        )
    }

    fun readThermalSample(): ThermalSample {
        val zones = HardwareRepository.readThermalZonesFast()
        val soc = zones.firstOrNull {
            it.type.contains(
                "soc",
                ignoreCase = true
            ) && it.celsiusApprox != null
        }
            ?: zones.firstOrNull { it.celsiusApprox != null }
        val gpu = zones.firstOrNull {
            it.type.contains(
                "gpu",
                ignoreCase = true
            ) && it.celsiusApprox != null
        }
            ?: zones.firstOrNull {
                it.type.contains(
                    "big",
                    ignoreCase = true
                ) && it.celsiusApprox != null
            }
        return ThermalSample(
            socCelsius = soc?.celsiusApprox?.toFloat(),
            gpuCelsius = gpu?.celsiusApprox?.toFloat(),
            socLabel = soc?.type ?: "SoC",
            gpuLabel = gpu?.type ?: "GPU",
        )
    }

    fun readDiskCountersSnapshot(diskstatsFile: File = File("/proc/diskstats")): DiskCountersSnapshot {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        var readSectors = 0L
        var writeSectors = 0L
        var readOps = 0L
        var writeOps = 0L

        runCatching {
            if (diskstatsFile.canRead()) {
                diskstatsFile.bufferedReader(Charsets.UTF_8, 2048).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        val parts = line.trim().split(whitespaceRegex)
                        if (parts.size >= 11) {
                            val devName = parts[2]
                            if (diskDevRegex.matches(devName)) {
                                readOps += parts[3].toLongOrNull() ?: 0L
                                readSectors += parts[5].toLongOrNull() ?: 0L
                                writeOps += parts[7].toLongOrNull() ?: 0L
                                writeSectors += parts[9].toLongOrNull() ?: 0L
                            }
                        }
                    }
                }
            }
        }

        val readBytes = readSectors * 512L
        val writeBytes = writeSectors * 512L
        return DiskCountersSnapshot(
            readBytes = readBytes,
            writeBytes = writeBytes,
            readOps = readOps,
            writeOps = writeOps,
            timestampNanos = nowNs,
        )
    }

    fun computeDiskRate(prev: DiskCountersSnapshot?, curr: DiskCountersSnapshot): DiskRateSample {
        if (prev == null) {
            return DiskRateSample(
                readBytesPerSec = 0f,
                writeBytesPerSec = 0f,
                totalReadBytes = curr.readBytes,
                totalWriteBytes = curr.writeBytes,
                readIops = 0f,
                writeIops = 0f,
            )
        }
        val elapsedNs = curr.timestampNanos - prev.timestampNanos
        if (elapsedNs <= 0L) {
            return DiskRateSample(
                readBytesPerSec = 0f,
                writeBytesPerSec = 0f,
                totalReadBytes = curr.readBytes,
                totalWriteBytes = curr.writeBytes,
                readIops = 0f,
                writeIops = 0f,
            )
        }
        val elapsedSec = elapsedNs.toDouble() / 1_000_000_000.0
        val readDelta = (curr.readBytes - prev.readBytes).coerceAtLeast(0L)
        val writeDelta = (curr.writeBytes - prev.writeBytes).coerceAtLeast(0L)
        val readOpsDelta = (curr.readOps - prev.readOps).coerceAtLeast(0L)
        val writeOpsDelta = (curr.writeOps - prev.writeOps).coerceAtLeast(0L)

        return DiskRateSample(
            readBytesPerSec = (readDelta / elapsedSec).toFloat(),
            writeBytesPerSec = (writeDelta / elapsedSec).toFloat(),
            totalReadBytes = curr.readBytes,
            totalWriteBytes = curr.writeBytes,
            readIops = (readOpsDelta / elapsedSec).toFloat(),
            writeIops = (writeOpsDelta / elapsedSec).toFloat(),
        )
    }

    fun readStorageSpaceInfo(): StorageSpaceInfo {
        val now = runCatching { SystemClock.elapsedRealtime() }.getOrDefault(0L)
        val cached = cachedStorageSpaceInfo
        if (cached != null && now > 0L && (now - cachedStorageTimestampMs) < 10_000L) {
            return cached
        }
        val info = runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            val total = stat.totalBytes
            val avail = stat.availableBytes
            val used = (total - avail).coerceAtLeast(0L)
            val pct = if (total > 0L) (used.toDouble() / total.toDouble() * 100.0).toFloat()
                .coerceIn(0f, 100f) else 0f
            StorageSpaceInfo(
                totalBytes = total,
                usedBytes = used,
                availBytes = avail,
                usedPercent = pct
            )
        }.getOrDefault(StorageSpaceInfo(0L, 0L, 0L, 0f))
        cachedStorageSpaceInfo = info
        cachedStorageTimestampMs = now
        return info
    }

    fun readGcSample(): GcSample {
        val rt = Runtime.getRuntime()
        val total = rt.totalMemory()
        val free = rt.freeMemory()
        val used = (total - free).coerceAtLeast(0L)
        val max = rt.maxMemory()

        val gcCount = android.os.Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0L
        val gcTimeMs = android.os.Debug.getRuntimeStat("art.gc.gc-time-ms")?.toLongOrNull() ?: 0L
        val bGcCount =
            android.os.Debug.getRuntimeStat("art.gc.blocking-gc-count")?.toLongOrNull() ?: 0L
        val bGcTimeMs =
            android.os.Debug.getRuntimeStat("art.gc.blocking-gc-time-ms")?.toLongOrNull() ?: 0L
        val bytesAllocated =
            android.os.Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: 0L
        val bytesFreed = android.os.Debug.getRuntimeStat("art.gc.bytes-freed")?.toLongOrNull() ?: 0L

        return GcSample(
            gcCount = gcCount,
            gcTimeMs = gcTimeMs,
            blockingGcCount = bGcCount,
            blockingGcTimeMs = bGcTimeMs,
            bytesAllocated = bytesAllocated,
            bytesFreed = bytesFreed,
            jvmUsedBytes = used,
            jvmMaxBytes = max,
            jvmTotalBytes = total,
        )
    }

    fun formatBytesPerSec(bytesPerSec: Float): String {
        val v = bytesPerSec.coerceAtLeast(0f)
        return when {
            v >= 1024f * 1024f -> String.format(Locale.US, "%.2f MB/s", v / (1024f * 1024f))
            v >= 1024f -> String.format(Locale.US, "%.1f KB/s", v / 1024f)
            else -> String.format(Locale.US, "%.0f B/s", v)
        }
    }

    fun formatBytesCompact(bytes: Long): String {
        val gb = 1024.0 * 1024.0 * 1024.0
        val mb = 1024.0 * 1024.0
        val kb = 1024.0
        return when {
            bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.1f KB", bytes / kb)
            else -> "$bytes B"
        }
    }
}
