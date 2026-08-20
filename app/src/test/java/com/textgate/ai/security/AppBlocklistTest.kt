package com.textgate.ai.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBlocklistTest {

    @Test
    fun `known password manager packages are blocked`() {
        assertTrue(AppBlocklist.isBlocked("com.lastpass.lpandroid"))
        assertTrue(AppBlocklist.isBlocked("com.x8bit.bitwarden"))
        assertTrue(AppBlocklist.isBlocked("com.dashlane"))
    }

    @Test
    fun `known authenticator packages are blocked`() {
        assertTrue(AppBlocklist.isBlocked("com.google.android.apps.authenticator2"))
        assertTrue(AppBlocklist.isBlocked("com.azure.authenticator"))
        assertTrue(AppBlocklist.isBlocked("com.microsoft.authenticator"))
    }

    @Test
    fun `system security surfaces are blocked`() {
        assertTrue(AppBlocklist.isBlocked("com.android.systemui"))
        assertTrue(AppBlocklist.isBlocked("com.android.settings"))
    }

    @Test
    fun `keyword heuristic blocks banking-shaped and wallet-shaped package names`() {
        assertTrue(AppBlocklist.isBlocked("com.examplebank.mobilebanking"))
        assertTrue(AppBlocklist.isBlocked("com.example.cryptowallet"))
        assertTrue(AppBlocklist.isBlocked("com.example.passwordvault"))
    }

    @Test
    fun `known cryptocurrency wallet and exchange packages are blocked`() {
        // Real package names, confirmed against an actual installed-apps
        // list during device testing — none contain "wallet"/"bank" and so
        // would otherwise slip past the keyword heuristic alone.
        assertTrue(AppBlocklist.isBlocked("io.metamask"))
        assertTrue(AppBlocklist.isBlocked("app.phantom"))
        assertTrue(AppBlocklist.isBlocked("com.binance.dev"))
        assertTrue(AppBlocklist.isBlocked("com.coinbase.android"))
        assertTrue(AppBlocklist.isBlocked("com.kubi.kucoin"))
        assertTrue(AppBlocklist.isBlocked("com.okinc.okex.gp"))
        assertTrue(AppBlocklist.isBlocked("com.tronlink.global"))
    }

    @Test
    fun `own package is always blocked regardless of name`() {
        assertTrue(AppBlocklist.isBlocked("com.textgate.ai", ownPackageName = "com.textgate.ai"))
    }

    @Test
    fun `blank package name is blocked (fail closed)`() {
        assertTrue(AppBlocklist.isBlocked(""))
    }

    @Test
    fun `ordinary messaging apps are not blocked`() {
        assertFalse(AppBlocklist.isBlocked("org.telegram.messenger"))
        assertFalse(AppBlocklist.isBlocked("com.whatsapp"))
        assertFalse(AppBlocklist.isBlocked("org.thoughtcrime.securesms")) // Signal
        assertFalse(AppBlocklist.isBlocked("com.discord"))
    }
}
