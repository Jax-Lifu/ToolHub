package com.qytech.toolhub.data

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.provider.Settings
import android.util.Log
import com.qytech.toolhub.R
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

enum class OtgMode(val value: String, val label: String) {
    OTG("otg", "OTG"),
    HOST("host", "HOST"),
    PERIPHERAL("peripheral", "PERIPHERAL");

    companion object {
        fun fromRaw(raw: String?): OtgMode? {
            val normalized = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.value == normalized }
        }
    }
}

data class OtgNodeInfo(
    val file: File,
    val path: String,
    val nodeName: String,
    val rawValue: String?,
    val currentMode: OtgMode?,
    val isReadable: Boolean,
    val isWritable: Boolean,
    val isOtgPortHint: Boolean,
    val isLoading: Boolean = false,
    val readError: String? = null,
    val writeError: String? = null,
)

data class ConnectedUsbDevice(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val manufacturerName: String?,
    val productName: String?,
    val deviceClass: Int,
    val classLabel: String,
    val interfaceCount: Int,
) {
    val vidPidHex: String
        get() = "%04X:%04X".format(vendorId, productId)

    val displayName: String
        get() = listOfNotNull(
            manufacturerName?.trim()?.takeIf { it.isNotEmpty() },
            productName?.trim()?.takeIf { it.isNotEmpty() },
        ).joinToString(" ").ifEmpty { "USB Device" }
}

data class UsbSystemProperties(
    val usbConfig: String = "",
    val usbState: String = "",
    val usbController: String = "",
    val adbTcpPort: String = "",
)

object OtgRepository {

    private const val TAG = "OtgRepository"
    private const val OTG_MODE_FILENAME = "otg_mode"
    private const val MAX_SYSFS_DEPTH = 5

    private val IGNORED_SYSFS_DIRS = setOf(
        "power",
        "subsystem",
        "driver",
        "of_node",
        "firmware_node",
        "wakeup",
        "holders",
        "slaves",
        "hwmon",
        "net",
        "block",
        "sound",
        "drm",
        "graphics",
        "tty",
        "input",
        "leds",
        "backlight",
        "thermal",
        "regulator",
        "clk",
        "iommu",
        "dma",
    )

    @Volatile
    private var cachedOtgNodeFiles: List<File>? = null

    fun discoverOtgNodes(sysRoot: File = File("/sys")): List<OtgNodeInfo> {
        val isDefaultRoot = sysRoot.path == "/sys"
        val files = if (isDefaultRoot) {
            cachedOtgNodeFiles?.takeIf { list -> list.isNotEmpty() && list.all { it.exists() } }
                ?: scanOtgNodeFiles(sysRoot).also { if (it.isNotEmpty()) cachedOtgNodeFiles = it }
        } else {
            scanOtgNodeFiles(sysRoot)
        }

        return files
            .map { readNodeInfo(it) }
            .sortedWith(
                compareByDescending<OtgNodeInfo> { it.isWritable }
                    .thenByDescending { it.isOtgPortHint }
                    .thenBy { it.path }
            )
    }

    private fun scanOtgNodeFiles(sysRoot: File): List<File> {
        val discovered = LinkedHashMap<String, File>()

        // 策略 1: /sys/bus/platform/devices/*/otg_mode（扁平化覆盖直挂与 syscon 嵌套平台设备，如 rk356x 与 rk3576）
        val platformBusDir = File(sysRoot, "bus/platform/devices")
        platformBusDir.listFiles()?.forEach { entry ->
            val candidate = File(entry, OTG_MODE_FILENAME)
            if (candidate.exists()) {
                addCanonical(discovered, candidate)
            }
        }

        // 策略 2: /sys/class/phy/*/device/otg_mode
        val classPhyDir = File(sysRoot, "class/phy")
        classPhyDir.listFiles()?.forEach { entry ->
            val candidate = File(entry, "device/$OTG_MODE_FILENAME")
            if (candidate.exists()) {
                addCanonical(discovered, candidate)
            }
        }

        // 策略 3: 非符号链接有界广度优先遍历 /sys/devices/platform（以及 /sys/devices/soc*）
        val platformRoot = File(sysRoot, "devices/platform")
        if (platformRoot.isDirectory) {
            bfsFindOtgNodes(platformRoot, discovered)
        }
        File(sysRoot, "devices").listFiles()?.forEach { child ->
            if (child.isDirectory && child.name.startsWith("soc") && !isSymlink(child)) {
                bfsFindOtgNodes(child, discovered)
            }
        }

        return discovered.values.toList()
    }

