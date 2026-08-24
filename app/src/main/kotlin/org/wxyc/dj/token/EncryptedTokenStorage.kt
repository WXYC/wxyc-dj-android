package org.wxyc.dj.token

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.wxyc.dj.api.TokenSlot
import org.wxyc.dj.api.TokenStorage

private const val TOKEN_DATASTORE_NAME = "wxyc_dj_tokens"

/**
 * The process-wide [DataStore] instance backing [EncryptedTokenStorage].
 * Declared once at file scope, per DataStore's own guidance: creating a
 * second live `DataStore` for the same file within one process throws
 * `IllegalStateException`, so every production caller must share this one
 * property rather than each constructing its own.
 */
val Context.tokenDataStore: DataStore<Preferences> by preferencesDataStore(name = TOKEN_DATASTORE_NAME)

/**
 * The Android-only, encrypted implementation of `:api`'s [TokenStorage]
 * (issue #7). Lives in `:app`, not `:api`, because both DataStore and the
 * Android Keystore are platform APIs -- `:api` keeps only the interface and
 * [org.wxyc.dj.api.InMemoryTokenStorage], exactly as this repo's `CLAUDE.md`
 * describes for a platform-backed `:api` interface implementation.
 *
 * DataStore holds ciphertext; [aead] does the encrypting, over a random
 * AES256-GCM data-encryption key Tink generates once and wraps (not derives
 * -- there is no KDF here) with an Android Keystore master key (see
 * `di/TokenStorageModule.kt` for how [aead] is built). **Not**
 * `EncryptedSharedPreferences`, deprecated as of
 * `security-crypto:1.1.0-alpha07` (main-thread StrictMode violations,
 * keyset-corruption crashes on some OEMs) -- see `docs/port-plan.md`'s
 * "Tech choices" table.
 *
 * Each ciphertext is bound to the [TokenSlot] it is stored under as Tink
 * associated data: a value copied between slots (e.g. a JWT blob pasted
 * into the session-token key) fails the AEAD tag check on decrypt rather
 * than silently decrypting into the wrong slot.
 *
 * [aead] is a [Lazy]`<Aead>`, not a plain [Aead], and every method below
 * resolves it inside `withContext(Dispatchers.IO)` rather than on whatever
 * dispatcher the caller happens to be on. Both halves matter: the [Lazy]
 * defers `di/TokenStorageModule.kt`'s Keystore/Tink construction until the
 * first real call instead of at Hilt-graph-construction time (which,
 * through `AuthGate`'s `hiltViewModel()`, is otherwise the main thread
 * during Compose composition); the `withContext` then keeps every
 * subsequent call's DataStore read/write and Keystore-backed
 * encrypt/decrypt off the caller's dispatcher too, since a `ViewModel`'s
 * `viewModelScope` runs on `Dispatchers.Main.immediate` by default.
 */
class EncryptedTokenStorage(
    private val dataStore: DataStore<Preferences>,
    private val aead: Lazy<Aead>,
) : TokenStorage {

    override suspend fun save(token: String, slot: TokenSlot) = withContext(Dispatchers.IO) {
        val ciphertext = aead.get().encrypt(token.toByteArray(Charsets.UTF_8), associatedData(slot))
        val encoded = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        dataStore.edit { prefs -> prefs[keyFor(slot)] = encoded }
        Unit
    }

    override suspend fun load(slot: TokenSlot): String? = withContext(Dispatchers.IO) {
        val encoded = dataStore.data.map { prefs -> prefs[keyFor(slot)] }.first() ?: return@withContext null
        val ciphertext = Base64.decode(encoded, Base64.NO_WRAP)
        val plaintext = aead.get().decrypt(ciphertext, associatedData(slot))
        String(plaintext, Charsets.UTF_8)
    }

    override suspend fun clear(slot: TokenSlot) = withContext(Dispatchers.IO) {
        dataStore.edit { prefs -> prefs.remove(keyFor(slot)) }
        Unit
    }

    override suspend fun clearAll() = withContext(Dispatchers.IO) {
        dataStore.edit { prefs -> prefs.clear() }
        Unit
    }

    private fun keyFor(slot: TokenSlot) = stringPreferencesKey(slot.name)

    private fun associatedData(slot: TokenSlot): ByteArray = slot.name.toByteArray(Charsets.UTF_8)
}
