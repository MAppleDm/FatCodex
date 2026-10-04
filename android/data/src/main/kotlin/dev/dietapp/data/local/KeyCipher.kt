package dev.dietapp.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject

/** Turns a secret into text that is safe to keep in a preferences file, and back. */
interface KeyCipher {
    /** Throws [GeneralSecurityException] when the phone cannot do it. */
    fun encrypt(plain: String): String

    /** Null when [token] cannot be read (damaged, or its key is gone), never a guess. */
    fun decrypt(token: String): String?
}

/**
 * AES-256-GCM with a key that lives in the Android Keystore: it never leaves the secure hardware (or the system's
 * key store) and is not part of any backup, so a copy of the app's files, or an old backup, does not hold a usable key.
 * The token is the 12-byte IV followed by the ciphertext, in Base64.
 */
class KeystoreCipher @Inject constructor() : KeyCipher {

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(cipher.iv + encrypted)
    }

    override fun decrypt(token: String): String? = try {
        val bytes = Base64.getDecoder().decode(token)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        }
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    } catch (_: GeneralSecurityException) {
        null // the key was lost (a restored phone, a changed lock screen) or the text was damaged
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "fatcodex.model_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
