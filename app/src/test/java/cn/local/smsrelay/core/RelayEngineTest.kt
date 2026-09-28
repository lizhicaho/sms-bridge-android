package cn.local.smsrelay.core

import org.junit.Assert.*
import org.junit.Test

class RelayEngineTest {
    private val config = RelayConfig("106123456", "13800138000", 7, true, 100)
    private val sms = IncomingSms(config.source, "【测试】验证码 123456，勿泄露。", 101, "event1")
    private val journal = MemoryJournal()
    private val transport = CapturingTransport()
    private val engine = RelayEngine(journal, transport)

    @Test fun preservesOriginalTextAndExplicitDestinationAndSim() {
        assertEquals(ForwardOutcome.SUBMITTED, engine.forward(config, sms))
        val actual = transport.sent.single()
        assertEquals("【测试】验证码 123456，勿泄露。", actual.parts.joinToString(""))
        assertEquals("13800138000", actual.destination)
        assertEquals(7, actual.subscriptionId)
        assertEquals(1, journal.claims.size)
        assertEquals("【测试】验证码 123456，勿泄露。", journal.bodies.values.single())
    }
    @Test fun differentSourcesForwardToOneDestinationAndDuplicateEntryDoesNotDoubleSend() {
        val multi = config.copy(source = "106123456\n+8613900139000\n106123456")
        assertEquals(ForwardOutcome.SUBMITTED, engine.forward(multi, sms))
        assertEquals(ForwardOutcome.SUBMITTED, engine.forward(multi, sms.copy(sender = "+8613900139000", eventKey = "second")))
        assertEquals(2, transport.sent.size)
        assertTrue(transport.sent.all { it.destination == "13800138000" })
    }
    @Test fun repeatedEventDoesNotResendEvenWithNewEngine() {
        engine.forward(config, sms)
        val restarted = RelayEngine(journal, transport)
        assertEquals(ForwardOutcome.DUPLICATE, restarted.forward(config, sms))
        assertEquals(1, transport.sent.size)
    }
    @Test fun sameBodyInSeparateEventsIsNotLost() {
        engine.forward(config, sms)
        engine.forward(config, sms.copy(receivedAt = 102, eventKey = "event2"))
        assertEquals(2, transport.sent.size)
    }
    @Test fun unrelatedOrDisabledOrOldMessagesHaveNoJournalOrSendingSideEffects() {
        engine.forward(config, sms.copy(sender = "1061234569"))
        engine.forward(config.copy(enabled = false), sms)
        engine.forward(config, sms.copy(receivedAt = 99))
        assertTrue(transport.sent.isEmpty())
        assertTrue(journal.claims.isEmpty())
    }
    @Test fun absentSimIsFailedAndNeverFallsBackOrRetries() {
        transport.unavailable = "SIM_UNAVAILABLE"
        assertEquals(ForwardOutcome.FAILED, engine.forward(config, sms))
        assertEquals(listOf("SIM_UNAVAILABLE"), journal.failures.values.toList())
        transport.unavailable = null
        assertEquals(ForwardOutcome.DUPLICATE, engine.forward(config, sms))
        assertTrue(transport.sent.isEmpty())
    }
    @Test fun multipartTextIsSubmittedOnceWithAllParts() {
        transport.chunks = listOf("【测试】验证码 ", "123456，勿泄露。")
        engine.forward(config, sms)
        assertEquals(2, journal.claims.values.single())
        assertEquals(listOf("【测试】验证码 ", "123456，勿泄露。"), transport.sent.single().parts)
    }
    @Test fun sendingExceptionDoesNotLeakExceptionTextIntoFailureReasonAndIsNotRetried() {
        transport.throwOnSend = true
        assertEquals(ForwardOutcome.FAILED, engine.forward(config, sms))
        assertEquals(listOf("SUBMISSION_ERROR"), journal.failures.values.toList())
        assertEquals(ForwardOutcome.DUPLICATE, engine.forward(config, sms))
        assertEquals(1, transport.sent.size)
    }
    @Test fun testCanSendWhileForwardingDisabledButMustHaveValidSettings() {
        assertEquals(ForwardOutcome.SUBMITTED, engine.test(config.copy(enabled = false), "test1"))
        assertEquals("短信转发工具测试：A 手机已提交这条测试短信。", transport.sent.single().parts.joinToString(""))
        assertEquals(ForwardOutcome.IGNORED, engine.test(config.copy(destination = ""), "test2"))
        assertEquals(1, transport.sent.size)
    }
    @Test fun persistenceFailurePreventsSending() {
        val failing = object : RelayJournal {
            override fun claim(eventKey: String, subscriptionId: Int, partCount: Int, test: Boolean, body: String, destinations: List<String>): List<ClaimedAttempt>? = throw IllegalStateException("disk")
            override fun fail(id: String, reason: String) = Unit
        }
        try { RelayEngine(failing, transport).forward(config, sms) } catch (_: IllegalStateException) { }
        assertTrue(transport.sent.isEmpty())
    }

