package com.ghost.smshelper

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class BackupPreview(val createdAt: Long, val messageCount: Int, val subscriptionIds: List<Int>, val sections: List<String>)

internal object FullBackupManager {
    private val lock = Any()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val booleanSettings = setOf("compat_mode", "intercept_all_sms", "haptics_enabled", "reduce_motion")
    private val themeKeys = setOf("theme_index", "custom_themes_json", "custom_theme_id", "recent_colors_json")

    fun export(context: Context): String = SmsRepository.withBackupLock {
        QueryRepository.withBackupTransaction(context) { ConfigManager.withBackupLock {
        val prefs = context.getSharedPreferences("SmsHelper_Prefs", Context.MODE_PRIVATE)
        FullBackupCodec.document(linkedMapOf(
            "sms" to SmsRepository.exportSnapshot(),
            "queries" to QueryRepository.exportSnapshot(),
            "rules" to ConfigManager.exportBackup(context),
            "sims" to SimPreferences.exportBackup(context),
            "theme" to preferencesJson(prefs, themeKeys).toString(),
            "ui" to context.getSharedPreferences("SmsHelper_UI", Context.MODE_PRIVATE).getString("commands", "{}").orEmpty(),
            "settings" to preferencesJson(prefs, booleanSettings).toString(),
            "reminders" to Reminders.exportBackup(context)
        ))
        } }
    }

    fun preview(document: String): BackupPreview {
        val root = FullBackupCodec.validate(document)
        val parts = root.getJSONObject("sections")
        SmsStateCodec.decode(parts.getJSONObject("sms").toString())
        parts.optJSONObject("queries")?.let { QueryRepository.validateSnapshot(it.toString()) }
        ConfigManager.validateBackup(parts.getJSONObject("rules").toString())
        parts.optJSONObject("sims")?.let { SimPreferences.validateBackup(it.toString()) }
        parts.optJSONObject("theme")?.let(::validateTheme)
        parts.optJSONObject("ui")?.let(BackupUiPreferences::validate)
        parts.optJSONObject("settings")?.let { settings -> settings.keys().forEach { key ->
            require(key in booleanSettings && settings.get(key) is Boolean) { "备份应用设置无效" }
        } }
        parts.optJSONObject("reminders")?.let { Reminders.validateBackup(it.toString()) }
        return BackupPreview(root.getLong("createdAt"), parts.getJSONObject("sms").getJSONArray("messages").length(),
            sourceSubscriptions(parts).sorted(), parts.keys().asSequence().toList())
    }

    fun restore(context: Context, document: String, merge: Boolean, mapping: Map<Int, Int>) {
        check(mutableBusy.compareAndSet(false, true)) { "已有恢复任务正在进行" }
        try {
            synchronized(lock) {
                SmsRepository.withBackupLock {
                    QueryRepository.withBackupTransaction(context) {
                        ConfigManager.withBackupLock {
                            val preview = preview(document)
                            val active = getActiveSims(context).map { it.subId }.toSet()
                            require(mapping.keys.all { it in preview.subscriptionIds } && mapping.values.all { it in active } && mapping.values.toSet().size == mapping.size) { "SIM 对应关系无效，请重新选择" }
                            val before = JSONObject(export(context)).getJSONObject("sections")
                            val parts = JSONObject(document).getJSONObject("sections")
                            try {
                                applyConfiguration(context, parts, merge, mapping)
                                parts.optJSONObject("queries")?.let { QueryRepository.restoreSnapshot(context, it.toString(), merge, mapping) }
                                // SMS restore is atomic and the final operation; incoming SMS and
                                // transport receipts are serialized until all stores commit.
                                SmsRepository.restoreSnapshot(context, parts.getJSONObject("sms").toString(), merge, mapping)
                            } catch (error: Exception) {
                                val rollback = runCatching { applyConfiguration(context, before, false, sourceSubscriptions(before).associateWith { it }, rollback = true) }
                                if (rollback.isFailure) throw IllegalStateException("恢复失败，部分配置未能回滚。原备份文件未修改，请检查存储空间后重试。", error)
                                throw IllegalStateException("恢复失败，配置已回滚：${error.message ?: "请检查存储空间"}", error)
                            }
                        }
                    }
                }
            }
        } finally {
            mutableBusy.value = false
        }
    }

