package com.ghost.smshelper

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class UiCommand(val id: String, val category: String, val command: ActionCommand, val target: String, val custom: Boolean = false)
data class QueryGroup(val commands: List<String> = emptyList(), val intervalSeconds: Int = 10)
data class CommandPreferences(
    val favorites: Set<String> = emptySet(), val recent: List<String> = emptyList(),
    val custom: List<UiCommand> = emptyList(), val order: List<String> = emptyList(),
    val cards: List<String> = listOf("account", "favorites", "recent"), val hiddenCards: Set<String> = emptySet(),
    val groups: Map<Int, QueryGroup> = emptyMap(), val lastQuery: Map<Int, Long> = emptyMap(),
    val categoryColors: Map<String, String> = emptyMap()
)

/** Small user-owned UI preferences; source carrier commands remain untouched. */
object CommandStore {
    private val mutable = MutableStateFlow(CommandPreferences())
    val data = mutable.asStateFlow()
    private var app: Context? = null
    fun init(context: Context) {
        app = context.applicationContext
        mutable.value = runCatching {
            val json = JSONObject(context.getSharedPreferences("SmsHelper_UI", Context.MODE_PRIVATE).getString("commands", "{}") ?: "{}")
            fun strings(key: String) = json.optJSONArray(key)?.let { array -> List(array.length()) { array.optString(it) }.filter(String::isNotBlank) }.orEmpty()
            val custom = json.optJSONArray("custom") ?: JSONArray()
            val groups = json.optJSONObject("groups") ?: JSONObject()
            val times = json.optJSONObject("lastQuery") ?: JSONObject()
            val colors = json.optJSONObject("categoryColors") ?: JSONObject()
            CommandPreferences(strings("favorites").toSet(), strings("recent"), List(custom.length()) { i ->
                val c = custom.getJSONObject(i)
                UiCommand(c.getString("id"), c.getString("category"), ActionCommand(c.getString("code"), c.getString("name"), c.optBoolean("requiresInput"), c.optString("inputHint")), c.getString("target"), true)
            }, strings("order"), strings("cards").filter { it in setOf("account", "favorites", "recent") }.let { existing -> existing + listOf("account", "favorites", "recent").filterNot(existing::contains) }, strings("hiddenCards").toSet(), groups.keys().asSequence().mapNotNull { key -> key.toIntOrNull()?.let { id ->
                val g = groups.getJSONObject(key); val codes = g.optJSONArray("commands") ?: JSONArray()
                id to QueryGroup(List(codes.length()) { codes.optString(it) }.filter(String::isNotBlank), g.optInt("intervalSeconds", 10).coerceIn(3, 120))
            } }.toMap(), times.keys().asSequence().mapNotNull { key -> key.toIntOrNull()?.let { it to times.optLong(key) } }.toMap(), colors.keys().asSequence().associateWith { colors.optString(it) }.filterValues { it.matches(Regex("#[0-9a-fA-F]{6}")) })
        }.getOrDefault(CommandPreferences())
    }
    private fun save(value: CommandPreferences) {
        val json = JSONObject().put("favorites", JSONArray(value.favorites.toList())).put("recent", JSONArray(value.recent)).put("order", JSONArray(value.order))
            .put("cards", JSONArray(value.cards)).put("hiddenCards", JSONArray(value.hiddenCards.toList()))
            .put("custom", JSONArray(value.custom.map { c -> JSONObject().put("id", c.id).put("category", c.category).put("name", c.command.name).put("code", c.command.code).put("target", c.target).put("requiresInput", c.command.requiresInput).put("inputHint", c.command.inputHint) }))
            .put("groups", JSONObject().apply { value.groups.forEach { (id, g) -> put(id.toString(), JSONObject().put("commands", JSONArray(g.commands)).put("intervalSeconds", g.intervalSeconds)) } })
            .put("lastQuery", JSONObject().apply { value.lastQuery.forEach { (id, time) -> put(id.toString(), time) } })
            .put("categoryColors", JSONObject(value.categoryColors))
        val context = app ?: return
        if (context.getSharedPreferences("SmsHelper_UI", Context.MODE_PRIVATE).edit().putString("commands", json.toString()).commit()) mutable.value = value
        else android.widget.Toast.makeText(context, "偏好未保存，请检查存储空间", android.widget.Toast.LENGTH_LONG).show()
    }
    fun commands(config: CarrierConfig?, preferences: CommandPreferences = mutable.value): List<UiCommand> {
        val builtin = config?.categories.orEmpty().flatMap { category -> category.commands.map { cmd ->
            UiCommand("${config?.identifier}:${category.title}:${cmd.name}:${cmd.code}", category.title, cmd, config?.targetNumber.orEmpty())
        } }
        val all = builtin + preferences.custom
        return all.sortedBy { command -> preferences.order.indexOf(command.id).takeIf { it >= 0 } ?: Int.MAX_VALUE }
    }
    fun favorite(id: String) { val p=mutable.value; save(p.copy(favorites=if(id in p.favorites) p.favorites-id else p.favorites+id)) }
    fun used(id: String) { val p=mutable.value; save(p.copy(recent=(listOf(id)+p.recent.filterNot { it==id }).take(20))) }
    fun upsert(command: UiCommand) { val p=mutable.value; val item=command.copy(id=command.id.ifBlank { UUID.randomUUID().toString() }, custom=true); save(p.copy(custom=p.custom.filterNot { it.id==item.id }+item)) }
    fun remove(id: String) { val p=mutable.value; save(p.copy(custom=p.custom.filterNot { it.id==id }, favorites=p.favorites-id, recent=p.recent-id, order=p.order-id)) }
    fun move(id: String, direction: Int, commands: List<UiCommand>) {
        val category=commands.find { it.id==id }?.category ?: return
        val peers=commands.filter { it.category==category }; val peer=peers.indexOfFirst { it.id==id }+direction
        if(peer !in peers.indices)return
        val ids=commands.map { it.id }.toMutableList();java.util.Collections.swap(ids,ids.indexOf(id),ids.indexOf(peers[peer].id))
        save(mutable.value.copy(order=mutable.value.order.filterNot(ids::contains)+ids))
    }
    fun moveCategory(category:String,direction:Int,commands:List<UiCommand>) {
        val groups=commands.groupBy { it.category };val names=groups.keys.toMutableList();val from=names.indexOf(category);val to=from+direction
        if(from<0||to !in names.indices)return
        java.util.Collections.swap(names,from,to);val ids=names.flatMap { name -> groups[name].orEmpty().map { it.id } }
        save(mutable.value.copy(order=mutable.value.order.filterNot(ids::contains)+ids))
    }
    fun renameCustomCategory(old:String,name:String) {
        if(name.isBlank()||name==old)return
        val p=mutable.value
        save(p.copy(custom=p.custom.map { if(it.category==old)it.copy(category=name)else it },categoryColors=(p.categoryColors-old).let { colors -> p.categoryColors[old]?.let { colors+(name to it) }?:colors }))
    }
    fun setGroup(subId: Int, group: QueryGroup) { save(mutable.value.copy(groups=mutable.value.groups+(subId to group))) }
    fun queried(subId: Int) { save(mutable.value.copy(lastQuery=mutable.value.lastQuery+(subId to System.currentTimeMillis()))) }
    fun hideCard(id: String) { val p=mutable.value; save(p.copy(hiddenCards=if(id in p.hiddenCards)p.hiddenCards-id else p.hiddenCards+id)) }
    fun moveCard(id: String, direction: Int) { val ids=mutable.value.cards.toMutableList(); val from=ids.indexOf(id); val to=from+direction; if(from<0||to !in ids.indices)return; java.util.Collections.swap(ids,from,to); save(mutable.value.copy(cards=ids)) }
    fun categoryColor(category: String, hex: String?) { val p=mutable.value; save(p.copy(categoryColors=if(hex.isNullOrBlank())p.categoryColors-category else p.categoryColors+(category to hex))) }
    fun reset(context: Context) { context.getSharedPreferences("SmsHelper_UI", Context.MODE_PRIVATE).edit().clear().commit(); init(context) }
}
