package com.textgate.ai.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkAllowlistTest {

    @Test
    fun `official Gemini host is allowed`() {
        assertTrue(NetworkAllowlist.isAllowedHost("generativelanguage.googleapis.com"))
    }

    @Test
    fun `any other host, including look-alikes, is rejected`() {
        assertFalse(NetworkAllowlist.isAllowedHost("generativelanguage.googleapis.com.evil.com"))
        assertFalse(NetworkAllowlist.isAllowedHost("evil.generativelanguage.googleapis.com"))
        assertFalse(NetworkAllowlist.isAllowedHost("googleapis.com"))
        assertFalse(NetworkAllowlist.isAllowedHost("localhost"))
        assertFalse(NetworkAllowlist.isAllowedHost(""))
    }
}
