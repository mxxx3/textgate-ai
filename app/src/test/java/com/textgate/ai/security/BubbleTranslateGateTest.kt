package com.textgate.ai.security

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
 *
 * Also covers the child-node text search (see BubbleTranslateGate's
 * "Where the text actually lives" doc section), added after real-device
 * testing showed WhatsApp reports a long-press target whose own text is
 * blank, with the actual message living in a child node.
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

    /** A node with its own text left blank, and the given [children]
     * attached — simulating a WhatsApp-shaped message row container. */
    private fun containerNode(vararg children: AccessibilityNodeInfo): AccessibilityNodeInfo {
        val node = AccessibilityNodeInfo.obtain().apply {
            isEditable = false
        }
        val shadow = shadowOf(node)
        children.forEach { shadow.addChild(it) }
        return node
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

    @Test
    fun `WhatsApp-shaped container node with the message in a child yields Ready with the child's text`() {
        val (gate, store) = newGate()
        store.isAiEnabled = true
        store.setPackageAllowed("com.whatsapp", true)
        val messageChild = readOnlyNode("cześć, jak się masz?")
        val node = containerNode(messageChild)

        val decision = gate.evaluate("com.whatsapp", node)

        assertTrue(decision is BubbleTranslateGate.Decision.Ready)
        val ready = decision as BubbleTranslateGate.Decision.Ready
        assertTrue(ready.text == "cześć, jak się masz?")
        @Suppress("DEPRECATION") node.recycle()
    }

    @Test
    fun `longest safe child text wins over a shorter sibling like a timestamp`() {
        val (gate, store) = newGate()
        store.isAiEnabled = true
        store.setPackageAllowed("com.whatsapp", true)
        val timestamp = readOnlyNode("10:42")
        val message = readOnlyNode("nie ma pośpiechu, odezwij się jak będziesz miał chwilę")
        val node = containerNode(timestamp, message)

        val decision = gate.evaluate("com.whatsapp", node)

        assertTrue(decision is BubbleTranslateGate.Decision.Ready)
        val ready = decision as BubbleTranslateGate.Decision.Ready
        assertTrue(ready.text == "nie ma pośpiechu, odezwij się jak będziesz miał chwilę")
        @Suppress("DEPRECATION") node.recycle()
    }

    @Test
    fun `a sensitive child is skipped even though its text would otherwise be the longest candidate`() {
        val (gate, store) = newGate()
        store.isAiEnabled = true
        store.setPackageAllowed("com.whatsapp", true)
        val message = readOnlyNode("wpadnij później")
        val maskedSecret = readOnlyNode("1234567890123456", password = true)
        val node = containerNode(message, maskedSecret)

        val decision = gate.evaluate("com.whatsapp", node)

        assertTrue(decision is BubbleTranslateGate.Decision.Ready)
        val ready = decision as BubbleTranslateGate.Decision.Ready
        assertTrue(ready.text == "wpadnij później")
        @Suppress("DEPRECATION") node.recycle()
    }

    @Test
    fun `container node with only a sensitive child is Blocked (empty text, not the sensitive value)`() {
        val (gate, store) = newGate()
        store.isAiEnabled = true
        store.setPackageAllowed("com.whatsapp", true)
        val maskedSecret = readOnlyNode("1234567890123456", password = true)
        val node = containerNode(maskedSecret)

        val decision = gate.evaluate("com.whatsapp", node)

        assertTrue(decision is BubbleTranslateGate.Decision.Blocked)
        @Suppress("DEPRECATION") node.recycle()
    }

    @Test
    fun `container node with no children and no own text is Blocked (empty text)`() {
        val (gate, store) = newGate()
        store.isAiEnabled = true
        store.setPackageAllowed("com.whatsapp", true)
        val node = containerNode()

        val decision = gate.evaluate("com.whatsapp", node)

        assertTrue(decision is BubbleTranslateGate.Decision.Blocked)
        @Suppress("DEPRECATION") node.recycle()
    }
}
