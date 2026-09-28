package cn.local.smsrelay.core

data class IncomingSms(val sender: String, val body: String, val receivedAt: Long, val eventKey: String)
data class Submission(val id: String, val destination: String, val subscriptionId: Int, val parts: List<String>)

data class ClaimedAttempt(val id: String, val destination: String)

interface RelayJournal {
    /** Atomically persists the event key and its unknown initial outcome before sending. */
    fun claim(eventKey: String, subscriptionId: Int, partCount: Int, test: Boolean, body: String, destinations: List<String>): List<ClaimedAttempt>?
    fun fail(id: String, reason: String)
}
interface SmsTransport {
    /** Return a fixed, non-sensitive error code, or null when ready. */
    fun unavailableReason(subscriptionId: Int): String?
    fun split(body: String, subscriptionId: Int): List<String>
    fun submit(submission: Submission)
}
enum class ForwardOutcome { IGNORED, DUPLICATE, SUBMITTED, FAILED, PARTIAL }

class RelayEngine(private val journal: RelayJournal, private val transport: SmsTransport) {
    fun forward(config: RelayConfig, sms: IncomingSms): ForwardOutcome {
        if (!RelayRules.matches(config, sms.sender, sms.receivedAt)) return ForwardOutcome.IGNORED
        return send(config, sms.body, sms.eventKey, false)
    }

    fun test(config: RelayConfig, eventKey: String): ForwardOutcome {
        if (RelayRules.validationError(config) != null) return ForwardOutcome.IGNORED
        return send(config, "短信转发工具测试：A 手机已提交这条测试短信。", eventKey, true)
    }

    private fun recordFailure(id: String, reason: String) {
        try { journal.fail(id, reason) }
        catch (_: RuntimeException) {
            // The attempt was committed before sending. Leave it UNKNOWN if its
            // status cannot be saved, and still process the other committed targets.
        }
    }

    private fun send(config: RelayConfig, body: String, eventKey: String, test: Boolean): ForwardOutcome {
        val unavailable = try { transport.unavailableReason(config.subscriptionId) }
            catch (_: SecurityException) { "PERMISSION_DENIED" }
            catch (_: RuntimeException) { "TELEPHONY_UNAVAILABLE" }
        val parts = if (unavailable == null) {
            try { transport.split(body, config.subscriptionId) }
            catch (_: RuntimeException) { emptyList() }
        } else emptyList()
        // Commit BEFORE handing data to the modem. Body is retained in bounded local history; there is no resend queue.
        val attempts = journal.claim(eventKey, config.subscriptionId, parts.size.coerceAtLeast(1), test, body,
            RelayRules.destinations(config.destination)) ?: return ForwardOutcome.DUPLICATE
        var submitted = 0
        attempts.forEach { attempt ->
            if (unavailable != null || parts.isEmpty()) {
                recordFailure(attempt.id, unavailable ?: "SPLIT_ERROR")
            } else {
                try {
                    transport.submit(Submission(attempt.id, attempt.destination, config.subscriptionId, parts))
                    submitted++
                } catch (_: SecurityException) {
                    recordFailure(attempt.id, "PERMISSION_DENIED")
                } catch (_: RuntimeException) {
                    recordFailure(attempt.id, "SUBMISSION_ERROR")
                }
            }
        }
        return when (submitted) {
            0 -> ForwardOutcome.FAILED
            attempts.size -> ForwardOutcome.SUBMITTED
            else -> ForwardOutcome.PARTIAL
        }
    }
}
