package com.textgate.ai.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented (on-device/emulator) test for the Android-Keystore-backed
 * encryption used to store Gemini API keys. This is NOT part of
 * `./gradlew test` (the unit test task) — the AndroidKeyStore provider is a
 * real system service backed by a secure element / TEE and is not
 * available in a plain JVM unit test or under Robolectric, so this class
 * lives in src/androidTest and runs only via `./gradlew connectedAndroidTest`
 * against a real device or emulator.
 *
 * Deliberately exercises only [SecureApiKeyStore]'s public API — the real
 * production entry point — rather than reaching into the internal
 * [KeystoreCrypto] object directly, so this test proves the same behavior
 * the app itself relies on.
 *
 * Since v1.6.0 [SecureApiKeyStore] holds an ORDERED LIST of keys rather
 * than a single key (see its doc comment) — these tests were rewritten
 * against that multi-key API (`addKey`/`removeKey`/`clearAllKeys`/
 * `listKeys`/`keyCount`/`hasAnyKey`/`activeKeyId`/`getActiveKeyPlaintext`/
 * `advanceActiveKey`); the old single-key methods no longer exist.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreCryptoInstrumentedTest {

    private fun newStore(): SecureApiKeyStore {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return SecureApiKeyStore(context)
    }

    @Test
    fun addThenGetActive_returnsOriginalKey() {
        val store = newStore()
        store.clearAllKeys()

        val key = "AIzaSyFAKE_TEST_KEY_DOES_NOT_EXIST".toCharArray()
        val added = store.addKey(key)

        assertTrue(added)
        assertTrue(store.hasAnyKey())
        assertEquals(1, store.keyCount())
        assertEquals("AIzaSyFAKE_TEST_KEY_DOES_NOT_EXIST", store.getActiveKeyPlaintext())

        store.clearAllKeys()
    }

    @Test
    fun clearAllKeys_removesEveryKeyPermanently() {
        val store = newStore()
        store.addKey("some-fake-key-value".toCharArray())
        assertTrue(store.hasAnyKey())

        store.clearAllKeys()

        assertFalse(store.hasAnyKey())
        assertEquals(0, store.keyCount())
        assertNull(store.activeKeyId())
        assertNull(store.getActiveKeyPlaintext())
    }

    @Test
    fun blankKey_isRejected_andNothingIsStored() {
        val store = newStore()
        store.clearAllKeys()

        val added = store.addKey("   ".toCharArray())

        assertFalse(added)
        assertFalse(store.hasAnyKey())
    }

    @Test
    fun addingASecondKey_appendsRatherThanReplacing_andKeepsTheFirstKeyActive() {
        val store = newStore()
        store.clearAllKeys()

        store.addKey("first-fake-key".toCharArray())
        val firstActiveId = store.activeKeyId()
        store.addKey("second-fake-key".toCharArray())

        // Adding a second key must not silently switch which key is
        // currently in use — only the very first key ever added becomes
        // active automatically (see SecureApiKeyStore.addKey's doc comment).
        assertEquals(2, store.keyCount())
        assertEquals(firstActiveId, store.activeKeyId())
        assertEquals("first-fake-key", store.getActiveKeyPlaintext())

        // Both fake keys happen to end in "-key", so last4 alone can't
        // distinguish them — what matters here is that each got its own
        // distinct internal id, and the store now lists exactly two rows.
        val listedKeys = store.listKeys()
        assertEquals(2, listedKeys.size)
        assertEquals(listedKeys.map { it.id }.toSet().size, listedKeys.size)
        assertTrue(listedKeys.any { it.id == firstActiveId })

        store.clearAllKeys()
    }

    @Test
    fun advanceActiveKey_cyclesThroughStoredKeysAndWrapsAround() {
        val store = newStore()
        store.clearAllKeys()

        store.addKey("first-fake-key".toCharArray())
        store.addKey("second-fake-key".toCharArray())
        val firstId = store.activeKeyId()

        store.advanceActiveKey()
        assertEquals("second-fake-key", store.getActiveKeyPlaintext())
        assertTrue(store.activeKeyId() != firstId)

        // Wraps back around to the first key after the last.
        store.advanceActiveKey()
        assertEquals(firstId, store.activeKeyId())
        assertEquals("first-fake-key", store.getActiveKeyPlaintext())

        store.clearAllKeys()
    }

    @Test
    fun advanceActiveKey_withOnlyOneStoredKey_isANoOp() {
        val store = newStore()
        store.clearAllKeys()

        store.addKey("only-fake-key".toCharArray())
        val onlyId = store.activeKeyId()

        store.advanceActiveKey()

        assertEquals(onlyId, store.activeKeyId())
        assertEquals("only-fake-key", store.getActiveKeyPlaintext())

        store.clearAllKeys()
    }

    @Test
    fun removeKey_reassignsActivePointerToNextKeyInOrder() {
        val store = newStore()
        store.clearAllKeys()

        store.addKey("first-fake-key".toCharArray())
        store.addKey("second-fake-key".toCharArray())
        store.addKey("third-fake-key".toCharArray())
        val firstId = store.activeKeyId()!!

        // Removing the currently-active key (the first, in the middle of
        // the remaining order once removed) should move the pointer to
        // whichever key is now next in rotation order.
        store.removeKey(firstId)

        assertEquals(2, store.keyCount())
        assertNotNull(store.activeKeyId())
        assertEquals("second-fake-key", store.getActiveKeyPlaintext())

        store.clearAllKeys()
    }

    @Test
    fun removeKey_whenActiveKeyIsLast_wrapsActivePointerToFirst() {
        val store = newStore()
        store.clearAllKeys()

        store.addKey("first-fake-key".toCharArray())
        store.addKey("second-fake-key".toCharArray())
        val lastId = store.listKeys().last().id

        // Move the active pointer onto the last key, then remove it —
        // this is the specific edge case the wraparound math
        // (removedIndex % newOrder.size) exists for.
        store.advanceActiveKey()
        assertEquals(lastId, store.activeKeyId())

        store.removeKey(lastId)

        assertEquals(1, store.keyCount())
        assertEquals("first-fake-key", store.getActiveKeyPlaintext())

        store.clearAllKeys()
    }

    @Test
    fun removeKey_lastRemainingKey_clearsActivePointerEntirely() {
        val store = newStore()
        store.clearAllKeys()

        store.addKey("only-fake-key".toCharArray())
        val onlyId = store.activeKeyId()!!

        store.removeKey(onlyId)

        assertEquals(0, store.keyCount())
        assertFalse(store.hasAnyKey())
        assertNull(store.activeKeyId())
        assertNull(store.getActiveKeyPlaintext())
    }
}
