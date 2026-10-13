@file:OptIn(ExperimentalMaterial3Api::class)
package com.ghost.smshelper

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(onBack: () -> Unit, showBack: Boolean = true, onSubPageChanged: (Boolean) -> Unit = {}) {
    var page by rememberSaveable { mutableStateOf("settings") }
    LaunchedEffect(page) { onSubPageChanged(page != "settings") }
    DisposableEffect(Unit) { onDispose { onSubPageChanged(false) } }
    val back = { page = "settings" }
    when (page) {
        "appearance" -> ThemeWorkshopScreen(onBack = back)
        "rules" -> RulesCenterScreen(onBack = back)
        "backup" -> BackupCenterScreen(onBack = back)
        "reminders" -> ReminderSettingsScreen(onBack = back)
        "security" -> SecretMenuScreen(onBack = back)
        "diagnostics" -> DiagnosticsScreen(onBack = back)
        else -> {
            BackHandler(onBack = onBack)
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Column {
                            Text("助手设置", fontWeight = FontWeight.Black)
                            Text("把助手调成你的样子", fontSize = 12.sp, color = AppTheme.colors.mutedText)
                        } },
                        navigationIcon = { if (showBack) HapticIconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        } },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = AppTheme.colors.background)
                    )
                },
                containerColor = AppTheme.colors.background
            ) { padding ->
                GeneralSettingsTab(LocalContext.current, { page = it }, Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun SettingsEntry(title: String, description: String, onClick: () -> Unit) {
    CartoonCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Black, fontSize = 17.sp)
                Spacer(Modifier.height(5.dp))
                Text(description, color = AppTheme.colors.mutedText, fontSize = 13.sp)
            }
            Text("›", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.text,
                modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
fun GeneralSettingsTab(context: Context, navigate: (String) -> Unit, modifier: Modifier = Modifier) {
    val prefs = remember(context) { context.getSharedPreferences("SmsHelper_Prefs", Context.MODE_PRIVATE) }
    var compatible by remember { mutableStateOf(prefs.getBoolean("compat_mode", false)) }
    var interceptAll by remember { mutableStateOf(prefs.getBoolean("intercept_all_sms", false)) }
    val haptics by AppFeedbackPreferences.hapticsEnabled.collectAsState()
    val reducedMotion by AppFeedbackPreferences.reduceMotion.collectAsState()
    var exporting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val result = runCatching { withContext(Dispatchers.IO) {
                val text = TxtBackup.encode(SmsRepository.messages.value)
                requireNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter(Charsets.UTF_8).use { it.write(text) }
            } }
            busy = false
            Toast.makeText(context, if (result.isSuccess) "短信已导出" else "导出失败，请检查文件位置", Toast.LENGTH_LONG).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val result = runCatching { withContext(Dispatchers.IO) {
                val text = requireNotNull(context.contentResolver.openInputStream(uri)).use { readUtf8Text(it, 32 * 1024 * 1024) }
                SmsRepository.importMessages(context, TxtBackup.decode(text))
            } }
            busy = false
            Toast.makeText(context, result.fold({ "已导入 $it 条，归入未分卡历史，不覆盖当前用量" },
                { "导入失败：文件格式错误或无法读取" }), Toast.LENGTH_LONG).show()
        }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (BuildConfig.APPLICATION_ID.endsWith(".preview")) item {
            CartoonCard(backgroundColor = AppTheme.colors.surfaceAlt, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("独立测试版", fontWeight = FontWeight.Black)
                    Text("与正式版并排安装，数据独立保存。可用正式版导出的 TXT 导入历史短信。", fontSize = 13.sp)
                }
            }
        }
        item { SettingsEntry("🎨 外观工作室", "四套漫画主题 · 分区调色 · 实时预览") { navigate("appearance") } }
        item { SettingsEntry("🧩 SIM 与规则", "每卡配置 · 指令编辑 · 解析测试 · 订阅版本") { navigate("rules") } }
        item { SettingsEntry("📦 完整备份", "备份短信、规则、主题和布局，支持密码保护") { navigate("backup") } }
        item { SettingsEntry("🔔 提醒", "余额与流量阈值 · 查询提醒 · 账单周期") { navigate("reminders") } }
        item {
            CartoonCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("发送与交互", fontWeight = FontWeight.Black, fontSize = 17.sp)
                    SettingToggle("系统短信兼容模式", "打开系统短信应用，由你确认发送", compatible) {
                        compatible = it; prefs.edit { putBoolean("compat_mode", it) }
                    }
                    HorizontalDivider(color = AppTheme.colors.outline.copy(alpha = .15f))
                    SettingToggle("接收全部新短信", "在助手中保存其他号码的短信副本", interceptAll) {
                        interceptAll = it; prefs.edit { putBoolean("intercept_all_sms", it) }
                    }
                    HorizontalDivider(color = AppTheme.colors.outline.copy(alpha = .15f))
                    SettingToggle("操作触感", "遵循手机系统的触感开关", haptics) { AppFeedbackPreferences.setHapticsEnabled(context, it) }
                    SettingToggle("减少动画", "减少缩放和弹性动画", reducedMotion) { AppFeedbackPreferences.setReduceMotion(context, it) }
                }
            }
        }
        item {
            CartoonCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("TXT 短信迁移", fontWeight = FontWeight.Black, fontSize = 17.sp)
                    Text("兼容旧版导出格式，只迁移短信。文件为可阅读的明文。", color = AppTheme.colors.mutedText, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CartoonButton("导入 TXT", Modifier.weight(1f), onClick = { if (!busy) importLauncher.launch(arrayOf("text/plain", "application/json", "application/octet-stream")) })
                        CartoonButton("导出 TXT", Modifier.weight(1f), onClick = { if (!busy) exporting = true })
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        item { SettingsEntry("🔐 隐私与安全 · 绝对禁区", "访问密码 · 清空记录 · 恢复默认") { navigate("security") } }
        item { SettingsEntry("🩺 设备诊断", "短信权限 · SIM 状态 · 触感测试 · 脱敏报告") { navigate("diagnostics") } }
        item {
            CartoonCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("👻 短信助手", fontWeight = FontWeight.Black, fontSize = 21.sp)
                    Text("${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_TYPE}", color = AppTheme.colors.mutedText, fontSize = 12.sp)
                    CartoonButton("前往 GitHub", color = AppTheme.colors.secondary, onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/Ghostdehole/Smshelper".toUri())) }
                            .onFailure { Toast.makeText(context, "未找到可打开网页的应用", Toast.LENGTH_SHORT).show() }
                    })
                }
            }
        }
        item { Spacer(Modifier.height(10.dp)) }
    }
    if (exporting) AlertDialog(
        onDismissRequest = { exporting = false }, containerColor = AppTheme.colors.dialog,
        title = { Text("导出明文短信") }, text = { Text("TXT 包含短信原文。需要密码保护时，可使用“完整备份”。") },
        confirmButton = { HapticTextButton(onClick = {
            exporting = false
            exportLauncher.launch("SmsBackup_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())}.txt")
        }) { Text("导出") } }, dismissButton = { HapticTextButton(onClick = { exporting = false }) { Text("取消") } }
    )
}

