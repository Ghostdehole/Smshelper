package com.ghost.smshelper

import org.junit.Assert.*
import org.junit.Test

class SmsStateCodecTest {
    @Test fun legacyHistoryRetainsSenderTimeAndUnassignedAccount() {
        val state = SmsStateCodec.decodeLegacy(
            """[{"sender":"10086","body":"余额18.50元","time":"2026-01-01 10:00:00"}]""",
            """{"balance":"18.50","generalDataMb":512,"bills":[{"month":"2026年1月","amount":"20.00"}]}""",
            1, 7
        )
        assertEquals(SmsItem("10086", "余额18.50元", "2026-01-01 10:00:00", null), state.messages.single())
        assertEquals("18.50", state.selectedInfo().balance)
        assertTrue(state.usesLegacyAccount())
        assertEquals("20.00", state.accounts.getValue(LEGACY_ACCOUNT).historicalBills.single().amount)
    }

    @Test fun zeroReplyEstablishesKnownAccountWithoutReusingLegacyAllowance() {
        val old = SmsStoreState(accounts = mapOf(LEGACY_ACCOUNT to ParsedInfo(generalDataMb = 500.0)), selectedSubId = 1)
        val updated = old.withParsedMessage(SmsItem("10086", "通用剩余0MB", "2026-01-02 10:00:00", 1),
            mapOf("generalData" to "通用剩余(.+)"))
        assertFalse(updated.usesLegacyAccount())
        assertEquals(0.0, updated.selectedInfo().generalDataMb, 0.0)
        assertEquals(500.0, updated.accounts.getValue(LEGACY_ACCOUNT).generalDataMb, 0.0)
    }

    @Test fun twoSubscriptionsWithSameSenderDoNotShareBalances() {
        val rules = mapOf("balance" to "余额([0-9.]+)元")
        var state = SmsStoreState(selectedSubId = 1)
        state = state.withParsedMessage(SmsItem("10086", "余额10元", "2026-01-01 10:00:00", 1), rules)
        state = state.withParsedMessage(SmsItem("10086", "余额20元", "2026-01-01 10:01:00", 2), rules)
        assertEquals("10", state.selectedInfo().balance)
        assertEquals("20", state.copy(selectedSubId = 2).selectedInfo().balance)
        assertEquals("0.00", state.copy(selectedSubId = 3).selectedInfo().balance)
    }

    @Test fun encryptedSnapshotPayloadRoundTripPreservesAllAccountsAndMessageIdentity() {
        val source = SmsStoreState(
            messages = listOf(SmsItem("10086", "第一行\n[END]\n第二行", "2026-01-01 00:00:00", 2),
                SmsItem("10010", "旧记录", "2025-12-01 00:00:00")),
            accounts = mapOf("2" to ParsedInfo(balance = "3.00", remainingTotalDataMb = 0.0,
                historicalBills = listOf(BillRecord("2026年1月", "5.00")),
                trafficDetails = listOf("流量剩余0MB")), LEGACY_ACCOUNT to ParsedInfo(balance = "1.00")),
            selectedSubId = 2, unread = 1
        )
        assertEquals(source, SmsStateCodec.decode(SmsStateCodec.encode(source)))
    }

    @Test fun importCannotReuseSubscriptionNumbersFromAnotherDevice() {
        val source = SmsStoreState(
            messages = listOf(SmsItem("10086", "现有记录", "2026-01-02 00:00:00", 1)),
            accounts = mapOf("1" to ParsedInfo(balance = "50.00")), selectedSubId = 1
        )
        val (updated, count) = source.withImportedMessages(
            listOf(SmsItem("10086", "余额1.00元", "2026-01-01 00:00:00", 1)))
        assertEquals(1, count)
        assertNull(updated.messages.last().subId)
        assertEquals("50.00", updated.selectedInfo().balance)
        assertEquals(1, updated.messages.first().subId)
    }

    @Test fun importAfterClearingHistoryRetainsLiveAccountMetrics() {
        val source = SmsStoreState(accounts = mapOf("7" to ParsedInfo(balance = "80.00")), selectedSubId = 7)
        val (updated, _) = source.withImportedMessages(
            listOf(SmsItem("10086", "余额10.00元", "2025-12-01 00:00:00", 7)))
        assertEquals(source.accounts, updated.accounts)
        assertEquals("80.00", updated.selectedInfo().balance)
    }

    @Test fun importDeduplicatesAgainstExistingMessagesWithoutChangingTheirSim() {
        val item = SmsItem("10086", "同一条回复", "2026-01-01 00:00:00", 2)
        val source = SmsStoreState(messages = listOf(item))
        val (updated, count) = source.withImportedMessages(listOf(item, item.copy(subId = 99)))
        assertEquals(0, count)
        assertEquals(listOf(item), updated.messages)
    }

    @Test fun malformedSnapshotIsRejectedInsteadOfSilentlyDroppingRecords() {
        try {
            SmsStateCodec.decode("""{"version":2,"messages":[{"sender":"10086"}],"accounts":{}}""")
            fail("Incomplete data must not be migrated as an empty inbox")
        } catch (_: org.json.JSONException) {
            // The repository keeps the original encrypted preference on failure.
        }
    }

    @Test fun unknownFutureVersionCannotBeOverwrittenByOldReader() {
        try {
            SmsStateCodec.decode("""{"version":99,"messages":[],"accounts":{}}""")
            fail("Unsupported versions must stay intact")
        } catch (_: IllegalArgumentException) {
        }
    }
}
