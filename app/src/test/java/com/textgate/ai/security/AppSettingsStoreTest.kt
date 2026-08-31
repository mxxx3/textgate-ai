package com.textgate.ai.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.textgate.ai.model.UserGender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the local settings defaults. The master switch now starts enabled
 * for the typed-trigger translator, while the allow-list itself defaults
 * to a curated set of common
 * social-media/messaging apps (see AppSettingsStore.DEFAULT_ALLOWED_PACKAGES)
 * rather than starting empty — a deliberate, requested trade-off so
 * day-to-day use needs no manual per-app setup — but any app outside that
 * curated set, or the block-list (see AppBlocklistTest), still gets zero
 * requests without the user explicitly adding it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppSettingsStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `AI transformation defaults to enabled`() {
        assertTrue(AppSettingsStore(context).isAiEnabled)
    }

    @Test
    fun `allow-list defaults to the curated social-messaging set, not empty`() {
        val store = AppSettingsStore(context)
        assertEquals(AppSettingsStore.DEFAULT_ALLOWED_PACKAGES, store.getAllowedPackages())
        assertTrue(store.isPackageAllowed("org.telegram.messenger"))
        assertTrue(store.isPackageAllowed("com.whatsapp"))
    }

    @Test
    fun `an app outside the curated default set is not allowed until explicitly added`() {
        val store = AppSettingsStore(context)
        assertFalse(store.isPackageAllowed("com.example.somerandomgame"))
    }

    @Test
    fun `explicitly disabling a default-allowed package overrides the curated default`() {
        val store = AppSettingsStore(context)
        assertTrue(store.isPackageAllowed("com.whatsapp"))

        store.setPackageAllowed("com.whatsapp", false)

        assertFalse(store.isPackageAllowed("com.whatsapp"))
        // Every other default stays intact — disabling one doesn't clear the set.
        assertTrue(store.isPackageAllowed("org.telegram.messenger"))
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
    fun `allow-listing one non-default package does not affect another`() {
        val store = AppSettingsStore(context)
        store.setPackageAllowed("com.example.notesapp", true)
        assertFalse(store.isPackageAllowed("com.example.othernotesapp"))
    }

    @Test
    fun `user gender preference defaults to AUTO`() {
        assertEquals(UserGender.AUTO, AppSettingsStore(context).userGender)
    }

    @Test
    fun `user gender preference persists across separate store instances`() {
        AppSettingsStore(context).userGender = UserGender.FEMALE
        assertEquals(UserGender.FEMALE, AppSettingsStore(context).userGender)

        AppSettingsStore(context).userGender = UserGender.MALE
        assertEquals(UserGender.MALE, AppSettingsStore(context).userGender)
    }

    @Test
    fun `settings persist across separate store instances backed by the same context`() {
        // Deliberately a package outside DEFAULT_ALLOWED_PACKAGES, so this
        // only passes if the explicit choice was actually written to
        // SharedPreferences — not just because the curated default already
        // happened to say true.
        AppSettingsStore(context).apply {
            isAiEnabled = true
            setPackageAllowed("com.example.notesapp", true)
        }
        val reloaded = AppSettingsStore(context)
        assertTrue(reloaded.isAiEnabled)
        assertTrue(reloaded.isPackageAllowed("com.example.notesapp"))
    }
}
