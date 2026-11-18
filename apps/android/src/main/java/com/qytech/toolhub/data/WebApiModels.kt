package com.qytech.toolhub.data

import kotlinx.serialization.Serializable

@Serializable
data class WebApiActionResponse(
    val ok: Boolean,
    val message: String = "",
    val output: String = "",
)

@Serializable
data class WebModeRequest(val mode: String = "OTG")

@Serializable
data class WebNodeModeRequest(val path: String = "", val mode: String = "OTG")

@Serializable
data class WebToggleRequest(val enabled: Boolean = false)

@Serializable
data class WebBootPolicyRequest(val autoOtg: Boolean = true, val autoAdb: Boolean = true)

@Serializable
data class WebSysfsWriteRequest(val path: String = "", val value: String = "")

@Serializable
data class WebStressStartRequest(
    val type: String = "SAT",
    val threads: Int = 4,
    val ramMB: Int = 256,
    val duration: Int = 60,
)

@Serializable
data class WebPingRequest(val host: String = "223.5.5.5")

@Serializable
data class WebPolicyGovRequest(val policy: String = "", val governor: String = "")

@Serializable
data class WebPolicyFreqRequest(val policy: String = "", val minFreq: Long = 0L, val maxFreq: Long = 0L)

@Serializable
data class WebAudioToneRequest(
    val action: String = "config",
    val waveform: String = "SINE_1K",
    val channelMode: String = "STEREO",
    val sampleRateHz: Int = 48000,
    val attenuationDb: Int = -6,
)

@Serializable
data class WebSysPropSetRequest(val key: String = "", val value: String = "")

@Serializable
data class WebAotCompileRequest(val pkg: String = "", val mode: String = "SPEED")

@Serializable
data class WebLegacyStatusResponse(
    val model: String,
    val android: String,
    val isStressRunning: Boolean,
    val stressElapsedSec: Int,
    val stressTempCelsius: Float,
    val usbAttached: Boolean,
    val usbVolume: String,
    val reportCount: Int,
)

@Serializable
data class WebDeviceDto(
    val model: String,
    val brand: String,
    val manufacturer: String,
    val androidVersion: String,
    val sdkInt: Int,
    val socPlatform: String,
    val abi: String,
    val kernelVersion: String,
    val buildDisplay: String,
    val uidSummary: String,
    val ramSummary: String,
    val storageSummary: String,
    val displaySummary: String,
    val uptime: String,
    val activeIp: String,
    val adbPort: String,
    val sysBootReason: String,
    val isAbnormalReboot: Boolean,
)

@Serializable
data class WebOtgNodeDto(
    val path: String,
    val name: String,
    val mode: String,
    val writable: Boolean,
)

@Serializable
data class WebUsbDeviceDto(
    val name: String,
    val classLabel: String,
    val vidPid: String,
)

@Serializable
data class WebOtgDto(
    val usbConfig: String,
    val usbState: String,
    val usbController: String,
    val adbTcpPort: String,
    val nodes: List<WebOtgNodeDto>,
    val usbDevices: List<WebUsbDeviceDto>,
)

@Serializable
data class WebControlsDto(
    val adbEnabled: Boolean,
    val wifiAdbEnabled: Boolean,
    val stayAwakeEnabled: Boolean,
    val autoOtgOnBoot: Boolean,
    val autoAdbOnBoot: Boolean,
)

@Serializable
data class WebHealthCheckDto(
    val title: String,
    val detail: String,
    val passed: Boolean,
)

@Serializable
data class WebBootHistoryDto(
    val trigger: String,
    val success: Boolean,
    val adbSummary: String,
    val timestampMillis: Long,
)

@Serializable
data class WebUsbStorageDto(
    val attached: Boolean,
    val volumeLabel: String,
    val reportsDir: String,
)

@Serializable
data class WebLastAgingReportDto(
    val isPass: Boolean,
    val elapsedSeconds: Long,
    val peakTemp: Float?,
    val errors: Long,
    val markdown: String,
)

@Serializable
data class WebStressDto(
    val isRunning: Boolean,
    val type: String,
    val elapsedSeconds: Int,
    val durationSeconds: Int,
    val currentTempCelsius: Float?,
    val memThroughputMBs: Double,
    val memErrors: Long,
    val statusMessage: String,
    val hasAgingReport: Boolean,
    val recentLogs: List<String>,
    val lastReport: WebLastAgingReportDto? = null,
)

@Serializable
data class WebBugreportStatusDto(
    val isGenerating: Boolean,
    val isIndeterminate: Boolean,
    val progress: Float,
    val elapsedSeconds: Int,
    val statusMessage: String,
)

@Serializable
data class WebOverviewResponse(
    val device: WebDeviceDto,
    val otg: WebOtgDto,
    val controls: WebControlsDto,
    val healthChecks: List<WebHealthCheckDto>,
    val bootHistory: List<WebBootHistoryDto>,
    val usbStorage: WebUsbStorageDto,
    val stress: WebStressDto,
    val bugreport: WebBugreportStatusDto,
    val pinRequired: Boolean = false,
)

@Serializable
data class WebGcDto(
    val gcCount: Long,
    val gcTimeMs: Long,
    val blockingGcCount: Long,
    val jvmUsedBytes: Long,
    val jvmMaxBytes: Long,
)

