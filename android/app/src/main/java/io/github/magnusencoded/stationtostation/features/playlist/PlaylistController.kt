package io.github.magnusencoded.stationtostation.features.playlist

import android.net.Uri
import io.github.magnusencoded.stationtostation.CoverCandidate
import io.github.magnusencoded.stationtostation.SongMatch
import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.StoredPlaylist
import io.github.magnusencoded.stationtostation.data.TimelineLogic
import io.github.magnusencoded.stationtostation.data.TimelineStore
import io.github.magnusencoded.stationtostation.data.photos.PhotoRepository
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSong
import io.github.magnusencoded.stationtostation.data.sfmStamp
import io.github.magnusencoded.stationtostation.data.sfmUserFromDescription
import io.github.magnusencoded.stationtostation.data.spotifyPlaylistId
import io.github.magnusencoded.stationtostation.data.spotify.SpotifyClient
import io.github.magnusencoded.stationtostation.data.spotify.SpotifyTrack
import io.github.magnusencoded.stationtostation.data.spotify.rankCandidates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Song matching, playlist creation and the playlist cover. */
class PlaylistController(
    private val state: () -> UiState,
    private val update: ((UiState) -> UiState) -> Unit,
    private val spotify: SpotifyClient,
    private val photos: PhotoRepository,
    private val timelines: TimelineStore,
    private val scope: CoroutineScope,
    private val fail: (Exception) -> Unit,
    private val addFriend: (Friend) -> Unit,
) {

    private var matchJob: Job? = null

    fun selectSetlist(setlist: FmSetlist) {
        matchJob?.cancel()
        val artistName = setlist.artist?.name ?: ""
        // A closed **Log** converts like a setlist, even before setlist.fm has the night.
        // An open one does not: the band may still be playing.
        // setlist.fm wins where it has songs: it carries covers and tape markers.
        val songs = setlist.songs().filter { it.name.isNotBlank() }.ifEmpty {
            state().logsByGig[setlist.id]
                ?.takeIf { it.closed }
                ?.named()
                ?.map { FmSong(name = it) }
                .orEmpty()
        }
        val matches = songs
            .map { song ->
                SongMatch(
                    song = song,
                    searchArtist = song.cover?.name ?: artistName,
                    // Tape songs are intro/outro recordings, not performed live; excluded by default.
                    included = !song.tape,
                )
            }
        // Year – Artist – Where; the rule is the logic layer's.
        val defaultName = TimelineLogic.playlistName(
            setlist, state().setlists, state().festivals,
        )
        update {
            it.copy(
                selectedSetlist = setlist,
                matches = matches,
                matching = true,
                playlistName = defaultName,
                createdPlaylistUrl = null,
                // A different show means different photos.
                coverCandidates = emptyList(),
                selectedCoverUri = null,
                coverSearched = false,
                coverUploadError = null,
            )
        }
        loadCoverCandidates()
        matchJob = scope.launch {
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
            update { it.copy(matching = false) }
        }
    }

    private suspend fun findCandidates(track: String, artist: String): Pair<List<SpotifyTrack>, String?> {
        return try {
            // Ten rather than the default five: ranking can only choose from what it
            // is handed, and the studio cut often sits under a run of live versions.
            // Same number of requests either way.
            var results = spotify.searchTracks("track:\"$track\" artist:\"$artist\"", limit = 10)
            if (results.isEmpty()) {
                results = spotify.searchTracks("$track $artist", limit = 10)
            }
            // Best-first rather than Spotify-first: the auto-selection above takes the
            // head of this list, and the picker lists them in this order too.
            rankCandidates(results, track, artist) to null
        } catch (e: Exception) {
            emptyList<SpotifyTrack>() to (e.message ?: "Search failed")
        }
    }

    private fun updateMatch(index: Int, transform: (SongMatch) -> SongMatch) {
        update { s ->
            if (index !in s.matches.indices) s
            else s.copy(matches = s.matches.mapIndexed { i, m -> if (i == index) transform(m) else m })
        }
    }

    fun toggleIncluded(index: Int) = updateMatch(index) { it.copy(included = !it.included) }

    fun chooseCandidate(index: Int, track: SpotifyTrack) =
        updateMatch(index) { it.copy(selected = track, included = true) }

    fun setPlaylistName(name: String) = update { it.copy(playlistName = name) }
    fun setPlaylistPublic(public: Boolean) = update { it.copy(playlistPublic = public) }

    /**
     * Discovers a friend from a Spotify playlist link they shared: reads the playlist's
     * description, and if it carries a setlist.fm stamp, adds the owner as a friend.
     */
    fun discoverFriendFromPlaylist(link: String) {
        val id = spotifyPlaylistId(link)
        if (id == null) {
            update { it.copy(errorKind = null, error = "That doesn't look like a Spotify playlist link.") }
            return
        }
        scope.launch {
            try {
                val playlist = spotify.getPlaylist(id)
                val username = sfmUserFromDescription(playlist.description)
                val ownerId = playlist.owner?.id
                val me = runCatching { spotify.currentUser().id }.getOrNull()
                when {
                    username == null -> update {
                        it.copy(
                            error = "That playlist wasn't made with this app, so there's no setlist.fm user to add.",
                            errorKind = null,
                        )
                    }
                    ownerId != null && ownerId == me -> update {
                        it.copy(notice = "That's your own playlist.")
                    }
                    else -> {
                        addFriend(
                            Friend(
                                setlistfm = username,
                                name = playlist.owner?.displayName?.ifBlank { null } ?: username,
                                spotifyId = ownerId,
                            )
                        )
                        update { it.copy(notice = "Added @$username as a friend.") }
                    }
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /**
     * Offers the gig's own keepsakes first — already chosen for this night, so
     * they need no permission and no re-asking — then the gallery's same-night
     * match once that permission is granted. The gallery half is silent when
     * missing: the confirm screen asks for it instead, so a prompt only ever
     * follows a tap.
     */
    fun loadCoverCandidates() {
        val setlist = state().selectedSetlist ?: return
        val date = setlist.localDate() ?: return
        val granted = photos.hasPermission()
        update { it.copy(coverPermissionGranted = granted) }
        scope.launch {
            update { it.copy(coverLoading = true) }
            val pinned = state().mediaBySetlist[setlist.id].orEmpty().map { Uri.parse(it.ref) }
            val gallery = if (granted) photos.photosFrom(date).map { it.uri } else emptyList()
            val candidates = (pinned + gallery).distinct().map { CoverCandidate(it, photos.preview(it)) }
            update {
                it.copy(
                    coverCandidates = candidates,
                    coverLoading = false,
                    coverSearched = true,
                    // The first photo is the suggestion, so it is the cover
                    // until the picker is swiped somewhere else.
                    selectedCoverUri = candidates.firstOrNull()?.uri,
                )
            }
        }
    }

    /**
     * The cover the picker has landed on, or null for Spotify's own collage.
     * Called on every settled swipe, so an unchanged value is left alone rather
     * than published as new state.
     */
    fun setCover(uri: Uri?) = update {
        // A different cover means the frame scrubbed out of the last one is moot.
        if (it.selectedCoverUri == uri) it
        else it.copy(selectedCoverUri = uri, selectedCoverFrameMs = 0L)
    }

    /** Where the scrubber landed on the chosen clip — the frame that becomes the cover. */
    fun setCoverFrame(atMs: Long) = update {
        if (it.selectedCoverFrameMs == atMs) it else it.copy(selectedCoverFrameMs = atMs)
    }

    /** Drops a playlist link the app made — for when the playlist itself was deleted
     *  on Spotify, so the pointer to it here is now just dead weight. */
    fun removePlaylist(setlistId: String, url: String) {
        update {
            it.copy(
                playlistsBySetlist = it.playlistsBySetlist +
                    (setlistId to it.playlistsBySetlist[setlistId].orEmpty().filterNot { p -> p.url == url }),
            )
        }
        scope.launch { timelines.removePlaylist(setlistId, url) }
    }

    /** Manual re-search for one song with a user-provided query. */
    fun researchSong(index: Int, query: String) {
        if (query.isBlank()) return
        updateMatch(index) { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                val found = spotify.searchTracks(query.trim(), limit = 10)
                updateMatch(index) {
                    // Ranked like the automatic search, or searching by hand would be
                    // the one path that still hands you Spotify's karaoke rendition.
                    // The query is the user's, but which recording we mean is still
                    // this song by this artist.
                    val results = rankCandidates(found, it.song.name, it.searchArtist)
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


    /** The Tour uses the normal matcher and playlist writer with character-owned metadata. */
    fun exportTour(gig: FmSetlist, title: String, description: String, completed: () -> Unit) {
        selectSetlist(gig)
        val matching = matchJob
        scope.launch {
            matching?.join()
            createPlaylist(title to description, completed)
        }
    }

    fun createPlaylist() = createPlaylist(null) {}

    private fun createPlaylist(metadata: Pair<String, String>?, completed: () -> Unit) {
        val s = state()
        val tracks = s.matches.filter { it.included && it.selected != null }.mapNotNull { it.selected }
        if (tracks.isEmpty()) {
            update { it.copy(errorKind = null, error = "No songs selected") }
            return
        }
        val name = metadata?.first ?: s.playlistName.ifBlank { "Setlist" }
        update { it.copy(creatingPlaylist = true) }
        scope.launch {
            try {
                // Unknown scope means the login predates scope tracking — the
                // remedy is the same as a missing scope: a fresh login.
                if (spotify.hasPlaylistScopes() != true) {
                    throw IllegalStateException(
                        "Your Spotify login is missing playlist permissions. " +
                            "Log out in Settings, then log in again and approve " +
                            "the playlist access on the Spotify page that opens."
                    )
                }
                val setlist = s.selectedSetlist
                // Stamp the creator so a friend's app can discover the mapping from a shared
                // link. Appended after the 300-char clamp so truncation can't cut it off.
                val stamp = s.mySetlistFmUser.trim().takeIf { it.isNotEmpty() }
                    ?.let { " " + sfmStamp(it) } ?: ""
                val description = metadata?.second ?: (buildString {
                    append("Live at ").append(setlist?.venueLine() ?: "an unknown venue")
                    // The name carries only the year, so the full date lives here.
                    setlist?.readableDate()?.let { append(", ").append(it) }
                    append(".")
                    setlist?.tour?.name?.let { append(" ").append(it).append(".") }
                    append(" From setlist.fm")
                    setlist?.url?.let { append(": ").append(it) }
                }.take(300 - stamp.length) + stamp)
                val playlist = spotify.createPlaylist(name, description, s.playlistPublic)
                val result = try {
                    spotify.addTracks(playlist.id, tracks.map { it.uri })
                } catch (e: Exception) {
                    // The playlist exists at this point, so say so rather than
                    // leaving the user with a bare failure and a stray playlist.
                    throw IllegalStateException(
                        "Playlist \"$name\" was created but the songs could not be added. " +
                            "${e.message}",
                        e,
                    )
                }
                // The songs are the point, so a cover that will not upload is
                // reported next to the success rather than thrown over it.
                val coverError = s.selectedCoverUri?.let {
                    uploadCover(playlist.id, it, s.selectedCoverFrameMs)
                }
                // Fall back to the canonical URL rather than dropping the link:
                // externalUrls is Spotify's to omit, the id is ours to keep.
                val url = playlist.externalUrls["spotify"]
                    ?: "https://open.spotify.com/playlist/${playlist.id}"
                val made = StoredPlaylist(url = url, name = name, trackCount = result.added)
                val night = setlist?.id?.takeIf { it.isNotBlank() }
                update {
                    it.copy(
                        creatingPlaylist = false,
                        createdPlaylistUrl = url,
                        createdPlaylistName = name,
                        createdTrackCount = result.added,
                        createdRefusedCount = result.refused.size,
                        coverUploadError = coverError,
                        // Appended: converting this night again must not orphan a
                        // link already sent to someone.
                        playlistsBySetlist =
                            if (night == null) it.playlistsBySetlist
                            else it.playlistsBySetlist +
                                (night to (it.playlistsBySetlist[night].orEmpty() + made)),
                    )
                }
                // So the night still points at it on the next launch.
                if (night != null) timelines.save(playlists = mapOf(night to made))
                completed()
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** Returns null on success, or the reason the cover did not make it. */
    private suspend fun uploadCover(playlistId: String, uri: Uri, frameMs: Long = 0L): String? {
        if (!spotify.hasImageUploadScope()) {
            return "The cover needs a permission your Spotify login predates. " +
                "Log out in Settings and log in again to enable playlist covers."
        }
        val jpeg = photos.coverJpeg(uri, frameMs)
            ?: return "That photo could not be prepared as a cover."
        return try {
            spotify.uploadCover(playlistId, jpeg)
            null
        } catch (e: Exception) {
            "The cover could not be uploaded. ${e.message}"
        }
    }
}
