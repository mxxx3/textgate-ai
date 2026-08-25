package com.textgate.ai.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import java.util.Arrays
import java.util.UUID

/**
 * Persists an ORDERED LIST of Gemini API keys, each individually encrypted
 * with AES-256-GCM via [KeystoreCrypto] — the same single Keystore-resident
 * wrapping key used to seal every entry (one wrapping key sealing many
 * independent (iv, ciphertext) pairs is exactly as safe as one key sealing
 * one value: GCM's security only requires a fresh random IV per encryption,
 * which [KeystoreCrypto.encrypt] already generates on every call).
 *
 * WHY MULTIPLE KEYS: each Gemini API key is the free tier of an individual
 * Google AI Studio project, with its own request quota. One key alone can
 * run out well before a user is done for the day. Storing several lets
 * [com.textgate.ai.network.KeyRotationTranslator] automatically move to the
 * next stored key the moment the currently active one reports a
 * quota-exceeded response (HTTP 429), so translation keeps working across
 * a user's own set of free-tier keys without them having to notice or
 * intervene mid-task. Adding a key never requires code changes elsewhere —
 * the rotation order is simply the order keys were added in.
 *
 * Guarantees this class upholds (same as the single-key version it
 * replaced in v1.6.0):
 *   - No plaintext key is ever written to disk.
 *   - Nothing here calls android.util.Log with any key or ciphertext.
 *   - Nothing here touches ClipboardManager.
 *   - The only plaintext ever persisted per key is its last 4 characters
 *     (`last4`), purely so the Settings screen can show the user which
 *     saved key is which ("•••• aB12") without ever displaying a full key
 *     again after it is saved — the same convention Stripe, AWS, and
 *     Google Cloud's own consoles use for exactly this reason. Four
 *     characters alone do not meaningfully weaken a Gemini API key's
 *     effective secrecy.
 *   - Byte buffers holding plaintext key material are zeroed as soon as
 *     they are no longer needed; a resulting Kotlin/Java `String`, once
 *     created, cannot be forcibly zeroed — a JVM-level limitation
 *     documented here rather than silently ignored. Callers must minimize
 *     how long they hold a decrypted key String and must never log it,
 *     store it in a field, or pass it anywhere other than the outgoing
 *     HTTPS request header (see [com.textgate.ai.network.GeminiClient]).
 */
class SecureApiKeyStore(context: Context) {

    /** [id] is an internal, randomly generated identifier — never derived
     * from or containing any part of the actual key — used only to address
     * one stored key's prefs entries and to name it in [removeKey]. [last4]
     * is the only plaintext fragment of the real key ever persisted. */
    data class StoredKey(val id: String, val last4: String)

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Ordered by when each key was added (oldest first) — this is also
     * the rotation order [advanceActiveKey] cycles through. */
    fun listKeys(): List<StoredKey> = readOrder().mapNotNull { id ->
        val last4 = prefs.getString(last4Pref(id), null) ?: return@mapNotNull null
        StoredKey(id, last4)
    }

    fun keyCount(): Int = readOrder().size

    fun hasAnyKey(): Boolean = keyCount() > 0

    /**
     * Encrypts and appends [apiKey] as a new stored key. Returns false (and
     * stores nothing) if [apiKey] is blank. The newly added key becomes the
     * active key only if there was no active key before (i.e. this is the
     * very first key ever added) — adding a second or later key never
     * silently switches which key is currently in use.
     */
    fun addKey(apiKey: CharArray): Boolean {
        if (apiKey.isEmpty() || apiKey.all { it.isWhitespace() }) return false

        val plaintext = String(apiKey)
        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        try {
            val id = UUID.randomUUID().toString()
            val payload = KeystoreCrypto.encrypt(plaintextBytes)
            val last4 = plaintext.takeLast(4)

            val order = readOrder() + id

            prefs.edit()
                .putString(ivPref(id), Base64.encodeToString(payload.iv, Base64.NO_WRAP))
                .putString(ciphertextPref(id), Base64.encodeToString(payload.ciphertext, Base64.NO_WRAP))
                .putString(last4Pref(id), last4)
                .putString(KEY_ORDER, JSONArray(order).toString())
                .apply()

            if (prefs.getString(KEY_ACTIVE_ID, null) == null) {
                prefs.edit().putString(KEY_ACTIVE_ID, id).apply()
            }
            return true
        } finally {
            Arrays.fill(plaintextBytes, 0)
            Arrays.fill(apiKey, '\u0000')
        }
    }

