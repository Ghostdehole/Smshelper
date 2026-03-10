@file:Suppress("unused", "DEPRECATION")
package com.ghost.smshelper

import android.content.Context
import android.util.Base64
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ======================= 主题引擎 =======================
object ThemeManager {
    val themes = listOf(
        lightColorScheme(primary = Color(0xFFFF9800), secondary = Color(0xFF29B6F6), background = Color(0xFFFFF9C4), surface = Color.White), // 经典元气黄
        lightColorScheme(primary = Color(0xFF0984E3), secondary = Color(0xFF00CEC9), background = Color(0xFFDFE6E9), surface = Color.White), // 极客冰川蓝
        lightColorScheme(primary = Color(0xFFE84393), secondary = Color(0xFFFD79A8), background = Color(0xFFFFEAA7), surface = Color.White), // 猛男樱花粉
        lightColorScheme(primary = Color(0xFF00B894), secondary = Color(0xFF55E6C1), background = Color(0xFFE8F8F5), surface = Color.White)  // 清新薄荷绿
    )
    val currentThemeIndex = MutableStateFlow(0)

    fun init(context: Context) {
        currentThemeIndex.value = context.getSharedPreferences("SmsHelper_Prefs", Context.MODE_PRIVATE).getInt("theme_index", 0)
    }

    fun setTheme(context: Context, index: Int) {
        if (index in themes.indices) {
            currentThemeIndex.value = index
            context.getSharedPreferences("SmsHelper_Prefs", Context.MODE_PRIVATE).edit { putInt("theme_index", index) }
        }
    }
}

// ======================= 基础工具与加密 =======================
object CryptoUtil {
    private const val ALGORITHM = "AES/CBC/PKCS5Padding"
    private val KEY = SecretKeySpec("GhostSmsHelperAg".toByteArray(), "AES")
    private val IV = IvParameterSpec(ByteArray(16))

    fun encrypt(data: String): String = try {
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, KEY, IV)
        Base64.encodeToString(cipher.doFinal(data.toByteArray()), Base64.NO_WRAP)
    } catch (_: Exception) { data }

    fun decrypt(data: String): String = try {
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, KEY, IV)
        String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)))
    } catch (_: Exception) { data }
}

// ======================= 数据模型 =======================
data class SimInfo(val subId: Int, val displayName: String, val slotIndex: Int, val carrierName: String, val number: String, val mcc: String, val mnc: String, val iccId: String, val countryIso: String, val isRoaming: Boolean)
data class ActionCommand(val code: String, val name: String, val requiresInput: Boolean = false, val inputHint: String = "")
data class CommandCategory(val title: String, val commands: List<ActionCommand>)
data class SmsItem(val sender: String, val body: String, val time: String)
data class BillRecord(val month: String, val amount: String)
data class ParsedInfo(var balance: String = "0.00", var consumption: String = "0.00", var usedDataMb: Double = 0.0, var remainingTotalDataMb: Double = 0.0, var generalDataMb: Double = 0.0, var totalVoice: Int = 0, var remainingVoice: Int = 0, var trafficDetails: List<String> = emptyList(), var historicalBills: List<BillRecord> = emptyList())
data class CarrierConfig(val identifier: String, val name: String, val targetNumber: String, val syncCommands: List<String>, val regexRules: Map<String, String>, val categories: List<CommandCategory>)
data class ConfigProfile(val id: String, val name: String, val source: String, val jsonContent: String, val carriers: List<CarrierConfig>)
data class CarrierSelection(val identifier: String, val profileId: String, val config: CarrierConfig)
data class Subscription(val url: String, var name: String, var intervalMinutes: Int, var lastUpdateTime: Long, var profileId: String?)

// ======================= 规则配置管理 =======================
object ConfigManager {
    private val profiles = mutableMapOf<String, ConfigProfile>()
    private val selections = mutableStateMapOf<String, CarrierSelection>()
    private val subscriptions = mutableMapOf<String, Subscription>()
    val activeConfig = MutableStateFlow<CarrierConfig?>(null)

