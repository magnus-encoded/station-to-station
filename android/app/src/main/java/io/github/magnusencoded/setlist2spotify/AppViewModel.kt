package io.github.magnusencoded.setlist2spotify

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.magnusencoded.setlist2spotify.data.SettingsRepository
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmArtist
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSetlist
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSong
import io.github.magnusencoded.setlist2spotify.data.setlistfm.SetlistFmClient
import io.github.magnusencoded.setlist2spotify.data.spotify.SpotifyClient
import io.github.magnusencoded.setlist2spotify.data.spotify.SpotifyTrack
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One setlist song together with its Spotify match candidates and selection. */
data class SongMatch(
    val song: FmSong,
    val searchArtist: String,
    val included: Boolean = true,
    val loading: Boolean = true,
    val candidates: List<SpotifyTrack> = emptyList(),
    val selected: SpotifyTrack? = null,
    val error: String? = null,
) {
    val isCover: Boolean get() = song.cover != null
}

enum class SetlistSource { ARTIST, USER }

data class UiState(
    // Settings
    val setlistFmApiKey: String = "",
    val spotifyClientId: String = "",
    val spotifyConnected: Boolean = false,
    /** A Spotify client ID is available (bundled at build time or user-entered). */
    val spotifyLoginReady: Boolean = false,
    /** A setlist.fm API key is available (bundled at build time or user-entered). */
    val setlistFmReady: Boolean = false,
    val bundledSpotifyClientId: Boolean = false,
    val bundledSetlistFmKey: Boolean = false,
    // Search
    val artistQuery: String = "",
    val userQuery: String = "",
    val artistResults: List<FmArtist> = emptyList(),
    val searchLoading: Boolean = false,
    // Setlists
    val source: SetlistSource = SetlistSource.ARTIST,
    val setlistsTitle: String = "",
    val setlists: List<FmSetlist> = emptyList(),
    val setlistsPage: Int = 1,
    val setlistsTotal: Int = 0,
    val setlistsLoading: Boolean = false,
    // Selected setlist + matching
    val selectedSetlist: FmSetlist? = null,
    val matches: List<SongMatch> = emptyList(),
    val matching: Boolean = false,
    val playlistName: String = "",
    // Playlist creation
    val creatingPlaylist: Boolean = false,
    val createdPlaylistUrl: String? = null,
    val createdPlaylistName: String = "",
    val createdTrackCount: Int = 0,
    // Transient error surfaced as a snackbar
    val error: String? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    val settings = SettingsRepository(application)
    private val setlistFm = SetlistFmClient { settings.setlistFmApiKeyValue() }
    val spotify = SpotifyClient(settings)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var matchJob: Job? = null

    init {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    setlistFmApiKey = settings.setlistFmApiKey.first() ?: "",
                    spotifyClientId = settings.spotifyClientId.first() ?: "",
                    spotifyConnected = spotify.isConnected(),
                    spotifyLoginReady = settings.spotifyClientIdValue() != null,
                    setlistFmReady = settings.setlistFmApiKeyValue() != null,
                    bundledSpotifyClientId = settings.hasBundledSpotifyClientId(),
                    bundledSetlistFmKey = settings.hasBundledSetlistFmKey(),
                )
            }
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }

    private fun fail(e: Exception) = _state.update {
        it.copy(
            error = e.message ?: "Something went wrong",
            searchLoading = false,
            setlistsLoading = false,
            creatingPlaylist = false,
        )
    }

    // --- Settings ---

    fun saveSettings(apiKey: String, clientId: String) {
        viewModelScope.launch { saveSettingsNow(apiKey, clientId) }
    }

    suspend fun saveSettingsNow(apiKey: String, clientId: String) {
        settings.saveSetlistFmApiKey(apiKey)
        settings.saveSpotifyClientId(clientId)
        _state.update {
            it.copy(
                setlistFmApiKey = apiKey.trim(),
                spotifyClientId = clientId.trim(),
                spotifyLoginReady = settings.spotifyClientIdValue() != null,
                setlistFmReady = settings.setlistFmApiKeyValue() != null,
            )
        }
    }

    suspend fun buildSpotifyAuthUri(): Uri = spotify.buildAuthorizationUri()

    fun handleAuthRedirect(uri: Uri) {
        val code = uri.getQueryParameter("code")
        val authError = uri.getQueryParameter("error")
        viewModelScope.launch {
            try {
                when {
                    code != null -> {
                        spotify.exchangeCodeForTokens(code)
                        _state.update { it.copy(spotifyConnected = true) }
                    }
                    authError != null ->
                        _state.update { it.copy(error = "Spotify login failed: $authError") }
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun disconnectSpotify() {
        viewModelScope.launch {
            settings.clearSpotifyAuth()
            _state.update { it.copy(spotifyConnected = false) }
        }
    }

    // --- Search ---

    fun setArtistQuery(q: String) = _state.update { it.copy(artistQuery = q) }
    fun setUserQuery(q: String) = _state.update { it.copy(userQuery = q) }

    fun searchArtists() {
        val query = _state.value.artistQuery.trim()
        if (query.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(searchLoading = true) }
            try {
                val result = setlistFm.searchArtists(query)
                _state.update { it.copy(artistResults = result.artist, searchLoading = false) }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** Loads setlists for an artist. Returns immediately; UI navigates and observes state. */
    fun openArtist(artist: FmArtist) {
        _state.update {
            it.copy(
                source = SetlistSource.ARTIST,
                setlistsTitle = artist.name,
                setlists = emptyList(),
                setlistsPage = 1,
                setlistsTotal = 0,
                setlistsLoading = true,
            )
        }
        viewModelScope.launch {
            try {
                val result = setlistFm.artistSetlists(artist.mbid)
                _state.update {
                    it.copy(setlists = result.setlist, setlistsTotal = result.total, setlistsLoading = false)
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun openUserAttended() {
        val userId = _state.value.userQuery.trim()
        if (userId.isEmpty()) return
        _state.update {
            it.copy(
                source = SetlistSource.USER,
                setlistsTitle = "Attended by $userId",
                setlists = emptyList(),
                setlistsPage = 1,
                setlistsTotal = 0,
                setlistsLoading = true,
            )
        }
        viewModelScope.launch {
            try {
                val result = setlistFm.userAttended(userId)
                _state.update {
                    it.copy(setlists = result.setlist, setlistsTotal = result.total, setlistsLoading = false)
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun loadMoreSetlists() {
        val s = _state.value
        if (s.setlistsLoading || s.setlists.size >= s.setlistsTotal) return
        val nextPage = s.setlistsPage + 1
        _state.update { it.copy(setlistsLoading = true) }
        viewModelScope.launch {
            try {
                val result = when (s.source) {
                    SetlistSource.USER -> setlistFm.userAttended(s.userQuery.trim(), nextPage)
                    SetlistSource.ARTIST -> {
                        val mbid = s.setlists.firstOrNull()?.artist?.mbid
                            ?: throw IllegalStateException("No artist context")
                        setlistFm.artistSetlists(mbid, nextPage)
                    }
                }
                _state.update {
                    it.copy(
                        setlists = it.setlists + result.setlist,
                        setlistsPage = nextPage,
                        setlistsTotal = result.total,
                        setlistsLoading = false,
                    )
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    // --- Matching ---

    fun selectSetlist(setlist: FmSetlist) {
        matchJob?.cancel()
        val artistName = setlist.artist?.name ?: ""
        val matches = setlist.songs()
            .filter { it.name.isNotBlank() }
            .map { song ->
                SongMatch(
                    song = song,
                    searchArtist = song.cover?.name ?: artistName,
                    // Tape songs are intro/outro recordings, not performed live; excluded by default.
                    included = !song.tape,
                )
            }
        val defaultName = buildString {
            append(artistName.ifBlank { "Setlist" })
            setlist.venue?.name?.let { append(" – ").append(it) }
            setlist.eventDate?.let { append(" – ").append(it) }
        }
        _state.update {
            it.copy(
                selectedSetlist = setlist,
                matches = matches,
                matching = true,
                playlistName = defaultName,
                createdPlaylistUrl = null,
            )
        }
        matchJob = viewModelScope.launch {
            matches.forEachIndexed { index, match ->
                val (candidates, error) = findCandidates(match.song.name, match.searchArtist)
                updateMatch(index) {
                    it.copy(
                        loading = false,
                        candidates = candidates,
                        selected = candidates.firstOrNull(),
                        included = it.included && candidates.isNotEmpty(),
                        error = error,
                    )
                }
                // Stay polite with the Spotify search API.
                delay(120)
            }
            _state.update { it.copy(matching = false) }
        }
    }

    private suspend fun findCandidates(track: String, artist: String): Pair<List<SpotifyTrack>, String?> {
        return try {
            var results = spotify.searchTracks("track:\"$track\" artist:\"$artist\"")
            if (results.isEmpty()) {
                results = spotify.searchTracks("$track $artist")
            }
            results to null
        } catch (e: Exception) {
            emptyList<SpotifyTrack>() to (e.message ?: "Search failed")
        }
    }

    private fun updateMatch(index: Int, transform: (SongMatch) -> SongMatch) {
        _state.update { s ->
            if (index !in s.matches.indices) s
            else s.copy(matches = s.matches.mapIndexed { i, m -> if (i == index) transform(m) else m })
        }
    }

    fun toggleIncluded(index: Int) = updateMatch(index) { it.copy(included = !it.included) }

    fun chooseCandidate(index: Int, track: SpotifyTrack) =
        updateMatch(index) { it.copy(selected = track, included = true) }

    fun setPlaylistName(name: String) = _state.update { it.copy(playlistName = name) }

    /** Manual re-search for one song with a user-provided query. */
    fun researchSong(index: Int, query: String) {
        if (query.isBlank()) return
        updateMatch(index) { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val results = spotify.searchTracks(query.trim(), limit = 10)
                updateMatch(index) {
                    it.copy(
                        loading = false,
                        candidates = results,
                        selected = results.firstOrNull() ?: it.selected,
                        error = if (results.isEmpty()) "No results for \"$query\"" else null,
                    )
                }
            } catch (e: Exception) {
                updateMatch(index) { it.copy(loading = false, error = e.message ?: "Search failed") }
            }
        }
    }

    // --- Playlist creation ---

    fun createPlaylist() {
        val s = _state.value
        val tracks = s.matches.filter { it.included && it.selected != null }.mapNotNull { it.selected }
        if (tracks.isEmpty()) {
            _state.update { it.copy(error = "No songs selected") }
            return
        }
        val name = s.playlistName.ifBlank { "Setlist" }
        _state.update { it.copy(creatingPlaylist = true) }
        viewModelScope.launch {
            try {
                val description = buildString {
                    append("Setlist")
                    s.selectedSetlist?.venueLine()?.let { append(" at ").append(it) }
                    s.selectedSetlist?.eventDate?.let { append(" on ").append(it) }
                    append(". Created from setlist.fm")
                    s.selectedSetlist?.url?.let { append(": ").append(it) }
                }
                val playlist = spotify.createPlaylist(name, description)
                spotify.addTracks(playlist.id, tracks.map { it.uri })
                _state.update {
                    it.copy(
                        creatingPlaylist = false,
                        createdPlaylistUrl = playlist.externalUrls["spotify"],
                        createdPlaylistName = name,
                        createdTrackCount = tracks.size,
                    )
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }
}
