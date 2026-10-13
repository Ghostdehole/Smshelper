package com.ghost.smshelper

data class SimInfo(val subId: Int, val displayName: String, val slotIndex: Int, val carrierName: String, val number: String, val mcc: String, val mnc: String, val iccId: String, val countryIso: String, val isRoaming: Boolean)
data class ActionCommand(val code: String, val name: String, val requiresInput: Boolean = false, val inputHint: String = "")
data class CommandCategory(val title: String, val commands: List<ActionCommand>)
data class SmsItem(val sender: String, val body: String, val time: String, val subId: Int? = null)
data class BillRecord(val month: String, val amount: String)
data class ParsedInfo(val balance: String = "0.00", val consumption: String = "0.00", val usedDataMb: Double = 0.0, val remainingTotalDataMb: Double = 0.0, val generalDataMb: Double = 0.0, val totalVoice: Int = 0, val remainingVoice: Int = 0, val trafficDetails: List<String> = emptyList(), val historicalBills: List<BillRecord> = emptyList())
data class CarrierConfig(val identifier: String, val name: String, val targetNumber: String, val syncCommands: List<String>, val regexRules: Map<String, String>, val categories: List<CommandCategory>)
data class ConfigProfile(val id: String, val name: String, val source: String, val jsonContent: String, val carriers: List<CarrierConfig>)
data class CarrierSelection(val identifier: String, val profileId: String, val config: CarrierConfig)
data class Subscription(val url: String, var name: String, var intervalMinutes: Int, var lastUpdateTime: Long, var profileId: String?)
