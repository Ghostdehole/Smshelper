package com.ghost.smshelper

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackupCenterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    var encrypted by remember { mutableStateOf(true) }
    var password by remember { mutableStateOf("") }
    var repeatedPassword by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var exportToken by rememberSaveable { mutableStateOf<String?>(null) }
    var importPayload by remember { mutableStateOf<String?>(null) }
    var importPassword by remember { mutableStateOf("") }
    var decoded by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<BackupPreview?>(null) }
    var merge by remember { mutableStateOf(true) }
    var mapping by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    val activeSims = remember(preview) { getActiveSims(context) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { FullBackupCache.cleanup(context) } }
    BackHandler { if (!busy) onBack() }

    fun inspect(source: String, secret: String?) {
        busy = true; status = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val chars = secret?.toCharArray()
                    try {
                        val document = FullBackupCodec.decode(source, chars)
                        document to FullBackupManager.preview(document)
                    } finally { chars?.fill('\u0000') }
                }
                decoded = result.first; preview = result.second; mapping = emptyMap(); merge = true
                importPayload = null; importPassword = ""
            } catch (error: Exception) { status = error.message ?: "无法读取完整备份" }
            finally { busy = false }
        }
    }

    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val token = exportToken
        exportToken = null
        if (uri != null && token != null) {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val payload = FullBackupCache.read(context, token)
                        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "无法写入所选文件" }
                            .bufferedWriter(Charsets.UTF_8).use { it.write(payload) }
                    }
                    status = "完整备份已导出。请妥善保存备份密码。"
                    password = ""; repeatedPassword = ""
                } catch (error: Exception) { status = error.message ?: "备份写入失败" }
                finally { FullBackupCache.remove(context, token); busy = false }
            }
        } else if (token != null) FullBackupCache.remove(context, token)
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true; status = null
            scope.launch {
                try {
                    val source = withContext(Dispatchers.IO) {
                        requireNotNull(context.contentResolver.openInputStream(uri)).use { readUtf8Text(it, FullBackupCodec.MAX_FILE_BYTES) }
                    }
                    if (FullBackupCodec.encrypted(source)) { importPayload = source; busy = false }
                    else inspect(source, null)
                } catch (error: Exception) { status = error.message ?: "文件读取失败"; busy = false }
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = colors.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth()) {
                HapticTextButton(onClick = onBack, enabled = !busy) { Text("← 返回") }
                Text("备份与迁移", Modifier.padding(top = 10.dp), fontSize = 22.sp, fontWeight = FontWeight.Black, color = colors.text)
            }
            CartoonCard(backgroundColor = colors.surface) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("把整个工作台带走", fontWeight = FontWeight.Black, fontSize = 18.sp, color = colors.text)
                    Text("保存短信与查询记录、规则订阅、SIM 设置、主题、布局、收藏和提醒偏好。PIN 与设备密钥不会导出。", color = colors.mutedText)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("使用密码加密", Modifier.padding(top = 12.dp), color = colors.text)
                        HapticSwitch(encrypted, { if (!busy) encrypted = it })
                    }
                    if (encrypted) {
                        OutlinedTextField(password, { password = it }, label = { Text("备份密码（至少 8 位）") }, enabled = !busy,
                            singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(repeatedPassword, { repeatedPassword = it }, label = { Text("再输入一次密码") }, enabled = !busy,
                            singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                        Text("换手机也能用同一密码恢复；忘记密码无法找回。", color = colors.mutedText, fontSize = 12.sp)
                    } else Text("当前将导出明文文件，文件中包含短信内容。", color = colors.danger, fontSize = 12.sp)
                    HapticButton(enabled = !busy && (!encrypted || password.length in 8..1024 && password == repeatedPassword), onClick = {
                        busy = true; status = null
                        val secret = if (encrypted) password.toCharArray() else null
                        scope.launch {
                            try {
                                exportToken = withContext(Dispatchers.IO) {
                                    try { FullBackupCache.save(context, FullBackupCodec.encode(FullBackupManager.export(context), secret)) }
                                    finally { secret?.fill('\u0000') }
                                }
                                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
                                createFile.launch("Smshelper-$stamp${if (encrypted) "-encrypted" else ""}.json")
                            } catch (error: Exception) { status = error.message ?: "备份生成失败" }
                            finally { busy = false }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("导出完整备份") }
                }
            }
            CartoonCard(backgroundColor = colors.surfaceAlt) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("从备份恢复", fontWeight = FontWeight.Black, fontSize = 18.sp, color = colors.text)
                    Text("先检查内容，再选择合并或覆盖。导入的 SIM 编号不会直接套用到本机，需要确认对应关系。", color = colors.mutedText)
                    HapticOutlinedButton(enabled = !busy, onClick = { openFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) { Text("选择完整备份文件") }
                    Text("旧版 TXT 短信导入和导出仍保留在设置中。", fontSize = 12.sp, color = colors.mutedText)
                }
            }
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在处理，请稍候…", color = colors.mutedText) }
            status?.let { Text(it, color = colors.text) }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (importPayload != null) AlertDialog(onDismissRequest = { if (!busy) { importPayload = null; importPassword = "" } },
        containerColor = colors.dialog, title = { Text("解锁备份") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(importPassword, { importPassword = it }, label = { Text("备份密码") }, enabled = !busy, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                status?.let { Text(it, color = colors.danger) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }, confirmButton = { HapticTextButton(enabled = !busy && importPassword.isNotEmpty(), onClick = { inspect(importPayload!!, importPassword) }) { Text("检查内容") } },
        dismissButton = { HapticTextButton(enabled = !busy, onClick = { importPayload = null; importPassword = "" }) { Text("取消") } })

    preview?.let { details ->
        AlertDialog(onDismissRequest = { if (!busy) { preview = null; decoded = null } }, containerColor = colors.dialog,
            title = { Text("恢复前确认", fontWeight = FontWeight.Black) }, text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("备份时间：${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(details.createdAt))}\n短信：${details.messageCount} 条\n配置区域：${details.sections.size} 项")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (merge) "合并 · 保留已有设置" else "覆盖 · 替换本地内容", Modifier.weight(1f).padding(top = 10.dp))
                        HapticSwitch(merge, { if (!busy) merge = it })
                    }
                    Text(if (merge) "重复记录去重；同名 ID 的本地收藏、主题选中和 SIM 设置优先保留。" else "将替换本应用的短信、查询记录与备份包含的配置；PIN 保持不变。", color = if (merge) colors.mutedText else colors.danger, fontSize = 12.sp)
                    if (details.subscriptionIds.isNotEmpty()) Text("点击选择每张旧卡对应的本机 SIM；不绑定的历史数据会保留为未分配记录。", fontSize = 12.sp, color = colors.mutedText)
                    details.subscriptionIds.forEach { oldId ->
                        val current = mapping[oldId]
                        val choices = listOf<Int?>(null) + activeSims.map { it.subId }.filter { it == current || it !in mapping.values }
                        HapticOutlinedButton(enabled = !busy, onClick = {
                            val next = choices[(choices.indexOf(current) + 1).mod(choices.size)]
                            mapping = if (next == null) mapping - oldId else mapping + (oldId to next)
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("旧卡 $oldId → ${activeSims.firstOrNull { it.subId == current }?.let { "${it.displayName} · 卡槽 ${it.slotIndex + 1}" } ?: "暂不绑定"}")
                        }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }, confirmButton = { HapticTextButton(enabled = !busy, onClick = {
                val document = decoded ?: return@HapticTextButton
                busy = true; status = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO + NonCancellable) { FullBackupManager.restore(context, document, merge, mapping) }
                        preview = null; decoded = null
                        status = "恢复完成。未绑定的旧 SIM 可在 SIM 设置中继续绑定。"
                        Toast.makeText(context, "完整备份恢复完成", Toast.LENGTH_LONG).show()
                    } catch (error: Exception) { status = error.message ?: "恢复失败"; preview = null; decoded = null }
                    finally { busy = false }
                }
            }) { Text(if (merge) "确认合并" else "确认覆盖", color = if (merge) colors.primary else colors.danger) } },
            dismissButton = { HapticTextButton(enabled = !busy, onClick = { preview = null; decoded = null }) { Text("取消") } })
    }
}
