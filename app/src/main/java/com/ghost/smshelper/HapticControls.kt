package com.ghost.smshelper

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun HapticButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                 colors: ButtonColors = ButtonDefaults.buttonColors(), contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
                 content: @Composable RowScope.() -> Unit) {
    val haptic = actionHaptics()
    Button(onClick = { haptic(HapticEvent.Tap); onClick() }, modifier = modifier, enabled = enabled,
        colors = colors, contentPadding = contentPadding, content = content)
}

@Composable
fun HapticOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                         contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
                         content: @Composable RowScope.() -> Unit) {
    val haptic = actionHaptics()
    OutlinedButton(onClick = { haptic(HapticEvent.Tap); onClick() }, modifier = modifier, enabled = enabled, contentPadding = contentPadding, content = content)
}

@Composable
fun HapticFilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit,
                     modifier: Modifier = Modifier, enabled: Boolean = true,
                     leadingIcon: @Composable (() -> Unit)? = null, trailingIcon: @Composable (() -> Unit)? = null) {
    val haptic = actionHaptics()
    FilterChip(selected = selected, onClick = { haptic(HapticEvent.Tap); onClick() }, label = label,
        modifier = modifier, enabled = enabled, leadingIcon = leadingIcon, trailingIcon = trailingIcon)
}

@Composable
fun HapticAssistChip(onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier,
                     enabled: Boolean = true, leadingIcon: @Composable (() -> Unit)? = null) {
    val haptic = actionHaptics()
    AssistChip(onClick = { haptic(HapticEvent.Tap); onClick() }, label = label, modifier = modifier,
        enabled = enabled, leadingIcon = leadingIcon)
}
