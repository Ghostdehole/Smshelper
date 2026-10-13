@file:Suppress("DEPRECATION", "UNUSED_VARIABLE")
@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
package com.ghost.smshelper

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HomeScreen(onNavigateToSettings: () -> Unit, onNavigateToSecretMenu: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("SmsHelper_Prefs", Context.MODE_PRIVATE)
    val haptic = actionHaptics()
    fun useSystemSender() = prefs.getBoolean("compat_mode", false)
    var lastQueryTime by rememberSaveable { mutableStateOf(prefs.getString("last_query_time", prefs.getString("last_sync_time", "从未"))) }

    var showFullScreenInbox by rememberSaveable { mutableStateOf(false) }
    var backPressedTime by remember { mutableLongStateOf(0L) }
    BackHandler {
        if (showFullScreenInbox) showFullScreenInbox = false
        else if (System.currentTimeMillis() - backPressedTime < 2000) (context as? Activity)?.finish()
        else { backPressedTime = System.currentTimeMillis(); Toast.makeText(context, "再按一次退出应用", Toast.LENGTH_SHORT).show() }
    }

    val parsedInfo by SmsRepository.parsedInfo.collectAsState()
    val messages by SmsRepository.messages.collectAsState()
    val unreadCount by SmsRepository.unreadCount.collectAsState()
    val activeConfig by ConfigManager.activeConfig.collectAsState()
    val isLegacyAccount by SmsRepository.isLegacyAccount.collectAsState()

    var simList by remember { mutableStateOf(getActiveSims(context)) }
    var selectedSim by remember { mutableStateOf(simList.find { it.subId == prefs.getInt("selected_sim_subid", -1) } ?: simList.firstOrNull()) }

    val showDashboard = rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(unreadCount, showDashboard.value, showFullScreenInbox) {
        if (unreadCount > 0 && (showDashboard.value || showFullScreenInbox) && (context as? Activity)?.hasWindowFocus() == true) {
            SmsRepository.clearUnread(context)
        }
    }

    val showInputDialog = remember { mutableStateOf<ActionCommand?>(null) }
    var inputText by rememberSaveable { mutableStateOf("") }
    var inputTargetSubId by rememberSaveable { mutableIntStateOf(-1) }
    var inputTargetNumber by rememberSaveable { mutableStateOf("") }
    val showTrafficDialog = remember { mutableStateOf(false) }
    val showBillsDialog = remember { mutableStateOf(false) }
    val showSimInfoDialog = remember { mutableStateOf<SimInfo?>(null) }
    var syncCommandsQueue by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var currentSyncIndex by rememberSaveable { mutableIntStateOf(-1) }
    var syncSubId by rememberSaveable { mutableIntStateOf(-1) }
    var syncTargetNumber by rememberSaveable { mutableStateOf("") }
    var syncAccepted by rememberSaveable { mutableStateOf(false) }
    fun dispatchSync(command: String): Boolean {
        val sim = getActiveSims(context).find { it.subId == syncSubId }
        if (sim == null) {
            Toast.makeText(context, "同步所选SIM卡已不可用", Toast.LENGTH_SHORT).show()
            return false
        }
        val activity = context as MainActivity
        return if (useSystemSender()) activity.sendSmsViaSystem(command, sim, syncTargetNumber)
        else activity.sendSmsSilently(command, sim.subId, syncTargetNumber, false)
    }
    val coroutineScope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms[android.Manifest.permission.READ_PHONE_STATE] == true || androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            simList = getActiveSims(context)
            val savedSubId = prefs.getInt("selected_sim_subid", -1)
            val simToSelect = simList.find { it.subId == savedSubId } ?: simList.firstOrNull()
            selectedSim = simToSelect
            SmsRepository.selectSubscription(context, simToSelect?.subId)
            if (simToSelect != null) ConfigManager.switchCarrier(simToSelect.mnc, simToSelect.carrierName, simToSelect.mcc)
        }
    }

    LaunchedEffect(Unit) {
        val requiredPerms = arrayOf(android.Manifest.permission.SEND_SMS, android.Manifest.permission.RECEIVE_SMS, android.Manifest.permission.READ_SMS, android.Manifest.permission.READ_PHONE_STATE)
        if (requiredPerms.all { androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            simList = getActiveSims(context)
            val savedSubId = prefs.getInt("selected_sim_subid", -1)
            val simToSelect = simList.find { it.subId == savedSubId } ?: simList.firstOrNull()
            selectedSim = simToSelect
            SmsRepository.selectSubscription(context, simToSelect?.subId)
            if (simToSelect != null) ConfigManager.switchCarrier(simToSelect.mnc, simToSelect.carrierName, simToSelect.mcc)
        } else permissionLauncher.launch(requiredPerms)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(selectedSim?.carrierName ?: "短信助手", fontWeight = FontWeight.Black, fontSize = 22.sp) },
                    actions = {
                        HapticIconButton(onClick = {
                            if (selectedSim != null && activeConfig != null && activeConfig!!.syncCommands.isNotEmpty()) {
                                syncCommandsQueue = ArrayList(activeConfig!!.syncCommands)
                                syncSubId = selectedSim!!.subId
                                syncTargetNumber = activeConfig!!.targetNumber
                                currentSyncIndex = 0
                                syncAccepted = dispatchSync(syncCommandsQueue[0])
                            } else Toast.makeText(context, "请选择有效SIM卡和同步规则", Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Default.Refresh, "同步", tint = Color.Black) }
                        HapticIconButton(onClick = onNavigateToSettings) { Icon(Icons.Default.Settings, "设置", tint = Color.Black) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            },
            containerColor = Color.Transparent
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                CartoonCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(top = 12.dp, bottom = 16.dp, start = 16.dp, end = 16.dp)) {
                        Text("最后查询: $lastQueryTime${if (isLegacyAccount) " · 旧版未分卡数据" else ""}", fontSize = 10.sp, color = Color.Gray, modifier = Modifier.align(Alignment.End))
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(95.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { haptic(HapticEvent.Tap); showTrafficDialog.value = true }) {
                                val progress = if (parsedInfo.usedDataMb + parsedInfo.remainingTotalDataMb > 0) (parsedInfo.remainingTotalDataMb / (parsedInfo.usedDataMb + parsedInfo.remainingTotalDataMb)).toFloat() else 0f
                                val animProgress by animateFloatAsState(targetValue = progress, animationSpec = spring(stiffness = Spring.StiffnessVeryLow), label = "")
                                CircularProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxSize(), color = Color(0xFFEEEEEE), strokeWidth = 10.dp)
                                CircularProgressIndicator(progress = { animProgress }, modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.secondary, strokeWidth = 10.dp)
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("通用剩余", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                                    Text("${String.format(Locale.getDefault(), "%.2f", parsedInfo.generalDataMb / 1024.0)}GB", fontSize = 16.sp, fontWeight = FontWeight.Black, color = Color.Black)
                                }
                            }
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(95.dp)) {
                                val voiceProg = if (parsedInfo.totalVoice > 0) (parsedInfo.remainingVoice.toFloat() / parsedInfo.totalVoice) else 0f
                                val animVoice by animateFloatAsState(targetValue = voiceProg, animationSpec = spring(stiffness = Spring.StiffnessVeryLow), label = "")
                                CircularProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxSize(), color = Color(0xFFEEEEEE), strokeWidth = 10.dp)
                                CircularProgressIndicator(progress = { animVoice }, modifier = Modifier.fillMaxSize(), color = Color(0xFF00B894), strokeWidth = 10.dp)
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("语音剩余", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                                    Text("${parsedInfo.remainingVoice}分", fontSize = 16.sp, fontWeight = FontWeight.Black, color = Color.Black)
                                }
                            }
                            Column(horizontalAlignment = Alignment.End, modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { haptic(HapticEvent.Tap); showBillsDialog.value = true }) {
                                Text("账户余额", fontSize = 13.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                                Text("¥ ${parsedInfo.balance}", fontSize = 26.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("本月已消费 ¥${parsedInfo.consumption}", fontSize = 12.sp, color = Color.DarkGray, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                if (simList.isNotEmpty()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        simList.forEach { sim ->
                            val isSelected = selectedSim?.subId == sim.subId
                            Box(
                                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (isSelected) Color.Black else Color.Transparent)
                                    .border(2.dp, Color.Black, RoundedCornerShape(12.dp)).combinedClickable(
                                        onClick = { haptic(HapticEvent.Tap); selectedSim = sim; prefs.edit { putInt("selected_sim_subid", sim.subId) }; SmsRepository.selectSubscription(context, sim.subId); ConfigManager.switchCarrier(sim.mnc, sim.carrierName, sim.mcc) },
                                        onLongClick = { haptic(HapticEvent.LongPress); showSimInfoDialog.value = sim }
                                    ).padding(horizontal = 12.dp, vertical = 6.dp)
                            ) { Text("卡${sim.slotIndex + 1}: ${sim.displayName}", color = if (isSelected) Color.White else Color.Black, fontWeight = FontWeight.Bold) }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                    BadgedBox(badge = { if (unreadCount > 0 && !showDashboard.value) Badge { Text("$unreadCount") } }) {
                        CartoonButton(
                            text = if (showDashboard.value) "收起小信箱 ▲" else "展开小信箱 ▼",
                            color = Color(0xFFFFCC80),
                            onClick = { showDashboard.value = !showDashboard.value; if (showDashboard.value) SmsRepository.clearUnread(context) },
                            onLongClick = { showFullScreenInbox = true; SmsRepository.clearUnread(context) }
                        )
                    }
                }

                AnimatedVisibility(visible = showDashboard.value, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    val listState = rememberLazyListState()
                    var showAdvSearch by remember { mutableStateOf(false) }
                    var qNumber by remember { mutableStateOf("") }
                    var qBody by remember { mutableStateOf("") }
                    var qDate by remember { mutableStateOf("") }

                    val filtered = messages.filter {
                        (qNumber.isBlank() || it.sender.contains(qNumber)) &&
                                (qBody.isBlank() || it.body.contains(qBody)) &&
                                (qDate.isBlank() || it.time.contains(qDate))
                    }

                    Box(modifier = Modifier.fillMaxWidth().height(250.dp).padding(vertical = 8.dp)) {
                        CartoonCard(modifier = Modifier.fillMaxSize(), backgroundColor = Color.White) {
                            if (filtered.isEmpty()) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (messages.isEmpty()) "信箱空空如也~" else "没有匹配的信件~", color = Color.Gray) } }
                            else {
                                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
                                    items(filtered) { msg ->
                                        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFF1F2F6)).padding(10.dp)) {
                                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                                Text("来自: ${msg.sender}", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
                                                Text(msg.time, fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(msg.body, fontSize = 14.sp, lineHeight = 20.sp, color = Color.Black)
                                        }
                                    }
                                }
                            }
                        }
                        val showScroll by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || qNumber.isNotBlank() || qBody.isNotBlank() || qDate.isNotBlank() } }
                        androidx.compose.animation.AnimatedVisibility(visible = showScroll || messages.isNotEmpty(), modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp), enter = scaleIn(), exit = scaleOut()) {
                            val isSearching = qNumber.isNotBlank() || qBody.isNotBlank() || qDate.isNotBlank()
                            InboxActionButton(isSearching = isSearching, size = 40.dp,
                                onClick = { if (isSearching) { qNumber=""; qBody=""; qDate="" } else coroutineScope.launch { listState.animateScrollToItem(0) } },
                                onLongClick = { showAdvSearch = true })
                        }
                    }

                    if (showAdvSearch) {
                        AlertDialog(onDismissRequest = { showAdvSearch = false }, containerColor = Color.White, title = { Text("🔍 高级搜寻", fontWeight = FontWeight.Black) },
                            text = { Column {
                                OutlinedTextField(value = qNumber, onValueChange = { qNumber = it }, label = { Text("检索号码") }, singleLine = true)
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(value = qBody, onValueChange = { qBody = it }, label = { Text("包含关键词") }, singleLine = true)
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(value = qDate, onValueChange = { qDate = it }, label = { Text("日期(如: 2026-03-09)") }, singleLine = true)
                            }},
                            confirmButton = { HapticTextButton(onClick = { showAdvSearch = false }) { Text("查找", color = MaterialTheme.colorScheme.primary) } }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                    val displayedConfig = activeConfig
                    val displayedSubId = selectedSim?.subId
                    val categories = displayedConfig?.categories ?: emptyList()
                    items(categories) { category ->
                        Text(category.title, color = Color.Black, fontWeight = FontWeight.Black, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                        category.commands.chunked(2).forEach { rowCommands ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                rowCommands.forEach { cmd ->
                                    CartoonButton(text = cmd.name, modifier = Modifier.weight(1f).padding(bottom = 8.dp), color = Color(0xFFF1F2F6), onClick = {
                                        val sim = getActiveSims(context).find { it.subId == displayedSubId }
                                        val target = displayedConfig?.targetNumber
                                        if (sim == null || target.isNullOrBlank() || selectedSim?.subId != displayedSubId || activeConfig != displayedConfig) {
                                            Toast.makeText(context, "SIM卡或规则已变化，请重新选择", Toast.LENGTH_SHORT).show()
                                        } else if (cmd.requiresInput) {
                                            inputText = ""
                                            inputTargetSubId = sim.subId
                                            inputTargetNumber = target
                                            showInputDialog.value = cmd
                                        } else {
                                            val act = context as MainActivity
                                            if (useSystemSender()) act.sendSmsViaSystem(cmd.code, sim, target) else act.sendSmsSilently(cmd.code, sim.subId, target)
                                        }
                                    })
                                }
                                if (rowCommands.size == 1) Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        // ================= 大信箱满屏浮层 =================
        AnimatedVisibility(visible = showFullScreenInbox, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            var replyTarget by rememberSaveable { mutableStateOf<String?>(null) }
            var replyText by rememberSaveable { mutableStateOf("") }
            var replySubId by rememberSaveable { mutableIntStateOf(-1) }
            val fullListState = rememberLazyListState()

            var showAdvSearch by remember { mutableStateOf(false) }
            var qNumber by remember { mutableStateOf("") }
            var qBody by remember { mutableStateOf("") }
            var qDate by remember { mutableStateOf("") }

            val filtered = messages.filter {
                (qNumber.isBlank() || it.sender.contains(qNumber)) &&
                        (qBody.isBlank() || it.body.contains(qBody)) &&
                        (qDate.isBlank() || it.time.contains(qDate))
            }

            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        TopAppBar(
                            title = { Text("大信箱", fontWeight = FontWeight.Black) },
                            navigationIcon = { HapticIconButton(onClick = { showFullScreenInbox = false }) { Icon(Icons.Default.Close, "关闭") } },
                            actions = {
                                Icon(Icons.Default.Settings, "Secret", modifier = Modifier.padding(16.dp)
                                    .combinedClickable(onClick = { haptic(HapticEvent.Tap); Toast.makeText(context, "长按进入设置", Toast.LENGTH_SHORT).show() }, onLongClick = { haptic(HapticEvent.LongPress); onNavigateToSecretMenu() }))
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                        )

                        Box(modifier = Modifier.weight(1f)) {
                            if (filtered.isEmpty()) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("信箱空空如也~", color = Color.Gray) } }
                            else {
                                LazyColumn(state = fullListState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp, start = 16.dp, end = 16.dp, top = 8.dp)) {
                                    items(filtered) { msg ->
                                        CartoonCard(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), backgroundColor = Color.White, onLongClick = { if (msg.sender != "系统") { replyTarget = msg.sender; replySubId = msg.subId ?: selectedSim?.subId ?: -1 } else Toast.makeText(context, "不可回复系统", Toast.LENGTH_SHORT).show() }) {
                                            Column(modifier = Modifier.padding(12.dp)) {
                                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                                    Text("来自: ${msg.sender}", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
                                                    Text(msg.time, fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                                                }
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(msg.body, fontSize = 15.sp, lineHeight = 22.sp, color = Color.Black)
                                            }
                                        }
                                    }
                                }

                                Box(modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(40.dp)
                                    .pointerInput(filtered.size) {
                                        detectVerticalDragGestures { change, _ ->
                                            val y = change.position.y; val ratio = (y / size.height).coerceIn(0f, 1f)
                                            val target = maxOf(0, (filtered.size * ratio).toInt().coerceIn(0, filtered.size - 1))
                                            coroutineScope.launch { fullListState.scrollToItem(target) }
                                        }
                                    }
                                ) { Box(modifier = Modifier.fillMaxHeight().width(4.dp).align(Alignment.CenterEnd).padding(end = 4.dp).background(Color.Gray.copy(alpha=0.3f), CircleShape)) }
                            }

                            val isSearching = qNumber.isNotBlank() || qBody.isNotBlank() || qDate.isNotBlank()
                            val showScroll by remember { derivedStateOf { fullListState.firstVisibleItemIndex > 0 || qNumber.isNotBlank() || qBody.isNotBlank() || qDate.isNotBlank() } }
                            androidx.compose.animation.AnimatedVisibility(visible = showScroll || messages.isNotEmpty(), modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp), enter = scaleIn(), exit = scaleOut()) {
                                InboxActionButton(isSearching = isSearching,
                                    onClick = { if (isSearching) { qNumber=""; qBody=""; qDate="" } else coroutineScope.launch { fullListState.animateScrollToItem(0) } },
                                    onLongClick = { showAdvSearch = true })
                            }
                        }
                    }

                    AnimatedVisibility(visible = replyTarget != null, modifier = Modifier.align(Alignment.BottomCenter), enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                        Surface(color = Color.White, shadowElevation = 16.dp, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
                            Column {
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CartoonButton("1", color = Color(0xFFF1F2F6), modifier = Modifier.weight(1f), onClick = { replyText = "1" })
                                    CartoonButton("TD", color = Color(0xFFF1F2F6), modifier = Modifier.weight(1f), onClick = { replyText = "TD" })
                                }
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(value = replyText, onValueChange = { replyText = it }, modifier = Modifier.weight(1f), placeholder = { Text("回复 $replyTarget...") }, singleLine = true, shape = RoundedCornerShape(24.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    HapticIconButton(
                                        onClick = {
                                            if (replyText.isNotBlank()) {
                                                val sim = getActiveSims(context).find { it.subId == replySubId }
                                                if (sim == null) Toast.makeText(context, "回复所用SIM卡已不可用", Toast.LENGTH_SHORT).show()
                                                else {
                                                    val act = context as MainActivity
                                                    val accepted = if (useSystemSender()) act.sendSmsViaSystem(replyText, sim, replyTarget!!) else act.sendSmsSilently(replyText, sim.subId, replyTarget!!)
                                                    if (accepted) { replyText = ""; replyTarget = null; replySubId = -1 }
                                                }
                                            }
                                        }, modifier = Modifier.background(MaterialTheme.colorScheme.primary, CircleShape).size(48.dp)
                                    ) { Icon(Icons.Default.Send, "发送", tint = Color.White) }
                                    HapticIconButton(onClick = { replyTarget = null; replyText = ""; replySubId = -1 }) { Icon(Icons.Default.Close, "取消", tint = Color.Gray) }
                                }
                            }
                        }
                    }

                    if (showAdvSearch) {
                        AlertDialog(onDismissRequest = { showAdvSearch = false }, containerColor = Color.White, title = { Text("🔍 高级搜寻", fontWeight = FontWeight.Black) },
                            text = { Column {
                                OutlinedTextField(value = qNumber, onValueChange = { qNumber = it }, label = { Text("检索号码") }, singleLine = true)
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(value = qBody, onValueChange = { qBody = it }, label = { Text("包含关键词") }, singleLine = true)
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(value = qDate, onValueChange = { qDate = it }, label = { Text("日期(如: 2026-03-09)") }, singleLine = true)
                            }},
                            confirmButton = { HapticTextButton(onClick = { showAdvSearch = false }) { Text("查找", color = MaterialTheme.colorScheme.primary) } }
                        )
                    }
                }
            }
        }

        if (currentSyncIndex >= 0 && currentSyncIndex < syncCommandsQueue.size) {
            val isLast = currentSyncIndex == syncCommandsQueue.size - 1
            val currentCmd = syncCommandsQueue[currentSyncIndex]
            AlertDialog(
                onDismissRequest = { }, containerColor = Color.White,
                title = { Text("📡 同步 (${currentSyncIndex + 1}/${syncCommandsQueue.size})", fontWeight = FontWeight.Black) },
                text = { Text(if (syncAccepted) "[$currentCmd] 已提交发送。请完成系统确认并等待运营商回复，提交不代表数据已更新。" else "[$currentCmd] 未能提交发送，请检查SIM卡及权限后重试。", fontSize = 15.sp) },
                confirmButton = {
                    HapticTextButton(onClick = {
                        if (!syncAccepted) syncAccepted = dispatchSync(currentCmd)
                        else if (isLast) {
                            currentSyncIndex = -1
                            val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
                            prefs.edit { putString("last_query_time", time) }
                            lastQueryTime = time
                            Toast.makeText(context, "查询请求已提交，等待运营商回复", Toast.LENGTH_SHORT).show()
                        } else {
                            currentSyncIndex++
                            syncAccepted = dispatchSync(syncCommandsQueue[currentSyncIndex])
                        }
                    }) { Text(if (!syncAccepted) "重试" else if (isLast) "✅ 结束同步" else "👉 下一条", fontWeight = FontWeight.Bold) }
                }, dismissButton = { HapticTextButton(onClick = { currentSyncIndex = -1 }) { Text("中止", color = Color.Gray) } }
            )
        }
        if (showInputDialog.value != null) {
            val cmd = showInputDialog.value!!
            AlertDialog(
                onDismissRequest = { showInputDialog.value = null; inputText = "" }, containerColor = Color.White, title = { Text("填写魔法参数: ${cmd.name}", fontWeight = FontWeight.Black) },
                text = { OutlinedTextField(value = inputText, onValueChange = { inputText = it }, label = { Text(cmd.inputHint) }, singleLine = true) },
                confirmButton = {
                    HapticTextButton(onClick = {
                        val error = CommandParameters.validate(cmd, inputText)
                        if (error != null) Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                        else {
                            val sim = getActiveSims(context).find { it.subId == inputTargetSubId }
                            val target = inputTargetNumber
                            if (sim == null || target.isBlank()) Toast.makeText(context, "原指令所用SIM卡或号码已不可用", Toast.LENGTH_SHORT).show()
                            else {
                                val finalCommand = "${cmd.code}${inputText.trim()}"
                                val act = context as MainActivity
                                val accepted = if (useSystemSender()) act.sendSmsViaSystem(finalCommand, sim, target) else act.sendSmsSilently(finalCommand, sim.subId, target)
                                if (accepted) { showInputDialog.value = null; inputText = "" }
                            }
                        }
                    }) { Text("biu~ 发送", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
                }, dismissButton = { HapticTextButton(onClick = { showInputDialog.value = null; inputText = "" }) { Text("算了", color = Color.Gray, fontWeight = FontWeight.Bold) } }
            )
        }
        if (showTrafficDialog.value) AlertDialog(onDismissRequest = { showTrafficDialog.value = false }, containerColor = Color.White, title = { Text("🚀 流量报告", fontWeight = FontWeight.Black) }, text = { LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) { if (parsedInfo.trafficDetails.isEmpty()) item { Text("暂时没有明细~", color = Color.Gray) } else items(parsedInfo.trafficDetails) { Text("👉 $it\n", fontSize = 14.sp) } } }, confirmButton = { HapticTextButton(onClick = { showTrafficDialog.value = false }) { Text("朕知道了", fontWeight = FontWeight.Bold) } })
        if (showBillsDialog.value) AlertDialog(onDismissRequest = { showBillsDialog.value = false }, containerColor = Color.White, title = { Text("💰 历史账单", fontWeight = FontWeight.Black) }, text = { LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) { if (parsedInfo.historicalBills.isEmpty()) item { Text("没有记录~", color = Color.Gray) } else items(parsedInfo.historicalBills.reversed()) { bill -> Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(bill.month, fontWeight = FontWeight.Bold); Text("¥ ${bill.amount}", color = Color(0xFFFF9800), fontWeight = FontWeight.Black) } } } }, confirmButton = { HapticTextButton(onClick = { showBillsDialog.value = false }) { Text("好滴", fontWeight = FontWeight.Bold) } })
        if (showSimInfoDialog.value != null) {
            val sim = showSimInfoDialog.value!!
            AlertDialog(
                onDismissRequest = { showSimInfoDialog.value = null }, containerColor = Color.White, title = { Text("📱 高级卡片信息", fontWeight = FontWeight.Black) },
                text = { Column { Text("卡槽: SIM ${sim.slotIndex + 1}", fontWeight = FontWeight.Bold); Text("运营商: ${sim.carrierName}"); Text("本机号码: ${sim.number}"); Spacer(modifier = Modifier.height(8.dp)); Text("ICCID: \n${sim.iccId}", fontSize = 12.sp); Text("MCC: ${sim.mcc} | MNC: ${sim.mnc}"); Text("漫游: ${sim.isRoaming}"); Text("底层标识: ${sim.subId}") } },
                confirmButton = { HapticTextButton(onClick = { showSimInfoDialog.value = null }) { Text("关闭") } }
            )
        }
    }
}