@Serializable
data class WebThermalZoneDto(
    val zone: String,
    val type: String,
    val celsius: Double?,
    val raw: Long,
)

@Serializable
data class WebCpuPolicyDto(
    val name: String,
    val affectedCpus: String,
    val curFreq: String,
    val rangeFreq: String,
    val governor: String,
    val minKHz: Long = 0L,
    val maxKHz: Long = 0L,
    val availableGovernors: List<String> = emptyList(),
    val availableFrequenciesKHz: List<Long> = emptyList(),
)

@Serializable
data class WebGpuDevfreqDto(
    val name: String,
    val curFreq: String,
    val rangeFreq: String,
    val governor: String,
    val minHz: Long = 0L,
    val maxHz: Long = 0L,
    val availableGovernors: List<String> = emptyList(),
    val availableFrequenciesHz: List<Long> = emptyList(),
)

@Serializable
data class WebTelemetryResponse(
    val cpuPercent: Float,
    val loadAvg: String,
    val gpuAvailable: Boolean,
    val gpuPercent: Float,
    val gpuName: String,
    val gpuFreqMhz: Long,
    val memPercent: Float,
    val memUsedBytes: Long,
    val memTotalBytes: Long,
    val zramUsedBytes: Long,
    val zramTotalBytes: Long,
    val netRxBytesPerSec: Float,
    val netTxBytesPerSec: Float,
    val diskReadBytesPerSec: Float,
    val diskWriteBytesPerSec: Float,
    val dataFreeBytes: Long,
    val dataTotalBytes: Long,
    val dataUsedPercent: Float,
    val gc: WebGcDto,
    val thermalZones: List<WebThermalZoneDto>,
    val cpuPolicies: List<WebCpuPolicyDto>,
    val gpuDevfreqs: List<WebGpuDevfreqDto> = emptyList(),
)

@Serializable
data class WebAlsaStreamDto(
    val pcmId: String,
    val streamName: String,
    val active: Boolean,
    val statusText: String,
    val format: String,
    val rate: String,
    val channels: String,
)

@Serializable
data class WebAlsaCardDto(
    val index: Int,
    val id: String,
    val name: String,
    val description: String,
    val active: Boolean,
    val streams: List<WebAlsaStreamDto>,
)

@Serializable
data class WebAudioDeviceDto(
    val id: Int,
    val typeLabel: String,
    val productName: String,
    val sampleRates: String,
    val encodings: String,
)

@Serializable
data class WebAudioPropsDto(
    val usbAudioInfo: String,
    val usbExclusive: String,
    val audioDirectDevice: String,
    val i2sMode: String,
)

@Serializable
data class WebSysfsNodeDto(
    val path: String,
    val group: String,
    val name: String,
    val value: String,
    val writable: Boolean,
    val isCustom: Boolean = false,
)

@Serializable
data class WebAudioToneDto(
    val isPlaying: Boolean = false,
    val waveform: String = "SINE_1K",
    val channelMode: String = "STEREO",
    val sampleRateHz: Int = 48000,
    val attenuationDb: Int = -6,
)

@Serializable
data class WebAudioResponse(
    val cards: List<WebAlsaCardDto>,
    val outputDevices: List<WebAudioDeviceDto>,
    val audioProps: WebAudioPropsDto,
    val sysfsNodes: List<WebSysfsNodeDto>,
    val tone: WebAudioToneDto = WebAudioToneDto(),
)

@Serializable
data class WebSysPropItemDto(
    val key: String,
    val value: String,
    val writable: Boolean,
)

@Serializable
data class WebSysPropsResponse(
    val props: List<WebSysPropItemDto>,
)

@Serializable
data class WebAotAppDto(
    val packageName: String,
    val appLabel: String,
    val isSystemApp: Boolean,
    val status: String,
    val reason: String,
    val abi: String,
    val odexSizeBytes: Long,
    val isSpeedCompiled: Boolean,
)

@Serializable
data class WebAotResponse(
    val isRunning: Boolean,
    val currentIndex: Int,
    val totalCount: Int,
    val statusMessage: String,
    val apps: List<WebAotAppDto>,
)

@Serializable
data class WebReportItemDto(
    val fileName: String,
    val typeLabel: String,
    val isZip: Boolean,
    val isUsbDrive: Boolean,
    val storageLocationLabel: String,
    val formattedTime: String,
    val sizeBytes: Long,
    val isPass: Boolean?,
    val summary: String,
)

@Serializable
data class WebBootReasonDto(
    val sysBootReason: String,
    val roBootReason: String,
    val isAbnormal: Boolean,
    val lastKernelExcerpt: String? = null,
)

@Serializable
data class WebTombstoneDto(
    val fileName: String,
    val timestampText: String,
    val processName: String,
    val pid: String,
    val signalText: String,
)

@Serializable
data class WebAnrDto(
    val tag: String,
    val processName: String,
    val timestampText: String,
    val reason: String,
    val excerpt: String,
)

@Serializable
data class WebCrashResponse(
    val bootReason: WebBootReasonDto,
    val tombstones: List<WebTombstoneDto>,
    val anrs: List<WebAnrDto>,
)

@Serializable
data class WebTextContentResponse(
    val content: String? = null,
    val error: String? = null,
)

@Serializable
data class WebSnapshotResponse(
    val snapshot: String,
)
