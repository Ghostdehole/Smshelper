@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ghost.smshelper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PaletteStateSaver = Saver<AppPalette, String>(
    save = { ThemePaletteCodec.encode(it) },
    restore = { runCatching { ThemePaletteCodec.decode(it) }.getOrNull() }
)

/** Drafts live only in this screen; navigating away never silently applies them. */
@Composable
fun ThemeWorkshopScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = actionHaptics()
    val original = rememberSaveable(saver = PaletteStateSaver) { ThemeManager.currentPalette.value }
    var draft by rememberSaveable(stateSaver = PaletteStateSaver) { mutableStateOf(original) }
    var resetPalette by rememberSaveable(stateSaver = PaletteStateSaver) { mutableStateOf(original) }
    val undo = remember { mutableStateListOf<AppPalette>() }
    val custom by ThemeManager.customPalettes.collectAsState()
    var advanced by remember { mutableStateOf(false) }
    var autoText by remember { mutableStateOf(true) }
    var preview by remember { mutableIntStateOf(0) }
    var selectedRole by remember { mutableStateOf<ThemeRole?>(null) }
    var showDiscard by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var saveName by remember { mutableStateOf("${original.name} · 我的配色".take(40)) }
    var deletePalette by remember { mutableStateOf<AppPalette?>(null) }
    var exportPayload by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun update(next: AppPalette) {
        if (next == draft) return
        undo.add(draft)
        if (undo.size > 50) undo.removeAt(0)
        draft = next
    }
    fun leave() { if (draft != original) showDiscard = true else onBack() }
    fun toast(message: String) { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    fun acceptImport(text: String) {
        runCatching { ThemePaletteCodec.decode(text) }.onSuccess {
            update(it); resetPalette = it; saveName = it.name; showImport = false; importError = null
            toast("已载入预览，保存后才会应用")
        }.onFailure { importError = it.message ?: "主题格式无效" }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) { runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(exportPayload) }
                    ?: error("无法打开目标文件")
            } }
            busy = false
            toast(if (result.isSuccess) "主题已导出" else "导出失败：${result.exceptionOrNull()?.message}")
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) { runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 32768) { "主题文件不能超过 32 KB" }
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8.name())
                } ?: error("无法读取主题文件")
            } }
            busy = false
            result.onSuccess { acceptImport(it) }.onFailure { toast("导入失败：${it.message}") }
            importError?.let { toast(it) }
        }
    }
    BackHandler { leave() }

    // Editor controls deliberately remain readable even when the draft has low contrast.
    ProvidePalette(ThemeManager.palettes[0]) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFFF7F8FA)) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    HapticTextButton(onClick = { leave() }) { Text("取消") }
                    Text("外观工作室", Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.Black)
                    FilledTonalButton(enabled = !busy, onClick = { haptic(HapticEvent.Tap);
                        val existing = (ThemeManager.palettes + custom).firstOrNull { it.id == draft.id && it.values == draft.values }
                        if (existing != null) { ThemeManager.applyPalette(context, existing); onBack() }
                        else { saveName = draft.name.take(40); showSave = true }
                    }) { Text("应用") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    item {
                        Text("选择底稿", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("保留漫画描边与硬阴影。当前修改只在预览中生效。", fontSize = 12.sp, color = Color(0xFF52616B))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                            ThemeManager.palettes.forEach { palette ->
                                HapticFilterChip(selected = resetPalette.id == palette.id, onClick = { update(palette); resetPalette = palette; saveName = "${palette.name} · 我的配色" },
                                    label = { Text(palette.name) }, leadingIcon = { Swatch(palette.primary, 16) })
                            }
                        }
                        if (custom.isNotEmpty()) {
                            Text("我的主题", Modifier.padding(top = 12.dp), fontWeight = FontWeight.Bold)
                            custom.forEach { palette ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    HapticFilterChip(selected = resetPalette.id == palette.id, onClick = { update(palette); resetPalette = palette; saveName = palette.name }, label = { Text(palette.name) }, leadingIcon = { Swatch(palette.primary, 16) }, modifier = Modifier.weight(1f))
                                    HapticTextButton(onClick = { deletePalette = palette }) { Text("删除") }
                                }
                            }
                        }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("实时预览", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            HapticTextButton(enabled = undo.isNotEmpty(), onClick = { draft = undo.removeAt(undo.lastIndex) }) { Text("撤销") }
                            HapticTextButton(onClick = { update(resetPalette) }) { Text("重置配色") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("总览", "指令", "信箱").forEachIndexed { index, title ->
                                HapticFilterChip(selected = preview == index, onClick = { preview = index }, label = { Text(title) })
                            }
                        }
                        Text("点击预览区域即可调色", fontSize = 12.sp, color = Color(0xFF52616B), modifier = Modifier.padding(bottom = 8.dp))
                        ThemePreview(draft, preview) { selectedRole = it }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("精细调色", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            HapticSwitch(checked = advanced, onCheckedChange = { advanced = it })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("自动搭配文字", fontWeight = FontWeight.Medium)
                                Text("联动文字与选中态；修改背景时同步卡片和气泡", fontSize = 12.sp, color = Color(0xFF52616B))
                            }
                            HapticSwitch(checked = autoText, onCheckedChange = { autoText = it })
                        }
                        val roles = if (advanced) ThemeRole.entries.toList() else listOf(ThemeRole.Primary, ThemeRole.Background, ThemeRole.Secondary, ThemeRole.Button)
                        roles.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                row.forEach { role ->
                                    HapticOutlinedButton(onClick = { selectedRole = role }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)) {
                                        Swatch(draft.color(role), 22)
                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(role.label, fontSize = 12.sp)
                                            Text(ThemeColorLogic.hex(draft.values.getValue(role)), fontSize = 11.sp, color = Color(0xFF52616B))
                                        }
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    item {
                        val warnings = ThemeColorLogic.contrastWarnings(draft)
                        if (warnings.isNotEmpty()) {
                            Surface(color = Color(0xFFFFF1DA), shape = RoundedCornerShape(14.dp)) {
                                Column(Modifier.padding(14.dp)) {
                                    Text("这些区域的文字可能难以辨认", fontWeight = FontWeight.Bold, color = Color(0xFF6D3800))
                                    Text(warnings.joinToString("；"), fontSize = 12.sp, color = Color(0xFF6D3800))
                                    Text("建议正文达到 4.5∶1。你仍可保留自己的配色，编辑器和恢复入口始终可读。", fontSize = 12.sp, color = Color(0xFF6D3800))
                                }
                            }
                        } else Text("✓ 当前预览的正文和按钮文字对比度良好", color = Color(0xFF176847), fontSize = 12.sp)
                    }
                    item {
                        Text("保存与分享", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HapticButton(onClick = { saveName = "${draft.name.removeSuffix(" · 我的配色")} · 我的配色".take(40); showSave = true }, modifier = Modifier.weight(1f)) { Text("另存并应用") }
                            HapticOutlinedButton(onClick = { exportPayload = ThemePaletteCodec.encode(draft); exportLauncher.launch("Smshelper-theme.json") }, modifier = Modifier.weight(1f), enabled = !busy) { Text("导出文件") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HapticOutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, modifier = Modifier.weight(1f), enabled = !busy) { Text("导入文件") }
                            HapticOutlinedButton(onClick = { importError = null; importText = ""; showImport = true }, modifier = Modifier.weight(1f)) { Text("粘贴导入") }
                        }
                        HapticTextButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Smshelper 主题", ThemePaletteCodec.encode(draft)))
                            toast("主题已复制")
                        }) { Text("复制主题 JSON") }
                        HapticTextButton(onClick = { update(ThemeManager.palettes[0]); resetPalette = ThemeManager.palettes[0] }) { Text("恢复经典元气黄预览") }
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
        selectedRole?.let { role ->
            ThemeColorPicker(role, draft.values.getValue(role), resetPalette.values.getValue(role), onDismiss = { selectedRole = null }) { value ->
                update(if (autoText) ThemeColorLogic.linked(draft, role, value) else draft.withColor(role, value))
                ThemeManager.rememberColor(context, value)
                selectedRole = null
            }
        }
        if (showDiscard) AlertDialog(onDismissRequest = { showDiscard = false }, title = { Text("放弃这次预览？") }, text = { Text("尚未应用的颜色修改会被丢弃，现有主题保持不变。") },
            confirmButton = { HapticTextButton(onClick = onBack) { Text("放弃修改") } }, dismissButton = { HapticTextButton(onClick = { showDiscard = false }) { Text("继续调色") } })
        if (showSave) AlertDialog(onDismissRequest = { showSave = false }, title = { Text("保存为我的主题") }, text = {
            Column {
                OutlinedTextField(saveName, onValueChange = { saveName = it.take(40) }, label = { Text("主题名称") }, singleLine = true)
                importError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("保存后立即应用，内置主题不会被覆盖。", fontSize = 12.sp)
            }
        }, confirmButton = { HapticTextButton(enabled = saveName.isNotBlank(), onClick = {
            runCatching { ThemeManager.saveCustom(context, draft, saveName) }.onSuccess { onBack() }.onFailure { importError = it.message }
        }) { Text("保存并应用") } }, dismissButton = { HapticTextButton(onClick = { showSave = false; importError = null }) { Text("取消") } })
        if (showImport) AlertDialog(onDismissRequest = { showImport = false }, title = { Text("粘贴主题 JSON") }, text = {
            Column {
                OutlinedTextField(importText, onValueChange = { importText = it.take(32769); importError = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp), label = { Text("主题内容") })
                importError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { HapticTextButton(enabled = importText.isNotBlank(), onClick = { acceptImport(importText) }) { Text("载入预览") } }, dismissButton = { HapticTextButton(onClick = { showImport = false }) { Text("取消") } })
        deletePalette?.let { palette -> AlertDialog(onDismissRequest = { deletePalette = null }, title = { Text("删除 ${palette.name}？") }, text = { Text("若它正在使用，将恢复内置主题。当前预览仍可另存。") },
            confirmButton = { HapticTextButton(onClick = { ThemeManager.deleteCustom(context, palette.id); deletePalette = null }) { Text("删除") } }, dismissButton = { HapticTextButton(onClick = { deletePalette = null }) { Text("取消") } }) }
    }
}

