package io.github.magnusencoded.stationtostation.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.magnusencoded.stationtostation.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * The bearer secrets, in a file of their own so that backup can be told about them.
 *
 * [Credentials] already says these are *"never in the records manifest, never in an
 * export, never in a backup"*. Android's backup rules exclude **files**, not keys, so
 * that sentence could not be true while the tokens shared `settings` with the friends
 * list — excluding one meant losing the other on a restore, and the friends list is
 * exactly what a person reinstalling wants back. Two files, and both halves get what
 * they need: this one is excluded (see `backup_rules.xml`), `settings` is not.
 *
 * The split follows the model rather than convenience: [Identities] records that the
 * setlist.fm username is *"public and is an identity, not a credential"*, so it stays
 * in `settings` with the friends. What lives here is what [Credentials] names.
 *
 * The expiry and scope are not themselves secret, but they describe a token and are
 * meaningless without one — a restored expiry for a token that did not come back
 * would be a lie about a credential the device does not hold.
 */
private val Context.credentialStore by preferencesDataStore(name = "credentials")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val SETLISTFM_API_KEY = stringPreferencesKey("setlistfm_api_key")
        val SPOTIFY_CLIENT_ID = stringPreferencesKey("spotify_client_id")
        val SPOTIFY_ACCESS_TOKEN = stringPreferencesKey("spotify_access_token")
        val SPOTIFY_REFRESH_TOKEN = stringPreferencesKey("spotify_refresh_token")
        val SPOTIFY_TOKEN_EXPIRY = longPreferencesKey("spotify_token_expiry")
        val SPOTIFY_SCOPE = stringPreferencesKey("spotify_scope")
        val PKCE_VERIFIER = stringPreferencesKey("pkce_verifier")
        val MY_SETLISTFM_USER = stringPreferencesKey("my_setlistfm_user")
        val FRIENDS = stringPreferencesKey("friends")
        val ONBOARDED = booleanPreferencesKey("onboarded")
    }

    /**
     * Tokens written before the split, moved once and erased from where they were.
     *
     * An install that predates this has its refresh token in `settings`, which is in
     * the backup set — so leaving a copy behind would keep the exact exposure this
     * change exists to close. Reading the old file is also what stops the move from
     * costing the user a Spotify login they had already done.
     *
     * Idempotent, and safe to lose: if the process dies mid-move the tokens are still
     * in one file or the other, and the worst case is logging in again.
     */
    private suspend fun migrateCredentials() {
        val old = context.dataStore.data.first()
        val leftBehind = CREDENTIAL_KEYS.filter { old.contains(it) }
        if (leftBehind.isEmpty()) return

        // Only fill an empty credential store. A login writes straight to the new one,
        // so if anything is already there it is newer than whatever `settings` kept —
        // copying then would log the user out by restoring the token they replaced.
        // Either way the old copy goes, which is the point of the exercise.
        val current = context.credentialStore.data.first()
        if (CREDENTIAL_KEYS.none { current.contains(it) }) {
            context.credentialStore.edit { new ->
                for (key in leftBehind) {
                    @Suppress("UNCHECKED_CAST")
                    new[key as Preferences.Key<Any>] = old[key]!!
                }
            }
        }
        context.dataStore.edit { prefs -> leftBehind.forEach { prefs.remove(it) } }
    }

    /** True once the user has passed the splash (logged in with Spotify or skipped). */
    val onboarded: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.ONBOARDED] ?: false }

    suspend fun setOnboarded() {
        context.dataStore.edit { it[Keys.ONBOARDED] = true }
    }

    val mySetlistFmUser: Flow<String?> =
        context.dataStore.data.map { it[Keys.MY_SETLISTFM_USER]?.ifBlank { null } }

    suspend fun saveMySetlistFmUser(value: String) {
        context.dataStore.edit { it[Keys.MY_SETLISTFM_USER] = value.trim() }
    }

    val friends: Flow<List<Friend>> =
        context.dataStore.data.map { decodeFriends(it[Keys.FRIENDS]) }

    suspend fun saveFriends(friends: List<Friend>) {
        context.dataStore.edit { it[Keys.FRIENDS] = encodeFriends(friends) }
    }

    val setlistFmApiKey: Flow<String?> =
        context.dataStore.data.map { it[Keys.SETLISTFM_API_KEY]?.ifBlank { null } }
    val spotifyClientId: Flow<String?> =
        context.dataStore.data.map { it[Keys.SPOTIFY_CLIENT_ID]?.ifBlank { null } }
    // Private: reading the credential store without [migrateCredentials] first would
    // report an install that predates the split as logged out. Go via
    // [refreshTokenValue], which migrates.
    private val spotifyRefreshToken: Flow<String?> =
        context.credentialStore.data.map { it[Keys.SPOTIFY_REFRESH_TOKEN]?.ifBlank { null } }

    // User-entered values take precedence; otherwise fall back to credentials
    // bundled at build time (see app/build.gradle.kts).
    suspend fun setlistFmApiKeyValue(): String? =
        setlistFmApiKey.first() ?: BuildConfig.SETLISTFM_API_KEY.ifBlank { null }

    suspend fun spotifyClientIdValue(): String? =
        spotifyClientId.first() ?: BuildConfig.SPOTIFY_CLIENT_ID.ifBlank { null }

    fun hasBundledSetlistFmKey(): Boolean = BuildConfig.SETLISTFM_API_KEY.isNotBlank()
    fun hasBundledSpotifyClientId(): Boolean = BuildConfig.SPOTIFY_CLIENT_ID.isNotBlank()

    fun bundledSetlistFmKeyHint(): String = maskedHint(BuildConfig.SETLISTFM_API_KEY)
    fun bundledSpotifyClientIdHint(): String = maskedHint(BuildConfig.SPOTIFY_CLIENT_ID)

    suspend fun saveSetlistFmApiKey(value: String) {
        context.dataStore.edit { it[Keys.SETLISTFM_API_KEY] = value.trim() }
    }

    suspend fun saveSpotifyClientId(value: String) {
        context.dataStore.edit { it[Keys.SPOTIFY_CLIENT_ID] = value.trim() }
    }

    suspend fun savePkceVerifier(value: String) {
        context.credentialStore.edit { it[Keys.PKCE_VERIFIER] = value }
    }

    suspend fun pkceVerifier(): String? {
        migrateCredentials()
        return context.credentialStore.data.map { it[Keys.PKCE_VERIFIER] }.first()
    }

    suspend fun saveTokens(
        accessToken: String,
        refreshToken: String?,
        expiresInSeconds: Long,
        scope: String? = null,
    ) {
        context.credentialStore.edit { prefs ->
            prefs[Keys.SPOTIFY_ACCESS_TOKEN] = accessToken
            // Refresh one minute early to avoid using a token that expires mid-request.
            prefs[Keys.SPOTIFY_TOKEN_EXPIRY] =
                System.currentTimeMillis() + (expiresInSeconds - 60) * 1000
            if (refreshToken != null) prefs[Keys.SPOTIFY_REFRESH_TOKEN] = refreshToken
            if (!scope.isNullOrBlank()) prefs[Keys.SPOTIFY_SCOPE] = scope
        }
    }

    /**
     * Stores a refresh token received via a device handover (#143) — no access token,
     * because the sending device's [AccountsPayload] never carries one (it is short-lived
     * and pointless to move). [validAccessToken] correctly reports "needs a refresh" until
     * [SpotifyClient][io.github.magnusencoded.stationtostation.data.spotify.SpotifyClient]
     * exchanges this refresh token for one on first use, the same lazy path an expired
     * token already takes.
     */
    suspend fun saveHandoverCredentials(refreshToken: String, scope: String?) {
        context.credentialStore.edit { prefs ->
            prefs[Keys.SPOTIFY_REFRESH_TOKEN] = refreshToken
            if (!scope.isNullOrBlank()) prefs[Keys.SPOTIFY_SCOPE] = scope
        }
    }

    suspend fun grantedScope(): String? {
        migrateCredentials()
        return context.credentialStore.data.map { it[Keys.SPOTIFY_SCOPE]?.ifBlank { null } }.first()
    }

    suspend fun validAccessToken(): String? {
        migrateCredentials()
        val prefs = context.credentialStore.data.first()
        val token = prefs[Keys.SPOTIFY_ACCESS_TOKEN] ?: return null
        val expiry = prefs[Keys.SPOTIFY_TOKEN_EXPIRY] ?: 0L
        return if (System.currentTimeMillis() < expiry) token else null
    }

    suspend fun refreshTokenValue(): String? {
        migrateCredentials()
        return spotifyRefreshToken.first()
    }

    /**
     * Clears both stores: an install that predates the split may still hold a copy in
     * `settings` if it has not been read since, and "log out" has to mean both.
     */
    suspend fun clearSpotifyAuth() {
        context.credentialStore.edit { prefs -> CREDENTIAL_KEYS.forEach { prefs.remove(it) } }
        context.dataStore.edit { prefs -> CREDENTIAL_KEYS.forEach { prefs.remove(it) } }
    }

    private companion object {
        /** Everything the credential store owns — the one list migration and logout share. */
        val CREDENTIAL_KEYS: List<Preferences.Key<*>> = listOf(
            Keys.SPOTIFY_ACCESS_TOKEN,
            Keys.SPOTIFY_REFRESH_TOKEN,
            Keys.SPOTIFY_TOKEN_EXPIRY,
            Keys.SPOTIFY_SCOPE,
            Keys.PKCE_VERIFIER,
        )
    }
}

/**
 * What a bundled credential looks like in a field the user has not filled in.
 *
 * The last four characters, because the only question the hint has to answer is
 * "is something already in use here, and is it the one I think it is" — enough to
 * tell two keys apart, not enough to be one. A short value shows nothing at all
 * rather than most of itself.
 */
fun maskedHint(value: String): String = when {
    value.isBlank() -> ""
    value.length <= 8 -> "*".repeat(value.length)
    else -> "*".repeat(7) + value.takeLast(4)
}
