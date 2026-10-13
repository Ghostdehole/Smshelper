package com.ghost.smshelper

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A transport receipt is separate from an incoming, heuristically associated reply. */
enum class QueryStatus { SUBMITTED, SENT, WAITING, PARTIAL, REPLIED, FAILED, TIMEOUT, COMPOSER_OPENED }

data class QueryTask(
    val id: String, val subId: Int, val target: String, val command: String,
    val createdAt: Long, val status: QueryStatus = QueryStatus.SUBMITTED,
    val updatedAt: Long = createdAt, val sentAt: Long? = null,
    val matchedFields: Set<String> = emptySet(), val replyMessageIds: List<String> = emptyList(),
    val error: String? = null, val partCount: Int = 1,
    val receipts: Map<Int, Boolean> = emptyMap(), val retryOf: String? = null,
    val userConfirmed: Boolean = false
)

internal object QueryState {
    const val REPLY_WINDOW = 20 * 60 * 1000L
    const val SUBMIT_WINDOW = 2 * 60 * 1000L
    const val DUPLICATE_WINDOW = 10_000L
    val awaiting = setOf(QueryStatus.SUBMITTED, QueryStatus.SENT, QueryStatus.WAITING, QueryStatus.PARTIAL)

    fun expire(task: QueryTask, now: Long): QueryTask {
        val limit = if (task.status == QueryStatus.SUBMITTED) SUBMIT_WINDOW else REPLY_WINDOW
        return if (task.status in awaiting && now - task.createdAt > limit) task.copy(
            status = QueryStatus.TIMEOUT, updatedAt = now,
            error = if (task.sentAt == null) "未收到完整发送回执，请检查系统短信记录后再决定是否重试"
                else if (task.replyMessageIds.isEmpty()) "等待回复超时" else "已有部分数据更新，其余结果尚未确认"
        ) else task
    }

    fun duplicate(tasks: List<QueryTask>, subId: Int, target: String, command: String, now: Long): Boolean =
        tasks.any { it.subId == subId && it.target == target && it.command == command &&
            now - it.createdAt in 0..DUPLICATE_WINDOW && it.status != QueryStatus.FAILED }

    fun receipt(task: QueryTask, part: Int, total: Int, success: Boolean, now: Long): Pair<QueryTask, SendOutcome?> {
        if (total != task.partCount || part !in 0 until total || part in task.receipts) return task to null
        val receipts = task.receipts + (part to success)
        if (task.receipts.values.any { !it }) return task.copy(receipts = receipts) to null
        if (!success) return task.copy(receipts = receipts, status = QueryStatus.FAILED, updatedAt = now,
            error = "短信发送失败；若为长短信，部分内容可能已经发送") to SendOutcome.FAILED
        if (receipts.size != total) return task.copy(receipts = receipts, updatedAt = now) to null
        val status = when (task.status) {
            QueryStatus.PARTIAL, QueryStatus.REPLIED -> task.status
            else -> QueryStatus.WAITING
        }
        return task.copy(receipts = receipts, status = status, sentAt = now, updatedAt = now, error = null) to SendOutcome.SENT
    }

    /** Ambiguous parallel queries stay unassociated rather than selecting an arbitrary request. */
    fun associate(tasks: List<QueryTask>, message: SmsItem, fields: Set<String>): List<QueryTask> {
        if (message.subId == null || fields.isEmpty()) return tasks
        fun number(value: String): String = value.replace(" ", "").removePrefix("+86")
        val candidates = tasks.filter { it.subId == message.subId && number(it.target) == number(message.sender) &&
            it.status in awaiting && message.timestamp - it.createdAt in -5_000L..REPLY_WINDOW &&
            message.id !in it.replyMessageIds }
        val task = candidates.singleOrNull() ?: return tasks
        return tasks.map { if (it.id != task.id) it else it.copy(status = QueryStatus.PARTIAL,
            matchedFields = it.matchedFields + fields, replyMessageIds = it.replyMessageIds + message.id,
            updatedAt = maxOf(it.updatedAt, message.timestamp), error = null) }
    }
}

