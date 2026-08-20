package com.textgate.ai.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.util.Arrays

/**
 * Persists the Gemini API key ONLY as AES-256-GCM ciphertext produced by
 * [KeystoreCrypto], whose wrapping key never leaves the Android Keystore.
 *
 * Guarantees this class upholds:
 *   - The plaintext key is never written to disk, ever.
 *   - The SharedPreferences file it uses is excluded from all backups
 *     (see AndroidManifest.xml allowBackup/dataExtractionRules and
 *     backup_rules.xml — those rules exclude the entire sharedpref domain,
 *     which includes this file).
 *   - Nothing in this class calls android.util.Log with the key, the
 *     ciphertext, or any derived value.
 *   - Nothing in this class touches ClipboardManager.
 *   - Byte buffers holding plaintext key material are zeroed as soon as
 *     they are no longer needed. (A resulting Kotlin/Java `String`, once
 *     created, cannot be forcibly zeroed — that is a JVM-level limitation
 *     documented here rather than silently ignored. Callers must minimize
 *     how long they hold a decrypted key String and must never log it,
 *     store it in a field, or pass it anywhere other than the outgoing
 *     HTTPS request header.)
 */
class SecureApiKeyStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasApiKey(): Boolean = prefs.contains(KEY_IV) && prefs.contains(KEY_CIPHERTEXT)

    /**
     * Encrypts [apiKey] and persists the ciphertext, overwriting anything
     * previously stored. [apiKey] is cleared (overwritten) as soon as it
     * has been encrypted.
     *
     * Returns false (and stores nothing) if [apiKey] is blank.
     */
    fun saveApiKey(apiKey: CharArray): Boolean {
        if (apiKey.isEmpty() || apiKey.all { it.isWhitespace() }) return false

        val plaintextBytes = String(apiKey).toByteArray(Charsets.UTF_8)
        try {
            val payload = KeystoreCrypto.encrypt(plaintextBytes)
            prefs.edit()
                .putString(KEY_IV, Base64.encodeToString(payload.iv, Base64.NO_WRAP))
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(payload.ciphertext, Base64.NO_WRAP))
                .apply()
            return true
        } finally {
            Arrays.fill(plaintextBytes, 0)
            Arrays.fill(apiKey, '\u0000')
        }
    }

    /**
     * Decrypts and returns the stored key, or null if none is stored or
     * decryption fails for any reason (e.g. the Keystore key was lost —
     * this happens if the device's lock-screen credential was removed on
     * some OEM configurations; in that case the user must re-enter their
     * key). Failure NEVER falls back to a plaintext store — it simply
     * returns null, and the caller must treat that as "no key configured".
     */
    fun getApiKey(): String? {
        val ivB64 = prefs.getString(KEY_IV, null) ?: return null
        val ciphertextB64 = prefs.getString(KEY_CIPHERTEXT, null) ?: return null
        return try {
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val ciphertext = Base64.decode(ciphertextB64, Base64.NO_WRAP)
            val plaintextBytes = KeystoreCrypto.decrypt(KeystoreCrypto.EncryptedPayload(iv, ciphertext))
            try {
                String(plaintextBytes, Charsets.UTF_8)
            } finally {
                Arrays.fill(plaintextBytes, 0)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Removes the stored ciphertext and destroys the Keystore wrapping key. */
    fun clearApiKey() {
        prefs.edit().remove(KEY_IV).remove(KEY_CIPHERTEXT).apply()
        try {
            KeystoreCrypto.deleteKey()
        } catch (_: Exception) {
            // Best-effort key deletion; the ciphertext reference above is
            // already gone either way, so there is nothing left to decrypt.
        }
    }

    companion object {
        private const val PREFS_NAME = "textgate_secure_prefs"
        private const val KEY_IV = "api_key_iv"
        private const val KEY_CIPHERTEXT = "api_key_ciphertext"
    }
}
