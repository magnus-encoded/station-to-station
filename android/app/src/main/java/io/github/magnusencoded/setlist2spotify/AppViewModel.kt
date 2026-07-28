package io.github.magnusencoded.setlist2spotify

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.magnusencoded.setlist2spotify.data.Friend
import io.github.magnusencoded.setlist2spotify.data.SettingsRepository
import io.github.magnusencoded.setlist2spotify.data.StoredPlaylist
import io.github.magnusencoded.setlist2spotify.data.TimelineStore
import io.github.magnusencoded.setlist2spotify.data.friendFromUri
import io.github.magnusencoded.setlist2spotify.data.photos.PhotoRepository
import io.github.magnusencoded.setlist2spotify.data.sfmStamp
import io.github.magnusencoded.setlist2spotify.data.sfmUserFromDescription
import io.github.magnusencoded.setlist2spotify.data.spotifyPlaylistId
import io.github.magnusencoded.setlist2spotify.data.toShareUri
import io.github.magnusencoded.setlist2spotify.ui.NodePlace
import io.github.magnusencoded.setlist2spotify.ui.TimelineNode
import io.github.magnusencoded.setlist2spotify.ui.groupIntoFestivals
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmArtist
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSetlist
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSong
import io.github.magnusencoded.setlist2spotify.data.setlistfm.SetlistFmClient
import io.github.magnusencoded.setlist2spotify.data.spotify.SpotifyClient
import io.github.magnusencoded.setlist2spotify.data.spotify.SpotifyTrack
import io.github.magnusencoded.setlist2spotify.data.spotify.rankCandidates
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

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

/**
 * Where a `station-to-station://` link lands. The link's first segment names whose
 * line you are looking at, and that is the same thing as the resolution: one gig on
 * its own is the setlist, a line plus a gig is that line scrolled to it.
 *
 * ponytail: [SINGLE_LINE] is always *my* line. A friend's own line is a resolution
 * the app doesn't have yet, so `station-to-station://Trummispojken/<gig>` lands on
 * mine at that night. Give it its own case when zooming into a lane exists.
 */
enum class GigLink { SETLIST, SINGLE_LINE, WOVEN }

/**
 * Reads a `station-to-station://` link into the gig it names and the resolution it
 * wants. Pure so the grammar can be checked without a device — the parsing is the
 * part most likely to be wrong, and a mis-read id fails silently.
 *
 *   334c742d              -> the setlist itself
 *   dizzi90/334c742d      -> a single line, scrolled to that gig
 *   Friends/334c742d      -> the woven view, scrolled to that gig
 */
fun parseGigLink(segments: List<String>): Pair<String, GigLink>? {
    val parts = segments.filter { it.isNotBlank() }
    val gig = parts.lastOrNull() ?: return null
    val where = when {
        parts.size < 2 -> GigLink.SETLIST
        parts[0].equals("friends", ignoreCase = true) -> GigLink.WOVEN
        else -> GigLink.SINGLE_LINE
    }
    return gig to where
}

