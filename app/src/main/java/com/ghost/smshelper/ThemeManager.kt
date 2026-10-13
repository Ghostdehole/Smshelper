package com.ghost.smshelper

import android.content.Context
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow

object ThemeManager {
    val themes = listOf(
        lightColorScheme(primary = Color(0xFFFF9800), secondary = Color(0xFF29B6F6), background = Color(0xFFFFF9C4), surface = Color.White), // 经典元气黄
        lightColorScheme(primary = Color(0xFF0984E3), secondary = Color(0xFF00CEC9), background = Color(0xFFDFE6E9), surface = Color.White), // 极客冰川蓝
        lightColorScheme(primary = Color(0xFFE84393), secondary = Color(0xFFFD79A8), background = Color(0xFFFFEAA7), surface = Color.White), // 猛男樱花粉
        lightColorScheme(primary = Color(0xFF00B894), secondary = Color(0xFF55E6C1), background = Color(0xFFE8F8F5), surface = Color.White)  // 清新薄荷绿
    )
    val currentThemeIndex = MutableStateFlow(0)

    fun init(context: Context) {
        currentThemeIndex.value = context.getSharedPreferences("SmsHelper_Prefs", Context.MODE_PRIVATE).getInt("theme_index", 0).coerceIn(themes.indices)
    }

    fun setTheme(context: Context, index: Int) {
        if (index in themes.indices) {
            currentThemeIndex.value = index
            context.getSharedPreferences("SmsHelper_Prefs", Context.MODE_PRIVATE).edit { putInt("theme_index", index) }
        }
    }
}
