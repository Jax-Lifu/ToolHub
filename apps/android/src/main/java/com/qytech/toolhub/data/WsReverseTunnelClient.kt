package com.qytech.toolhub.data

import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.security.SecureRandom
import javax.net.ssl.SSLSocketFactory

/**
 * Lightweight pure-Kotlin RFC 6455 WebSocket client supporting ws:// and wss://
 * for ToolHub reverse-proxying local HTTP requests through toolhub-fleet.
 */
internal object WsReverseTunnelClient {

    suspend fun runSession(
        serverHttpOrWsUrl: String,
        deviceToken: String,
        scope: CoroutineScope,
        buildHeartbeatJson: () -> String,
        localWebPortProvider: () -> Int,
        onConnected: () -> Unit,
    ) {
        val wsUri = toWsUri(serverHttpOrWsUrl)
        val isTls = wsUri.scheme.equals("wss", ignoreCase = true)
        val host = wsUri.host ?: throw IllegalArgumentException("Invalid Fleet URL")
        val port = if (wsUri.port > 0) wsUri.port else if (isTls) 443 else 80
        val rawPath = (wsUri.rawPath?.takeIf { it.isNotBlank() } ?: "").trimEnd('/') + "/api/tunnel/ws"

        val socket: Socket = if (isTls) {
            SSLSocketFactory.getDefault().createSocket(host, port)
        } else {
            Socket().apply { connect(InetSocketAddress(host, port), 10_000) }
        }
        socket.soTimeout = 45_000
        socket.tcpNoDelay = true

        val writeLock = Any()
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            // RFC 6455 HTTP Upgrade handshake
            val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val wsKey = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
            val hostHdr = if ((isTls && port == 443) || (!isTls && port == 80)) host else "$host:$port"
            val req = buildString {
                append("GET ").append(rawPath).append(" HTTP/1.1\r\n")
                append("Host: ").append(hostHdr).append("\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                if (deviceToken.isNotBlank()) {
                    append("X-Device-Token: ").append(deviceToken).append("\r\n")
                }
                append("\r\n")
            }
            output.write(req.toByteArray(Charsets.UTF_8))
            output.flush()

            val statusLine = readHttpLine(input)
            if (!statusLine.contains("101")) {
                throw IllegalStateException("WS upgrade failed: $statusLine")
            }
            while (true) {
                val line = readHttpLine(input)
                if (line.isEmpty()) break
            }

            onConnected()

            // Send initial hello
            val helloJson = """{"type":"hello","heartbeat":${buildHeartbeatJson()}}"""
            sendFrame(output, writeLock, 0x1, helloJson.toByteArray(Charsets.UTF_8))

            // Heartbeat ticker
            val hbJob = scope.launch(Dispatchers.IO) {
                while (isActive && !socket.isClosed) {
                    delay(15_000L)
                    val hb = """{"type":"heartbeat","heartbeat":${buildHeartbeatJson()}}"""
                    runCatching {
                        sendFrame(output, writeLock, 0x1, hb.toByteArray(Charsets.UTF_8))
                    }.onFailure {
                        runCatching { socket.close() }
                    }
                }
            }

            try {
                while (scope.isActive && !socket.isClosed) {
                    val frame = readFrame(input) ?: break
                    when (frame.opcode) {
                        0x1 -> {
                            val text = String(frame.payload, Charsets.UTF_8)
                            scope.launch(Dispatchers.IO) {
                                handleServerMessage(text, localWebPortProvider(), output, writeLock)
                            }
                        }
                        0x8 -> break // Close
                        0x9 -> sendFrame(output, writeLock, 0xA, frame.payload) // Ping -> Pong
                        0xA -> Unit // Pong
                    }
                }
            } finally {
                hbJob.cancel()
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun handleServerMessage(
        text: String,
        localWebPort: Int,
        output: OutputStream,
        writeLock: Any,
    ) {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (obj.optString("type") != "http_req") return
        val reqId = obj.optString("id")
        val method = obj.optString("method", "GET")
        val path = obj.optString("path", "/")
        val headersObj = obj.optJSONObject("headers")
        val bodyB64 = obj.optString("bodyB64", "")
        val reqBytes = if (bodyB64.isNotEmpty()) Base64.decode(bodyB64, Base64.DEFAULT) else ByteArray(0)

        var status = 502
        val respHeaders = JSONObject()
        var respBytes = ByteArray(0)

        runCatching {
            val url = URL("http://127.0.0.1:$localWebPort$path")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = 45_000
                if (headersObj != null) {
                    val keys = headersObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        setRequestProperty(k, headersObj.optString(k))
                    }
                }
                if (reqBytes.isNotEmpty()) {
                    doOutput = true
                    outputStream.use { it.write(reqBytes) }
                }
            }
            try {
                status = conn.responseCode
                conn.contentType?.let { respHeaders.put("Content-Type", it) }
                conn.getHeaderField("Content-Disposition")?.let { respHeaders.put("Content-Disposition", it) }
                val stream = if (status in 200..399) conn.inputStream else (conn.errorStream ?: conn.inputStream)
                respBytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            } finally {
                conn.disconnect()
            }
        }.onFailure { err ->
            status = 502
            respHeaders.put("Content-Type", "text/plain; charset=utf-8")
            respBytes = (err.message ?: "Local proxy error").toByteArray(Charsets.UTF_8)
        }

        val chunkLimit = 192 * 1024 // multiple of 3 for clean Base64 chunking
        if (respBytes.size <= chunkLimit) {
            val respJson = JSONObject().apply {
                put("type", "http_resp")
                put("id", reqId)
                put("status", status)
                put("headers", respHeaders)
                put("bodyB64", Base64.encodeToString(respBytes, Base64.NO_WRAP))
            }.toString()

            runCatching {
                sendFrame(output, writeLock, 0x1, respJson.toByteArray(Charsets.UTF_8))
            }
            return
        }

        var offset = 0
        while (offset < respBytes.size) {
            val len = minOf(chunkLimit, respBytes.size - offset)
            val isFirst = offset == 0
            val isLast = offset + len >= respBytes.size
            val chunkB64 = Base64.encodeToString(respBytes, offset, len, Base64.NO_WRAP)
            val chunkJson = JSONObject().apply {
                put("type", "http_resp_chunk")
                put("id", reqId)
                put("status", if (isFirst) status else 0)
                put("headers", if (isFirst) respHeaders else JSONObject())
                put("bodyB64", chunkB64)
                put("eof", isLast)
            }.toString()

            val ok = runCatching {
                sendFrame(output, writeLock, 0x1, chunkJson.toByteArray(Charsets.UTF_8))
            }.isSuccess
            if (!ok) break
            offset += len
        }
    }

