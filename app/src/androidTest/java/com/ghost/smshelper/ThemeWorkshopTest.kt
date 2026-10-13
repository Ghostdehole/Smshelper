package com.ghost.smshelper

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeWorkshopTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val keys = setOf("theme_index", "custom_theme_id", "custom_themes_json", "recent_colors_json")
    private var saved: Map<String, *> = emptyMap<String, Any>()

    @Before fun rememberExistingPreferences() {
        val prefs = context.getSharedPreferences("SmsHelper_Prefs", Context.MODE_PRIVATE)
        saved = prefs.all.filterKeys { it in keys }
        ThemeManager.init(context)
        ThemeManager.setTheme(context, 0)
    }

    @After fun restoreExistingPreferences() {
        val editor = context.getSharedPreferences("SmsHelper_Prefs", Context.MODE_PRIVATE).edit()
        keys.forEach { editor.remove(it) }
        saved.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            else -> Unit
        } }
        editor.commit()
        ThemeManager.init(context)
    }

    @Test fun changingThePreviewAndCancellingDoesNotApplyIt() {
        var exits = 0
        compose.setContent { ProvideAppTheme { ThemeWorkshopScreen(onBack = { exits++ }) } }
        compose.onNodeWithText("猛男樱花粉").performClick()
        compose.runOnIdle { assertEquals("classic", ThemeManager.currentPalette.value.id) }
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("放弃修改").performClick()
        compose.runOnIdle {
            assertEquals(1, exits)
            assertEquals("classic", ThemeManager.currentPalette.value.id)
            assertEquals(0, context.getSharedPreferences("SmsHelper_Prefs", Context.MODE_PRIVATE).getInt("theme_index", -1))
        }
    }

    @Test fun applyingABuiltInThemeUpdatesStateAndPersistence() {
        var exits = 0
        compose.setContent { ProvideAppTheme { ThemeWorkshopScreen(onBack = { exits++ }) } }
        compose.onNodeWithText("极客冰川蓝").performClick()
        compose.onNodeWithText("应用").performClick()
        compose.runOnIdle {
            assertEquals(1, exits)
            assertEquals("glacier", ThemeManager.currentPalette.value.id)
            assertEquals(1, context.getSharedPreferences("SmsHelper_Prefs", Context.MODE_PRIVATE).getInt("theme_index", -1))
        }
    }

    @Test fun aPreviewRegionOpensItsColorEditorWithoutChangingTheActiveTheme() {
        compose.setContent { ProvideAppTheme { ThemeWorkshopScreen(onBack = {}) } }
        compose.onNodeWithTag("theme-preview-primary").performScrollTo().performClick()
        compose.onNodeWithTag("theme-color-picker").assertExists().assertIsDisplayed()
        compose.onNodeWithText("HEX · #RRGGBB").assertIsDisplayed()
        compose.runOnIdle { assertEquals("classic", ThemeManager.currentPalette.value.id) }
    }
}
