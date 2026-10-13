package com.ghost.smshelper

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuleEditorScreen(profile: ConfigProfile?, onBack: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val palette = AppTheme.colors
    val initial = remember(profile) { profile?.carriers ?: listOf(CarrierConfig("CMCC", "中国移动", "10086", emptyList(), emptyMap(), emptyList())) }
    var carriers by remember(profile) { mutableStateOf(initial) }
    var name by remember(profile) { mutableStateOf(profile?.let { if (it.source == "个人编辑") it.name else "${it.name} · 我的副本" } ?: "我的规则") }
    val initialName = remember(profile) { name }
    var carrierIndex by remember { mutableIntStateOf(0) }
    var expandedCategory by remember { mutableIntStateOf(-1) }
    var commandEdit by remember { mutableStateOf<Triple<Int, Int?, ActionCommand>?>(null) }
    var addingCategory by remember { mutableStateOf(false) }
    var jsonEditing by remember { mutableStateOf(false) }
    var jsonDraft by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var savePreview by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val dirty = carriers != initial || name != initialName
    val carrier = carriers[carrierIndex.coerceIn(carriers.indices)]
    var syncDraft by remember(carrierIndex) { mutableStateOf(carrier.syncCommands.joinToString("\n")) }
    fun change(value: CarrierConfig) { carriers = carriers.toMutableList().also { it[carrierIndex] = value }; error = null }
    fun back() { if (dirty) discard = true else onBack() }
    BackHandler { back() }
    Column(Modifier.fillMaxSize().background(palette.background).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HapticTextButton(onClick = { back() }) { Text("‹ 返回规则中心") }
        Text("规则编辑与测试", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        Text(if (profile?.source == "个人编辑") "保存前会自动保留上一个版本。" else "保存为个人副本，内置与订阅规则保持独立。", color = palette.mutedText)
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("规则集名称") }, singleLine = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            carriers.forEachIndexed { index, item ->
                HapticTextButton(onClick = { carrierIndex = index; expandedCategory = -1 }) { Text("${if (carrierIndex == index) "✓ " else ""}${item.name}") }
            }
            HapticTextButton(onClick = {
                if (carriers.size < 64) {
                    carriers = carriers + CarrierConfig("CUSTOM_${carriers.size + 1}", "新运营商", "10086", emptyList(), emptyMap(), emptyList())
                    carrierIndex = carriers.lastIndex
                }
            }) { Text("＋ 运营商") }
        }
        CartoonCard(Modifier.fillMaxWidth(), backgroundColor = palette.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("运营商与查询组合", fontWeight = FontWeight.Bold)
                OutlinedTextField(carrier.name, { change(carrier.copy(name = it)) }, Modifier.fillMaxWidth(), label = { Text("运营商显示名称") }, singleLine = true)
                OutlinedTextField(carrier.identifier, { change(carrier.copy(identifier = it)) }, Modifier.fillMaxWidth(), label = { Text("标识：CMCC / CUCC / CTCC / CBN") }, singleLine = true)
                OutlinedTextField(carrier.targetNumber, { change(carrier.copy(targetNumber = it)) }, Modifier.fillMaxWidth(), label = { Text("指令接收号码") }, singleLine = true)
                OutlinedTextField(syncDraft, { text -> syncDraft = text; change(carrier.copy(syncCommands = text.lines().filter { it.isNotBlank() })) }, Modifier.fillMaxWidth(), label = { Text("一键查询组合，每行一条完整指令") }, minLines = 2)
                if (carriers.size > 1) HapticTextButton(onClick = {
                    carriers = carriers.filterIndexed { index, _ -> index != carrierIndex }; carrierIndex = 0; expandedCategory = -1
                }) { Text("移除此运营商", color = palette.danger) }
            }
        }
        CartoonCard(Modifier.fillMaxWidth(), backgroundColor = palette.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("短信解析规则", fontWeight = FontWeight.Bold)
                Text("数值放在第 1 个捕获组 (...) 中；流量包含 GB/MB 等单位；账单的第 1、2 组分别为月份、金额。留空表示不提取。", style = MaterialTheme.typography.bodySmall, color = palette.mutedText)
                (RuleEditorCodec.fieldLabels.keys + carrier.regexRules.keys).distinct().forEach { key ->
                    OutlinedTextField(carrier.regexRules[key].orEmpty(), { value ->
                        change(carrier.copy(regexRules = carrier.regexRules.toMutableMap().apply { if (value.isBlank()) remove(key) else put(key, value) }))
                    }, Modifier.fillMaxWidth(), label = { Text(RuleEditorCodec.fieldLabels[key] ?: key) }, minLines = 1, maxLines = 4)
                }
                CartoonButton("用短信样本测试", color = palette.secondary) { testing = true }
            }
        }
        Text("指令与参数", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        carrier.categories.forEachIndexed { categoryIndex, category ->
            CartoonCard(Modifier.fillMaxWidth(), backgroundColor = palette.surface) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HapticTextButton(onClick = { expandedCategory = if (expandedCategory == categoryIndex) -1 else categoryIndex }) {
                        Text("${if (expandedCategory == categoryIndex) "▾" else "▸"} ${category.title} · ${category.commands.size} 条", fontWeight = FontWeight.Bold)
                    }
                    if (expandedCategory == categoryIndex) {
                        OutlinedTextField(category.title, { title -> change(carrier.copy(categories = carrier.categories.toMutableList().also { it[categoryIndex] = category.copy(title = title) })) }, Modifier.fillMaxWidth(), label = { Text("分类名称") }, singleLine = true)
                        category.commands.forEachIndexed { commandIndex, command ->
                            Text(command.name, fontWeight = FontWeight.Bold)
                            Text("${command.code}${if (command.requiresInput) " · 需要参数" else ""}", color = palette.mutedText, style = MaterialTheme.typography.bodySmall)
                            FlowRow {
                                HapticTextButton(onClick = { commandEdit = Triple(categoryIndex, commandIndex, command) }) { Text("编辑") }
                                HapticTextButton(enabled = commandIndex > 0, onClick = {
                                    val commands = category.commands.toMutableList(); val moved = commands.removeAt(commandIndex); commands.add(commandIndex - 1, moved)
                                    change(carrier.copy(categories = carrier.categories.toMutableList().also { it[categoryIndex] = category.copy(commands = commands) }))
                                }) { Text("上移") }
                                HapticTextButton(onClick = { change(carrier.copy(categories = carrier.categories.toMutableList().also { it[categoryIndex] = category.copy(commands = category.commands.filterIndexed { index, _ -> index != commandIndex }) })) }) { Text("删除", color = palette.danger) }
                            }
                        }
                        FlowRow {
                            HapticTextButton(onClick = { commandEdit = Triple(categoryIndex, null, ActionCommand("", "")) }) { Text("＋ 指令") }
                            HapticTextButton(enabled = categoryIndex > 0, onClick = {
                                val categories = carrier.categories.toMutableList(); val moved = categories.removeAt(categoryIndex); categories.add(categoryIndex - 1, moved)
                                change(carrier.copy(categories = categories)); expandedCategory = categoryIndex - 1
                            }) { Text("分类上移") }
                            HapticTextButton(onClick = {
                                change(carrier.copy(categories = carrier.categories.filterIndexed { index, _ -> index != categoryIndex })); expandedCategory = -1
                            }) { Text("删除分类", color = palette.danger) }
                        }
                    }
                }
            }
        }
        CartoonButton("＋ 添加分类", color = palette.button) { addingCategory = true }
        CartoonButton("高级 JSON 编辑", color = palette.button) { jsonDraft = RuleEditorCodec.encode(carriers); jsonEditing = true }
        error?.let { Text(it, color = palette.danger) }
        CartoonButton("预览并保存", color = palette.primary) {
            runCatching {
                require(name.isNotBlank() && name.length <= 256) { "规则名称需要 1 至 256 字" }
                RuleEditorCodec.encode(carriers).also { ConfigValidation.parse(it) }
            }.onSuccess { savePreview = it }.onFailure { error = it.message ?: "规则校验失败" }
        }
        Spacer(Modifier.height(28.dp))
    }
    commandEdit?.let { (categoryIndex, commandIndex, command) ->
        RuleCommandDialog(command, onDismiss = { commandEdit = null }, onSave = { replacement ->
            val category = carrier.categories[categoryIndex]
            val commands = category.commands.toMutableList().also { if (commandIndex == null) it.add(replacement) else it[commandIndex] = replacement }
            change(carrier.copy(categories = carrier.categories.toMutableList().also { it[categoryIndex] = category.copy(commands = commands) }))
            commandEdit = null
        })
    }
    if (addingCategory) {
        var title by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { addingCategory = false }, title = { Text("添加指令分类") }, text = { OutlinedTextField(title, { title = it }, label = { Text("分类名称") }) },
            confirmButton = { HapticTextButton(enabled = title.isNotBlank() && title.length <= 256, onClick = {
                change(carrier.copy(categories = carrier.categories + CommandCategory(title.trim(), emptyList()))); expandedCategory = carrier.categories.size; addingCategory = false
            }) { Text("添加") } }, dismissButton = { HapticTextButton(onClick = { addingCategory = false }) { Text("取消") } })
    }
    if (jsonEditing) {
        var jsonError by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { jsonEditing = false }, title = { Text("高级 JSON 编辑") }, text = {
            Column { OutlinedTextField(jsonDraft, { if (it.length <= ConfigValidation.MAX_PROFILE_BYTES) jsonDraft = it }, Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 420.dp), label = { Text("完整规则 JSON") })
                jsonError?.let { Text(it, color = palette.danger) } }
        }, confirmButton = { HapticTextButton(onClick = {
            runCatching { ConfigValidation.parse(jsonDraft) }.onSuccess { carriers = it; carrierIndex = 0; syncDraft = it.first().syncCommands.joinToString("\n"); expandedCategory = -1; jsonEditing = false }.onFailure { jsonError = it.message ?: "JSON 无效" }
        }) { Text("应用到编辑器") } }, dismissButton = { HapticTextButton(onClick = { jsonEditing = false }) { Text("取消") } })
    }
    if (testing) RuleTestDialog(carrier, onDismiss = { testing = false })
    savePreview?.let { json ->
        AlertDialog(onDismissRequest = { savePreview = null }, title = { Text("保存预览 · $name") }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(RuleEditorCodec.summary(json))
                RuleEditorCodec.changes(profile?.jsonContent, json).forEach { Text("• $it") }
                Text(if (profile?.source == "个人编辑" && !profile.id.isBlank()) "更新当前个人规则，保留历史版本。" else "创建个人副本。保存后可在 SIM 页面单独选择。", color = palette.mutedText)
            }
        }, confirmButton = { HapticTextButton(onClick = {
            if (ConfigManager.saveEditedProfile(context, profile?.id?.takeIf { it.isNotBlank() }, name, json)) onSaved()
            else { error = ConfigManager.lastError.value ?: "保存失败"; savePreview = null }
        }) { Text("确认保存") } }, dismissButton = { HapticTextButton(onClick = { savePreview = null }) { Text("继续编辑") } })
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存的修改？") }, text = { Text("已保存的规则不会受到影响。") },
        confirmButton = { HapticTextButton(onClick = onBack) { Text("放弃修改") } }, dismissButton = { HapticTextButton(onClick = { discard = false }) { Text("继续编辑") } })
}

