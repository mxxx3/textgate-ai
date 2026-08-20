package com.textgate.ai.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps the Gemini API key with AES-256-GCM using a key that never leaves
 * the Android Keystore (StrongBox-backed hardware security module when the
 * device has one; otherwise the Keystore's TEE-backed key). The app never
 * sees the raw wrapping key material — only the Keystore-mediated
 * Cipher object, which is the entire point of using AndroidKeyStore
 * instead of, say, deriving a key from a hardcoded value.
 *
 * Nothing here is logged. On failure, exceptions propagate to the caller
 * (SecureApiKeyStore), which fails closed (treats the key as unavailable)
 * rather than falling back to an unencrypted store.
 */
internal object KeystoreCrypto {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "textgate_api_key_wrap_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    const val IV_LENGTH_BYTES = 12

    data class EncryptedPayload(val iv: ByteArray, val ciphertext: ByteArray)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                keyGenerator.init(buildSpec(strongBox = true))
                keyGenerator.generateKey()
            } else {
                keyGenerator.init(buildSpec(strongBox = false))
                keyGenerator.generateKey()
            }
        } catch (_: StrongBoxUnavailableException) {
            // Device has no StrongBox HSM — fall back to the standard
            // TEE-backed Keystore key, still hardware-isolated on the vast
            // majority of devices, never in software-only storage.
            val fallbackGenerator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            fallbackGenerator.init(buildSpec(strongBox = false))
            fallbackGenerator.generateKey()
        }
    }

    private fun buildSpec(strongBox: Boolean): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)

        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        return builder.build()
    }

    fun encrypt(plaintext: ByteArray): EncryptedPayload {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedPayload(cipher.iv, ciphertext)
    }

    fun decrypt(payload: EncryptedPayload): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, payload.iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)
        return cipher.doFinal(payload.ciphertext)
    }

    /**
     * Permanently destroys the wrapping key. Any previously stored
     * ciphertext becomes permanently undecryptable after this call — this
     * is intentional: it is invoked when the user removes their API key,
     * so the ciphertext left behind (if the pref write ever failed
     * partway) can never be recovered either.
     */
    fun deleteKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
    }
}