@Composable
private fun SettingToggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(detail, color = AppTheme.colors.mutedText, fontSize = 12.sp)
        }
        HapticSwitch(checked, onChange)
    }
}

@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val haptic = actionHaptics()
    val report = remember(refresh) { buildDiagnosticReport(context) }
    BackHandler(onBack = onBack)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    Scaffold(containerColor = AppTheme.colors.background, topBar = {
        TopAppBar(title = { Text("设备诊断", fontWeight = FontWeight.Black) }, navigationIcon = {
            HapticIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = AppTheme.colors.background))
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                CartoonCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                    Text("当前状态", fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Spacer(Modifier.height(12.dp)); Text(report, fontSize = 14.sp)
                } }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CartoonButton("刷新状态", Modifier.weight(1f), onClick = { refresh++ })
                    CartoonButton("测试触感", Modifier.weight(1f), onClick = { haptic(HapticEvent.Confirm) })
                }
            }
            item { CartoonButton("申请短信与 SIM 权限", Modifier.fillMaxWidth(), onClick = {
                permissionLauncher.launch(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS, Manifest.permission.READ_PHONE_STATE))
            }) }
            item { CartoonButton("打开系统应用设置", Modifier.fillMaxWidth(), onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }
                    .onFailure { Toast.makeText(context, "无法打开应用设置", Toast.LENGTH_SHORT).show() }
            }) }
            item { CartoonButton("复制脱敏诊断报告", Modifier.fillMaxWidth(), onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Smshelper 诊断", buildDiagnosticReport(context)))
                Toast.makeText(context, "已复制，不含号码、短信或 SIM 标识", Toast.LENGTH_SHORT).show()
            }) }
            item { Text("诊断报告只包含版本、权限开关和可用 SIM 数量。触感仍受手机系统设置控制；短信实际发送结果请到查询记录查看。", color = AppTheme.colors.mutedText, fontSize = 13.sp) }
        }
    }
}

