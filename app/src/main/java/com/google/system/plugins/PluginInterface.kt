package com.google.system.plugins

import android.content.Context

interface Plugin {
    val id: String
    val name: String
    val version: String
    val commands: List<String>
    val description: String
    
    fun onEnable(context: Context): Boolean
    fun onDisable()
    fun handleCommand(cmd: String, payload: String?): String?
    fun getConfig(): Map<String, Any>
}
