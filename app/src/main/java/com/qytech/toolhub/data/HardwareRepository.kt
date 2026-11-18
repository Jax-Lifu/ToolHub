package com.qytech.toolhub.data

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import androidx.compose.runtime.Immutable
import com.qytech.toolhub.DeviceInfoUiState
import com.qytech.toolhub.HardwareUiState
import com.qytech.toolhub.OtgModeUiState
import com.qytech.toolhub.R
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

data class AlsaPcmStreamInfo(
    val pcmId: String,
    val streamName: String,
    val isActive: Boolean,
    val statusText: String,
    val format: String? = null,
    val rate: String? = null,
    val channels: String? = null,
    val periodSize: String? = null,
    val bufferSize: String? = null,
)

data class AlsaCardInfo(
    val index: Int,
    val id: String,
    val name: String,
    val description: String,
    val streams: List<AlsaPcmStreamInfo>,
) {
    val hasActiveStream: Boolean
        get() = streams.any { it.isActive }
}

data class AudioOutputDeviceSummary(
    val id: Int,
    val typeLabel: String,
    val productName: String,
    val sampleRates: String,
    val encodings: String,
)

data class AudioSystemProperties(
    val usbAudioInfo: String = "",
    val usbExclusive: String = "",
    val audioDirectDevice: String = "",
    val i2sMode: String = "",
)

data class BoardSysfsNode(
    val file: File,
    val path: String,
    val group: String,
    val name: String,
    val value: String?,
    val isWritable: Boolean,
    val isCustom: Boolean = false,
    val readError: String? = null,
)

@Immutable
data class ThermalZoneInfo(
    val name: String,
    val type: String,
    val rawTemp: Long,
    val formattedTemp: String,
    val celsiusApprox: Double?,
)

@Immutable
data class CpuPolicyInfo(
    val policyName: String,
    val currentKHz: Long?,
    val minKHz: Long?,
    val maxKHz: Long?,
    val governor: String,
    val relatedCpus: String,
    val availableGovernors: List<String> = emptyList(),
    val availableFrequenciesKHz: List<Long> = emptyList(),
    val isWritable: Boolean = false,
) {
    val currentFreqText: String
        get() = currentKHz?.let { formatFreqKHz(it) } ?: "-"

    val rangeText: String
        get() = if (minKHz != null && maxKHz != null) {
            "${formatFreqKHz(minKHz)} ~ ${formatFreqKHz(maxKHz)}"
        } else {
            "-"
        }

    companion object {
        fun formatFreqKHz(khz: Long): String = when {
            khz >= 1_000_000L -> String.format(Locale.US, "%.2f GHz", khz / 1_000_000.0)
            else -> "${khz / 1000L} MHz"
        }
    }
}

@Immutable
data class GpuDevfreqInfo(
    val nodeName: String,
    val path: String,
    val currentHz: Long?,
    val minHz: Long?,
    val maxHz: Long?,
    val governor: String,
    val availableGovernors: List<String> = emptyList(),
    val availableFrequenciesHz: List<Long> = emptyList(),
    val isWritable: Boolean = false,
) {
    val currentFreqText: String
        get() = currentHz?.let { formatFreqHz(it) } ?: "-"

    val rangeText: String
        get() = if (minHz != null && maxHz != null) {
            "${formatFreqHz(minHz)} ~ ${formatFreqHz(maxHz)}"
        } else {
            "-"
        }

    companion object {
        fun formatFreqHz(hz: Long): String = when {
            hz >= 1_000_000_000L -> String.format(Locale.US, "%.2f GHz", hz / 1_000_000_000.0)
            hz >= 1_000_000L -> "${hz / 1_000_000L} MHz"
            hz >= 1_000L -> "${hz / 1_000L} KHz"
            else -> "$hz Hz"
        }
    }
}

object HardwareRepository {

