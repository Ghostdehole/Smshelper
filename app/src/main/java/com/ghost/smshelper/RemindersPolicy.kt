package com.ghost.smshelper

import org.json.JSONObject
import java.time.LocalDate

internal data class ReminderOptions(
    val enabled: Boolean = false,
    val lowBalance: Boolean = true,
    val balanceThreshold: Double = 10.0,
    val lowData: Boolean = true,
    val dataThresholdMb: Double = 500.0,
    val queryReminder: Boolean = false,
    val queryIntervalDays: Int = 7,
    val reminderHour: Int = 10,
    val billReminder: Boolean = false,
    val quietEnabled: Boolean = true,
    val quietStart: Int = 22,
    val quietEnd: Int = 8,
    val repeatHours: Int = 24,
    val backgroundSubscriptions: Boolean = true
) {
    fun validate(): ReminderOptions = apply {
        require(balanceThreshold.isFinite() && balanceThreshold in 0.0..1_000_000.0) { "余额阈值应在 0 至 1000000 元之间" }
        require(dataThresholdMb.isFinite() && dataThresholdMb in 0.0..100_000_000.0) { "流量阈值无效" }
        require(queryIntervalDays in 1..90 && repeatHours in 1..720) { "提醒间隔无效" }
        require(listOf(reminderHour, quietStart, quietEnd).all { it in 0..23 }) { "时间应为 0 至 23 时" }
    }

    fun encode(): String = JSONObject().apply {
        put("enabled", enabled); put("lowBalance", lowBalance); put("balanceThreshold", balanceThreshold)
        put("lowData", lowData); put("dataThresholdMb", dataThresholdMb)
        put("queryReminder", queryReminder); put("queryIntervalDays", queryIntervalDays)
        put("reminderHour", reminderHour); put("billReminder", billReminder)
        put("quietEnabled", quietEnabled); put("quietStart", quietStart); put("quietEnd", quietEnd)
        put("repeatHours", repeatHours); put("backgroundSubscriptions", backgroundSubscriptions)
    }.toString()

    companion object {
        fun decode(json: String): ReminderOptions {
            val o = JSONObject(json)
            return ReminderOptions(o.optBoolean("enabled", false), o.optBoolean("lowBalance", true),
                o.optDouble("balanceThreshold", 10.0), o.optBoolean("lowData", true),
                o.optDouble("dataThresholdMb", 500.0), o.optBoolean("queryReminder", false),
                o.optInt("queryIntervalDays", 7), o.optInt("reminderHour", 10), o.optBoolean("billReminder", false),
                o.optBoolean("quietEnabled", true), o.optInt("quietStart", 22), o.optInt("quietEnd", 8),
                o.optInt("repeatHours", 24), o.optBoolean("backgroundSubscriptions", true)).validate()
        }
    }
}

internal object ReminderPolicy {
    fun quiet(hour: Int, options: ReminderOptions): Boolean {
        if (!options.quietEnabled || options.quietStart == options.quietEnd) return false
        return if (options.quietStart < options.quietEnd) hour in options.quietStart until options.quietEnd
        else hour >= options.quietStart || hour < options.quietEnd
    }

    fun repeatAllowed(now: Long, last: Long, hours: Int): Boolean =
        last <= 0 || now < last || now - last >= hours * 3_600_000L

    fun billingDay(date: LocalDate, requestedDay: Int): Boolean =
        date.dayOfMonth == requestedDay.coerceIn(1, date.lengthOfMonth())

    fun below(value: Double?, limit: Double): Boolean = value != null && value.isFinite() && value < limit
}