    fun init(context: Context) {
        loadFromPrefs(context)
        if (profiles.isEmpty()) importDefaultProfiles(context)
    }
    fun getAllProfiles(): List<ConfigProfile> = profiles.values.toList()
    fun getSelections(): Map<String, CarrierSelection> = selections
    fun getAllSubscriptions(): List<Subscription> = subscriptions.values.toList()

    fun getCurrentProfileJson(): String {
        val active = activeConfig.value ?: return ""
        val activeSelection = selections[active.identifier] ?: return ""
        return profiles[activeSelection.profileId]?.jsonContent ?: ""
    }

    fun importProfile(context: Context, jsonStr: String, name: String, source: String): Boolean {
        return try {
            val carriers = parseCarriersFromJson(jsonStr)
            if (carriers.isEmpty()) return false
            val profileId = UUID.randomUUID().toString()
            profiles[profileId] = ConfigProfile(profileId, name, source, jsonStr, carriers)
            carriers.forEach {
                if (selections[it.identifier] == null) selections[it.identifier] = CarrierSelection(it.identifier, profileId, it)
            }
            saveToPrefs(context); true
        } catch (_: Exception) { false }
    }

    fun deleteProfile(context: Context, profileId: String) {
        profiles.remove(profileId)
        val toRemove = mutableListOf<String>()
        selections.forEach { (k, v) -> if (v.profileId == profileId) toRemove.add(k) }
        toRemove.forEach { selections.remove(it) }
        subscriptions.values.forEach { if (it.profileId == profileId) it.profileId = null }
        saveToPrefs(context)
    }

    fun selectProfileForCarrier(context: Context, identifier: String, profileId: String) {
        val carrierConfig = profiles[profileId]?.carriers?.find { it.identifier == identifier } ?: return
        selections[identifier] = CarrierSelection(identifier, profileId, carrierConfig)
        saveToPrefs(context)
        if (activeConfig.value?.identifier == identifier) activeConfig.value = carrierConfig
    }

    fun switchCarrier(mnc: String, carrierName: String) {
        val id = when {
            mnc in listOf("00", "02", "07", "08") || carrierName.contains("移动") -> "CMCC"
            mnc in listOf("01", "06", "09") || carrierName.contains("联通") -> "CUCC"
            mnc in listOf("03", "05", "11") || carrierName.contains("电信") -> "CTCC"
            mnc == "15" || carrierName.contains("广电") -> "CBN"
            else -> carrierName.ifBlank { "OTHER" }
        }
        activeConfig.value = selections[id]?.config ?: profiles.values.flatMap { it.carriers }.find { it.identifier == id } ?: profiles.values.flatMap { it.carriers }.firstOrNull()
    }

    fun addOrUpdateSubscription(context: Context, url: String, name: String, intervalMinutes: Int) {
        subscriptions[url] = Subscription(url, name, intervalMinutes, subscriptions[url]?.lastUpdateTime ?: 0L, subscriptions[url]?.profileId)
        saveToPrefs(context)
    }

    fun removeSubscription(context: Context, url: String) { subscriptions.remove(url); saveToPrefs(context) }

    suspend fun updateSubscription(context: Context, url: String): Boolean {
        val sub = subscriptions[url] ?: return false
        return try {
            val jsonStr = withContext(Dispatchers.IO) { URL(url).readText() }
            parseCarriersFromJson(jsonStr)
            val success = if (sub.profileId != null && profiles.containsKey(sub.profileId)) {
                val newCarriers = parseCarriersFromJson(jsonStr)
                val oldProfile = profiles[sub.profileId!!]
                if (oldProfile != null) {
                    profiles[sub.profileId!!] = oldProfile.copy(jsonContent = jsonStr, carriers = newCarriers)
                }
                true
            } else importProfile(context, jsonStr, sub.name, "订阅: $url")

            if (success) { sub.lastUpdateTime = System.currentTimeMillis(); saveToPrefs(context) }
            success
        } catch (_: Exception) { false }
    }

    suspend fun checkSubscriptions(context: Context) {
        val now = System.currentTimeMillis()
        subscriptions.values.forEach { if (it.intervalMinutes > 0 && now >= it.lastUpdateTime + TimeUnit.MINUTES.toMillis(it.intervalMinutes.toLong())) updateSubscription(context, it.url) }
    }

