@file:Suppress("DEPRECATION")
package com.ghost.smshelper

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/** Both build variants go through the same SIM validation and dispatch path. */
class SmsDispatcher(private val context: Context) {
    fun compose(command: String, sim: SimInfo, target: String): Boolean {
        if (!validate(command, sim.subId, target)) return false
        val task = QueryRepository.begin(context, sim.subId, target.trim(), command, composer = true)
            ?: return journalUnavailable()
        return try {
            context.startActivity(Intent(Intent.ACTION_SENDTO, "smsto:${target.trim()}".toUri()).apply {
                putExtra("sms_body", command)
                putExtra("android.telephony.extra.SUBSCRIPTION_INDEX", sim.subId)
                putExtra("subscription", sim.subId)
                putExtra("simSlot", sim.slotIndex)
                putExtra("sim_slot", sim.slotIndex)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true // The external app still owns confirmation and its actual sending result.
        } catch (_: Exception) {
            QueryRepository.failed(context, task.id, "无法打开系统短信应用")
            notify("无法打开系统短信应用")
            false
        }
    }

    fun send(command: String, subId: Int, target: String, showPrompt: Boolean): Boolean =
        dispatch(command, subId, target, showPrompt, null)

    /** Retrying always validates the original SIM; it never silently falls back to another card. */
    fun retry(task: QueryTask, showPrompt: Boolean = true): Boolean {
        QueryRepository.init(context)
        QueryRepository.refreshTimeouts(context)
        val current = QueryRepository.tasks.value.firstOrNull { it.id == task.id }
            ?: run { notify("此查询记录已被移除，请重新选择指令"); return false }
        return retryCurrent(current, showPrompt)
    }

    private fun retryCurrent(task: QueryTask, showPrompt: Boolean): Boolean {
        if (task.status in QueryState.awaiting) { notify("此请求仍在等待结果，请先检查记录"); return false }
        val sim = getActiveSims(context).firstOrNull { it.subId == task.subId }
        if (sim == null) { notify("原 SIM 卡已不可用，请重新选择并发送"); return false }
        val compatible = context.getSharedPreferences("SmsHelper_Prefs", Context.MODE_PRIVATE)
            .getBoolean("compat_mode", false)
        return if (compatible || task.status == QueryStatus.COMPOSER_OPENED) compose(task.command, sim, task.target)
        else dispatch(task.command, task.subId, task.target, showPrompt, task.id)
    }

    private fun dispatch(command: String, subId: Int, target: String, showPrompt: Boolean, retryOf: String?): Boolean {
        if (!validate(command, subId, target)) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            notify("请先允许发送短信权限，或启用系统短信兼容模式")
            return false
        }
        val task = QueryRepository.begin(context, subId, target.trim(), command, retryOf)
            ?: return journalUnavailable()
        val id = task.id
        return try {
            val modifiedRom = Build.MANUFACTURER.lowercase().let { "xiaomi" in it || "redmi" in it }
            val manager = if (Build.VERSION.SDK_INT >= 31 && !modifiedRom) {
                context.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
            } else SmsManager.getSmsManagerForSubscriptionId(subId)
            val parts = manager.divideMessage(command)
            check(parts.isNotEmpty()) { "短信内容为空" }
            check(QueryRepository.setPartCount(context, id, parts.size)) { "无法记录发送任务" }
            val sentIntents = ArrayList(parts.indices.map { part ->
                val intent = Intent(context, SmsSentReceiver::class.java).apply {
                    action = ACTION_SENT
                    data = "smshelper://sent/$id/$part".toUri()
                    putExtra(EXTRA_ID, id)
                    putExtra(EXTRA_PART, part)
                    putExtra(EXTRA_COUNT, parts.size)
                    putExtra(EXTRA_PROMPT, showPrompt)
                }
                PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            })
            if (parts.size > 1) manager.sendMultipartTextMessage(target.trim(), null, parts, sentIntents, null)
            else manager.sendTextMessage(target.trim(), null, command, sentIntents.first(), null)
            if (showPrompt) notify("查询已提交，等待发送结果")
            true
        } catch (_: Exception) {
            QueryRepository.failed(context, id, "发送失败，请检查短信权限、SIM 卡和网络")
            notify("发送失败，请检查短信权限、SIM 卡和网络")
            false
        }
    }

    private fun journalUnavailable(): Boolean {
        notify(QueryRepository.storageError.value ?: "相同短信刚刚提交，请勿重复发送")
        return false
    }

    private fun validate(command: String, subId: Int, target: String): Boolean {
        val problem = when {
            command.isBlank() -> "短信内容不能为空"
            target.isBlank() -> "当前运营商没有可用的短信规则"
            getActiveSims(context).none { it.subId == subId } -> "所选 SIM 卡已不可用，请重新选择"
            else -> null
        }
        if (problem != null) notify(problem)
        return problem == null
    }

    private fun notify(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    companion object {
        const val ACTION_SENT = "com.ghost.smshelper.SMS_SENT"
        const val EXTRA_ID = "request_id"
        const val EXTRA_PART = "part"
        const val EXTRA_COUNT = "part_count"
        const val EXTRA_PROMPT = "show_prompt"
    }
}

/** Explicit PendingIntents target this unexported receiver; unrelated apps cannot spoof receipts. */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SmsDispatcher.ACTION_SENT) return
        val id = intent.getStringExtra(SmsDispatcher.EXTRA_ID) ?: return
        val state = QueryRepository.recordReceipt(
            context, id, intent.getIntExtra(SmsDispatcher.EXTRA_PART, 0),
            intent.getIntExtra(SmsDispatcher.EXTRA_COUNT, 1), resultCode == Activity.RESULT_OK
        ) ?: return
        if (state == SendOutcome.FAILED || intent.getBooleanExtra(SmsDispatcher.EXTRA_PROMPT, true)) {
            val text = if (state == SendOutcome.SENT) "短信已发送，等待对方回复" else "短信发送失败，请检查网络、余额及系统短信限制"
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }

}