    private fun applyConfiguration(context: Context, parts: JSONObject, merge: Boolean, mapping: Map<Int, Int>, rollback: Boolean = false) {
        val profileMapping = mutableMapOf<String, String>()
        check(ConfigManager.restoreBackup(context, parts.getJSONObject("rules").toString(), merge, profileMapping)) { "规则保存失败" }
        parts.optJSONObject("sims")?.let {
            check(SimPreferences.restoreBackup(context, it.toString(), merge, restoreBindings = rollback, subscriptionMapping = mapping, profileIdMapping = profileMapping)) { "SIM 设置保存失败" }
        }
        val prefs = context.getSharedPreferences("SmsHelper_Prefs", Context.MODE_PRIVATE)
        val edit = prefs.edit()
        parts.optJSONObject("settings")?.let { settings ->
            if (!merge) booleanSettings.forEach(edit::remove)
            settings.keys().forEach { key -> if (!merge || !prefs.contains(key)) edit.putBoolean(key, settings.getBoolean(key)) }
        }
        parts.optJSONObject("theme")?.let { theme ->
            val restored = if (merge) mergeTheme(preferencesJson(prefs, themeKeys), theme) else theme
            themeKeys.forEach(edit::remove)
            restored.keys().forEach { key ->
                if (key == "theme_index") edit.putInt(key, restored.getInt(key)) else edit.putString(key, restored.getString(key))
            }
        }
        check(edit.commit()) { "外观与使用设置保存失败" }
        parts.optJSONObject("ui")?.let { incoming ->
            val uiPrefs = context.getSharedPreferences("SmsHelper_UI", Context.MODE_PRIVATE)
            val restored = BackupUiPreferences.restore(JSONObject(uiPrefs.getString("commands", "{}").orEmpty()), incoming, merge, mapping)
            check(uiPrefs.edit().putString("commands", restored.toString()).commit()) { "指令与布局保存失败" }
        }
        parts.optJSONObject("reminders")?.let { Reminders.restoreBackup(context, it.toString(), merge) }
        ThemeManager.init(context)
        AppFeedbackPreferences.init(context)
        CommandStore.init(context)
    }

    private fun sourceSubscriptions(parts: JSONObject): Set<Int> = buildSet {
        val sms = parts.getJSONObject("sms")
        sms.optJSONObject("accounts")?.keys()?.forEach { it.toIntOrNull()?.takeIf { id -> id >= 0 }?.let(::add) }
        val messages = sms.getJSONArray("messages")
        repeat(messages.length()) { messages.getJSONObject(it).optInt("subId", -1).takeIf { id -> id >= 0 }?.let(::add) }
        val cards = parts.optJSONObject("sims")?.optJSONArray("cards")
        if (cards != null) repeat(cards.length()) { cards.getJSONObject(it).optInt("subId", -1).takeIf { id -> id >= 0 }?.let(::add) }
        val pending = parts.optJSONObject("sims")?.optJSONArray("pending")
        if (pending != null) repeat(pending.length()) { pending.getJSONObject(it).optInt("subId", -1).takeIf { id -> id >= 0 }?.let(::add) }
        val queries = parts.optJSONObject("queries")?.optJSONArray("tasks")
        if (queries != null) repeat(queries.length()) { queries.getJSONObject(it).optInt("subId", -1).takeIf { id -> id >= 0 }?.let(::add) }
        listOf("groups", "lastQuery").forEach { field -> parts.optJSONObject("ui")?.optJSONObject(field)?.keys()?.forEach { it.toIntOrNull()?.takeIf { id -> id >= 0 }?.let(::add) } }
    }

    private fun preferencesJson(prefs: SharedPreferences, keys: Set<String>): JSONObject = JSONObject().apply {
        keys.forEach { key -> prefs.all[key]?.let { value -> put(key, value) } }
    }

    private fun validateTheme(json: JSONObject) {
        json.keys().forEach { require(it in themeKeys) { "未知主题设置" } }
        if (json.has("theme_index")) require(json.getInt("theme_index") in 0..3) { "默认主题编号无效" }
        if (json.has("custom_theme_id")) require(json.getString("custom_theme_id").length <= 128)
        val themes = JSONArray(json.optString("custom_themes_json", "[]"))
        require(themes.length() <= 50) { "自定义主题过多" }
        repeat(themes.length()) { ThemePaletteCodec.decode(themes.getJSONObject(it).toString()) }
        val recent = JSONArray(json.optString("recent_colors_json", "[]"))
        require(recent.length() <= 100) { "最近颜色列表过长" }
        repeat(recent.length()) { require(recent.getString(it).matches(Regex("#[0-9a-fA-F]{6}"))) { "最近颜色格式无效" } }
    }