    /**
     * Removes one stored key by id. If it was the active key, the active
     * pointer moves to whichever key is now next in rotation order
     * (wrapping around), or is cleared entirely if none remain.
     */
    fun removeKey(id: String) {
        val oldOrder = readOrder()
        val removedIndex = oldOrder.indexOf(id)
        if (removedIndex == -1) return
        val newOrder = oldOrder.toMutableList().apply { removeAt(removedIndex) }

        prefs.edit()
            .remove(ivPref(id))
            .remove(ciphertextPref(id))
            .remove(last4Pref(id))
            .putString(KEY_ORDER, JSONArray(newOrder).toString())
            .apply()

        if (prefs.getString(KEY_ACTIVE_ID, null) == id) {
            // removedIndex % newOrder.size lands on whichever key shifted
            // into the removed slot (i.e. the key that was next after it),
            // and correctly wraps to index 0 when the removed key was last.
            val newActive = if (newOrder.isEmpty()) null else newOrder[removedIndex % newOrder.size]
            if (newActive != null) {
                prefs.edit().putString(KEY_ACTIVE_ID, newActive).apply()
            } else {
                prefs.edit().remove(KEY_ACTIVE_ID).apply()
            }
        }
    }

    /** Removes every stored key and destroys the shared Keystore wrapping
     * key — same "destroy the key, not just the ciphertext" behavior as the
     * single-key version this replaced. */
    fun clearAllKeys() {
        val editor = prefs.edit()
        for (id in readOrder()) {
            editor.remove(ivPref(id)).remove(ciphertextPref(id)).remove(last4Pref(id))
        }
        editor.remove(KEY_ORDER).remove(KEY_ACTIVE_ID).apply()
        try {
            KeystoreCrypto.deleteKey()
        } catch (_: Exception) {
            // Best-effort key deletion; see single-key version's identical
            // comment — the ciphertext references above are already gone
            // either way, so there is nothing left to decrypt regardless.
        }
    }

    /** The id of the key [getActiveKeyPlaintext] would currently decrypt,
     * or null if no key is stored. Defensively re-checked against
     * [readOrder] so a stale pointer (which should never happen — every
     * mutation above keeps it in sync) can never point at a removed key. */
    fun activeKeyId(): String? = prefs.getString(KEY_ACTIVE_ID, null)?.takeIf { it in readOrder() }

    /** Decrypts and returns the currently active key's plaintext, or null
     * if none is stored or decryption fails for any reason (e.g. the
     * Keystore wrapping key was lost). Failure NEVER falls back to a
     * plaintext store or to a different key automatically — the caller
     * ([com.textgate.ai.network.KeyRotationTranslator]) decides whether to
     * treat that as "no usable key". */
    fun getActiveKeyPlaintext(): String? {
        val id = activeKeyId() ?: return null
        return decrypt(id)
    }

    /**
     * Moves the active pointer to the NEXT key in add-order, wrapping back
     * to the first key after the last. A single stored key rotates to
     * itself (a deliberate no-op — there is nothing else to try). Called
     * only by [com.textgate.ai.network.KeyRotationTranslator], exactly when
     * the currently active key's request came back quota-exceeded.
     */
    fun advanceActiveKey() {
        val order = readOrder()
        if (order.isEmpty()) return
        val currentIndex = order.indexOf(prefs.getString(KEY_ACTIVE_ID, null))
        val nextIndex = (currentIndex + 1) % order.size
        prefs.edit().putString(KEY_ACTIVE_ID, order[nextIndex]).apply()
    }

    private fun decrypt(id: String): String? {
        val ivB64 = prefs.getString(ivPref(id), null) ?: return null
        val ciphertextB64 = prefs.getString(ciphertextPref(id), null) ?: return null
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

    private fun readOrder(): List<String> {
        val raw = prefs.getString(KEY_ORDER, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun ivPref(id: String) = "key_iv_$id"
    private fun ciphertextPref(id: String) = "key_ciphertext_$id"
    private fun last4Pref(id: String) = "key_last4_$id"

    companion object {
        private const val PREFS_NAME = "textgate_secure_prefs"
        private const val KEY_ORDER = "key_order"
        private const val KEY_ACTIVE_ID = "active_key_id"
    }
}
