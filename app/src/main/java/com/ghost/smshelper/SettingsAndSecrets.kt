@file:Suppress("ASSIGNED_BUT_NEVER_ACCESSED_VARIABLE", "UNUSED_VALUE", "UNUSED_VARIABLE", "CanBeVal")
@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
package com.ghost.smshelper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("助手设置", fontWeight = FontWeight.Black, color = Color.Black) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
                TabRow(
                    selectedTabIndex = pagerState.currentPage, containerColor = Color.Transparent,
                    indicator = { tabPositions -> if (pagerState.currentPage < tabPositions.size) TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]), color = Color.Black) }
                ) {
                    Tab(selected = pagerState.currentPage == 0, onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } }, text = { Text("基础常规", fontWeight = FontWeight.Bold) })
                    Tab(selected = pagerState.currentPage == 1, onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } }, text = { Text("规则中心", fontWeight = FontWeight.Bold) })
                }
            }
        },
        containerColor = Color.Transparent
    ) { padding ->
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().padding(padding)) { page ->
            if (page == 0) GeneralSettingsTab(context, onBack) else RuleCenterTab(context)
        }
    }
}

@Composable
fun GeneralSettingsTab(context: Context, onBack: () -> Unit) {
    val prefs = context.getSharedPreferences("SmsHelper_Prefs", Context.MODE_PRIVATE)
    val useIntentFallback = remember { mutableStateOf(prefs.getBoolean("compat_mode", false)) }
    val interceptAll = remember { mutableStateOf(prefs.getBoolean("intercept_all_sms", false)) }
    var expandedTheme by remember { mutableStateOf(false) }

    var profiles by remember { mutableStateOf(ConfigManager.getAllProfiles().toList()) }
    var selections by remember { mutableStateOf(ConfigManager.getSelections().toMap()) }
    var expandedOperator by remember { mutableStateOf<String?>(null) }
    val showExportWarning = remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let {
            try {
                context.contentResolver.openOutputStream(it)?.use { out ->
                    val data = SmsRepository.messages.value.joinToString("\n\n") { msg -> "[SMS]\nSender: ${msg.sender}\nTime: ${msg.time}\nBody: ${msg.body}\n[END]" }
                    out.write(data.toByteArray())
                }
                Toast.makeText(context, "导出成功", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) { Toast.makeText(context, "导出失败", Toast.LENGTH_SHORT).show() }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                val txt = context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader -> reader.readText() } ?: ""
                val blocks = txt.split("[SMS]").filter { b -> b.contains("[END]") }
                var count = 0
                val newList = SmsRepository.messages.value.toMutableList()
                blocks.forEach { block ->
                    val sender = "Sender: (.*)".toRegex().find(block)?.groupValues?.get(1)?.trim() ?: ""
                    val time = "Time: (.*)".toRegex().find(block)?.groupValues?.get(1)?.trim() ?: ""
                    val body = "Body: ([\\s\\S]*?)\\[END]".toRegex().find(block)?.groupValues?.get(1)?.trim() ?: ""
                    if (sender.isNotBlank() && body.isNotBlank()) { newList.add(SmsItem(sender, body, time)); count++ }
                }
                SmsRepository.messages.value = newList.distinctBy { msg -> msg.time + msg.body }.sortedByDescending { msg -> msg.time }
                SmsRepository.addMessage(context, "系统", "导入了 $count 条短信")
                Toast.makeText(context, "成功导入记录", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) { Toast.makeText(context, "导入失败或格式错误", Toast.LENGTH_SHORT).show() }
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectHorizontalDragGestures { _, dragAmount -> if (dragAmount > 80) onBack() } }, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            CartoonCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("🎯 快捷规则分配", fontWeight = FontWeight.Black, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))

                    val activeIds = mutableSetOf("CMCC", "CUCC", "CTCC", "CBN")
                    selections.keys.forEach { activeIds.add(it) }

                    Column(modifier = Modifier.heightIn(max = 200.dp)) {
                        for (id in activeIds.toList()) {
                            val currentName = selections[id]?.let { sel -> profiles.find { it.id == sel.profileId }?.name } ?: "未选择规则"
                            val displayName = when(id) { "CMCC"->"移动"; "CUCC"->"联通"; "CTCC"->"电信"; "CBN"->"广电"; else -> id }
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(displayName, modifier = Modifier.weight(0.2f), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(currentName, modifier = Modifier.weight(0.6f), color = Color.Gray, fontSize = 14.sp, maxLines = 1)
                                Box {
                                    IconButton(onClick = { expandedOperator = id }) { Icon(Icons.Default.Edit, contentDescription = "选择", tint = MaterialTheme.colorScheme.primary) }
                                    DropdownMenu(expanded = expandedOperator == id, onDismissRequest = { expandedOperator = null }) {
                                        if (profiles.isEmpty()) DropdownMenuItem(text = { Text("无可用规则") }, onClick = { expandedOperator = null })
                                        else profiles.forEach { profile ->
                                            DropdownMenuItem(text = { Text(profile.name) }, onClick = { ConfigManager.selectProfileForCarrier(context, id, profile.id); selections = ConfigManager.getSelections().toMap(); expandedOperator = null })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            CartoonCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("⚙️ 系统与数据", fontWeight = FontWeight.Black, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("系统短信兼容模式", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("唤起系统App发短信，防拦截", fontSize = 11.sp, color = Color.Gray)
                        }
                        Switch(checked = useIntentFallback.value, onCheckedChange = { useIntentFallback.value = it; prefs.edit { putBoolean("compat_mode", it) } })
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("拦截全部系统短信", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("开启后接管所有短信，不再局限运营商", fontSize = 11.sp, color = Color.Gray)
                        }
                        Switch(checked = interceptAll.value, onCheckedChange = { interceptAll.value = it; prefs.edit { putBoolean("intercept_all_sms", it) } })
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("个性化主题", modifier = Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Box {
                            TextButton(onClick = { expandedTheme = true }) { Text("更改主题", color = MaterialTheme.colorScheme.primary) }
                            DropdownMenu(expanded = expandedTheme, onDismissRequest = { expandedTheme = false }) {
                                listOf("经典元气黄", "极客冰川蓝", "猛男樱花粉", "清新薄荷绿").forEachIndexed { index, name ->
                                    DropdownMenuItem(text = { Text(name) }, onClick = { ThemeManager.setTheme(context, index); expandedTheme = false })
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CartoonButton(text = "导入TXT短信", color = Color(0xFFE8F5E9), modifier = Modifier.weight(1f), onClick = { importLauncher.launch(arrayOf("text/plain")) })
                        CartoonButton(text = "导出明文TXT", color = Color(0xFFFFF3E0), modifier = Modifier.weight(1f), onClick = { showExportWarning.value = true })
                    }
                }
            }
        }
        item {
            CartoonCard(modifier = Modifier.fillMaxWidth(), backgroundColor = Color.White) {
                Column(
                    modifier = Modifier.padding(24.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(modifier = Modifier.size(70.dp).background(Color(0xFFFFCC80), CircleShape).border(3.dp, Color.Black, CircleShape), contentAlignment = Alignment.Center) { Text("👻", fontSize = 36.sp) }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("短信助手", fontWeight = FontWeight.Black, fontSize = 18.sp, color = Color.Black)
                    Text("Version 2.0.0", fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(16.dp))
                    CartoonButton(text = "前往 GitHub", color = Color(0xFFE3F2FD), onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/Ghostdehole/sms-helper".toUri())) })
                }
            }
        }
    }

    if (showExportWarning.value) {
        AlertDialog(
            onDismissRequest = { showExportWarning.value = false }, containerColor = Color.White, title = { Text("⚠️ 导出安全警告", fontWeight = FontWeight.Black) },
            text = { Text("短信助手在本地采用AES加密存储短信。\n\n导出为TXT将解密为【明文】，请务必妥善保管文件！") },
            confirmButton = { TextButton(onClick = { showExportWarning.value = false; val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()); exportLauncher.launch("SmsBackup_$stamp.txt") }) { Text("继续导出", color = Color.Red, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { showExportWarning.value = false }) { Text("取消", color = Color.Gray) } }
        )
    }
}

@Composable
fun RuleCenterTab(context: Context) {
    var profiles by remember { mutableStateOf(ConfigManager.getAllProfiles().toList()) }
    var subscriptions by remember { mutableStateOf(ConfigManager.getAllSubscriptions().toList()) }
    val coroutineScope = rememberCoroutineScope()

    val showAddDialog = remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }
    var newMin by remember { mutableStateOf("60") }
    val showConfigSourceDialog = remember { mutableStateOf<String?>(null) }

    fun refresh() { profiles = ConfigManager.getAllProfiles().toList(); subscriptions = ConfigManager.getAllSubscriptions().toList() }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val json = context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader -> reader.readText() }
                if (!json.isNullOrBlank() && ConfigManager.importProfile(context, json, "文件导入", "系统")) { refresh(); Toast.makeText(context, "✅ 导入成功", Toast.LENGTH_SHORT).show() } else Toast.makeText(context, "❌ 格式错误", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CartoonButton(text = "恢复内置规则", color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f), onClick = { ConfigManager.importDefaultProfiles(context); refresh() })
                CartoonButton(text = "系统文件导入", color = Color(0xFFC8E6C9), modifier = Modifier.weight(1f), onClick = { fileLauncher.launch("*/*") })
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CartoonButton(text = "📥 剪切板导入", color = Color(0xFFFFF9C4), modifier = Modifier.weight(1f), onClick = {
                    val pasteData = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()
                    if (!pasteData.isNullOrBlank() && ConfigManager.importProfile(context, pasteData, "剪贴板导入", "剪贴板")) { refresh(); Toast.makeText(context, "✅ 导入成功", Toast.LENGTH_SHORT).show() }
                })
                CartoonButton(text = "📤 复制当前", color = Color(0xFFE1F5FE), modifier = Modifier.weight(1f), onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("JSON", ConfigManager.getCurrentProfileJson()))
                    Toast.makeText(context, "✅ 已复制", Toast.LENGTH_SHORT).show()
                })
            }
            Spacer(modifier = Modifier.height(8.dp))
            CartoonButton(text = "🌐 添加云端订阅", color = Color(0xFF81D4FA), modifier = Modifier.fillMaxWidth(), onClick = {
                val pasteData = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                newUrl = if (pasteData.startsWith("http")) pasteData else ""; newName = ""; newMin = "60"; showAddDialog.value = true
            })
        }

        if (subscriptions.isNotEmpty()) {
            item { Text("☁️ 云端订阅规则", fontWeight = FontWeight.Black, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp)) }
            items(subscriptions) { sub ->
                CartoonCard(backgroundColor = Color(0xFFE3F2FD), modifier = Modifier.fillMaxWidth(), onLongClick = { showConfigSourceDialog.value = profiles.find { it.id == sub.profileId }?.jsonContent ?: "空" }) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(sub.name, fontWeight = FontWeight.Black, fontSize = 16.sp)
                            Text(sub.url, fontSize = 11.sp, color = Color.Gray, maxLines = 1)
                        }
                        IconButton(onClick = { coroutineScope.launch { ConfigManager.updateSubscription(context, sub.url); refresh() } }) { Icon(Icons.Default.Refresh, "Update", tint = Color.Blue) }
                        IconButton(onClick = { ConfigManager.removeSubscription(context, sub.url); refresh() }) { Icon(Icons.Default.Delete, "Delete", tint = Color.Red) }
                    }
                }
            }
        }

        item { Text("📁 本地独立规则", fontWeight = FontWeight.Black, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp)) }
        val localProfiles = profiles.filter { p -> subscriptions.none { it.profileId == p.id } }
        items(localProfiles) { profile ->
            CartoonCard(backgroundColor = Color.White, modifier = Modifier.fillMaxWidth(), onLongClick = { showConfigSourceDialog.value = profile.jsonContent }) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(profile.name, fontWeight = FontWeight.Black, fontSize = 16.sp)
                        Text("来源: ${profile.source}", fontSize = 12.sp, color = Color.Gray)
                    }
                    IconButton(onClick = { ConfigManager.deleteProfile(context, profile.id); refresh() }) { Icon(Icons.Default.Delete, "Delete", tint = Color.Red) }
                }
            }
        }
    }

    if (showAddDialog.value) {
        AlertDialog(
            onDismissRequest = { showAddDialog.value = false }, containerColor = Color.White, title = { Text("➕ 增加云端订阅", fontWeight = FontWeight.Black) },
            text = { Column { OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("订阅名称") }, singleLine = true); Spacer(modifier = Modifier.height(8.dp)); OutlinedTextField(value = newUrl, onValueChange = { newUrl = it }, label = { Text("订阅链接 URL") }, singleLine = true); Spacer(modifier = Modifier.height(8.dp)); OutlinedTextField(value = newMin, onValueChange = { newMin = it }, label = { Text("自动同步(分钟)") }, singleLine = true) } },
            confirmButton = { TextButton(onClick = { if (newName.isBlank() || newUrl.isBlank()) Toast.makeText(context, "名称和链接不能为空！", Toast.LENGTH_SHORT).show() else { ConfigManager.addOrUpdateSubscription(context, newUrl, newName, newMin.toIntOrNull() ?: 60); refresh(); showAddDialog.value = false } }) { Text("保存", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) } }, dismissButton = { TextButton(onClick = { showAddDialog.value = false }) { Text("取消", color = Color.Gray) } }
        )
    }

    if (showConfigSourceDialog.value != null) {
        val jsonSource = showConfigSourceDialog.value!!
        AlertDialog(
            onDismissRequest = { showConfigSourceDialog.value = null }, containerColor = Color.White, title = { Text("📜 规则源代码", fontWeight = FontWeight.Black) },
            text = { Box(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).background(Color(0xFFF5F5F5), RoundedCornerShape(8.dp)).padding(8.dp)) { LazyColumn { item { Text(jsonSource, fontSize = 11.sp, fontFamily = FontFamily.Monospace) } } } },
            confirmButton = { TextButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("JSON", jsonSource)); Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show() }) { Text("复制", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) } }, dismissButton = { TextButton(onClick = { showConfigSourceDialog.value = null }) { Text("关闭", color = Color.Gray) } }
        )
    }
}