    fun importDefaultProfiles(context: Context) {
        val builtInIds = profiles.values.filter { it.source == "内置" }.map { it.id }
        builtInIds.forEach { deleteProfile(context, it) }
        try {
            val maps = mapOf(
                R.raw.default_config_cmcc to "内置移动规则",
                R.raw.default_config_cucc to "内置联通规则",
                R.raw.default_config_ctcc to "内置电信规则",
                R.raw.default_config_cbn to "内置广电规则"
            )
            maps.forEach { (resId, name) ->
                val json = context.resources.openRawResource(resId).bufferedReader().use { it.readText() }
                importProfile(context, json, name, "内置")
            }
        } catch (_: Exception) {}
    }

    private fun saveToPrefs(context: Context) {
        val prefs = context.getSharedPreferences("SmsHelper_Config", Context.MODE_PRIVATE)
        val profilesArray = JSONArray()
        profiles.values.forEach { profilesArray.put(JSONObject().apply { put("id", it.id); put("name", it.name); put("source", it.source); put("jsonContent", it.jsonContent) }) }

        val selectionsObj = JSONObject()
        selections.forEach { (id, sel) ->
            selectionsObj.put(id, JSONObject().apply { put("identifier", sel.identifier); put("profileId", sel.profileId) })
        }

        val subsArray = JSONArray()
        subscriptions.values.forEach { subsArray.put(JSONObject().apply { put("url", it.url); put("name", it.name); put("intervalMinutes", it.intervalMinutes); put("lastUpdateTime", it.lastUpdateTime); put("profileId", it.profileId ?: "") }) }
        prefs.edit { putString("profiles", profilesArray.toString()); putString("selections", selectionsObj.toString()); putString("subs", subsArray.toString()) }
    }

    private fun loadFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences("SmsHelper_Config", Context.MODE_PRIVATE)
        try {
            val profilesArray = JSONArray(prefs.getString("profiles", "[]") ?: "[]")
            for (i in 0 until profilesArray.length()) {
                val obj = profilesArray.getJSONObject(i)
                val json = obj.getString("jsonContent")
                profiles[obj.getString("id")] = ConfigProfile(obj.getString("id"), obj.getString("name"), obj.getString("source"), json, parseCarriersFromJson(json))
            }

            val selectionsStr = prefs.getString("selections", "{}") ?: "{}"
            val selectionsObj = JSONObject(selectionsStr)
            val selKeys = selectionsObj.keys()
            while(selKeys.hasNext()) {
                val id = selKeys.next() as String
                val pId = selectionsObj.getJSONObject(id).getString("profileId")
                val foundCarrier = profiles[pId]?.carriers?.find { it.identifier == id }
                if (foundCarrier != null) {
                    selections[id] = CarrierSelection(id, pId, foundCarrier)
                }
            }

            val subsArray = JSONArray(prefs.getString("subs", "[]") ?: "[]")
            for (i in 0 until subsArray.length()) {
                val obj = subsArray.getJSONObject(i)
                val profileIdRaw = obj.optString("profileId")
                subscriptions[obj.getString("url")] = Subscription(obj.getString("url"), obj.getString("name"), obj.getInt("intervalMinutes"), obj.getLong("lastUpdateTime"), profileIdRaw.ifBlank { null })
            }
        } catch (_: Exception) {}
    }

    private fun parseCarriersFromJson(jsonStr: String): List<CarrierConfig> {
        val arr = JSONObject(jsonStr).getJSONArray("carriers")
        return List(arr.length()) { i ->
            val cObj = arr.getJSONObject(i)
            val syncArr = cObj.optJSONArray("syncCommands") ?: JSONArray()
            val rulesObj = cObj.optJSONObject("regexRules") ?: JSONObject()
            val rules = mutableMapOf<String, String>()
            val rKeys = rulesObj.keys()
            while(rKeys.hasNext()) { val k = rKeys.next() as String; rules[k] = rulesObj.getString(k) }
            val catsArr = cObj.optJSONArray("categories") ?: JSONArray()
            CarrierConfig(
                cObj.getString("identifier"), cObj.getString("name"), cObj.getString("targetNumber"),
                List(syncArr.length()) { syncArr.getString(it) }, rules,
                List(catsArr.length()) { j ->
                    val catObj = catsArr.getJSONObject(j)
                    val cmdsArr = catObj.getJSONArray("commands")
                    CommandCategory(catObj.getString("title"), List(cmdsArr.length()) { k ->
                        val cmdObj = cmdsArr.getJSONObject(k)
                        ActionCommand(cmdObj.getString("code"), cmdObj.getString("name"), cmdObj.optBoolean("requiresInput", false), cmdObj.optString("inputHint", ""))
                    })
                }
            )
        }
    }
}

