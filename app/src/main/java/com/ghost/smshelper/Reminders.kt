package com.ghost.smshelper

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDateTime

object Reminders {
    private const val PREFS = "SmsHelper_Reminders"
    private const val CHANNEL = "account_reminders"
    private const val JOB_ID = 24021
    private const val SUBSCRIPTION_JOB_ID = 24022
    private val lock = Any()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    internal fun options(context: Context): ReminderOptions = runCatching {
        ReminderOptions.decode(prefs(context).getString("options", "{}").orEmpty())
    }.getOrDefault(ReminderOptions())

    fun init(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "账户与查询提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "根据收到的短信数据和设置的周期提醒，不会自动发送短信"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            })
        val manager = context.getSystemService(JobScheduler::class.java) ?: return
        val options = options(context)
        if (options.enabled) {
            manager.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, ReminderJobService::class.java))
                .setPeriodic(30 * 60_000L).setPersisted(true).build())
        } else manager.cancel(JOB_ID)
        if (options.backgroundSubscriptions) {
            manager.schedule(JobInfo.Builder(SUBSCRIPTION_JOB_ID, ComponentName(context, ReminderJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(30 * 60_000L).setPersisted(true).build())
        } else manager.cancel(SUBSCRIPTION_JOB_ID)
    }

    internal fun save(context: Context, options: ReminderOptions) {
        options.validate()
        val previous = options(context)
        val prefs = prefs(context)
        val edit = prefs.edit().putString("options", options.encode())
        // A pending notification was evaluated against the old threshold. Wait for
        // a new accepted SMS rather than applying a changed threshold to that verdict.
        if (previous.enabled != options.enabled || previous.lowBalance != options.lowBalance || previous.lowData != options.lowData ||
            previous.balanceThreshold != options.balanceThreshold || previous.dataThresholdMb != options.dataThresholdMb) {
            prefs.all.keys.filter { it.startsWith("pending_") }.forEach(edit::remove)
        }
        check(edit.commit()) { "提醒设置保存失败" }
        init(context)
    }

    fun reset(context: Context) {
        check(prefs(context).edit().clear().commit()) { "提醒设置重置失败" }
        val jobs = context.getSystemService(JobScheduler::class.java)
        jobs?.cancel(JOB_ID); jobs?.cancel(SUBSCRIPTION_JOB_ID)
        context.getSystemService(NotificationManager::class.java)?.cancelAll()
    }

    fun exportBackup(context: Context): String = options(context).encode()
    fun validateBackup(json: String) { ReminderOptions.decode(json) }
    fun restoreBackup(context: Context, json: String, merge: Boolean) {
        val restored = ReminderOptions.decode(json)
        if (!merge || !prefs(context).contains("options")) save(context, restored)
    }

    fun onAccountUpdated(context: Context, subscriptionId: Int, balance: Double?, remainingDataMb: Double?, observedAt: Long) = synchronized(lock) {
        if (subscriptionId < 0 || observedAt <= 0) return@synchronized
        val p = prefs(context)
        val latest = p.getLong("observed_$subscriptionId", 0)
        val edit = p.edit().putLong("observed_$subscriptionId", maxOf(latest, observedAt))
        val options = options(context)
        listOf(Triple("balance", balance, options.balanceThreshold), Triple("data", remainingDataMb, options.dataThresholdMb)).forEach { (type, value, limit) ->
            if (value != null && value.isFinite() && observedAt >= p.getLong("field_${type}_$subscriptionId", 0)) {
                val key = "${type}_$subscriptionId"
                edit.putLong("field_$key", observedAt)
                if (ReminderPolicy.below(value, limit)) edit.putLong("pending_$key", observedAt)
                else edit.remove("pending_$key").remove("sent_$key")
            }
        }
        edit.apply()
        if (options.enabled) deliverThresholds(context, options)
    }

    internal suspend fun performScheduledCheck(context: Context, jobId: Int) {
        SimPreferences.init(context)
        if (jobId == SUBSCRIPTION_JOB_ID) {
            if (options(context).backgroundSubscriptions) ConfigManager.checkSubscriptions(context)
            return
        }
        synchronized(lock) {
            val options = options(context)
            if (!options.enabled) return@synchronized
            deliverThresholds(context, options)
            deliverCalendar(context, options)
        }
    }

    private fun deliverThresholds(context: Context, options: ReminderOptions) {
        if (ReminderPolicy.quiet(LocalDateTime.now().hour, options)) return
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val active = getActiveSims(context).associateBy { it.subId }
        p.all.filterKeys { it.startsWith("pending_") }.forEach { (prefKey, value) ->
            val key = prefKey.removePrefix("pending_")
            val subId = key.substringAfterLast('_').toIntOrNull() ?: return@forEach
            if (subId !in active) return@forEach
            val balance = key.startsWith("balance_")
            if ((balance && !options.lowBalance) || (!balance && !options.lowData)) return@forEach
            val observed = value as? Long ?: return@forEach
            if (now - observed > 48 * 3_600_000L || observed > now + 60_000L) return@forEach
            if (!ReminderPolicy.repeatAllowed(now, p.getLong("sent_$key", 0), options.repeatHours)) return@forEach
            val label = active.getValue(subId).displayName
            if (notify(context, key, if (balance) "$label · 余额提醒" else "$label · 流量提醒",
                    "最新收到的短信显示${if (balance) "余额" else "剩余流量"}低于设定阈值，打开查看数据时间与原文。")) {
                p.edit().putLong("sent_$key", now).remove(prefKey).apply()
            }
        }
    }

    private fun deliverCalendar(context: Context, options: ReminderOptions) {
        val clock = LocalDateTime.now()
        if (ReminderPolicy.quiet(clock.hour, options) || clock.hour < options.reminderHour) return
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val today = clock.toLocalDate().toString()
        val sims = getActiveSims(context)
        sims.forEach { sim ->
            val queryKey = "query_${sim.subId}"
            val observed = p.getLong("observed_${sim.subId}", 0)
            if (options.queryReminder && (observed == 0L || now - observed >= options.queryIntervalDays * 86_400_000L) &&
                p.getString("date_$queryKey", "") != today) {
                if (notify(context, queryKey, "${sim.displayName} · 该查询啦", "账户数据已有一段时间未更新，打开应用手动查询。")) {
                    p.edit().putString("date_$queryKey", today).apply()
                }
            }
            val billKey = "bill_${sim.subId}"
            val cycle = SimPreferences.get(sim.subId).billingDay
            if (options.billReminder && ReminderPolicy.billingDay(clock.toLocalDate(), cycle) && p.getString("date_$billKey", "") != today) {
                if (notify(context, billKey, "${sim.displayName} · 账单周期提醒", "已到设置的账单周期日，打开应用查看或查询账单。")) {
                    p.edit().putString("date_$billKey", today).apply()
                }
            }
        }
    }

    private fun notify(context: Context, key: String, title: String, message: String): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val pending = PendingIntent.getActivity(context, key.hashCode(), Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return runCatching {
            manager.notify(key.hashCode(), Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle(title).setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message)).setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(pending).build())
            true
        }.getOrDefault(false)
    }
}

class ReminderJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<Int, Job>()
    override fun onStartJob(params: JobParameters): Boolean {
        jobs[params.jobId] = scope.launch {
            try { Reminders.performScheduledCheck(applicationContext, params.jobId) }
            catch (_: Exception) { /* A later job retries checks; user data is untouched. */ }
            finally { jobFinished(params, false) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { jobs.remove(params.jobId)?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Reminders.init(context)
    }
}
