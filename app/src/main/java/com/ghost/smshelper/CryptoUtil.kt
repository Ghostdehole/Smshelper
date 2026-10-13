package com.ghost.smshelper

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** New writes use an authenticated, device-bound key; the old format is read only. */
object CryptoUtil {
    private const val KEY_ALIAS = "SmsHelper.Data.v2"
    private const val PREFIX = "v2:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private val legacyKey = SecretKeySpec("GhostSmsHelperAg".toByteArray(Charsets.UTF_8), "AES")

    @Synchronized
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "短信存储密钥不可用，原有数据已保留" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    fun encrypt(data: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    fun decrypt(data: String): String {
        if (data.startsWith(PREFIX)) {
            val parts = data.removePrefix(PREFIX).split(':')
            require(parts.size == 2) { "短信存储格式损坏" }
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            require(iv.size == 12) { "短信存储校验失败" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, iv))
            return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }

        // Older versions silently wrote plaintext when encryption failed. Only recognized
        // legacy JSON is accepted here; malformed ciphertext must never become new data.
        if (data.trimStart().startsWith("[")) return data
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, legacyKey, IvParameterSpec(ByteArray(16)))
        return String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
    }
}
