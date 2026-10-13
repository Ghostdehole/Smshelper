package com.ghost.smshelper

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Stable role names are also the portable theme-file format. */
enum class ThemeRole(val label: String) {
    Background("页面背景"), Surface("卡片"), SurfaceAlt("次级卡片"),
    Button("普通按钮"), ButtonText("普通按钮文字"), Primary("主按钮"), OnPrimary("主按钮文字"),
    Secondary("辅助按钮"), OnSecondary("辅助按钮文字"), Text("正文"), MutedText("次要文字"),
    Outline("描边"), Shadow("硬阴影"), SimSelected("选中 SIM"), SimUnselected("未选 SIM"),
    NavBackground("底部导航背景"), NavSelected("导航选中背景"), NavText("导航文字"),
    InboxReceived("收到的短信气泡"), InboxSent("发送的短信气泡"), Dialog("弹窗"),
    ChartData("流量指示"), ChartVoice("语音指示"), Danger("警示文字"), Success("成功文字")
}

@Immutable
data class AppPalette(val id: String, val name: String, val values: Map<ThemeRole, Long>) {
    fun color(role: ThemeRole): Color = Color(values.getValue(role))
    fun withColor(role: ThemeRole, value: Long): AppPalette = copy(values = values + (role to (value or 0xFF000000L)))
    val background get() = color(ThemeRole.Background)
    val surface get() = color(ThemeRole.Surface)
    val surfaceAlt get() = color(ThemeRole.SurfaceAlt)
    val button get() = color(ThemeRole.Button)
    val buttonText get() = color(ThemeRole.ButtonText)
    val primary get() = color(ThemeRole.Primary)
    val onPrimary get() = color(ThemeRole.OnPrimary)
    val secondary get() = color(ThemeRole.Secondary)
    val onSecondary get() = color(ThemeRole.OnSecondary)
    val text get() = color(ThemeRole.Text)
    val mutedText get() = color(ThemeRole.MutedText)
    val outline get() = color(ThemeRole.Outline)
    val shadow get() = color(ThemeRole.Shadow)
    val simSelected get() = color(ThemeRole.SimSelected)
    val simUnselected get() = color(ThemeRole.SimUnselected)
    val navBackground get() = color(ThemeRole.NavBackground)
    val navSelected get() = color(ThemeRole.NavSelected)
    val navText get() = color(ThemeRole.NavText)
    val inboxReceived get() = color(ThemeRole.InboxReceived)
    val inboxSent get() = color(ThemeRole.InboxSent)
    val dialog get() = color(ThemeRole.Dialog)
    val chartData get() = color(ThemeRole.ChartData)
    val chartVoice get() = color(ThemeRole.ChartVoice)
    val danger get() = color(ThemeRole.Danger)
    val success get() = color(ThemeRole.Success)

    fun colorScheme(): ColorScheme {
        // Bright comic fills are distinct from text/link accents on Material controls.
        // Explicit AppTheme role values remain exactly what the user selected.
        val surfaces = listOf(values.getValue(ThemeRole.Background), values.getValue(ThemeRole.Surface))
        val materialPrimary = ThemeColorLogic.readableAccent(values.getValue(ThemeRole.Primary), surfaces)
        val materialSecondary = ThemeColorLogic.readableAccent(values.getValue(ThemeRole.Secondary), surfaces)
        val materialTertiary = ThemeColorLogic.readableAccent(values.getValue(ThemeRole.ChartVoice), surfaces)
        return lightColorScheme(
        primary = Color(materialPrimary), onPrimary = Color(ThemeColorLogic.readableText(materialPrimary)), primaryContainer = simSelected, onPrimaryContainer = text,
        secondary = Color(materialSecondary), onSecondary = Color(ThemeColorLogic.readableText(materialSecondary)), secondaryContainer = surfaceAlt, onSecondaryContainer = text,
        tertiary = Color(materialTertiary), onTertiary = Color(ThemeColorLogic.readableText(materialTertiary)), tertiaryContainer = inboxSent, onTertiaryContainer = text,
        background = background, onBackground = text, surface = surface, onSurface = text,
        surfaceVariant = surfaceAlt, onSurfaceVariant = mutedText, outline = outline, outlineVariant = outline.copy(alpha = 0.35f),
        surfaceTint = Color.Transparent, error = danger, onError = Color(ThemeColorLogic.readableText(values.getValue(ThemeRole.Danger))),
        errorContainer = Color(ThemeColorLogic.blend(values.getValue(ThemeRole.Danger), 0xFFFFFFFF, 0.88f)), onErrorContainer = danger,
        inverseSurface = text, inverseOnSurface = surface, inversePrimary = primary,
        surfaceBright = surface, surfaceDim = surfaceAlt, surfaceContainer = dialog,
        surfaceContainerHigh = surfaceAlt, surfaceContainerHighest = surfaceAlt,
        surfaceContainerLow = surface, surfaceContainerLowest = surface
        )
    }
}