    fun readNodeInfo(
        file: File,
        writeError: String? = null,
    ): OtgNodeInfo {
        val canonicalFile = runCatching { file.canonicalFile }.getOrDefault(file)
        val canRead = runCatching { canonicalFile.canRead() }.getOrDefault(false)
        val canWrite = runCatching { canonicalFile.canWrite() }.getOrDefault(false)
        val isOtgHint = runCatching {
            val parent = canonicalFile.parentFile
            parent != null && (
                    File(parent, "of_node/otg-port").exists() ||
                            canonicalFile.path.contains("otg", ignoreCase = true)
                    )
        }.getOrDefault(false)

        var rawValue: String? = null
        var readError: String? = null
        if (canRead) {
            val readText = TelemetryRepository.readSmallFileText(canonicalFile)
            if (readText != null) {
                rawValue = readText
            } else {
                readError = "read_failed"
            }
        } else {
            readError = "permission_denied"
        }

        return OtgNodeInfo(
            file = canonicalFile,
            path = canonicalFile.path,
            nodeName = canonicalFile.parentFile?.name ?: canonicalFile.name,
            rawValue = rawValue,
            currentMode = OtgMode.fromRaw(rawValue),
            isReadable = canRead && readError == null,
            isWritable = canWrite,
            isOtgPortHint = isOtgHint,
            readError = readError,
            writeError = writeError,
        )
    }

    fun setNodeMode(
        context: Context,
        file: File,
        mode: OtgMode,
        syncAdbIfOtg: Boolean,
    ): Result<OtgNodeInfo> {
        val previousMode = readNodeInfo(file).currentMode
        val writeAttempt = runCatching {
            FileOutputStream(file).use { stream ->
                stream.write(mode.value.toByteArray(Charsets.UTF_8))
                stream.flush()
            }
            if (mode == OtgMode.OTG && syncAdbIfOtg) {
                val alreadyAdb = isAdbEnabled(context)
                if (!alreadyAdb) {
                    setAdbEnabled(context, true).getOrThrow()
                } else if (previousMode != OtgMode.OTG) {
                    // Android 14+ UsbGadget HAL 在 HOST->OTG 且 ADB_ENABLED 本身就是 1 时可能不会主动重绑 UDC
                    if (!waitForUdcConfigured(timeoutMs = 1000L)) {
                        cycleAdbEnabled(context)
                    }
                }
            }
        }

        return if (writeAttempt.isSuccess) {
            Result.success(readNodeInfo(file))
        } else {
            val ex = writeAttempt.exceptionOrNull() ?: IllegalStateException("写入失败")
            Log.e(TAG, "Failed to write ${mode.value} to ${file.path}", ex)
            Result.failure(ex)
        }
    }

    fun isAdbEnabled(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
    }.getOrDefault(false)

