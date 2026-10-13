package com.ghost.smshelper

import org.junit.Assert.*
import org.junit.Test

class SmsParserTest {
    private val rules = mapOf(
        "balance" to "余额[：:]?([+-]?[0-9.]+)元",
        "consumption" to "消费([0-9.]+)元",
        "usedData" to "已用([^；]+)",
        "remainingData" to "剩余流量([^；]+)",
        "generalData" to "通用流量([^；]+)",
        "voiceInclude" to "包含([0-9]+)分钟",
        "voiceRemain" to "剩余([0-9]+)分钟",
        "bill" to "([0-9]{4}年[0-9]{1,2}月)：消费([0-9.]+)元"
    )

    @Test fun zeroUsageAndExhaustedAllowancesReplacePreviousValues() {
        val previous = ParsedInfo(usedDataMb = 50.0, remainingTotalDataMb = 900.0, generalDataMb = 100.0,
            totalVoice = 100, remainingVoice = 50)
        val result = SmsParser.parse(previous, "已用0MB；剩余流量0 GB；通用流量0kb；包含0分钟；剩余0分钟", rules)
        assertEquals(0.0, result.usedDataMb, 0.0)
        assertEquals(0.0, result.remainingTotalDataMb, 0.0)
        assertEquals(0.0, result.generalDataMb, 0.0)
        assertEquals(0, result.totalVoice)
        assertEquals(0, result.remainingVoice)
    }

    @Test fun nonmatchingRepliesKeepPreviouslyKnownFields() {
        val previous = ParsedInfo(balance = "18.50", usedDataMb = 20.0, remainingVoice = 40,
            historicalBills = listOf(BillRecord("2026年1月", "25.00")))
        assertEquals(previous, SmsParser.parse(previous, "业务已经办理成功", rules))
    }

    @Test fun mixedUnitsWhitespaceAndLowercaseConvertToMegabytes() {
        assertEquals(1537.0, SmsParser.parseDataMb("1 gb 512MB 1024 kB")!!, 0.00001)
        assertEquals(1024.0 * 1024.0, SmsParser.parseDataMb("1t")!!, 0.00001)
        assertEquals(1.0, SmsParser.parseDataMb("1048576 B")!!, 0.00001)
        assertEquals(2048.0, SmsParser.parseDataMb("2G")!!, 0.00001)
    }

    @Test fun malformedOrUnboundedNumbersDoNotBecomePartialPositiveValues() {
        listOf(null, "", "不限量", "-1GB", "1..5GB", "NaNMB", "1e999GB", "999999999999999999GB")
            .forEach { assertNull("Must not parse $it", SmsParser.parseDataMb(it)) }
        val before = ParsedInfo(balance = "12.00", remainingVoice = 20)
        val result = SmsParser.parse(before, "余额1..2元；剩余9999999999999999999999999分钟", rules)
        assertEquals("12.00", result.balance)
        assertEquals(20, result.remainingVoice)
    }

    @Test fun voicePackagesAreAddedAndNegativeBalanceIsAllowed() {
        val result = SmsParser.parse(ParsedInfo(), "余额-2.50元；包含100分钟；包含20分钟；剩余50分钟；剩余0分钟", rules)
        assertEquals("-2.50", result.balance)
        assertEquals(120, result.totalVoice)
        assertEquals(50, result.remainingVoice)
    }

    @Test fun malformedRegexOrMissingCaptureCannotBlockOtherFields() {
        val brokenRules = rules + mapOf("balance" to "[", "voiceRemain" to "剩余[0-9]+分钟", "bill" to "(账单)")
        val previous = ParsedInfo(balance = "12.00", remainingVoice = 30)
        val result = SmsParser.parse(previous, "余额90元；消费3.50元；剩余5分钟；账单", brokenRules)
        assertEquals("12.00", result.balance)
        assertEquals(30, result.remainingVoice)
        assertEquals("3.50", result.consumption)
    }

    @Test fun oversizedMessageIsStoredByCallerButNotFedIntoCustomRegex() {
        val previous = ParsedInfo(balance = "8.00")
        val result = SmsParser.parse(previous, "余额90元" + "a".repeat(32_768), rules)
        assertEquals(previous, result)
    }

    @Test fun historicalBillsAndTrafficDetailsAreKept() {
        val result = SmsParser.parse(ParsedInfo(),
            "2026年1月：消费20.00元；2026年2月：消费30.00元；套餐流量剩余100MB。定向流量剩余200MB", rules)
        assertEquals(listOf(BillRecord("2026年1月", "20.00"), BillRecord("2026年2月", "30.00")), result.historicalBills)
        assertEquals(listOf("套餐流量剩余100MB", "定向流量剩余200MB"), result.trafficDetails)
    }
}
