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
import androidx.core.view.WindowCompat
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
        AppFeedbackPreferences.init(this)
        SimPreferences.init(this)
        ConfigManager.init(this)
        SmsRepository.init(this)
        QueryRepository.init(this)
        CommandStore.init(this)
        Reminders.init(this)
        val prefs = getSharedPreferences("SmsHelper_Prefs", MODE_PRIVATE)
        requiresAuth = prefs.getBoolean("pin_enabled", false)
        lifecycleScope.launch(Dispatchers.IO) { ConfigManager.checkSubscriptions(this@MainActivity) }

        setContent {
            val palette by ThemeManager.currentPalette.collectAsState()
            SideEffect {
                fun darkIcons(role: ThemeRole): Boolean {
                    val color = palette.values.getValue(role)
                    return ThemeColorLogic.contrast(color, 0xFF000000L) >= ThemeColorLogic.contrast(color, 0xFFFFFFFFL)
                }
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = darkIcons(ThemeRole.Background)
                    isAppearanceLightNavigationBars = darkIcons(ThemeRole.NavBackground)
                }
            }
            ProvideAppTheme {
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
                        val queryError by QueryRepository.storageError.collectAsState()
                        LaunchedEffect(configError) {
                            configError?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
                        }
                        LaunchedEffect(storageError) {
                            storageError?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
                        }
                        LaunchedEffect(queryError) {
                            queryError?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
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

    fun retrySms(task: QueryTask): Boolean = dispatcher.retry(task, showPrompt = false)

    fun resetApplicationData(): Boolean = try {
        SmsRepository.factoryReset(this)
        QueryRepository.factoryReset(this)
        Reminders.reset(this)
        CommandStore.reset(this)
        InboxExportCache.clear(this)
        FullBackupCache.clear(this)
        check(SimPreferences.reset(this)) { "无法重置 SIM 设置" }
        check(ConfigManager.reset(this)) { "无法重置规则配置" }
        check(getSharedPreferences("SmsHelper_Prefs", MODE_PRIVATE).edit().clear().commit()) { "无法重置应用设置" }
        ThemeManager.init(this)
        AppFeedbackPreferences.init(this)
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
    val reduceMotion by AppFeedbackPreferences.reduceMotion.collectAsState()
    val screenState = rememberSaveableStateHolder()
    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = {
            val springSpec = spring<IntOffset>(stiffness = 250f, dampingRatio = 0.85f)
            val fadeSpec = tween<Float>(300, easing = LinearOutSlowInEasing)
            if (reduceMotion) {
                EnterTransition.None togetherWith ExitTransition.None
            } else if (targetState == "SETTINGS" || targetState == "SECRET_MENU") {
                slideInHorizontally(animationSpec = springSpec) { it } + fadeIn(fadeSpec) togetherWith
                    slideOutHorizontally(animationSpec = springSpec) { -it } + fadeOut(fadeSpec)
            } else {
                slideInHorizontally(animationSpec = springSpec) { -it } + fadeIn(fadeSpec) togetherWith
                    slideOutHorizontally(animationSpec = springSpec) { it } + fadeOut(fadeSpec)
            }
        }, label = "nav"
    ) { screen ->
        screenState.SaveableStateProvider(screen) {
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
}
