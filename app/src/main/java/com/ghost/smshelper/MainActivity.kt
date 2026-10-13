package com.ghost.smshelper

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var requiresAuth by mutableStateOf(false)
    private var resetGeneration by mutableIntStateOf(0)
    private val dispatcher by lazy { SmsDispatcher(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ThemeManager.init(this)
        ConfigManager.init(this)
        SmsRepository.init(this)
        val prefs = getSharedPreferences("SmsHelper_Prefs", MODE_PRIVATE)
        requiresAuth = prefs.getBoolean("pin_enabled", false)
        lifecycleScope.launch(Dispatchers.IO) { ConfigManager.checkSubscriptions(this@MainActivity) }

        setContent {
            val themeIndex by ThemeManager.currentThemeIndex.collectAsState()
            MaterialTheme(colorScheme = ThemeManager.themes[themeIndex.coerceIn(ThemeManager.themes.indices)]) {
                key(resetGeneration) {
                    val navigationState = rememberSaveableStateHolder()
                    if (requiresAuth) {
                        // Dispose Dialog windows and permission-launching effects while locked.
                        BackHandler { finish() }
                        PinLockScreen(
                            targetPin = prefs.getString("pin_code", "").orEmpty(),
                            onUnlock = { requiresAuth = false }
                        )
                    } else {
                        val configError by ConfigManager.lastError.collectAsState()
                        val storageError by SmsRepository.storageError.collectAsState()
                        LaunchedEffect(configError) {
                            configError?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
                        }
                        LaunchedEffect(storageError) {
                            storageError?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
                        }
                        navigationState.SaveableStateProvider("navigation") { MainNavigation() }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && getSharedPreferences("SmsHelper_Prefs", MODE_PRIVATE)
                .getBoolean("pin_enabled", false)) requiresAuth = true
    }

    fun sendSmsViaSystem(command: String, sim: SimInfo, target: String): Boolean =
        dispatcher.compose(command, sim, target)

    fun sendSmsSilently(command: String, subId: Int, target: String, showSystemPrompt: Boolean = true): Boolean =
        dispatcher.send(command, subId, target, showSystemPrompt)

    fun resetApplicationData(): Boolean = try {
        SmsRepository.factoryReset(this)
        check(ConfigManager.reset(this)) { "无法重置规则配置" }
        check(getSharedPreferences("SmsHelper_Prefs", MODE_PRIVATE).edit().clear().commit()) { "无法重置应用设置" }
        ThemeManager.init(this)
        requiresAuth = false
        resetGeneration++
        true
    } catch (_: Exception) {
        Toast.makeText(this, "重置未全部完成，请检查可用存储空间后重试", Toast.LENGTH_LONG).show()
        false
    }
}

@Composable
fun MainNavigation() {
    var currentScreen by rememberSaveable { mutableStateOf("HOME") }
    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = {
            val springSpec = spring<IntOffset>(stiffness = 250f, dampingRatio = 0.85f)
            val fadeSpec = tween<Float>(300, easing = LinearOutSlowInEasing)
            if (targetState == "SETTINGS" || targetState == "SECRET_MENU") {
                slideInHorizontally(animationSpec = springSpec) { it } + fadeIn(fadeSpec) togetherWith
                    slideOutHorizontally(animationSpec = springSpec) { -it } + fadeOut(fadeSpec)
            } else {
                slideInHorizontally(animationSpec = springSpec) { -it } + fadeIn(fadeSpec) togetherWith
                    slideOutHorizontally(animationSpec = springSpec) { it } + fadeOut(fadeSpec)
            }
        }, label = "nav"
    ) { screen ->
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (screen) {
                "HOME" -> HomeScreen(
                    onNavigateToSettings = { currentScreen = "SETTINGS" },
                    onNavigateToSecretMenu = { currentScreen = "SECRET_MENU" }
                )
                "SETTINGS" -> SettingsScreen(onBack = { currentScreen = "HOME" })
                "SECRET_MENU" -> SecretMenuScreen(onBack = { currentScreen = "HOME" })
            }
        }
    }
}
