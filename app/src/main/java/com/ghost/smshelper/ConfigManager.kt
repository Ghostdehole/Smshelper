@file:Suppress("DEPRECATION")
package com.ghost.smshelper

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

object ConfigManager {
    private val lock = Any()
    private var catalog = ConfigCatalog()
    private var initialized = false
    private var migrationBackupFailed = false
    private var activeIdentifier: String? = null
    private var activeSubId: Int? = null
    private val mutableProfiles = MutableStateFlow<List<ConfigProfile>>(emptyList())
    private val mutableSelections = MutableStateFlow<Map<String, CarrierSelection>>(emptyMap())
    private val mutableSubscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    val profilesFlow: StateFlow<List<ConfigProfile>> = mutableProfiles.asStateFlow()
    val selectionsFlow: StateFlow<Map<String, CarrierSelection>> = mutableSelections.asStateFlow()
    val subscriptionsFlow: StateFlow<List<Subscription>> = mutableSubscriptions.asStateFlow()
    val activeConfig = MutableStateFlow<CarrierConfig?>(null)
    private val mutableRevisions = MutableStateFlow<List<RuleRevision>>(emptyList())
    val revisionsFlow: StateFlow<List<RuleRevision>> = mutableRevisions.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = mutableError.asStateFlow()

    fun init(context: Context) = synchronized(lock) {
        if (initialized) return@synchronized
        SimPreferences.init(context)
        loadFromPrefs(context)
        initialized = true
        // Refresh old built-in profiles in place: their IDs and user selections stay valid.
        runCatching { installBuiltIns(readBuiltIns(context), addMissing = catalog.profiles.isEmpty()) }
            .onFailure { mutableError.value = "内置规则读取失败" }
        publish()
        if (!saveToPrefs(context)) mutableError.value = "规则保存失败"
    }

    fun <T> withBackupLock(block: () -> T): T = synchronized(lock) { block() }

    fun getAllProfiles(): List<ConfigProfile> = synchronized(lock) { catalog.profiles.values.toList() }
    fun getSelections(): Map<String, CarrierSelection> = synchronized(lock) { catalog.selections.toMap() }
    fun getAllSubscriptions(): List<Subscription> = synchronized(lock) { catalog.subscriptions.values.map { it.copy() } }

    fun getCurrentProfileJson(): String = synchronized(lock) {
        val id = activeIdentifier ?: return@synchronized ""
        selectedProfileId(id, activeSubId)?.let { catalog.profiles[it]?.jsonContent } ?: ""
    }

    fun importProfile(context: Context, jsonStr: String, name: String, source: String): Boolean {
        val carriers = validated(jsonStr) ?: return false
        if (name.isBlank() || name.length > 256 || source.length > 2048) return fail("规则名称或来源无效")
        return transaction(context) {
            val id = UUID.randomUUID().toString()
            catalog.replaceProfile(ConfigProfile(id, name, source, jsonStr, carriers))
            true
        }
    }

    fun deleteProfile(context: Context, profileId: String): Boolean = transaction(context) {
        catalog.removeProfile(profileId)
    }

    fun selectProfileForCarrier(context: Context, identifier: String, profileId: String): Boolean = transaction(context) {
        catalog.select(identifier, profileId)
    }

    fun switchCarrier(mnc: String, carrierName: String, mcc: String? = null) = synchronized(lock) {
        activeSubId = null
        activeIdentifier = CarrierIdentity.identifier(mcc, mnc, carrierName)
        activeConfig.value = activeIdentifier?.let { catalog.selected(it) }
    }

    fun switchSim(sim: SimInfo?) = synchronized(lock) {
        activeSubId = sim?.subId
        activeIdentifier = sim?.let { CarrierIdentity.identifier(it.mcc, it.mnc, it.carrierName) }
        publish()
    }

    fun configForSim(sim: SimInfo): CarrierConfig? = synchronized(lock) {
        val identifier = CarrierIdentity.identifier(sim.mcc, sim.mnc, sim.carrierName) ?: return@synchronized null
        selectedConfig(identifier, sim.subId)
    }

    fun refreshSelection() = synchronized(lock) { publish() }

    fun selectProfileForSim(context: Context, subId: Int, identifier: String, profileId: String): Boolean {
        init(context)
        val available = synchronized(lock) { catalog.profiles[profileId]?.carriers?.any { it.identifier == identifier } == true }
        if (subId < 0 || !available) return fail("该规则不包含当前运营商")
        return SimPreferences.update(context, SimPreferences.get(subId).copy(profileId = profileId, identifier = identifier))
    }

