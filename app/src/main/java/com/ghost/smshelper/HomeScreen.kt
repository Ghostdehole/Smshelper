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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HomeScreen(onNavigateToSettings: () -> Unit, onNavigateToSecretMenu: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("SmsHelper_Prefs", Context.MODE_PRIVATE)
    val useIntentFallback = remember { mutableStateOf(prefs.getBoolean("compat_mode", false)) }
    var lastSyncCompleteTime by remember { mutableStateOf(prefs.getString("last_sync_time", "从未")) }

    var showFullScreenInbox by remember { mutableStateOf(false) }
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

    var simList by remember { mutableStateOf(getActiveSims(context)) }
    var selectedSim by remember { mutableStateOf(simList.find { it.subId == prefs.getInt("selected_sim_subid", -1) } ?: simList.firstOrNull()) }

    val showDashboard = remember { mutableStateOf(false) }

    val showInputDialog = remember { mutableStateOf<ActionCommand?>(null) }
    var inputText by remember { mutableStateOf("") }
    val showTrafficDialog = remember { mutableStateOf(false) }
    val showBillsDialog = remember { mutableStateOf(false) }
    val showSimInfoDialog = remember { mutableStateOf<SimInfo?>(null) }
    var syncCommandsQueue by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentSyncIndex by remember { mutableIntStateOf(-1) }
    val coroutineScope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms.values.all { it }) {
            simList = getActiveSims(context)
            val savedSubId = prefs.getInt("selected_sim_subid", -1)
            val simToSelect = simList.find { it.subId == savedSubId } ?: simList.firstOrNull()
            selectedSim = simToSelect
            if (simToSelect != null) ConfigManager.switchCarrier(simToSelect.mnc, simToSelect.carrierName)
        }
    }

    LaunchedEffect(Unit) {
        val requiredPerms = arrayOf(android.Manifest.permission.SEND_SMS, android.Manifest.permission.RECEIVE_SMS, android.Manifest.permission.READ_SMS, android.Manifest.permission.READ_PHONE_STATE)
        if (requiredPerms.all { androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            simList = getActiveSims(context)
            val savedSubId = prefs.getInt("selected_sim_subid", -1)
            val simToSelect = simList.find { it.subId == savedSubId } ?: simList.firstOrNull()
            selectedSim = simToSelect
            if (simToSelect != null) ConfigManager.switchCarrier(simToSelect.mnc, simToSelect.carrierName)
        } else permissionLauncher.launch(requiredPerms)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(selectedSim?.carrierName ?: "短信助手", fontWeight = FontWeight.Black, fontSize = 22.sp) },
                    actions = {
                        IconButton(onClick = {
                            if (selectedSim != null && activeConfig != null && activeConfig!!.syncCommands.isNotEmpty()) {
                                syncCommandsQueue = activeConfig!!.syncCommands; currentSyncIndex = 0
                                if (useIntentFallback.value) (context as MainActivity).sendSmsViaSystem(syncCommandsQueue[0], selectedSim!!, activeConfig!!.targetNumber)
                                else (context as MainActivity).sendSmsSilently(syncCommandsQueue[0], selectedSim!!.subId, activeConfig!!.targetNumber, false)
                            }
                        }) { Icon(Icons.Default.Refresh, "同步", tint = Color.Black) }
                        IconButton(onClick = onNavigateToSettings) { Icon(Icons.Default.Settings, "设置", tint = Color.Black) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            },
            containerColor = Color.Transparent
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                CartoonCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(top = 12.dp, bottom = 16.dp, start = 16.dp, end = 16.dp)) {
                        Text("最后更新: $lastSyncCompleteTime", fontSize = 10.sp, color = Color.Gray, modifier = Modifier.align(Alignment.End))
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(95.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showTrafficDialog.value = true }) {
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
                            Column(horizontalAlignment = Alignment.End, modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showBillsDialog.value = true }) {
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
                                        onClick = { selectedSim = sim; prefs.edit { putInt("selected_sim_subid", sim.subId) }; ConfigManager.switchCarrier(sim.mnc, sim.carrierName) },
                                        onLongClick = { showSimInfoDialog.value = sim }
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
                            FloatingActionButton(
                                onClick = { if (isSearching) { qNumber=""; qBody=""; qDate="" } else coroutineScope.launch { listState.animateScrollToItem(0) } },
                                modifier = Modifier.size(40.dp).combinedClickable(onClick = { if (isSearching) { qNumber=""; qBody=""; qDate="" } else coroutineScope.launch { listState.animateScrollToItem(0) } }, onLongClick = { showAdvSearch = true }),
                                containerColor = if (isSearching) Color.Red else Color.Black, contentColor = Color.White, shape = CircleShape
                            ) { Icon(if (isSearching) Icons.Default.Clear else Icons.Default.Search, "Action") }
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
                            confirmButton = { TextButton(onClick = { showAdvSearch = false }) { Text("查找", color = MaterialTheme.colorScheme.primary) } }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                    val categories = activeConfig?.categories ?: emptyList()
                    items(categories) { category ->
                        Text(category.title, color = Color.Black, fontWeight = FontWeight.Black, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                        category.commands.chunked(2).forEach { rowCommands ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                rowCommands.forEach { cmd ->
                                    CartoonButton(text = cmd.name, modifier = Modifier.weight(1f).padding(bottom = 8.dp), color = Color(0xFFF1F2F6), onClick = {
                                        if (cmd.requiresInput) showInputDialog.value = cmd
                                        else {
                                            selectedSim?.let {
                                                val act = context as MainActivity; val target = activeConfig?.targetNumber ?: "10086"
                                                if (useIntentFallback.value) act.sendSmsViaSystem(cmd.code, it, target) else act.sendSmsSilently(cmd.code, it.subId, target)
                                            } ?: Toast.makeText(context, "未选择有效的SIM卡", Toast.LENGTH_SHORT).show()
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
            var replyTarget by remember { mutableStateOf<String?>(null) }
            var replyText by remember { mutableStateOf("") }
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
                            navigationIcon = { IconButton(onClick = { showFullScreenInbox = false }) { Icon(Icons.Default.Close, "关闭") } },
                            actions = {
                                var isPressing by remember { mutableStateOf(false) }
                                LaunchedEffect(isPressing) { if (isPressing) { delay(5000); isPressing = false; onNavigateToSecretMenu() } }
                                Icon(Icons.Default.Settings, "Secret", modifier = Modifier.padding(16.dp).pointerInput(Unit) {
                                    awaitPointerEventScope { while (true) awaitPointerEvent() }
                                }.combinedClickable(onClick = { Toast.makeText(context, "按得不够久哦", Toast.LENGTH_SHORT).show() }, onLongClick = { onNavigateToSecretMenu() }))
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                        )

                        Box(modifier = Modifier.weight(1f)) {
                            if (filtered.isEmpty()) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("信箱空空如也~", color = Color.Gray) } }
                            else {
                                LazyColumn(state = fullListState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp, start = 16.dp, end = 16.dp, top = 8.dp)) {
                                    items(filtered) { msg ->
                                        CartoonCard(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), backgroundColor = Color.White, onLongClick = { if (msg.sender != "系统") replyTarget = msg.sender else Toast.makeText(context, "不可回复系统", Toast.LENGTH_SHORT).show() }) {
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
                                    .pointerInput(Unit) {
                                        detectVerticalDragGestures { change, _ ->
                                            val y = change.position.y; val ratio = (y / size.height).coerceIn(0f, 1f)
                                            val target = maxOf(0, (filtered.size * ratio).toInt().coerceIn(0, filtered.size - 1))
                                            coroutineScope.launch { fullListState.scrollToItem(target) }
                                        }
                                    }
                                ) { Box(modifier = Modifier.fillMaxHeight().width(4.dp).align(Alignment.CenterEnd).padding(end = 4.dp).background(Color.Gray.copy(alpha=0.3f), CircleShape)) }
                            }

                            val isSearching = qNumber.isNotBlank() || qBody.isNotBlank() || qDate.isNotBlank()
                            val showScroll by remember { derivedStateOf { fullListState.firstVisibleItemIndex > 0 || isSearching } }
                            androidx.compose.animation.AnimatedVisibility(visible = showScroll || messages.isNotEmpty(), modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp), enter = scaleIn(), exit = scaleOut()) {
                                FloatingActionButton(
                                    onClick = { if (isSearching) { qNumber=""; qBody=""; qDate="" } else coroutineScope.launch { fullListState.animateScrollToItem(0) } },
                                    modifier = Modifier.combinedClickable(onClick = { if (isSearching) { qNumber=""; qBody=""; qDate="" } else coroutineScope.launch { fullListState.animateScrollToItem(0) } }, onLongClick = { showAdvSearch = true }),
                                    containerColor = if (isSearching) Color.Red else Color.Black, contentColor = Color.White
                                ) { Icon(if (isSearching) Icons.Default.Clear else Icons.Default.Search, "Top") }
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
                                    IconButton(
                                        onClick = {
                                            if (replyText.isNotBlank() && selectedSim != null) {
                                                val act = context as MainActivity
                                                if (useIntentFallback.value) act.sendSmsViaSystem(replyText, selectedSim!!, replyTarget!!) else act.sendSmsSilently(replyText, selectedSim!!.subId, replyTarget!!)
                                                replyText = ""; replyTarget = null
                                            }
                                        }, modifier = Modifier.background(MaterialTheme.colorScheme.primary, CircleShape).size(48.dp)
                                    ) { Icon(Icons.Default.Send, "发送", tint = Color.White) }
                                    IconButton(onClick = { replyTarget = null; replyText = "" }) { Icon(Icons.Default.Close, "取消", tint = Color.Gray) }
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
                            confirmButton = { TextButton(onClick = { showAdvSearch = false }) { Text("查找", color = MaterialTheme.colorScheme.primary) } }
                        )
                    }
                }
            }
        }

        if (currentSyncIndex >= 0 && currentSyncIndex < syncCommandsQueue.size) {
            val isLast = currentSyncIndex == syncCommandsQueue.size - 1; val currentCmd = syncCommandsQueue[currentSyncIndex]
            AlertDialog(
                onDismissRequest = { }, containerColor = Color.White, title = { Text("📡 同步 (${currentSyncIndex + 1}/${syncCommandsQueue.size})", fontWeight = FontWeight.Black) },
                text = { Text("已发送[$currentCmd]，系统允许后点击下方继续。", fontSize = 15.sp) },
                confirmButton = {
                    TextButton(onClick = {
                        if (isLast) {
                            currentSyncIndex = -1; Toast.makeText(context, "🎉 同步完成", Toast.LENGTH_SHORT).show()
                            val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
                            prefs.edit { putString("last_sync_time", time) }; lastSyncCompleteTime = time
                        } else {
                            currentSyncIndex++; val nextCmd = syncCommandsQueue[currentSyncIndex]
                            selectedSim?.let {
                                val act = context as MainActivity; val target = activeConfig?.targetNumber ?: "10086"
                                if (useIntentFallback.value) act.sendSmsViaSystem(nextCmd, it, target) else act.sendSmsSilently(nextCmd, it.subId, target, false)
                            }
                        }
                    }) { Text(if (isLast) "✅ 结束同步" else "👉 下一条", fontWeight = FontWeight.Bold) }
                }, dismissButton = { TextButton(onClick = { currentSyncIndex = -1 }) { Text("中止", color = Color.Gray) } }
            )
        }
        if (showInputDialog.value != null) {
            val cmd = showInputDialog.value!!
            AlertDialog(
                onDismissRequest = { showInputDialog.value = null }, containerColor = Color.White, title = { Text("填写魔法参数: ${cmd.name}", fontWeight = FontWeight.Black) },
                text = { OutlinedTextField(value = inputText, onValueChange = { inputText = it }, label = { Text(cmd.inputHint) }, singleLine = true) },
                confirmButton = {
                    TextButton(onClick = {
                        val finalCommand = "${cmd.code}$inputText"
                        selectedSim?.let {
                            val act = context as MainActivity; val target = activeConfig?.targetNumber ?: "10086"
                            if (useIntentFallback.value) act.sendSmsViaSystem(finalCommand, it, target) else act.sendSmsSilently(finalCommand, it.subId, target)
                        }
                        showInputDialog.value = null; inputText = ""
                    }) { Text("biu~ 发送", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
                }, dismissButton = { TextButton(onClick = { showInputDialog.value = null }) { Text("算了", color = Color.Gray, fontWeight = FontWeight.Bold) } }
            )
        }
        if (showTrafficDialog.value) AlertDialog(onDismissRequest = { showTrafficDialog.value = false }, containerColor = Color.White, title = { Text("🚀 流量报告", fontWeight = FontWeight.Black) }, text = { LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) { if (parsedInfo.trafficDetails.isEmpty()) item { Text("暂时没有明细~", color = Color.Gray) } else items(parsedInfo.trafficDetails) { Text("👉 $it\n", fontSize = 14.sp) } } }, confirmButton = { TextButton(onClick = { showTrafficDialog.value = false }) { Text("朕知道了", fontWeight = FontWeight.Bold) } })
        if (showBillsDialog.value) AlertDialog(onDismissRequest = { showBillsDialog.value = false }, containerColor = Color.White, title = { Text("💰 历史账单", fontWeight = FontWeight.Black) }, text = { LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) { if (parsedInfo.historicalBills.isEmpty()) item { Text("没有记录~", color = Color.Gray) } else items(parsedInfo.historicalBills.reversed()) { bill -> Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(bill.month, fontWeight = FontWeight.Bold); Text("¥ ${bill.amount}", color = Color(0xFFFF9800), fontWeight = FontWeight.Black) } } } }, confirmButton = { TextButton(onClick = { showBillsDialog.value = false }) { Text("好滴", fontWeight = FontWeight.Bold) } })
        if (showSimInfoDialog.value != null) {
            val sim = showSimInfoDialog.value!!
            AlertDialog(
                onDismissRequest = { showSimInfoDialog.value = null }, containerColor = Color.White, title = { Text("📱 高级卡片信息", fontWeight = FontWeight.Black) },
                text = { Column { Text("卡槽: SIM ${sim.slotIndex + 1}", fontWeight = FontWeight.Bold); Text("运营商: ${sim.carrierName}"); Text("本机号码: ${sim.number}"); Spacer(modifier = Modifier.height(8.dp)); Text("ICCID: \n${sim.iccId}", fontSize = 12.sp); Text("MCC: ${sim.mcc} | MNC: ${sim.mnc}"); Text("漫游: ${sim.isRoaming}"); Text("底层标识: ${sim.subId}") } },
                confirmButton = { TextButton(onClick = { showSimInfoDialog.value = null }) { Text("关闭") } }
            )
        }
    }
}