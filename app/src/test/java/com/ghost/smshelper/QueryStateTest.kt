package com.ghost.smshelper

import org.junit.Assert.*
import org.junit.Test

class QueryStateTest {
    private fun task(id: String = "a", subId: Int = 1, createdAt: Long = 100_000L) =
        QueryTask(id, subId, "10086", "CXYE", createdAt)

    @Test fun multipartReceiptsRemainCorrectAcrossPersistedProcessRecreation() {
        val original = task().copy(partCount = 2)
        val (first, firstOutcome) = QueryState.receipt(original, 0, 2, true, 101_000)
        assertNull(firstOutcome)
        val restored = QueryCodec.decode(QueryCodec.encode(listOf(first))).single()
        val (failed, outcome) = QueryState.receipt(restored, 1, 2, false, 102_000)
        assertEquals(SendOutcome.FAILED, outcome)
        assertEquals(QueryStatus.FAILED, failed.status)
        assertNull(QueryState.receipt(failed, 1, 2, true, 103_000).second)
    }

    @Test fun allSentPartsOnlyMeanWaitingAndNeverDataUpdated() {
        val (sent, outcome) = QueryState.receipt(task(), 0, 1, true, 101_000)
        assertEquals(SendOutcome.SENT, outcome)
        assertEquals(QueryStatus.WAITING, sent.status)
        assertTrue(sent.matchedFields.isEmpty())
        assertTrue(sent.replyMessageIds.isEmpty())
    }

    @Test fun duplicateTapIsBlockedOnlyOnSameCardTargetAndBodyWithinWindow() {
        val tasks = listOf(task())
        assertTrue(QueryState.duplicate(tasks, 1, "10086", "CXYE", 105_000))
        assertFalse(QueryState.duplicate(tasks, 2, "10086", "CXYE", 105_000))
        assertFalse(QueryState.duplicate(tasks, 1, "10010", "CXYE", 105_000))
        assertFalse(QueryState.duplicate(tasks, 1, "10086", "CXLL", 105_000))
        assertFalse(QueryState.duplicate(tasks, 1, "10086", "CXYE", 115_000))
    }

    @Test fun zeroFieldReplyIsAssociatedButRequiresUserConfirmation() {
        val message = SmsItem("10086", "余额0元", "now", 1, timestamp = 105_000)
        val updated = QueryState.associate(listOf(task()), message, setOf("balance")).single()
        assertEquals(QueryStatus.PARTIAL, updated.status)
        assertEquals(setOf("balance"), updated.matchedFields)
        assertFalse(updated.userConfirmed)
        assertEquals(listOf(message.id), updated.replyMessageIds)
    }

    @Test fun wrongCardOldReplyAndUnparsedMessagesCannotCompleteQueries() {
        val original = listOf(task())
        val message = SmsItem("10086", "余额0元", "now", 2, timestamp = 105_000)
        assertEquals(original, QueryState.associate(original, message, setOf("balance")))
        assertEquals(original, QueryState.associate(original, message.copy(subId = 1, timestamp = 1_000), setOf("balance")))
        assertEquals(original, QueryState.associate(original, message.copy(subId = 1), emptySet()))
    }

    @Test fun ambiguousParallelQueriesStayUnassociated() {
        val tasks = listOf(task(), task("b", createdAt = 101_000))
        val message = SmsItem("10086", "余额0元", "now", 1, timestamp = 105_000)
        assertEquals(tasks, QueryState.associate(tasks, message, setOf("balance")))
    }

    @Test fun partialReplyBeforeTransportReceiptIsNotLost() {
        val original = task().copy(status = QueryStatus.PARTIAL, matchedFields = setOf("balance"), replyMessageIds = listOf("sms"))
        val (updated, outcome) = QueryState.receipt(original, 0, 1, true, 105_000)
        assertEquals(QueryStatus.PARTIAL, updated.status)
        assertEquals(SendOutcome.SENT, outcome)
        assertEquals(listOf("sms"), updated.replyMessageIds)
    }

    @Test fun composerOpenedNeverPretendsToBeSentOrTimeout() {
        val composed = task().copy(status = QueryStatus.COMPOSER_OPENED)
        assertEquals(composed, QueryState.expire(composed, Long.MAX_VALUE / 2))
        val message = SmsItem("10086", "余额0元", "now", 1, timestamp = 105_000)
        assertEquals(listOf(composed), QueryState.associate(listOf(composed), message, setOf("balance")))
    }

    @Test fun lateReceiptCanRecoverUnknownTransportTimeout() {
        val timedOut = QueryState.expire(task(), 400_000)
        assertEquals(QueryStatus.TIMEOUT, timedOut.status)
        val (updated, _) = QueryState.receipt(timedOut, 0, 1, true, 401_000)
        assertEquals(QueryStatus.WAITING, updated.status)
        assertNull(updated.error)
    }
}
