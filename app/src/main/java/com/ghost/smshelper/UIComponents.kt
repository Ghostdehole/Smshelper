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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun CartoonCard(modifier: Modifier = Modifier, backgroundColor: Color = Color.White, onLongClick: (() -> Unit)? = null, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val animatedScale by animateFloatAsState(targetValue = if (isPressed && (onClick != null || onLongClick != null)) 0.96f else 1f, animationSpec = spring(stiffness = 300f, dampingRatio = 0.7f), label = "")
    Box(
        modifier = modifier.graphicsLayer { scaleX = animatedScale; scaleY = animatedScale }.padding(4.dp)
            .then(if (onClick != null || onLongClick != null) Modifier.combinedClickable(interactionSource = interactionSource, indication = null, onClick = onClick ?: {}, onLongClick = onLongClick) else Modifier)
    ) {
        Box(modifier = Modifier.matchParentSize().offset(4.dp, 4.dp).background(Color(0xFF2D3436), RoundedCornerShape(16.dp)))
        Box(modifier = Modifier.background(backgroundColor, RoundedCornerShape(16.dp)).border(2.dp, Color(0xFF2D3436), RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))) { content() }
    }
}

@Composable
fun CartoonButton(text: String, modifier: Modifier = Modifier, color: Color = Color.White, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val animatedScale by animateFloatAsState(targetValue = if (isPressed) 0.92f else 1f, animationSpec = spring(stiffness = 400f, dampingRatio = 0.6f), label = "")
    Box(modifier = modifier.graphicsLayer { scaleX = animatedScale; scaleY = animatedScale }.combinedClickable(interactionSource = interactionSource, indication = null, onClick = onClick, onLongClick = onLongClick)) {
        Box(modifier = Modifier.matchParentSize().offset(2.dp, 3.dp).background(Color(0xFF2D3436), RoundedCornerShape(12.dp)))
        Box(modifier = Modifier.fillMaxWidth().background(color, RoundedCornerShape(12.dp)).border(2.dp, Color(0xFF2D3436), RoundedCornerShape(12.dp)).padding(vertical = 10.dp, horizontal = 4.dp), contentAlignment = Alignment.Center) {
            Text(text, fontWeight = FontWeight.Black, fontSize = 13.sp, color = Color(0xFF2D3436))
        }
    }
}

@Composable
fun PinLockScreen(targetPin: String, onUnlock: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Lock, "Lock", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
            Spacer(modifier = Modifier.height(24.dp))
            Text("请输入安全访问密码", color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(32.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.offset(x = offsetX.value.dp)) {
                for (i in 0 until 4) {
                    Box(modifier = Modifier.size(24.dp).background(if (i < input.length) MaterialTheme.colorScheme.primary else Color.LightGray, CircleShape))
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
                                    modifier = Modifier.size(72.dp).background(Color.Black.copy(alpha = 0.05f), CircleShape).clip(CircleShape).clickable {
                                        if (key == "DEL") { if (input.isNotEmpty()) input = input.dropLast(1) }
                                        else if (input.length < 4) {
                                            input += key
                                            if (input.length == 4) {
                                                if (input == targetPin) onUnlock()
                                                else {
                                                    scope.launch {
                                                        offsetX.animateTo(20f, spring(dampingRatio = 0.2f, stiffness = 2000f))
                                                        offsetX.animateTo(0f, spring(dampingRatio = 0.2f, stiffness = 2000f))
                                                        input = ""
                                                    }
                                                }
                                            }
                                        }
                                    }, contentAlignment = Alignment.Center
                                ) {
                                    if (key == "DEL") Icon(Icons.Default.Clear, "DEL", tint = Color.Black)
                                    else Text(key, color = Color.Black, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}