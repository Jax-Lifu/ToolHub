package com.qytech.toolhub

import com.qytech.toolhub.data.BootExecutionRecord
import com.qytech.toolhub.data.HardwareRepository
import com.qytech.toolhub.data.NetworkCountersSnapshot
import com.qytech.toolhub.data.OtgMode
import com.qytech.toolhub.data.OtgRepository
import com.qytech.toolhub.data.SystemPropertiesHelper
import com.qytech.toolhub.data.TelemetryRepository
import com.qytech.toolhub.data.ToolHubPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class OtgAndSerializationUnitTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun discoverOtgNodes_handlesFlatAndNestedSysconAndSymlinkCycles() {
        val sysRoot = tempFolder.newFolder("sys")

        // 1. 模拟 rk356x: /sys/devices/platform/fe8a0000.usb2-phy/otg_mode
        val rk356xPhy = File(sysRoot, "devices/platform/fe8a0000.usb2-phy").apply { mkdirs() }
        val rk356xOtg = File(rk356xPhy, "otg_mode").apply { writeText("host\n") }

        // 2. 模拟 rk3576: /sys/devices/platform/2602e000.syscon/2602e000.syscon:usb2-phy@0/otg_mode
        val rk3576Phy = File(
            sysRoot,
            "devices/platform/2602e000.syscon/2602e000.syscon:usb2-phy@0",
        ).apply { mkdirs() }
        val rk3576Otg = File(rk3576Phy, "otg_mode").apply { writeText("otg\n") }
        File(rk3576Phy, "of_node").apply { mkdirs() }.let {
            File(it, "otg-port").writeText("")
        }

        // 3. 模拟 /sys/bus/platform/devices 软链与重复引用
        val platformBus = File(sysRoot, "bus/platform/devices").apply { mkdirs() }
        Files.createSymbolicLink(
            File(platformBus, "fe8a0000.usb2-phy").toPath(),
            rk356xPhy.toPath(),
        )
        Files.createSymbolicLink(
            File(platformBus, "2602e000.syscon:usb2-phy@0").toPath(),
            rk3576Phy.toPath(),
        )

        // 4. 模拟 sysfs 循环符号链接（subsystem -> 回指根目录），确保不会死循环
        Files.createSymbolicLink(
            File(rk3576Phy, "subsystem").toPath(),
            sysRoot.toPath(),
        )

        // 5. 模拟只读 node
        val roPhy = File(sysRoot, "devices/platform/fe8b0000.usb2-phy").apply { mkdirs() }
        val roOtg = File(roPhy, "otg_mode").apply {
            writeText("UNKNOWN\n")
            setReadOnly()
        }

        val discovered = OtgRepository.discoverOtgNodes(sysRoot)
        assertEquals(3, discovered.size)

        val paths = discovered.map { it.path }.toSet()
        assertTrue(rk356xOtg.canonicalPath in paths)
        assertTrue(rk3576Otg.canonicalPath in paths)
        assertTrue(roOtg.canonicalPath in paths)

        val rk3576Node = discovered.first { it.path == rk3576Otg.canonicalPath }
        assertEquals(OtgMode.OTG, rk3576Node.currentMode)
        assertTrue(rk3576Node.isOtgPortHint)
        assertTrue(rk3576Node.isWritable)

        val rk356xNode = discovered.first { it.path == rk356xOtg.canonicalPath }
        assertEquals(OtgMode.HOST, rk356xNode.currentMode)

        val roNode = discovered.first { it.path == roOtg.canonicalPath }
        assertFalse(roNode.isWritable)
        assertEquals("UNKNOWN", roNode.rawValue)
    }

    @Test
    fun bootExecutionRecord_roundTripKotlinSerialization() {
        val records = listOf(
            BootExecutionRecord(
                timestampMillis = 1763344500000L,
                trigger = "BOOT_COMPLETED",
                success = true,
                nodeSummaries = listOf("2602e000.syscon:usb2-phy@0: host -> otg"),
                adbSummary = "ADB_ENABLED: 0 -> 1",
                errorMessage = null,
            ),
            BootExecutionRecord(
                timestampMillis = 1763344400000L,
                trigger = "LOCKED_BOOT_COMPLETED",
                success = false,
                nodeSummaries = listOf("fe8a0000.usb2-phy: 写入失败"),
                adbSummary = "ADB_ENABLED 已是 1",
                errorMessage = "Permission denied",
            ),
        )

        val encoded = ToolHubPreferences.encodeHistory(records)
        assertTrue(encoded.contains("BOOT_COMPLETED"))
        val decoded = ToolHubPreferences.decodeHistory(encoded)
        assertEquals(records, decoded)
        assertEquals(emptyList<BootExecutionRecord>(), ToolHubPreferences.decodeHistory("invalid-json"))
    }

    @Test
    fun parseGetpropOutput_parsesKeyValuePairsCorrectly() {
        val sample = """
            [sys.usb.config]: [mtp,adb]
            [sys.usb.controller]: [23000000.usb]
            [empty.prop]: []
            malformed line
        """.trimIndent()

        val parsed = SystemPropertiesHelper.parseGetpropOutput(sample)
        assertEquals("mtp,adb", parsed["sys.usb.config"])
        assertEquals("23000000.usb", parsed["sys.usb.controller"])
        assertNotNull(parsed["empty.prop"])
        assertEquals("", parsed["empty.prop"])
        assertFalse(parsed.containsKey("malformed line"))
    }

    @Test
    fun readAlsaCards_parsesCardsAndActiveHwParams() {
        val asoundRoot = tempFolder.newFolder("asound")
        File(asoundRoot, "cards").writeText(
            """
             0 [Loopback       ]: Loopback - Loopback
                                  Loopback 1
             3 [QYHIFI         ]: simple-card - QYHIFI
                                  QYHIFI
            """.trimIndent()
        )
        val pcm0Sub0 = File(asoundRoot, "card3/pcm0p/sub0").apply { mkdirs() }
        File(asoundRoot, "card3/pcm0p/info").writeText("name: ES9039PRO\n")
        File(pcm0Sub0, "hw_params").writeText(
            """
            access: MMAP_INTERLEAVED
            format: S32_LE
            channels: 2
            rate: 384000 (384000/1)
            period_size: 4096
            buffer_size: 16384
            """.trimIndent()
        )
        File(pcm0Sub0, "status").writeText("state: RUNNING\n")

        val cards = HardwareRepository.readAlsaCards(asoundRoot)
        assertEquals(2, cards.size)
        val qyhifi = cards.first { it.id == "QYHIFI" }
        assertTrue(qyhifi.hasActiveStream)
        val stream = qyhifi.streams.single()
        assertEquals("ES9039PRO", stream.streamName)
        assertEquals("S32_LE", stream.format)
        assertEquals("384000 Hz", stream.rate)
        assertEquals("2", stream.channels)
    }

    @Test
    fun readThermalAndCpuPolicies_parsesSysfsNodes() {
        val thermalRoot = tempFolder.newFolder("thermal")
        val z0 = File(thermalRoot, "thermal_zone0").apply { mkdirs() }
        File(z0, "type").writeText("soc-thermal\n")
        File(z0, "temp").writeText("62846\n")
        val z1 = File(thermalRoot, "thermal_zone1").apply { mkdirs() }
        File(z1, "type").writeText("test_battery\n")
        File(z1, "temp").writeText("2600\n")

        val zones = HardwareRepository.readThermalZones(thermalRoot)
        assertEquals(2, zones.size)
        assertEquals("62.8 °C", zones[0].formattedTemp)
        assertEquals("2600 (raw)", zones[1].formattedTemp)

        val cpufreqRoot = tempFolder.newFolder("cpufreq")
        val p0 = File(cpufreqRoot, "policy0").apply { mkdirs() }
        File(p0, "scaling_cur_freq").writeText("1608000\n")
        File(p0, "scaling_min_freq").writeText("408000\n")
        File(p0, "scaling_max_freq").writeText("2208000\n")
        File(p0, "scaling_governor").writeText("schedutil\n")
        File(p0, "related_cpus").writeText("0 1 2 3\n")

        val policies = HardwareRepository.readCpuPolicies(cpufreqRoot)
        assertEquals(1, policies.size)
        assertEquals("1.61 GHz", policies[0].currentFreqText)
        assertEquals("schedutil", policies[0].governor)

        val paths = listOf("/sys/class/leds/tube/brightness", "/sys/class/ct7302/ct7302/filter")
        val encodedPaths = ToolHubPreferences.encodeStringList(paths)
        assertEquals(paths, ToolHubPreferences.decodeStringList(encodedPaths))
    }

    @Test
    fun telemetryParsing_computesCpuGpuAndNetworkRates() {
        val prevLine = "cpu  1000 100 500 8400 0 0 0 0 0 0"
        val currLine = "cpu  1300 100 700 8900 0 0 0 0 0 0"
        val prevTicks = TelemetryRepository.parseProcStatCpuLine(prevLine)
        val currTicks = TelemetryRepository.parseProcStatCpuLine(currLine)
        assertNotNull(prevTicks)
        assertNotNull(currTicks)
        // delta total = 1000, delta idle = 500 -> 50%
        val cpuPct = TelemetryRepository.computeCpuPercent(prevTicks, currTicks)!!
        assertEquals(50f, cpuPct, 0.01f)

        val devfreqRoot = tempFolder.newFolder("devfreq")
        val gpuDir = File(devfreqRoot, "27800000.gpu").apply { mkdirs() }
        File(gpuDir, "load").writeText("42@300000000Hz\n")
        File(gpuDir, "cur_freq").writeText("300000000\n")
        File(gpuDir, "max_freq").writeText("900000000\n")
        File(gpuDir, "governor").writeText("simple_ondemand\n")

        val gpu = TelemetryRepository.readGpuSample(devfreqRoot)
        assertTrue(gpu.available)
        assertEquals(42f, gpu.usagePercent, 0.01f)
        assertEquals(300L, gpu.curFreqMHz)
        assertEquals(900L, gpu.maxFreqMHz)

        val netPrev = NetworkCountersSnapshot(rxBytes = 10_000L, txBytes = 2_000L, timestampNanos = 1_000_000_000L)
        val netCurr = NetworkCountersSnapshot(rxBytes = 110_000L, txBytes = 22_000L, timestampNanos = 3_000_000_000L)
        val netRate = TelemetryRepository.computeNetworkRate(netPrev, netCurr)
        assertEquals(50_000f, netRate.rxBytesPerSec, 1f)
        assertEquals(10_000f, netRate.txBytesPerSec, 1f)
    }

    @Test
    fun cpuAndGpuTuning_writesGovernorsAndFrequencyBoundsCorrectly() {
        val cpufreqRoot = tempFolder.newFolder("cpufreq_tune")
        val p0 = File(cpufreqRoot, "policy0").apply { mkdirs() }
        File(p0, "scaling_cur_freq").writeText("1200000\n")
        File(p0, "scaling_min_freq").writeText("408000\n")
        File(p0, "scaling_max_freq").writeText("1800000\n")
        File(p0, "scaling_governor").writeText("schedutil\n")
        File(p0, "scaling_available_governors").writeText("powersave schedutil performance\n")
        File(p0, "scaling_available_frequencies").writeText("408000 1200000 1800000 2208000\n")
        File(p0, "related_cpus").writeText("0 1 2 3\n")

        val policies = HardwareRepository.readCpuPolicies(cpufreqRoot)
        assertEquals(1, policies.size)
        assertEquals(listOf("powersave", "schedutil", "performance"), policies[0].availableGovernors)
        assertEquals(listOf(408000L, 1200000L, 1800000L, 2208000L), policies[0].availableFrequenciesKHz)
        assertTrue(policies[0].isWritable)

        val govUpdated = HardwareRepository.setCpuGovernor("policy0", "performance", cpufreqRoot).getOrThrow()
        assertEquals("performance", govUpdated.governor)

        val allCount = HardwareRepository.setAllCpuGovernors("powersave", cpufreqRoot).getOrThrow()
        assertEquals(1, allCount)
        assertEquals("powersave", File(p0, "scaling_governor").readText().trim())

        val locked = HardwareRepository.setCpuFreqBounds("policy0", 2208000L, 2208000L, cpufreqRoot).getOrThrow()
        assertEquals(2208000L, locked.minKHz)
        assertEquals(2208000L, locked.maxKHz)
        assertEquals("2.21 GHz ~ 2.21 GHz", locked.rangeText)

        // GPU devfreq
        val devfreqRoot = tempFolder.newFolder("devfreq_tune")
        val gpuDir = File(devfreqRoot, "27800000.gpu").apply { mkdirs() }
        File(gpuDir, "cur_freq").writeText("400000000\n")
        File(gpuDir, "min_freq").writeText("200000000\n")
        File(gpuDir, "max_freq").writeText("800000000\n")
        File(gpuDir, "governor").writeText("simple_ondemand\n")
        File(gpuDir, "available_governors").writeText("simple_ondemand performance powersave\n")
        File(gpuDir, "available_frequencies").writeText("200000000 400000000 800000000\n")

        val gpus = HardwareRepository.readGpuDevfreqNodes(devfreqRoot)
        assertEquals(1, gpus.size)
        assertEquals("400 MHz", gpus[0].currentFreqText)
        assertEquals("200 MHz ~ 800 MHz", gpus[0].rangeText)

        val gpuGov = HardwareRepository.setGpuGovernor("27800000.gpu", "performance", devfreqRoot).getOrThrow()
        assertEquals("performance", gpuGov.governor)

        val gpuLocked = HardwareRepository.setGpuFreqBounds("27800000.gpu", 800000000L, 800000000L, devfreqRoot).getOrThrow()
        assertEquals("800 MHz ~ 800 MHz", gpuLocked.rangeText)
    }

    @Test
    fun systemPropertiesAndSysfsReadWrite_validatesCorrectly() {
        assertFalse(SystemPropertiesHelper.isWritableKey(""))
        assertFalse(SystemPropertiesHelper.isWritableKey("ro.board.platform"))
        assertTrue(SystemPropertiesHelper.isWritableKey("persist.sys.usb.config"))
        assertTrue(SystemPropertiesHelper.isWritableKey("vendor.audio_usb_exclusive"))

        val sysDir = tempFolder.newFolder("sysfs_rw")
        val missingFile = File(sysDir, "non_existent")
        val missingNode = HardwareRepository.readSysfsNode(missingFile, "custom", true)
        assertEquals("path_not_found", missingNode.readError)
        assertFalse(missingNode.isWritable)

        val writableFile = File(sysDir, "brightness").apply { writeText("128\n") }
        val node = HardwareRepository.readSysfsNode(writableFile, "backlight", false)
        assertEquals("128", node.value)
        assertTrue(node.isWritable)

        val written = HardwareRepository.writeSysfsNode(writableFile, "255", "backlight", false).getOrThrow()
        assertEquals("255", written.value)
    }
}
