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
import java.util.UUID

internal const val LEGACY_ACCOUNT = "legacy"

internal data class SmsStoreState(
    val messages: List<SmsItem> = emptyList(),
    val accounts: Map<String, ParsedInfo> = emptyMap(),
    val selectedSubId: Int? = null,
    val unread: Int = 0,
    val metadata: Map<String, Map<String, FieldFreshness>> = emptyMap()
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
        if (!result.matched) return this
        val previous = metadata[key].orEmpty()
        val accepted = result.matchedFields.filterTo(mutableSetOf()) {
            message.timestamp >= (previous[it]?.updatedAt ?: 0L)
        }
        if (accepted.isEmpty()) return this
        val info = applyParsedFields(accounts[key] ?: ParsedInfo(), result.info, accepted)
        val updated = previous + accepted.associateWith { FieldFreshness(message.timestamp, message.id) }
        return copy(accounts = accounts + (key to info), metadata = metadata + (key to updated))
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
    private const val VERSION = 3

    fun encode(state: SmsStoreState): String = JSONObject().apply {
        put("version", VERSION)
        put("messages", JSONArray().apply {
            state.messages.forEach { message ->
                put(JSONObject().apply {
                    put("sender", message.sender)
                    put("body", message.body)
                    put("time", message.time)
                    put("subId", message.subId ?: JSONObject.NULL)
                    put("id", message.id)
                    put("read", message.read)
                    put("starred", message.starred)
                    put("timestamp", message.timestamp)
                })
            }
        })
        put("accounts", JSONObject().apply {
            state.accounts.forEach { (key, value) -> put(key, encodeInfo(value)) }
        })
        put("metadata", JSONObject().apply {
            state.metadata.forEach { (account, fields) ->
                put(account, JSONObject().apply {
                    fields.forEach { (field, freshness) -> put(field, JSONObject()
                        .put("updatedAt", freshness.updatedAt)
                        .put("sourceMessageId", freshness.sourceMessageId ?: JSONObject.NULL)) }
                })
            }
        })
        put("selectedSubId", state.selectedSubId ?: JSONObject.NULL)
        put("unread", state.unread)
    }.toString()

    fun decode(json: String): SmsStoreState {
        val root = JSONObject(json)
        val version = root.getInt("version")
        require(version in 2..VERSION) { "不支持的短信数据版本" }
        val rawMessages = decodeMessages(root.getJSONArray("messages"))
        val unread = root.optInt("unread", 0).coerceIn(0, rawMessages.size)
        val messages = if (version == 2) migrateReadFlags(uniqueLegacyIds(rawMessages), unread) else rawMessages
        require(messages.map { it.id }.toSet().size == messages.size) { "短信记录 ID 重复，备份可能已损坏" }
        val accountJson = root.getJSONObject("accounts")
        val accounts = mutableMapOf<String, ParsedInfo>()
        accountJson.keys().forEach { key ->
            require(key == LEGACY_ACCOUNT || key.startsWith("archived:") || key.toIntOrNull()?.let { it >= 0 } == true)
            accounts[key] = decodeInfo(accountJson.getJSONObject(key))
        }
        val metadata = mutableMapOf<String, Map<String, FieldFreshness>>()
        val metadataJson = root.optJSONObject("metadata") ?: JSONObject()
        metadataJson.keys().forEach { account ->
            val fields = metadataJson.getJSONObject(account)
            metadata[account] = fields.keys().asSequence().associateWith { field ->
                val value = fields.getJSONObject(field)
                FieldFreshness(value.getLong("updatedAt").coerceAtLeast(0L),
                    if (value.isNull("sourceMessageId")) null else value.getString("sourceMessageId"))
            }
        }
        // Non-zero legacy values remain visible, but their age is explicitly unknown.
        if (version == 2) accounts.forEach { (key, info) -> metadata[key] = legacyMetadata(info) }
        return SmsStoreState(messages, accounts, optionalSubId(root, "selectedSubId"), unread, metadata)
    }

    fun decodeLegacy(messagesJson: String, infoJson: String, unread: Int, selectedSubId: Int?): SmsStoreState {
        val messages = uniqueLegacyIds(decodeMessages(JSONArray(messagesJson)))
        val infoObject = JSONObject(infoJson)
        val accounts = if (infoObject.length() == 0) emptyMap()
            else mapOf(LEGACY_ACCOUNT to decodeInfo(infoObject))
        return SmsStoreState(migrateReadFlags(messages, unread), accounts, selectedSubId?.takeIf { it >= 0 },
            unread.coerceIn(0, messages.size), accounts.mapValues { legacyMetadata(it.value) })
    }

    private fun optionalSubId(obj: JSONObject, key: String): Int? =
        if (!obj.has(key) || obj.isNull(key)) null else obj.getInt(key).takeIf { it >= 0 }

    private fun decodeMessages(array: JSONArray): List<SmsItem> = List(array.length()) { i ->
        val obj = array.getJSONObject(i)
        val sender = obj.getString("sender")
        val body = obj.getString("body")
        val time = obj.getString("time")
        val subId = optionalSubId(obj, "subId")
        SmsItem(sender, body, time, subId,
            obj.optString("id").takeIf { it.isNotBlank() } ?: stableMessageId(sender, body, time, subId),
            obj.optBoolean("read", false), obj.optBoolean("starred", false),
            obj.optLong("timestamp", messageTimestamp(time)).coerceAtLeast(0L))
    }

    private fun uniqueLegacyIds(messages: List<SmsItem>): List<SmsItem> {
        val used = mutableSetOf<String>()
        return messages.mapIndexed { index, message ->
            if (used.add(message.id)) message else {
                val id = UUID.nameUUIDFromBytes("${message.id}:legacy:$index".toByteArray(Charsets.UTF_8)).toString()
                used.add(id)
                message.copy(id = id)
            }
        }
    }

    private fun migrateReadFlags(messages: List<SmsItem>, unread: Int): List<SmsItem> {
        val unreadIds = messages.sortedByDescending { it.time }.take(unread.coerceIn(0, messages.size)).map { it.id }.toSet()
        return messages.map { it.copy(read = it.id !in unreadIds) }
    }

    private fun legacyMetadata(info: ParsedInfo): Map<String, FieldFreshness> = buildMap {
        fun known(key: String, yes: Boolean) { if (yes) put(key, FieldFreshness(0L, null)) }
        known("balance", info.balance.toDoubleOrNull()?.let { it != 0.0 } == true)
        known("consumption", info.consumption.toDoubleOrNull()?.let { it != 0.0 } == true)
        known("usedData", info.usedDataMb != 0.0)
        known("remainingData", info.remainingTotalDataMb != 0.0)
        known("generalData", info.generalDataMb != 0.0)
        known("voiceInclude", info.totalVoice != 0)
        known("voiceRemain", info.remainingVoice != 0)
        known("traffic", info.trafficDetails.isNotEmpty())
        known("bill", info.historicalBills.isNotEmpty())
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

internal fun applyParsedFields(current: ParsedInfo, result: ParsedInfo, fields: Set<String>): ParsedInfo = current.copy(
    balance = if ("balance" in fields) result.balance else current.balance,
    consumption = if ("consumption" in fields) result.consumption else current.consumption,
    usedDataMb = if ("usedData" in fields) result.usedDataMb else current.usedDataMb,
    remainingTotalDataMb = if ("remainingData" in fields) result.remainingTotalDataMb else current.remainingTotalDataMb,
    generalDataMb = if ("generalData" in fields) result.generalDataMb else current.generalDataMb,
    totalVoice = if ("voiceInclude" in fields) result.totalVoice else current.totalVoice,
    remainingVoice = if ("voiceRemain" in fields) result.remainingVoice else current.remainingVoice,
    trafficDetails = if ("traffic" in fields) result.trafficDetails else current.trafficDetails,
    historicalBills = if ("bill" in fields) result.historicalBills else current.historicalBills
)

internal fun SmsStoreState.remapSubscriptions(mapping: Map<Int, Int>): SmsStoreState {
    require(mapping.values.toSet().size == mapping.values.size) { "不能将多张历史 SIM 合并到同一张卡" }
    val used = mutableSetOf<String>()
    val keys = (accounts.keys + metadata.keys).associateWith { old ->
        val base = old.toIntOrNull()?.let { mapping[it]?.toString() ?: "archived:$old" } ?: old
        var unique = base
        var suffix = 1
        while (!used.add(unique)) { unique = "$base:${suffix++}" }
        unique
    }
    val remapped = messages.map { it.copy(subId = it.subId?.let(mapping::get)) }
    return copy(messages = remapped, accounts = accounts.mapKeys { keys.getValue(it.key) },
        metadata = metadata.mapKeys { keys.getValue(it.key) }, selectedSubId = selectedSubId?.let(mapping::get),
        unread = remapped.count { !it.read })
}

internal fun SmsStoreState.mergeBackup(restored: SmsStoreState): SmsStoreState {
    // Two SIMs can receive identical replies in the same second. Stable IDs, not text,
    // define identity in full backups; legacy text imports keep their own compatibility rule.
    val knownIds = messages.map { it.id }.toHashSet()
    val added = restored.messages.filter { knownIds.add(it.id) }
    val combined = (messages + added).sortedByDescending { it.time }
    return copy(messages = combined, accounts = restored.accounts + accounts,
        metadata = restored.metadata + metadata, unread = combined.count { !it.read })
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
    private val mutableSelectedMetadata = MutableStateFlow<Map<String, FieldFreshness>>(emptyMap())
    private val deleted = linkedMapOf<String, List<SmsItem>>()
    val selectedMetadata: StateFlow<Map<String, FieldFreshness>> = mutableSelectedMetadata.asStateFlow()
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
            subId?.takeIf { it >= 0 }, id = UUID.randomUUID().toString(), timestamp = timestamp.coerceAtLeast(0L))
        // Some ROMs redeliver the same broadcast; retain distinct later messages.
        if (state.messages.any { it.sender == sender && it.body == body && it.subId == message.subId && it.timestamp == message.timestamp }) return@synchronized
        state = state.copy(messages = (listOf(message) + state.messages).sortedByDescending { it.time },
            unread = (state.unread.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        state = parseMessage(context, state, message)
        publishLocked()
        persistLocked(context)
        val key = state.accountKey(message.subId)
        val updatedFields = state.metadata[key].orEmpty().filterValues { it.sourceMessageId == message.id }.keys
        QueryRepository.onIncoming(context, message, updatedFields)
        val info = state.accounts[key]
        message.subId?.takeIf { updatedFields.isNotEmpty() }?.let { sim ->
            Reminders.onAccountUpdated(context, sim,
                info?.balance?.toDoubleOrNull()?.takeIf { "balance" in updatedFields },
                info?.remainingTotalDataMb?.takeIf { "remainingData" in updatedFields }, message.timestamp)
        }
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
        state = state.copy(messages = state.messages.map { it.copy(read = true) }, unread = 0)
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
        deleted.clear()
        publishLocked()
    }

    fun markRead(context: Context, ids: Set<String>, read: Boolean = true) = synchronized(lock) {
        init(context)
        changeMessagesLocked(context, state.messages.map { if (it.id in ids) it.copy(read = read) else it })
    }

    fun toggleStar(context: Context, id: String) = synchronized(lock) {
        init(context)
        changeMessagesLocked(context, state.messages.map { if (it.id == id) it.copy(starred = !it.starred) else it })
    }

    /** Tokens restore only app records and never alter Android's system SMS database. */
    fun deleteMessages(context: Context, ids: Set<String>): String = synchronized(lock) {
        init(context)
        val removed = state.messages.filter { it.id in ids }
        val token = UUID.randomUUID().toString()
        changeMessagesLocked(context, state.messages.filterNot { it.id in ids })
        deleted[token] = removed
        while (deleted.size > 10) deleted.remove(deleted.keys.first())
        token
    }

    fun undoDelete(context: Context, token: String): Boolean = synchronized(lock) {
        init(context)
        val removed = deleted[token] ?: return@synchronized false
        val ids = state.messages.map { it.id }.toSet()
        changeMessagesLocked(context, (state.messages + removed.filterNot { it.id in ids }).sortedByDescending { it.time })
        deleted.remove(token)
        true
    }

    /** Shared restore lock order is SMS then queries, matching incoming-message processing. */
    fun <T> withBackupLock(block: () -> T): T = synchronized(lock) { block() }

    fun exportSnapshot(): String = synchronized(lock) { SmsStateCodec.encode(state) }
    fun validateSnapshot(json: String) { SmsStateCodec.decode(json) }
    fun previewSnapshot(json: String): SmsBackupSummary = SmsStateCodec.decode(json).let {
        SmsBackupSummary(it.messages.size, it.accounts.size)
    }

    /** A foreign subscription id has no relationship to a local SIM without an explicit map. */
    fun restoreSnapshot(context: Context, json: String, merge: Boolean,
                        subscriptionMapping: Map<Int, Int> = emptyMap()) = synchronized(lock) {
        init(context)
        require(subscriptionMapping.values.all { it >= 0 }) { "SIM 映射无效" }
        val restored = SmsStateCodec.decode(json).remapSubscriptions(subscriptionMapping)
        val previous = state
        // The current screen owns the active SIM selection; a foreign backup must
        // never silently change which account the visible SIM chip refers to.
        state = (if (merge) state.mergeBackup(restored) else restored).copy(selectedSubId = previous.selectedSubId)
        if (!persistLocked(context)) { state = previous; error(mutableStorageError.value ?: "恢复短信失败") }
        deleted.clear()
        publishLocked()
    }

    private fun changeMessagesLocked(context: Context, messages: List<SmsItem>) {
        if (messages == state.messages) return
        val previous = state
        state = state.copy(messages = messages, unread = messages.count { !it.read })
        if (!persistLocked(context)) { state = previous; error(mutableStorageError.value ?: "短信保存失败") }
        publishLocked()
    }

    /** Clears only SMS data; the UI's complete reset also resets settings/configuration. */
    fun factoryReset(context: Context) = synchronized(lock) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()) { "清除短信数据失败" }
        state = SmsStoreState()
        deleted.clear()
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
        val key = if (state.usesLegacyAccount()) LEGACY_ACCOUNT else state.accountKey(state.selectedSubId)
        mutableSelectedMetadata.value = state.metadata[key].orEmpty()
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
