package com.textgate.ai.setup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupReadinessTest {
    @Test
    fun standardPhoneRequiresKeyAccessibilityAndTrigger() {
        val ready = SetupReadiness.Status(true, true, true, true, false, false, false)
        assertTrue(ready.ready)
        assertFalse(ready.copy(apiKeyReady = false).ready)
        assertFalse(ready.copy(apiTestPassed = false).ready)
        assertFalse(ready.copy(accessibilityEnabled = false).ready)
        assertFalse(ready.copy(triggerEnabled = false).ready)
    }

    @Test
    fun xiaomiChecklistRequiresBothUserConfirmations() {
        val pending = SetupReadiness.Status(true, true, true, true, true, false, false)
        assertFalse(pending.ready)
        assertFalse(pending.copy(batteryConfirmed = true).ready)
        assertFalse(pending.copy(autostartConfirmed = true).ready)
        assertTrue(pending.copy(batteryConfirmed = true, autostartConfirmed = true).ready)
    }
}
