package com.ghost.smshelper

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal const val LEGACY_ACCOUNT = "legacy"

internal data class SmsStoreState(
    val messages: List<SmsItem> = emptyList(),
    val accounts: Map<String, ParsedInfo> = emptyMap(),
    val selectedSubId: Int? = null,
    val unread: Int = 0
) {
    fun accountKey(subId: Int?): String = subId?.takeIf { it >= 0 }?.toString() ?: LEGACY_ACCOUNT

    fun usesLegacyAccount(): Boolean =
        selectedSubId != null && accountKey(selectedSubId) !in accounts &&
            accounts.keys.none { it != LEGACY_ACCOUNT } && LEGACY_ACCOUNT in accounts

    fun selectedInfo(): ParsedInfo = accounts[accountKey(selectedSubId)]
        ?: if (usesLegacyAccount()) accounts.getValue(LEGACY_ACCOUNT) else ParsedInfo()

    fun withParsedMessage(message: SmsItem, rules: Map<String, String>): SmsStoreState {
        val key = accountKey(message.subId)
        val result = SmsParser.parseResult(accounts[key] ?: ParsedInfo(), message.body, rules)
        return if (result.matched) copy(accounts = accounts + (key to result.info)) else this
    }

    fun withImportedMessages(imported: List<SmsItem>): Pair<SmsStoreState, Int> {
        fun identity(message: SmsItem) = Triple(message.sender, message.body, message.time)
        val known = messages.map(::identity).toHashSet()
        val added = imported.filter { known.add(identity(it)) }.map { it.copy(subId = null) }
        if (added.isEmpty()) return this to 0
        return copy(
            messages = (messages + added).sortedByDescending { it.time },
            unread = (unread.toLong() + added.size).coerceAtMost(messages.size.toLong() + added.size).toInt()
        ) to added.size
    }
}

/** JSON is kept separate from encryption so old backups and account isolation are testable. */
internal object SmsStateCodec {
    private const val VERSION = 2

    fun encode(state: SmsStoreState): String = JSONObject().apply {
        put("version", VERSION)
        put("messages", JSONArray().apply {
            state.messages.forEach { message ->
                put(JSONObject().apply {
                    put("sender", message.sender)
                    put("body", message.body)
                    put("time", message.time)
                    put("subId", message.subId ?: JSONObject.NULL)
                })
            }
        })
        put("accounts", JSONObject().apply {
            state.accounts.forEach { (key, value) -> put(key, encodeInfo(value)) }
        })
        put("selectedSubId", state.selectedSubId ?: JSONObject.NULL)
        put("unread", state.unread)
    }.toString()

    fun decode(json: String): SmsStoreState {
        val root = JSONObject(json)
        require(root.getInt("version") == VERSION) { "不支持的短信数据版本" }
        val messages = decodeMessages(root.getJSONArray("messages"))
        val accountJson = root.getJSONObject("accounts")
        val accounts = mutableMapOf<String, ParsedInfo>()
        accountJson.keys().forEach { key ->
            require(key == LEGACY_ACCOUNT || key.toIntOrNull()?.let { it >= 0 } == true)
            accounts[key] = decodeInfo(accountJson.getJSONObject(key))
        }
        return SmsStoreState(messages, accounts, optionalSubId(root, "selectedSubId"),
            root.optInt("unread", 0).coerceIn(0, messages.size))
    }

    fun decodeLegacy(messagesJson: String, infoJson: String, unread: Int, selectedSubId: Int?): SmsStoreState {
        val messages = decodeMessages(JSONArray(messagesJson))
        val infoObject = JSONObject(infoJson)
        val accounts = if (infoObject.length() == 0) emptyMap()
            else mapOf(LEGACY_ACCOUNT to decodeInfo(infoObject))
        return SmsStoreState(messages, accounts, selectedSubId?.takeIf { it >= 0 },
            unread.coerceIn(0, messages.size))
    }

    private fun optionalSubId(obj: JSONObject, key: String): Int? =
        if (!obj.has(key) || obj.isNull(key)) null else obj.getInt(key).takeIf { it >= 0 }