    private val cardHeaderRegex = Regex("""^\s*(\d+)\s+\[([^\]]+)\]:\s*(.+?)\s*-\s*(.+)$""")
    private val whitespaceRegex = Regex("\\s+")

    private data class CachedThermalZoneMeta(
        val dir: File,
        val name: String,
        val type: String,
    )

    private data class CachedCpuPolicyMeta(
        val dir: File,
        val policyName: String,
        val minKHz: Long?,
        val maxKHz: Long?,
        val relatedCpus: String,
        val availableGovernors: List<String>,
        val availableFrequenciesKHz: List<Long>,
        val isWritable: Boolean,
    )

    @Volatile
    private var cachedThermalZonesMeta: List<CachedThermalZoneMeta>? = null

    @Volatile
    private var cachedCpuPoliciesMeta: List<CachedCpuPolicyMeta>? = null

    private val BOARD_SYSFS_GROUPS = listOf(
        "ct7302" to "/sys/class/ct7302/ct7302",
        "knob_led" to "/sys/class/knob_led/knob",
        "tube_led" to "/sys/class/leds/tube",
        "backlight" to "/sys/class/backlight/backlight",
    )

    private val IGNORED_NODE_NAMES = setOf(
        "uevent",
        "dev",
        "modalias",
        "name",
        "trigger",
        "power",
        "subsystem",
        "device",
        "of_node",
        "waiting_for_supplier",
        "dump_regs",
    )

    fun readAlsaCards(asoundRoot: File = File("/proc/asound")): List<AlsaCardInfo> {
        val cardsFile = File(asoundRoot, "cards")
        if (!cardsFile.exists() || !cardsFile.canRead()) return emptyList()

        val rawLines = runCatching { cardsFile.readLines() }.getOrDefault(emptyList())
        val cards = mutableListOf<AlsaCardInfo>()
        var i = 0
        while (i < rawLines.size) {
            val match = cardHeaderRegex.matchEntire(rawLines[i])
            if (match != null) {
                val index = match.groupValues[1].toIntOrNull() ?: 0
                val id = match.groupValues[2].trim()
                val name = match.groupValues[4].trim()
                val description = rawLines.getOrNull(i + 1)?.trim().orEmpty().ifEmpty { name }
                val cardDir = File(asoundRoot, "card$index")
                cards += AlsaCardInfo(
                    index = index,
                    id = id,
                    name = name,
                    description = description,
                    streams = readCardPcmStreams(cardDir),
                )
                i += 2
            } else {
                i += 1
            }
        }
        return cards.sortedBy { it.index }
    }

    private fun readCardPcmStreams(cardDir: File): List<AlsaPcmStreamInfo> {
        val pcmDirs = cardDir.listFiles { file ->
            file.isDirectory && file.name.startsWith("pcm")
        }?.sortedBy { it.name } ?: return emptyList()

        return pcmDirs.mapNotNull { pcmDir ->
            val infoText = runCatching { File(pcmDir, "info").readText() }.getOrDefault("")
            val streamName = infoText.lineSequence()
                .firstOrNull { it.startsWith("name:") }
                ?.substringAfter("name:")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: pcmDir.name

            val sub0 = File(pcmDir, "sub0")
            if (!sub0.exists()) return@mapNotNull null

            val hwRaw = runCatching { File(sub0, "hw_params").readText().trim() }.getOrDefault("")
            val statusRaw = runCatching { File(sub0, "status").readText().trim() }.getOrDefault("")

            val isActive = hwRaw.isNotEmpty() && !hwRaw.equals("closed", ignoreCase = true)
            val statusLine = statusRaw.lineSequence().firstOrNull()?.trim().orEmpty()
                .ifEmpty { if (isActive) "RUNNING" else "closed" }

            if (!isActive) {
                AlsaPcmStreamInfo(
                    pcmId = pcmDir.name,
                    streamName = streamName,
                    isActive = false,
                    statusText = statusLine,
                )
            } else {
                val params = parseHwParams(hwRaw)
                AlsaPcmStreamInfo(
                    pcmId = pcmDir.name,
                    streamName = streamName,
                    isActive = true,
                    statusText = statusLine.removePrefix("state:").trim(),
                    format = params["format"],
                    rate = params["rate"]?.substringBefore(" ")?.let { "${it} Hz" }
                        ?: params["rate"],
                    channels = params["channels"],
                    periodSize = params["period_size"],
                    bufferSize = params["buffer_size"],
                )
            }
        }
    }