/** A gallery photo from the night of the show, offered as the playlist cover. */
data class CoverCandidate(val uri: Uri, val preview: Bitmap?)

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
    /** Scopes granted at the last Spotify login; null when unknown. */
    val grantedScope: String? = null,
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
    /** Public playlists can be discovered by a friend's app; private ones can't. */
    val playlistPublic: Boolean = false,
    // Cover art taken from the phone's gallery on the night of the show
    val coverCandidates: List<CoverCandidate> = emptyList(),
    val coverLoading: Boolean = false,
    val selectedCoverUri: Uri? = null,
    /** True once the gallery has been searched, so "nothing found" can be said. */
    val coverSearched: Boolean = false,
    val coverPermissionGranted: Boolean = false,
    // Playlist creation
    val creatingPlaylist: Boolean = false,
    val createdPlaylistUrl: String? = null,
    val createdPlaylistName: String = "",
    val createdTrackCount: Int = 0,
    val createdRefusedCount: Int = 0,
    /** Every playlist this app has made, by the setlist id it came from, oldest first. */
    val playlistsBySetlist: Map<String, List<StoredPlaylist>> = emptyMap(),
    // Friends (peer-to-peer, on-device)
    val mySetlistFmUser: String = "",
    val friends: List<Friend> = emptyList(),
    val sharedWith: Friend? = null,
    // A friend's collection timeline, opened from the Connect screen.
    val viewingFriend: Friend? = null,
    // One friend's shows, for the friend screen. Named apart from [showsByFriend]
    // on purpose: they were friendTimeline/friendTimelines, one character and two
    // very different meanings apart.
    val viewedFriendShows: List<FmSetlist> = emptyList(),
    val viewedFriendLoading: Boolean = false,
    // Nearby discovery (mocked) → the woven view, the resolution one level out from a
    // single timeline: my line braided with every known friend's, keyed by username.
    val discovering: Boolean = false,
    val nearbyPeers: List<Friend> = emptyList(),
    /** Every lane's shows, keyed by setlist.fm username. Feeds the zoomed-out weave. */
    val showsByFriend: Map<String, List<FmSetlist>> = emptyMap(),
    val timelinesLoading: Boolean = false,
    /** Festival name by the first show id of its cluster; see resolveFestivalNames(). */
    val festivalNames: Map<String, String> = emptyMap(),
    /** Set by a card swap so the timeline opens with the other lines already showing. */
    val justConnected: Boolean = false,
    /**
     * Which resolution the timeline is at: my own line, or the woven view with every
     * known lane beside it. Held here rather than in the screen so it can be driven by
     * something other than a two-finger pinch — see MainActivity's key handling.
     */
    val zoomedOut: Boolean = false,
    /**
     * Which festivals stand open, by row key. Here rather than in the screen for the
     * same reason as [zoomedOut]: opening a gig disposes the timeline, and anything
     * remembered inside it comes back reset. A collapsed festival also changes how
     * many rows precede it, so the restored scroll offset lands somewhere else — you
     * went into a night from the woven view and came back to a different place.
     */
    val openFestivals: Set<String> = emptySet(),
    /**
     * Where a crossing sits among the lines that meet there. Three candidates, none
     * settled by argument — cycled with a key so all three can be looked at on the
     * same night rather than compared across three builds.
     */
    val nodePlace: NodePlace = NodePlace.INNERMOST,
    /**
     * A gig a `station-to-station://` link asked for, and how it wants to be shown.
     * The timeline is the one place that can find a gig's row — a gig inside a
     * collapsed festival has no row until the festival opens — so it does the
     * revealing and clears this when done.
     */
    val linkedGig: String? = null,
    val linkedGigAs: GigLink? = null,
    /** Set when the playlist was made but its cover could not be uploaded. */
    val coverUploadError: String? = null,
    // Transient error surfaced as a snackbar
    val error: String? = null,
    // Transient non-error notice (e.g. "Added a friend from that playlist")
    val notice: String? = null,
    // True once the splash has been passed (Spotify login or skip).
    val onboarded: Boolean = false,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /** ponytail: mocked nearby peers so the woven view is testable before real
         *  Nearby Connections lands. Both were at The Warning, Tons of Rock, 25 Jun
         *  2026 — the node where all three lines are one. Carlitos2 stood here first
         *  and gigged ~200 times a month, which buried my line under his and made a
         *  lane that no page limit could cover; a real history is the better test. */
        val TRUMMISPOJKEN = Friend(setlistfm = "Trummispojken", name = "Trummispojken")
        val EGIL = Friend(setlistfm = "Egil", name = "Egil")
        val MOCK_PEERS = listOf(TRUMMISPOJKEN, EGIL)

        /** setlist.fm's page size for attended lists — used to resume a cached spine. */
        private const val SETLISTS_PER_PAGE = 20
    }

    val settings = SettingsRepository(application)
    private val timelines = TimelineStore(application)
    private val setlistFm = SetlistFmClient { settings.setlistFmApiKeyValue() }
    val spotify = SpotifyClient(settings)
    private val photos = PhotoRepository(application)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var matchJob: Job? = null

    init {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    setlistFmApiKey = settings.setlistFmApiKey.first() ?: "",
                    // Effective value, so Settings shows the bundled ID and lets
                    // it be swapped for another app's without a rebuild.
                    spotifyClientId = settings.spotifyClientIdValue() ?: "",
                    spotifyConnected = spotify.isConnected(),
                    spotifyLoginReady = settings.spotifyClientIdValue() != null,
                    setlistFmReady = settings.setlistFmApiKeyValue() != null,
                    bundledSpotifyClientId = settings.hasBundledSpotifyClientId(),
                    bundledSetlistFmKey = settings.hasBundledSetlistFmKey(),
                    grantedScope = settings.grantedScope(),
                    mySetlistFmUser = settings.mySetlistFmUser.first() ?: "",
                    friends = settings.friends.first(),
                    onboarded = settings.onboarded.first(),
                )
            }
            restoreTimelines()
        }
    }

    /**
     * Puts the last-stored timelines back on screen, so a launch opens on the spine
     * instead of the empty state plus a full re-import.
     *
     * ponytail: no auto-refresh — the cache is shown and left alone until the user
     * re-imports. Fetching on every launch is exactly the cost this removes, and
     * attended history only changes when its owner edits setlist.fm. Add a
     * pull-to-refresh (or a staleness check) when the staleness is actually felt.
     */
    private suspend fun restoreTimelines() {
        val cached = timelines.load()
        if (cached.shows.isEmpty() && cached.festivalNames.isEmpty() && cached.playlistsMade.isEmpty()) return
        val me = _state.value.mySetlistFmUser
        val mine = cached.shows[me].orEmpty()
        _state.update {
            it.copy(
                festivalNames = it.festivalNames + cached.festivalNames,
                playlistsBySetlist = it.playlistsBySetlist + cached.playlistsMade,
                // Every lane but mine: the weave reads friends from here.
                showsByFriend = cached.shows - me,
                // Only adopt a cached spine if nothing has already loaded into it.
                setlists = if (mine.isNotEmpty() && it.setlists.isEmpty()) mine else it.setlists,
                source = if (mine.isNotEmpty() && it.setlists.isEmpty()) SetlistSource.USER else it.source,
                setlistsTitle = if (mine.isNotEmpty() && it.setlistsTitle.isBlank()) "Attended by $me" else it.setlistsTitle,
                userQuery = if (mine.isNotEmpty() && it.userQuery.isBlank()) me else it.userQuery,
                // The total setlist.fm reported, not how many we cached. Setting it to
                // the cached size made the spine look complete at whatever page it had
                // reached, so scrolling into your own history stopped there for good.
                setlistsTotal = if (mine.isNotEmpty() && it.setlists.isEmpty()) {
                    // No stored total means a cache written before totals were kept.
                    // Allow exactly one more page: it reports the real total and
                    // stores it, so the gap heals itself on the first scroll back.
                    cached.attendedTotals[me] ?: (mine.size + 1)
                } else {
                    it.setlistsTotal
                },
                // Resume where the cache left off. Floor, so a part-filled last page is
                // fetched again rather than skipped — loadMoreSetlists de-dupes.
                setlistsPage = if (mine.isNotEmpty() && it.setlists.isEmpty()) {
                    (mine.size / SETLISTS_PER_PAGE).coerceAtLeast(1)
                } else {
                    it.setlistsPage
                },
            )
        }
    }

    /** Records that the splash was passed, so it never shows again. */
    fun markOnboarded() {
        _state.update { it.copy(onboarded = true) }
        viewModelScope.launch { settings.setOnboarded() }
    }

    fun consumeError() = _state.update { it.copy(error = null) }
    fun consumeNotice() = _state.update { it.copy(notice = null) }

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
                        _state.update {
                            it.copy(spotifyConnected = true, grantedScope = settings.grantedScope())
                        }
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
            _state.update { it.copy(spotifyConnected = false, grantedScope = null) }
        }
    }

    // --- Friends (peer-to-peer) ---

    fun saveMySetlistFmUser(username: String) {
        val trimmed = username.trim()
        viewModelScope.launch {
            settings.saveMySetlistFmUser(trimmed)
            _state.update { it.copy(mySetlistFmUser = trimmed) }
        }
    }

    /** My shareable identity card, or null until I've set my setlist.fm username. */
    suspend fun myCardUri(): Uri? {
        val me = _state.value.mySetlistFmUser.trim()
        if (me.isEmpty()) return null
        val user = runCatching { spotify.currentUser() }.getOrNull()
        return Friend(
            setlistfm = me,
            name = user?.displayName?.ifBlank { null } ?: me,
            spotifyId = user?.id,
        ).toShareUri()
    }

    fun addFriend(friend: Friend) {
        viewModelScope.launch { addFriendNow(friend) }
    }

    private suspend fun addFriendNow(friend: Friend) {
        val current = _state.value.friends
        // De-dupe on setlist.fm username; a re-share updates the display name.
        val next = current.filterNot { it.setlistfm.equals(friend.setlistfm, ignoreCase = true) } + friend
        settings.saveFriends(next)
        _state.update { it.copy(friends = next) }
    }

    fun addFriendByUsername(username: String) {
        val u = username.trim()
        if (u.isNotEmpty()) addFriend(Friend(setlistfm = u))
    }

    fun handleFriendLink(uri: Uri) {
        friendFromUri(uri)?.let { addFriend(it) }
    }

    fun removeFriend(friend: Friend) {
        viewModelScope.launch {
            val next = _state.value.friends.filterNot { it.setlistfm == friend.setlistfm }
            settings.saveFriends(next)
            _state.update { it.copy(friends = next) }
        }
    }

    /** Opens a festival node — its individual concerts, in the same timeline UI. */

    /** Loads a friend's whole attended-concert timeline for the Connect screen. */
    fun viewFriendTimeline(friend: Friend) {
        _state.update {
            it.copy(viewingFriend = friend, viewedFriendShows = emptyList(), viewedFriendLoading = true)
        }
        viewModelScope.launch {
            try {
                // ponytail: caps at 60 shows (3 pages). Bump if power users miss older ones.
                val shows = attendedConcerts(friend.setlistfm, maxPages = 3)
                _state.update { it.copy(viewedFriendShows = shows, viewedFriendLoading = false) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        viewedFriendLoading = false,
                        error = e.message ?: "Could not load ${friend.name}'s shows",
                    )
                }
            }
        }
    }

    /** Fetches attended concerts for one user across up to [maxPages] pages. */
    private suspend fun attendedConcerts(userId: String, maxPages: Int): List<FmSetlist> {
        val all = mutableListOf<FmSetlist>()
        for (page in 1..maxPages) {
            val resp = setlistFm.userAttended(userId, page)
            all += resp.setlist
            if (all.size >= resp.total || resp.setlist.isEmpty()) break
        }
        return all
    }

    /**
     * A friend's attended shows, paged back far enough to cover my own line rather
     * than to a fixed page count. setlist.fm returns newest first, so a flat cap is
     * a *window*, not a sample: Carlitos2's first 60 shows spanned ten days, and
     * every night we actually shared was older than his last fetched page — the
     * lines could never meet however correct the drawing was.
     *
     * ponytail: [maxPages] is a runaway guard, not a policy. Nothing older than my
     * own first gig can overlap, so that is where paging stops.
     */
    private suspend fun attendedBackTo(
        userId: String,
        oldestOfMine: LocalDate?,
        maxPages: Int = 25,
    ): List<FmSetlist> {
        val all = mutableListOf<FmSetlist>()
        for (page in 1..maxPages) {
            val resp = setlistFm.userAttended(userId, page)
            all += resp.setlist
            if (all.size >= resp.total || resp.setlist.isEmpty()) break
            val pageOldest = resp.setlist.mapNotNull { it.localDate() }.minOrNull()
            if (oldestOfMine != null && pageOldest != null && pageOldest < oldestOfMine) break
        }
        return all
    }

    /**
     * Loads the concerts both [friend] and I attended into [UiState.setlists], so
     * the existing SetlistsScreen renders them and tapping one flows into the
     * normal confirm → create-playlist path.
     */
    fun openSharedConcerts(friend: Friend) {
        val me = _state.value.mySetlistFmUser.trim()
        if (me.isEmpty()) {
            _state.update { it.copy(error = "Set your setlist.fm username first (Friends screen).") }
            return
        }
        _state.update {
            it.copy(
                sharedWith = friend,
                source = SetlistSource.USER, // shared list mixes artists; show "date · artist"
                setlistsTitle = "You & ${friend.name}",
                setlists = emptyList(),
                setlistsPage = 1,
                setlistsTotal = 0,
                setlistsLoading = true,
            )
        }
        viewModelScope.launch {
            try {
                // ponytail: caps at 60 concerts each. Bump maxPages or cache per
                // user if power users miss older overlaps.
                val mine = attendedConcerts(me, maxPages = 3)
                val theirs = attendedConcerts(friend.setlistfm, maxPages = 3).map { it.id }.toSet()
                val shared = mine.filter { it.id in theirs }
                _state.update {
                    // total == size so loadMoreSetlists() won't try to paginate this list.
                    it.copy(setlists = shared, setlistsTotal = shared.size, setlistsLoading = false)
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    // --- Nearby discovery + two-timeline comparison ---

    /**
     * Begins looking for people to swap timelines with. ponytail: mocked — real
     * Nearby Connections / BLE would fill [UiState.nearbyPeers] as devices appear.
     * Today one known peer surfaces after a beat so the exchange is testable.
     */
    fun startNearbyDiscovery() {
        _state.update { it.copy(discovering = true, nearbyPeers = emptyList()) }
        viewModelScope.launch {
            delay(1600)
            // Anyone already added drops off the radar, so a second exchange finds
            // the person you haven't got yet rather than offering the same card twice.
            val known = _state.value.friends.map { it.setlistfm.lowercase() }.toSet()
            _state.update {
                it.copy(
                    discovering = false,
                    nearbyPeers = MOCK_PEERS.filterNot { p -> p.setlistfm.lowercase() in known },
                )
            }
        }
    }

    /**
     * The card swap: each phone hands over its setlist.fm ↔ Spotify identity, so we
     * add them as a friend and reload the woven view — every known timeline braided
     * against mine, co-attended shows marked as intersections.
     */
    fun connectWithPeer(peer: Friend) {
        viewModelScope.launch {
            // Persist the friend before loading, or the load runs against the old list.
            addFriendNow(peer)
            _state.update { it.copy(justConnected = true) }
            loadFriendTimelines()
        }
    }

    fun consumeJustConnected() = _state.update { it.copy(justConnected = false) }

    /**
     * Open or close the woven view. The one place that decides it, so a pinch, a card
     * swap and a key press cannot disagree about when there is anything to open onto.
     */
    fun setZoomedOut(on: Boolean) = _state.update {
        if (on && it.friends.isEmpty()) it else it.copy(zoomedOut = on)
    }

    /**
     * A `station-to-station://` link. The first segment is whose line to show — a
     * username, or `Friends` for the woven view — and the last is always the gig's
     * setlist.fm id. A single segment is the gig on its own, so it opens the setlist.
     *
     * The link only records the intent; [UiState.linkedGig] is acted on by the
     * timeline, which is the only place that knows which row a gig ended up in.
     */
    fun openGigLink(uri: Uri) {
        val (gig, where) = parseGigLink(listOfNotNull(uri.host) + uri.pathSegments) ?: return
        if (where != GigLink.SETLIST) setZoomedOut(where == GigLink.WOVEN)
        _state.update { it.copy(linkedGig = gig, linkedGigAs = where) }
    }

    fun consumeGigLink() = _state.update { it.copy(linkedGig = null, linkedGigAs = null) }

    /** Open or close a festival in place. A new set each time, so remember() sees it. */
    fun toggleFestival(key: String) = _state.update {
        it.copy(
            openFestivals = if (key in it.openFestivals) it.openFestivals - key
            else it.openFestivals + key,
        )
    }

    fun openFestival(key: String) = _state.update {
        it.copy(openFestivals = it.openFestivals + key)
    }

    /** Next candidate for where a crossing sits. See [NodePlace]. */
    fun cycleNodePlace() = _state.update {
        val all = NodePlace.entries
        it.copy(nodePlace = all[(all.indexOf(it.nodePlace) + 1) % all.size])
    }

    /** The gig behind a link, wherever it is already loaded — mine or any lane's. */
    fun knownGig(id: String): FmSetlist? =
        _state.value.setlists.firstOrNull { it.id == id }
            ?: _state.value.showsByFriend.values.firstNotNullOfOrNull { shows ->
                shows.firstOrNull { it.id == id }
            }

    /**
     * Fills in the real festival names for the clusters currently on the timeline —
     * one page fetch per festival, only for ones not already resolved. Failures are
     * silent: the venue name stays as the label.
     */
    fun resolveFestivalNames() {
        val firsts = groupIntoFestivals(_state.value.setlists)
            .filterIsInstance<TimelineNode.Festival>()
            .map { it.shows.first() }
            .filter { it.id !in _state.value.festivalNames && !it.url.isNullOrBlank() }
        if (firsts.isEmpty()) return
        viewModelScope.launch {
            val found = firsts.mapNotNull { show ->
                setlistFm.festivalName(show.url!!)?.let { show.id to it }
            }
            if (found.isNotEmpty()) {
                _state.update { it.copy(festivalNames = it.festivalNames + found) }
                // A festival name costs a fetch each; store them so it's paid once.
                timelines.save(festivalNames = found.toMap())
            }
        }
    }

    /**
     * Whether a cached lane already goes back as far as my own line does.
     *
     * ponytail: a friend whose whole history is newer than my first gig looks short
     * every time, so zooming out costs them one page fetch each — the fetch stops on
     * the first page because it has their whole list. Store their reported total if
     * that one call ever matters.
     */
    private fun reachesBack(shows: List<FmSetlist>, oldestOfMine: LocalDate?): Boolean {
        if (oldestOfMine == null) return true
        val theirOldest = shows.mapNotNull { it.localDate() }.minOrNull() ?: return false
        return theirOldest <= oldestOfMine
    }

    /** Loads every known friend's attended shows for the woven (zoomed-out) view. */
    fun loadFriendTimelines() {
        val friends = _state.value.friends
        if (friends.isEmpty()) return
        val myOldest = _state.value.setlists.mapNotNull { it.localDate() }.minOrNull()
        // Reload a lane only if it is missing or stops short of my own first gig.
        // Cached-and-complete is the common case, and refetching every lane on every
        // zoom-out is the call volume the store exists to remove — but a lane cut off
        // at 60 shows is not complete, however cached it is.
        val stale = friends.filter { friend ->
            val have = _state.value.showsByFriend[friend.setlistfm]
            have.isNullOrEmpty() || !reachesBack(have, myOldest)
        }
        if (stale.isEmpty()) return
        _state.update { it.copy(timelinesLoading = true) }
        viewModelScope.launch {
            val loaded = stale.associate { friend ->
                friend.setlistfm to runCatching { attendedBackTo(friend.setlistfm, myOldest) }
                    .getOrDefault(emptyList())
            }.filterValues { it.isNotEmpty() }
            _state.update { it.copy(showsByFriend = it.showsByFriend + loaded, timelinesLoading = false) }
            // Merge, so a friend whose fetch just failed keeps their last good lane.
            timelines.save(shows = loaded)
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

    /**
     * Timeline import: persists a just-entered API key first (so the fetch sees
     * it — saveSettings alone is fire-and-forget and would race), then loads the
     * user's attended concerts. [apiKey] is null when a key is already available.
     */
    fun importAttended(username: String, apiKey: String?) {
        viewModelScope.launch {
            consumeError()
            if (!apiKey.isNullOrBlank()) saveSettingsNow(apiKey.trim(), _state.value.spotifyClientId)
            setUserQuery(username)
            openUserAttended()
        }
    }

    fun openUserAttended() {
        val userId = _state.value.userQuery.trim()
        if (userId.isEmpty()) return
        // "My concerts" is your own username; adopt it as the identity used to stamp
        // playlists and find shared concerts — but never clobber an explicit choice.
        if (_state.value.mySetlistFmUser.isBlank()) saveMySetlistFmUser(userId)
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
                timelines.save(
                    shows = mapOf(userId to result.setlist),
                    attendedTotals = mapOf(userId to result.total),
                )
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
                        // By id: resuming a cached spine refetches its last, part-full
                        // page, and a duplicate row would collide on the LazyColumn key.
                        setlists = (it.setlists + result.setlist).distinctBy { s -> s.id },
                        setlistsPage = nextPage,
                        setlistsTotal = result.total,
                        setlistsLoading = false,
                    )
                }
                // Store the accumulated spine, or scrolling back through history
                // pays for those pages again on the next launch.
                if (s.source == SetlistSource.USER) {
                    val user = s.userQuery.trim()
                    timelines.save(
                        shows = mapOf(user to _state.value.setlists),
                        attendedTotals = mapOf(user to result.total),
                    )
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    // --- Matching ---

    /** Opens a show for viewing (its real setlist) without the Spotify match/cover
     *  machinery — that only starts when the user converts it to a playlist. */
    fun openShow(setlist: FmSetlist) = _state.update { it.copy(selectedSetlist = setlist) }

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
        // Year first: an alphabetical playlist library then falls into
        // chronological order, and the show reads as "when, who, where".
        val defaultName = listOfNotNull(
            setlist.year(),
            artistName.ifBlank { null },
            setlist.venue?.name,
        ).joinToString(" – ").ifBlank { "Setlist" }
        _state.update {
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
        _state.update { s ->
            if (index !in s.matches.indices) s
            else s.copy(matches = s.matches.mapIndexed { i, m -> if (i == index) transform(m) else m })
        }
    }

    fun toggleIncluded(index: Int) = updateMatch(index) { it.copy(included = !it.included) }

    fun chooseCandidate(index: Int, track: SpotifyTrack) =
        updateMatch(index) { it.copy(selected = track, included = true) }

    fun setPlaylistName(name: String) = _state.update { it.copy(playlistName = name) }
    fun setPlaylistPublic(public: Boolean) = _state.update { it.copy(playlistPublic = public) }

    /**
     * Discovers a friend from a Spotify playlist link they shared: reads the playlist's
     * description, and if it carries a setlist.fm stamp, adds the owner as a friend.
     */
    fun discoverFriendFromPlaylist(link: String) {
        val id = spotifyPlaylistId(link)
        if (id == null) {
            _state.update { it.copy(error = "That doesn't look like a Spotify playlist link.") }
            return
        }
        viewModelScope.launch {
            try {
                val playlist = spotify.getPlaylist(id)
                val username = sfmUserFromDescription(playlist.description)
                val ownerId = playlist.owner?.id
                val me = runCatching { spotify.currentUser().id }.getOrNull()
                when {
                    username == null -> _state.update {
                        it.copy(error = "That playlist wasn't made with this app, so there's no setlist.fm user to add.")
                    }
                    ownerId != null && ownerId == me -> _state.update {
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
                        _state.update { it.copy(notice = "Added @$username as a friend.") }
                    }
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    // --- Cover art ---

    /**
     * Offers the photos taken on the night of the selected show. Silent when the
     * gallery permission is missing: the confirm screen asks for it instead, so
     * a permission prompt only ever follows a tap.
     */
    fun loadCoverCandidates() {
        val date = _state.value.selectedSetlist?.localDate() ?: return
        val granted = photos.hasPermission()
        _state.update { it.copy(coverPermissionGranted = granted) }
        if (!granted) return
        viewModelScope.launch {
            _state.update { it.copy(coverLoading = true) }
            val found = photos.photosFrom(date)
            val candidates = found.map { CoverCandidate(it.uri, photos.preview(it.uri)) }
            _state.update {
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
    fun setCover(uri: Uri?) = _state.update {
        if (it.selectedCoverUri == uri) it else it.copy(selectedCoverUri = uri)
    }

    /** Manual re-search for one song with a user-provided query. */
    fun researchSong(index: Int, query: String) {
        if (query.isBlank()) return
        updateMatch(index) { it.copy(loading = true, error = null) }
        viewModelScope.launch {
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
                val description = buildString {
                    append("Live at ").append(setlist?.venueLine() ?: "an unknown venue")
                    // The name carries only the year, so the full date lives here.
                    setlist?.readableDate()?.let { append(", ").append(it) }
                    append(".")
                    setlist?.tour?.name?.let { append(" ").append(it).append(".") }
                    append(" From setlist.fm")
                    setlist?.url?.let { append(": ").append(it) }
                }.take(300 - stamp.length) + stamp
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
                val coverError = s.selectedCoverUri?.let { uploadCover(playlist.id, it) }
                // Fall back to the canonical URL rather than dropping the link:
                // externalUrls is Spotify's to omit, the id is ours to keep.
                val url = playlist.externalUrls["spotify"]
                    ?: "https://open.spotify.com/playlist/${playlist.id}"
                val made = StoredPlaylist(url = url, name = name, trackCount = result.added)
                val night = setlist?.id?.takeIf { it.isNotBlank() }
                _state.update {
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
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** Returns null on success, or the reason the cover did not make it. */
    private suspend fun uploadCover(playlistId: String, uri: Uri): String? {
        if (!spotify.hasImageUploadScope()) {
            return "The cover needs a permission your Spotify login predates. " +
                "Log out in Settings and log in again to enable playlist covers."
        }
        val jpeg = photos.coverJpeg(uri)
            ?: return "That photo could not be prepared as a cover."
        return try {
            spotify.uploadCover(playlistId, jpeg)
            null
        } catch (e: Exception) {
            "The cover could not be uploaded. ${e.message}"
        }
    }
}
