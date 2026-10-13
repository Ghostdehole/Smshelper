package com.ghost.smshelper

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Subscription IDs are device-local. Imported cards remain pending until explicitly mapped. */
data class SimPreference(
    val subId: Int,
    val nickname: String = "",
    val colorHex: String = "#4DABF7",
    val billingDay: Int = 1,
    val profileId: String? = null,
    val identifier: String? = null
)

object SimPreferences {
    private val lock = Any()
    private var initialized = false
    private val mutableData = MutableStateFlow<Map<Int, SimPreference>>(emptyMap())
    private val mutablePending = MutableStateFlow<List<SimPreference>>(emptyList())
    val data: StateFlow<Map<Int, SimPreference>> = mutableData.asStateFlow()
    val pending: StateFlow<List<SimPreference>> = mutablePending.asStateFlow()

    fun init(context: Context) = synchronized(lock) {
        if (initialized) return@synchronized
        val prefs = context.getSharedPreferences("SmsHelper_SimPreferences", Context.MODE_PRIVATE)
        runCatching { decode(prefs.getString("data", null) ?: "{\"cards\":[],\"pending\":[]}") }
            .onSuccess { (cards, pendingCards) ->
                mutableData.value = cards.associateBy { it.subId }
                mutablePending.value = pendingCards
            }
        initialized = true
    }

    fun get(subId: Int): SimPreference = data.value[subId] ?: SimPreference(subId)

    fun update(context: Context, preference: SimPreference): Boolean {
        init(context)
        if (!valid(preference)) return false
        val success = synchronized(lock) {
            persist(context, data.value + (preference.subId to preference.copy(nickname = preference.nickname.trim(), colorHex = preference.colorHex.uppercase())), pending.value)
        }
        if (success) ConfigManager.refreshSelection()
        return success
    }

    fun mapPending(context: Context, index: Int, subId: Int): Boolean {
        init(context)
        if (subId < 0) return false
        val success = synchronized(lock) {
            val card = pending.value.getOrNull(index) ?: return@synchronized false
            persist(context, data.value + (subId to card.copy(subId = subId)), pending.value.filterIndexed { i, _ -> i != index })
        }
        if (success) ConfigManager.refreshSelection()
        return success
    }

    fun reset(context: Context): Boolean {
        val success = synchronized(lock) { persist(context, emptyMap(), emptyList()) }
        if (success) ConfigManager.refreshSelection()
        return success
    }

    fun exportBackup(context: Context): String { init(context); return encode(data.value.values.toList(), pending.value).toString() }
    fun validateBackup(json: String) { decode(json) }

    fun restoreBackup(context: Context, json: String, merge: Boolean, restoreBindings: Boolean = false, subscriptionMapping: Map<Int, Int> = emptyMap(), profileIdMapping: Map<String, String> = emptyMap()): Boolean {
        val decoded = runCatching { decode(json) }.getOrNull() ?: return false
        if (subscriptionMapping.values.any { it < 0 } || subscriptionMapping.values.toSet().size != subscriptionMapping.size) return false
        init(context)
        val success = synchronized(lock) {
            val plan = runCatching { restorePlan(data.value, pending.value, decoded, merge, restoreBindings, subscriptionMapping, profileIdMapping) }.getOrNull()
                ?: return@synchronized false
            persist(context, plan.first, plan.second)
        }
        if (success) ConfigManager.refreshSelection()
        return success
    }

