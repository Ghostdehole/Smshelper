package com.ghost.smshelper

import org.json.JSONArray
import org.json.JSONObject

/** Lossless editor representation for all supported carrier, command and regex fields. */
object RuleEditorCodec {
    val fieldLabels = linkedMapOf("balance" to "余额（元）", "consumption" to "消费（元）", "usedData" to "已用流量（含单位）",
        "remainingData" to "剩余流量（含单位）", "generalData" to "通用流量（含单位）", "voiceInclude" to "语音总量（分钟）",
        "voiceRemain" to "剩余语音（分钟）", "bill" to "账单（月份、金额）")

    fun encode(carriers: List<CarrierConfig>): String = JSONObject().put("carriers", JSONArray().apply {
        carriers.forEach { carrier -> put(JSONObject().apply {
            put("identifier", carrier.identifier); put("name", carrier.name); put("targetNumber", carrier.targetNumber)
            put("syncCommands", JSONArray(carrier.syncCommands)); put("regexRules", JSONObject(carrier.regexRules))
            put("categories", JSONArray().apply { carrier.categories.forEach { category -> put(JSONObject().apply {
                put("title", category.title); put("commands", JSONArray().apply { category.commands.forEach { command -> put(JSONObject().apply {
                    put("code", command.code); put("name", command.name); put("requiresInput", command.requiresInput); put("inputHint", command.inputHint)
                }) } })
            }) } })
        }) }
    }).toString(2)

    fun summary(json: String): String {
        val carriers = ConfigValidation.parse(json)
        return "${carriers.size} 个运营商 · ${carriers.sumOf { it.categories.sumOf { category -> category.commands.size } }} 条指令 · ${carriers.sumOf { it.regexRules.size }} 条解析规则"
    }

    fun changes(oldJson: String?, newJson: String): List<String> {
        val old = oldJson?.let { ConfigValidation.parse(it) }.orEmpty().associateBy { it.identifier }
        val fresh = ConfigValidation.parse(newJson).associateBy { it.identifier }
        val changes = mutableListOf<String>()
        old.keys.minus(fresh.keys).forEach { changes += "移除运营商：${old.getValue(it).name}" }
        fresh.forEach { (id, carrier) ->
            val previous = old[id]
            if (previous == null) changes += "新增运营商：${carrier.name}"
            else {
                if (previous.name != carrier.name) changes += "$id 名称：${previous.name} → ${carrier.name}"
                if (previous.targetNumber != carrier.targetNumber) changes += "${carrier.name} 号码：${previous.targetNumber} → ${carrier.targetNumber}"
                if (previous.syncCommands != carrier.syncCommands) changes += "${carrier.name} 查询组合：${previous.syncCommands.joinToString()} → ${carrier.syncCommands.joinToString()}"
                (previous.regexRules.keys + carrier.regexRules.keys).distinct().forEach { key ->
                    if (previous.regexRules[key] != carrier.regexRules[key]) changes += "${carrier.name} 解析规则：${fieldLabels[key] ?: key} 已变更"
                }
                if (previous.categories != carrier.categories) {
                    val before = previous.categories.sumOf { it.commands.size }
                    val after = carrier.categories.sumOf { it.commands.size }
                    changes += "${carrier.name} 指令分类或内容已变更（$before → $after 条）"
                }
            }
        }
        return changes.ifEmpty { listOf("规则内容没有变化") }
    }
}

data class RuleRevision(val id: String, val profileId: String, val name: String, val json: String, val createdAt: Long, val reason: String)
data class SubscriptionPreview(val url: String, val name: String, val previousJson: String?, val json: String,
    val carriers: List<CarrierConfig>, internal val original: Subscription)
