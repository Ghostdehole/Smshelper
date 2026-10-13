package com.ghost.smshelper

enum class SendOutcome { SENT, FAILED }

/** Tracks individual parts so one successful part cannot announce success for a failed SMS. */
internal class MultipartSendTracker(private val capacity: Int = 128) {
    private data class Progress(val total: Int, val received: MutableSet<Int> = mutableSetOf(), var result: SendOutcome? = null)
    private val requests = linkedMapOf<String, Progress>()

    @Synchronized
    fun record(id: String, part: Int, total: Int, success: Boolean): SendOutcome? {
        if (total < 1 || part !in 0 until total) return null
        val progress = requests.getOrPut(id) { Progress(total) }
        while (requests.size > capacity) requests.remove(requests.keys.first())
        if (progress.total != total || progress.result != null || !progress.received.add(part)) return null
        progress.result = when {
            !success -> SendOutcome.FAILED
            progress.received.size == total -> SendOutcome.SENT
            else -> null
        }
        return progress.result
    }
}