internal object QueryCodec {
    fun encode(tasks: List<QueryTask>): String = JSONObject().put("version", 1).put("tasks", JSONArray().apply {
        tasks.forEach { task -> put(JSONObject().apply {
            put("id", task.id); put("subId", task.subId); put("target", task.target); put("command", task.command)
            put("createdAt", task.createdAt); put("status", task.status.name); put("updatedAt", task.updatedAt)
            put("sentAt", task.sentAt ?: JSONObject.NULL); put("fields", JSONArray(task.matchedFields.toList()))
            put("replies", JSONArray(task.replyMessageIds)); put("error", task.error ?: JSONObject.NULL)
            put("partCount", task.partCount); put("retryOf", task.retryOf ?: JSONObject.NULL)
            put("userConfirmed", task.userConfirmed)
            put("receipts", JSONObject().apply { task.receipts.forEach { (part, result) -> put(part.toString(), result) } })
        }) }
    }).toString()

    fun decode(json: String): List<QueryTask> {
        val root = JSONObject(json)
        require(root.getInt("version") == 1) { "不支持的查询记录版本" }
        val entries = root.getJSONArray("tasks")
        return List(entries.length()) { i ->
            val obj = entries.getJSONObject(i)
            val fields = obj.getJSONArray("fields")
            val replies = obj.getJSONArray("replies")
            val receipts = obj.getJSONObject("receipts")
            QueryTask(id = obj.getString("id"), subId = obj.getInt("subId"), target = obj.getString("target"),
                command = obj.getString("command"), createdAt = obj.getLong("createdAt"),
                status = QueryStatus.valueOf(obj.getString("status")), updatedAt = obj.getLong("updatedAt"),
                sentAt = if (obj.isNull("sentAt")) null else obj.getLong("sentAt"),
                matchedFields = List(fields.length()) { fields.getString(it) }.toSet(),
                replyMessageIds = List(replies.length()) { replies.getString(it) },
                error = if (obj.isNull("error")) null else obj.getString("error"),
                partCount = obj.getInt("partCount").also { require(it in 1..1000) },
                receipts = receipts.keys().asSequence().associate { it.toInt() to receipts.getBoolean(it) },
                retryOf = if (obj.isNull("retryOf")) null else obj.getString("retryOf"),
                userConfirmed = obj.optBoolean("userConfirmed", false))
        }.also { require(it.map(QueryTask::id).toSet().size == it.size) { "查询记录 ID 重复" } }
    }
}

/** A bounded, encrypted journal also persists multipart receipts across process recreation. */
object QueryRepository {
    private const val PREFS = "SmsHelper_Queries"
    private val lock = Any()
    private var initialized = false
    private var blocked = false
    private val mutableTasks = MutableStateFlow<List<QueryTask>>(emptyList())
    private val mutableError = MutableStateFlow<String?>(null)
    val tasks: StateFlow<List<QueryTask>> = mutableTasks.asStateFlow()
    val storageError: StateFlow<String?> = mutableError.asStateFlow()

    fun init(context: Context): Unit = synchronized(lock) {
        if (initialized) return@synchronized
        initialized = true
        try {
            val payload = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("state_enc", null)
            mutableTasks.value = payload?.let { QueryCodec.decode(CryptoUtil.decrypt(it)) }.orEmpty()
        } catch (_: Exception) {
            blocked = true
            mutableError.value = "无法读取查询记录，原有数据已保留"
        }
        refreshTimeouts(context)
    }

    internal fun begin(context: Context, subId: Int, target: String, command: String, retryOf: String? = null,
                       composer: Boolean = false): QueryTask? = synchronized(lock) {
        init(context)
        refreshTimeouts(context)
        val now = System.currentTimeMillis()
        if (blocked || QueryState.duplicate(mutableTasks.value, subId, target, command, now)) return@synchronized null
        val task = QueryTask(UUID.randomUUID().toString(), subId, target, command, now,
            status = if (composer) QueryStatus.COMPOSER_OPENED else QueryStatus.SUBMITTED, retryOf = retryOf)
        if (saveLocked(context, (listOf(task) + mutableTasks.value).take(300))) task else null
    }

    internal fun setPartCount(context: Context, id: String, count: Int): Boolean = update(context, id) {
        require(count in 1..1000) { "短信内容过长" }
        it.copy(partCount = count)
    }

    internal fun failed(context: Context, id: String, reason: String) = update(context, id) {
        it.copy(status = QueryStatus.FAILED, error = reason, updatedAt = System.currentTimeMillis())
    }