// ======================= 持久化与解析中枢 =======================
object SmsRepository {
    val messages = MutableStateFlow<List<SmsItem>>(emptyList())
    val parsedInfo = MutableStateFlow(ParsedInfo())
    val unreadCount = MutableStateFlow(0)

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("SmsHelper_Data", Context.MODE_PRIVATE)
        val encryptedSms = prefs.getString("sms_data_enc", "") ?: ""
        val smsJsonStr = if (encryptedSms.isNotEmpty()) CryptoUtil.decrypt(encryptedSms) else "[]"

        try {
            val smsArray = JSONArray(smsJsonStr)
            val loadedSms = mutableListOf<SmsItem>()
            for (i in 0 until smsArray.length()) {
                val obj = smsArray.getJSONObject(i)
                loadedSms.add(SmsItem(obj.getString("sender"), obj.getString("body"), obj.getString("time")))
            }
            messages.value = loadedSms
        } catch (_: Exception) {}

        unreadCount.value = prefs.getInt("unread_count", 0)

        val infoJsonStr = prefs.getString("parsed_info", "{}") ?: "{}"
        if (infoJsonStr != "{}") {
            try {
                val infoObj = JSONObject(infoJsonStr)
                val billsArray = infoObj.optJSONArray("bills") ?: JSONArray()
                val bills = mutableListOf<BillRecord>()
                for (i in 0 until billsArray.length()) bills.add(BillRecord(billsArray.getJSONObject(i).getString("month"), billsArray.getJSONObject(i).getString("amount")))
                val trafficArray = infoObj.optJSONArray("traffic") ?: JSONArray()
                val traffic = mutableListOf<String>()
                for (i in 0 until trafficArray.length()) traffic.add(trafficArray.getString(i))

                parsedInfo.value = ParsedInfo(
                    balance = infoObj.optString("balance", "0.00"), consumption = infoObj.optString("consumption", "0.00"),
                    usedDataMb = infoObj.optDouble("usedDataMb", 0.0), remainingTotalDataMb = infoObj.optDouble("remainingTotalDataMb", 0.0),
                    generalDataMb = infoObj.optDouble("generalDataMb", 0.0), totalVoice = infoObj.optInt("totalVoice", 0),
                    remainingVoice = infoObj.optInt("remainingVoice", 0), historicalBills = bills, trafficDetails = traffic
                )
            } catch (_: Exception) {}
        }
    }

    fun addMessage(context: Context, sender: String, body: String) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val newList = messages.value.toMutableList()
        newList.add(0, SmsItem(sender, body, time))
        messages.value = newList
        unreadCount.value += 1
        smartParse(body)
        saveToPrefs(context)
    }

    fun clearUnread(context: Context) { unreadCount.value = 0; saveToPrefs(context) }
    fun clearHistory(context: Context) { messages.value = emptyList(); unreadCount.value = 0; saveToPrefs(context) }
    fun factoryReset(context: Context) { messages.value = emptyList(); parsedInfo.value = ParsedInfo(); unreadCount.value = 0; context.getSharedPreferences("SmsHelper_Data", Context.MODE_PRIVATE).edit { clear() } }

    private fun parseMb(str: String?): Double {
        if (str == null) return 0.0
        var mb = 0.0
        "([0-9.]+)GB".toRegex().find(str)?.let { mb += it.groupValues[1].toDouble() * 1024 }
        "([0-9.]+)MB".toRegex().find(str)?.let { mb += it.groupValues[1].toDouble() }
        return mb
    }

    private fun smartParse(body: String) {
        val rules = ConfigManager.activeConfig.value?.regexRules ?: return
        val currentInfo = parsedInfo.value

        val newUsedDataMb = parseMb(rules["usedData"]?.toRegex()?.find(body)?.groupValues?.get(1))
        val newRemainingDataMb = parseMb(rules["remainingData"]?.toRegex()?.find(body)?.groupValues?.get(1))

        var newTotalVoice = currentInfo.totalVoice
        var newRemainVoice = currentInfo.remainingVoice
        rules["voiceInclude"]?.toRegex()?.findAll(body)?.let { if(it.any()) newTotalVoice = it.sumOf { m -> m.groupValues[1].toInt() } }
        rules["voiceRemain"]?.toRegex()?.findAll(body)?.let { if(it.any()) newRemainVoice = it.sumOf { m -> m.groupValues[1].toInt() } }

        val newTrafficList = currentInfo.trafficDetails.toMutableList()
        if (body.contains("流量") && (body.contains("剩余") || body.contains("已使用"))) {
            newTrafficList.clear()
            newTrafficList.addAll(body.split("\n", ";", "；", "。").filter { it.contains("流量") && it.length > 5 }.map { it.trim() })
        }

        val newBills = currentInfo.historicalBills.toMutableList()
        rules["bill"]?.toRegex()?.findAll(body)?.let { matches ->
            if (matches.any()) { newBills.clear(); matches.forEach { newBills.add(BillRecord(it.groupValues[1], it.groupValues[2])) } }
        }

        parsedInfo.value = currentInfo.copy(
            balance = rules["balance"]?.toRegex()?.find(body)?.groupValues?.get(1) ?: currentInfo.balance,
            consumption = rules["consumption"]?.toRegex()?.find(body)?.groupValues?.get(1) ?: currentInfo.consumption,
            usedDataMb = if (newUsedDataMb > 0) newUsedDataMb else currentInfo.usedDataMb,
            remainingTotalDataMb = if (newRemainingDataMb > 0) newRemainingDataMb else currentInfo.remainingTotalDataMb,
            generalDataMb = parseMb(rules["generalData"]?.toRegex()?.find(body)?.groupValues?.get(1)).takeIf { it > 0 } ?: currentInfo.generalDataMb,
            totalVoice = newTotalVoice, remainingVoice = newRemainVoice,
            trafficDetails = newTrafficList.ifEmpty { currentInfo.trafficDetails },
            historicalBills = newBills.ifEmpty { currentInfo.historicalBills }
        )
    }

    private fun saveToPrefs(context: Context) {
        val prefs = context.getSharedPreferences("SmsHelper_Data", Context.MODE_PRIVATE)
        val smsArray = JSONArray()
        messages.value.forEach { smsArray.put(JSONObject().apply { put("sender", it.sender); put("body", it.body); put("time", it.time) }) }

        val infoObj = JSONObject().apply {
            put("balance", parsedInfo.value.balance); put("consumption", parsedInfo.value.consumption)
            put("usedDataMb", parsedInfo.value.usedDataMb); put("remainingTotalDataMb", parsedInfo.value.remainingTotalDataMb)
            put("generalDataMb", parsedInfo.value.generalDataMb); put("totalVoice", parsedInfo.value.totalVoice); put("remainingVoice", parsedInfo.value.remainingVoice)
            val billsArr = JSONArray(); parsedInfo.value.historicalBills.forEach { billsArr.put(JSONObject().apply { put("month", it.month); put("amount", it.amount) }) }; put("bills", billsArr)
            val trafficArr = JSONArray(); parsedInfo.value.trafficDetails.forEach { trafficArr.put(it) }; put("traffic", trafficArr)
        }
        prefs.edit {
            putString("sms_data_enc", CryptoUtil.encrypt(smsArray.toString()))
            putString("parsed_info", infoObj.toString())
            putInt("unread_count", unreadCount.value)
        }
    }
}