    private fun mergeTheme(existing: JSONObject, incoming: JSONObject): JSONObject {
        val result = JSONObject(existing.toString())
        incoming.keys().forEach { key -> if (!result.has(key)) result.put(key, incoming.get(key)) }
        val themes = linkedMapOf<String, JSONObject>()
        listOf(incoming, existing).forEach { source ->
            val values = JSONArray(source.optString("custom_themes_json", "[]"))
            repeat(values.length()) { val item = values.getJSONObject(it); themes[item.getString("id")] = item }
        }
        require(themes.size <= 50) { "合并后自定义主题超过 50 个，请先整理主题" }
        result.put("custom_themes_json", JSONArray(themes.values.toList()).toString())
        return result
    }
}

/** Strict whitelist prevents a portable backup from restoring permissions, PINs or raw SIM IDs. */
internal object BackupUiPreferences {
    private val arrays = setOf("favorites", "recent", "order", "cards", "hiddenCards")
    fun validate(json: JSONObject) {
        json.keys().forEach { require(it in arrays + setOf("custom", "groups", "lastQuery", "categoryColors")) { "未知界面配置" } }
        json.optJSONObject("categoryColors")?.let { colors ->
            require(colors.length() <= 2000)
            colors.keys().forEach { key -> require(key.length <= 2048 && colors.getString(key).matches(Regex("#[0-9a-fA-F]{6}"))) { "分类颜色无效" } }
        }
        arrays.forEach { key -> json.optJSONArray(key)?.let { array ->
            require(array.length() <= 10_000)
            repeat(array.length()) { require(array.getString(it).length <= 2048) { "指令标识过长" } }
        } }
        json.optJSONArray("custom")?.let { commands ->
            require(commands.length() <= 2000) { "自定义指令过多" }
            val ids = mutableSetOf<String>()
            repeat(commands.length()) { index ->
                val c = commands.getJSONObject(index)
                require(ids.add(c.getString("id")) && c.getString("id").length in 1..128) { "自定义指令 ID 无效" }
                listOf("category", "name", "code", "target").forEach { key -> require(c.getString(key).length in 1..2048) { "自定义指令缺少必要内容" } }
                require(c.optString("inputHint").length <= 2048)
                if (c.has("requiresInput")) require(c.get("requiresInput") is Boolean)
            }
        }
        listOf("groups", "lastQuery").forEach { field -> json.optJSONObject(field)?.let { values ->
            require(values.length() <= 128)
            values.keys().forEach { key ->
                require(key.toIntOrNull()?.let { it >= 0 } == true) { "SIM 查询配置无效" }
                if (field == "lastQuery") require(values.getLong(key) >= 0)
                else {
                    val g = values.getJSONObject(key)
                    require(g.getInt("intervalSeconds") in 3..120)
                    val commands = g.getJSONArray("commands")
                    require(commands.length() <= 100)
                    repeat(commands.length()) { require(commands.getString(it).length in 1..2048) }
                }
            }
        } }
    }

    fun restore(existing: JSONObject, incoming: JSONObject, merge: Boolean, mapping: Map<Int, Int>): JSONObject {
        validate(incoming)
        val result = if (merge) JSONObject(existing.toString()) else JSONObject()
        arrays.forEach { key ->
            val items = linkedSetOf<String>()
            (if (merge) listOf(existing, incoming) else listOf(incoming)).forEach { source ->
                source.optJSONArray(key)?.let { array -> repeat(array.length()) { items.add(array.getString(it)) } }
            }
            result.put(key, JSONArray(if (key == "recent") items.take(20) else items.toList()))
        }
        val custom = linkedMapOf<String, JSONObject>()
        (if (merge) listOf(incoming, existing) else listOf(incoming)).forEach { source ->
            source.optJSONArray("custom")?.let { array -> repeat(array.length()) { val c = array.getJSONObject(it); custom[c.getString("id")] = c } }
        }
        result.put("custom", JSONArray(custom.values.toList()))
        val colors = if (merge) JSONObject(existing.optJSONObject("categoryColors")?.toString() ?: "{}") else JSONObject()
        incoming.optJSONObject("categoryColors")?.let { incomingColors -> incomingColors.keys().forEach { key ->
            if (!merge || !colors.has(key)) colors.put(key, incomingColors.getString(key))
        } }
        result.put("categoryColors", colors)
        listOf("groups", "lastQuery").forEach { key ->
            val target = if (merge) JSONObject(existing.optJSONObject(key)?.toString() ?: "{}") else JSONObject()
            incoming.optJSONObject(key)?.let { values -> values.keys().forEach { oldId ->
                mapping[oldId.toInt()]?.toString()?.let { newId ->
                    if (!merge || !target.has(newId)) target.put(newId, values.get(oldId))
                }
            } }
            result.put(key, target)
        }
        validate(result)
        return result
    }
}