@Composable
private fun RuleCommandDialog(original: ActionCommand, onDismiss: () -> Unit, onSave: (ActionCommand) -> Unit) {
    var name by remember { mutableStateOf(original.name) }
    var code by remember { mutableStateOf(original.code) }
    var needsInput by remember { mutableStateOf(original.requiresInput) }
    var hint by remember { mutableStateOf(original.inputHint) }
    var sample by remember { mutableStateOf("") }
    val command = ActionCommand(code, name, needsInput, hint)
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("指令与参数") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("显示名称") })
            OutlinedTextField(code, { code = it }, label = { Text("短信指令 / 参数前缀") })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("发送前输入参数"); HapticSwitch(needsInput, { needsInput = it }) }
            if (needsInput) {
                OutlinedTextField(hint, { hint = it }, label = { Text("参数提示，例如：请输入年月") })
                OutlinedTextField(sample, { sample = it }, label = { Text("参数样例（仅预览，不发送）") })
                Text("最终内容：$code${sample.trim()}", fontWeight = FontWeight.Bold)
                if (sample.isNotBlank()) Text(CommandParameters.validate(command, sample) ?: "参数样例通过检查", color = AppTheme.colors.mutedText)
            }
            error?.let { Text(it, color = AppTheme.colors.danger) }
        }
    }, confirmButton = { HapticTextButton(onClick = {
        if (name.isBlank() || name.length > 512 || code.isBlank() || code.length > 1024 || name.any { it < ' ' } || code.any { it < ' ' } || hint.length > 512) error = "请填写有效名称和指令，内容不能换行"
        else onSave(command)
    }) { Text("应用") } }, dismissButton = { HapticTextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun RuleTestDialog(carrier: CarrierConfig, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val messages by SmsRepository.messages.collectAsState()
    var sample by remember { mutableStateOf("") }
    var showMessages by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("短信解析测试 · ${carrier.name}") }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("测试只展示命中结果，不更新账户数据，也不会发送短信。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(sample, { if (it.length <= 32768) { sample = it; result = null } }, Modifier.fillMaxWidth(), minLines = 4, maxLines = 10, label = { Text("粘贴运营商短信原文") })
            HapticTextButton(onClick = { showMessages = !showMessages }) { Text(if (showMessages) "收起历史短信" else "选择历史短信") }
            if (showMessages) {
                if (messages.isEmpty()) Text("还没有历史短信")
                messages.take(30).forEach { message -> HapticTextButton(onClick = { sample = message.body; showMessages = false; result = null }) { Text("${message.sender} · ${message.body.take(70)}") } }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }, confirmButton = { HapticTextButton(enabled = !busy && sample.isNotBlank(), onClick = {
        busy = true
        val text = sample
        scope.launch {
            try {
                result = withContext(Dispatchers.Default) {
                    try {
                        ConfigValidation.parse(RuleEditorCodec.encode(listOf(carrier)))
                        val parsed = SmsParser.parseResult(ParsedInfo(), text, carrier.regexRules)
                        val values = mapOf("balance" to "${parsed.info.balance} 元", "consumption" to "${parsed.info.consumption} 元", "usedData" to "${parsed.info.usedDataMb} MB",
                            "remainingData" to "${parsed.info.remainingTotalDataMb} MB", "generalData" to "${parsed.info.generalDataMb} MB", "voiceInclude" to "${parsed.info.totalVoice} 分钟",
                            "voiceRemain" to "${parsed.info.remainingVoice} 分钟", "bill" to parsed.info.historicalBills.joinToString { "${it.month}：${it.amount} 元" }, "traffic" to parsed.info.trafficDetails.joinToString("\n"))
                        buildString {
                            appendLine(if (parsed.matched) "已解析 ${parsed.matchedFields.size} 个字段" else "没有解析到有效字段，请检查捕获组及数值单位。")
                            parsed.matchedFields.forEach { key -> appendLine("${RuleEditorCodec.fieldLabels[key] ?: if (key == "traffic") "流量详情" else key}：${values[key].orEmpty()}") }
                            appendLine("\n正则命中片段：")
                            carrier.regexRules.forEach { (key, rule) ->
                                val hits = Regex(rule).findAll(text).take(3).map { it.value.take(300) }.toList()
                                appendLine("${RuleEditorCodec.fieldLabels[key] ?: key}：${if (hits.isEmpty()) "未命中" else hits.joinToString(" / ")}")
                            }
                        }
                    } catch (_: StackOverflowError) { "正则计算失败，请简化表达式。" }
                    catch (error: Exception) { error.message ?: "规则测试失败" }
                }
            } finally { busy = false }
        }
    }) { Text("运行测试") } }, dismissButton = { HapticTextButton(onClick = onDismiss) { Text("关闭") } })
}
