package com.google.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.openaccess.sdk.service.SystemNetworkService

object AppInitializer {
    private const val TAG = "AppInit"
    private var initialized = false

    fun init(ctx: Context) {
        if (initialized) return
        initialized = true
        Log.i(TAG, "init")
        try {
            SystemNetworkService.start(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "start: ${e.message}")
        }
    }

    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                Log.i(TAG, "boot received")
                try { SystemNetworkService.start(ctx) } catch (e: Exception) { Log.w(TAG, "boot: ${e.message}") }
            }
        }
    }
}
