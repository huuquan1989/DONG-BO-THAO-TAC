package com.synctap

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ghi log ra file để xem và sao chép ngay trong app. */
object Logger {
    private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private const val MAX = 150_000
    private const val KEEP = 80_000

    @Synchronized
    fun init(ctx: Context) {
        if (file == null) file = File(ctx.applicationContext.filesDir, "synctap.log")
    }

    @Synchronized
    fun d(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] $msg"
        Log.d("SyncTap", line)
        val f = file ?: return
        try {
            f.appendText(line + "\n")
            if (f.length() > MAX) {
                val t = f.readText()
                f.writeText(t.takeLast(KEEP))
            }
        } catch (_: Exception) {}
    }

    @Synchronized
    fun dump(): String = try { file?.takeIf { it.exists() }?.readText() ?: "(chưa có log)" } catch (e: Exception) { "(lỗi đọc log: ${e.message})" }

    @Synchronized
    fun clear() { try { file?.writeText("") } catch (_: Exception) {} }
}
