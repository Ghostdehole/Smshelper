@file:Suppress("DEPRECATION")
package com.ghost.smshelper

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

@SuppressLint("MissingPermission", "HardwareIds")
fun getActiveSims(context: Context): List<SimInfo> = try {
    val manager = context.getSystemService(SubscriptionManager::class.java)
    manager?.activeSubscriptionInfoList.orEmpty().map { info ->
        val telephony = context.getSystemService(TelephonyManager::class.java)
            ?.createForSubscriptionId(info.subscriptionId)
        val modernNumber = if (Build.VERSION.SDK_INT >= 33) {
            runCatching { manager?.getPhoneNumber(info.subscriptionId) }.getOrNull().orEmpty()
        } else ""
        // Existing READ_SMS grants can expose the legacy number without a new permission.
        val number = modernNumber.ifBlank { runCatching { info.number }.getOrNull().orEmpty() }
            .ifBlank { "未获取" }
        SimInfo(
            subId = info.subscriptionId,
            displayName = info.displayName?.toString() ?: "SIM ${info.simSlotIndex + 1}",
            slotIndex = info.simSlotIndex,
            carrierName = info.carrierName?.toString().orEmpty().ifBlank { "未知运营商" },
            number = number,
            mcc = if (Build.VERSION.SDK_INT >= 29) info.mccString.orEmpty() else info.mcc.toString(),
            mnc = if (Build.VERSION.SDK_INT >= 29) info.mncString.orEmpty() else "%02d".format(info.mnc),
            iccId = runCatching { info.iccId }.getOrNull().orEmpty().ifBlank { "系统隐私限制不可见" },
            countryIso = info.countryIso.orEmpty().ifBlank { "未知" },
            isRoaming = runCatching { telephony?.isNetworkRoaming == true }.getOrDefault(false)
        )
    }.sortedBy { it.slotIndex }
} catch (_: RuntimeException) {
    emptyList()
}
