package org.wxyc.dj.di

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.wxyc.dj.api.TokenStorage
import org.wxyc.dj.token.EncryptedTokenStorage
import org.wxyc.dj.token.tokenDataStore

/** The Keystore alias wrapping Tink's generated data-encryption key. */
private const val MASTER_KEY_URI = "android-keystore://org.wxyc.dj.token_master_key"

/** The `SharedPreferences` file Tink stores the Keystore-wrapped keyset blob in -- ciphertext, not a credential on its own. */
private const val KEYSET_PREF_FILE_NAME = "org.wxyc.dj.token_master_keyset"
private const val KEYSET_PREF_NAME = "master_keyset"

/**
 * Provides the encrypted [TokenStorage] (issue #7): DataStore + Google
 * Tink, per `docs/port-plan.md`'s "Tech choices" table. Tink is a deliberate
 * new dependency (approved by issue #7, per the repo's ask-first-for-
 * third-party-deps rule), chosen because credential-at-rest crypto is
 * exactly where bespoke AES-GCM goes silently wrong.
 */
@Module
@InstallIn(SingletonComponent::class)
object TokenStorageModule {

    /**
     * A Tink [Aead] over a key generated once and stored, wrapped by an
     * Android Keystore master key, in [KEYSET_PREF_FILE_NAME]. Re-deriving
     * this provider on a fresh process always resolves the **same**
     * plaintext key material, because [AndroidKeysetManager] decrypts the
     * stored keyset with the Keystore key rather than regenerating it --
     * that persistence, on a real Keystore, is exactly what
     * `EncryptedTokenStorageDeviceTest` (issue #7's instrumented
     * requirement) exists to prove, since neither Robolectric nor the host
     * JVM has a real Android Keystore to test against.
     */
    @Provides
    @Singleton
    fun provideAead(@ApplicationContext context: Context): Aead {
        AeadConfig.register()
        val keysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_PREF_NAME, KEYSET_PREF_FILE_NAME)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
        return keysetHandle.getPrimitive(Aead::class.java)
    }

    @Provides
    @Singleton
    fun provideTokenStorage(@ApplicationContext context: Context, aead: Aead): TokenStorage =
        EncryptedTokenStorage(context.tokenDataStore, aead)
}
