package io.github.magnusencoded.stationtostation.features.settings

import android.net.Uri
import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.data.clashfinder.ClashfinderAuth
import io.github.magnusencoded.stationtostation.features.FakeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsControllerTest {

    private class Store : SettingsStore {
        var apiKey: String? = null
        var clientId: String? = null
        var sharedQuotaSpent = false
        var clashfinder: ClashfinderAuth? = null
        var scope: String? = null
        var authCleared = false

        override suspend fun saveSetlistFmApiKey(value: String) { apiKey = value.trim().ifEmpty { null } }
        override suspend fun saveSpotifyClientId(value: String) { clientId = value.trim().ifEmpty { null } }
        override suspend fun spotifyClientIdValue() = clientId
        override suspend fun setlistFmApiKeyValue() = apiKey
        override suspend fun sharedQuotaSpentNow() = sharedQuotaSpent
        override suspend fun saveClashfinderCredentials(user: String, privateKey: String) {
            clashfinder = if (user.isBlank() || privateKey.isBlank()) null
            else ClashfinderAuth(user.trim(), privateKey.trim())
        }
        override suspend fun clashfinderAuth() = clashfinder
        override suspend fun grantedScope() = scope
        override suspend fun clearSpotifyAuth() { authCleared = true }
    }

    private class Login : SpotifyLogin {
        override suspend fun buildAuthorizationUri(): Uri = error("not used")
        override suspend fun exchangeCodeForTokens(code: String) = Unit
    }

    private val store = Store()
    private val fake = FakeState()

    private fun controller() = SettingsController(
        update = fake.update,
        settings = store,
        spotify = Login(),
        scope = CoroutineScope(Dispatchers.Unconfined),
        fail = {},
    )

    @Test
    fun saved_settings_are_trimmed_into_state_and_mark_each_service_ready() = runBlocking {
        controller().saveSettingsNow("  key  ", " client ")
        assertEquals("key", fake.current.setlistFmApiKey)
        assertEquals("client", fake.current.spotifyClientId)
        assertTrue(fake.current.spotifyLoginReady)
        assertTrue(fake.current.setlistFmReady)
    }

    @Test
    fun blank_settings_leave_both_services_not_ready() = runBlocking {
        controller().saveSettingsNow("", " ")
        assertFalse(fake.current.spotifyLoginReady)
        assertFalse(fake.current.setlistFmReady)
    }

    @Test
    fun saving_settings_re_reads_the_shared_quota_flag() = runBlocking {
        store.sharedQuotaSpent = true
        controller().saveSettingsNow("key", "client")
        assertTrue(fake.current.setlistFmSharedQuotaSpent)
    }

    @Test
    fun clashfinder_credentials_are_trimmed_and_ready_only_when_both_halves_are_present() {
        controller().saveClashfinderCredentials(" me ", " secret ")
        assertEquals("me", fake.current.clashfinderUser)
        assertEquals("secret", fake.current.clashfinderPrivateKey)
        assertTrue(fake.current.clashfinderReady)

        controller().saveClashfinderCredentials("me", "")
        assertFalse(fake.current.clashfinderReady)
    }

    @Test
    fun disconnecting_spotify_clears_the_login_and_the_granted_scope() {
        val c = controller()
        fake.update { it.copy(spotifyConnected = true, grantedScope = "playlist-modify-private") }
        c.disconnectSpotify()
        assertTrue(store.authCleared)
        assertFalse(fake.current.spotifyConnected)
        assertNull(fake.current.grantedScope)
    }

    @Test
    fun state_not_touched_by_settings_is_left_alone() = runBlocking {
        fake.update { it.copy(error = "boom") }
        controller().saveSettingsNow("key", "client")
        assertEquals(UiState().copy(error = "boom").error, fake.current.error)
    }
}