    internal fun recordReceipt(context: Context, id: String, part: Int, total: Int, success: Boolean): SendOutcome? = synchronized(lock) {
        init(context)
        val task = mutableTasks.value.firstOrNull { it.id == id } ?: return@synchronized null
        val (updated, outcome) = QueryState.receipt(task, part, total, success, System.currentTimeMillis())
        if (updated != task) saveLocked(context, mutableTasks.value.map { if (it.id == id) updated else it })
        outcome
    }

    fun onIncoming(context: Context, message: SmsItem, fields: Set<String>) = synchronized(lock) {
        init(context)
        refreshTimeouts(context)
        val updated = QueryState.associate(mutableTasks.value, message, fields)
        if (updated != mutableTasks.value) saveLocked(context, updated)
    }

    fun confirmReply(context: Context, id: String): Boolean = synchronized(lock) {
        init(context)
        val task = mutableTasks.value.firstOrNull { it.id == id } ?: return@synchronized false
        if (task.replyMessageIds.isEmpty() || task.status !in setOf(QueryStatus.PARTIAL, QueryStatus.TIMEOUT)) return@synchronized false
        update(context, id) {
            it.copy(status = QueryStatus.REPLIED, userConfirmed = true, updatedAt = System.currentTimeMillis(), error = null)
        }
    }

    fun refreshTimeouts(context: Context): Unit = synchronized(lock) {
        if (!initialized) { init(context); return@synchronized }
        val now = System.currentTimeMillis()
        val updated = mutableTasks.value.map { QueryState.expire(it, now) }
        if (updated != mutableTasks.value) saveLocked(context, updated)
    }

    fun <T> withBackupTransaction(context: Context, block: () -> T): T = synchronized(lock) {
        init(context)
        val before = mutableTasks.value
        try { block() } catch (error: Exception) {
            if (mutableTasks.value != before && !saveLocked(context, before)) {
                throw IllegalStateException("恢复失败，查询记录未能回滚；请保留原备份重试", error)
            }
            throw error
        }
    }

    fun exportSnapshot(): String = synchronized(lock) { QueryCodec.encode(mutableTasks.value) }
    fun validateSnapshot(json: String) { QueryCodec.decode(json) }
    fun restoreSnapshot(context: Context, json: String, merge: Boolean, subscriptionMapping: Map<Int, Int> = emptyMap()) = synchronized(lock) {
        init(context)
        val restored = QueryCodec.decode(json).map { task -> task.copy(
            subId = subscriptionMapping[task.subId] ?: -1,
            status = if (task.status in QueryState.awaiting) QueryStatus.TIMEOUT else task.status,
            error = if (task.status in QueryState.awaiting) "历史查询记录已恢复，不会自动重新发送" else task.error
        ) }
        val result = if (merge) (mutableTasks.value + restored).distinctBy { it.id }.sortedByDescending { it.createdAt } else restored
        check(saveLocked(context, result.take(300))) { mutableError.value ?: "恢复查询记录失败" }
    }

    /** Keep in-flight requests so transport callbacks and incoming replies still have a journal. */
    fun clearHistory(context: Context): Boolean = synchronized(lock) {
        init(context)
        refreshTimeouts(context)
        saveLocked(context, mutableTasks.value.filter { it.status in QueryState.awaiting })
    }

    fun factoryReset(context: Context) = synchronized(lock) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit())
        mutableTasks.value = emptyList(); mutableError.value = null; initialized = true; blocked = false
    }

    private fun update(context: Context, id: String, transform: (QueryTask) -> QueryTask): Boolean = synchronized(lock) {
        init(context)
        if (mutableTasks.value.none { it.id == id }) return@synchronized false
        saveLocked(context, mutableTasks.value.map { if (it.id == id) transform(it) else it })
    }

    private fun saveLocked(context: Context, tasks: List<QueryTask>): Boolean {
        if (blocked) return false
        return try {
            val encrypted = CryptoUtil.encrypt(QueryCodec.encode(tasks))
            check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("state_enc", encrypted).commit())
            mutableTasks.value = tasks; mutableError.value = null; true
        } catch (_: Exception) { mutableError.value = "查询记录保存失败，请检查可用存储空间"; false }
    }
}
