package com.textgate.ai.network

/**
 * The single source of truth for "which host is this app allowed to talk
 * to." Every network call in the app must go through GeminiClient, and
 * GeminiClient checks the host it actually built a request for against
 * this allow-list before ever opening a connection — so even a future bug
 * in URL construction cannot silently start talking to a different host.
 *
 * This is intentionally a single constant. There is no configuration UI to
 * add a second host, and no code path anywhere in the app reads a
 * server-provided or user-provided host string and treats it as trusted.
 */
object NetworkAllowlist {
    /** The official Gemini API host. This is the ONLY external host this
     * app ever contacts. */
    const val GEMINI_HOST: String = "generativelanguage.googleapis.com"

    fun isAllowedHost(host: String): Boolean = host == GEMINI_HOST
}
