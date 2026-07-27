package io.github.magnusencoded.setlist2spotify.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.magnusencoded.setlist2spotify.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

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
    val spotifyRefreshToken: Flow<String?> =
        context.dataStore.data.map { it[Keys.SPOTIFY_REFRESH_TOKEN]?.ifBlank { null } }

    // User-entered values take precedence; otherwise fall back to credentials
    // bundled at build time (see app/build.gradle.kts).
    suspend fun setlistFmApiKeyValue(): String? =
        setlistFmApiKey.first() ?: BuildConfig.SETLISTFM_API_KEY.ifBlank { null }

    suspend fun spotifyClientIdValue(): String? =
        spotifyClientId.first() ?: BuildConfig.SPOTIFY_CLIENT_ID.ifBlank { null }

    fun hasBundledSetlistFmKey(): Boolean = BuildConfig.SETLISTFM_API_KEY.isNotBlank()
    fun hasBundledSpotifyClientId(): Boolean = BuildConfig.SPOTIFY_CLIENT_ID.isNotBlank()

    suspend fun saveSetlistFmApiKey(value: String) {
        context.dataStore.edit { it[Keys.SETLISTFM_API_KEY] = value.trim() }
    }

    suspend fun saveSpotifyClientId(value: String) {
        context.dataStore.edit { it[Keys.SPOTIFY_CLIENT_ID] = value.trim() }
    }

    suspend fun savePkceVerifier(value: String) {
        context.dataStore.edit { it[Keys.PKCE_VERIFIER] = value }
    }

    suspend fun pkceVerifier(): String? =
        context.dataStore.data.map { it[Keys.PKCE_VERIFIER] }.first()

    suspend fun saveTokens(
        accessToken: String,
        refreshToken: String?,
        expiresInSeconds: Long,
        scope: String? = null,
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SPOTIFY_ACCESS_TOKEN] = accessToken
            // Refresh one minute early to avoid using a token that expires mid-request.
            prefs[Keys.SPOTIFY_TOKEN_EXPIRY] =
                System.currentTimeMillis() + (expiresInSeconds - 60) * 1000
            if (refreshToken != null) prefs[Keys.SPOTIFY_REFRESH_TOKEN] = refreshToken
            if (!scope.isNullOrBlank()) prefs[Keys.SPOTIFY_SCOPE] = scope
        }
    }

    suspend fun grantedScope(): String? =
        context.dataStore.data.map { it[Keys.SPOTIFY_SCOPE]?.ifBlank { null } }.first()

    suspend fun validAccessToken(): String? {
        val prefs = context.dataStore.data.first()
        val token = prefs[Keys.SPOTIFY_ACCESS_TOKEN] ?: return null
        val expiry = prefs[Keys.SPOTIFY_TOKEN_EXPIRY] ?: 0L
        return if (System.currentTimeMillis() < expiry) token else null
    }

    suspend fun refreshTokenValue(): String? = spotifyRefreshToken.first()

    suspend fun clearSpotifyAuth() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.SPOTIFY_ACCESS_TOKEN)
            prefs.remove(Keys.SPOTIFY_REFRESH_TOKEN)
            prefs.remove(Keys.SPOTIFY_TOKEN_EXPIRY)
            prefs.remove(Keys.SPOTIFY_SCOPE)
            prefs.remove(Keys.PKCE_VERIFIER)
        }
    }
}
