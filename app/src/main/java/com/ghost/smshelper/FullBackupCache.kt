package com.ghost.smshelper

import android.content.Context
import java.io.File
import java.util.UUID

/** Only a short token enters saved instance state; a document picker can outlive the PIN screen. */
internal object FullBackupCache {
    private val tokenPattern = Regex("smshelper-backup-[0-9a-f-]{36}\\.json")

    fun save(context: Context, payload: String): String {
        cleanup(context)
        val token = "smshelper-backup-${UUID.randomUUID()}.json"
        val file = resolve(context, token)
        try { file.writeText(payload, Charsets.UTF_8) }
        catch (error: Exception) { file.delete(); throw error }
        return token
    }

    fun read(context: Context, token: String): String {
        val file = resolve(context, token)
        require(file.isFile) { "待导出的备份已过期，请重新导出" }
        return file.inputStream().use { readUtf8Text(it, FullBackupCodec.MAX_FILE_BYTES) }
    }

    fun remove(context: Context, token: String) { if (tokenPattern.matches(token)) resolve(context, token).delete() }

    fun cleanup(context: Context) {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60_000L
        context.cacheDir.listFiles()?.filter { tokenPattern.matches(it.name) && it.lastModified() < cutoff }?.forEach(File::delete)
    }

    fun clear(context: Context) {
        context.cacheDir.listFiles()?.filter { tokenPattern.matches(it.name) }?.forEach(File::delete)
    }

    private fun resolve(context: Context, token: String): File {
        require(tokenPattern.matches(token)) { "备份临时文件标识无效" }
        return File(context.cacheDir, token)
    }
}