    private fun decodeMessages(array: JSONArray): List<SmsItem> = List(array.length()) { i ->
        val obj = array.getJSONObject(i)
        SmsItem(obj.getString("sender"), obj.getString("body"), obj.getString("time"), optionalSubId(obj, "subId"))
    }

    private fun encodeInfo(info: ParsedInfo): JSONObject = JSONObject().apply {
        put("balance", info.balance)
        put("consumption", info.consumption)
        put("usedDataMb", info.usedDataMb)
        put("remainingTotalDataMb", info.remainingTotalDataMb)
        put("generalDataMb", info.generalDataMb)
        put("totalVoice", info.totalVoice)
        put("remainingVoice", info.remainingVoice)
        put("traffic", JSONArray(info.trafficDetails))
        put("bills", JSONArray().apply {
            info.historicalBills.forEach { bill ->
                put(JSONObject().apply { put("month", bill.month); put("amount", bill.amount) })
            }
        })
    }

    private fun decodeInfo(obj: JSONObject): ParsedInfo {
        fun data(key: String): Double = obj.optDouble(key, 0.0)
            .takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val bills = obj.optJSONArray("bills") ?: JSONArray()
        val traffic = obj.optJSONArray("traffic") ?: JSONArray()
        return ParsedInfo(
            balance = obj.optString("balance", "0.00"),
            consumption = obj.optString("consumption", "0.00"),
            usedDataMb = data("usedDataMb"),
            remainingTotalDataMb = data("remainingTotalDataMb"),
            generalDataMb = data("generalDataMb"),
            totalVoice = obj.optInt("totalVoice", 0).coerceAtLeast(0),
            remainingVoice = obj.optInt("remainingVoice", 0).coerceAtLeast(0),
            trafficDetails = List(traffic.length()) { traffic.getString(it) },
            historicalBills = List(bills.length()) {
                val bill = bills.getJSONObject(it)
                BillRecord(bill.getString("month"), bill.getString("amount"))
            }
        )
    }
}

object SmsRepository {
    private const val PREFS = "SmsHelper_Data"
    private const val STATE_KEY = "state_enc"
    private val lock = Any()
    private var initialized = false
    private var persistenceBlocked = false
    private var state = SmsStoreState()

    private val mutableMessages = MutableStateFlow<List<SmsItem>>(emptyList())
    private val mutableParsedInfo = MutableStateFlow(ParsedInfo())
    private val mutableUnreadCount = MutableStateFlow(0)
    private val mutableStorageError = MutableStateFlow<String?>(null)
    private val mutableIsLegacyAccount = MutableStateFlow(false)
    val messages: StateFlow<List<SmsItem>> = mutableMessages.asStateFlow()
    val parsedInfo: StateFlow<ParsedInfo> = mutableParsedInfo.asStateFlow()
    val unreadCount: StateFlow<Int> = mutableUnreadCount.asStateFlow()
    val storageError: StateFlow<String?> = mutableStorageError.asStateFlow()
    val isLegacyAccount: StateFlow<Boolean> = mutableIsLegacyAccount.asStateFlow()

