package com.textgate.ai.setup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SetupReadinessTest {
    @Test
    fun everyPhoneRequiresKeyAccessibilityTriggerAndBackgroundReview() {
        val ready = SetupReadiness.Status(
            true, true, true, true,
            SetupReadiness.ManualChoice.ENABLED,
            SetupReadiness.ManualChoice.UNAVAILABLE
        )
        assertTrue(ready.ready)
        assertFalse(ready.copy(apiKeyReady = false).ready)
        assertFalse(ready.copy(apiTestPassed = false).ready)
        assertFalse(ready.copy(accessibilityEnabled = false).ready)
        assertFalse(ready.copy(triggerEnabled = false).ready)
        assertFalse(ready.copy(batteryChoice = SetupReadiness.ManualChoice.PENDING).ready)
        assertFalse(ready.copy(autostartChoice = SetupReadiness.ManualChoice.PENDING).ready)
    }

    @Test
    fun backgroundReviewRequiresBothUserConfirmations() {
        val pending = SetupReadiness.Status(
            true, true, true, true,
            SetupReadiness.ManualChoice.PENDING,
            SetupReadiness.ManualChoice.PENDING
        )
        assertFalse(pending.ready)
        assertFalse(pending.copy(batteryChoice = SetupReadiness.ManualChoice.ENABLED).ready)
        assertFalse(pending.copy(autostartChoice = SetupReadiness.ManualChoice.ENABLED).ready)
        assertTrue(pending.copy(
            batteryChoice = SetupReadiness.ManualChoice.ENABLED,
            autostartChoice = SetupReadiness.ManualChoice.UNAVAILABLE
        ).ready)
    }

    @Test
    fun `isSetupCompleted returns true once marked completed`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(SetupReadiness.isSetupCompleted(context))
        SetupReadiness.markSetupCompleted(context)
        assertTrue(SetupReadiness.isSetupCompleted(context))
    }
}