    @Test fun sendsOnceToEachUniqueTargetWithIndependentIds() {
        val multi = config.copy(destination = "13800138000\n13900139000，13800138000")
        assertEquals(ForwardOutcome.SUBMITTED, engine.forward(multi, sms))
        assertEquals(listOf("13800138000", "13900139000"), transport.sent.map { it.destination })
        assertEquals(2, transport.sent.map { it.id }.distinct().size)
        assertTrue(transport.sent.all { it.parts.joinToString("") == sms.body })
    }
    @Test fun oneTargetFailureDoesNotPreventOtherTargetsAndIsNotRetried() {
        val multi = config.copy(destination = "13800138000\n13900139000")
        transport.failedDestination = "13800138000"
        assertEquals(ForwardOutcome.PARTIAL, engine.forward(multi, sms))
        assertEquals(2, transport.sent.size)
        assertEquals(listOf("SUBMISSION_ERROR"), journal.failures.values.toList())
        assertEquals(ForwardOutcome.DUPLICATE, RelayEngine(journal, transport).forward(multi, sms))
        assertEquals(2, transport.sent.size)
    }
    @Test fun changingTargetsDoesNotReplayAnAlreadyClaimedEvent() {
        assertEquals(ForwardOutcome.SUBMITTED, engine.forward(config, sms))
        assertEquals(ForwardOutcome.DUPLICATE, engine.forward(config.copy(destination = "13800138000\n13900139000"), sms))
        assertEquals(1, transport.sent.size)
    }
    @Test fun testMessageIsSubmittedToAllConfiguredTargets() {
        val multi = config.copy(destination = "13800138000,13900139000", enabled = false)
        assertEquals(ForwardOutcome.SUBMITTED, engine.test(multi, "test-multi"))
        assertEquals(listOf("13800138000", "13900139000"), transport.sent.map { it.destination })
        assertTrue(transport.sent.all { it.parts.joinToString("") == "短信转发工具测试：A 手机已提交这条测试短信。" })
    }

    @Test fun failureStatusWriteExceptionDoesNotStopRemainingTargets() {
        journal.throwOnFailureWrite = true
        transport.failedDestination = "13800138000"
        val multi = config.copy(destination = "13800138000\n13900139000")
        assertEquals(ForwardOutcome.PARTIAL, engine.forward(multi, sms))
        assertEquals(listOf("13800138000", "13900139000"), transport.sent.map { it.destination })
        assertTrue(journal.failures.isEmpty())
        assertEquals(ForwardOutcome.DUPLICATE, engine.forward(multi, sms))
        assertEquals(2, transport.sent.size)
    }

    private class MemoryJournal : RelayJournal {
        val claims = linkedMapOf<String, Int>()
        val bodies = linkedMapOf<String, String>()
        val failures = linkedMapOf<String, String>()
        var throwOnFailureWrite = false
        override fun claim(eventKey: String, subscriptionId: Int, partCount: Int, test: Boolean, body: String, destinations: List<String>): List<ClaimedAttempt>? {
            if (claims.containsKey(eventKey)) return null
            claims[eventKey] = partCount
            bodies[eventKey] = body
            return destinations.distinct().mapIndexed { index, target -> ClaimedAttempt("$eventKey:$index", target) }
        }
        override fun fail(id: String, reason: String) {
            if (throwOnFailureWrite) throw IllegalStateException("disk write failed")
            failures[id] = reason
        }
    }
    private class CapturingTransport : SmsTransport {
        val sent = mutableListOf<Submission>()
        var unavailable: String? = null
        var chunks: List<String>? = null
        var throwOnSend = false
        var failedDestination: String? = null
        override fun unavailableReason(subscriptionId: Int) = unavailable
        override fun split(body: String, subscriptionId: Int) = chunks ?: listOf(body)
        override fun submit(submission: Submission) {
            sent += submission
            if (throwOnSend || submission.destination == failedDestination) throw IllegalStateException("secret body 123456")
        }
    }
}
