package com.ghost.smshelper

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import java.util.regex.Pattern

/** The same bounded schema is used for bundled, imported and downloaded profiles. */
object ConfigValidation {
    const val MAX_PROFILE_BYTES = 1024 * 1024
    private val captures = mapOf("balance" to 1, "consumption" to 1, "usedData" to 1,
        "remainingData" to 1, "generalData" to 1, "voiceInclude" to 1, "voiceRemain" to 1, "bill" to 2)

    fun parse(json: String): List<CarrierConfig> {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_PROFILE_BYTES) { "规则文件不能超过 1 MB" }
        val carriers = JSONObject(json.removePrefix("\uFEFF")).getJSONArray("carriers")
        require(carriers.length() in 1..64) { "规则必须包含 1 至 64 个运营商" }
        val identifiers = mutableSetOf<String>()
        var commandCount = 0
        return List(carriers.length()) { index ->
            val obj = carriers.getJSONObject(index)
            val id = requiredString(obj, "identifier", 128)
            require(identifiers.add(id)) { "运营商标识重复: $id" }
            val name = requiredString(obj, "name", 128)
            val target = requiredString(obj, "targetNumber", 32)
            require(CarrierIdentity.normalizeNumber(target) != null) { "无效的目标号码" }
            val sync = optionalArray(obj, "syncCommands")
            require(sync.length() <= 64) { "同步指令过多" }
            val syncCommands = List(sync.length()) { stringValue(sync.get(it), "同步指令", 1024) }
            val rulesObj = if (obj.has("regexRules")) obj.getJSONObject("regexRules") else JSONObject()
            require(rulesObj.length() <= 64) { "解析规则过多" }
            val rules = linkedMapOf<String, String>()
            rulesObj.keys().forEach { key ->
                val value = rulesObj.get(key)
                require(value is String && value.isNotEmpty() && value.length <= 2048) { "$key 正则内容无效" }
                val regex = value
                val compiled = Pattern.compile(regex)
                val groups = captures[key]
                require(groups == null || compiled.matcher("").groupCount() >= groups) { "$key 缺少捕获组" }
                rules[key] = regex
            }
            val categories = optionalArray(obj, "categories")
            require(categories.length() <= 128) { "分类过多" }
            val parsedCategories = List(categories.length()) { categoryIndex ->
                val category = categories.getJSONObject(categoryIndex)
                val title = requiredString(category, "title", 256)
                val commands = category.getJSONArray("commands")
                commandCount += commands.length()
                require(commandCount <= 4096) { "指令总数过多" }
                CommandCategory(title, List(commands.length()) { commandIndex ->
                    val command = commands.getJSONObject(commandIndex)
                    val code = requiredString(command, "code", 1024)
                    val label = requiredString(command, "name", 512)
                    val requiresInput = if (command.has("requiresInput")) {
                        require(command.get("requiresInput") is Boolean) { "requiresInput 必须为布尔值" }
                        command.getBoolean("requiresInput")
                    } else false
                    val hint = if (command.has("inputHint")) {
                        require(command.get("inputHint") is String) { "inputHint 必须为文本" }
                        command.getString("inputHint").also { require(it.length <= 512) }
                    } else ""
                    ActionCommand(code, label, requiresInput, hint)
                })
            }
            CarrierConfig(id, name, target, syncCommands, rules, parsedCategories)
        }
    }

    fun subscriptionUrl(raw: String): String? = runCatching {
        val value = raw.trim()
        require(value.length in 1..2048)
        val uri = URI(value)
        require(uri.scheme?.lowercase() in setOf("http", "https"))
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.fragment == null)
        require(uri.port == -1 || uri.port in 1..65535)
        uri.toASCIIString()
    }.getOrNull()

    private fun optionalArray(obj: JSONObject, key: String): JSONArray =
        if (obj.has(key)) obj.getJSONArray(key) else JSONArray()

    private fun requiredString(obj: JSONObject, key: String, max: Int) = stringValue(obj.get(key), key, max)

    private fun stringValue(value: Any, key: String, max: Int): String {
        require(value is String) { "$key 必须为文本" }
        require(value.isNotBlank() && value.length <= max && value.none { it < ' ' }) { "$key 内容无效" }
        return value
    }
}

/** Resolve identity from a SIM's home network, never a visited roaming network's name. */
object CarrierIdentity {
    private val chineseNames = setOf("CMCC", "CUCC", "CTCC", "CBN", "移动", "联通", "电信", "广电", "中国移动", "中国联通", "中国电信", "中国广电")

    fun identifier(mcc: String?, mnc: String, carrierName: String): String? {
        if (mcc == "460") return when (mnc.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.padStart(2, '0')) {
            "00", "02", "07", "08" -> "CMCC"
            "01", "06", "09" -> "CUCC"
            "03", "05", "11" -> "CTCC"
            "15" -> "CBN"
            else -> identifierFromName(carrierName)
        }
        // Missing home-network metadata may use an exact carrier name, never MNC alone.
        if (mcc.isNullOrBlank() || mcc == "0") return identifierFromName(carrierName)
        return carrierName.takeIf { it.isNotBlank() && it.trim() !in chineseNames }
    }

    private fun identifierFromName(carrierName: String): String? = when (carrierName.trim()) {
            "中国移动", "移动", "CMCC" -> "CMCC"
            "中国联通", "联通", "CUCC" -> "CUCC"
            "中国电信", "电信", "CTCC" -> "CTCC"
            "中国广电", "广电", "CBN" -> "CBN"
            else -> carrierName.takeIf { it.isNotBlank() }
    }