    fun setAdbEnabled(context: Context, enabled: Boolean): Result<Boolean> = runCatching {
        val target = if (enabled) 1 else 0
        val current =
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, -1)
        if (current != target) {
            val updated = Settings.Global.putInt(
                context.contentResolver,
                Settings.Global.ADB_ENABLED,
                target,
            )
            if (!updated) {
                throw IllegalStateException("Settings.Global.putInt(ADB_ENABLED=$target) 返回 false")
            }
        }
        isAdbEnabled(context)
    }

    fun applyBootDefaults(
        context: Context,
        trigger: String,
        forceApply: Boolean = false,
    ): BootExecutionRecord {
        val prefs = ToolHubPreferences(context)
        val settings = prefs.getBootSettings()
        val shouldApplyOtg = forceApply || settings.autoOtgOnBoot
        val shouldApplyAdb = forceApply || settings.autoAdbOnBoot

        val nodeSummaries = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var anyNodeChangedToOtg = false

        if (shouldApplyOtg) {
            val nodes = discoverOtgNodes()
            val writableNodes = nodes.filter { it.isWritable }
            if (writableNodes.isEmpty()) {
                val msg = if (nodes.isEmpty()) {
                    AppLanguageManager.getString(context, R.string.otg_boot_log_no_nodes)
                } else {
                    AppLanguageManager.getString(
                        context,
                        R.string.otg_boot_log_ro_nodes_fmt,
                        nodes.size
                    )
                }
                nodeSummaries += msg
                errors += msg
            } else {
                writableNodes.forEach { node ->
                    if (node.currentMode == OtgMode.OTG) {
                        nodeSummaries += AppLanguageManager.getString(
                            context,
                            R.string.otg_boot_log_already_otg_fmt,
                            node.nodeName
                        )
                    } else {
                        val before = node.rawValue ?: "-"
                        val result = runCatching {
                            FileOutputStream(node.file).use { stream ->
                                stream.write(OtgMode.OTG.value.toByteArray(Charsets.UTF_8))
                                stream.flush()
                            }
                            readNodeInfo(node.file)
                        }
                        result.onSuccess { updated ->
                            val after = updated.rawValue ?: "-"
                            if (updated.currentMode == OtgMode.OTG) {
                                anyNodeChangedToOtg = true
                                nodeSummaries += "${node.nodeName}: $before -> otg"
                            } else {
                                val msg = "${node.nodeName}: $before -> $after"
                                nodeSummaries += msg
                                errors += msg
                            }
                        }.onFailure { ex ->
                            val msg = AppLanguageManager.getString(
                                context,
                                R.string.otg_boot_log_write_failed_fmt,
                                node.nodeName,
                                ex.message ?: "Unknown"
                            )
                            Log.e(TAG, msg, ex)
                            nodeSummaries += msg
                            errors += msg
                        }
                    }
                }
            }
        } else {
            nodeSummaries += AppLanguageManager.getString(
                context,
                R.string.otg_boot_log_skipped_otg
            )
        }

        val adbSummary = if (shouldApplyAdb) {
            val wasEnabled = isAdbEnabled(context)
            if (!wasEnabled) {
                if (anyNodeChangedToOtg) {
                    runCatching { Thread.sleep(600L) }
                }
                setAdbEnabled(context, true).fold(
                    onSuccess = { enabled ->
                        if (enabled) {
                            "ADB_ENABLED: 0 -> 1"
                        } else {
                            errors += "ADB_ENABLED write failed (0)"
                            "ADB_ENABLED: 0"
                        }
                    },
                    onFailure = { ex ->
                        val msg = AppLanguageManager.getString(
                            context,
                            R.string.otg_boot_log_adb_write_failed_fmt,
                            ex.message ?: "Unknown"
                        )
                        Log.e(TAG, msg, ex)
                        errors += msg
                        msg
                    },
                )
            } else {
                val isFullBootOrManual = trigger != "LOCKED_BOOT_COMPLETED"
                val usesGadgetHal = SystemPropertiesHelper.get("sys.usb.configfs") == "2"
                if (isFullBootOrManual && (anyNodeChangedToOtg || usesGadgetHal)) {
                    // 等待 UsbDeviceManager.finishBoot 完成，若 UDC 仍未就绪则切换 1->0->1 触发重绑
                    val configured = waitForUdcConfigured(timeoutMs = 2800L)
                    if (configured) {
                        "${
                            AppLanguageManager.getString(
                                context,
                                R.string.otg_boot_log_adb_already_on
                            )
                        } (UDC: ${readUdcStatesSummary()})"
                    } else {
                        runCatching {
                            cycleAdbEnabled(context)
                            waitForUdcConfigured(timeoutMs = 1500L)
                            "ADB_ENABLED 1->0->1 (UDC: ${readUdcStatesSummary()})"
                        }.getOrElse { ex ->
                            val msg = AppLanguageManager.getString(
                                context,
                                R.string.otg_boot_log_adb_write_failed_fmt,
                                ex.message ?: "Unknown"
                            )
                            Log.e(TAG, msg, ex)
                            errors += msg
                            msg
                        }
                    }
                } else {
                    AppLanguageManager.getString(context, R.string.otg_boot_log_adb_already_on)
                }
            }
        } else {
            AppLanguageManager.getString(context, R.string.otg_boot_log_skipped_adb)
        }

        val record = BootExecutionRecord(
            timestampMillis = System.currentTimeMillis(),
            trigger = trigger,
            success = errors.isEmpty(),
            nodeSummaries = nodeSummaries,
            adbSummary = adbSummary,
            errorMessage = errors.takeIf { it.isNotEmpty() }?.joinToString("; "),
        )
        prefs.appendBootRecord(record)
        return record
    }

    private fun cycleAdbEnabled(context: Context) {
        setAdbEnabled(context, false).getOrThrow()
        Thread.sleep(900L)
        setAdbEnabled(context, true).getOrThrow()
    }

    private fun isAnyUdcConfigured(sysRoot: File = File("/sys")): Boolean {
        val udcRoot = File(sysRoot, "class/udc")
        val entries = udcRoot.listFiles() ?: return false
        return entries.any { dir ->
            runCatching {
                File(dir, "state").readText().trim().equals("configured", ignoreCase = true)
            }.getOrDefault(false)
        }
    }

    private fun waitForUdcConfigured(timeoutMs: Long, pollIntervalMs: Long = 250L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (isAnyUdcConfigured()) return true
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) return false
            runCatching { Thread.sleep(minOf(pollIntervalMs, remaining)) }
        }
    }

    private fun readUdcStatesSummary(sysRoot: File = File("/sys")): String {
        val udcRoot = File(sysRoot, "class/udc")
        val entries = udcRoot.listFiles()?.sortedBy { it.name } ?: return "none"
        if (entries.isEmpty()) return "none"
        return entries.joinToString(",") { dir ->
            val state = runCatching { File(dir, "state").readText().trim() }.getOrDefault("?")
            "${dir.name}=$state"
        }
    }

    fun getConnectedUsbDevices(context: Context): List<ConnectedUsbDevice> = runCatching {
        val usbManager = context.getSystemService(UsbManager::class.java) ?: return emptyList()
        usbManager.deviceList.values
            .map { device ->
                val resolvedClass = resolveUsbClass(device)
                ConnectedUsbDevice(
                    deviceName = device.deviceName,
                    vendorId = device.vendorId,
                    productId = device.productId,
                    manufacturerName = device.manufacturerName,
                    productName = device.productName,
                    deviceClass = resolvedClass,
                    classLabel = usbClassLabel(context, resolvedClass),
                    interfaceCount = device.interfaceCount,
                )
            }
            .sortedBy { it.deviceName }
    }.getOrDefault(emptyList())

    fun getUsbSystemProperties(props: Map<String, String>? = null): UsbSystemProperties {
        val usbConfig = props?.get("sys.usb.config") ?: SystemPropertiesHelper.get("sys.usb.config")
        val usbState = props?.get("sys.usb.state") ?: SystemPropertiesHelper.get("sys.usb.state")
        val usbController = (props?.get("sys.usb.controller")
            ?: SystemPropertiesHelper.get("sys.usb.controller")).ifEmpty {
            props?.get("vendor.usb.controller")
                ?: SystemPropertiesHelper.get("vendor.usb.controller")
        }
        val adbTcpPort = (props?.get("service.adb.tcp.port")
            ?: SystemPropertiesHelper.get("service.adb.tcp.port")).ifEmpty {
            props?.get("persist.adb.tcp.port") ?: SystemPropertiesHelper.get("persist.adb.tcp.port")
        }
        return UsbSystemProperties(
            usbConfig = usbConfig,
            usbState = usbState,
            usbController = usbController,
            adbTcpPort = adbTcpPort,
        )
    }

    private fun resolveUsbClass(device: UsbDevice): Int {
        if (device.deviceClass != UsbConstants.USB_CLASS_PER_INTERFACE && device.deviceClass != UsbConstants.USB_CLASS_MISC) {
            return device.deviceClass
        }
        return if (device.interfaceCount > 0) {
            device.getInterface(0).interfaceClass
        } else {
            device.deviceClass
        }
    }

    private fun usbClassLabel(context: Context, usbClass: Int): String = when (usbClass) {
        UsbConstants.USB_CLASS_AUDIO -> AppLanguageManager.getString(
            context,
            R.string.usb_class_audio
        )

        UsbConstants.USB_CLASS_COMM -> AppLanguageManager.getString(
            context,
            R.string.usb_class_comm
        )

        UsbConstants.USB_CLASS_HID -> AppLanguageManager.getString(
            context,
            R.string.usb_class_hid
        )

        UsbConstants.USB_CLASS_MASS_STORAGE -> AppLanguageManager.getString(
            context,
            R.string.usb_class_storage
        )

        UsbConstants.USB_CLASS_HUB -> "USB Hub"
        UsbConstants.USB_CLASS_VIDEO -> AppLanguageManager.getString(
            context,
            R.string.usb_class_video
        )

        UsbConstants.USB_CLASS_STILL_IMAGE -> AppLanguageManager.getString(
            context,
            R.string.usb_class_ptp
        )

        UsbConstants.USB_CLASS_PRINTER -> AppLanguageManager.getString(
            context,
            R.string.usb_class_printer
        )

        UsbConstants.USB_CLASS_VENDOR_SPEC -> AppLanguageManager.getString(
            context,
            R.string.usb_class_vendor
        )

        else -> "Class $usbClass"
    }

    private fun addCanonical(target: MutableMap<String, File>, candidate: File) {
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return
        if (canonical.exists() && canonical.isFile) {
            target.putIfAbsent(canonical.path, canonical)
        }
    }

    private fun bfsFindOtgNodes(root: File, target: MutableMap<String, File>) {
        val visitedDirs = HashSet<String>()
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(root to 0)

        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            val canonicalDirPath = runCatching { dir.canonicalPath }.getOrNull() ?: continue
            if (!visitedDirs.add(canonicalDirPath)) continue

            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (isSymlink(child)) continue
                val name = child.name
                if (name == OTG_MODE_FILENAME && !child.isDirectory) {
                    addCanonical(target, child)
                    continue
                }
                if (depth < MAX_SYSFS_DEPTH && child.isDirectory && !shouldSkipSysfsDir(name)) {
                    queue.add(child to depth + 1)
                }
            }
        }
    }

    private fun shouldSkipSysfsDir(name: String): Boolean {
        return name in IGNORED_SYSFS_DIRS || name.startsWith("consumer:") ||
                name.startsWith("supplier:") ||
                name.startsWith("devlink:")
    }

    private fun isSymlink(file: File): Boolean = runCatching {
        Files.isSymbolicLink(file.toPath())
    }.getOrDefault(false)
}
