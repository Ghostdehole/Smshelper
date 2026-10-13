package com.ghost.smshelper

import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppFeedbackPreferences {
    private val mutableHaptics = MutableStateFlow(true)
    private val mutableMotion = MutableStateFlow(false)
    val hapticsEnabled = mutableHaptics.asStateFlow()
    val reduceMotion = mutableMotion.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("SmsHelper_Prefs", Context.MODE_PRIVATE)
        mutableHaptics.value = prefs.getBoolean("haptics_enabled", true)
        mutableMotion.value = prefs.getBoolean("reduce_motion", false)
    }

    fun setHapticsEnabled(context: Context, enabled: Boolean) {
        check(context.getSharedPreferences("SmsHelper_Prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("haptics_enabled", enabled).commit()) { "触感设置保存失败" }
        mutableHaptics.value = enabled
    }

    fun setReduceMotion(context: Context, enabled: Boolean) {
        check(context.getSharedPreferences("SmsHelper_Prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("reduce_motion", enabled).commit()) { "动画设置保存失败" }
        mutableMotion.value = enabled
    }
}

// The pinned Foundation 1.7.x does not emit long-press feedback itself.
// Keep the interaction tests when upgrading Compose to detect duplicate feedback.
enum class HapticEvent { Tap, LongPress, Confirm, Reject }

/** An optional dispatcher makes the same production interaction paths testable. */
val LocalHapticDispatcher = staticCompositionLocalOf<((HapticEvent) -> Unit)?> { null }

@Composable
fun actionHaptics(): (HapticEvent) -> Unit {
    val view = LocalView.current
    val dispatcher = LocalHapticDispatcher.current
    val enabled = AppFeedbackPreferences.hapticsEnabled.collectAsState().value
    return { event ->
        if (!enabled) Unit
        else if (dispatcher != null) dispatcher(event)
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
fun HapticTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding, content: @Composable RowScope.() -> Unit) {
    val haptic = actionHaptics()
    TextButton(onClick = { haptic(HapticEvent.Tap); onClick() }, modifier = modifier, enabled = enabled, contentPadding = contentPadding, content = content)
}

@Composable
fun HapticSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    val haptic = actionHaptics()
    Switch(checked = checked, onCheckedChange = onCheckedChange?.let { change -> { value: Boolean -> haptic(HapticEvent.Tap); change(value) } }, modifier = modifier)
}
