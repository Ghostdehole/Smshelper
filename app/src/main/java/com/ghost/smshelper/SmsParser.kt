package com.ghost.smshelper

import java.util.Locale

/** Carrier parsing has no Android or persistence dependencies. Invalid fields are isolated. */
object SmsParser {
    private const val MAX_TEXT_LENGTH = 32_768
    private const val MAX_PATTERN_LENGTH = 2_048
    private const val MAX_MATCHES = 500
    private const val MAX_DATA_MB = 1_000_000_000.0
    private const val MAX_VOICE_MINUTES = 10_000_000L
    private const val MAX_MONEY = 1_000_000_000.0
    private val decimal = Regex("[+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)")
    private val dataUnit = Regex("(\\d+(?:\\.\\d+)?|\\.\\d+)\\s*(TB|GB|MB|KB|B|T|G|M|K)", RegexOption.IGNORE_CASE)
    private val regexCache = object : LinkedHashMap<String, Regex?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Regex?>?): Boolean = size > 128
    }

    private fun regex(pattern: String?): Regex? {
        if (pattern.isNullOrEmpty() || pattern.length > MAX_PATTERN_LENGTH) return null
        return synchronized(regexCache) {
            if (!regexCache.containsKey(pattern)) regexCache[pattern] = runCatching { Regex(pattern) }.getOrNull()
            regexCache[pattern]
        }
    }

    private fun matches(pattern: String?, body: String): List<MatchResult> = try {
        // Preserve Java/Android regex syntax for existing custom profiles. Length caps
        // and exception handling are not a timeout: profiles must come from trusted sources.
        regex(pattern)?.findAll(body)?.take(MAX_MATCHES)?.toList().orEmpty()
    } catch (_: RuntimeException) {
        emptyList()
    } catch (_: StackOverflowError) {
        // A user-supplied recursive/backtracking expression must not crash SMS reception.
        emptyList()
    }

    private fun MatchResult.capture(index: Int): String? =
        if (index in groups.indices) groups[index]?.value?.trim()?.takeIf { it.isNotEmpty() } else null

    internal fun parseDataMb(text: String?): Double? {
        if (text.isNullOrBlank() || text.length > MAX_TEXT_LENGTH) return null
        val value = text.trim()
        val parts = dataUnit.findAll(value).toList()
        if (parts.isEmpty()) return null
        // Reject malformed values such as -1GB or 1..5GB instead of parsing a suffix.
        val remainder = dataUnit.replace(value, "").replace(Regex("[\\s+、,，]+"), "")
        if (remainder.isNotEmpty()) return null
        var total = 0.0
        for (part in parts) {
            val number = part.groupValues[1].toDoubleOrNull() ?: return null
            val multiplier = when (part.groupValues[2].uppercase(Locale.ROOT)) {
                "TB", "T" -> 1024.0 * 1024.0
                "GB", "G" -> 1024.0
                "KB", "K" -> 1.0 / 1024.0
                "B" -> 1.0 / (1024.0 * 1024.0)
                else -> 1.0
            }
            total += number * multiplier
            if (!total.isFinite() || total > MAX_DATA_MB) return null
        }
        return total
    }

    private fun money(text: String?): String? {
        val value = text?.trim() ?: return null
        if (!decimal.matches(value)) return null
        val number = value.toDoubleOrNull() ?: return null
        return value.takeIf { number.isFinite() && kotlin.math.abs(number) <= MAX_MONEY }
    }

    private fun voice(pattern: String?, body: String): Int? {
        val found = matches(pattern, body)
        if (found.isEmpty()) return null
        var total = 0L
        for (match in found) {
            val number = match.capture(1)?.toLongOrNull() ?: return null
            if (number < 0 || number > MAX_VOICE_MINUTES) return null
            total += number
            if (total > MAX_VOICE_MINUTES) return null
        }
        return total.toInt()
    }

    internal data class Result(val info: ParsedInfo, val matched: Boolean, val matchedFields: Set<String> = emptySet())

    fun parse(current: ParsedInfo, body: String, rules: Map<String, String>): ParsedInfo =
        parseResult(current, body, rules).info

    internal fun parseResult(current: ParsedInfo, body: String, rules: Map<String, String>): Result {
        if (body.isEmpty() || body.length > MAX_TEXT_LENGTH) return Result(current, false)
        fun capture(key: String): String? = matches(rules[key], body).firstOrNull()?.capture(1)
        val balance = money(capture("balance"))
        val consumption = money(capture("consumption"))
        val usedData = parseDataMb(capture("usedData"))
        val remainingData = parseDataMb(capture("remainingData"))
        val generalData = parseDataMb(capture("generalData"))
        val totalVoice = voice(rules["voiceInclude"], body)
        val remainingVoice = voice(rules["voiceRemain"], body)
        val traffic = if (body.contains("流量") && (body.contains("剩余") || body.contains("已使用"))) {
            body.split("\n", ";", "；", "。")
                .map { it.trim() }.filter { it.contains("流量") && it.length > 5 }.take(MAX_MATCHES)
        } else emptyList()
        val bills = matches(rules["bill"], body).mapNotNull { match ->
            val month = match.capture(1) ?: return@mapNotNull null
            val amount = money(match.capture(2)) ?: return@mapNotNull null
            BillRecord(month, amount)
        }.distinctBy { it.month }
        val info = current.copy(
            balance = balance ?: current.balance,
            consumption = consumption ?: current.consumption,
            usedDataMb = usedData ?: current.usedDataMb,
            remainingTotalDataMb = remainingData ?: current.remainingTotalDataMb,
            generalDataMb = generalData ?: current.generalDataMb,
            totalVoice = totalVoice ?: current.totalVoice,
            remainingVoice = remainingVoice ?: current.remainingVoice,
            trafficDetails = traffic.ifEmpty { current.trafficDetails },
            historicalBills = bills.ifEmpty { current.historicalBills }
        )
        val fields = buildSet {
            if (balance != null) add("balance")
            if (consumption != null) add("consumption")
            if (usedData != null) add("usedData")
            if (remainingData != null) add("remainingData")
            if (generalData != null) add("generalData")
            if (totalVoice != null) add("voiceInclude")
            if (remainingVoice != null) add("voiceRemain")
            if (traffic.isNotEmpty()) add("traffic")
            if (bills.isNotEmpty()) add("bill")
        }
        return Result(info, fields.isNotEmpty(), fields)
    }
}
