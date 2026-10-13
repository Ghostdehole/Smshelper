package com.ghost.smshelper

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class RemindersPolicyTest {
    @Test fun overnightQuietHoursIncludeStartButNotEnd() {
        val options = ReminderOptions(quietStart = 22, quietEnd = 8)
        assertTrue(ReminderPolicy.quiet(22, options))
        assertTrue(ReminderPolicy.quiet(0, options))
        assertTrue(ReminderPolicy.quiet(7, options))
        assertFalse(ReminderPolicy.quiet(8, options))
        assertFalse(ReminderPolicy.quiet(21, options))
    }

    @Test fun equalQuietHoursDoNotSilenceTheEntireDay() {
        assertFalse(ReminderPolicy.quiet(12, ReminderOptions(quietStart = 12, quietEnd = 12)))
        assertFalse(ReminderPolicy.quiet(23, ReminderOptions(quietEnabled = false)))
    }

    @Test fun repeatCooldownWorksAcrossClockCorrection() {
        assertFalse(ReminderPolicy.repeatAllowed(5_000, 4_000, 24))
        assertTrue(ReminderPolicy.repeatAllowed(86_404_000, 4_000, 24))
        assertTrue(ReminderPolicy.repeatAllowed(3_000, 4_000, 24))
    }

    @Test fun billingDayUsesMonthEndForShortMonthsAndLeapYears() {
        assertTrue(ReminderPolicy.billingDay(LocalDate.of(2026, 2, 28), 31))
        assertTrue(ReminderPolicy.billingDay(LocalDate.of(2024, 2, 29), 31))
        assertFalse(ReminderPolicy.billingDay(LocalDate.of(2024, 2, 28), 31))
    }

    @Test fun unknownOrNonFiniteValuesNeverGenerateThresholdAlerts() {
        assertFalse(ReminderPolicy.below(null, 10.0))
        assertFalse(ReminderPolicy.below(Double.NaN, 10.0))
        assertFalse(ReminderPolicy.below(Double.POSITIVE_INFINITY, 10.0))
        assertTrue(ReminderPolicy.below(0.0, 10.0))
        assertTrue(ReminderPolicy.below(-2.0, 10.0))
    }

    @Test fun optionsRoundTripAndRejectOutOfRangeValues() {
        val original = ReminderOptions(enabled = true, queryIntervalDays = 14, billReminder = true)
        assertEquals(original, ReminderOptions.decode(original.encode()))
        assertThrows(IllegalArgumentException::class.java) { ReminderOptions(quietEnd = 24).validate() }
        assertThrows(IllegalArgumentException::class.java) { ReminderOptions(dataThresholdMb = Double.NaN).validate() }
    }
}
