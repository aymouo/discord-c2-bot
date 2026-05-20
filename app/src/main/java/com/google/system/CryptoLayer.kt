package com.google.system

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoLayer {

    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val IV_LENGTH = 12
    private const val SALT_LENGTH = 16
    private const val ITERATIONS = 10000
    private const val KEY_LENGTH = 256

    private var masterKey: SecretKey? = null
    private var hmacKey: SecretKey? = null

    init {
        initializeKeys()
    }

    private fun initializeKeys() {
        val deviceSeed = getDeviceSeed()
        masterKey = deriveKey(deviceSeed, "master".toByteArray())
        hmacKey = deriveKey(deviceSeed, "hmac".toByteArray())
    }

    private fun getDeviceSeed(): ByteArray {
        val seed = StringBuilder()
        seed.append(android.os.Build.SERIAL)
        seed.append(android.os.Build.BOARD)
        seed.append(android.os.Build.HARDWARE)
        seed.append(android.os.Build.FINGERPRINT)
        seed.append(System.getProperty("os.version"))
        return sha256(seed.toString()).toByteArray()
    }

    fun encrypt(plaintext: String): String {
        return try {
            val key = masterKey ?: return plaintext
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = ByteArray(IV_LENGTH)
            SecureRandom().nextBytes(iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val result = iv + encrypted
            val hmac = computeHMAC(result)
            Base64.encodeToString(result + hmac, Base64.NO_WRAP)
        } catch (_: Exception) { plaintext }
    }

    fun decrypt(ciphertext: String): String {
        return try {
            val key = masterKey ?: return ciphertext
            val data = Base64.decode(ciphertext, Base64.NO_WRAP)
            val hmacLength = 32
            if (data.size < IV_LENGTH + hmacLength + 1) return ciphertext
            val encryptedData = data.copyOfRange(0, data.size - hmacLength)
            val receivedHmac = data.copyOfRange(data.size - hmacLength, data.size)
            val computedHmac = computeHMAC(encryptedData)
            if (!computedHmac.contentEquals(receivedHmac)) return ciphertext
            val iv = encryptedData.copyOfRange(0, IV_LENGTH)
            val encrypted = encryptedData.copyOfRange(IV_LENGTH, encryptedData.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) { ciphertext }
    }

    fun encryptFile(data: ByteArray): ByteArray {
        return try {
            val key = masterKey ?: return data
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = ByteArray(IV_LENGTH)
            SecureRandom().nextBytes(iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val encrypted = cipher.doFinal(data)
            iv + encrypted
        } catch (_: Exception) { data }
    }

    fun decryptFile(data: ByteArray): ByteArray {
        return try {
            val key = masterKey ?: return data
            if (data.size < IV_LENGTH + 1) return data
            val iv = data.copyOfRange(0, IV_LENGTH)
            val encrypted = data.copyOfRange(IV_LENGTH, data.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            cipher.doFinal(encrypted)
        } catch (_: Exception) { data }
    }

    fun hash(data: String): String {
        return sha256(data)
    }

    fun generateRandomKey(): String {
        val key = ByteArray(32)
        SecureRandom().nextBytes(key)
        return Base64.encodeToString(key, Base64.NO_WRAP)
    }

    private fun deriveKey(seed: ByteArray, salt: ByteArray): SecretKey {
        return try {
            val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = javax.crypto.spec.PBEKeySpec(
                String(seed).toCharArray(),
                salt,
                ITERATIONS,
                KEY_LENGTH
            )
            val tmp = factory.generateSecret(spec)
            SecretKeySpec(tmp.encoded, ALGORITHM)
        } catch (_: Exception) {
            SecretKeySpec(ByteArray(32), ALGORITHM)
        }
    }

    private fun computeHMAC(data: ByteArray): ByteArray {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(hmacKey ?: SecretKeySpec(ByteArray(32), "HmacSHA256"))
            mac.doFinal(data)
        } catch (_: Exception) { ByteArray(32) }
    }

    private fun sha256(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { "" }
    }
}
