package com.qytech.toolhub.data

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.regex.Pattern

data class PingResult(
    val host: String,
    val isSuccess: Boolean,
    val lossPercent: Int,
    val minRttMs: Float? = null,
    val avgRttMs: Float? = null,
    val maxRttMs: Float? = null,
    val rawOutput: String = "",
    val error: String? = null,
)

data class AdbPortStatus(
    val port: Int,
    val isListening: Boolean,
    val details: String,
)

object NetworkDiagnosticsRepository {

    /**
     * 执行 ICMP Ping 连通性测试（参考 RKDeviceTest LanTest.java 实现）
     */
    suspend fun executePing(host: String, count: Int = 4, timeoutSec: Int = 2): PingResult =
        withContext(Dispatchers.IO) {
            val target = host.trim().ifBlank { "223.5.5.5" }
            try {
                val process = Runtime.getRuntime().exec(
                    arrayOf(
                        "ping",
                        "-c",
                        count.toString(),
                        "-W",
                        timeoutSec.toString(),
                        target
                    )
                )
                val stdout = BufferedReader(InputStreamReader(process.inputStream))
                val stderr = BufferedReader(InputStreamReader(process.errorStream))
                val outputBuilder = StringBuilder()
                val errorBuilder = StringBuilder()

                var line: String?
                while (stdout.readLine().also { line = it } != null) {
                    outputBuilder.appendLine(line)
                }
                while (stderr.readLine().also { line = it } != null) {
                    errorBuilder.appendLine(line)
                }
                process.waitFor()

                val raw = outputBuilder.toString()
                val err = errorBuilder.toString().trim()

                // 匹配丢包率: 4 packets transmitted, 4 received, 0% packet loss
                val lossPattern = Pattern.compile("(\\d+)%\\s*(?:packet\\s*)?loss")
                val lossMatcher = lossPattern.matcher(raw)
                val lossPercent = if (lossMatcher.find()) {
                    lossMatcher.group(1)?.toIntOrNull() ?: 100
                } else if (raw.contains("100% packet loss")) 100 else if (err.isNotBlank()) 100 else 0

                // 匹配 RTT: rtt min/avg/max/mdev = 1.234/3.456/7.890/1.111 ms
                val rttPattern =
                    Pattern.compile("(?:rtt|round-trip)\\s+min/avg/max.*?=\\s*([0-9.]+)/([0-9.]+)/([0-9.]+)")
                val rttMatcher = rttPattern.matcher(raw)
                var minRtt: Float? = null
                var avgRtt: Float? = null
                var maxRtt: Float? = null
                if (rttMatcher.find()) {
                    minRtt = rttMatcher.group(1)?.toFloatOrNull()
                    avgRtt = rttMatcher.group(2)?.toFloatOrNull()
                    maxRtt = rttMatcher.group(3)?.toFloatOrNull()
                }

                val success = lossPercent < 100 && (avgRtt != null || raw.contains("bytes from"))
                PingResult(
                    host = target,
                    isSuccess = success,
                    lossPercent = lossPercent,
                    minRttMs = minRtt,
                    avgRttMs = avgRtt,
                    maxRttMs = maxRtt,
                    rawOutput = raw.ifBlank { err },
                    error = if (!success && err.isNotBlank()) err else null,
                )
            } catch (e: Exception) {
                PingResult(
                    host = target,
                    isSuccess = false,
                    lossPercent = 100,
                    rawOutput = e.message ?: "Ping 执行异常",
                    error = e.localizedMessage,
                )
            }
        }

    /**
     * 探测 ADB TCP 端口是否真正处于 LISTEN 监听中
     * 检查 /proc/net/tcp & /proc/net/tcp6 中的 15B3 (5555 十六进制) 以及本地回环 Socket
     */
    suspend fun checkAdbTcpListening(
        port: Int = 5555,
        context: android.content.Context? = null,
    ): AdbPortStatus = withContext(Dispatchers.IO) {
        val portHex = "%04X".format(port)
        var foundInProc = false

        for (path in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
            val file = File(path)
            if (file.canRead()) {
                runCatching {
                    file.forEachLine { l ->
                        // 本地地址格式: 0100007F:15B3 状态 0A (LISTEN)
                        if (l.contains(":$portHex") && l.contains(" 0A ")) {
                            foundInProc = true
                        }
                    }
                }
            }
        }

        // Socket 探测作为补充验证
        var socketConnected = false
        if (!foundInProc) {
            runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", port), 250)
                    socketConnected = true
                }
            }
        }

        val listening = foundInProc || socketConnected
        val detail = when {
            listening -> if (context != null) {
                AppLanguageManager.getString(
                    context,
                    com.qytech.toolhub.R.string.net_adb_ready_fmt,
                    port
                )
            } else {
                "adbd listening on 0.0.0.0:$port (Ready)"
            }

            else -> if (context != null) {
                AppLanguageManager.getString(
                    context,
                    com.qytech.toolhub.R.string.net_adb_not_listening
                )
            } else {
                "Port 5555 is not listening"
            }
        }

        AdbPortStatus(port = port, isListening = listening, details = detail)
    }

    /**
     * 使用 ZXing 高质量生成紧凑清晰的 QR 二维码 Bitmap
     */
    suspend fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap =
        withContext(Dispatchers.Default) {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
            )
            val matrix =
                QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val width = matrix.width
            val height = matrix.height
            val pixels = IntArray(width * height)

            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }

            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }

    /**
     * 获取当前活动的 IPv4 地址（优先 wlan0，其次 eth0）
     */
    fun getActiveIpv4Address(): String? {
        return runCatching {
            val interfaces =
                java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            val sorted = interfaces.sortedWith(compareBy({
                when {
                    it.name.startsWith("wlan") -> 0
                    it.name.startsWith("eth") -> 1
                    else -> 2
                }
            }, { it.name }))
            for (intf in sorted) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = java.util.Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return@runCatching addr.hostAddress
                    }
                }
            }
            null
        }.getOrNull()
    }
}
