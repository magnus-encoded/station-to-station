package io.github.magnusencoded.stationtostation.features.settings

import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The shape every feature controller takes: shared [UiState] reached through [state] and
 * [update], persistence through its `data/` dependencies, async work on [scope].
 */
class SettingsController(
    @Suppress("unused") private val state: () -> UiState,
    private val update: ((UiState) -> UiState) -> Unit,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    /** Records that the splash was passed, so it never shows again. */
    fun markOnboarded() {
        update { it.copy(onboarded = true) }
        scope.launch { settings.setOnboarded() }
    }
}
package io.github.magnusencoded.stationtostation.features.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.data.SettingsRepository
import io.github.magnusencoded.stationtostation.data.clashfinder.ClashfinderAuth
import io.github.magnusencoded.stationtostation.data.clashfinder.clashfinderUrl
import io.github.magnusencoded.stationtostation.data.spotify.SpotifyClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The slice of [SettingsRepository] the settings gestures touch. */
interface SettingsStore {
    suspend fun setOnboarded()
    suspend fun saveSetlistFmApiKey(value: String)
    suspend fun saveSpotifyClientId(value: String)
    suspend fun spotifyClientIdValue(): String?
    suspend fun setlistFmApiKeyValue(): String?
    suspend fun sharedQuotaSpentNow(): Boolean
    suspend fun saveClashfinderCredentials(user: String, privateKey: String)
    suspend fun clashfinderAuth(): ClashfinderAuth?
    suspend fun grantedScope(): String?
    suspend fun clearSpotifyAuth()
}

/** The slice of [SpotifyClient] the login round trip touches. */
interface SpotifyLogin {
    suspend fun buildAuthorizationUri(): Uri
    suspend fun exchangeCodeForTokens(code: String)
}

fun SettingsRepository.asSettingsStore(): SettingsStore {
    val repo = this
    return object : SettingsStore {
        override suspend fun setOnboarded() = repo.setOnboarded()
        override suspend fun saveSetlistFmApiKey(value: String) = repo.saveSetlistFmApiKey(value)
        override suspend fun saveSpotifyClientId(value: String) = repo.saveSpotifyClientId(value)
        override suspend fun spotifyClientIdValue() = repo.spotifyClientIdValue()
        override suspend fun setlistFmApiKeyValue() = repo.setlistFmApiKeyValue()
        override suspend fun sharedQuotaSpentNow() = repo.sharedQuotaSpentNow()
        override suspend fun saveClashfinderCredentials(user: String, privateKey: String) =
            repo.saveClashfinderCredentials(user, privateKey)
        override suspend fun clashfinderAuth() = repo.clashfinderAuth()
        override suspend fun grantedScope() = repo.grantedScope()
        override suspend fun clearSpotifyAuth() = repo.clearSpotifyAuth()
    }
}

fun SpotifyClient.asSpotifyLogin(): SpotifyLogin {
    val client = this
    return object : SpotifyLogin {
        override suspend fun buildAuthorizationUri() = client.buildAuthorizationUri()
        override suspend fun exchangeCodeForTokens(code: String) = client.exchangeCodeForTokens(code)
    }
}

class SettingsController(
    private val update: ((UiState) -> UiState) -> Unit,
    private val settings: SettingsStore,
    private val spotify: SpotifyLogin,
    private val scope: CoroutineScope,
    private val fail: (Exception) -> Unit,
) {

    /** Records that the splash was passed, so it never shows again. */
    fun markOnboarded() {
        update { it.copy(onboarded = true) }
        scope.launch { settings.setOnboarded() }
    }

    fun saveSettings(apiKey: String, clientId: String) {
        scope.launch { saveSettingsNow(apiKey, clientId) }
    }

    suspend fun saveSettingsNow(apiKey: String, clientId: String) {
        settings.saveSetlistFmApiKey(apiKey)
        settings.saveSpotifyClientId(clientId)
        val loginReady = settings.spotifyClientIdValue() != null
        val setlistFmReady = settings.setlistFmApiKeyValue() != null
        val quotaSpent = settings.sharedQuotaSpentNow()
        update {
            it.copy(
                setlistFmApiKey = apiKey.trim(),
                spotifyClientId = clientId.trim(),
                spotifyLoginReady = loginReady,
                setlistFmReady = setlistFmReady,
                setlistFmSharedQuotaSpent = quotaSpent,
            )
        }
    }

    /**
     * The clashfinder account. Saved as its own gesture rather than folded into
     * [saveSettings], because it is two fields that only mean anything together.
     */
    fun saveClashfinderCredentials(user: String, privateKey: String) {
        scope.launch {
            settings.saveClashfinderCredentials(user, privateKey)
            val ready = settings.clashfinderAuth() != null
            update {
                it.copy(
                    clashfinderUser = user.trim(),
                    clashfinderPrivateKey = privateKey.trim(),
                    clashfinderReady = ready,
                )
            }
        }
    }

    suspend fun buildSpotifyAuthUri(): Uri = spotify.buildAuthorizationUri()

    fun handleAuthRedirect(uri: Uri) {
        val code = uri.getQueryParameter("code")
        val authError = uri.getQueryParameter("error")
        scope.launch {
            try {
                when {
                    code != null -> {
                        spotify.exchangeCodeForTokens(code)
                        val granted = settings.grantedScope()
                        update { it.copy(spotifyConnected = true, grantedScope = granted) }
                    }
                    authError != null ->
                        update { it.copy(errorKind = null, error = "Spotify login failed: $authError") }
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun disconnectSpotify() {
        scope.launch {
            settings.clearSpotifyAuth()
            update { it.copy(spotifyConnected = false, grantedScope = null) }
        }
    }

    /**
     * Hand one clashfinder document to the browser, which the host does still answer.
     *
     * The address carries the account's credentials because the data needs them, so this
     * puts the public key in the browser's history — accepted only because it is the one
     * route to the file while the app itself is refused, and it is the user's own key on
     * their own phone.
     */
    suspend fun openClashfinderInBrowser(context: Context, path: String) {
        val auth = settings.clashfinderAuth() ?: return
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(clashfinderUrl(path, auth)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
