package com.ghost.smshelper

import org.junit.Assert.*
import org.junit.Test

class MessageMetadataTest {
    private val rules = mapOf("balance" to "余额([0-9.]+)元", "remainingData" to "剩余([0-9.]+MB)")

    @Test fun matchedZeroHasFreshnessButAbsentFieldRemainsUnknown() {
        val message = SmsItem("10086", "余额0元", "2026-01-01 00:00:00", 1)
        val state = SmsStoreState().withParsedMessage(message, rules)
        assertEquals("0", state.accounts.getValue("1").balance)
        assertEquals(FieldFreshness(message.timestamp, message.id), state.metadata.getValue("1")["balance"])
        assertFalse("remainingData" in state.metadata.getValue("1"))
    }

    @Test fun identicalValueReplyRefreshesTimeAndSource() {
        val first = SmsItem("10086", "余额0元", "2026-01-01 00:00:00", 1)
        val second = SmsItem("10086", "余额0元", "2026-01-02 00:00:00", 1)
        val state = SmsStoreState().withParsedMessage(first, rules).withParsedMessage(second, rules)
        assertEquals(second.id, state.metadata.getValue("1").getValue("balance").sourceMessageId)
    }

    @Test fun delayedOlderSmsCannotRollBackNewBalanceButCanAddUnknownField() {
        val newer = SmsItem("10086", "余额20元", "2026-01-02 00:00:00", 1)
        val older = SmsItem("10086", "余额10元 剩余50MB", "2026-01-01 00:00:00", 1)
        val state = SmsStoreState().withParsedMessage(newer, rules).withParsedMessage(older, rules)
        assertEquals("20", state.accounts.getValue("1").balance)
        assertEquals(50.0, state.accounts.getValue("1").remainingTotalDataMb, 0.0)
        assertEquals(newer.id, state.metadata.getValue("1").getValue("balance").sourceMessageId)
    }

    @Test fun v2MigratesReadCountWithoutInventingZeroMetadata() {
        val json = """{"version":2,"messages":[{"sender":"10086","body":"new","time":"2026-01-02 00:00:00"},{"sender":"10086","body":"old","time":"2026-01-01 00:00:00"}],"unread":1,"accounts":{"1":{"balance":"0.00","consumption":"9.00"}}} """
        val state = SmsStateCodec.decode(json)
        assertFalse(state.messages.first().read)
        assertTrue(state.messages.last().read)
        assertFalse("balance" in state.metadata.getValue("1"))
        assertEquals(0L, state.metadata.getValue("1").getValue("consumption").updatedAt)
        assertEquals(state, SmsStateCodec.decode(SmsStateCodec.encode(state)))
    }

    @Test fun snapshotPreservesIndividualReadStarAndFreshness() {
        val message = SmsItem("10086", "余额0元", "2026-01-01 00:00:00", 1, id = "unique", read = true, starred = true)
        val state = SmsStoreState(messages = listOf(message)).withParsedMessage(message, rules)
        assertEquals(state, SmsStateCodec.decode(SmsStateCodec.encode(state)))
    }

    @Test fun portableRestoreArchivesUnmappedAccountsWithoutBindingCurrentCard() {
        val message = SmsItem("10086", "余额9元", "2026-01-01 00:00:00", 1)
        val foreign = SmsStoreState(messages = listOf(message), selectedSubId = 1).withParsedMessage(message, rules)
        val restored = foreign.remapSubscriptions(emptyMap())
        assertNull(restored.messages.single().subId)
        assertNull(restored.selectedSubId)
        assertTrue("archived:1" in restored.accounts)
        assertFalse("1" in restored.accounts)
        val mapped = foreign.remapSubscriptions(mapOf(1 to 7))
        assertEquals(7, mapped.messages.single().subId)
        assertEquals(message.id, mapped.messages.single().id)
        assertEquals("9", mapped.accounts.getValue("7").balance)
    }

    @Test fun mergeKeepsLiveMetricsAndLocalStarFlags() {
        val message = SmsItem("10086", "余额9元", "2026-01-01 00:00:00", 1)
        val local = SmsStoreState(messages = listOf(message.copy(starred = true)), accounts = mapOf("1" to ParsedInfo(balance = "50")))
        val backup = SmsStoreState(messages = listOf(message), accounts = mapOf("1" to ParsedInfo(balance = "9")))
        val merged = local.mergeBackup(backup)
        assertEquals(1, merged.messages.size)
        assertTrue(merged.messages.single().starred)
        assertEquals("50", merged.accounts.getValue("1").balance)
    }
    @Test fun sameSecondDualSimMessagesSurviveFullBackupMerge() {
        val a = SmsItem("10086", "余额9元", "2026-01-01 00:00:00", 1)
        val b = SmsItem("10086", "余额9元", "2026-01-01 00:00:00", 2)
        val backup = SmsStoreState(messages = listOf(a, b)).remapSubscriptions(emptyMap())
        val merged = SmsStoreState().mergeBackup(backup)
        assertEquals(2, merged.messages.size)
        assertNotEquals(merged.messages[0].id, merged.messages[1].id)
    }

    @Test fun damagedV3DuplicateIdsAreRejectedButLegacyRecordsReceiveUniqueIds() {
        val item = """{"sender":"10086","body":"reply","time":"2026-01-01 00:00:00","id":"same"}"""
        val v2 = """{"version":2,"messages":[$item,$item],"accounts":{}}"""
        val migrated = SmsStateCodec.decode(v2)
        assertEquals(2, migrated.messages.map { it.id }.toSet().size)
        assertThrows(IllegalArgumentException::class.java) { SmsStateCodec.decode(v2.replace("\"version\":2", "\"version\":3")) }
    }

    @Test fun archivedAccountCollisionDoesNotDiscardEitherBalance() {
        val state = SmsStoreState(accounts = linkedMapOf("1" to ParsedInfo(balance = "10"),
            "archived:1" to ParsedInfo(balance = "20")))
        val remapped = state.remapSubscriptions(emptyMap())
        assertEquals(2, remapped.accounts.size)
        assertEquals(setOf("10", "20"), remapped.accounts.values.map { it.balance }.toSet())
        assertEquals(remapped, SmsStateCodec.decode(SmsStateCodec.encode(remapped)))
    }

}
