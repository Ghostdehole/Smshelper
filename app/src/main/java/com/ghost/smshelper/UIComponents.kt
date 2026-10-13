@file:Suppress("ASSIGNED_BUT_NEVER_ACCESSED_VARIABLE", "UNUSED_VALUE")
@file:OptIn(ExperimentalFoundationApi::class)
package com.ghost.smshelper

import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun CartoonCard(modifier: Modifier = Modifier, backgroundColor: Color = AppTheme.colors.surface, onLongClick: (() -> Unit)? = null, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val haptic = actionHaptics()
    val palette = AppTheme.colors
    val reduceMotion by AppFeedbackPreferences.reduceMotion.collectAsState()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val animatedScale by animateFloatAsState(targetValue = if (!reduceMotion && isPressed && (onClick != null || onLongClick != null)) 0.96f else 1f, animationSpec = spring(stiffness = 300f, dampingRatio = 0.7f), label = "")
    Box(
        modifier = modifier.graphicsLayer { scaleX = if (reduceMotion) 1f else animatedScale; scaleY = if (reduceMotion) 1f else animatedScale }.padding(4.dp)
            .then(if (onClick != null || onLongClick != null) Modifier.combinedClickable(interactionSource = interactionSource, indication = null, onClick = { if (onClick != null) { haptic(HapticEvent.Tap); onClick() } }, onLongClick = onLongClick?.let { action -> { haptic(HapticEvent.LongPress); action() } }) else Modifier)
    ) {
        Box(modifier = Modifier.matchParentSize().offset(4.dp, 4.dp).background(palette.shadow, RoundedCornerShape(16.dp)))
        Box(modifier = Modifier.background(backgroundColor, RoundedCornerShape(16.dp)).border(2.dp, palette.outline, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))) { CompositionLocalProvider(LocalContentColor provides palette.text) { content() } }
    }
}

