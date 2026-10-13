package com.ghost.smshelper

import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable backups deliberately use a password key, never the device's Android Keystore. */
internal object FullBackupCodec {
    const val MAX_FILE_BYTES = 48 * 1024 * 1024
    private const val MAX_PAYLOAD_BYTES = 32 * 1024 * 1024
    private const val FORMAT = "smshelper-backup"
    private const val ITERATIONS = 210_000
    private val sections = setOf("sms", "queries", "rules", "sims", "theme", "ui", "settings", "reminders")
    private val aad = "$FORMAT:1:PBKDF2WithHmacSHA256:AES-256-GCM".toByteArray(Charsets.UTF_8)

    fun document(parts: Map<String, String>, createdAt: Long = System.currentTimeMillis()): String =
        JSONObject().apply {
            put("format", FORMAT)
            put("version", 1)
            put("createdAt", createdAt)
            put("sections", JSONObject().apply { parts.forEach { (name, json) -> put(name, JSONObject(json)) } })
        }.toString().also(::validate)

    fun validate(json: String): JSONObject {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) { "备份内容超过 32 MB" }
        val root = JSONObject(json)
        require(root.getString("format") == FORMAT && root.getInt("version") == 1) { "不支持的完整备份格式" }
        require(root.getLong("createdAt") >= 0) { "备份时间无效" }
        val parts = root.getJSONObject("sections")
        require(parts.has("sms") && parts.has("rules")) { "备份缺少短信或规则数据" }
        parts.keys().forEach { key ->
            require(key in sections) { "备份包含未知配置区域：$key" }
            parts.getJSONObject(key)
        }
        return root
    }

    fun encrypted(source: String): Boolean = JSONObject(source).optString("format") == "$FORMAT-encrypted"

    fun encode(document: String, password: CharArray? = null): String {
        validate(document)
        if (password == null) return document
        require(password.size in 8..1024) { "备份密码至少需要 8 个字符" }
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val key = derive(password, salt, ITERATIONS)
        val ciphertext = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                updateAAD(aad)
                doFinal(document.toByteArray(Charsets.UTF_8))
            }
        } finally { key.fill(0) }
        return JSONObject().apply {
            put("format", "$FORMAT-encrypted")
            put("version", 1)
            put("kdf", "PBKDF2WithHmacSHA256")
            put("cipher", "AES-256-GCM")
            put("iterations", ITERATIONS)
            put("salt", Base64.getEncoder().encodeToString(salt))
            put("iv", Base64.getEncoder().encodeToString(iv))
            put("ciphertext", Base64.getEncoder().encodeToString(ciphertext))
        }.toString()
    }

    fun decode(source: String, password: CharArray? = null): String {
        require(source.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "备份文件超过 48 MB" }
        val envelope = JSONObject(source)
        if (!encrypted(source)) return source.also(::validate)
        require(envelope.getInt("version") == 1 && envelope.getString("kdf") == "PBKDF2WithHmacSHA256" &&
            envelope.getString("cipher") == "AES-256-GCM") { "不支持的备份加密格式" }
        val iterations = envelope.getInt("iterations")
        require(iterations in 100_000..600_000) { "备份密钥参数无效" }
        require(password != null && password.size in 1..1024) { "请输入备份密码" }
        val salt = Base64.getDecoder().decode(envelope.getString("salt"))
        val iv = Base64.getDecoder().decode(envelope.getString("iv"))
        val ciphertext = Base64.getDecoder().decode(envelope.getString("ciphertext"))
        require(salt.size == 16 && iv.size == 12 && ciphertext.size in 16..MAX_PAYLOAD_BYTES + 16) { "备份加密参数无效" }
        val key = derive(password, salt, iterations)
        val plain = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                updateAAD(aad)
                doFinal(ciphertext).toString(Charsets.UTF_8)
            }
        } catch (_: java.security.GeneralSecurityException) {
            throw IllegalArgumentException("密码不正确，或备份文件已损坏")
        } finally { key.fill(0) }
        return plain.also(::validate)
    }

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
}
