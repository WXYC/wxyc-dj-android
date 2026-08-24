package org.wxyc.dj.di

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.wxyc.dj.api.TokenStorage
import org.wxyc.dj.token.EncryptedTokenStorage
import org.wxyc.dj.token.tokenDataStore

private const val LOG_TAG = "TokenStorageModule"

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
 *
 * [provideAead] is bound as a plain [Aead], but [provideTokenStorage] injects
 * it as a [Lazy]`<Aead>` rather than an eager [Aead] -- see that method's
 * KDoc for why deferring the resolution is load-bearing, not stylistic.
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
     *
     * This does a `SharedPreferences` disk read plus an AndroidKeyStore
     * binder round-trip, and generates the master key on first run -- real
     * I/O, never call it from a composition or the main thread directly.
     * [EncryptedTokenStorage] is the one caller, and it only ever resolves
     * this (via the injected [Lazy]) from inside `withContext(Dispatchers.IO)`.
     *
     * tink-android 1.19.0's [AndroidKeysetManager.Builder.build] has no catch
     * and no regenerate fallback on its read path: a lost Keystore key or a
     * corrupted keyset blob throws straight out of this provider, out of
     * [org.wxyc.dj.ui.AuthViewModel]'s constructor, and crashes the hosting
     * Activity on *every* launch -- unrecoverable short of clearing app data,
     * since Hilt re-attempts the same failing provider on every cold start.
     * [buildAeadPrimitive] adds the missing fallback: a failed first attempt
     * clears the keyset preferences file and builds once more, so a corrupted
     * or unusable keyset degrades to "the DJ has to sign in again" --
     * `AuthService.restoreSession()`'s existing storage-failure arm already
     * maps a [TokenStorage] failure to [org.wxyc.dj.api.AuthState.SignedOut]
     * -- instead of bricking the app. A second failure still propagates:
     * this is a bounded retry, not a loop.
     */
    @Provides
    @Singleton
    fun provideAead(@ApplicationContext context: Context): Aead {
        AeadConfig.register()
        return buildAeadPrimitive(context)
    }

    /**
     * [EncryptedTokenStorage] takes [Aead] as a [Lazy] rather than resolving
     * it here eagerly. Hilt would otherwise construct the real [Aead] --
     * [provideAead]'s Keystore/Tink I/O -- the instant something upstream of
     * [TokenStorage] is requested, and that chain starts from
     * `AuthGate`'s `hiltViewModel()`, i.e. **during Compose composition on
     * the main thread**: `hiltViewModel()` -> `AuthViewModel` ->
     * `AuthService` -> `TokenStorage` -> (formerly) an eager [Aead] ->
     * [provideAead]. Injecting [Lazy]`<Aead>` defers that resolution to
     * [EncryptedTokenStorage]'s first `save`/`load`/`clear`/`clearAll` call,
     * each of which wraps the resolution in `withContext(Dispatchers.IO)` --
     * so the same real work now happens off the main thread on first use
     * instead of synchronously during composition.
     */
    @Provides
    @Singleton
    fun provideTokenStorage(@ApplicationContext context: Context, aead: Lazy<Aead>): TokenStorage =
        EncryptedTokenStorage(context.tokenDataStore, aead)

    /**
     * `internal` and parameterized -- rather than a private, no-arg function
     * hardcoded to the production keyset names -- so
     * `TokenStorageRegenerationDeviceTest` can drive this exact regenerate
     * branch against a test-owned keyset/pref/master-key alias, never the
     * real one. [provideAead] calls this with the production defaults.
     */
    internal fun buildAeadPrimitive(
        context: Context,
        keysetPrefName: String = KEYSET_PREF_NAME,
        keysetPrefFileName: String = KEYSET_PREF_FILE_NAME,
        masterKeyUri: String = MASTER_KEY_URI,
    ): Aead {
        return try {
            keysetHandle(context, keysetPrefName, keysetPrefFileName, masterKeyUri).getPrimitive(Aead::class.java)
        } catch (e: Exception) {
            // A corrupted keyset blob or an invalidated/missing Keystore key
            // both surface here as a GeneralSecurityException or IOException
            // out of AndroidKeysetManager.Builder.build() -- caught broadly
            // because tink-android does not distinguish the two at this call
            // site, and both have the same remedy: the wrapped ciphertext is
            // unusable, so wipe it and let AndroidKeysetManager generate a
            // fresh keyset under the same Keystore master key. This can never
            // recover the previously-stored session token -- it was
            // unreadable anyway -- so the DJ signs in again, which is exactly
            // what a lost Keystore key already implies.
            Log.w(LOG_TAG, "Keystore-wrapped keyset unreadable; regenerating and clearing stored tokens", e)
            context.getSharedPreferences(keysetPrefFileName, Context.MODE_PRIVATE).edit { clear() }
            keysetHandle(context, keysetPrefName, keysetPrefFileName, masterKeyUri).getPrimitive(Aead::class.java)
        }
    }

    private fun keysetHandle(
        context: Context,
        keysetPrefName: String,
        keysetPrefFileName: String,
        masterKeyUri: String,
    ): KeysetHandle =
        AndroidKeysetManager.Builder()
            .withSharedPref(context, keysetPrefName, keysetPrefFileName)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(masterKeyUri)
            .build()
            .keysetHandle
}