private fun buildDiagnosticReport(context: Context): String = buildString {
    appendLine("短信助手 ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
    appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
    val permissions = linkedMapOf("发送短信" to Manifest.permission.SEND_SMS, "接收短信" to Manifest.permission.RECEIVE_SMS,
        "读取短信" to Manifest.permission.READ_SMS, "SIM 信息" to Manifest.permission.READ_PHONE_STATE)
    if (Build.VERSION.SDK_INT >= 33) permissions["通知"] = Manifest.permission.POST_NOTIFICATIONS
    permissions.forEach { (name, permission) -> appendLine("$name：${if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) "已允许" else "未允许"}") }
    appendLine("可用 SIM：${getActiveSims(context).size} 张")
    val prefs = context.getSharedPreferences("SmsHelper_Prefs", Context.MODE_PRIVATE)
    appendLine("发送模式：${if (prefs.getBoolean("compat_mode", false)) "系统短信应用" else "直接发送"}")
    appendLine("应用触感：${if (prefs.getBoolean("haptics_enabled", true)) "开启" else "关闭"}")
    appendLine("减少动画：${if (prefs.getBoolean("reduce_motion", false)) "开启" else "关闭"}")
    append("本地短信存储：${if (SmsRepository.storageError.value == null) "正常" else "待处理错误"}")
}
// ======================= 隐秘设置 =======================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretMenuScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("SmsHelper_Prefs", Context.MODE_PRIVATE)
    var isPinEnabled by remember { mutableStateOf(prefs.getBoolean("pin_enabled", false)) }
    val showPinSetup = remember { mutableStateOf(false) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = { TopAppBar(title = { Text("绝对禁区", fontWeight = FontWeight.Black) }, navigationIcon = { HapticIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { CartoonCard(backgroundColor = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("安全认证", fontWeight = FontWeight.Black, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("应用进入需四位密码", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        HapticSwitch(checked = isPinEnabled, onCheckedChange = {
                            if (it) showPinSetup.value = true
                            else if (prefs.edit().putBoolean("pin_enabled", false).remove("pin_code").commit()) isPinEnabled = false
                            else Toast.makeText(context, "设置保存失败，请重试", Toast.LENGTH_SHORT).show()
                        })
                    }
                    if (isPinEnabled) HapticTextButton(onClick = { showPinSetup.value = true }) { Text("更改访问密码") }
                    Text("离开应用后重新验证。密码不包含在导出备份中。", color = AppTheme.colors.mutedText, fontSize = 12.sp)
                }
            } }
            item { Text("本应用的数据管理", fontWeight = FontWeight.Bold) }
            item { CartoonButton("清空信箱历史", color = AppTheme.colors.danger, onClick = { confirmClearHistory = true }) }
            item { CartoonButton("🧨 恢复默认并清空全部数据", color = AppTheme.colors.danger, onClick = { confirmReset = true }) }
        }
    }

    if (confirmClearHistory) {
        AlertDialog(onDismissRequest = { confirmClearHistory = false }, containerColor = AppTheme.colors.dialog,
            title = { Text("清空信箱历史", fontWeight = FontWeight.Black) }, text = { Text("确认删除本应用保存的全部短信？此操作无法撤销，不会删除系统短信。") },
            confirmButton = { HapticTextButton(onClick = {
                try { SmsRepository.clearHistory(context); confirmClearHistory = false; Toast.makeText(context, "信箱已清空", Toast.LENGTH_SHORT).show() }
                catch (_: Exception) { Toast.makeText(context, "清空失败，请稍后重试", Toast.LENGTH_SHORT).show() }
            }) { Text("清空", color = AppTheme.colors.danger) } },
            dismissButton = { HapticTextButton(onClick = { confirmClearHistory = false }) { Text("取消") } })
    }
    if (confirmReset) {
        AlertDialog(onDismissRequest = { confirmReset = false }, containerColor = AppTheme.colors.dialog,
            title = { Text("格式化所有数据", fontWeight = FontWeight.Black) }, text = { Text("确认删除本应用的短信、统计、规则、订阅、密码和设置，并恢复默认规则？此操作无法撤销。") },
            confirmButton = { HapticTextButton(onClick = { if ((context as MainActivity).resetApplicationData()) { confirmReset = false; Toast.makeText(context, "已恢复默认数据与设置", Toast.LENGTH_SHORT).show(); onBack() } }) { Text("重置", color = AppTheme.colors.danger) } },
            dismissButton = { HapticTextButton(onClick = { confirmReset = false }) { Text("取消") } })
    }
    if (showPinSetup.value) {
        var setupPin by remember { mutableStateOf("") }
        var confirmPin by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPinSetup.value = false }, containerColor = AppTheme.colors.dialog, title = { Text("设置 4 位安全密码", fontWeight = FontWeight.Black) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = setupPin, onValueChange = { if (it.length <= 4 && it.all { char -> char in '0'..'9' }) setupPin = it }, label = { Text("新密码") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(value = confirmPin, onValueChange = { if (it.length <= 4 && it.all { char -> char in '0'..'9' }) confirmPin = it }, label = { Text("再次输入") }, isError = confirmPin.length == 4 && confirmPin != setupPin, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation())
            } },
            confirmButton = { HapticTextButton(enabled = setupPin.length == 4 && confirmPin == setupPin, onClick = {
                if (setupPin.length == 4 && confirmPin == setupPin) {
                    val saved = prefs.edit().putBoolean("pin_enabled", true).putString("pin_code", setupPin).commit()
                    if (saved) { isPinEnabled = true; showPinSetup.value = false }
                    else Toast.makeText(context, "密码保存失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }) { Text("保存", color = MaterialTheme.colorScheme.primary) } },
            dismissButton = { HapticTextButton(onClick = { showPinSetup.value = false }) { Text("取消") } }
        )
    }
}
