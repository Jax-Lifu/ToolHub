package com.qytech.toolhub.data

import android.annotation.SuppressLint
import android.os.SystemClock

/**
 * 通过 SystemProperties (优先反射同进程读取，兜底 getprop) 读取系统属性。
 */
@SuppressLint("PrivateApi")
object SystemPropertiesHelper {

    private val lineRegex = Regex("^\\[([^]]+)]\\s*:\\s*\\[(.*)]$")

    private val sysPropGetMethod by lazy {
        runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            clazz.getMethod("get", String::class.java, String::class.java)
        }.getOrNull()
    }

    @Volatile
    private var cachedAllProps: Map<String, String> = emptyMap()

    @Volatile
    private var cachedAllTimestampMs: Long = 0L

    private const val ALL_PROPS_CACHE_TTL_MS = 3_000L

    fun readAll(forceRefresh: Boolean = false): Map<String, String> {
        val now = runCatching { SystemClock.elapsedRealtime() }.getOrDefault(0L)
        if (!forceRefresh && cachedAllProps.isNotEmpty() && now > 0L && (now - cachedAllTimestampMs) < ALL_PROPS_CACHE_TTL_MS) {
            return cachedAllProps
        }
        val parsed = runCatching {
            val process = ProcessBuilder("getprop")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            parseGetpropOutput(output)
        }.getOrDefault(emptyMap())
        if (parsed.isNotEmpty()) {
            cachedAllProps = parsed
            cachedAllTimestampMs = now
        }
        return parsed
    }

    fun get(key: String, default: String = ""): String {
        // 1. 优先尝试同进程 android.os.SystemProperties.get，零子进程分配与零 LOS 缓冲区
        val reflectMethod = sysPropGetMethod
        if (reflectMethod != null) {
            val reflected = runCatching {
                reflectMethod.invoke(null, key, default) as? String
            }.getOrNull()
            if (reflected != null) {
                return reflected.ifEmpty { default }
            }
        }
        // 2. 兜底通过 getprop 命令行读取
        return runCatching {
            val process = ProcessBuilder("getprop", key)
                .redirectErrorStream(true)
                .start()
            val value = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor()
            value.ifEmpty { default }
        }.getOrDefault(default)
    }

    fun set(key: String, value: String): Result<String> = runCatching {
        val trimmedKey = key.trim()
        require(trimmedKey.isNotEmpty()) { "Property key cannot be empty" }
        require(!trimmedKey.startsWith("ro.")) { "Read-only property (ro.*) cannot be modified" }

        val reflectOk = runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val setMethod = clazz.getMethod("set", String::class.java, String::class.java)
            setMethod.invoke(null, trimmedKey, value)
            true
        }.getOrDefault(false)

        if (!reflectOk) {
            val proc = ProcessBuilder("setprop", trimmedKey, value)
                .redirectErrorStream(true)
                .start()
            val err = proc.inputStream.bufferedReader().use { it.readText() }.trim()
            val code = proc.waitFor()
            if (code != 0 && err.isNotEmpty()) {
                throw IllegalStateException(err)
            }
        }
        cachedAllTimestampMs = 0L
        get(trimmedKey, "")
    }

    fun isWritableKey(key: String): Boolean = key.isNotBlank() && !key.startsWith("ro.")

    internal fun parseGetpropOutput(raw: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        raw.lineSequence().forEach { line ->
            val match = lineRegex.matchEntire(line.trim()) ?: return@forEach
            result[match.groupValues[1]] = match.groupValues[2]
        }
        return result
    }
}
