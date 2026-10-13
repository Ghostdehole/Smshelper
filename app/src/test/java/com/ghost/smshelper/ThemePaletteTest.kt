package com.ghost.smshelper

import org.json.JSONObject
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.*
import org.junit.Test

class ThemePaletteTest {
    @Test fun hexImportsAcceptOnlyOpaqueRgb() {
        assertEquals(0xFFFF8FBAL, ThemeColorLogic.parseHex(" #ff8fba "))
        assertEquals(0xFF001122L, ThemeColorLogic.parseHex("001122"))
        assertNull(ThemeColorLogic.parseHex("#123"))
        assertNull(ThemeColorLogic.parseHex("#00FF8FBA"))
        assertNull(ThemeColorLogic.parseHex("not-a-color"))
        assertEquals("#FF8FBA", ThemeColorLogic.hex(0xFFFF8FBA))
    }

    @Test fun builtInsHaveDistinctBackgroundsAndCompleteReadableRoles() {
        assertEquals(4, ThemeManager.palettes.map { it.values.getValue(ThemeRole.Background) }.distinct().size)
        assertEquals(0xFFFFF9C4L, ThemeManager.palettes[0].values.getValue(ThemeRole.Background))
        assertEquals(0xFFFFF1F6L, ThemeManager.palettes[2].values.getValue(ThemeRole.Background))
        ThemeManager.palettes.forEach { palette ->
            assertEquals(ThemeRole.entries.toSet(), palette.values.keys)
            assertTrue("${palette.name}: ${ThemeColorLogic.contrastWarnings(palette)}", ThemeColorLogic.contrastWarnings(palette).isEmpty())
        }
    }

    @Test fun automaticTextNeverFallsIntoTheMidGrayContrastGap() {
        for (channel in 0..255) {
            val color = 0xFF000000L or (channel.toLong() shl 16) or (channel.toLong() shl 8) or channel.toLong()
            assertTrue("Channel $channel", ThemeColorLogic.contrast(color, ThemeColorLogic.readableText(color)) >= 4.5)
        }
    }

    @Test fun materialTextAccentsAndTheirFilledButtonsHaveReadableContrast() {
        ThemeManager.palettes.forEach { palette ->
            val scheme = palette.colorScheme()
            val background = scheme.background.toArgb().toLong() and 0xFFFFFFFFL
            val surface = scheme.surface.toArgb().toLong() and 0xFFFFFFFFL
            listOf(scheme.primary to scheme.onPrimary, scheme.secondary to scheme.onSecondary, scheme.tertiary to scheme.onTertiary).forEach { (fill, text) ->
                val color = fill.toArgb().toLong() and 0xFFFFFFFFL
                assertTrue(ThemeColorLogic.contrast(color, background) >= 4.5)
                assertTrue(ThemeColorLogic.contrast(color, surface) >= 4.5)
                assertTrue(ThemeColorLogic.contrast(color, text.toArgb().toLong() and 0xFFFFFFFFL) >= 4.5)
            }
        }
        assertEquals(0xFFFF9800L, ThemeManager.palettes[0].values.getValue(ThemeRole.Primary))
    }

    @Test fun themeFileRoundTripPreservesEveryUserChosenRole() {
        val draft = ThemeManager.palettes[2].withColor(ThemeRole.Shadow, 0xFF5F2870).withColor(ThemeRole.ButtonText, 0xFF173A57)
            .copy(name = "我的主题")
        assertEquals(draft, ThemePaletteCodec.decode(ThemePaletteCodec.encode(draft)))
    }

    @Test fun themeImportRejectsIncompleteOrUnsupportedFiles() {
        val valid = JSONObject(ThemePaletteCodec.encode(ThemeManager.palettes[0]))
        valid.getJSONObject("colors").remove(ThemeRole.Outline.name)
        assertThrows(IllegalStateException::class.java) { ThemePaletteCodec.decode(valid.toString()) }
        assertThrows(IllegalArgumentException::class.java) { ThemePaletteCodec.decode("{\"format\":\"smshelper-theme\",\"version\":2}") }
        assertThrows(IllegalArgumentException::class.java) { ThemePaletteCodec.decode(" ".repeat(32769)) }
    }

    @Test fun linkedColorsKeepTextReadableWithoutChangingUnrelatedRoles() {
        val original = ThemeManager.palettes[0]
        val dark = ThemeColorLogic.linked(original, ThemeRole.Primary, 0xFF060918)
        assertEquals(0xFFFFFFFFL, dark.values.getValue(ThemeRole.OnPrimary))
        assertEquals(0xFF060918L, dark.values.getValue(ThemeRole.ChartData))
        assertEquals(original.values.getValue(ThemeRole.InboxReceived), dark.values.getValue(ThemeRole.InboxReceived))
        assertTrue(ThemeColorLogic.contrast(dark.values.getValue(ThemeRole.Primary), dark.values.getValue(ThemeRole.OnPrimary)) >= 4.5)
        // Advanced/manual edits remain literal, rather than silently replacing the user's text color.
        val manual = original.withColor(ThemeRole.Primary, 0xFF060918)
        assertEquals(original.values.getValue(ThemeRole.OnPrimary), manual.values.getValue(ThemeRole.OnPrimary))
    }

    @Test fun contrastWarningsCoverManualLowContrastAndNavigation() {
        val changed = ThemeManager.palettes[0].withColor(ThemeRole.ButtonText, ThemeManager.palettes[0].values.getValue(ThemeRole.Button))
            .withColor(ThemeRole.NavText, 0xFFFFFFFF)
        val warnings = ThemeColorLogic.contrastWarnings(changed)
        assertTrue(warnings.any { it.startsWith("普通按钮") })
        assertTrue(warnings.any { it.startsWith("导航栏") })
        assertEquals(21.0, ThemeColorLogic.contrast(0xFF000000, 0xFFFFFFFF), 0.00001)
    }
}
