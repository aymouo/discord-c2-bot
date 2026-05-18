package com.openaccess.sdk.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

class DisplayCapture(private val context: android.content.Context) {

    companion object {
        private const val QUALITY = 85
        private const val STREAM_QUALITY = 60
        private const val MAX_DIM = 1920
        private const val STREAM_MAX_DIM = 720
    }

    interface Callback {
        fun onSuccess(data: ByteArray)
        fun onFailure(error: String)
    }

    fun capture(callback: Callback) {
        captureInternal(callback, QUALITY, MAX_DIM)
    }

    fun captureForStream(callback: Callback) {
        captureInternal(callback, STREAM_QUALITY, STREAM_MAX_DIM)
    }

    private fun captureInternal(callback: Callback, quality: Int, maxDim: Int) {

        // 1. AccessibilityService screenshot (Android 14+) — most reliable
        if (Build.VERSION.SDK_INT >= 34) {
            val svc = AccessibilityHelper.instance
            if (svc != null) {
                captureAccessibility(callback, quality, maxDim)
                return
            }
        }

        // 2. Direct screencap via stdout pipe
        val directResult = captureDirect()
        if (directResult != null) {
            val processed = processBytes(directResult, quality, maxDim)
            if (processed != null) { callback.onSuccess(processed); return }
        }

        // 3. Root screencap
        val rootResult = captureRoot()
        if (rootResult != null) {
            val processed = processBytes(rootResult, quality, maxDim)
            if (processed != null) { callback.onSuccess(processed); return }
        }

        // 4. Screencap via /data/local/tmp
        val tmpResult = captureViaTmp()
        if (tmpResult != null) {
            val processed = processBytes(tmpResult, quality, maxDim)
            if (processed != null) { callback.onSuccess(processed); return }
        }

        callback.onFailure("Screenshot failed")
    }

    private fun captureDirect(): ByteArray? {
        return try {
            val proc = ProcessBuilder("sh", "-c", "screencap -p")
                .redirectErrorStream(true)
                .start()
            val ok = proc.waitFor(10, TimeUnit.SECONDS)
            if (!ok) { proc.destroyForcibly(); return null }
            if (proc.exitValue() != 0) return null
            val bytes = proc.inputStream.readBytes()
            if (bytes.isEmpty() || bytes.size < 100) return null
            bytes
        } catch (_: Exception) { null }
    }

    private fun captureRoot(): ByteArray? {
        return try {
            val proc = ProcessBuilder("su", "-c", "screencap -p")
                .redirectErrorStream(true)
                .start()
            val ok = proc.waitFor(10, TimeUnit.SECONDS)
            if (!ok) { proc.destroyForcibly(); return null }
            if (proc.exitValue() != 0) return null
            val bytes = proc.inputStream.readBytes()
            if (bytes.isEmpty() || bytes.size < 100) return null
            bytes
        } catch (_: Exception) { null }
    }

    private fun captureViaTmp(): ByteArray? {
        return try {
            val tmpFile = File("/data/local/tmp/screen_${System.currentTimeMillis()}.png")
            val proc = ProcessBuilder("sh", "-c", "screencap -p ${tmpFile.absolutePath}")
                .redirectErrorStream(true)
                .start()
            val ok = proc.waitFor(10, TimeUnit.SECONDS)
            if (!ok || proc.exitValue() != 0) {
                proc.destroyForcibly()
                return null
            }
            if (!tmpFile.exists() || tmpFile.length() == 0L) return null
            val bytes = tmpFile.readBytes()
            tmpFile.delete()
            if (bytes.isEmpty() || bytes.size < 100) return null
            bytes
        } catch (_: Exception) { null }
    }

    private fun captureAccessibility(callback: Callback, quality: Int, maxDim: Int) {
        val svc = AccessibilityHelper.instance ?: run {
            callback.onFailure("AccessibilityService not running")
            return
        }
        if (Build.VERSION.SDK_INT < 34) {
            callback.onFailure("Accessibility screenshot requires Android 14+")
            return
        }
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            svc.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                exec,
                object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                        try {
                            val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            if (bitmap == null) { callback.onFailure("Bitmap wrap failed"); return }
                            val bytes = processBitmap(bitmap, quality, maxDim)
                            bitmap.recycle()
                            if (bytes != null) callback.onSuccess(bytes)
                            else callback.onFailure("Process failed")
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
        } catch (e: Exception) {
            callback.onFailure("Accessibility: ${e.message}")
            exec.shutdown()
        }
    }

    private fun processBytes(data: ByteArray, quality: Int, maxDim: Int): ByteArray? {
        return try {
            val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return data
            processBitmap(bmp, quality, maxDim)
        } catch (_: Exception) { data }
    }

    private fun processBitmap(bmp: Bitmap, quality: Int, maxDim: Int): ByteArray? {
        return try {
            val w = bmp.width; val h = bmp.height
            val resized = if (w > maxDim || h > maxDim) {
                val r = maxDim.toFloat() / maxOf(w, h)
                Bitmap.createScaledBitmap(bmp, (w * r).toInt(), (h * r).toInt(), true)
                    .also { if (it !== bmp) bmp.recycle() }
            } else bmp
            val out = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (resized !== bmp) resized.recycle()
            out.toByteArray()
        } catch (_: Exception) { null }
    }
}
