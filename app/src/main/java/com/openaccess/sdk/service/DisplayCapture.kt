package com.openaccess.sdk.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DisplayCapture(private val context: Context) {

    companion object {
        private const val QUALITY = 85
        private const val STREAM_QUALITY = 60
        private const val MAX_DIM = 1920
        private const val STREAM_MAX_DIM = 720
        private const val SCREENCAP_PATH = "/system/bin/screencap"
        var mediaProjection: MediaProjection? = null
            private set
        var projectionResultCode: Int = 0
            private set
        var projectionData: Intent? = null
            private set

        fun setProjection(resultCode: Int, data: Intent) {
            projectionResultCode = resultCode
            projectionData = data
        }

        fun initProjection(context: Context): Boolean {
            return try {
                val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val data = projectionData ?: return false
                val proj = mgr.getMediaProjection(projectionResultCode, data)
                mediaProjection = proj
                proj.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        mediaProjection = null
                    }
                }, Handler(Looper.getMainLooper()))
                true
            } catch (_: Exception) {
                false
            }
        }

        fun getProjectionIntent(context: Context): Intent {
            val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            return mgr.createScreenCaptureIntent()
        }
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

        if (Build.VERSION.SDK_INT >= 34) {
            val svc = AccessibilityHelper.instance
            if (svc != null) {
                captureAccessibility(callback, quality, maxDim)
                return
            }
        }

        val mp = mediaProjection
        if (mp != null) {
            captureMediaProjection(mp, callback, quality, maxDim)
            return
        }

        val sdcardResult = captureToSdcard()
        if (sdcardResult != null) {
            val processed = processBytes(sdcardResult, quality, maxDim)
            if (processed != null) { callback.onSuccess(processed); return }
        }

        val cacheResult = captureToCache()
        if (cacheResult != null) {
            val processed = processBytes(cacheResult, quality, maxDim)
            if (processed != null) { callback.onSuccess(processed); return }
        }

        val rootResult = captureRoot()
        if (rootResult != null) {
            val processed = processBytes(rootResult, quality, maxDim)
            if (processed != null) { callback.onSuccess(processed); return }
        }

        val directResult = captureDirect()
        if (directResult != null) {
            val processed = processBytes(directResult, quality, maxDim)
            if (processed != null) { callback.onSuccess(processed); return }
        }

        val tmpResult = captureViaTmp()
        if (tmpResult != null) {
            val processed = processBytes(tmpResult, quality, maxDim)
            if (processed != null) { callback.onSuccess(processed); return }
        }

        callback.onFailure("Screenshot failed")
    }

    private fun captureMediaProjection(
        projection: MediaProjection,
        callback: Callback,
        quality: Int,
        maxDim: Int
    ) {
        try {
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val metrics = context.resources.displayMetrics
            val width = minOf(metrics.widthPixels, maxDim)
            val height = minOf(metrics.heightPixels, maxDim)
            val density = metrics.densityDpi

            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            var virtualDisplay: VirtualDisplay? = null
            val done = AtomicBoolean(false)

            virtualDisplay = projection.createVirtualDisplay(
                "OpenAccessCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                reader.surface, null, null
            )

            Handler(Looper.getMainLooper()).postDelayed({
                if (done.get()) return@postDelayed
                try {
                    val image = reader.acquireLatestImage()
                    if (image == null) {
                        done.set(true)
                        callback.onFailure("MediaProjection: no image")
                        return@postDelayed
                    }
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride, height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                    image.close()

                    val bytes = processBitmap(bitmap, quality, maxDim)
                    bitmap.recycle()
                    done.set(true)

                    if (bytes != null) callback.onSuccess(bytes)
                    else callback.onFailure("MediaProjection: process failed")
                } catch (e: Exception) {
                    if (!done.get()) {
                        done.set(true)
                        callback.onFailure("MediaProjection: ${e.message}")
                    }
                } finally {
                    try { virtualDisplay?.release() } catch (_: Exception) {}
                    try { reader.close() } catch (_: Exception) {}
                }
            }, 500)
        } catch (e: Exception) {
            callback.onFailure("MediaProjection: ${e.message}")
        }
    }

    private fun captureToSdcard(): ByteArray? {
        return try {
            val sdcardDir = Environment.getExternalStorageDirectory()
            val tmpFile = File(sdcardDir, ".screen_${System.currentTimeMillis()}.png")
            val proc = ProcessBuilder(SCREENCAP_PATH, "-p", tmpFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            val ok = proc.waitFor(10, TimeUnit.SECONDS)
            if (!ok || proc.exitValue() != 0) {
                proc.destroyForcibly()
                if (tmpFile.exists()) tmpFile.delete()
                return null
            }
            if (!tmpFile.exists() || tmpFile.length() == 0L) {
                if (tmpFile.exists()) tmpFile.delete()
                return null
            }
            val bytes = tmpFile.readBytes()
            tmpFile.delete()
            if (bytes.isEmpty() || bytes.size < 100) return null
            bytes
        } catch (_: Exception) { null }
    }

    private fun captureToCache(): ByteArray? {
        return try {
            val cacheDir = context.cacheDir
            val tmpFile = File.createTempFile("sc_", ".png", cacheDir)
            val proc = ProcessBuilder(SCREENCAP_PATH, "-p", tmpFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            val ok = proc.waitFor(10, TimeUnit.SECONDS)
            if (!ok || proc.exitValue() != 0) {
                proc.destroyForcibly()
                tmpFile.delete()
                return null
            }
            if (!tmpFile.exists() || tmpFile.length() == 0L) {
                tmpFile.delete()
                return null
            }
            val bytes = tmpFile.readBytes()
            tmpFile.delete()
            if (bytes.isEmpty() || bytes.size < 100) return null
            bytes
        } catch (_: Exception) { null }
    }

    private fun captureDirect(): ByteArray? {
        return try {
            val proc = ProcessBuilder("sh", "-c", "$SCREENCAP_PATH -p")
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
            val proc = ProcessBuilder("su", "-c", "$SCREENCAP_PATH -p")
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
            val proc = ProcessBuilder("sh", "-c", "$SCREENCAP_PATH -p ${tmpFile.absolutePath}")
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
                            val hb = result.hardwareBuffer
                            val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                            if (bitmap == null) { hb.close(); callback.onFailure("Bitmap wrap failed"); return }
                            val bytes = processBitmap(bitmap, quality, maxDim)
                            bitmap.recycle()
                            hb.close()
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
