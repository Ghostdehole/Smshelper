package com.ghost.smshelper

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupUiPreferencesTest {
    private fun source() = JSONObject("""{
        "favorites":["first"],"recent":["first"],"order":["first"],"cards":["account"],"hiddenCards":[],
        "custom":[],"groups":{"7":{"commands":["first"],"intervalSeconds":10}},"lastQuery":{"7":123},
        "categoryColors":{"常用":"#FF98AB"}
    }""")

    @Test fun restoringWithoutMappingNeverReusesForeignSubscriptionIds() {
        val result = BackupUiPreferences.restore(JSONObject(), source(), false, emptyMap())
        assertEquals(0, result.getJSONObject("groups").length())
        assertEquals(0, result.getJSONObject("lastQuery").length())
        assertEquals("first", result.getJSONArray("favorites").getString(0))
        assertEquals("#FF98AB", result.getJSONObject("categoryColors").getString("常用"))
    }

    @Test fun explicitMappingMovesQueryGroupToChosenSim() {
        val result = BackupUiPreferences.restore(JSONObject(), source(), false, mapOf(7 to 12))
        assertFalse(result.getJSONObject("groups").has("7"))
        assertEquals(10, result.getJSONObject("groups").getJSONObject("12").getInt("intervalSeconds"))
        assertEquals(123, result.getJSONObject("lastQuery").getLong("12"))
    }

    @Test fun mergePreservesLocalGroupAndDeduplicatesFavorites() {
        val existing = source()
        existing.getJSONObject("groups").getJSONObject("7").put("intervalSeconds", 30)
        val result = BackupUiPreferences.restore(existing, source(), true, mapOf(7 to 7))
        assertEquals(30, result.getJSONObject("groups").getJSONObject("7").getInt("intervalSeconds"))
        assertEquals(1, result.getJSONArray("favorites").length())
    }

    @Test fun secretOrUnrecognizedPreferencesCannotBeRestored() {
        val invalid = source().put("pin_code", "1234")
        assertThrows(IllegalArgumentException::class.java) { BackupUiPreferences.validate(invalid) }
    }
}
