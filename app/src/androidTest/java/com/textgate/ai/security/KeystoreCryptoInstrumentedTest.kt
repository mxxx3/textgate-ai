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
 * encryption used to store the Gemini API key. This is NOT part of
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
 */
@RunWith(AndroidJUnit4::class)
class KeystoreCryptoInstrumentedTest {

    private fun newStore(): SecureApiKeyStore {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return SecureApiKeyStore(context)
    }

    @Test
    fun saveThenGet_returnsOriginalKey() {
        val store = newStore()
        store.clearApiKey()

        val key = "AIzaSyFAKE_TEST_KEY_DOES_NOT_EXIST".toCharArray()
        val saved = store.saveApiKey(key)

        assertTrue(saved)
        assertTrue(store.hasApiKey())
        assertEquals("AIzaSyFAKE_TEST_KEY_DOES_NOT_EXIST", store.getApiKey())

        store.clearApiKey()
    }

    @Test
    fun clearApiKey_removesTheKeyPermanently() {
        val store = newStore()
        store.saveApiKey("some-fake-key-value".toCharArray())
        assertTrue(store.hasApiKey())

        store.clearApiKey()

        assertFalse(store.hasApiKey())
        assertNull(store.getApiKey())
    }

    @Test
    fun blankKey_isRejected_andNothingIsStored() {
        val store = newStore()
        store.clearApiKey()

        val saved = store.saveApiKey("   ".toCharArray())

        assertFalse(saved)
        assertFalse(store.hasApiKey())
    }

    @Test
    fun savingANewKey_overwritesThePreviousOne() {
        val store = newStore()
        store.saveApiKey("first-fake-key".toCharArray())
        store.saveApiKey("second-fake-key".toCharArray())

        assertNotNull(store.getApiKey())
        assertEquals("second-fake-key", store.getApiKey())

        store.clearApiKey()
    }
}
