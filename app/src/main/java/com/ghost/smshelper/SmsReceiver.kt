package com.ghost.smshelper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION", "SpellCheckingInspection")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SMS_RECEIVED") {
            val bundle = intent.extras ?: return
            val pdus = bundle.get("pdus") as? Array<*> ?: return
            val format = bundle.getString("format")

            val fullMessage = StringBuilder()
            var sender = ""

            for (pdu in pdus) {
                val sms = SmsMessage.createFromPdu(pdu as ByteArray, format)
                sender = sms.displayOriginatingAddress ?: ""
                fullMessage.append(sms.displayMessageBody)
            }

            ConfigManager.init(context)
            SmsRepository.init(context)

            val interceptAll = context.getSharedPreferences("SmsHelper_Prefs", Context.MODE_PRIVATE).getBoolean("intercept_all_sms", false)
            val activeTarget = ConfigManager.activeConfig.value?.targetNumber ?: ""

            // 如果开启了拦截所有，或者是当前运营商的目标号码，或者是默认常见的四大系统号码，则均入库
            if (interceptAll || sender.contains(activeTarget) || sender.startsWith("100") || sender.startsWith("+86100")) {
                CoroutineScope(Dispatchers.Main).launch {
                    SmsRepository.addMessage(context, sender, fullMessage.toString())
                }
            }
        }
    }
}