@Composable
fun CartoonButton(text: String, modifier: Modifier = Modifier, color: Color = AppTheme.colors.button, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val haptic = actionHaptics()
    val palette = AppTheme.colors
    val reduceMotion by AppFeedbackPreferences.reduceMotion.collectAsState()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val animatedScale by animateFloatAsState(targetValue = if (!reduceMotion && isPressed) 0.96f else 1f, animationSpec = spring(stiffness = 400f, dampingRatio = 0.6f), label = "")
    Box(modifier = modifier.graphicsLayer { scaleX = if (reduceMotion) 1f else animatedScale; scaleY = if (reduceMotion) 1f else animatedScale }.combinedClickable(interactionSource = interactionSource, indication = null, onClick = { haptic(HapticEvent.Tap); onClick() }, onLongClick = onLongClick?.let { action -> { haptic(HapticEvent.LongPress); action() } })) {
        Box(modifier = Modifier.matchParentSize().offset(2.dp, 3.dp).background(palette.shadow, RoundedCornerShape(12.dp)))
        Box(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).background(color, RoundedCornerShape(12.dp)).border(2.dp, palette.outline, RoundedCornerShape(12.dp)).padding(vertical = 10.dp, horizontal = 4.dp), contentAlignment = Alignment.Center) {
            val labelColor = when (color) {
                palette.button -> palette.buttonText
                palette.primary -> palette.onPrimary
                palette.secondary -> palette.onSecondary
                else -> Color(ThemeColorLogic.readableText(color.toArgb().toLong() and 0xFFFFFFFFL))
            }
            Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = labelColor, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun PinLockScreen(targetPin: String, onUnlock: () -> Unit) {
    val haptic = actionHaptics()
    val palette = AppTheme.colors
    val reduceMotion by AppFeedbackPreferences.reduceMotion.collectAsState()
    var input by remember { mutableStateOf("") }
    var rejecting by remember { mutableStateOf(false) }
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // Older versions accepted arbitrary characters, so retain an unlock path
    // for those existing credentials without weakening or silently removing them.
    if (!targetPin.matches(Regex("[0-9]{4}"))) {
        var incorrect by remember { mutableStateOf(false) }
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.Lock, "Lock", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(24.dp))
                Text("请输入旧版安全密码", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(value = input, onValueChange = { input = it; incorrect = false }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation(), isError = incorrect)
                if (incorrect) Text("密码不正确", color = MaterialTheme.colorScheme.error)
                TextButton(enabled = input.isNotEmpty(), onClick = {
                    if (input == targetPin) { haptic(HapticEvent.Confirm); onUnlock() }
                    else { haptic(HapticEvent.Reject); incorrect = true }
                }) { Text("解锁") }
                Text("解锁后可在安全认证中重新设置四位数字密码。", fontSize = 12.sp, color = palette.mutedText)
            }
        }
        return
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Lock, "Lock", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
            Spacer(modifier = Modifier.height(24.dp))
            Text("请输入安全访问密码", color = palette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(32.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.offset(x = offsetX.value.dp)) {
                for (i in 0 until 4) {
                    Box(modifier = Modifier.size(24.dp).background(if (i < input.length) MaterialTheme.colorScheme.primary else palette.surfaceAlt, CircleShape).border(1.dp, palette.outline, CircleShape))
                }
            }
            Spacer(modifier = Modifier.height(48.dp))

            val keys = listOf(listOf("1","2","3"), listOf("4","5","6"), listOf("7","8","9"), listOf("", "0", "DEL"))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                keys.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                        row.forEach { key ->
                            if (key.isEmpty()) { Spacer(modifier = Modifier.size(72.dp)) }
                            else {
                                Box(
                                    modifier = Modifier.size(72.dp).background(palette.button, CircleShape).clip(CircleShape).clickable {
                                        if (rejecting) return@clickable
                                        if (key == "DEL") { if (input.isNotEmpty()) { haptic(HapticEvent.Tap); input = input.dropLast(1) } }
                                        else if (input.length < 4) {
                                            input += key
                                            if (input.length == 4) {
                                                if (input == targetPin) { haptic(HapticEvent.Confirm); onUnlock() }
                                                else {
                                                    haptic(HapticEvent.Reject)
                                                    rejecting = true
                                                    scope.launch {
                                                        if (!reduceMotion) {
                                                            offsetX.animateTo(20f, spring(dampingRatio = 0.2f, stiffness = 2000f))
                                                            offsetX.animateTo(0f, spring(dampingRatio = 0.2f, stiffness = 2000f))
                                                        }
                                                        input = ""
                                                        rejecting = false
                                                    }
                                                }
                                            } else haptic(HapticEvent.Tap)
                                        }
                                    }, contentAlignment = Alignment.Center
                                ) {
                                    if (key == "DEL") Icon(Icons.Default.Clear, "DEL", tint = palette.buttonText)
                                    else Text(key, color = palette.buttonText, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A single gesture owner handles both click and long-click, without a nested FAB click target. */
@Composable
fun InboxActionButton(isSearching: Boolean, size: Dp = 56.dp, onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptic = actionHaptics()
    val palette = AppTheme.colors
    Surface(
        modifier = Modifier.size(size).combinedClickable(onClick = { haptic(HapticEvent.Tap); onClick() }, onLongClick = { haptic(HapticEvent.LongPress); onLongClick() }),
        shape = if (size == 40.dp) CircleShape else RoundedCornerShape(16.dp),
        color = if (isSearching) palette.danger else palette.primary, contentColor = if (isSearching) Color(ThemeColorLogic.readableText(palette.danger.toArgb().toLong() and 0xFFFFFFFFL)) else palette.onPrimary, shadowElevation = 6.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(if (isSearching) Icons.Default.Clear else Icons.Default.Search, if (isSearching) "清除搜索" else "返回顶部，长按搜索")
        }
    }
}