    internal fun parseHwParams(raw: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        raw.lineSequence().forEach { line ->
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim()
                map[key] = value
            }
        }
        return map
    }

    fun readAudioOutputDevices(context: Context): List<AudioOutputDeviceSummary> = runCatching {
        val am = context.getSystemService(AudioManager::class.java) ?: return emptyList()
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { dev ->
            val rates = dev.sampleRates
                .takeIf { it.isNotEmpty() }
                ?.sorted()
                ?.joinToString(", ") { rate ->
                    if (rate >= 1000 && rate % 1000 == 0) "${rate / 1000}k"
                    else if (rate == 44100) "44.1k"
                    else if (rate == 88200) "88.2k"
                    else if (rate == 176400) "176.4k"
                    else if (rate == 352800) "352.8k"
                    else "${rate}Hz"
                }
                ?: AppLanguageManager.getString(context, R.string.audio_adaptive)
            val encs = dev.encodings
                .takeIf { it.isNotEmpty() }
                ?.map(::encodingLabel)
                ?.distinct()
                ?.joinToString(", ")
                ?: AppLanguageManager.getString(
                    context,
                    R.string.audio_default_enc
                )
            AudioOutputDeviceSummary(
                id = dev.id,
                typeLabel = audioDeviceTypeLabel(context, dev.type),
                productName = dev.productName?.toString()?.trim().orEmpty().ifEmpty {
                    AppLanguageManager.getString(
                        context,
                        R.string.audio_sys_device
                    )
                },
                sampleRates = rates,
                encodings = encs,
            )
        }
    }.getOrDefault(emptyList())

    fun readAudioProperties(props: Map<String, String>? = null): AudioSystemProperties {
        return AudioSystemProperties(
            usbAudioInfo = props?.get("audio.usb.info")
                ?: SystemPropertiesHelper.get("audio.usb.info"),
            usbExclusive = props?.get("vendor.audio_usb_exclusive")
                ?: SystemPropertiesHelper.get("vendor.audio_usb_exclusive"),
            audioDirectDevice = props?.get("persist.vendor.audio_direct.device")
                ?: SystemPropertiesHelper.get("persist.vendor.audio_direct.device"),
            i2sMode = props?.get("persist.sys.audio.i2s")
                ?: SystemPropertiesHelper.get("persist.sys.audio.i2s"),
        )
    }

    fun discoverBoardSysfsNodes(customPaths: List<String> = emptyList()): List<BoardSysfsNode> {
        val seenPaths = HashSet<String>()
        val result = mutableListOf<BoardSysfsNode>()

        for ((groupName, dirPath) in BOARD_SYSFS_GROUPS) {
            val dir = File(dirPath)
            if (!dir.isDirectory) continue
            val files = dir.listFiles()?.sortedBy { it.name } ?: continue
            for (file in files) {
                if (file.isDirectory || file.name in IGNORED_NODE_NAMES) continue
                val canonical = runCatching { file.canonicalFile }.getOrDefault(file)
                if (!seenPaths.add(canonical.path)) continue
                result += readSysfsNode(canonical, groupName, isCustom = false)
            }
        }

        for (customPath in customPaths) {
            val file = File(customPath)
            val canonical = runCatching { file.canonicalFile }.getOrDefault(file)
            if (!seenPaths.add(canonical.path)) continue
            result += readSysfsNode(canonical, group = "custom", isCustom = true)
        }

        return result.sortedWith(
            compareByDescending<BoardSysfsNode> { it.isCustom }
                .thenByDescending { it.isWritable }
                .thenBy { it.group }
                .thenBy { it.name }
        )
    }

    fun readSysfsNode(file: File, group: String, isCustom: Boolean): BoardSysfsNode {
        if (!file.exists()) {
            return BoardSysfsNode(
                file = file,
                path = file.path,
                group = group,
                name = file.name,
                value = null,
                isWritable = false,
                isCustom = isCustom,
                readError = "path_not_found",
            )
        }
        val canRead = runCatching { file.canRead() }.getOrDefault(false)
        val canWrite = runCatching { file.canWrite() }.getOrDefault(false)
        var value: String? = null
        var readError: String? = null
        if (canRead) {
            runCatching { file.readText().trim() }
                .onSuccess { value = it.ifEmpty { "<empty>" } }
                .onFailure { readError = it.message ?: "read_failed" }
        } else {
            readError = "permission_denied"
        }

        return BoardSysfsNode(
            file = file,
            path = file.path,
            group = group,
            name = file.name,
            value = value,
            isWritable = canWrite,
            isCustom = isCustom,
            readError = readError,
        )
    }

    fun writeSysfsNode(
        file: File,
        value: String,
        group: String,
        isCustom: Boolean
    ): Result<BoardSysfsNode> {
        return runCatching {
            FileOutputStream(file).use { stream ->
                stream.write(value.toByteArray(Charsets.UTF_8))
                stream.flush()
            }
            readSysfsNode(file, group, isCustom)
        }
    }

    fun readThermalZonesFast(thermalRoot: File = File("/sys/class/thermal")): List<ThermalZoneInfo> {
        val isDefaultRoot = thermalRoot.path == "/sys/class/thermal"
        if (!isDefaultRoot) return readThermalZones(thermalRoot)

        var metas = cachedThermalZonesMeta
        if (metas == null) {
            val dirs = thermalRoot.listFiles { file ->
                file.isDirectory && file.name.startsWith("thermal_zone")
            }?.sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: Int.MAX_VALUE }
                ?: return emptyList()
            metas = dirs.mapNotNull { dir ->
                val type = TelemetryRepository.readSmallFileText(File(dir, "type"))
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                CachedThermalZoneMeta(dir = dir, name = dir.name, type = type)
            }
            cachedThermalZonesMeta = metas
        }

        return metas.mapNotNull { meta ->
            val rawTemp = readLongFile(File(meta.dir, "temp")) ?: return@mapNotNull null
            val celsius = if (rawTemp >= 10_000L) rawTemp / 1000.0 else null
            val formatted = if (celsius != null) {
                String.format(Locale.US, "%.1f °C", celsius)
            } else {
                "$rawTemp (raw)"
            }
            ThermalZoneInfo(
                name = meta.name,
                type = meta.type,
                rawTemp = rawTemp,
                formattedTemp = formatted,
                celsiusApprox = celsius,
            )
        }
    }

    fun readThermalZones(thermalRoot: File = File("/sys/class/thermal")): List<ThermalZoneInfo> {
        val dirs = thermalRoot.listFiles { file ->
            file.isDirectory && file.name.startsWith("thermal_zone")
        }?.sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: Int.MAX_VALUE }
            ?: return emptyList()

        return dirs.mapNotNull { dir ->
            val type =
                TelemetryRepository.readSmallFileText(File(dir, "type"))?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
            val rawTemp = readLongFile(File(dir, "temp"))
                ?: return@mapNotNull null
            val celsius = if (rawTemp >= 10_000L) rawTemp / 1000.0 else null
            val formatted = if (celsius != null) {
                String.format(Locale.US, "%.1f °C", celsius)
            } else {
                "$rawTemp (raw)"
            }
            ThermalZoneInfo(
                name = dir.name,
                type = type,
                rawTemp = rawTemp,
                formattedTemp = formatted,
                celsiusApprox = celsius,
            )
        }
    }

    fun readCpuPoliciesFast(cpufreqRoot: File = File("/sys/devices/system/cpu/cpufreq")): List<CpuPolicyInfo> {
        val isDefaultRoot = cpufreqRoot.path == "/sys/devices/system/cpu/cpufreq"
        if (!isDefaultRoot) return readCpuPolicies(cpufreqRoot)

        var metas = cachedCpuPoliciesMeta
        if (metas == null) {
            val dirs = cpufreqRoot.listFiles { file ->
                file.isDirectory && file.name.startsWith("policy")
            }?.sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: Int.MAX_VALUE }
                ?: return emptyList()
            metas = dirs.map { dir ->
                val govFile = File(dir, "scaling_governor")
                val availGovs = runCatching {
                    TelemetryRepository.readSmallFileText(File(dir, "scaling_available_governors"))
                        ?.split(whitespaceRegex)?.filter { it.isNotBlank() } ?: emptyList()
                }.getOrDefault(emptyList())
                val availFreqs = runCatching {
                    TelemetryRepository.readSmallFileText(
                        File(
                            dir,
                            "scaling_available_frequencies"
                        )
                    )
                        ?.split(whitespaceRegex)?.mapNotNull { it.toLongOrNull() }?.sorted()
                        ?: emptyList()
                }.getOrDefault(emptyList())
                CachedCpuPolicyMeta(
                    dir = dir,
                    policyName = dir.name,
                    minKHz = readLongFile(File(dir, "scaling_min_freq")),
                    maxKHz = readLongFile(File(dir, "scaling_max_freq")),
                    relatedCpus = TelemetryRepository.readSmallFileText(File(dir, "related_cpus"))
                        ?.ifEmpty { "-" } ?: "-",
                    availableGovernors = availGovs,
                    availableFrequenciesKHz = availFreqs,
                    isWritable = runCatching { govFile.canWrite() }.getOrDefault(false),
                )
            }
            cachedCpuPoliciesMeta = metas
        }

        return metas.map { meta ->
            CpuPolicyInfo(
                policyName = meta.policyName,
                currentKHz = readLongFile(File(meta.dir, "scaling_cur_freq")),
                minKHz = meta.minKHz,
                maxKHz = meta.maxKHz,
                governor = TelemetryRepository.readSmallFileText(File(meta.dir, "scaling_governor"))
                    ?.ifEmpty { "-" } ?: "-",
                relatedCpus = meta.relatedCpus,
                availableGovernors = meta.availableGovernors,
                availableFrequenciesKHz = meta.availableFrequenciesKHz,
                isWritable = meta.isWritable,
            )
        }
    }

    fun readCpuPolicies(cpufreqRoot: File = File("/sys/devices/system/cpu/cpufreq")): List<CpuPolicyInfo> {
        val dirs = cpufreqRoot.listFiles { file ->
            file.isDirectory && file.name.startsWith("policy")
        }?.sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: Int.MAX_VALUE }
            ?: return emptyList()

        return dirs.map { dir ->
            readSingleCpuPolicy(dir)
        }
    }

    private fun readSingleCpuPolicy(dir: File): CpuPolicyInfo {
        val govFile = File(dir, "scaling_governor")
        val availGovs = runCatching {
            TelemetryRepository.readSmallFileText(File(dir, "scaling_available_governors"))
                ?.split(whitespaceRegex)?.filter { it.isNotBlank() } ?: emptyList()
        }.getOrDefault(emptyList())
        val availFreqs = runCatching {
            TelemetryRepository.readSmallFileText(File(dir, "scaling_available_frequencies"))
                ?.split(whitespaceRegex)?.mapNotNull { it.toLongOrNull() }?.sorted() ?: emptyList()
        }.getOrDefault(emptyList())
        val isWritable = runCatching { govFile.canWrite() }.getOrDefault(false)

        return CpuPolicyInfo(
            policyName = dir.name,
            currentKHz = readLongFile(File(dir, "scaling_cur_freq")),
            minKHz = readLongFile(File(dir, "scaling_min_freq")),
            maxKHz = readLongFile(File(dir, "scaling_max_freq")),
            governor = TelemetryRepository.readSmallFileText(govFile)?.ifEmpty { "-" } ?: "-",
            relatedCpus = TelemetryRepository.readSmallFileText(File(dir, "related_cpus"))
                ?.ifEmpty { "-" } ?: "-",
            availableGovernors = availGovs,
            availableFrequenciesKHz = availFreqs,
            isWritable = isWritable,
        )
    }

    fun setCpuGovernor(
        policyName: String,
        governor: String,
        cpufreqRoot: File = File("/sys/devices/system/cpu/cpufreq"),
    ): Result<CpuPolicyInfo> = runCatching {
        val dir = File(cpufreqRoot, policyName)
        if (!dir.isDirectory) throw IllegalArgumentException("Policy not found: $policyName")
        val govFile = File(dir, "scaling_governor")
        writeRawFile(govFile, governor.trim())
        cachedCpuPoliciesMeta = null
        readSingleCpuPolicy(dir)
    }

    fun setAllCpuGovernors(
        governor: String,
        cpufreqRoot: File = File("/sys/devices/system/cpu/cpufreq"),
    ): Result<Int> = runCatching {
        val policies = readCpuPolicies(cpufreqRoot)
        var okCount = 0
        for (p in policies) {
            if (setCpuGovernor(p.policyName, governor, cpufreqRoot).isSuccess) {
                okCount++
            }
        }
        if (okCount == 0) throw IllegalStateException("No writable cpufreq policies")
        okCount
    }

    fun setCpuFreqBounds(
        policyName: String,
        minKHz: Long,
        maxKHz: Long,
        cpufreqRoot: File = File("/sys/devices/system/cpu/cpufreq"),
    ): Result<CpuPolicyInfo> = runCatching {
        val dir = File(cpufreqRoot, policyName)
        if (!dir.isDirectory) throw IllegalArgumentException("Policy not found: $policyName")
        val targetMin = minOf(minKHz, maxKHz)
        val targetMax = maxOf(minKHz, maxKHz)
        val curMax = readLongFile(File(dir, "scaling_max_freq")) ?: Long.MAX_VALUE
        val minFile = File(dir, "scaling_min_freq")
        val maxFile = File(dir, "scaling_max_freq")

        if (targetMin > curMax) {
            writeRawFile(maxFile, targetMax.toString())
            writeRawFile(minFile, targetMin.toString())
        } else {
            writeRawFile(minFile, targetMin.toString())
            writeRawFile(maxFile, targetMax.toString())
        }
        cachedCpuPoliciesMeta = null
        readSingleCpuPolicy(dir)
    }

    fun readGpuDevfreqNodes(devfreqRoot: File = File("/sys/class/devfreq")): List<GpuDevfreqInfo> {
        val dirs = devfreqRoot.listFiles { file ->
            file.isDirectory && file.name.contains("gpu", ignoreCase = true)
        }?.sortedBy { it.name } ?: return emptyList()

        return dirs.map { dir ->
            readSingleGpuDevfreq(dir)
        }
    }

    private fun readSingleGpuDevfreq(dir: File): GpuDevfreqInfo {
        val govFile = File(dir, "governor")
        val availGovs = runCatching {
            File(dir, "available_governors").readText().trim().split(Regex("\\s+"))
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
        val availFreqs = runCatching {
            File(dir, "available_frequencies").readText().trim().split(Regex("\\s+"))
                .mapNotNull { it.toLongOrNull() }.sorted()
        }.getOrDefault(emptyList())
        val isWritable = runCatching { govFile.canWrite() }.getOrDefault(false)

        return GpuDevfreqInfo(
            nodeName = dir.name,
            path = dir.absolutePath,
            currentHz = readLongFile(File(dir, "cur_freq")),
            minHz = readLongFile(File(dir, "min_freq")),
            maxHz = readLongFile(File(dir, "max_freq")),
            governor = runCatching { govFile.readText().trim() }.getOrDefault("-"),
            availableGovernors = availGovs,
            availableFrequenciesHz = availFreqs,
            isWritable = isWritable,
        )
    }

    fun setGpuGovernor(
        nodeName: String,
        governor: String,
        devfreqRoot: File = File("/sys/class/devfreq"),
    ): Result<GpuDevfreqInfo> = runCatching {
        val dir = File(devfreqRoot, nodeName)
        if (!dir.isDirectory) throw IllegalArgumentException("GPU devfreq not found: $nodeName")
        writeRawFile(File(dir, "governor"), governor.trim())
        readSingleGpuDevfreq(dir)
    }

    fun setGpuFreqBounds(
        nodeName: String,
        minHz: Long,
        maxHz: Long,
        devfreqRoot: File = File("/sys/class/devfreq"),
    ): Result<GpuDevfreqInfo> = runCatching {
        val dir = File(devfreqRoot, nodeName)
        if (!dir.isDirectory) throw IllegalArgumentException("GPU devfreq not found: $nodeName")
        val targetMin = minOf(minHz, maxHz)
        val targetMax = maxOf(minHz, maxHz)
        val curMax = readLongFile(File(dir, "max_freq")) ?: Long.MAX_VALUE
        val minFile = File(dir, "min_freq")
        val maxFile = File(dir, "max_freq")

        if (targetMin > curMax) {
            writeRawFile(maxFile, targetMax.toString())
            writeRawFile(minFile, targetMin.toString())
        } else {
            writeRawFile(minFile, targetMin.toString())
            writeRawFile(maxFile, targetMax.toString())
        }
        readSingleGpuDevfreq(dir)
    }

    private fun writeRawFile(file: File, value: String) {
        FileOutputStream(file).use { stream ->
            stream.write(value.toByteArray(Charsets.UTF_8))
            stream.flush()
        }
    }

    fun buildDiagnosticSnapshot(
        otgState: OtgModeUiState,
        deviceState: DeviceInfoUiState,
        hardwareState: HardwareUiState,
        context: Context? = null,
    ): String = buildString {
        val basic = deviceState.basicInfo
        val header = context?.let { AppLanguageManager.getString(it, R.string.home_env_snapshot) }
            ?: "Environment Diagnostics Snapshot"
        appendLine("# ToolHub $header")
        if (basic != null) {
            appendLine("- Model/Platform: ${basic.model} (${basic.boardName} / ${basic.socPlatform})")
            appendLine("- OS Version: Android ${basic.androidVersion} (API ${basic.sdkInt})")
            appendLine("- Build: ${basic.buildNumber}")
            appendLine("- Serial: ${basic.serialNumber}")
            appendLine("- UID: ${basic.uidSummary}")
        }
        appendLine("- Network: ${deviceState.primaryIpSummary}")
        deviceState.wifiInfo?.takeIf { it.isConnected }?.let {
            appendLine("  - Wi-Fi: ${it.ssid} (${it.ipAddress}, ${it.signalStrength} dBm)")
        }
        deviceState.ethernetList.filter { it.isConnected }.forEach {
            appendLine("  - Ethernet ${it.interfaceName}: ${it.ipAddress} (MAC ${it.macAddress})")
        }
        appendLine("- OTG Mode: ${otgState.aggregateModeLabel}")
        otgState.controllableNodes.forEach {
            appendLine("  - [writable] ${it.path} = ${it.rawValue ?: "-"}")
        }
        appendLine("- ADB Status: ADB_ENABLED=${if (otgState.adbEnabled) 1 else 0} | sys.usb.config=${otgState.usbProperties.usbConfig}")
        appendLine("- Boot Policy: autoOtg=${otgState.bootSettings.autoOtgOnBoot}, autoAdb=${otgState.bootSettings.autoAdbOnBoot}")
        appendLine("- USB Host Devices (${otgState.connectedUsbDevices.size}):")
        otgState.connectedUsbDevices.forEach {
            appendLine("  - ${it.displayName} [${it.vidPidHex}] (${it.classLabel})")
        }
        appendLine("- ALSA Cards (${hardwareState.alsaCards.size}):")
        hardwareState.alsaCards.forEach { card ->
            val active = card.streams.filter { it.isActive }
            val activeDesc = if (active.isEmpty()) "idle" else active.joinToString("; ") {
                "${it.pcmId}: ${it.format ?: ""} ${it.rate ?: ""} ${it.channels ?: ""}ch".trim()
            }
            appendLine("  - card${card.index} [${card.id}] ${card.name} -> $activeDesc")
        }
        if (hardwareState.thermalZones.isNotEmpty()) {
            val temps =
                hardwareState.thermalZones.joinToString(", ") { "${it.type}=${it.formattedTemp}" }
            appendLine("- Thermal Sensors: $temps")
        }
        if (hardwareState.cpuPolicies.isNotEmpty()) {
            val cpus =
                hardwareState.cpuPolicies.joinToString(", ") { "${it.policyName}=${it.currentFreqText}(${it.governor})" }
            appendLine("- CPU Policies: $cpus")
        }
    }.trimEnd()

    private fun readLongFile(file: File): Long? =
        TelemetryRepository.readSmallFileText(file)?.toLongOrNull()

    private fun audioDeviceTypeLabel(context: Context, type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AppLanguageManager.getString(
            context,
            R.string.audio_type_speaker
        )

        AudioDeviceInfo.TYPE_WIRED_HEADSET -> AppLanguageManager.getString(
            context,
            R.string.audio_type_headset
        )

        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AppLanguageManager.getString(
            context,
            R.string.audio_type_headphones
        )

        AudioDeviceInfo.TYPE_LINE_ANALOG -> AppLanguageManager.getString(
            context,
            R.string.audio_type_line_analog
        )

        AudioDeviceInfo.TYPE_LINE_DIGITAL -> AppLanguageManager.getString(
            context,
            R.string.audio_type_line_digital
        )

        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
        AudioDeviceInfo.TYPE_USB_DEVICE -> AppLanguageManager.getString(
            context,
            R.string.audio_type_usb_dev
        )

        AudioDeviceInfo.TYPE_USB_ACCESSORY -> AppLanguageManager.getString(
            context,
            R.string.audio_type_usb_acc
        )

        AudioDeviceInfo.TYPE_USB_HEADSET -> AppLanguageManager.getString(
            context,
            R.string.audio_type_usb_headset
        )

        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AppLanguageManager.getString(
            context,
            R.string.audio_type_bt_a2dp
        )

        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AppLanguageManager.getString(
            context,
            R.string.audio_type_bt_sco
        )

        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AppLanguageManager.getString(
            context,
            R.string.audio_type_earpiece
        )

        else -> AppLanguageManager.getString(
            context,
            R.string.audio_type_other_fmt,
            type
        )
    }

    private fun encodingLabel(encoding: Int): String = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> "16bit"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24bit"
        AudioFormat.ENCODING_PCM_32BIT -> "32bit"
        AudioFormat.ENCODING_PCM_FLOAT -> "Float"
        AudioFormat.ENCODING_DSD -> "DSD"
        AudioFormat.ENCODING_DOLBY_TRUEHD -> "TrueHD"
        AudioFormat.ENCODING_E_AC3 -> "EAC3"
        AudioFormat.ENCODING_AC3 -> "AC3"
        AudioFormat.ENCODING_DTS -> "DTS"
        AudioFormat.ENCODING_DTS_HD -> "DTS-HD"
        else -> "Enc($encoding)"
    }
}