@Composable
private fun Swatch(color: Color, size: Int) {
    Box(Modifier.size(size.dp).background(color, CircleShape).border(1.dp, Color(0xFF5F6870), CircleShape))
}

@Composable
private fun ThemePreview(palette: AppPalette, page: Int, onPick: (ThemeRole) -> Unit) {
    val haptic = actionHaptics()
    ProvidePalette(palette) {
        Column(Modifier.fillMaxWidth().border(2.dp, palette.outline, RoundedCornerShape(18.dp)).background(palette.background, RoundedCornerShape(18.dp)).clickable { haptic(HapticEvent.Tap); onPick(ThemeRole.Background) }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("短信小助手", Modifier.weight(1f).clickable { haptic(HapticEvent.Tap); onPick(ThemeRole.Text) }, fontWeight = FontWeight.Black, fontSize = 20.sp, color = palette.text)
                Text("卡 1", Modifier.background(palette.simSelected, RoundedCornerShape(8.dp)).clickable { haptic(HapticEvent.Tap); onPick(ThemeRole.SimSelected) }.padding(8.dp), color = palette.text, fontSize = 12.sp)
            }
            when (page) {
                0 -> {
                    CartoonCard(backgroundColor = palette.surface, onClick = { onPick(ThemeRole.Surface) }) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("话费余额", color = palette.mutedText, modifier = Modifier.clickable { haptic(HapticEvent.Tap); onPick(ThemeRole.MutedText) })
                            Text("¥ 36.80", fontSize = 28.sp, fontWeight = FontWeight.Black, color = palette.text)
                            Spacer(Modifier.height(10.dp))
                            Text("剩余流量 12.6 GB", fontWeight = FontWeight.Bold, color = palette.text)
                            LinearProgressIndicator(progress = { 0.62f }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(8.dp).clickable { haptic(HapticEvent.Tap); onPick(ThemeRole.ChartData) }, color = palette.chartData, trackColor = palette.surfaceAlt)
                            Text("剩余语音 180 分钟", color = palette.text)
                            LinearProgressIndicator(progress = { 0.45f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(8.dp).clickable { haptic(HapticEvent.Tap); onPick(ThemeRole.ChartVoice) }, color = palette.chartVoice, trackColor = palette.surfaceAlt)
                        }
                    }
                    CartoonButton("一键查询", modifier = Modifier.testTag("theme-preview-primary"), color = palette.primary, onClick = { onPick(ThemeRole.Primary) })
                }
                1 -> {
                    Text("常用指令", color = palette.mutedText, modifier = Modifier.clickable { haptic(HapticEvent.Tap); onPick(ThemeRole.MutedText) })
                    listOf("查话费" to "查流量", "查套餐" to "查积分").forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                            CartoonButton(pair.first, modifier = Modifier.weight(1f), color = palette.button, onClick = { onPick(ThemeRole.Button) })
                            CartoonButton(pair.second, modifier = Modifier.weight(1f), color = palette.button, onClick = { onPick(ThemeRole.Button) })
                        }
                    }
                    CartoonCard(backgroundColor = palette.surfaceAlt, onClick = { onPick(ThemeRole.SurfaceAlt) }) { Text("收藏常用指令，下次一键找到", Modifier.fillMaxWidth().padding(14.dp), color = palette.text) }
                }
                else -> {
                    CartoonCard(backgroundColor = palette.inboxReceived, onClick = { onPick(ThemeRole.InboxReceived) }) {
                        Column(Modifier.padding(14.dp)) {
                            Text("10086 · 卡 1", fontWeight = FontWeight.Bold, color = palette.text)
                            Text("您的账户余额为 36.80 元，剩余流量 12.6 GB。", color = palette.text, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    CartoonCard(backgroundColor = palette.inboxSent, onClick = { onPick(ThemeRole.InboxSent) }, modifier = Modifier.align(Alignment.End)) { Text("查询余额", Modifier.padding(14.dp), color = palette.text) }
                    Text("刚刚更新 · 预览示例", color = palette.mutedText, fontSize = 12.sp)
                }
            }
            Row(Modifier.fillMaxWidth().background(palette.navBackground, RoundedCornerShape(12.dp)).clickable { haptic(HapticEvent.Tap); onPick(ThemeRole.NavBackground) }.padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("总览", "指令", "信箱", "设置").forEachIndexed { index, name ->
                    Text(name, color = palette.navText, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(if (index == page) palette.navSelected else Color.Transparent, RoundedCornerShape(8.dp)).clickable { haptic(HapticEvent.Tap); onPick(if (index == page) ThemeRole.NavSelected else ThemeRole.NavText) }.padding(8.dp))
                }
            }
        }
    }
}

