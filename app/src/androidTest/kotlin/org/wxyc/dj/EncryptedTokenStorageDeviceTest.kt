package org.wxyc.dj

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.Lazy
import android.util.Base64
import java.io.File
import java.security.GeneralSecurityException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.wxyc.dj.api.TokenSlot
import org.wxyc.dj.api.TokenStorage
import org.wxyc.dj.token.EncryptedTokenStorage

/**
 * Issue #7's instrumented requirement for the encrypted token store: "a
 * write, a read back through a *newly constructed* store instance (not the
 * same object -- that proves nothing about persistence), and a sign-out
 * clearing it." Deliberately not in `app/src/test` with Robolectric shadows
 * -- per this repo's `CLAUDE.md`, "a shadowed Keystore is a test of the
 * shadow" -- so this runs against the **real** `android.security.keystore`
 * and a real on-disk DataStore file.
 *
 * Uses its own Keystore alias and `SharedPreferences`/DataStore file names,
 * distinct from `di/TokenStorageModule.kt`'s production ones, so this suite
 * can never read or clobber a real session.
 *
 * [withFreshStorage] is the load-bearing helper: each call opens a brand
 * **new** [androidx.datastore.core.DataStore] (its own [CoroutineScope],
 * cancelled and joined before the call returns) over a brand new
 * [Aead] re-derived from the Keystore-wrapped keyset -- not a second
 * [EncryptedTokenStorage] wrapping the *same* live `DataStore`/`Aead`
 * objects. Reusing either object would let the read-back pass off an
 * in-memory cache (DataStore keeps one per live instance; a hypothetical
 * `EncryptedTokenStorage` bug could keep another) without ever touching the
 * disk file or Keystore-wrapped key again, which is exactly the "proves
 * nothing about persistence" trap issue #7's instrumented note calls out.
 * DataStore itself enforces the corollary: constructing a second **live**
 * `DataStore` for the same file while an earlier one is still open throws
 * `IllegalStateException`, so each instance's [CoroutineScope] must be torn
 * all the way down (`cancel()` + `join()`) before the next opens.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedTokenStorageDeviceTest {
    private lateinit var testFile: File

    @Before
    fun setUp() {
        AeadConfig.register()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        testFile = File(context.filesDir, "encrypted_token_storage_device_test.preferences_pb")
        testFile.delete()
    }

    @After
    fun tearDown() {
        testFile.delete()
    }

    @Test
    fun writeThenReadBackThroughANewlyConstructedStoreInstance() = runBlocking {
        withFreshStorage { it.save("session-token-for-juana-molina", TokenSlot.SESSION_TOKEN) }

        val readBack = withFreshStorage { it.load(TokenSlot.SESSION_TOKEN) }

        assertEquals("session-token-for-juana-molina", readBack)
    }

    @Test
    fun aSignOutClearsThePersistedTokenForANewlyConstructedStoreInstance() = runBlocking {
        withFreshStorage { it.save("session-token-for-jessica-pratt", TokenSlot.SESSION_TOKEN) }
        withFreshStorage { it.save("jwt-for-jessica-pratt", TokenSlot.JWT) }

        // AuthService.signOut() -> clearLocalSession() -> tokenStorage.clearAll().
        withFreshStorage { it.clearAll() }

        assertNull(withFreshStorage { it.load(TokenSlot.SESSION_TOKEN) })
        assertNull(withFreshStorage { it.load(TokenSlot.JWT) })
    }

    @Test
    fun whatLandsOnDiskIsNotTheToken() {
        // The reason this class exists. Both cases above pass unchanged if
        // save()/load() drop the Aead and round-trip plaintext -- measured, by
        // making exactly that mutation: 9/9 instrumented tests and the whole
        // host suite stayed green with the DJ's session token sitting in
        // cleartext on disk. Persistence and confidentiality are separate
        // claims, and only the first one had a test.
        val token = "session-token-for-chuquimamani-condori"
        runBlocking { withFreshStorage { it.save(token, TokenSlot.SESSION_TOKEN) } }

        val stored = runBlocking { readRawStoredValue(TokenSlot.SESSION_TOKEN) }
        val fileBytes = testFile.readBytes()

        // Not the token, and not merely an encoding of it: a mutation that
        // dropped the Aead but kept the Base64 layer would still fail the
        // second assertion, which is the one that makes this test about
        // encryption rather than about transport encoding.
        assertNotEquals(token, stored)
        assertNotEquals(token, String(Base64.decode(stored, Base64.NO_WRAP), Charsets.UTF_8))

        // Belt: the token must not appear anywhere in the DataStore file, in
        // either form. Checked against the raw bytes rather than the decoded
        // preference so a future storage-format change cannot quietly move the
        // plaintext somewhere this test stops looking.
        assertFalse(fileBytes.containsSubsequence(token.toByteArray(Charsets.UTF_8)))
        assertFalse(
            fileBytes.containsSubsequence(
                Base64.encodeToString(token.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    .toByteArray(Charsets.UTF_8),
            ),
        )
    }

    @Test
    fun aCiphertextMovedBetweenSlotsFailsTheTagCheck() {
        // Pins the slot binding EncryptedTokenStorage's KDoc claims: each
        // ciphertext carries its TokenSlot name as Tink associated data, so a
        // blob copied from one slot to another fails the AEAD tag check rather
        // than silently decrypting into the wrong slot. Without the binding
        // this returns the session token under TokenSlot.JWT and nothing
        // notices.
        val token = "session-token-for-duke-ellington"
        runBlocking { withFreshStorage { it.save(token, TokenSlot.SESSION_TOKEN) } }

        val sessionCiphertext = runBlocking { readRawStoredValue(TokenSlot.SESSION_TOKEN) }
        runBlocking { writeRawStoredValue(TokenSlot.JWT, requireNotNull(sessionCiphertext)) }

        assertThrows(GeneralSecurityException::class.java) {
            runBlocking { withFreshStorage { it.load(TokenSlot.JWT) } }
        }
    }

    /**
     * Opens a fully independent [EncryptedTokenStorage] -- its own
     * [CoroutineScope]-backed `DataStore` and its own Keystore-derived
     * [Aead] -- runs [block] against it, then tears the scope all the way
     * down before returning. Sequential by construction (each call awaits
     * the previous scope's full cancellation first), so two instances are
     * never simultaneously live against [testFile].
     */
    private suspend fun <T> withFreshStorage(block: suspend (TokenStorage) -> T): T {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { testFile }
        // A plain lambda-backed Lazy, not Dagger's memoizing DoubleCheck --
        // fine here because every withFreshStorage call runs exactly one
        // storage operation, so .get() is called at most once regardless.
        val storage = EncryptedTokenStorage(dataStore, Lazy { freshAead() })
        try {
            return block(storage)
        } finally {
            scope.cancel()
            scope.coroutineContext.job.join()
        }
    }

    /** Reads the stored preference for [slot] without decrypting it -- i.e. exactly the bytes at rest. */
    private suspend fun readRawStoredValue(slot: TokenSlot): String? =
        withFreshDataStore { store ->
            store.data.map { prefs -> prefs[stringPreferencesKey(slot.name)] }.first()
        }

    /** Writes [value] under [slot]'s key verbatim, bypassing encryption -- used to forge a cross-slot copy. */
    private suspend fun writeRawStoredValue(slot: TokenSlot, value: String) {
        withFreshDataStore { store ->
            store.edit { prefs -> prefs[stringPreferencesKey(slot.name)] = value }
        }
    }

    /** [withFreshStorage]'s scope discipline, for the raw-DataStore helpers above. */
    private suspend fun <T> withFreshDataStore(block: suspend (DataStore<Preferences>) -> T): T {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { testFile }
        try {
            return block(dataStore)
        } finally {
            scope.cancel()
            scope.coroutineContext.job.join()
        }
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (start in 0..size - needle.size) {
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) continue@outer
            }
            return true
        }
        return false
    }

    /** Re-derives the Aead from the Android Keystore-wrapped keyset each call -- see the class KDoc. */
    private fun freshAead(): Aead {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(context, "encrypted_token_storage_device_test_keyset", "encrypted_token_storage_device_test_keyset_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://org.wxyc.dj.encrypted_token_storage_device_test_master_key")
            .build()
            .keysetHandle
        return keysetHandle.getPrimitive(Aead::class.java)
    }
}
