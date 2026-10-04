package dev.dietapp.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dietapp.data.ReversibleCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecretStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    @Before fun setUp() {
        prefs.edit().clear().commit()
    }

    @Test fun `the key is stored encrypted and never as plain text`() {
        val store = SecretStore(context, ReversibleCipher)
        store.saveKey("  sk-secret-1234567890 ")
        assertEquals("sk-secret-1234567890", store.deepseekKey)
        assertTrue(store.hasKey.value)
        val onDisk = prefs.all.values.joinToString("|")
        assertFalse("no plain copy on disk: $onDisk", onDisk.contains("sk-secret-1234567890"))
        assertNotNull(prefs.getString("deepseek_key_enc", null))
        assertNull(prefs.getString("deepseek_key", null))
    }

    @Test fun `a new process reads the key back`() {
        SecretStore(context, ReversibleCipher).saveKey("sk-secret-1234567890")
        val again = SecretStore(context, ReversibleCipher)
        assertEquals("sk-secret-1234567890", again.deepseekKey)
        assertTrue(again.hasKey.value)
    }

    @Test fun `a key saved in plain text by an older version is encrypted on first read`() {
        prefs.edit().putString("deepseek_key", "sk-old-plain-1234567890").commit()
        val store = SecretStore(context, ReversibleCipher)
        assertEquals("sk-old-plain-1234567890", store.deepseekKey)
        assertNull("the plain copy is gone", prefs.getString("deepseek_key", null))
        assertEquals("enc:" + "sk-old-plain-1234567890".reversed(), prefs.getString("deepseek_key_enc", null))
        assertEquals("sk-old-plain-1234567890", SecretStore(context, ReversibleCipher).deepseekKey)
    }

    @Test fun `a key that cannot be decrypted any more counts as no key, to be entered again`() {
        prefs.edit().putString("deepseek_key_enc", "garbage").commit()
        val store = SecretStore(context, ReversibleCipher)
        assertNull(store.deepseekKey)
        assertFalse(store.hasKey.value)
        assertNull("the dead copy is removed", prefs.getString("deepseek_key_enc", null))
    }

    @Test fun `when the phone cannot encrypt, nothing is stored and the user is told`() {
        val broken = object : KeyCipher {
            override fun encrypt(plain: String): String = throw java.security.GeneralSecurityException("no keystore")
            override fun decrypt(token: String): String? = null
        }
        val store = SecretStore(context, broken)
        try {
            store.saveKey("sk-secret-1234567890")
            fail("expected an error")
        } catch (e: dev.dietapp.data.net.AppError) {
            assertEquals("key_store", e.code)
        }
        assertFalse(store.hasKey.value)
        assertTrue("nothing at all on disk", prefs.all.isEmpty())
    }

    @Test fun `clearing forgets the key everywhere`() {
        val store = SecretStore(context, ReversibleCipher)
        store.saveKey("sk-secret-1234567890")
        store.clear()
        assertNull(store.deepseekKey)
        assertFalse(store.hasKey.value)
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun `the real Keystore cipher round-trips where the system has a Keystore`() {
        val cipher = KeystoreCipher()
        val token = try {
            cipher.encrypt("sk-secret-1234567890")
        } catch (e: Exception) {
            assumeTrue("no Android Keystore on this JVM (${e.javaClass.simpleName})", false)
            return
        }
        assertFalse(token.contains("sk-secret"))
        assertEquals("sk-secret-1234567890", cipher.decrypt(token))
        assertNull(cipher.decrypt("not a token"))
    }
}
