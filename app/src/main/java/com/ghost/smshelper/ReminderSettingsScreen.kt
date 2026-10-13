package com.ghost.smshelper

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ReminderSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = AppTheme.colors
    var options by remember { mutableStateOf(Reminders.options(context)) }
    var balance by remember { mutableStateOf(options.balanceThreshold.toString()) }
    var data by remember { mutableStateOf(options.dataThresholdMb.toString()) }
    var days by remember { mutableStateOf(options.queryIntervalDays.toString()) }
    var hour by remember { mutableStateOf(options.reminderHour.toString()) }
    var start by remember { mutableStateOf(options.quietStart.toString()) }
    var end by remember { mutableStateOf(options.quietEnd.toString()) }
    var repeat by remember { mutableStateOf(options.repeatHours.toString()) }
    var status by remember { mutableStateOf<String?>(null) }
    var notificationsAllowed by remember { mutableStateOf(context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        notificationsAllowed = allowed
        if (!allowed) status = "设置已保存；开启系统通知权限后才能收到提醒。"
    }
    BackHandler(onBack = onBack)
    Surface(Modifier.fillMaxSize(), color = colors.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row {
                HapticTextButton(onClick = onBack) { Text("← 返回") }
                Text("提醒与后台检查", Modifier.padding(top = 10.dp), fontSize = 22.sp, fontWeight = FontWeight.Black, color = colors.text)
            }
            CartoonCard(backgroundColor = colors.surface) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ReminderToggle("开启账户提醒", options.enabled) { options = options.copy(enabled = it) }
                    Text("根据已收到的短信判断余额和流量，不是实时监测；提醒只打开应用，不会自动发短信。", color = colors.mutedText, fontSize = 12.sp)
                    if (options.enabled && !notificationsAllowed) {
                        Text("系统通知尚未开启", color = colors.danger)
                        HapticOutlinedButton(onClick = {
                            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) request.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        }) { Text("开启通知") }
                    }
                    ReminderToggle("低余额提醒", options.lowBalance) { options = options.copy(lowBalance = it) }
                    ReminderField("余额低于（元）", balance) { balance = it }
                    ReminderToggle("低流量提醒", options.lowData) { options = options.copy(lowData = it) }
                    ReminderField("剩余流量低于（MB）", data) { data = it }
                    ReminderField("同类提醒最短间隔（小时，1–720）", repeat) { repeat = it }
                }
            }
            CartoonCard(backgroundColor = colors.surfaceAlt) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("定期来看看", fontWeight = FontWeight.Black, fontSize = 18.sp, color = colors.text)
                    ReminderToggle("数据久未更新时提醒查询", options.queryReminder) { options = options.copy(queryReminder = it) }
                    ReminderField("多少天未更新后提醒（1–90）", days) { days = it }
                    ReminderField("每天从几点开始提醒（0–23）", hour) { hour = it }
                    ReminderToggle("账单周期提醒", options.billReminder) { options = options.copy(billReminder = it) }
                    Text("账单日期使用每张 SIM 设置的账单周期日；当月没有该日期时按月末提醒。日期可在 SIM 设置中修改。", color = colors.mutedText, fontSize = 12.sp)
                }
            }
            CartoonCard(backgroundColor = colors.surface) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ReminderToggle("勿扰时段", options.quietEnabled) { options = options.copy(quietEnabled = it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(start, { start = it }, label = { Text("开始（时）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                        OutlinedTextField(end, { end = it }, label = { Text("结束（时）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    }
                    Text("支持跨夜，例如 22 至 8；起止相同表示全天可提醒。勿扰期间收到的阈值提醒会延后，过期数据不会补发。", color = colors.mutedText, fontSize = 12.sp)
                    ReminderToggle("后台检查规则订阅", options.backgroundSubscriptions) { options = options.copy(backgroundSubscriptions = it) }
                    Text("有网络时检查已到更新间隔的订阅；间隔设为 0 的订阅仍只手动更新。后台检查和提醒可能因系统省电而延迟。", color = colors.mutedText, fontSize = 12.sp)
                }
            }
            HapticButton(modifier = Modifier.fillMaxWidth(), onClick = {
                runCatching {
                    val updated = options.copy(balanceThreshold = balance.toDoubleOrNull() ?: error("请输入正确的余额阈值"),
                        dataThresholdMb = data.toDoubleOrNull() ?: error("请输入正确的流量阈值"),
                        queryIntervalDays = days.toIntOrNull() ?: error("请输入正确的查询天数"),
                        reminderHour = hour.toIntOrNull() ?: error("请输入正确的提醒时间"),
                        quietStart = start.toIntOrNull() ?: error("请输入正确的勿扰开始时间"),
                        quietEnd = end.toIntOrNull() ?: error("请输入正确的勿扰结束时间"),
                        repeatHours = repeat.toIntOrNull() ?: error("请输入正确的重复间隔")).validate()
                    Reminders.save(context, updated)
                    options = updated
                    status = "提醒设置已保存"
                    if (updated.enabled && Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) request.launch(Manifest.permission.POST_NOTIFICATIONS)
                }.onFailure { status = it.message ?: "提醒设置保存失败" }
            }) { Text("保存提醒设置") }
            status?.let { Text(it, color = colors.text) }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ReminderToggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, Modifier.weight(1f).padding(top = 12.dp, end = 8.dp), color = AppTheme.colors.text, fontWeight = FontWeight.Bold)
        HapticSwitch(checked, onChange)
    }
}

@Composable
private fun ReminderField(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
}
