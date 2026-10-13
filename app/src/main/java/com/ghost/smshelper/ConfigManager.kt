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
    private val mutableProfiles = MutableStateFlow<List<ConfigProfile>>(emptyList())
    private val mutableSelections = MutableStateFlow<Map<String, CarrierSelection>>(emptyMap())
    private val mutableSubscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    val profilesFlow: StateFlow<List<ConfigProfile>> = mutableProfiles.asStateFlow()
    val selectionsFlow: StateFlow<Map<String, CarrierSelection>> = mutableSelections.asStateFlow()
    val subscriptionsFlow: StateFlow<List<Subscription>> = mutableSubscriptions.asStateFlow()
    val activeConfig = MutableStateFlow<CarrierConfig?>(null)
    private val mutableError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = mutableError.asStateFlow()

    fun init(context: Context) = synchronized(lock) {
        if (initialized) return@synchronized
        loadFromPrefs(context)
        initialized = true
        // Refresh old built-in profiles in place: their IDs and user selections stay valid.
        runCatching { installBuiltIns(readBuiltIns(context), addMissing = catalog.profiles.isEmpty()) }
            .onFailure { mutableError.value = "内置规则读取失败" }
        publish()
        if (!saveToPrefs(context)) mutableError.value = "规则保存失败"
    }

    fun getAllProfiles(): List<ConfigProfile> = synchronized(lock) { catalog.profiles.values.toList() }
    fun getSelections(): Map<String, CarrierSelection> = synchronized(lock) { catalog.selections.toMap() }
    fun getAllSubscriptions(): List<Subscription> = synchronized(lock) { catalog.subscriptions.values.map { it.copy() } }

    fun getCurrentProfileJson(): String = synchronized(lock) {
        val id = activeIdentifier ?: return@synchronized ""
        catalog.selections[id]?.let { catalog.profiles[it.profileId]?.jsonContent } ?: ""
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
        activeIdentifier = CarrierIdentity.identifier(mcc, mnc, carrierName)
        activeConfig.value = activeIdentifier?.let { catalog.selected(it) }
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
                catalog.selected(identifier)?.takeIf { sender == null || CarrierIdentity.matches(sender, it.targetNumber) }
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

    suspend fun updateSubscription(context: Context, url: String): Boolean {
        init(context)
        val key = ConfigValidation.subscriptionUrl(url) ?: return fail("无效的订阅链接")
        val original = synchronized(lock) { catalog.subscriptions[key] } ?: return fail("订阅不存在")
        return try {
            val json = withContext(Dispatchers.IO) { downloadProfile(key) }
            val carriers = ConfigValidation.parse(json)
            transaction(context) {
                // Deletion, reset, editing, or a newer completed update invalidate this response.
                if (catalog.subscriptions[key] !== original) return@transaction false
                catalog.applySubscription(key, json, carriers, System.currentTimeMillis())
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { fail("订阅更新失败，请检查链接、网络和规则格式") }
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
            val previousBackupFailure = migrationBackupFailed
            catalog = ConfigCatalog()
            activeIdentifier = null
            migrationBackupFailed = false
            installBuiltIns(builtIns)
            if (!saveToPrefs(context, clear = true)) {
                catalog = previous
                activeIdentifier = previousActive
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
                if (!saveToPrefs(context)) { catalog = previous; return@synchronized fail("规则保存失败") }
                mutableError.value = null
                publish()
                true
            } catch (_: Exception) {
                catalog = previous
                fail("规则操作失败")
            }
        }
    }

    private fun publish() {
        mutableProfiles.value = catalog.profiles.values.toList()
        mutableSelections.value = catalog.selections.toMap()
        mutableSubscriptions.value = catalog.subscriptions.values.map { it.copy() }
        activeConfig.value = activeIdentifier?.let { catalog.selected(it) }
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
            .putString("subs", subscriptions.toString()).commit()
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
        catalog = loaded
        if (skipped > 0 && !migrationBackupFailed) mutableError.value = "$skipped 条旧规则或订阅无法加载，原始数据已保留在迁移备份中"
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
