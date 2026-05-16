package com.openaccess.sdk

import android.app.Application
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class OpenAccessApp : Application() {
    companion object {
        private const val TAG = "OpenAccess"
        const val CRASH_DIR = "crash_reports"
    }

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            saveCrashReport(thread, ex)
            Process.killProcess(Process.myPid())
        }
    }

    private fun saveCrashReport(thread: Thread, ex: Throwable) {
        try {
            val dir = File(filesDir, CRASH_DIR)
            dir.mkdirs()
            val sw = StringWriter()
            sw.write("=== CRASH REPORT ===\n")
            sw.write("Time: ${System.currentTimeMillis()}\n")
            sw.write("Device: ${Build.MODEL} (${Build.MANUFACTURER})\n")
            sw.write("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
            sw.write("Thread: ${thread.name}\n")
            sw.write("Exception: ${ex.javaClass.name}: ${ex.message}\n")
            sw.write("Stack trace:\n")
            val pw = PrintWriter(sw)
            ex.printStackTrace(pw)
            pw.flush()
            sw.write("\nCause:\n")
            var cause = ex.cause
            while (cause != null) {
                sw.write("Caused by: ${cause.javaClass.name}: ${cause.message}\n")
                cause = cause.cause
            }
            val f = File(dir, "crash_${System.currentTimeMillis()}.txt")
            f.writeText(sw.toString())
            Log.e(TAG, "Crash saved to ${f.absolutePath}")
        } catch (_: Exception) {}
    }
}
