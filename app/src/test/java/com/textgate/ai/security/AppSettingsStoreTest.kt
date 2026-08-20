package com.textgate.ai.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the "safe by default" half of spec scenario #8: the allow-list is
 * empty and the master switch is off until the user explicitly changes
 * either, which is what makes "app not on the allow-list -> zero requests"
 * true even for an app the user has simply never opened this settings
 * screen for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppSettingsStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `AI transformation defaults to disabled`() {
        assertFalse(AppSettingsStore(context).isAiEnabled)
    }

    @Test
    fun `allow-list defaults to empty - no app is monitored by default`() {
        val store = AppSettingsStore(context)
        assertTrue(store.getAllowedPackages().isEmpty())
        assertFalse(store.isPackageAllowed("org.telegram.messenger"))
    }

    @Test
    fun `default model is the documented default`() {
        assertEquals(AppSettingsStore.DEFAULT_MODEL, AppSettingsStore(context).selectedModel)
    }

    @Test
    fun `enabling and disabling a package updates isPackageAllowed`() {
        val store = AppSettingsStore(context)
        store.setPackageAllowed("org.telegram.messenger", true)
        assertTrue(store.isPackageAllowed("org.telegram.messenger"))

        store.setPackageAllowed("org.telegram.messenger", false)
        assertFalse(store.isPackageAllowed("org.telegram.messenger"))
    }

    @Test
    fun `allow-listing one package does not affect another`() {
        val store = AppSettingsStore(context)
        store.setPackageAllowed("com.whatsapp", true)
        assertFalse(store.isPackageAllowed("com.discord"))
    }

    @Test
    fun `settings persist across separate store instances backed by the same context`() {
        AppSettingsStore(context).apply {
            isAiEnabled = true
            setPackageAllowed("com.whatsapp", true)
        }
        val reloaded = AppSettingsStore(context)
        assertTrue(reloaded.isAiEnabled)
        assertTrue(reloaded.isPackageAllowed("com.whatsapp"))
    }
}