    private fun toWsUri(raw: String): URI {
        val trimmed = raw.trim().trimEnd('/')
        val converted = when {
            trimmed.startsWith("https://", ignoreCase = true) -> "wss://" + trimmed.substring(8)
            trimmed.startsWith("http://", ignoreCase = true) -> "ws://" + trimmed.substring(7)
            trimmed.startsWith("wss://", ignoreCase = true) || trimmed.startsWith("ws://", ignoreCase = true) -> trimmed
            else -> "ws://$trimmed"
        }
        return URI(converted)
    }

    private fun readHttpLine(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) throw EOFException("EOF in HTTP header")
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private data class WsFrame(val opcode: Int, val payload: ByteArray)

    private fun readFrame(input: InputStream): WsFrame? {
        val b0 = input.read()
        if (b0 == -1) return null
        val b1 = input.read()
        if (b1 == -1) return null
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) {
            val hi = input.read()
            val lo = input.read()
            if (hi == -1 || lo == -1) return null
            len = ((hi shl 8) or lo).toLong()
        } else if (len == 127L) {
            len = 0L
            for (i in 0 until 8) {
                val x = input.read()
                if (x == -1) return null
                len = (len shl 8) or x.toLong()
            }
        }
        val mask = if (masked) ByteArray(4).also { readFully(input, it) } else null
        val payload = ByteArray(len.toInt())
        if (len > 0) readFully(input, payload)
        if (mask != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
        }
        return WsFrame(opcode, payload)
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n <= 0) throw EOFException("Unexpected EOF in WS frame")
            off += n
        }
    }

    private fun sendFrame(output: OutputStream, lock: Any, opcode: Int, payload: ByteArray) {
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val masked = ByteArray(payload.size)
        for (i in payload.indices) {
            masked[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        synchronized(lock) {
            output.write(0x80 or (opcode and 0x0F))
            val len = payload.size
            when {
                len < 126 -> output.write(0x80 or len)
                len <= 65535 -> {
                    output.write(0x80 or 126)
                    output.write((len ushr 8) and 0xFF)
                    output.write(len and 0xFF)
                }
                else -> {
                    output.write(0x80 or 127)
                    val l = len.toLong()
                    for (shift in 56 downTo 0 step 8) {
                        output.write(((l ushr shift) and 0xFF).toInt())
                    }
                }
            }
            output.write(mask)
            output.write(masked)
            output.flush()
        }
    }
}