@Composable
private fun ThemeColorPicker(role: ThemeRole, initial: Long, resetValue: Long, onDismiss: () -> Unit, onApply: (Long) -> Unit) {
    val haptic = actionHaptics()
    var value by remember(role) { mutableLongStateOf(initial) }
    var hex by remember(role) { mutableStateOf(ThemeColorLogic.hex(initial)) }
    val recent by ThemeManager.recentColors.collectAsState()
    val hsv = remember(value) { FloatArray(3).also { android.graphics.Color.colorToHSV(value.toInt(), it) } }
    var hue by remember(role) { mutableFloatStateOf(hsv[0]) }
    fun change(next: Long) { value = next; hex = ThemeColorLogic.hex(next) }
    fun changeHsv(h: Float = hue, s: Float = hsv[1], v: Float = hsv[2]) {
        hue = h
        change(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)).toLong() and 0xFFFFFFFF)
    }
    AlertDialog(modifier = Modifier.testTag("theme-color-picker"), onDismissRequest = onDismiss, title = { Text(role.label) }, text = {
        Column(Modifier.verticalScrollCompat(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().height(48.dp).background(Color(value), RoundedCornerShape(10.dp)).border(1.dp, Color(0xFF52616B), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Text(ThemeColorLogic.hex(value), color = Color(ThemeColorLogic.readableText(value)), fontWeight = FontWeight.Bold)
            }
            OutlinedTextField(hex, onValueChange = { text ->
                hex = text.take(7)
                ThemeColorLogic.parseHex(text)?.let { next -> value = next; hue = FloatArray(3).also { android.graphics.Color.colorToHSV(next.toInt(), it) }[0] }
            }, label = { Text("HEX · #RRGGBB") }, singleLine = true, isError = ThemeColorLogic.parseHex(hex) == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
            Text("色相", fontSize = 12.sp)
            Box(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)), RoundedCornerShape(16.dp))) {
                Slider(value = hue, onValueChange = { changeHsv(h = it, s = if (hsv[1] == 0f) 0.7f else hsv[1], v = if (hsv[2] == 0f) 0.85f else hsv[2]) }, valueRange = 0f..360f, modifier = Modifier.height(30.dp))
            }
            Text("饱和度", fontSize = 12.sp)
            Slider(value = hsv[1], onValueChange = { changeHsv(s = it) })
            Text("明度", fontSize = 12.sp)
            Slider(value = hsv[2], onValueChange = { changeHsv(v = it) })
            Text("推荐色盘", fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val options = listOf(0xFFFFFFFFL, 0xFF17212BL, 0xFFFFF9C4L, 0xFFFF9800L, 0xFFEDF8FFL, 0xFF4DABF7L, 0xFF87E0EDL, 0xFFFFF1F6L, 0xFFFF8FBAL, 0xFFCBB4F4L, 0xFFEDF9F0L, 0xFF69D5B1L, 0xFFC4E878L, 0xFF5B2B46L, 0xFF173A57L, 0xFF234A3AL)
                options.forEach { color -> Box(Modifier.size(48.dp).clickable { haptic(HapticEvent.Tap); change(color); hue = FloatArray(3).also { android.graphics.Color.colorToHSV(color.toInt(), it) }[0] }, contentAlignment = Alignment.Center) { Swatch(Color(color), 28) } }
            }
            if (recent.isNotEmpty()) {
                Text("最近使用", fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recent.forEach { color -> Box(Modifier.size(48.dp).clickable { haptic(HapticEvent.Tap); change(color); hue = FloatArray(3).also { android.graphics.Color.colorToHSV(color.toInt(), it) }[0] }, contentAlignment = Alignment.Center) { Swatch(Color(color), 28) } }
                }
            }
            HapticTextButton(onClick = { change(resetValue); hue = FloatArray(3).also { android.graphics.Color.colorToHSV(resetValue.toInt(), it) }[0] }) { Text("重置这一项") }
        }
    }, confirmButton = { HapticTextButton(enabled = ThemeColorLogic.parseHex(hex) != null, onClick = { onApply(value) }) { Text("使用此颜色") } }, dismissButton = { HapticTextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun Modifier.verticalScrollCompat(): Modifier = this.then(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()))
