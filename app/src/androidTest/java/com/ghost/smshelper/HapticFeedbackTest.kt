package com.ghost.smshelper

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HapticFeedbackTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<HapticEvent>()
    private val frameworkEvents = mutableListOf<HapticFeedbackType>()
    private val frameworkHaptics = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            frameworkEvents += hapticFeedbackType
        }
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(
                LocalHapticDispatcher provides { event -> events += event },
                LocalHapticFeedback provides frameworkHaptics
            ) {
                MaterialTheme(content = content)
            }
        }
    }

    @Test
    fun cartoonButtonClickAndLongClickEachDispatchOnceAndKeepTheirActions() {
        val actions = mutableListOf<String>()
        show {
            CartoonButton(
                text = "查询",
                modifier = Modifier.width(180.dp).testTag("command"),
                onClick = { actions += "click" },
                onLongClick = { actions += "longClick" }
            )
        }

        compose.onNodeWithTag("command").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(listOf("click"), actions)
            assertEquals(listOf(HapticEvent.Tap), events)
        }
        compose.onNodeWithTag("command").performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(listOf("click", "longClick"), actions)
            assertEquals(listOf(HapticEvent.Tap, HapticEvent.LongPress), events)
            assertTrue("Framework feedback must not duplicate explicit feedback", frameworkEvents.isEmpty())
        }
    }

    @Test
    fun iconButtonDispatchesOneTapAndDisabledButtonDispatchesNothing() {
        var clicks = 0
        show {
            var enabled by remember { mutableStateOf(true) }
            HapticIconButton(
                modifier = Modifier.testTag("icon"),
                enabled = enabled,
                onClick = { clicks++; enabled = false }
            ) { Text("设置") }
        }

        compose.onNodeWithTag("icon").performTouchInput { click() }
        compose.onNodeWithTag("icon").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, clicks)
            assertEquals(listOf(HapticEvent.Tap), events)
            assertTrue(frameworkEvents.isEmpty())
        }
    }

    @Test
    fun switchTogglesStateAndDispatchesExactlyOneTapPerChange() {
        val changes = mutableListOf<Boolean>()
        show {
            var checked by remember { mutableStateOf(false) }
            HapticSwitch(
                checked = checked,
                modifier = Modifier.testTag("compatibility"),
                onCheckedChange = { checked = it; changes += it }
            )
        }

        compose.onNodeWithTag("compatibility").assertIsOff().performTouchInput { click() }
        compose.onNodeWithTag("compatibility").assertIsOn().performTouchInput { click() }
        compose.onNodeWithTag("compatibility").assertIsOff()
        compose.runOnIdle {
            assertEquals(listOf(true, false), changes)
            assertEquals(listOf(HapticEvent.Tap, HapticEvent.Tap), events)
            assertTrue(frameworkEvents.isEmpty())
        }
    }

    @Test
    fun correctPinDispatchesThreeTapsAndOneConfirmationAndUnlocksOnce() {
        var unlocks = 0
        show { PinLockScreen(targetPin = "1234", onUnlock = { unlocks++ }) }

        listOf("1", "2", "3", "4").forEach { digit ->
            compose.onNodeWithText(digit).performTouchInput { click() }
        }
        compose.runOnIdle {
            assertEquals(1, unlocks)
            assertEquals(listOf(HapticEvent.Tap, HapticEvent.Tap, HapticEvent.Tap, HapticEvent.Confirm), events)
            assertTrue(frameworkEvents.isEmpty())
        }
    }

    @Test
    fun incorrectPinRejectsOnceWithoutUnlockingAndAcceptsANewAttempt() {
        var unlocks = 0
        show { PinLockScreen(targetPin = "1234", onUnlock = { unlocks++ }) }

        listOf("1", "2", "3", "5").forEach { digit ->
            compose.onNodeWithText(digit).performTouchInput { click() }
        }
        compose.runOnIdle {
            assertEquals(0, unlocks)
            assertEquals(listOf(HapticEvent.Tap, HapticEvent.Tap, HapticEvent.Tap, HapticEvent.Reject), events)
        }
        listOf("1", "2", "3", "4").forEach { digit ->
            compose.onNodeWithText(digit).performTouchInput { click() }
        }
        compose.runOnIdle {
            assertEquals(1, unlocks)
            assertEquals(
                listOf(HapticEvent.Tap, HapticEvent.Tap, HapticEvent.Tap, HapticEvent.Reject,
                    HapticEvent.Tap, HapticEvent.Tap, HapticEvent.Tap, HapticEvent.Confirm),
                events
            )
            assertTrue(frameworkEvents.isEmpty())
        }
    }

    @Test
    fun inboxActionKeepsClickAndLongClickDistinctWithOneFeedbackEach() {
        val actions = mutableListOf<String>()
        show {
            InboxActionButton(
                isSearching = false,
                onClick = { actions += "scroll" },
                onLongClick = { actions += "search" }
            )
        }

        val button = compose.onNodeWithContentDescription("返回顶部，长按搜索")
        button.performTouchInput { click() }
        button.performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(listOf("scroll", "search"), actions)
            assertEquals(listOf(HapticEvent.Tap, HapticEvent.LongPress), events)
            assertTrue(frameworkEvents.isEmpty())
        }
    }
}