    fun normalizeNumber(raw: String?): String? {
        if (raw == null || raw.length > 64) return null
        val compact = raw.trim().replace(Regex("[\\s()\\-]"), "")
        if (!compact.matches(Regex("\\+?[0-9]{3,20}"))) return null
        return when {
            compact.startsWith("+86") -> compact.removePrefix("+86")
            compact.startsWith("0086") -> compact.removePrefix("0086")
            else -> compact
        }.takeIf { it.length >= 3 }
    }

    fun matches(sender: String?, target: String): Boolean {
        val number = normalizeNumber(sender) ?: return false
        return number == normalizeNumber(target)
    }
}

/** Parameter validation is independent of UI and never sends a message. */
object CommandParameters {
    fun validate(command: ActionCommand, input: String): String? {
        val value = input.trim()
        if (value.isEmpty()) return "请填写参数"
        if (value.length > 1024 || input.any { it < ' ' }) return "参数过长或包含换行等无效字符"
        return when (command.code) {
            "1011#", "MX" -> if (!value.matches(Regex("[0-9]{4}(0[1-9]|1[0-2])"))) "请输入有效年月，例如 202601" else null
            "CXGSD#", "HZ#" -> if (!value.matches(Regex("\\+?[0-9]{5,20}"))) "请输入有效电话号码" else null
            "GBJC#" -> if (!value.matches(Regex("[A-Za-z0-9_-]{1,64}"))) "请输入有效业务编码" else null
            "LJDXJB#" -> {
                val parts = value.split('#', limit = 2)
                if (parts.size != 2 || !parts[0].matches(Regex("\\+?[0-9]{3,20}")) || parts[1].isBlank()) "请输入被举报号码#举报内容" else null
            }
            "6012#" -> if (!value.matches(Regex("[A-Za-z0-9]{3,64}"))) "请输入有效证件号码" else null
            "tzxy" -> if (!value.matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?"))) "请输入非负金额，最多两位小数" else null
            else -> null
        }
    }
}

/** Pure in-memory transitions, also exercised by the local regression tests. */
internal class ConfigCatalog {
    val profiles = linkedMapOf<String, ConfigProfile>()
    val selections = linkedMapOf<String, CarrierSelection>()
    val subscriptions = linkedMapOf<String, Subscription>()
    val revisions = mutableListOf<RuleRevision>()

    fun selected(identifier: String): CarrierConfig? = selections[identifier]?.config

    fun replaceProfile(profile: ConfigProfile) {
        val ownedIds = selections.filterValues { it.profileId == profile.id }.keys.toSet()
        profiles[profile.id] = profile
        ownedIds.forEach { id ->
            val replacement = profile.carriers.find { it.identifier == id }
                ?: profiles.values.asSequence().filter { it.id != profile.id }.flatMap { it.carriers.asSequence() }.find { it.identifier == id }
            if (replacement == null) selections.remove(id)
            else {
                val owner = if (profile.carriers.any { it.identifier == id }) profile
                    else profiles.values.first { candidate -> candidate.carriers.any { it === replacement } }
                selections[id] = CarrierSelection(id, owner.id, replacement)
            }
        }
        profile.carriers.forEach { carrier ->
            selections.putIfAbsent(carrier.identifier, CarrierSelection(carrier.identifier, profile.id, carrier))
        }
    }

    fun select(identifier: String, profileId: String): Boolean {
        val carrier = profiles[profileId]?.carriers?.find { it.identifier == identifier } ?: return false
        selections[identifier] = CarrierSelection(identifier, profileId, carrier)
        return true
    }

    fun removeProfile(id: String): Boolean {
        val profile = profiles.remove(id) ?: return false
        revisions.removeAll { it.profileId == id }
        selections.entries.removeAll { it.value.profileId == id }
        profile.carriers.forEach { carrier ->
            if (selections[carrier.identifier] == null) profiles.values.firstOrNull { candidate -> candidate.carriers.any { it.identifier == carrier.identifier } }
                ?.let { select(carrier.identifier, it.id) }
        }
        subscriptions.entries.toList().forEach { (url, subscription) ->
            if (subscription.profileId == id) subscriptions[url] = subscription.copy(profileId = null)
        }
        return true
    }

    fun applySubscription(url: String, json: String, carriers: List<CarrierConfig>, now: Long): Boolean {
        val subscription = subscriptions[url] ?: return false
        require(carriers.isNotEmpty())
        val id = subscription.profileId?.takeIf { profiles.containsKey(it) } ?: UUID.randomUUID().toString()
        replaceProfile(ConfigProfile(id, subscription.name, "订阅: $url", json, carriers))
        subscriptions[url] = subscription.copy(profileId = id, lastUpdateTime = now)
        return true
    }

    fun recoverSubscriptionLinks() {
        subscriptions.entries.toList().forEach { (url, sub) ->
            if (sub.profileId == null || !profiles.containsKey(sub.profileId)) {
                val candidates = profiles.values.filter { it.source == "订阅: $url" }
                val selectedIds = selections.values.map { it.profileId }.toSet()
                val match = candidates.lastOrNull { it.id in selectedIds } ?: candidates.lastOrNull()
                subscriptions[url] = sub.copy(profileId = match?.id)
            }
        }
    }

    fun copy(): ConfigCatalog = ConfigCatalog().also { copy ->
        copy.profiles.putAll(profiles)
        copy.selections.putAll(selections)
        // All transitions replace Subscription instances; retain identities for in-flight downloads.
        copy.subscriptions.putAll(subscriptions)
        copy.revisions.addAll(revisions)
    }
}
