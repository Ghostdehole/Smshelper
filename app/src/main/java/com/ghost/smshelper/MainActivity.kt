@file:Suppress("DEPRECATION", "SpellCheckingInspection")
package com.ghost.smshelper

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var requiresAuth by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ThemeManager.init(this)
        ConfigManager.init(this)
        SmsRepository.init(this)

        val prefs = getSharedPreferences("SmsHelper_Prefs", MODE_PRIVATE)
        if (prefs.getBoolean("pin_enabled", false)) requiresAuth = true

        lifecycleScope.launch { ConfigManager.checkSubscriptions(this@MainActivity) }

        setContent {
            val themeIndex by ThemeManager.currentThemeIndex.collectAsState()
            MaterialTheme(colorScheme = ThemeManager.themes[themeIndex.coerceIn(0, ThemeManager.themes.size - 1)]) {
                if (requiresAuth) {
                    val targetPin = prefs.getString("pin_code", "") ?: ""
                    PinLockScreen(targetPin = targetPin, onUnlock = { requiresAuth = false })
                } else {
                    MainNavigation()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (getSharedPreferences("SmsHelper_Prefs", MODE_PRIVATE).getBoolean("pin_enabled", false)) requiresAuth = true
    }

    fun sendSmsViaSystem(command: String, sim: SimInfo, target: String) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO, "smsto:$target".toUri()).apply {
                putExtra("sms_body", command)
                putExtra("android.telephony.extra.SUBSCRIPTION_INDEX", sim.subId)
                putExtra("subscription", sim.subId)
                putExtra("simSlot", sim.slotIndex)
                putExtra("sim_slot", sim.slotIndex)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {}
    }

    fun sendSmsSilently(command: String, subId: Int, target: String, showSystemPrompt: Boolean = true) {
        try {
            val isModifiedRom = Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("redmi") }
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= 31 && !isModifiedRom) {
                getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
            } else {
                SmsManager.getSmsManagerForSubscriptionId(subId)
            }
            val sentIntent = if (showSystemPrompt) PendingIntent.getBroadcast(this, 0, Intent("SMS_SENT_ACTION"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) else null
            smsManager.sendTextMessage(target, null, command, sentIntent, null)
            if (showSystemPrompt) Toast.makeText(this, "嗖~[$command] 已发出!", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            if (showSystemPrompt) Toast.makeText(this, "发送异常，请检查权限", Toast.LENGTH_SHORT).show()
        }
    }
}

@SuppressLint("MissingPermission", "HardwareIds")
fun getActiveSims(context: Context): List<SimInfo> {
    return try {
        val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val list = subscriptionManager.activeSubscriptionInfoList ?: return emptyList()
        list.map {
            var iccIdStr = "系统隐私限制不可见"
            try {
                if (!it.iccId.isNullOrBlank()) iccIdStr = it.iccId
            } catch (_: Exception) {}

            SimInfo(
                subId = it.subscriptionId, displayName = it.displayName?.toString() ?: "SIM ${it.simSlotIndex + 1}", slotIndex = it.simSlotIndex,
                carrierName = it.carrierName?.toString() ?: "未知运营商",
                number = try { it.number ?: "未获取" } catch (_: Exception) { "权限受限" },
                mcc = if (Build.VERSION.SDK_INT >= 29) it.mccString ?: "" else it.mcc.toString(),
                mnc = if (Build.VERSION.SDK_INT >= 29) it.mncString ?: "" else it.mnc.toString(),
                iccId = iccIdStr, countryIso = it.countryIso ?: "未知", isRoaming = it.dataRoaming == SubscriptionManager.DATA_ROAMING_ENABLE
            )
        }.sortedBy { it.slotIndex }
    } catch (_: Exception) { emptyList() }
}

@Composable
fun MainNavigation() {
    val currentScreen = remember { mutableStateOf("HOME") }
    AnimatedContent(
        targetState = currentScreen.value,
        transitionSpec = {
            val springSpec = spring<IntOffset>(stiffness = 250f, dampingRatio = 0.85f)
            val fadeSpec = tween<Float>(300, easing = LinearOutSlowInEasing)
            if (targetState == "SETTINGS" || targetState == "SECRET_MENU") {
                slideInHorizontally(animationSpec = springSpec) { width -> width } + fadeIn(animationSpec = fadeSpec) togetherWith
                        slideOutHorizontally(animationSpec = springSpec) { width -> -width } + fadeOut(animationSpec = fadeSpec)
            } else {
                slideInHorizontally(animationSpec = springSpec) { width -> -width } + fadeIn(animationSpec = fadeSpec) togetherWith
                        slideOutHorizontally(animationSpec = springSpec) { width -> width } + fadeOut(animationSpec = fadeSpec)
            }
        }, label = "nav"
    ) { screen ->
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (screen) {
                "HOME" -> HomeScreen(
                    onNavigateToSettings = { currentScreen.value = "SETTINGS" },
                    onNavigateToSecretMenu = { currentScreen.value = "SECRET_MENU" }
                )
                "SETTINGS" -> SettingsScreen(onBack = { currentScreen.value = "HOME" })
                "SECRET_MENU" -> SecretMenuScreen(onBack = { currentScreen.value = "HOME" })
            }
        }
    }
}