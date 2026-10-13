package com.ghost.smshelper

import org.junit.Assert.*
import org.junit.Test

class SimRestorePlanTest {
    private val local = SimPreference(1, "本机卡", profileId = "current", identifier = "CMCC")
    private val foreign = SimPreference(1, "另一设备", profileId = "old", identifier = "CMCC")

    @Test fun rollbackKeepsForeignPendingEvenWhenNumericIdsCollide() {
        val restored = SimPreferences.restorePlan(emptyMap(), emptyList(), listOf(local) to listOf(foreign),
            merge = false, restoreBindings = true, subscriptionMapping = mapOf(1 to 1), profileIdMapping = emptyMap())
        assertEquals(local, restored.first[1])
        assertEquals(listOf(foreign), restored.second)
    }

    @Test fun crossDeviceImportNeverReusesAnUnconfirmedSubscriptionId() {
        val restored = SimPreferences.restorePlan(emptyMap(), emptyList(), listOf(foreign) to emptyList(),
            merge = false, restoreBindings = false, subscriptionMapping = emptyMap(), profileIdMapping = mapOf("old" to "imported"))
        assertTrue(restored.first.isEmpty())
        assertEquals("imported", restored.second.single().profileId)
    }

    @Test fun explicitMappingDoesNotAlsoBindAmbiguousPendingCard() {
        val restored = SimPreferences.restorePlan(emptyMap(), emptyList(), listOf(local) to listOf(foreign),
            merge = false, restoreBindings = false, subscriptionMapping = mapOf(1 to 9), profileIdMapping = emptyMap())
        assertEquals(local.copy(subId = 9), restored.first[9])
        assertEquals(listOf(foreign), restored.second)
    }

    @Test fun mergeRetainsLocalSettingsAndKeepsConflictingImportForReview() {
        val restored = SimPreferences.restorePlan(mapOf(1 to local), emptyList(), listOf(foreign) to emptyList(),
            merge = true, restoreBindings = false, subscriptionMapping = mapOf(1 to 1), profileIdMapping = mapOf("old" to "imported"))
        assertEquals(local, restored.first[1])
        assertEquals(foreign.copy(profileId = "imported"), restored.second.single())
    }

    @Test fun capacityOverflowFailsInsteadOfSilentlyDroppingCards() {
        val pending = (1..128).map { SimPreference(it, "已有 $it") }
        assertTrue(runCatching {
            SimPreferences.restorePlan(emptyMap(), pending, listOf(foreign) to emptyList(), true, false, emptyMap(), emptyMap())
        }.isFailure)
    }
}