// ======================= 隐秘设置 =======================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretMenuScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("SmsHelper_Prefs", Context.MODE_PRIVATE)
    var isPinEnabled by remember { mutableStateOf(prefs.getBoolean("pin_enabled", false)) }
    val showPinSetup = remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("绝对禁区", fontWeight = FontWeight.Black) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            CartoonCard(backgroundColor = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("安全认证", fontWeight = FontWeight.Black, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("应用进入需四位密码", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        Switch(checked = isPinEnabled, onCheckedChange = { if (it) showPinSetup.value = true else { isPinEnabled = false; prefs.edit { putBoolean("pin_enabled", false) } } })
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            CartoonButton("清空信箱历史", color = Color(0xFFFF7675), onClick = { SmsRepository.clearHistory(context); Toast.makeText(context, "信箱已清空", Toast.LENGTH_SHORT).show() })
            Spacer(modifier = Modifier.height(16.dp))
            CartoonButton("🧨 格式化所有数据", color = Color.Red, onClick = { SmsRepository.factoryReset(context); Toast.makeText(context, "数据已毁灭", Toast.LENGTH_SHORT).show(); onBack() })
        }
    }

    if (showPinSetup.value) {
        var setupPin by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPinSetup.value = false }, containerColor = Color.White, title = { Text("设置 4 位安全密码", fontWeight = FontWeight.Black) },
            text = { OutlinedTextField(value = setupPin, onValueChange = { if(it.length <= 4) setupPin = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (setupPin.length == 4) { isPinEnabled = true; prefs.edit { putBoolean("pin_enabled", true); putString("pin_code", setupPin) }; showPinSetup.value = false } }) { Text("保存", color = MaterialTheme.colorScheme.primary) } }
        )
    }
}