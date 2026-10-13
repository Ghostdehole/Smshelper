package com.ghost.smshelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TxtBackupTest {
    @Test fun boundedInputRejectsOversizedFilesBeforeParsing() {
        assertEquals("你好", readUtf8Text("你好".byteInputStream(), 6))
        assertThrows(IllegalArgumentException::class.java) { readUtf8Text("你好".byteInputStream(), 5) }
    }

    @Test fun versionedBackupRoundTripsDelimiterTextUnicodeAndSubscription() {
        val messages = listOf(
            SmsItem("+8610086", "  第一行\r\n[SMS]\nSender: x\nTime: y\nBody: z\n[END]\n\"\\🙂\t  ", "2026-10-12 17:20:30", 7),
            SmsItem("other sender", "", "", null)
        )
        assertEquals(messages, TxtBackup.decode(TxtBackup.encode(messages)))
        assertEquals(emptyList<SmsItem>(), TxtBackup.decode(TxtBackup.encode(emptyList())))
    }

    @Test fun oldBackupRetainsMultilineBodyAndDoesNotCollapseDifferentSenders() {
        val legacy = "[SMS]\nSender: 10086\nTime: 2026-10-12 17:20:30\nBody: 第一行\n第二行 [END] [SMS]\n[END]\n\n[SMS]\nSender: 10010\nTime: 2026-10-12 17:20:30\nBody: 第一行\n第二行 [END] [SMS]\n[END]"
        val expected = listOf(
            SmsItem("10086", "第一行\n第二行 [END] [SMS]", "2026-10-12 17:20:30"),
            SmsItem("10010", "第一行\n第二行 [END] [SMS]", "2026-10-12 17:20:30")
        )
        assertEquals(expected, TxtBackup.decode(legacy))
        assertEquals(expected, TxtBackup.decode("\uFEFF" + legacy.replace("\n", "\r\n")))
    }

    @Test fun malformedOrUnknownBackupsAreRejectedRatherThanPartiallyImported() {
        assertThrows(IllegalArgumentException::class.java) { TxtBackup.decode("hello") }
        assertThrows(IllegalArgumentException::class.java) { TxtBackup.decode("{\"format\":\"SmsHelperBackup\",\"version\":3,\"messages\":[]}") }
        assertThrows(IllegalArgumentException::class.java) { TxtBackup.decode("[SMS]\nSender: 10086\nTime: now\nBody: incomplete") }
        assertThrows(IllegalArgumentException::class.java) { TxtBackup.decode("[SMS]\nSender: 10086\nTime: now\nBody: good\n[END]\n\n[SMS]\ncorrupt") }
    }
}
