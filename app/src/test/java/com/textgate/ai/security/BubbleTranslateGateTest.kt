package com.textgate.ai.security

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Same style as EventGateTest — exercises the real BubbleTranslateGate
 * instance/class TextGateAccessibilityService relies on for the long-press
 * "translate what's under my finger" pathway, not a re-implementation.
 *
 * The key behavioral difference from EventGate this file specifically
 * proves: a NON-editable node (isEditable = false), exactly like a
 * received message bubble, still yields Ready here — where it would be
 * Blocked under EventGate — because this pathway's entire purpose is
 * reading content the user did not write.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BubbleTranslateGateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun newGate(): Pair<BubbleTranslateGate, AppSettingsStore> {
        val store = AppSettingsStore(context)
        return BubbleTranslateGate(store, ownPackageName = "com.textgate.ai") to store
    }

    private fun readOnlyNode(text: String, password: Boolean = false): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain().apply {
            isEditable = false
            isPassword = password
            this.text = text
        }

    @Test
    fun `non-editable received message in an allowed app yields Ready`() {
        val (gate, store) = newGate()
        store.isAiEnabled = true
        store.setPackageAllowed("com.whatsapp", true)
        val node = readOnlyNode("cześć, jak się masz?")

        val decision = gate.evaluate("com.whatsapp", node)

        assertTrue(decision is BubbleTranslateGate.Decision.Ready)
        val ready = decision as BubbleTranslateGate.Decision.Ready
        assertTrue(ready.text == "cześć, jak się masz?")
        @Suppress("DEPRECATION") node.recycle()
    }

    @Test
    fun `password field is Blocked even though it is non-editable`() {
        val (gate, store) = newGate()
        store.isAiEnabled = true
        store.setPackageAllowed("com.whatsapp", true)
        val node = readOnlyNode("••••••••", password = true)

        val decision = gate.evaluate("com.whatsapp", node)

        assertTrue(decision is BubbleTranslateGate.Decision.Blocked)
        @Suppress("DEPRECATION") node.recycle()
    }

    @Test
    fun `app not on the allow-list is Blocked regardless of content`() {
        val (gate, store) = newGate()
        store.isAiEnabled = true
        // Deliberately NOT calling setPackageAllowed for this package.
        val node = readOnlyNode("some message")

        val decision = gate.evaluate("com.some.random.app", node)

        assertTrue(decision is BubbleTranslateGate.Decision.Blocked)
        @Suppress("DEPRECATION") node.recycle()
    }

    @Test
    fun `master switch off blocks an allow-listed app`() {
        val (gate, store) = newGate()
        store.isAiEnabled = false
        store.setPackageAllowed("com.whatsapp", true)
        val node = readOnlyNode("some message")

        val decision = gate.evaluate("com.whatsapp", node)

        assertTrue(decision is BubbleTranslateGate.Decision.Blocked)
        @Suppress("DEPRECATION") node.recycle()
    }

    @Test
    fun `hard-coded blocklist wins even if the package is somehow allow-listed`() {
        val (gate, store) = newGate()
        store.isAiEnabled = true
        store.setPackageAllowed("com.x8bit.bitwarden", true) // should never happen via the UI
        val node = readOnlyNode("some message")

        val decision = gate.evaluate("com.x8bit.bitwarden", node)

        assertTrue(decision is BubbleTranslateGate.Decision.Blocked)
        @Suppress("DEPRECATION") node.recycle()
    }

    @Test
    fun `empty text is Blocked`() {
        val (gate, store) = newGate()
        store.isAiEnabled = true
        store.setPackageAllowed("com.whatsapp", true)
        val node = readOnlyNode("   ")

        val decision = gate.evaluate("com.whatsapp", node)

        assertTrue(decision is BubbleTranslateGate.Decision.Blocked)
        @Suppress("DEPRECATION") node.recycle()
    }

    @Test
    fun `text longer than the shared limit is Blocked`() {
        val (gate, store) = newGate()
        store.isAiEnabled = true
        store.setPackageAllowed("com.whatsapp", true)
        val node = readOnlyNode("a".repeat(TriggerDetector.MAX_INPUT_LENGTH + 1))

        val decision = gate.evaluate("com.whatsapp", node)

        assertTrue(decision is BubbleTranslateGate.Decision.Blocked)
        @Suppress("DEPRECATION") node.recycle()
    }

    @Test
    fun `null source node is Blocked (fail closed)`() {
        val (gate, store) = newGate()
        store.isAiEnabled = true
        store.setPackageAllowed("com.whatsapp", true)

        val decision = gate.evaluate("com.whatsapp", null)

        assertTrue(decision is BubbleTranslateGate.Decision.Blocked)
    }

    @Test
    fun `missing package name is Blocked (fail closed)`() {
        val (gate, store) = newGate()
        store.isAiEnabled = true
        val node = readOnlyNode("some message")

        val decision = gate.evaluate(null, node)

        assertTrue(decision is BubbleTranslateGate.Decision.Blocked)
        @Suppress("DEPRECATION") node.recycle()
    }
}
