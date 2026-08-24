package org.wxyc.dj.token

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
 * DataStore holds ciphertext; [aead] does the encrypting, over a key Tink
 * derives and wraps with an Android Keystore master key (see
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
 */
class EncryptedTokenStorage(
    private val dataStore: DataStore<Preferences>,
    private val aead: Aead,
) : TokenStorage {

    override suspend fun save(token: String, slot: TokenSlot) {
        val ciphertext = aead.encrypt(token.toByteArray(Charsets.UTF_8), associatedData(slot))
        val encoded = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        dataStore.edit { prefs -> prefs[keyFor(slot)] = encoded }
    }

    override suspend fun load(slot: TokenSlot): String? {
        val encoded = dataStore.data.map { prefs -> prefs[keyFor(slot)] }.first() ?: return null
        val ciphertext = Base64.decode(encoded, Base64.NO_WRAP)
        val plaintext = aead.decrypt(ciphertext, associatedData(slot))
        return String(plaintext, Charsets.UTF_8)
    }

    override suspend fun clear(slot: TokenSlot) {
        dataStore.edit { prefs -> prefs.remove(keyFor(slot)) }
    }

    override suspend fun clearAll() {
        dataStore.edit { prefs -> prefs.clear() }
    }

    private fun keyFor(slot: TokenSlot) = stringPreferencesKey(slot.name)

    private fun associatedData(slot: TokenSlot): ByteArray = slot.name.toByteArray(Charsets.UTF_8)
}