    fun init(context: Context) = synchronized(lock) {
        if (initialized) return@synchronized
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            val encryptedState = prefs.getString(STATE_KEY, null)
            state = if (encryptedState != null) {
                SmsStateCodec.decode(CryptoUtil.decrypt(encryptedState))
            } else {
                val encryptedSms = prefs.getString("sms_data_enc", "").orEmpty()
                val selected = context.getSharedPreferences("SmsHelper_Prefs", Context.MODE_PRIVATE)
                    .getInt("selected_sim_subid", -1).takeIf { it >= 0 }
                SmsStateCodec.decodeLegacy(
                    if (encryptedSms.isNotEmpty()) CryptoUtil.decrypt(encryptedSms) else "[]",
                    prefs.getString("parsed_info", "{}") ?: "{}",
                    prefs.getInt("unread_count", 0), selected
                )
            }
            initialized = true
            publishLocked()
            if (encryptedState == null && (prefs.contains("sms_data_enc") || prefs.contains("parsed_info"))) {
                persistLocked(context)
            }
        } catch (_: Exception) {
            // Never replace an unreadable snapshot with an empty one on the next SMS.
            initialized = true
            persistenceBlocked = true
            mutableStorageError.value = "无法读取原有短信数据，原文件已保留；请先导出可读记录后再重置。"
        }
    }

    fun selectSubscription(context: Context, subId: Int?) = synchronized(lock) {
        init(context)
        val normalized = subId?.takeIf { it >= 0 }
        if (state.selectedSubId == normalized) return@synchronized
        state = state.copy(selectedSubId = normalized)
        publishLocked()
        persistLocked(context)
    }

    fun addMessage(context: Context, sender: String, body: String, subId: Int? = null,
                   timestamp: Long = System.currentTimeMillis()) = synchronized(lock) {
        init(context)
        val message = SmsItem(sender, body,
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(timestamp)),
            subId?.takeIf { it >= 0 })
        state = state.copy(messages = (listOf(message) + state.messages).sortedByDescending { it.time },
            unread = (state.unread.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        state = parseMessage(context, state, message)
        publishLocked()
        persistLocked(context)
    }

    /** Subscription IDs are installation-local; imports never overwrite a live SIM's metrics. */
    fun importMessages(context: Context, imported: List<SmsItem>): Int = synchronized(lock) {
        init(context)
        val (merged, added) = state.withImportedMessages(imported)
        if (added <= 0) return@synchronized 0
        var next = merged
        // Existing metrics can outlive a cleared inbox. Only reconstruct an absent,
        // unassigned account; a historical backup must not roll live balances back.
        if (LEGACY_ACCOUNT !in state.accounts) {
            merged.messages.asReversed().filter { it.subId == null }
                .forEach { next = parseMessage(context, next, it) }
        }
        val previous = state
        state = next
        if (!persistLocked(context)) {
            state = previous
            error(mutableStorageError.value ?: "短信保存失败")
        }
        publishLocked()
        added
    }

    fun clearUnread(context: Context) = synchronized(lock) {
        init(context)
        if (state.unread == 0) return@synchronized
        state = state.copy(unread = 0)
        publishLocked()
        persistLocked(context)
    }

    fun clearHistory(context: Context) = synchronized(lock) {
        init(context)
        val previous = state
        state = state.copy(messages = emptyList(), unread = 0)
        if (!persistLocked(context)) {
            state = previous
            error(mutableStorageError.value ?: "短信保存失败")
        }
        publishLocked()
    }

    /** Clears only SMS data; the UI's complete reset also resets settings/configuration. */
    fun factoryReset(context: Context) = synchronized(lock) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()) { "清除短信数据失败" }
        state = SmsStoreState()
        initialized = true
        persistenceBlocked = false
        mutableStorageError.value = null
        publishLocked()
    }

    private fun parseMessage(context: Context, data: SmsStoreState, message: SmsItem): SmsStoreState {
        val config = ConfigManager.resolveForSubscription(context, message.subId, message.sender) ?: return data
        return data.withParsedMessage(message, config.regexRules)
    }

    private fun publishLocked() {
        mutableMessages.value = state.messages
        mutableParsedInfo.value = state.selectedInfo()
        mutableUnreadCount.value = state.unread
        mutableIsLegacyAccount.value = state.usesLegacyAccount()
    }

    private fun persistLocked(context: Context): Boolean {
        if (persistenceBlocked) return false
        return try {
            // Encrypt before creating an editor. Any failure leaves all prior keys untouched.
            val encrypted = CryptoUtil.encrypt(SmsStateCodec.encode(state))
            val success = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(STATE_KEY, encrypted)
                .remove("sms_data_enc").remove("parsed_info").remove("unread_count")
                .commit()
            check(success) { "短信保存失败" }
            mutableStorageError.value = null
            true
        } catch (_: Exception) {
            mutableStorageError.value = "短信加密保存失败，原有存储已保留；请重试或导出当前记录，未保存的新短信仅留在本次运行中。"
            false
        }
    }
}
