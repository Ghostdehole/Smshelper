package com.ghost.smshelper

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

fun readUtf8Text(input: InputStream, maxBytes: Int): String {
    require(maxBytes > 0)
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(bytes.size().toLong() + count <= maxBytes) { "文件过大" }
        bytes.write(buffer, 0, count)
    }
    return bytes.toString(Charsets.UTF_8.name())
}

/** Versioned JSON remains a plain-text file, with lossless message escaping. */
object TxtBackup {
    fun encode(messages: List<SmsItem>): String {
        val entries = JSONArray()
        messages.forEach { message ->
            entries.put(JSONObject().put("sender", message.sender).put("body", message.body)
                .put("time", message.time).put("subId", message.subId ?: JSONObject.NULL))
        }
        return JSONObject().put("format", "SmsHelperBackup").put("version", 2)
            .put("messages", entries).toString(2)
    }

    fun decode(text: String): List<SmsItem> {
        val input = text.removePrefix("\uFEFF").trim()
        require(input.isNotEmpty()) { "备份文件为空" }
        if (input.startsWith("{")) {
            val root = JSONObject(input)
            require(root.optString("format") == "SmsHelperBackup" && root.optInt("version") == 2) { "不支持的备份版本" }
            val entries = root.getJSONArray("messages")
            return List(entries.length()) { index ->
                val entry = entries.getJSONObject(index)
                val sender = entry.getString("sender")
                val body = entry.getString("body")
                require(sender.isNotBlank()) { "备份发送方为空" }
                SmsItem(sender, body, entry.getString("time"), if (entry.isNull("subId")) null else entry.getInt("subId"))
            }
        }
        // Legacy files never escaped delimiters. Accept well-formed old records,
        // consuming only standalone boundary markers rather than arbitrary text.
        val legacy = input.replace("\r\n", "\n")
        val pattern = Regex("(?ms)^\\[SMS]\\nSender: ([^\\n]*)\\nTime: ([^\\n]*)\\nBody: (.*?)\\n\\[END](?=\\n\\n\\[SMS]\\n|\\s*\\z)")
        val matches = pattern.findAll(legacy).toList()
        require(matches.isNotEmpty()) { "无法识别短信备份格式" }
        var position = 0
        val messages = matches.map { match ->
            require(legacy.substring(position, match.range.first).isBlank()) { "备份记录不完整" }
            position = match.range.last + 1
            val (sender, time, body) = match.destructured
            require(sender.isNotBlank()) { "备份发送方为空" }
            SmsItem(sender, body, time)
        }
        require(legacy.substring(position).isBlank()) { "备份记录不完整" }
        return messages
    }
}
