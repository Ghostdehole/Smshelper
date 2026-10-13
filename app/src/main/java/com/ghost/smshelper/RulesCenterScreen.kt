package com.ghost.smshelper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RulesCenterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = AppTheme.colors
    val profiles by ConfigManager.profilesFlow.collectAsState()
    val subscriptions by ConfigManager.subscriptionsFlow.collectAsState()
    val revisions by ConfigManager.revisionsFlow.collectAsState()
    val preferences by SimPreferences.data.collectAsState()
    val pending by SimPreferences.pending.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var editor by remember { mutableStateOf<ConfigProfile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var jsonView by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<SubscriptionPreview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var subscriptionEdit by remember { mutableStateOf<Subscription?>(null) }
    var addingSubscription by remember { mutableStateOf(false) }
    var deleteProfile by remember { mutableStateOf<ConfigProfile?>(null) }
    var revisionProfile by remember { mutableStateOf<ConfigProfile?>(null) }
    var simEdit by remember { mutableStateOf<SimInfo?>(null) }
    var pendingIndex by remember { mutableStateOf<Int?>(null) }
    var sims by remember { mutableStateOf(getActiveSims(context)) }
    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    fun report(success: Boolean, message: String) = toast(if (success) message else ConfigManager.lastError.value ?: "操作失败，请重试")
    LaunchedEffect(Unit) { ConfigManager.init(context); SimPreferences.init(context) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val bytes = ByteArrayOutputStream()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(bytes.size() + count <= ConfigValidation.MAX_PROFILE_BYTES) { "规则不能超过 1 MB" }
                        bytes.write(buffer, 0, count)
                    }
                } ?: error("无法打开文件")
                val json = bytes.toString("UTF-8")
                ConfigProfile("", "导入的个人规则", "个人编辑", json, ConfigValidation.parse(json))
            } }
            result.onSuccess { editor = it; creating = true }.onFailure { toast(it.message ?: "规则读取失败") }
        }
    }
    if (creating || editor != null) {
        RuleEditorScreen(editor, onBack = { editor = null; creating = false }, onSaved = { editor = null; creating = false; toast("个人规则已保存，请在 SIM 页面选择使用") })
        return
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(palette.background).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HapticTextButton(onClick = onBack) { Text("‹ 返回设置") }
        Text("规则与 SIM 中心", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        Text("先编辑和测试，再为每张 SIM 选择规则。订阅更新会保留历史版本。", color = palette.mutedText)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("规则", "订阅", "SIM").forEachIndexed { index, label ->
                CartoonButton(label, Modifier.weight(1f), color = if (tab == index) palette.primary else palette.button) { tab = index }
            }
        }
        when (tab) {
            0 -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HapticTextButton(onClick = { creating = true }) { Text("＋ 新建规则") }
                    HapticTextButton(onClick = { importer.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }) { Text("文件导入") }
                    HapticTextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val json = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        runCatching { ConfigProfile("", "剪贴板个人规则", "个人编辑", json, ConfigValidation.parse(json)) }
                            .onSuccess { editor = it; creating = true }.onFailure { toast("剪贴板中没有有效规则") }
                    }) { Text("剪贴板导入") }
                    HapticTextButton(onClick = { report(ConfigManager.importDefaultProfiles(context), "内置规则已恢复，个人规则保持不变") }) { Text("恢复内置") }
                    HapticTextButton(onClick = {
                        val json = ConfigManager.getCurrentProfileJson()
                        if (json.isBlank()) toast("请先在总览选择 SIM") else copyRule(context, json)
                    }) { Text("复制当前规则") }
                }
                profiles.forEach { profile ->
                    CartoonCard(Modifier.fillMaxWidth(), backgroundColor = palette.surface) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(profile.name, fontWeight = FontWeight.Bold)
                            Text(profile.source, color = palette.mutedText, style = MaterialTheme.typography.bodySmall)
                            Text("${profile.carriers.joinToString { it.name }} · ${profile.carriers.sumOf { it.categories.sumOf { category -> category.commands.size } }} 条指令", style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                HapticTextButton(onClick = { editor = profile }) { Text(if (profile.source == "个人编辑") "编辑 / 测试" else "复制编辑 / 测试") }
                                HapticTextButton(onClick = { jsonView = profile.jsonContent }) { Text("JSON") }
                                HapticTextButton(onClick = { revisionProfile = profile }) { Text("历史 ${revisions.count { it.profileId == profile.id }}") }
                                HapticTextButton(onClick = { deleteProfile = profile }) { Text("删除", color = palette.danger) }
                            }
                        }
                    }
                }
                if (profiles.isEmpty()) Text("还没有规则，可以恢复内置或导入规则文件。")
            }
            1 -> {
                CartoonButton("＋ 添加订阅", color = palette.primary) { addingSubscription = true }
                Text("更新间隔为 0 时仅手动更新。自动检查仅在允许运行时执行；每次更新保留最近 10 个历史版本（总计约 4 MB）。", color = palette.mutedText, style = MaterialTheme.typography.bodySmall)
                subscriptions.forEach { sub ->
                    CartoonCard(Modifier.fillMaxWidth(), backgroundColor = palette.surface) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(sub.name, fontWeight = FontWeight.Bold)
                            Text(sub.url, style = MaterialTheme.typography.bodySmall, color = palette.mutedText)
                            Text("${if (sub.intervalMinutes == 0) "手动更新" else "每 ${sub.intervalMinutes} 分钟检查"} · ${if (sub.lastUpdateTime == 0L) "尚未同步" else formatRuleTime(sub.lastUpdateTime)}", style = MaterialTheme.typography.bodySmall)
                            FlowRow {
                                HapticTextButton(onClick = { subscriptionEdit = sub }) { Text("设置") }
                                HapticTextButton(enabled = !busy, onClick = {
                                    busy = true
                                    scope.launch { try {
                                        preview = ConfigManager.previewSubscription(context, sub.url)
                                        if (preview == null) toast(ConfigManager.lastError.value ?: "更新失败")
                                    } finally { busy = false } }
                                }) { Text("检查更新") }
                                HapticTextButton(onClick = { report(ConfigManager.removeSubscription(context, sub.url), "已取消订阅，已下载规则继续保留") }) { Text("取消订阅", color = palette.danger) }
                            }
                        }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (subscriptions.isEmpty()) Text("添加规则链接后，可以先查看变化再应用。个人编辑副本不会被订阅覆盖。")
            }
            else -> {
                HapticTextButton(onClick = { sims = getActiveSims(context) }) { Text("刷新 SIM 列表") }
                if (sims.isEmpty()) Text("未获取 SIM，请在设置中允许电话权限，然后刷新。")
                sims.forEach { sim ->
                    val pref = preferences[sim.subId] ?: SimPreference(sim.subId)
                    CartoonCard(Modifier.fillMaxWidth(), backgroundColor = palette.surface, onClick = { simEdit = sim }) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(pref.nickname.ifBlank { sim.displayName }, fontWeight = FontWeight.Bold)
                            Text("卡槽 ${sim.slotIndex + 1} · ${sim.carrierName} · 每月 ${pref.billingDay} 日结算", color = palette.mutedText)
                            val identifier = CarrierIdentity.identifier(sim.mcc, sim.mnc, sim.carrierName)
                            val selectedId = pref.profileId?.takeIf { id -> pref.identifier == identifier && profiles.any { it.id == id && it.carriers.any { c -> c.identifier == identifier } } }
                                ?: ConfigManager.getSelections()[identifier]?.profileId
                            Text("当前规则：${profiles.firstOrNull { it.id == selectedId }?.name ?: "未匹配"}")
                            Text("点击编辑昵称、颜色、账单周期与独立规则", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                pending.forEachIndexed { index, pref ->
                    CartoonCard(Modifier.fillMaxWidth(), backgroundColor = palette.surfaceAlt) {
                        Column(Modifier.padding(16.dp)) {
                            Text("待绑定：${pref.nickname.ifBlank { "备份 SIM ${pref.subId}" }}", fontWeight = FontWeight.Bold)
                            Text("备份的 SIM 标识不会自动用于当前设备。", style = MaterialTheme.typography.bodySmall)
                            Row {
                                HapticTextButton(onClick = { pendingIndex = index }) { Text("选择当前 SIM") }
                                HapticTextButton(onClick = { if (!SimPreferences.discardPending(context, index)) toast("删除失败") }) { Text("移除待绑定项", color = palette.danger) }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
    jsonView?.let { json ->
        AlertDialog(onDismissRequest = { jsonView = null }, title = { Text("规则 JSON") }, text = {
            Text(json, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
        }, confirmButton = { HapticTextButton(onClick = { copyRule(context, json) }) { Text("复制") } }, dismissButton = { HapticTextButton(onClick = { jsonView = null }) { Text("关闭") } })
    }
    deleteProfile?.let { profile ->
        AlertDialog(onDismissRequest = { deleteProfile = null }, title = { Text("删除 ${profile.name}？") }, text = { Text("使用此规则的 SIM 将回退到可用的运营商默认规则。此规则的历史版本也会删除。") },
            confirmButton = { HapticTextButton(onClick = { report(ConfigManager.deleteProfile(context, profile.id), "规则已删除"); deleteProfile = null }) { Text("删除") } },
            dismissButton = { HapticTextButton(onClick = { deleteProfile = null }) { Text("取消") } })
    }
    preview?.let { value ->
        AlertDialog(onDismissRequest = { preview = null }, title = { Text("更新预览 · ${value.name}") }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(RuleEditorCodec.summary(value.json))
                RuleEditorCodec.changes(value.previousJson, value.json).forEach { Text("• $it") }
                HapticTextButton(onClick = { jsonView = value.json }) { Text("查看完整新 JSON") }
            }
        }, confirmButton = { HapticTextButton(onClick = { report(ConfigManager.applySubscriptionPreview(context, value), "订阅已更新"); preview = null }) { Text("应用更新") } },
            dismissButton = { HapticTextButton(onClick = { preview = null }) { Text("暂不更新") } })
    }
    if (addingSubscription || subscriptionEdit != null) SubscriptionEditorDialog(subscriptionEdit,
        onDismiss = { addingSubscription = false; subscriptionEdit = null }, onSave = { url, name, interval ->
            if (ConfigManager.addOrUpdateSubscription(context, url, name, interval)) {
                addingSubscription = false; subscriptionEdit = null; toast("订阅已保存，点击检查更新下载规则")
            } else toast(ConfigManager.lastError.value ?: "保存失败")
        })
    revisionProfile?.let { profile ->
        AlertDialog(onDismissRequest = { revisionProfile = null }, title = { Text("${profile.name} · 历史版本") }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val history = revisions.filter { it.profileId == profile.id }.asReversed()
                if (history.isEmpty()) Text("暂无历史版本。个人编辑和订阅更新前会自动保存。")
                history.forEach { revision ->
                    Text("${formatRuleTime(revision.createdAt)} · ${revision.reason}", fontWeight = FontWeight.Bold)
                    Text(RuleEditorCodec.summary(revision.json), style = MaterialTheme.typography.bodySmall)
                    Row { HapticTextButton(onClick = { jsonView = revision.json }) { Text("查看") }
                        HapticTextButton(onClick = { report(ConfigManager.rollbackProfile(context, revision.id), "已恢复该版本；当前版本也已保留"); revisionProfile = null }) { Text("恢复此版本") } }
                }
            }
        }, confirmButton = { HapticTextButton(onClick = { revisionProfile = null }) { Text("关闭") } })
    }
    simEdit?.let { sim -> SimPreferenceDialog(sim, preferences[sim.subId] ?: SimPreference(sim.subId), profiles, onDismiss = { simEdit = null }, onSave = { preference ->
        if (SimPreferences.update(context, preference)) { simEdit = null; toast("SIM 设置已保存") } else toast("SIM 设置保存失败")
    }) }
    pendingIndex?.let { index -> AlertDialog(onDismissRequest = { pendingIndex = null }, title = { Text("绑定到当前 SIM") }, text = {
        Column {
            Text("确认选择后，将替换该 SIM 的昵称、颜色、账单周期和规则选择。", style = MaterialTheme.typography.bodySmall)
            if (sims.isEmpty()) Text("请先允许电话权限并刷新 SIM 列表。")
            sims.forEach { sim -> HapticTextButton(onClick = {
                if (SimPreferences.mapPending(context, index, sim.subId)) { pendingIndex = null; toast("已绑定，请检查规则选择") } else toast("绑定失败")
            }) { Text("卡槽 ${sim.slotIndex + 1} · ${sim.displayName}") } } }
    }, confirmButton = { HapticTextButton(onClick = { pendingIndex = null }) { Text("取消") } }) }
}

private fun copyRule(context: Context, json: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Smshelper 规则", json))
    Toast.makeText(context, "规则已复制", Toast.LENGTH_SHORT).show()
}
private fun formatRuleTime(value: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(value))

@Composable
private fun SubscriptionEditorDialog(original: Subscription?, onDismiss: () -> Unit, onSave: (String, String, Int) -> Unit) {
    var name by remember { mutableStateOf(original?.name.orEmpty()) }
    var url by remember { mutableStateOf(original?.url.orEmpty()) }
    var interval by remember { mutableStateOf((original?.intervalMinutes ?: 0).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (original == null) "添加订阅" else "订阅设置") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
            OutlinedTextField(url, { url = it }, label = { Text("HTTP(S) 链接") }, enabled = original == null)
            OutlinedTextField(interval, { interval = it }, label = { Text("检查间隔（分钟，0 为手动）") }, singleLine = true)
            error?.let { Text(it, color = AppTheme.colors.danger) }
        }
    }, confirmButton = { HapticTextButton(onClick = {
        val minutes = interval.toIntOrNull()
        if (name.isBlank() || name.length > 256 || ConfigValidation.subscriptionUrl(url) == null || minutes == null || minutes !in 0..525600) error = "请检查名称、链接和间隔（0 至 525600）"
        else onSave(url, name, minutes)
    }) { Text("保存") } }, dismissButton = { HapticTextButton(onClick = onDismiss) { Text("取消") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimPreferenceDialog(sim: SimInfo, original: SimPreference, profiles: List<ConfigProfile>, onDismiss: () -> Unit, onSave: (SimPreference) -> Unit) {
    var nickname by remember { mutableStateOf(original.nickname) }
    var color by remember { mutableStateOf(original.colorHex) }
    var day by remember { mutableStateOf(original.billingDay.toString()) }
    var profileId by remember { mutableStateOf(original.profileId) }
    var error by remember { mutableStateOf<String?>(null) }
    val identifier = CarrierIdentity.identifier(sim.mcc, sim.mnc, sim.carrierName)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("卡槽 ${sim.slotIndex + 1} · SIM 设置") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(nickname, { nickname = it }, label = { Text("昵称（留空显示系统名称）") }, singleLine = true)
            OutlinedTextField(color, { color = it }, label = { Text("标签颜色 #RRGGBB") }, singleLine = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("#4DABF7", "#FF8FBA", "#69D5B1", "#FFB547", "#B39DDB").forEach { hex ->
                HapticTextButton(onClick = { color = hex }) { Text("●", color = Color(android.graphics.Color.parseColor(hex))) }
            } }
            OutlinedTextField(day, { day = it }, label = { Text("账单周期起始日（1—31）") }, singleLine = true)
            Text("月份没有该日期时使用当月最后一天。", style = MaterialTheme.typography.bodySmall)
            Text("此 SIM 独立规则", fontWeight = FontWeight.Bold)
            HapticTextButton(onClick = { profileId = null }) { Text(if (profileId == null) "✓ 使用运营商默认规则" else "使用运营商默认规则") }
            profiles.filter { profile -> profile.carriers.any { it.identifier == identifier } }.forEach { profile ->
                HapticTextButton(onClick = { profileId = profile.id }) { Text("${if (profileId == profile.id) "✓ " else ""}${profile.name}") }
            }
            if (identifier == null) Text("无法识别运营商，请检查 SIM 信息。")
            error?.let { Text(it, color = AppTheme.colors.danger) }
        }
    }, confirmButton = { HapticTextButton(onClick = {
        val billingDay = day.toIntOrNull()
        if (nickname.length > 64 || !color.matches(Regex("#[0-9a-fA-F]{6}")) || billingDay == null || billingDay !in 1..31) error = "昵称最多 64 字，颜色为 #RRGGBB，结算日为 1 至 31"
        else onSave(original.copy(nickname = nickname.trim(), colorHex = color.uppercase(), billingDay = billingDay, profileId = profileId, identifier = if (profileId == null) null else identifier))
    }) { Text("保存") } }, dismissButton = { HapticTextButton(onClick = onDismiss) { Text("取消") } })
}