/** Color math has no Android dependencies, so imports and contrast rules can be unit tested. */
object ThemeColorLogic {
    fun parseHex(input: String): Long? {
        val clean = input.trim().removePrefix("#")
        return if (clean.matches(Regex("[0-9a-fA-F]{6}"))) clean.toLong(16) or 0xFF000000L else null
    }
    fun hex(value: Long): String = "#%06X".format(value and 0xFFFFFF)
    fun blend(first: Long, second: Long, amount: Float): Long {
        val t = amount.coerceIn(0f, 1f)
        fun channel(shift: Int): Long = ((((first shr shift) and 255) * (1 - t) + ((second shr shift) and 255) * t).toLong()).coerceIn(0, 255)
        return 0xFF000000L or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
    private fun luminance(color: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((color shr shift) and 255) / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
    fun contrast(first: Long, second: Long): Double {
        val a = luminance(first); val b = luminance(second)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }
    fun readableText(background: Long): Long {
        val inkContrast = contrast(background, 0xFF17212BL)
        val whiteContrast = contrast(background, 0xFFFFFFFFL)
        return when {
            inkContrast >= 4.5 && inkContrast >= whiteContrast -> 0xFF17212BL
            whiteContrast >= 4.5 -> 0xFFFFFFFFL
            else -> 0xFF000000L
        }
    }
    fun readableAccent(accent: Long, backgrounds: List<Long>): Long {
        fun score(color: Long): Double = backgrounds.minOf { contrast(color, it) }
        if (score(accent) >= 4.5) return accent
        val target = if (score(0xFF17212BL) >= score(0xFFFFFFFFL)) 0xFF17212BL else 0xFFFFFFFFL
        for (step in 1..100) {
            val candidate = blend(accent, target, step / 100f)
            if (score(candidate) >= 4.5) return candidate
        }
        return target
    }
    fun linked(palette: AppPalette, role: ThemeRole, value: Long): AppPalette {
        var result = palette.withColor(role, value)
        val pairs = when (role) {
            ThemeRole.Primary -> mapOf(ThemeRole.OnPrimary to readableText(value), ThemeRole.SimSelected to blend(value, palette.values.getValue(ThemeRole.Surface), 0.78f), ThemeRole.NavSelected to blend(value, palette.values.getValue(ThemeRole.Surface), 0.85f), ThemeRole.ChartData to value)
            ThemeRole.Background -> {
                val foreground = readableText(value)
                val surface = if (foreground == 0xFFFFFFFFL) blend(value, 0xFF000000, 0.18f) else 0xFFFFFFFFL
                mapOf(
                    ThemeRole.Text to foreground, ThemeRole.MutedText to blend(foreground, value, 0.20f),
                    ThemeRole.Surface to surface, ThemeRole.SurfaceAlt to blend(palette.values.getValue(ThemeRole.Primary), surface, 0.90f),
                    ThemeRole.Dialog to surface, ThemeRole.InboxReceived to surface,
                    ThemeRole.InboxSent to blend(palette.values.getValue(ThemeRole.Secondary), surface, 0.85f),
                    ThemeRole.SimSelected to blend(palette.values.getValue(ThemeRole.Primary), surface, 0.78f), ThemeRole.SimUnselected to surface,
                    ThemeRole.NavBackground to surface, ThemeRole.NavSelected to blend(palette.values.getValue(ThemeRole.Primary), surface, 0.85f), ThemeRole.NavText to foreground
                )
            }
            ThemeRole.Secondary -> mapOf(ThemeRole.OnSecondary to readableText(value), ThemeRole.ChartVoice to value)
            ThemeRole.Button -> mapOf(ThemeRole.ButtonText to readableText(value))
            else -> emptyMap()
        }
        pairs.forEach { (key, color) -> result = result.withColor(key, color) }
        return result
    }
    fun contrastWarnings(palette: AppPalette): List<String> {
        val pairs = listOf(
            Triple(ThemeRole.Text, ThemeRole.Background, "页面正文"), Triple(ThemeRole.Text, ThemeRole.Surface, "卡片正文"),
            Triple(ThemeRole.Text, ThemeRole.SurfaceAlt, "次级卡片正文"), Triple(ThemeRole.Text, ThemeRole.Dialog, "弹窗正文"),
            Triple(ThemeRole.MutedText, ThemeRole.Background, "页面次要文字"), Triple(ThemeRole.MutedText, ThemeRole.Surface, "卡片次要文字"),
            Triple(ThemeRole.ButtonText, ThemeRole.Button, "普通按钮"), Triple(ThemeRole.OnPrimary, ThemeRole.Primary, "主按钮"),
            Triple(ThemeRole.OnSecondary, ThemeRole.Secondary, "辅助按钮"), Triple(ThemeRole.NavText, ThemeRole.NavBackground, "导航栏"),
            Triple(ThemeRole.NavText, ThemeRole.NavSelected, "选中导航"), Triple(ThemeRole.Text, ThemeRole.SimSelected, "选中 SIM"),
            Triple(ThemeRole.Text, ThemeRole.SimUnselected, "未选 SIM"), Triple(ThemeRole.Text, ThemeRole.InboxReceived, "收到的短信"),
            Triple(ThemeRole.Text, ThemeRole.InboxSent, "发送的短信")
        )
        return pairs.mapNotNull { (fg, bg, name) ->
            val ratio = contrast(palette.values.getValue(fg), palette.values.getValue(bg))
            if (ratio < 4.5) "$name：${"%.1f".format(ratio)}∶1" else null
        }
    }
}

object ThemePaletteCodec {
    fun encode(palette: AppPalette): String = JSONObject().apply {
        put("format", "smshelper-theme"); put("version", 1); put("id", palette.id); put("name", palette.name)
        put("colors", JSONObject().apply { ThemeRole.entries.forEach { put(it.name, ThemeColorLogic.hex(palette.values.getValue(it))) } })
    }.toString(2)
    fun decode(source: String): AppPalette {
        require(source.length <= 32_768) { "主题文件不能超过 32 KB" }
        val root = JSONObject(source)
        require(root.optString("format") == "smshelper-theme" && root.optInt("version") == 1) { "不支持的主题格式" }
        val name = root.optString("name").trim()
        require(name.isNotBlank() && name.length <= 40) { "主题名称应为 1–40 个字符" }
        val colors = root.getJSONObject("colors")
        val values = ThemeRole.entries.associateWith { role ->
            ThemeColorLogic.parseHex(colors.optString(role.name)) ?: error("${role.label} 颜色无效，应为 #RRGGBB")
        }
        return AppPalette(root.optString("id").takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,80}")) } ?: UUID.randomUUID().toString(), name, values)
    }
}

object ThemeManager {
    private fun palette(id: String, name: String, background: Long, primary: Long, secondary: Long, ink: Long): AppPalette {
        val white = 0xFFFFFFFFL
        val tint = ThemeColorLogic.blend(primary, white, 0.88f)
        return AppPalette(id, name, mapOf(
            ThemeRole.Background to background, ThemeRole.Surface to white, ThemeRole.SurfaceAlt to tint,
            ThemeRole.Button to ThemeColorLogic.blend(primary, white, 0.74f), ThemeRole.ButtonText to ink,
            ThemeRole.Primary to primary, ThemeRole.OnPrimary to ink, ThemeRole.Secondary to secondary, ThemeRole.OnSecondary to ink,
            ThemeRole.Text to ink, ThemeRole.MutedText to ThemeColorLogic.blend(ink, white, 0.22f), ThemeRole.Outline to ink, ThemeRole.Shadow to ink,
            ThemeRole.SimSelected to ThemeColorLogic.blend(primary, white, 0.52f), ThemeRole.SimUnselected to white,
            ThemeRole.NavBackground to white, ThemeRole.NavSelected to ThemeColorLogic.blend(primary, white, 0.60f), ThemeRole.NavText to ink,
            ThemeRole.InboxReceived to white, ThemeRole.InboxSent to ThemeColorLogic.blend(secondary, white, 0.78f), ThemeRole.Dialog to white,
            ThemeRole.ChartData to primary, ThemeRole.ChartVoice to secondary, ThemeRole.Danger to 0xFFB42338L, ThemeRole.Success to 0xFF176847L
        ))
    }
    val palettes = listOf(
        palette("classic", "经典元气黄", 0xFFFFF9C4, 0xFFFF9800, 0xFF29B6F6, 0xFF2D3436),
        palette("glacier", "极客冰川蓝", 0xFFEDF8FF, 0xFF4DABF7, 0xFF87E0ED, 0xFF173A57),
        palette("sakura", "猛男樱花粉", 0xFFFFF1F6, 0xFFFF8FBA, 0xFFCBB4F4, 0xFF5B2B46),
        palette("mint", "清新薄荷绿", 0xFFEDF9F0, 0xFF69D5B1, 0xFFC4E878, 0xFF234A3A)
    )
    val themes = palettes.map { it.colorScheme() }
    val currentThemeIndex = MutableStateFlow(0)
    val currentPalette = MutableStateFlow(palettes[0])
    val customPalettes = MutableStateFlow<List<AppPalette>>(emptyList())
    val recentColors = MutableStateFlow<List<Long>>(emptyList())
    private fun preferences(context: Context) = context.getSharedPreferences("SmsHelper_Prefs", Context.MODE_PRIVATE)

    @Synchronized fun init(context: Context) {
        val prefs = preferences(context)
        currentThemeIndex.value = prefs.getInt("theme_index", 0).coerceIn(palettes.indices)
        customPalettes.value = runCatching {
            val array = JSONArray(prefs.getString("custom_themes_json", "[]"))
            (0 until min(array.length(), 50)).mapNotNull { index -> runCatching { ThemePaletteCodec.decode(array.getJSONObject(index).toString()) }.getOrNull() }
        }.getOrDefault(emptyList())
        currentPalette.value = customPalettes.value.firstOrNull { it.id == prefs.getString("custom_theme_id", null) } ?: palettes[currentThemeIndex.value]
        recentColors.value = runCatching {
            val array = JSONArray(prefs.getString("recent_colors_json", "[]"))
            (0 until min(array.length(), 16)).mapNotNull { ThemeColorLogic.parseHex(array.optString(it)) }.distinct()
        }.getOrDefault(emptyList())
    }
    @Synchronized fun setTheme(context: Context, index: Int) {
        if (index !in palettes.indices) return
        preferences(context).edit { putInt("theme_index", index); remove("custom_theme_id") }
        currentThemeIndex.value = index
        currentPalette.value = palettes[index]
    }
    @Synchronized fun applyPalette(context: Context, palette: AppPalette) {
        val index = palettes.indexOfFirst { it.id == palette.id && it.values == palette.values }
        if (index >= 0) setTheme(context, index)
        else {
            require(customPalettes.value.any { it.id == palette.id && it.values == palette.values }) { "请先保存自定义主题" }
            preferences(context).edit { putString("custom_theme_id", palette.id) }
            currentPalette.value = palette
        }
    }
    @Synchronized fun saveCustom(context: Context, palette: AppPalette, name: String): AppPalette {
        require(name.trim().isNotEmpty() && name.trim().length <= 40) { "主题名称应为 1–40 个字符" }
        require(customPalettes.value.size < 50) { "最多保存 50 个自定义主题，请先删除不需要的主题" }
        val saved = palette.copy(id = UUID.randomUUID().toString(), name = name.trim())
        val next = customPalettes.value + saved
        preferences(context).edit { putString("custom_themes_json", JSONArray(next.map { JSONObject(ThemePaletteCodec.encode(it)) }).toString()) }
        customPalettes.value = next
        applyPalette(context, saved)
        return saved
    }
    @Synchronized fun deleteCustom(context: Context, id: String) {
        val next = customPalettes.value.filterNot { it.id == id }
        preferences(context).edit { putString("custom_themes_json", JSONArray(next.map { JSONObject(ThemePaletteCodec.encode(it)) }).toString()) }
        customPalettes.value = next
        if (currentPalette.value.id == id) setTheme(context, currentThemeIndex.value)
    }
    @Synchronized fun rememberColor(context: Context, color: Long) {
        val next = (listOf(color) + recentColors.value).distinct().take(16)
        preferences(context).edit { putString("recent_colors_json", JSONArray(next.map(ThemeColorLogic::hex)).toString()) }
        recentColors.value = next
    }
}

val LocalAppPalette = staticCompositionLocalOf { ThemeManager.palettes[0] }
object AppTheme { val colors: AppPalette @Composable get() = LocalAppPalette.current }

@Composable
fun ProvidePalette(palette: AppPalette, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppPalette provides palette) { MaterialTheme(colorScheme = palette.colorScheme(), content = content) }
}

@Composable
fun ProvideAppTheme(content: @Composable () -> Unit) {
    val palette by ThemeManager.currentPalette.collectAsState()
    ProvidePalette(palette, content)
}
