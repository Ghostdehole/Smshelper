package com.ghost.smshelper

import org.junit.Assert.*
import org.junit.Test

class RuleEditorCodecTest {
    private val original = CarrierConfig("CMCC", "移动", "10086", listOf("CXYE", "CXLL"), linkedMapOf("balance" to "余额([0-9.]+)元", "custom" to "自定义(.*)"),
        listOf(CommandCategory("查询", listOf(ActionCommand("1011#", "月份账单", true, "输入年月"), ActionCommand("CXYE", "余额")))))

    @Test fun editorRoundTripPreservesAllFieldsAndUnknownRegexKeys() {
        assertEquals(listOf(original), ConfigValidation.parse(RuleEditorCodec.encode(listOf(original))))
    }

    @Test fun previewExplainsSenderChangesAndRemovedCarriers() {
        val before = RuleEditorCodec.encode(listOf(original, original.copy(identifier = "CUCC", name = "联通", targetNumber = "10010")))
        val after = RuleEditorCodec.encode(listOf(original.copy(targetNumber = "1008611", syncCommands = listOf("CXYE"))))
        val changes = RuleEditorCodec.changes(before, after)
        assertTrue(changes.any { it.contains("移除运营商：联通") })
        assertTrue(changes.any { it.contains("10086 → 1008611") })
        assertTrue(changes.any { it.contains("查询组合") })
    }

    @Test fun formattingOnlyChangesDoNotSuggestRuleChanges() {
        val json = RuleEditorCodec.encode(listOf(original))
        assertEquals(listOf("规则内容没有变化"), RuleEditorCodec.changes(json, json))
    }

    @Test fun catalogSnapshotKeepsRevisionHistoryIndependent() {
        val catalog = ConfigCatalog()
        val json = RuleEditorCodec.encode(listOf(original))
        catalog.replaceProfile(ConfigProfile("one", "个人", "个人编辑", json, listOf(original)))
        catalog.revisions.add(RuleRevision("v1", "one", "个人", json, 10, "编辑前"))
        val snapshot = catalog.copy()
        catalog.removeProfile("one")
        assertTrue(catalog.revisions.isEmpty())
        assertEquals(1, snapshot.revisions.size)
        assertEquals(1, snapshot.profiles.size)
    }
}
