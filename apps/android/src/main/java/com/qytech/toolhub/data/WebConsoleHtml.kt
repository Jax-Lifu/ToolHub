package com.qytech.toolhub.data

import android.content.Context
import android.os.Build

object WebConsoleHtml {

    fun buildAppHtml(context: Context): String {
        val raw = runCatching {
            context.assets.open("web_console.html").bufferedReader().use { it.readText() }
        }.getOrElse {
            "<!DOCTYPE html><html><body><h1>ToolHub Web Console</h1><p>Template load error: ${it.message}</p></body></html>"
        }
        return raw.replace("{{MODEL}}", escapeHtml(Build.MODEL))
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
