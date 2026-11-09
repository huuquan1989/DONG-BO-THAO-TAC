package com.synctap

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.OutputStreamWriter
import kotlin.concurrent.thread

/** Khởi động Injector bằng quyền shell qua Shizuku và gửi lệnh cho nó. */
object ShizukuBridge {
    private var proc: Process? = null
    private var writer: OutputStreamWriter? = null

    @Volatile var devPath: String? = null
    @Volatile var maxX = 0
    @Volatile var maxY = 0
    @Volatile var onDev: ((String, Int, Int) -> Unit)? = null

    fun binderAlive(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
    fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }
    fun ready() = binderAlive() && hasPermission()
    fun serverUid(): Int = try { Shizuku.getUid() } catch (_: Throwable) { -1 }

    private fun newProcess(cmd: Array<String>): Process? = try {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        m.invoke(null, cmd, null, null) as Process
    } catch (e: Throwable) {
        val c = e.cause ?: e
        Logger.d("SHZ", "newProcess loi: ${c.javaClass.simpleName}: ${c.message}")
        null
    }

    @Synchronized
    fun startInjector(ctx: Context): Boolean {
        if (proc?.isAlive == true) return true
        val apk = ctx.applicationInfo.sourceDir
        val p = newProcess(arrayOf("sh", "-c", "CLASSPATH='$apk' exec app_process /system/bin com.synctap.Injector"))
            ?: return false
        proc = p
        writer = OutputStreamWriter(p.outputStream)
        Logger.d("SHZ", "Injector khoi dong, apk=$apk")
        thread(isDaemon = true) {
            try {
                p.inputStream.bufferedReader().forEachLine { handleLine(it) }
            } catch (_: Throwable) {}
            Logger.d("SHZ", "Injector stdout dong")
        }
        thread(isDaemon = true) {
            try {
                p.errorStream.bufferedReader().forEachLine { Logger.d("INJ-ERR", it) }
            } catch (_: Throwable) {}
        }
        send("PING")
        return true
    }

    private fun handleLine(line: String) {
        if (line.startsWith("DEV ")) {
            val t = line.split(" ")
            if (t.size >= 4) {
                devPath = t[1]; maxX = t[2].toIntOrNull() ?: 0; maxY = t[3].toIntOrNull() ?: 0
                Logger.d("INJ", "man cam ung: $devPath max=${maxX}x$maxY")
                onDev?.invoke(t[1], maxX, maxY)
            }
        } else {
            Logger.d("INJ", line.removePrefix("LOG "))
        }
    }

    @Synchronized
    fun send(cmd: String) {
        try {
            writer?.apply { write(cmd + "\n"); flush() }
        } catch (e: Throwable) {
            Logger.d("SHZ", "gui lenh loi: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        send("STOP")
        val old = proc
        proc = null; writer = null
        thread(isDaemon = true) {
            Thread.sleep(1500)
            try { old?.destroy() } catch (_: Throwable) {}
        }
    }
}