    private fun selectedProfileId(identifier: String, subId: Int?): String? {
        val choice = subId?.let { SimPreferences.get(it) }
        val selected = choice?.profileId?.let { catalog.profiles[it] }
        return if (choice?.identifier == identifier && selected?.carriers?.any { it.identifier == identifier } == true) selected.id
        else catalog.selections[identifier]?.profileId
    }

    private fun selectedConfig(identifier: String, subId: Int?): CarrierConfig? =
        selectedProfileId(identifier, subId)?.let { catalog.profiles[it]?.carriers?.find { carrier -> carrier.identifier == identifier } }

    /** Built-in and subscribed rules are always edited as personal copies. */
    fun saveEditedProfile(context: Context, profileId: String?, name: String, json: String): Boolean {
        val carriers = validated(json) ?: return false
        if (name.isBlank() || name.length > 256) return fail("请输入 1 至 256 字的规则名称")
        return transaction(context) {
            val previous = profileId?.let { catalog.profiles[it] }
            val personal = previous?.source == "个人编辑"
            if (personal && previous != null) archive(previous, "编辑前")
            val id = if (personal) previous!!.id else UUID.randomUUID().toString()
            catalog.replaceProfile(ConfigProfile(id, name.trim(), "个人编辑", json, carriers))
            true
        }
    }

    fun rollbackProfile(context: Context, revisionId: String): Boolean = transaction(context) {
        val revision = catalog.revisions.find { it.id == revisionId } ?: return@transaction false
        val current = catalog.profiles[revision.profileId] ?: return@transaction false
        archive(current, "回滚前")
        catalog.replaceProfile(current.copy(jsonContent = revision.json, carriers = ConfigValidation.parse(revision.json)))
        true
    }

    private fun archive(profile: ConfigProfile, reason: String) {
        if (catalog.revisions.lastOrNull { it.profileId == profile.id }?.json == profile.jsonContent) return
        catalog.revisions += RuleRevision(UUID.randomUUID().toString(), profile.id, profile.name, profile.jsonContent, System.currentTimeMillis(), reason)
        while (catalog.revisions.count { it.profileId == profile.id } > 10) catalog.revisions.removeAt(catalog.revisions.indexOfFirst { it.profileId == profile.id })
        while (catalog.revisions.sumOf { it.json.length.toLong() } > 4_000_000L) catalog.revisions.removeAt(0)
    }

