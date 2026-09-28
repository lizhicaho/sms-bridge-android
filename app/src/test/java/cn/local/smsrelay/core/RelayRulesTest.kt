package cn.local.smsrelay.core

import org.junit.Assert.*
import org.junit.Test

class RelayRulesTest {
    private val config = RelayConfig("106123456", "13800138000", 2, true, 1_000)

    @Test fun allModeAcceptsAnyNewSenderWithoutSourceAndStillRequiresValidTargets() {
        val all = config.copy(source = "", forwardAll = true)
        assertNull(RelayRules.validationError(all))
        assertTrue(RelayRules.matches(all, "OTHER", 1001))
        assertTrue(RelayRules.matches(all, "+8613999999999", 1001))
        assertFalse(RelayRules.matches(all, "OTHER", 999))
        assertFalse(RelayRules.matches(all.copy(enabled = false), "OTHER", 1001))
        assertNotNull(RelayRules.validationError(all.copy(destination = "")))
        assertNotNull(RelayRules.validationError(all.copy(subscriptionId = -1)))
        assertNotNull(RelayRules.validationError(all.copy(forwardAll = false)))
    }

    @Test fun exactSenderOnly() {
        assertTrue(RelayRules.matches(config, "106123456", 1_001))
        assertFalse(RelayRules.matches(config, "1061234567", 1_001))
        assertFalse(RelayRules.matches(config, "+86106123456", 1_001))
        assertFalse(RelayRules.matches(config, "10612345", 1_001))
    }
    @Test fun disabledOrEarlierSmsIsNotForwarded() {
        assertFalse(RelayRules.matches(config.copy(enabled = false), config.source, 1_001))
        assertFalse(RelayRules.matches(config, config.source, 999))
        assertTrue(RelayRules.matches(config, config.source, 1_000))
    }
    @Test fun invalidSettingsCannotEnableForwarding() {
        assertNotNull(RelayRules.validationError(config.copy(source = "")))
        assertNotNull(RelayRules.validationError(config.copy(destination = "")))
        assertNotNull(RelayRules.validationError(config.copy(subscriptionId = -1)))
        assertNotNull(RelayRules.validationError(config.copy(destination = config.source)))
        assertNotNull(RelayRules.validationError(config.copy(destination = "138 0013")))
        assertNull(RelayRules.validationError(config))
        assertNull(RelayRules.validationError(config.copy(destination = "+8613800138000")))
        assertFalse(RelayRules.matches(config.copy(subscriptionId = -1), config.source, 1_001))
    }
    @Test fun anyConfiguredSourceMatchesExactly() {
        val multi = config.copy(source = "106123456\n+8613900139000")
        assertNull(RelayRules.validationError(multi))
        assertTrue(RelayRules.matches(multi, "106123456", 1_001))
        assertTrue(RelayRules.matches(multi, "+8613900139000", 1_001))
        assertFalse(RelayRules.matches(multi, "13900139000", 1_001))
        assertFalse(RelayRules.matches(multi, "1061234567", 1_001))
        assertFalse(RelayRules.matches(multi, "106000000", 1_001))
    }
    @Test fun acceptsCommaAndChineseCommaSeparatorsAndIgnoresBlankLines() {
        val multi = config.copy(source = " 106123456 , +8613900139000，106888888\r\n\n106123456 ")
        assertNull(RelayRules.validationError(multi))
        assertTrue(RelayRules.matches(multi, "106888888", 1_001))
        assertTrue(RelayRules.matches(multi, "+8613900139000", 1_001))
    }
    @Test fun invalidMemberOrEmptyListOrDestinationInSourcesRejectsWholeConfig() {
        assertNotNull(RelayRules.validationError(config.copy(source = "106123456\nnot-a-number")))
        assertNotNull(RelayRules.validationError(config.copy(source = " , ，\n")))
        assertNotNull(RelayRules.validationError(config.copy(source = "106123456\n13800138000")))
        assertFalse(RelayRules.matches(config.copy(source = "106123456\n+86 13900139000"), "106123456", 1_001))
    }
    @Test fun multipleTargetsAreAcceptedButInvalidOrConflictingMembersRejectWholeConfig() {
        assertNull(RelayRules.validationError(config.copy(destination = "13800138000，+8613900139000\n13800138000")))
        assertNotNull(RelayRules.validationError(config.copy(destination = "13800138000\ninvalid")))
        assertNotNull(RelayRules.validationError(config.copy(destination = " ,，\n")))
        assertNotNull(RelayRules.validationError(config.copy(destination = "13800138000\n106123456")))
    }
    @Test fun allPartsMustSucceedBeforeReportingSent() {
        assertEquals(SendState.UNKNOWN, RelayRules.state(listOf(null, null)))
        assertEquals(SendState.UNKNOWN, RelayRules.state(listOf(-1, null)))
        assertEquals(SendState.SENT, RelayRules.state(listOf(-1, -1)))
        assertEquals(SendState.UNKNOWN, RelayRules.state(emptyList()))
    }
    @Test fun anyFailedPartMakesWholeMessageFailedEvenWithMissingCallbacks() {
        assertEquals(SendState.FAILED, RelayRules.state(listOf(-1, 4, null)))
        assertEquals(SendState.FAILED, RelayRules.state(listOf(1)))
    }
}
