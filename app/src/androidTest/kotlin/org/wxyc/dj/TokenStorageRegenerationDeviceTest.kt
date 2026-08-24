package org.wxyc.dj

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.wxyc.dj.di.TokenStorageModule

/**
 * The finding this pins: tink-android 1.19.0's
 * [AndroidKeysetManager.Builder.build] has no catch and no regenerate
 * fallback on its read path, so a corrupted Keystore-wrapped keyset blob
 * used to throw straight out of `TokenStorageModule.provideAead` -- out of
 * `AuthViewModel`'s constructor, crashing `MainActivity` on every launch,
 * unrecoverable short of clearing app data. `TokenStorageModule.buildAeadPrimitive`
 * is the fallback: catch, wipe the keyset preferences file, rebuild once.
 *
 * This corrupts a **real** Keystore-wrapped keyset on a **real**
 * `SharedPreferences` file, not a Robolectric shadow -- per this repo's
 * `CLAUDE.md`, "a shadowed Keystore is a test of the shadow." It calls
 * [TokenStorageModule.buildAeadPrimitive] directly (an `internal` function,
 * parameterized for exactly this reason) against a test-only keyset
 * name/pref-file/master-key alias, so this suite can never read, corrupt, or
 * regenerate the app's real production keyset.
 */
@RunWith(AndroidJUnit4::class)
class TokenStorageRegenerationDeviceTest {

    private val keysetPrefName = "token_storage_regeneration_device_test_keyset"
    private val keysetPrefFileName = "token_storage_regeneration_device_test_keyset_prefs"
    private val masterKeyUri = "android-keystore://org.wxyc.dj.token_storage_regeneration_device_test_master_key"

    private lateinit var context: Context

    @Before
    fun setUp() {
        AeadConfig.register()
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences(keysetPrefFileName, Context.MODE_PRIVATE).edit().clear().apply()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(keysetPrefFileName, Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Test
    fun aCorruptedKeysetBlobIsRegeneratedInsteadOfThrowing() {
        // Establish a real, working keyset under the test alias first, so the
        // corruption below is a genuine "was working, now isn't" case rather
        // than merely "was never written."
        val firstAead = TokenStorageModule.buildAeadPrimitive(context, keysetPrefName, keysetPrefFileName, masterKeyUri)
        val plaintext = "session-token-for-jessica-pratt".toByteArray(Charsets.UTF_8)
        // Round-trip once to prove the first keyset is genuinely usable, not
        // merely constructed.
        val firstCiphertext = firstAead.encrypt(plaintext, null)
        assertArrayEquals(plaintext, firstAead.decrypt(firstCiphertext, null))

        // Corrupt the persisted, Keystore-wrapped keyset blob in place -- the
        // shape of damage a torn write, an OEM SharedPreferences bug, or a
        // partial backup restore leaves behind: the preference key still
        // exists, but its value no longer parses as a valid Tink keyset.
        context.getSharedPreferences(keysetPrefFileName, Context.MODE_PRIVATE)
            .edit()
            .putString(keysetPrefName, "not-a-valid-tink-keyset")
            .apply()

        // Confirm the corruption is real and would otherwise throw: the bare
        // AndroidKeysetManager call, with none of TokenStorageModule's
        // fallback, fails to build.
        assertThrows(Exception::class.java) {
            AndroidKeysetManager.Builder()
                .withSharedPref(context, keysetPrefName, keysetPrefFileName)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(masterKeyUri)
                .build()
        }

        // The module's own regenerate path must not throw, and must hand
        // back a genuinely usable Aead -- not merely a non-null one.
        val regeneratedAead =
            TokenStorageModule.buildAeadPrimitive(context, keysetPrefName, keysetPrefFileName, masterKeyUri)
        val regeneratedCiphertext = regeneratedAead.encrypt(plaintext, null)
        assertArrayEquals(plaintext, regeneratedAead.decrypt(regeneratedCiphertext, null))
    }
}