    /** Receiving/parsing is independent of whichever SIM is displayed on screen. */
    fun resolveForSubscription(context: Context, subId: Int?, sender: String?): CarrierConfig? {
        init(context)
        if (subId != null && subId < 0) return null
        val identifier = if (subId != null && subId >= 0) {
            try {
                val manager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                    ?: return null
                val sim = manager.getActiveSubscriptionInfo(subId) ?: return null
                val mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) sim.mccString.orEmpty() else sim.mcc.toString()
                val mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) sim.mncString.orEmpty() else sim.mnc.toString().padStart(2, '0')
                CarrierIdentity.identifier(mcc, mnc, sim.carrierName?.toString().orEmpty()) ?: return null
            } catch (_: SecurityException) { return null }
            catch (_: RuntimeException) { return null }
        } else null
        return synchronized(lock) {
            if (identifier != null) {
                selectedConfig(identifier, subId)?.takeIf { sender == null || CarrierIdentity.matches(sender, it.targetNumber) }
            } else {
                // Legacy rows have no subscription ID. Ambiguous senders must not contaminate an account.
                if (sender == null) return@synchronized null
                catalog.selections.values.map { it.config }
                    .filter { CarrierIdentity.matches(sender, it.targetNumber) }.singleOrNull()
            }
        }
    }

    fun addOrUpdateSubscription(context: Context, url: String, name: String, intervalMinutes: Int): Boolean {
        val normalizedUrl = ConfigValidation.subscriptionUrl(url) ?: return fail("请输入有效的 HTTP(S) 订阅链接")
        if (name.isBlank() || name.length > 256 || intervalMinutes !in 0..525600) return fail("订阅名称或同步间隔无效（0 表示手动更新）")
        return transaction(context) {
            val old = catalog.subscriptions[normalizedUrl]
            catalog.subscriptions[normalizedUrl] = Subscription(normalizedUrl, name.trim(), intervalMinutes, old?.lastUpdateTime ?: 0L, old?.profileId)
            old?.profileId?.let { id -> catalog.profiles[id]?.let { catalog.replaceProfile(it.copy(name = name.trim())) } }
            true
        }
    }

    fun removeSubscription(context: Context, url: String): Boolean = transaction(context) {
        val key = ConfigValidation.subscriptionUrl(url) ?: url
        val removed = catalog.subscriptions.remove(key) ?: return@transaction false
        removed.profileId?.let { id -> catalog.profiles[id]?.let { catalog.replaceProfile(it.copy(source = "已取消订阅: $key")) } }
        true
    }

    suspend fun previewSubscription(context: Context, url: String): SubscriptionPreview? {
        init(context)
        val key = ConfigValidation.subscriptionUrl(url) ?: return null.also { fail("无效的订阅链接") }
        val snapshot = synchronized(lock) {
            val sub = catalog.subscriptions[key] ?: return@synchronized null
            sub.copy() to sub.profileId?.let { catalog.profiles[it]?.jsonContent }
        } ?: return null.also { fail("订阅不存在") }
        return try {
            val json = withContext(Dispatchers.IO) { downloadProfile(key) }
            SubscriptionPreview(key, snapshot.first.name, snapshot.second, json, ConfigValidation.parse(json), snapshot.first)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { fail("订阅更新失败，请检查链接、网络和规则格式；现有规则已保留"); null }
    }

    fun applySubscriptionPreview(context: Context, preview: SubscriptionPreview): Boolean = transaction(context) {
        val current = catalog.subscriptions[preview.url] ?: return@transaction false
        if (current != preview.original || current.profileId?.let { catalog.profiles[it]?.jsonContent } != preview.previousJson) return@transaction false
        current.profileId?.let { catalog.profiles[it] }?.takeIf { it.jsonContent != preview.json }?.let { archive(it, "订阅更新前") }
        catalog.applySubscription(preview.url, preview.json, ConfigValidation.parse(preview.json), System.currentTimeMillis())
    }

    suspend fun updateSubscription(context: Context, url: String): Boolean {
        val preview = previewSubscription(context, url) ?: return false
        return applySubscriptionPreview(context, preview)
    }

    suspend fun checkSubscriptions(context: Context) {
        init(context)
        val now = System.currentTimeMillis()
        val due = synchronized(lock) {
            catalog.subscriptions.values.filter {
                it.intervalMinutes > 0 && (it.lastUpdateTime <= 0L || now < it.lastUpdateTime ||
                    now - it.lastUpdateTime >= TimeUnit.MINUTES.toMillis(it.intervalMinutes.toLong()))
            }.map { it.url }
        }
        due.forEach { updateSubscription(context, it) }
    }

    fun importDefaultProfiles(context: Context): Boolean {
        val builtIns = try { readBuiltIns(context) } catch (_: Exception) { return fail("内置规则读取失败") }
        return transaction(context) { installBuiltIns(builtIns); true }
    }

    fun reset(context: Context): Boolean {
        val builtIns = try { readBuiltIns(context) } catch (_: Exception) { return fail("内置规则读取失败") }
        return synchronized(lock) {
            val previous = catalog
            val previousActive = activeIdentifier
            val previousSubId = activeSubId
            val previousBackupFailure = migrationBackupFailed
            catalog = ConfigCatalog()
            activeIdentifier = null
            activeSubId = null
            migrationBackupFailed = false
            installBuiltIns(builtIns)
            if (!saveToPrefs(context, clear = true)) {
                catalog = previous
                activeIdentifier = previousActive
                activeSubId = previousSubId
                migrationBackupFailed = previousBackupFailure
                fail("规则重置失败")
            } else {
                initialized = true
                mutableError.value = null
                publish()
                true
            }
        }
    }

    private fun validated(json: String): List<CarrierConfig>? = try {
        ConfigValidation.parse(json)
    } catch (_: Exception) { fail("规则格式、正则表达式或捕获组无效"); null }

    private fun fail(message: String): Boolean { mutableError.value = message; return false }

    private fun transaction(context: Context, mutation: () -> Boolean): Boolean {
        init(context)
        return synchronized(lock) {
            val previous = catalog.copy()
            try {
                if (!mutation()) { catalog = previous; return@synchronized fail("规则操作未完成，目标可能已变更") }
                validateCatalogLimits(catalog)
                if (!saveToPrefs(context)) { catalog = previous; return@synchronized fail("规则保存失败") }
                mutableError.value = null
                publish()
                true
            } catch (error: Exception) {
                catalog = previous
                fail(error.message?.take(180) ?: "规则操作失败")
            }
        }
    }

    private fun publish() {
        mutableProfiles.value = catalog.profiles.values.toList()
        mutableSelections.value = catalog.selections.toMap()
        mutableSubscriptions.value = catalog.subscriptions.values.map { it.copy() }
        activeConfig.value = activeIdentifier?.let { selectedConfig(it, activeSubId) }
        mutableRevisions.value = catalog.revisions.toList()
    }

    private data class BundledProfile(val name: String, val json: String, val carriers: List<CarrierConfig>)

    private fun readBuiltIns(context: Context): List<BundledProfile> =
        listOf(R.raw.default_config_cmcc to "内置移动规则", R.raw.default_config_cucc to "内置联通规则",
            R.raw.default_config_ctcc to "内置电信规则", R.raw.default_config_cbn to "内置广电规则").map { (resId, name) ->
            val json = context.resources.openRawResource(resId).bufferedReader(Charsets.UTF_8).use { it.readText() }
            BundledProfile(name, json, ConfigValidation.parse(json))
        }

    private fun installBuiltIns(builtIns: List<BundledProfile>, addMissing: Boolean = true) {
        builtIns.forEach { (name, json, carriers) ->
            val ids = carriers.map { it.identifier }.toSet()
            val existing = catalog.profiles.values.firstOrNull { it.source == "内置" && it.carriers.map { carrier -> carrier.identifier }.toSet() == ids }
            if (existing == null && !addMissing) return@forEach
            val id = existing?.id ?: UUID.randomUUID().toString()
            catalog.replaceProfile(ConfigProfile(id, name, "内置", json, carriers))
        }
    }

    private fun saveToPrefs(context: Context, clear: Boolean = false): Boolean = if (migrationBackupFailed) false else try {
        val profiles = JSONArray()
        catalog.profiles.values.forEach { profiles.put(JSONObject().apply {
            put("id", it.id); put("name", it.name); put("source", it.source); put("jsonContent", it.jsonContent)
        }) }
        val selections = JSONObject()
        catalog.selections.forEach { (id, selection) -> selections.put(id, JSONObject().apply {
            put("identifier", selection.identifier); put("profileId", selection.profileId)
        }) }
        val subscriptions = JSONArray()
        catalog.subscriptions.values.forEach { subscriptions.put(JSONObject().apply {
            put("url", it.url); put("name", it.name); put("intervalMinutes", it.intervalMinutes)
            put("lastUpdateTime", it.lastUpdateTime); put("profileId", it.profileId ?: "")
        }) }
        val editor = context.getSharedPreferences("SmsHelper_Config", Context.MODE_PRIVATE).edit()
        if (clear) editor.clear()
        editor.putString("profiles", profiles.toString()).putString("selections", selections.toString())
            .putString("subs", subscriptions.toString()).putString("revisions", revisionsJson(catalog.revisions).toString()).commit()
    } catch (_: Exception) { false }

    private fun loadFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences("SmsHelper_Config", Context.MODE_PRIVATE)
        val rawProfiles = prefs.getString("profiles", "[]") ?: "[]"
        val rawSelections = prefs.getString("selections", "{}") ?: "{}"
        val rawSubscriptions = prefs.getString("subs", "[]") ?: "[]"
        // Preserve the exact pre-migration payload before repairing links or refreshing defaults.
        if (!prefs.contains("migration_backup_profiles") && (rawProfiles != "[]" || rawSubscriptions != "[]")) {
            if (!prefs.edit().putString("migration_backup_profiles", rawProfiles)
                    .putString("migration_backup_selections", rawSelections)
                    .putString("migration_backup_subs", rawSubscriptions).commit()) {
                migrationBackupFailed = true
                mutableError.value = "旧规则备份失败"
            }
        }
        val loaded = ConfigCatalog()
        var skipped = 0
        val profiles = runCatching { JSONArray(rawProfiles) }.getOrElse { skipped++; JSONArray() }
        for (index in 0 until profiles.length()) runCatching {
            val obj = profiles.getJSONObject(index)
            val json = obj.getString("jsonContent")
            val profile = ConfigProfile(obj.getString("id"), obj.getString("name"), obj.getString("source"), json, ConfigValidation.parse(json))
            loaded.replaceProfile(profile)
        }.onFailure { skipped++ }
        val selections = runCatching { JSONObject(rawSelections) }.getOrElse { skipped++; JSONObject() }
        selections.keys().forEach { id -> runCatching { loaded.select(id, selections.getJSONObject(id).getString("profileId")) }.onFailure { skipped++ } }
        val subscriptions = runCatching { JSONArray(rawSubscriptions) }.getOrElse { skipped++; JSONArray() }
        for (index in 0 until subscriptions.length()) runCatching {
            val obj = subscriptions.getJSONObject(index)
            val url = ConfigValidation.subscriptionUrl(obj.getString("url")) ?: error("无效的旧订阅链接")
            val interval = obj.optInt("intervalMinutes", 60).coerceIn(0, 525600)
            val profileId = obj.optString("profileId", "").takeIf { it.isNotBlank() && it != "null" }
            loaded.subscriptions[url] = Subscription(url, obj.getString("name"), interval, obj.optLong("lastUpdateTime", 0L).coerceAtLeast(0L), profileId)
        }.onFailure { skipped++ }
        loaded.recoverSubscriptionLinks()
        runCatching { readRevisions(JSONArray(prefs.getString("revisions", "[]"))).filter { loaded.profiles.containsKey(it.profileId) } }
            .onSuccess { loaded.revisions.addAll(it) }.onFailure { skipped++ }
        catalog = loaded
        if (skipped > 0 && !migrationBackupFailed) mutableError.value = "$skipped 条旧规则或订阅无法加载，原始数据已保留在迁移备份中"
    }

    fun exportBackup(context: Context): String {
        init(context)
        return synchronized(lock) { encodeCatalog(catalog).toString() }
    }

    fun validateBackup(json: String) { decodeCatalog(json) }

    fun restoreBackup(context: Context, json: String, merge: Boolean, profileIdMapping: MutableMap<String, String>? = null): Boolean {
        val restored = try { decodeCatalog(json) } catch (_: Exception) { return fail("备份中的规则数据无效") }
        val idMap = mutableMapOf<String, String>()
        val success = transaction(context) {
            if (!merge) {
                catalog = restored
                restored.profiles.keys.forEach { idMap[it] = it }
            } else {
                val originallySelected = catalog.selections.keys.toSet()
                restored.profiles.values.forEach { profile ->
                    val existing = catalog.profiles[profile.id]
                    val id = if (existing != null && existing.jsonContent != profile.jsonContent) UUID.randomUUID().toString() else profile.id
                    idMap[profile.id] = id
                    if (id !in catalog.profiles) catalog.replaceProfile(profile.copy(id = id))
                }
                restored.selections.forEach { (identifier, selection) ->
                    if (identifier !in originallySelected) catalog.select(identifier, idMap[selection.profileId] ?: selection.profileId)
                }
                restored.subscriptions.forEach { (url, sub) ->
                    catalog.subscriptions.putIfAbsent(url, sub.copy(profileId = sub.profileId?.let { idMap[it] ?: it }))
                }
                restored.revisions.forEach { revision ->
                    if (catalog.revisions.none { it.id == revision.id }) catalog.revisions.add(revision.copy(profileId = idMap[revision.profileId] ?: revision.profileId))
                }
            }
            true
        }
        if (success) profileIdMapping?.putAll(idMap)
        return success
    }

    private fun validateCatalogLimits(value: ConfigCatalog) {
        require(value.profiles.size <= 256 && value.subscriptions.size <= 256) { "规则或订阅数量超过 256，请先清理后再导入" }
        require(value.revisions.size <= 2560 && value.revisions.sumOf { it.json.length.toLong() } <= 8_000_000L) { "历史版本超过备份容量，请先清理规则" }
        require(encodeCatalog(value).toString().length <= 24_000_000) { "规则总量超过备份容量，请先清理后再导入" }
    }

    private fun revisionsJson(revisions: List<RuleRevision>): JSONArray = JSONArray().apply { revisions.forEach { revision -> put(JSONObject().apply {
        put("id", revision.id); put("profileId", revision.profileId); put("name", revision.name); put("json", revision.json)
        put("createdAt", revision.createdAt); put("reason", revision.reason)
    }) } }

    private fun readRevisions(array: JSONArray): List<RuleRevision> {
        require(array.length() <= 2560)
        var total = 0L
        return List(array.length()) { index ->
            val obj = array.getJSONObject(index)
            val json = obj.getString("json")
            total += json.length
            require(total <= 8_000_000L)
            ConfigValidation.parse(json)
            RuleRevision(obj.getString("id"), obj.getString("profileId"), obj.getString("name"), json,
                obj.getLong("createdAt").coerceAtLeast(0L), obj.optString("reason", "历史版本"))
        }
    }

    private fun encodeCatalog(value: ConfigCatalog): JSONObject = JSONObject().apply {
        put("version", 1)
        put("profiles", JSONArray().apply { value.profiles.values.forEach { profile -> put(JSONObject().apply {
            put("id", profile.id); put("name", profile.name); put("source", profile.source); put("jsonContent", profile.jsonContent)
        }) } })
        put("selections", JSONObject().apply { value.selections.forEach { (identifier, selection) -> put(identifier, selection.profileId) } })
        put("subscriptions", JSONArray().apply { value.subscriptions.values.forEach { sub -> put(JSONObject().apply {
            put("url", sub.url); put("name", sub.name); put("intervalMinutes", sub.intervalMinutes); put("lastUpdateTime", sub.lastUpdateTime); put("profileId", sub.profileId ?: "")
        }) } })
        put("revisions", revisionsJson(value.revisions))
    }

    private fun decodeCatalog(json: String): ConfigCatalog {
        require(json.length <= 24_000_000) { "规则备份过大" }
        val root = JSONObject(json)
        require(root.optInt("version", 1) == 1) { "不支持的规则备份版本" }
        val result = ConfigCatalog()
        val profiles = root.getJSONArray("profiles")
        require(profiles.length() <= 256)
        for (i in 0 until profiles.length()) {
            val obj = profiles.getJSONObject(i)
            val id = obj.getString("id").also { require(it.length in 1..128 && it !in result.profiles) }
            val name = obj.getString("name").also { require(it.isNotBlank() && it.length <= 256) }
            val source = obj.getString("source").also { require(it.length <= 2100) }
            val content = obj.getString("jsonContent")
            result.replaceProfile(ConfigProfile(id, name, source, content, ConfigValidation.parse(content)))
        }
        val selections = root.getJSONObject("selections")
        selections.keys().forEach { identifier -> require(result.select(identifier, selections.getString(identifier))) }
        val subscriptions = root.getJSONArray("subscriptions")
        require(subscriptions.length() <= 256)
        for (i in 0 until subscriptions.length()) {
            val obj = subscriptions.getJSONObject(i)
            val url = ConfigValidation.subscriptionUrl(obj.getString("url")) ?: error("无效订阅链接")
            require(url !in result.subscriptions)
            val name = obj.getString("name").also { require(it.isNotBlank() && it.length <= 256) }
            val interval = obj.getInt("intervalMinutes").also { require(it in 0..525600) }
            val profile = obj.optString("profileId").takeIf { it.isNotBlank() && it != "null" }.also { require(it == null || it in result.profiles) }
            result.subscriptions[url] = Subscription(url, name, interval, obj.optLong("lastUpdateTime", 0).coerceAtLeast(0), profile)
        }
        val revisions = readRevisions(root.optJSONArray("revisions") ?: JSONArray())
        require(revisions.map { it.id }.toSet().size == revisions.size)
        require(revisions.all { it.profileId in result.profiles })
        result.revisions.addAll(revisions)
        return result
    }

    private suspend fun downloadProfile(initialUrl: String): String {
        var current = initialUrl
        val started = System.nanoTime()
        repeat(6) { redirectCount ->
            coroutineContext.ensureActive()
            require(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 45) { "订阅下载超时" }
            val connection = URL(current).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json, text/plain;q=0.9")
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    require(redirectCount < 5) { "重定向过多" }
                    val location = connection.getHeaderField("Location") ?: error("无效重定向")
                    val next = ConfigValidation.subscriptionUrl(URL(URL(current), location).toString()) ?: error("不支持的重定向")
                    require(!current.startsWith("https:", ignoreCase = true) || next.startsWith("https:", ignoreCase = true)) { "不允许 HTTPS 降级为 HTTP" }
                    current = next
                } else {
                    require(status in 200..299) { "HTTP $status" }
                    require(connection.contentLengthLong <= ConfigValidation.MAX_PROFILE_BYTES) { "规则文件过大" }
                    val bytes = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            coroutineContext.ensureActive()
                            require(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 45) { "订阅下载超时" }
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(bytes.size() + count <= ConfigValidation.MAX_PROFILE_BYTES) { "规则文件过大" }
                            bytes.write(buffer, 0, count)
                        }
                    }
                    return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
                }
            } finally { connection.disconnect() }
        }
        error("重定向过多")
    }
}