    internal fun restorePlan(current: Map<Int, SimPreference>, currentPending: List<SimPreference>, decoded: Pair<List<SimPreference>, List<SimPreference>>,
        merge: Boolean, restoreBindings: Boolean, subscriptionMapping: Map<Int, Int>, profileIdMapping: Map<String, String>): Pair<Map<Int, SimPreference>, List<SimPreference>> {
        require(subscriptionMapping.values.all { it >= 0 } && subscriptionMapping.values.toSet().size == subscriptionMapping.size)
        val cards = if (merge) current.toMutableMap() else mutableMapOf()
        val pendingCards = if (merge) currentPending.toMutableList() else mutableListOf()
        fun mappedProfile(card: SimPreference) = card.copy(profileId = card.profileId?.let { profileIdMapping[it] ?: it })
        if (restoreBindings) {
            // Exact same-device rollback: pending foreign IDs must never become active IDs.
            decoded.first.forEach { imported ->
                val card = mappedProfile(imported)
                if (!merge || card.subId !in cards) cards[card.subId] = card
                else if (cards[card.subId] != card) pendingCards.add(card)
            }
            pendingCards.addAll(decoded.second.map(::mappedProfile))
        } else {
            val usedTargets = mutableSetOf<Int>()
            val activeSourceIds = decoded.first.map { it.subId }.toSet()
            fun importCard(imported: SimPreference, allowMapping: Boolean) {
                val card = mappedProfile(imported)
                val target = if (allowMapping) subscriptionMapping[card.subId] else null
                if (target == null || !usedTargets.add(target)) pendingCards.add(card)
                else {
                    val replacement = card.copy(subId = target)
                    if (!merge || target !in cards) cards[target] = replacement
                    else if (cards[target] != replacement) pendingCards.add(card)
                }
            }
            decoded.first.forEach { importCard(it, true) }
            // Equal numeric IDs from separate devices are ambiguous; retain those pending.
            decoded.second.forEach { importCard(it, it.subId !in activeSourceIds) }
        }
        val uniquePending = pendingCards.distinct()
        require(cards.size <= 128 && uniquePending.size <= 128) { "SIM 设置超过容量" }
        return cards.toMap() to uniquePending
    }

    fun discardPending(context: Context, index: Int): Boolean = synchronized(lock) {
        if (index !in pending.value.indices) false else persist(context, data.value, pending.value.filterIndexed { i, _ -> i != index })
    }

    private fun valid(card: SimPreference): Boolean = card.subId >= 0 && card.nickname.length <= 64 && card.nickname.none { it < ' ' } &&
        card.colorHex.matches(Regex("#[0-9a-fA-F]{6}")) && card.billingDay in 1..31 &&
        (card.profileId == null || card.profileId.length in 1..128) && (card.identifier == null || card.identifier.length in 1..128)

    private fun decode(json: String): Pair<List<SimPreference>, List<SimPreference>> {
        require(json.length <= 128_000) { "SIM 设置过大" }
        val root = JSONObject(json)
        require(root.optInt("version", 1) == 1) { "不支持的 SIM 设置版本" }
        fun cards(key: String): List<SimPreference> {
            val values = root.optJSONArray(key) ?: JSONArray()
            require(values.length() <= 128) { "SIM 数量过多" }
            return List(values.length()) { index ->
                val obj = values.getJSONObject(index)
                SimPreference(obj.getInt("subId"), obj.optString("nickname", ""), obj.optString("colorHex", "#4DABF7"), obj.optInt("billingDay", 1),
                    obj.optString("profileId", "").takeIf { it.isNotBlank() && it != "null" }, obj.optString("identifier", "").takeIf { it.isNotBlank() && it != "null" })
                    .also { require(valid(it)) { "SIM 设置无效" } }
            }
        }
        val active = cards("cards")
        require(active.map { it.subId }.distinct().size == active.size) { "SIM 标识重复" }
        return active to cards("pending")
    }

    private fun encode(cards: List<SimPreference>, pendingCards: List<SimPreference>): JSONObject = JSONObject().apply {
        put("version", 1)
        fun array(items: List<SimPreference>) = JSONArray().apply { items.forEach { card -> put(JSONObject().apply {
            put("subId", card.subId); put("nickname", card.nickname); put("colorHex", card.colorHex); put("billingDay", card.billingDay)
            put("profileId", card.profileId ?: ""); put("identifier", card.identifier ?: "")
        }) } }
        put("cards", array(cards)); put("pending", array(pendingCards))
    }

    private fun persist(context: Context, cards: Map<Int, SimPreference>, pendingCards: List<SimPreference>): Boolean = try {
        require(cards.size <= 128 && pendingCards.size <= 128) { "SIM 设置超过 128 条，请先清理或绑定待恢复项目" }
        if (!context.getSharedPreferences("SmsHelper_SimPreferences", Context.MODE_PRIVATE).edit()
                .putString("data", encode(cards.values.toList(), pendingCards).toString()).commit()) false
        else { mutableData.value = cards.toMap(); mutablePending.value = pendingCards.toList(); initialized = true; true }
    } catch (_: Exception) { false }
}
