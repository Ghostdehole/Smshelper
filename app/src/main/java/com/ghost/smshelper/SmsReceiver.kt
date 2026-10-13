package com.ghost.smshelper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import java.util.concurrent.Executors

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull()
            ?.filterNotNull().orEmpty()
        if (messages.isEmpty()) return
        val pending = goAsync()
        val appContext = context.applicationContext
        worker.execute {
            try {
                ConfigManager.init(appContext)
                SmsRepository.init(appContext)
                val subId = receivingSubscription(appContext, intent)
                val interceptAll = appContext.getSharedPreferences("SmsHelper_Prefs", Context.MODE_PRIVATE)
                    .getBoolean("intercept_all_sms", false)
                messages.groupBy { it.displayOriginatingAddress.orEmpty() }.forEach { (sender, parts) ->
                    if (sender.isNotBlank() && (interceptAll || ConfigManager.resolveForSubscription(appContext, subId, sender) != null)) {
                        SmsRepository.addMessage(
                            appContext, sender, parts.joinToString("") { it.displayMessageBody.orEmpty() },
                            subId, parts.first().timestampMillis
                        )
                    }
                }
            } catch (error: Exception) {
                // Do not put private SMS content in logs.
                Log.e("SmsReceiver", "Unable to store incoming SMS", error)
            } finally {
                pending.finish()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun receivingSubscription(context: Context, intent: Intent): Int? {
        val keys = listOf("android.telephony.extra.SUBSCRIPTION_INDEX", "subscription", "subscription_id", "sub_id")
        keys.forEach { key ->
            val id = (intent.extras?.get(key) as? Number)?.toInt()
            if (id != null && id >= 0) return id
        }
        val sims = getActiveSims(context)
        val slot = listOf("slot", "slot_id", "phone").firstNotNullOfOrNull {
            (intent.extras?.get(it) as? Number)?.toInt()
        }
        return if (slot != null) sims.firstOrNull { it.slotIndex == slot }?.subId
        else sims.singleOrNull()?.subId // An ambiguous multi-SIM message stays unassigned.
    }

    companion object {
        private val worker = Executors.newSingleThreadExecutor()
    }
}
