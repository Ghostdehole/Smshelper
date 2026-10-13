package com.ghost.smshelper

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView

// The pinned Foundation 1.7.x does not emit long-press feedback itself.
// Keep the interaction tests when upgrading Compose to detect duplicate feedback.
enum class HapticEvent { Tap, LongPress, Confirm, Reject }

/** An optional dispatcher makes the same production interaction paths testable. */
val LocalHapticDispatcher = staticCompositionLocalOf<((HapticEvent) -> Unit)?> { null }

@Composable
fun actionHaptics(): (HapticEvent) -> Unit {
    val view = LocalView.current
    val dispatcher = LocalHapticDispatcher.current
    return { event ->
        if (dispatcher != null) dispatcher(event)
        else {
            val feedback = when {
                event == HapticEvent.LongPress -> HapticFeedbackConstants.LONG_PRESS
                Build.VERSION.SDK_INT >= 30 && event == HapticEvent.Confirm -> HapticFeedbackConstants.CONFIRM
                Build.VERSION.SDK_INT >= 30 && event == HapticEvent.Reject -> HapticFeedbackConstants.REJECT
                else -> HapticFeedbackConstants.KEYBOARD_TAP
            }
            // No override flags: respect the view and system haptic settings.
            view.performHapticFeedback(feedback)
        }
    }
}

@Composable
fun HapticIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    val haptic = actionHaptics()
    IconButton(onClick = { haptic(HapticEvent.Tap); onClick() }, modifier = modifier, enabled = enabled, content = content)
}

@Composable
fun HapticTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val haptic = actionHaptics()
    TextButton(onClick = { haptic(HapticEvent.Tap); onClick() }, modifier = modifier, enabled = enabled, content = content)
}

@Composable
fun HapticSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    val haptic = actionHaptics()
    Switch(checked = checked, onCheckedChange = onCheckedChange?.let { change -> { value: Boolean -> haptic(HapticEvent.Tap); change(value) } }, modifier = modifier)
}
