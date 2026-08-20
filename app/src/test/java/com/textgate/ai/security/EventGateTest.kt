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
 * End-to-end (minus network) test of the exact decision chain
 * TextGateAccessibilityService relies on. This is the same EventGate
 * instance/class the real service uses — not a re-implementation — so
 * these tests exercise the real gating logic.
 *
 * Covers spec scenarios:
 *   #1 normal field + ?en -> allowed
 *   #2 normal field without ?en -> zero requests
 *   #3 password field -> zero requests even with a trigger present
 *   #8 app outside the allow-list -> zero requests
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EventGateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun newGate(): Pair<EventGate, AppSettingsStore> {
        val store = AppSettingsStore(context)
        return EventGate(store, ownPackageName = "com.textgate.ai") to store
    }

    private fun editableNode(text: String, password: Boolean = false): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain().apply {
            isEditable = true
            isPassword = password
            this.text = text
        }

    @Test
    fun `scenario 1 - allowed app plus trigger yields Ready`() {
        val (gate, store) = newGate()
        store.isAiEnabled = true
        store.setPackageAllowed("org.telegram.messenger", true)
        val node = editableNode("nie ma pośpiechu ?en")

        val decision = gate.evaluate("org.telegram.messenger", node)

        assertTrue(decision is EventGate.Decision.Ready)
        val ready = decision as EventGate.Decision.Ready
        assertTrue(ready.content == "nie ma pośpiechu ")
        assertTrue(ready.fullText == "nie ma pośpiechu ?en")
        @Suppress("DEPRECATION") node.recycle()
    }

    @Test
    fun `scenario 2 - normal typing without trigger yields NotTriggered`() {
        val (gate, store) = newGate()
        store.isAiEnabled = true
        store.setPackageAllowed("org.telegram.messenger", true)
        val node = editableNode("just typing normally")

        val decision = gate.evaluate("org.telegram.messenger", node)

        assertTrue(decision is EventGate.Decision.NotTriggered)
        @Suppress("DEPRECATION") node.recycle()
    }

    @Test
    fun `scenario 3 - password field is Blocked even with a valid trigger`() {
        val (gate, store) = newGate()
        store.isAiEnabled = true
        store.setPackageAllowed("org.telegram.messenger", true)
        val node = editableNode("secret ?en", password = true)

        val decision = gate.evaluate("org.telegram.messenger", node)

        assertTrue(decision is EventGate.Decision.Blocked)
        @Suppress("DEPRECATION") node.recycle()
    }

    @Test
    fun `scenario 8 - app not on the allow-list is Blocked regardless of content`() {
        val (gate, store) = newGate()
        store.isAiEnabled = true
        // Deliberately NOT calling setPackageAllowed for this package.
        val node = editableNode("nie ma pośpiechu ?en")

        val decision = gate.evaluate("com.some.random.app", node)

        assertTrue(decision is EventGate.Decision.Blocked)
        @Suppress("DEPRECATION") node.recycle()
    }

    @Test
    fun `master switch off blocks an allow-listed app with a valid trigger`() {
        val (gate, store) = newGate()
        store.isAiEnabled = false
        store.setPackageAllowed("org.telegram.messenger", true)
        val node = editableNode("nie ma pośpiechu ?en")

        val decision = gate.evaluate("org.telegram.messenger", node)

        assertTrue(decision is EventGate.Decision.Blocked)
        @Suppress("DEPRECATION") node.recycle()
    }

    @Test
    fun `hard-coded blocklist wins even if the package is somehow allow-listed`() {
        val (gate, store) = newGate()
        store.isAiEnabled = true
        store.setPackageAllowed("com.x8bit.bitwarden", true) // should never happen via the UI

        val node = editableNode("nie ma pośpiechu ?en")
        val decision = gate.evaluate("com.x8bit.bitwarden", node)

        assertTrue(decision is EventGate.Decision.Blocked)
        @Suppress("DEPRECATION") node.recycle()
    }

    @Test
    fun `null source node is Blocked (fail closed)`() {
        val (gate, store) = newGate()
        store.isAiEnabled = true
        store.setPackageAllowed("org.telegram.messenger", true)

        val decision = gate.evaluate("org.telegram.messenger", null)

        assertTrue(decision is EventGate.Decision.Blocked)
    }

    @Test
    fun `missing package name is Blocked (fail closed)`() {
        val (gate, store) = newGate()
        store.isAiEnabled = true
        val node = editableNode("nie ma pośpiechu ?en")

        val decision = gate.evaluate(null, node)

        assertTrue(decision is EventGate.Decision.Blocked)
        @Suppress("DEPRECATION") node.recycle()
    }
}
