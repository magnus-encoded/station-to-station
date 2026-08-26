package io.github.magnusencoded.stationtostation

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.magnusencoded.stationtostation.data.Band
import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.FriendArrival
import io.github.magnusencoded.stationtostation.data.friendArrival
import io.github.magnusencoded.stationtostation.data.DeviceLocation
import io.github.magnusencoded.stationtostation.data.DeviceTimelinePlumbing
import io.github.magnusencoded.stationtostation.data.Festivals
import io.github.magnusencoded.stationtostation.data.LoadedSpine
import io.github.magnusencoded.stationtostation.data.SettingsRepository
import io.github.magnusencoded.stationtostation.data.StoredAct
import io.github.magnusencoded.stationtostation.data.StoredAttendance
import io.github.magnusencoded.stationtostation.data.StoredBill
import io.github.magnusencoded.stationtostation.data.StoredLog
import io.github.magnusencoded.stationtostation.data.artistLabel
import io.github.magnusencoded.stationtostation.data.bandsOf
import io.github.magnusencoded.stationtostation.data.billNight
import io.github.magnusencoded.stationtostation.data.candidateSongs
import io.github.magnusencoded.stationtostation.data.fmDate
import io.github.magnusencoded.stationtostation.data.gigNight
import io.github.magnusencoded.stationtostation.data.isLocal
import io.github.magnusencoded.stationtostation.data.localGigSetlist
import io.github.magnusencoded.stationtostation.data.moveMedia
import io.github.magnusencoded.stationtostation.data.parseFmDate
import io.github.magnusencoded.stationtostation.data.parseLineup
import io.github.magnusencoded.stationtostation.data.plannedLane
import io.github.magnusencoded.stationtostation.data.playsSong
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.StoredPlaylist
import io.github.magnusencoded.stationtostation.data.TimelineLogic
import io.github.magnusencoded.stationtostation.data.TimelineCache
import io.github.magnusencoded.stationtostation.data.TimelineStore
import io.github.magnusencoded.stationtostation.data.friendFromUri
import io.github.magnusencoded.stationtostation.data.gigIdFromInvite
import io.github.magnusencoded.stationtostation.data.photos.PhotoRepository
import io.github.magnusencoded.stationtostation.data.sfmStamp
import io.github.magnusencoded.stationtostation.data.sfmUserFromDescription
import io.github.magnusencoded.stationtostation.data.spotifyPlaylistId
import io.github.magnusencoded.stationtostation.data.toShareUri
import io.github.magnusencoded.stationtostation.ui.atVenue
import io.github.magnusencoded.stationtostation.ui.canCheckInManually
import io.github.magnusencoded.stationtostation.ui.checkInCandidate
import io.github.magnusencoded.stationtostation.ui.venueMapsQuery
import io.github.magnusencoded.stationtostation.ble.ProbeCard
import io.github.magnusencoded.stationtostation.data.AccountsMove
import io.github.magnusencoded.stationtostation.data.AccountsPayload
import io.github.magnusencoded.stationtostation.data.CATEGORY_ACCOUNTS
import io.github.magnusencoded.stationtostation.data.Credentials
import io.github.magnusencoded.stationtostation.data.HandoverManifest
import io.github.magnusencoded.stationtostation.data.Identities
import io.github.magnusencoded.stationtostation.data.categoriesFor
import io.github.magnusencoded.stationtostation.data.deviceManifest
import io.github.magnusencoded.stationtostation.data.identitiesOnly
import io.github.magnusencoded.stationtostation.data.mayClearCredentials
import io.github.magnusencoded.stationtostation.data.exchange.HandoverInvite
import io.github.magnusencoded.stationtostation.data.exchange.HandoverPhase
import io.github.magnusencoded.stationtostation.data.exchange.HandoverProgress
import io.github.magnusencoded.stationtostation.data.exchange.HandoverReceipt
import io.github.magnusencoded.stationtostation.data.exchange.certFingerprint
import io.github.magnusencoded.stationtostation.data.exchange.forgetHandoverIdentity
import io.github.magnusencoded.stationtostation.data.exchange.generateHandoverIdentity
import io.github.magnusencoded.stationtostation.data.exchange.handoverAlias
import io.github.magnusencoded.stationtostation.data.exchange.localLinkAddress
import io.github.magnusencoded.stationtostation.data.exchange.parseHandoverInvite
import io.github.magnusencoded.stationtostation.data.exchange.runHandoverReceiver
import io.github.magnusencoded.stationtostation.data.exchange.runHandoverSource
import io.github.magnusencoded.stationtostation.data.exchange.sslClientContext
import io.github.magnusencoded.stationtostation.data.exchange.sslServerContext
import io.github.magnusencoded.stationtostation.data.exchange.toUri
import io.github.magnusencoded.stationtostation.data.exchange.ContactExchange
import io.github.magnusencoded.stationtostation.data.exchange.ExchangePeer
import io.github.magnusencoded.stationtostation.data.exchange.ExchangeSession
import io.github.magnusencoded.stationtostation.data.exchange.contactIdentityPublicKeyBase64
import io.github.magnusencoded.stationtostation.data.contactManifest
import io.github.magnusencoded.stationtostation.data.GalleryItem
import io.github.magnusencoded.stationtostation.data.exchange.readAccountsAck
import io.github.magnusencoded.stationtostation.data.exchange.readAccountsStep
import io.github.magnusencoded.stationtostation.data.exchange.writeAccountsAck
import io.github.magnusencoded.stationtostation.data.exchange.writeAccountsStep
import io.github.magnusencoded.stationtostation.data.setlistfm.FmArtist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSong
import io.github.magnusencoded.stationtostation.data.musicbrainz.MbArtist
import io.github.magnusencoded.stationtostation.data.musicbrainz.MusicBrainzClient
import io.github.magnusencoded.stationtostation.data.setlistfm.SetlistFmClient
import io.github.magnusencoded.stationtostation.data.setlistfm.parseSetlistId
import io.github.magnusencoded.stationtostation.data.spotify.SpotifyClient
import io.github.magnusencoded.stationtostation.data.spotify.SpotifyTrack
import io.github.magnusencoded.stationtostation.data.spotify.rankCandidates
import java.io.Closeable
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Socket
import java.time.LocalDate
import java.time.LocalDateTime

/** A song with no place yet in the night's recording. 0L is a real time — the first song. */
const val NOT_STAMPED = -1L

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
 * the app doesn't have yet, so `station-to-station://Lemmy/<gig>` lands on
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

/** A gig-keepsake thumbnail: the decoded frame, and whether it came from a video. */
data class MediaThumb(val bitmap: Bitmap?, val isVideo: Boolean = false)

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
    /** The bundled Spotify client id, masked — what the empty field hints at. */
    val bundledSpotifyHint: String = "",
    /** The bundled setlist.fm key, masked. */
    val bundledSetlistFmHint: String = "",
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
    /** Which frame of it, when the chosen cover is a video — scrubbed by the user. */
    val selectedCoverFrameMs: Long = 0L,
    /** True once the gallery has been searched, so "nothing found" can be said. */
    val coverSearched: Boolean = false,
    val coverPermissionGranted: Boolean = false,
    /**
     * The Reliver's media on a gig's single-night view, by setlist id — records
     * now, not bare URIs (#97), so a photo carries its kind, its capture time and
     * the **Personal** bit rather than being a string the gallery can invalidate.
     * A video's song stamps ride on its own record.
     */
    val mediaBySetlist: Map<String, List<StoredMedia>> = emptyMap(),
    // Gig-photo suggestions: the same same-night gallery search as the playlist
    // cover picker, offered as one-tap adds instead of a single chosen cover.
    val gigPhotoSuggestions: List<CoverCandidate> = emptyList(),
    val gigPhotoSuggestionsLoading: Boolean = false,
    val gigPhotoSuggestionsSearched: Boolean = false,
    val gigPhotoSuggestionsPermissionGranted: Boolean = false,
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
    // The Exchange → the woven view, the resolution one level out from a single timeline:
    // my line braided with every known friend's, keyed by username.
    val discovering: Boolean = false,
    /** Everyone the radios have surfaced, deduped into one list; see [ExchangePeer]. */
    val exchangePeers: List<ExchangePeer> = emptyList(),
    /** The display name we are mid-connect with, for "Connecting with dizzi90". Null otherwise. */
    val connectingWith: String? = null,
    /** Every lane's shows, keyed by setlist.fm username. Feeds the zoomed-out weave. */
    val showsByFriend: Map<String, List<FmSetlist>> = emptyMap(),
    val timelinesLoading: Boolean = false,
    /**
     * The gigs I'm going to: nights that haven't happened, above today on the line.
     * Kept out of [setlists] deliberately — that list is what I attended, it drives
     * "13 shows" and the festival clustering, and neither is true of a ticket.
     */
    val plannedGigs: List<FmSetlist> = emptyList(),
    /** A planned gig is being fetched from setlist.fm. */
    val planningLoading: Boolean = false,
    /**
     * Spellings MusicBrainz offered for the artist name being typed into a planned
     * gig. A prompt and never a requirement — the field works with the list empty,
     * which is the ordinary case for a small act and must stay usable.
     */
    val artistSuggestions: List<MbArtist> = emptyList(),
    /**
     * The **Bills** on the wall, poster order preserved. Above today like the gigs
     * I'm going to, and for the same reason — up is always later — but never mixed
     * in with them: a **Bill** is a lineup, not a set of tickets.
     */
    val bills: List<StoredBill> = emptyList(),
    /** An **Act**'s candidate songs are being fetched. The **Bill** id, or null. */
    val billFetching: String? = null,
    /**
     * My **Log** of each night, by gig id — what I saw, kept apart from what
     * setlist.fm publishes. Restored from disk, because a set noted in a field with
     * no signal is the one thing here that cannot be fetched again.
     */
    val logsByGig: Map<String, StoredLog> = emptyMap(),
    /**
     * The light is on: my own **Line**, lit as a **Contact** sees it (#145).
     *
     * A state of where I already am, not a place I travelled to — the same corridor,
     * the same rooms, in the same order, differently lit. It persists while I walk
     * around under it and is flicked off by the same gesture that turned it on.
     */
    val contactLight: Boolean = false,
    /**
     * Inside the light: show what I am *withholding*, as placeholders. Off by default,
     * because the primary question is what a **Contact** sees and the faithful answer
     * is the one that needs no interpretation.
     */
    val showWithheld: Boolean = false,
    /** An artist's own titles by mbid, for correcting a **Log** entry (#126). */
    val catalogueByArtist: Map<String, List<String>> = emptyMap(),
    /** The mbid whose catalogue is being fetched, or null. */
    val catalogueFetching: String? = null,
    /**
     * My relationship to each gig, by gig id — planned, attended, checked in.
     * Restored from disk on launch, which is what makes a check-in survive a cold
     * start rather than being a thing the screen remembers until it doesn't.
     */
    val attendanceByGig: Map<String, StoredAttendance> = emptyMap(),
    /** The calendar event made for a gig, by gig id → its content URI; restored from disk. */
    val calendarEventByGig: Map<String, String> = emptyMap(),
    /**
     * A card that would change a **Contact** I already hold, waiting to be allowed or
     * refused (#188). Nothing has been written while this is set.
     *
     * Not persisted, deliberately: an unanswered question about a card is not a fact
     * about my record, and a prompt surviving a cold start would outlive the moment
     * that produced it — by which time nobody remembers who handed it over.
     */
    val friendConflict: FriendArrival.Conflict? = null,
    /**
     * The gig the timeline is offering a check-in for, if the one location fix it
     * took put me at one. Null the rest of the time, which is nearly always.
     */
    val checkInOffer: FmSetlist? = null,
    /**
     * Every **Festival** identity this device knows, and which **Gigs** carry one.
     * Nothing else makes a **Node** a **Festival** (#166); see resolveFestivals().
     */
    val festivals: Festivals = Festivals(),
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
     * Whose **Line** is tapped out of the weave, by setlist.fm username. A reading aid
     * for a moment of comparison, not a preference and not a decision about a person:
     * nothing is stored, nothing is revoked, and nothing is told to anyone (#266).
     *
     * Held here for the same reason as [openFestivals] — opening a gig disposes the
     * timeline, and a filter set up to survive a change of **Resolution** must survive
     * going one rung **Inner** and coming back. Deliberately **not** persisted: opening
     * the app to a timeline missing people you have no memory of hiding is the failure
     * worth avoiding, and this state dies with the process.
     *
     * Keyed by person, so someone who has never been hidden is visible — adding a
     * **Followed line** or a **Contact** is never silently a no-op.
     */
    val hiddenLines: Set<String> = emptySet(),
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
    /** The device handover on screen, if one is running or has just finished (#142). */
    val handover: HandoverUi = HandoverUi(),
    // Transient error surfaced as a snackbar
    val error: String? = null,
    // Transient non-error notice (e.g. "Added a friend from that playlist")
    val notice: String? = null,
    // True once the splash has been passed (Spotify login or skip).
    val onboarded: Boolean = false,
)

/**
 * One handover, as the screen sees it (#142). Which side of it this phone is on, the code
 * to show while waiting, how far it has got, and what it ended up being.
 *
 * [receipt] and [error] are the two ways it ends and they are not the same thing: a
 * receipt with `trouble` set is a transfer that stopped early and still landed what it
 * landed, while [error] is never having got as far as a transfer at all.
 */
data class HandoverUi(
    val role: HandoverRole? = null,
    /** The source's QR content: where to connect, what to pin, and the session's key. */
    val inviteUri: String? = null,
    val progress: HandoverProgress = HandoverProgress(),
    val receipt: HandoverReceipt? = null,
    val error: String? = null,
) {
    val running: Boolean get() = role != null && receipt == null && error == null
}

enum class HandoverRole { SOURCE, RECEIVER }

class AppViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /** setlist.fm's page size for attended lists — used to resume a cached spine. */
        private const val SETLISTS_PER_PAGE = 20
    }

    val settings = SettingsRepository(application)
    private val timelines = TimelineStore(application)
    private val setlistFm = SetlistFmClient { settings.setlistFmApiKeyValue() }
    private val musicBrainz = MusicBrainzClient()

    /**
     * The Timeline's sequence and rules (ADR-0001), with the device half handed in
     * rather than constructed inside it — which is what makes them reachable from
     * a test. Everything else in this view model is still the OS-facing half.
     */
    private val logic = TimelineLogic(DeviceTimelinePlumbing(timelines, setlistFm))

    val spotify = SpotifyClient(settings)
    private val photos = PhotoRepository(application)
    private val exchange = ExchangeSession(application, viewModelScope)
    private val where = DeviceLocation(application)

    /**
     * #257's whole LAN reconcile, scoped to the Exchange screen:
     * [startContactExchange]/[stopContactExchange] sit on that screen's
     * `DisposableEffect`, alongside the Nearby/BLE radios it already starts and stops —
     * no background service, no extra permission, and no advertising on the network for
     * as long as the app merely happens to be open.
     */
    private val contactExchange = ContactExchange(
        context = application,
        scope = viewModelScope,
        photos = photos,
        contactKeys = { settings.friends.first().mapNotNull { it.publicKey } },
        manifest = {
            val cache = timelines.load()
            hashedManifest(contactManifest(cache, contactIdentityPublicKeyBase64()), cache)
        },
        mine = { timelines.load() },
        gallery = { galleryForMatching(timelines.load()) },
        onLanded = { landing -> timelines.mergeContactMedia(landing) },
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var matchJob: Job? = null

    /** The in-flight artist lookup, so a new keystroke cancels the last one. */
    private var artistSearch: Job? = null

    init {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    setlistFmApiKey = settings.setlistFmApiKey.first() ?: "",
                    // What *I* entered, never the effective value. This used to be
                    // `spotifyClientIdValue()`, which falls back to the bundled id —
                    // so the field arrived pre-filled with a value the user had not
                    // typed, and pressing Save pinned it as their personal override.
                    // A later build shipping a different bundled id would then be
                    // ignored on that phone, silently and permanently. The bundled
                    // one is hinted at instead, masked (see [bundledSpotifyHint]).
                    spotifyClientId = settings.spotifyClientId.first() ?: "",
                    spotifyConnected = spotify.isConnected(),
                    spotifyLoginReady = settings.spotifyClientIdValue() != null,
                    setlistFmReady = settings.setlistFmApiKeyValue() != null,
                    bundledSpotifyClientId = settings.hasBundledSpotifyClientId(),
                    bundledSetlistFmKey = settings.hasBundledSetlistFmKey(),
                    bundledSpotifyHint = settings.bundledSpotifyClientIdHint(),
                    bundledSetlistFmHint = settings.bundledSetlistFmKeyHint(),
                    grantedScope = settings.grantedScope(),
                    mySetlistFmUser = settings.mySetlistFmUser.first() ?: "",
                    friends = settings.friends.first(),
                    onboarded = settings.onboarded.first(),
                )
            }
            restoreTimelines()
        }
        // The radios' outputs, mirrored into UiState.
        viewModelScope.launch {
            exchange.peers.collect { peers ->
                // Anyone already on my timeline drops off the radar — the list is
                // "people I could add", not "people who are here". A BLE peer whose card
                // hasn't arrived has no username to match on, so it stays until tapped.
                val known = _state.value.friends.map { it.setlistfm.lowercase() }.toSet()
                _state.update {
                    it.copy(
                        exchangePeers = peers.filterNot { p -> p.setlistfm?.lowercase() in known },
                        discovering = peers.isEmpty(),
                    )
                }
            }
        }
        viewModelScope.launch {
            exchange.failure.collect { message ->
                if (message != null) {
                    _state.update { it.copy(error = message, discovering = false) }
                    exchange.consumeFailure()
                }
            }
        }
        // #87: the peer tapped, not me — their card arrived over the write characteristic.
        // Same landing as a tap, so one tap brings both people in.
        exchange.onFriendReceived = { friend -> viewModelScope.launch { bringIn(friend) } }
    }

    /**
     * Called when the Exchange screen appears — see [contactExchange]'s doc comment.
     *
     * Only once there is a **Contact** with a key to search for: a first-time user has
     * nobody to reconcile with, and lighting up a radio to look for them is asking the
     * network a question with no possible answer. iOS gates the same call for a sharper
     * reason — starting it is what raises the local-network permission prompt there.
     */
    fun startContactExchange() {
        if (_state.value.friends.any { !it.publicKey.isNullOrBlank() }) contactExchange.start()
    }

    /** Called when the Exchange screen goes away — see [contactExchange]'s doc comment. */
    fun stopContactExchange() = contactExchange.stop()

    override fun onCleared() {
        exchange.stop()
        contactExchange.stop()
        super.onCleared()
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
        // plannedShows counts: a collector with no history but one ticket is a real
        // cold start, and without it here that launch restored nothing at all.
        if (cached.shows.isEmpty() && cached.festivals.isEmpty() &&
            cached.gigPlaylists.isEmpty() && cached.gigPlanned.isEmpty() &&
            // A Bill on its own is a real cold start too: the lineup was entered the
            // night before, and the phone has been to no gigs at all yet.
            cached.bills.isEmpty()
        ) {
            return
        }
        val me = _state.value.mySetlistFmUser
        _state.update {
            it.copy(
                // The store keys everything by its own Gig id now (#107); these read
                // it back under the id the screens use — the setlist.fm id where the
                // night has one, its own where it doesn't.
                playlistsBySetlist = it.playlistsBySetlist + cached.playlists(),
                mediaBySetlist = it.mediaBySetlist + cached.media(),
                plannedGigs = sortedPlanned(cached.planned()),
                // An **Act**'s pointer is read back the same way, and for the same
                // reason: adoption renames a night without moving its data, so a
                // pointer minted before it is stale afterwards. This was the one map
                // here that read raw, which is why a published act stopped opening and
                // its night drew twice. Also heals pointers already saved stale.
                bills = cached.bills.values.map { b ->
                    b.copy(acts = b.acts.map { a -> if (a.gigId == null) a else a.copy(gigId = cached.keyOf(a.gigId)) })
                },
                logsByGig = it.logsByGig + cached.logs(),
                catalogueByArtist = it.catalogueByArtist + cached.catalogueByArtist,
                attendanceByGig = it.attendanceByGig + cached.attendance(),
                calendarEventByGig = it.calendarEventByGig + cached.calendarEvents(),
            )
        }
        // The Spine itself — which source it comes from, and the retry of unresolved
        // Festival names that follows — is the logic layer's sequence, so it is the
        // same sequence iOS runs and the same one the tests drive. [adoptSpine] runs
        // once for the Spine and again if the retry found anything.
        //
        // ponytail: this reads timelines.json a second time, since the plumbing owns
        // the load now and everything above still needs the rest of the cache. One
        // small file at launch. Hand the cache in if it is ever felt.
        logic.loadSpine(me) { spine -> adoptSpine(spine, cached.attendedTotals[me]) }
    }

    /**
     * Puts a loaded Spine on screen. Only ever *adopts* it: anything already loaded
     * into the list — a live import that beat the cache back — wins, because the
     * cache is the older story of the same line.
     */
    private fun adoptSpine(spine: LoadedSpine, attendedTotal: Int?) = _state.update {
        val mine = spine.mine
        // Only adopt a cached spine if nothing has already loaded into it.
        val adopt = mine.isNotEmpty() && it.setlists.isEmpty()
        it.copy(
            festivals = it.festivals + spine.festivals,
            // Every lane but mine: the weave reads friends from here.
            showsByFriend = spine.byFriend,
            setlists = if (adopt) mine else it.setlists,
            source = if (adopt) SetlistSource.USER else it.source,
            setlistsTitle = if (mine.isNotEmpty() && it.setlistsTitle.isBlank()) {
                "Attended by ${spine.me}"
            } else {
                it.setlistsTitle
            },
            userQuery = if (mine.isNotEmpty() && it.userQuery.isBlank()) spine.me else it.userQuery,
            // The total setlist.fm reported, not how many we cached. Setting it to
            // the cached size made the spine look complete at whatever page it had
            // reached, so scrolling into your own history stopped there for good.
            //
            // No stored total means a cache written before totals were kept. Allow
            // exactly one more page: it reports the real total and stores it, so the
            // gap heals itself on the first scroll back.
            setlistsTotal = if (adopt) attendedTotal ?: (mine.size + 1) else it.setlistsTotal,
            // Resume where the cache left off. Floor, so a part-filled last page is
            // fetched again rather than skipped — loadMoreSetlists de-dupes.
            setlistsPage = if (adopt) (mine.size / SETLISTS_PER_PAGE).coerceAtLeast(1) else it.setlistsPage,
        )
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

    // --- Device handover, accounts step (#143) --------------------------------------
    //
    // What connects `HandoverWire`'s tested wire primitives (#259) to real storage.
    // Both functions take an already-authenticated `Socket` — the TLS handshake and
    // `proveLinkKey`/`verifyLinkKey` challenge (`HandoverWire.kt`) already passed —
    // because establishing that socket between two real devices is the transport
    // bring-up #142 owns, not this step. Deliberately not unit-testable at this layer
    // for the same reason `AndroidKeyStoreCert`/`HandoverDebugHarness` are not: real
    // sockets. The protocol sequencing they call is already covered by
    // `HandoverWireTest`, and the gating decision they call ([mayClearCredentials]) is
    // already covered by `AccountsTest`.

    /**
     * Receiving device's half. Stores whatever arrives — durably, via [settings] —
     * *before* acking, because the source's clear is gated on the ack having meant
     * something real. Returns null only if the connection dropped before any accounts
     * frame arrived; a genuinely declined row still arrives as identities-only, not as
     * null.
     */
    suspend fun receiveHandoverAccounts(socket: Socket): AccountsPayload? =
        withContext(Dispatchers.IO) {
            val payload = readAccountsStep(socket) ?: return@withContext null

            payload.identities.setlistFmUser?.let { settings.saveMySetlistFmUser(it) }
            val token = payload.credentials.spotifyRefreshToken
            if (!token.isNullOrBlank()) {
                settings.saveHandoverCredentials(token, payload.credentials.spotifyScope)
            }
            writeAccountsAck(socket)

            _state.update {
                it.copy(
                    mySetlistFmUser = payload.identities.setlistFmUser ?: it.mySetlistFmUser,
                    spotifyConnected = it.spotifyConnected || !token.isNullOrBlank(),
                    grantedScope = payload.credentials.spotifyScope ?: it.grantedScope,
                )
            }
            payload
        }

    /**
     * Sending device's half — the phone being replaced. Sends [payload], then signs out
     * *here* only if the receiver's ack genuinely arrives ([mayClearCredentials]): a
     * dropped connection after the send must never clear a credential that may exist
     * nowhere else. This is the one call site of a handover-triggered
     * [SettingsRepository.clearSpotifyAuth] — manual sign-out ([disconnectSpotify]) does
     * not go through it and is untouched.
     */
    suspend fun sendHandoverAccounts(socket: Socket, payload: AccountsPayload): AccountsMove =
        withContext(Dispatchers.IO) {
            writeAccountsStep(socket, payload)
            val step = if (readAccountsAck(socket)) AccountsMove.ACKNOWLEDGED else AccountsMove.SENT
            // The payload, not the step, decides whether there is anything to let go of:
            // an identities-only frame (the accounts row unticked, #143 story 11) travels
            // and is acked exactly like a full one, and signing out on that ack would move
            // an account nobody asked to move.
            if (mayClearCredentials(step) && !payload.credentials.spotifyRefreshToken.isNullOrBlank()) {
                settings.clearSpotifyAuth()
                _state.update { it.copy(spotifyConnected = false, grantedScope = null) }
            }
            step
        }

    // --- Device handover, the session itself (#142) ----------------------------------
    //
    // The two ends of one transfer, each a single job holding a single socket. Everything
    // decidable inside them lives in `HandoverSession.kt` and is tested over a loopback
    // pair; what is here is the device half — a real certificate from `AndroidKeyStore`, a
    // real TLS socket, the real gallery and the real store.

    private var handoverJob: Job? = null

    /** Closed by [cancelHandover]. A blocking socket read cannot be interrupted by a flag,
     * so cancelling is closing the socket out from under it — see `HandoverSession`. */
    @Volatile private var handoverCloseables: List<Closeable> = emptyList()

    private fun handoverUi(update: (HandoverUi) -> HandoverUi) =
        _state.update { it.copy(handover = update(it.handover)) }

    /**
     * The old phone. Generates a session identity, listens, and puts the invite on screen
     * as a QR: where to connect, the fingerprint to pin, and the link key that proves the
     * other phone read *this* screen. Serves exactly one joining device and then stops.
     *
     * [allow] is the tick list, applied to the manifest at construction — see
     * [deviceManifest]. Nothing outside it reaches the wire.
     */
    fun offerHandover(allow: Set<String>) {
        // Not just a cancel: the previous session may be parked on a blocking accept, and
        // overwriting `handoverCloseables` below would drop the only reference to it.
        endHandover()
        handoverUi { HandoverUi(role = HandoverRole.SOURCE) }
        val sessionId = UUID.randomUUID().toString().take(8)
        handoverJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Asked before anything is bound: there is nothing to put in the QR
                // without it, and a socket opened first would be one the throw below
                // leaves bound with nothing holding a reference to close it.
                val host = localLinkAddress(getApplication())
                    ?: throw IllegalStateException("this phone is not on a network to hand over across")
                val (cert, keyStore) = generateHandoverIdentity(sessionId)
                val linkKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val server = sslServerContext(keyStore, CharArray(0), handoverAlias(sessionId))
                    .serverSocketFactory.createServerSocket(0)
                handoverCloseables = listOf(server)
                handoverUi {
                    it.copy(
                        inviteUri = HandoverInvite(host, server.localPort, certFingerprint(cert), linkKey).toUri(),
                    )
                }
                server.use { listening ->
                    val socket = listening.accept()
                    handoverCloseables = listOf(server, socket)
                    socket.use { live ->
                        val cache = timelines.load()
                        val manifest = hashedManifest(deviceManifest(cache, allow, myIdentities()), cache)
                        val payload = accountsPayload(allow)
                        val refById = cache.gigMedia.values.flatten().associate { it.id to it.ref }
                        val receipt = runHandoverSource(
                            socket = live,
                            linkKey = linkKey,
                            allow = allow,
                            manifest = manifest,
                            accounts = { s -> sendHandoverAccounts(s, payload) },
                            mediaSource = { id -> refById[id]?.let { photos.mediaSource(it) } },
                            onProgress = { p -> handoverUi { it.copy(progress = p) } },
                        )
                        handoverUi {
                            if (receipt == null) it.copy(error = "That phone could not prove it read this code.")
                            else it.copy(receipt = receipt)
                        }
                    }
                }
            } catch (e: Exception) {
                handoverUi { it.copy(error = it.error ?: handoverTrouble(e)) }
            } finally {
                handoverCloseables = emptyList()
                runCatching { forgetHandoverIdentity(sessionId) }
            }
        }
    }

    /**
     * The new phone, arriving from the QR's deep link. Connects, pinning the exact
     * certificate the code named, and takes whatever the old phone approved.
     *
     * A link that is not a handover invite is simply not one: null, no error, nothing
     * started — [MainActivity] hands every `station-to-station://handover` link here and
     * a malformed one is indistinguishable from a mistyped anything else.
     */
    fun joinHandover(uri: Uri) {
        val invite = parseHandoverInvite(uri.toString()) ?: return
        endHandover()
        handoverUi { HandoverUi(role = HandoverRole.RECEIVER) }
        handoverJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val socket = sslClientContext(invite.fingerprint).socketFactory
                    .createSocket(invite.host, invite.port)
                handoverCloseables = listOf(socket)
                socket.use { live ->
                    val cache = timelines.load()
                    val receipt = runHandoverReceiver(
                        socket = live,
                        linkKey = invite.linkKey,
                        accounts = { s -> receiveHandoverAccounts(s) },
                        mine = cache,
                        gallery = galleryForMatching(cache),
                        receivedFile = { id, kind -> photos.receivedMediaFile(id, kind) },
                        refForReceivedFile = photos::fileProviderRef,
                        apply = { replan -> timelines.applyHandover(replan) },
                        onProgress = { p -> handoverUi { it.copy(progress = p) } },
                    )
                    handoverUi {
                        if (receipt == null) it.copy(error = "That transfer did not verify, so nothing was written.")
                        else it.copy(receipt = receipt)
                    }
                }
                restoreTimelines()
            } catch (e: Exception) {
                handoverUi { it.copy(error = it.error ?: handoverTrouble(e)) }
            } finally {
                handoverCloseables = emptyList()
            }
        }
    }

    /**
     * The one way a handover session ends: **close, then cancel**.
     *
     * That order is the whole of it. A blocking `accept()` or socket read cannot be
     * interrupted by cancelling the job — the coroutine is parked in a native call — so a
     * cancel on its own leaves a bound port, an IO thread and an un-forgotten
     * `AndroidKeyStore` identity behind, and leaves a phone that already read the QR able
     * to complete the whole transfer against a screen nobody is looking at. Closing the
     * socket is what makes the parked read throw and the coroutine's own `finally` run.
     */
    private fun endHandover() {
        handoverCloseables.forEach { runCatching { it.close() } }
        handoverCloseables = emptyList()
        handoverJob?.cancel()
        handoverJob = null
    }

    /** Starting is not a commitment (#142 story 15). What already arrived stays. */
    fun cancelHandover() {
        endHandover()
        handoverUi {
            if (it.receipt != null) it else it.copy(
                progress = it.progress.copy(phase = HandoverPhase.FAILED),
                error = "Stopped. Anything that had already arrived is on this phone.",
            )
        }
    }

    /**
     * Leaves the handover screen's state behind, once it has been read — and takes the
     * session with it, because this is also what a *back gesture* off the screen calls
     * (see `HandoverScreen`'s `DisposableEffect`). Leaving the screen with a listener
     * still up would mean a transfer completing behind it.
     */
    fun dismissHandover() {
        endHandover()
        handoverUi { HandoverUi() }
    }

    /** A dropped wifi, a refused certificate and a closed socket all read the same from
     * here, and naming the exception at the user is not information. */
    private fun handoverTrouble(e: Exception): String = when (e) {
        is CancellationException -> throw e
        else -> "The connection to the other phone failed."
    }

    /**
     * The bytes and content hash of everything a manifest offers, filled in from real
     * storage — the one part of building a manifest that has to read files, which is why
     * the pure builders ([deviceManifest], [contactManifest]) leave both at their defaults.
     *
     * Shared by both far ends on purpose: a Contact's offer and my own other phone's are
     * the same shape, and hashing them two different ways is how the same photograph ends
     * up looking like two different files.
     */
    private suspend fun hashedManifest(bare: HandoverManifest, cache: TimelineCache): HandoverManifest =
        bare.copy(media = bare.media.map { item ->
            val ref = cache.gigMedia[item.gigId]?.firstOrNull { it.id == item.id }?.ref
            val uri = ref?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) } ?: return@map item
            item.copy(hash = photos.mediaHash(uri) ?: "", bytes = runCatching {
                getApplication<Application>().contentResolver
                    .openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
            }.getOrDefault(0L))
        })

    /**
     * My own gallery, narrowed to the nights I have records of, hashed — the candidates an
     * incoming offer is matched against so that a photograph already on this phone is
     * never sent over the wire a second time.
     */
    private suspend fun galleryForMatching(cache: TimelineCache): List<GalleryItem> =
        cache.gigs.values.mapNotNull { it.date.takeIf { d -> d.isNotBlank() } }
            .distinct()
            // No cap: photosFrom's default limit=20 is sized for cover-photo picking,
            // not for reconcile matching — truncating here would send bytes the peer
            // already has locally just because they fell past position 20.
            .flatMap { d -> parseFmDate(d)?.let { photos.photosFrom(it, limit = Int.MAX_VALUE) }.orEmpty() }
            .distinctBy { it.uri }
            .mapNotNull { p -> photos.mediaHash(p.uri)?.let { GalleryItem(ref = p.uri.toString(), hash = it) } }

    private suspend fun myIdentities(): Identities {
        val user = runCatching { spotify.currentUser() }.getOrNull()
        return Identities(
            setlistFmUser = _state.value.mySetlistFmUser.trim().ifBlank { null },
            spotifyAccount = user?.id,
        )
    }

    /** Credentials only when the row was ticked; identities travel either way (#143). */
    private suspend fun accountsPayload(allow: Set<String>): AccountsPayload {
        val identities = myIdentities()
        if (CATEGORY_ACCOUNTS !in allow) return identitiesOnly(identities)
        return AccountsPayload(
            identities = identities,
            credentials = Credentials(
                spotifyRefreshToken = settings.refreshTokenValue(),
                spotifyScope = settings.grantedScope(),
            ),
        )
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

    /**
     * A card handed to me. Writes into an empty space; **asks before changing a contact
     * I already hold** (#188).
     *
     * Every route in comes through here — a deep link, a BLE write, a pasted username —
     * so the question is answered once rather than at each door.
     */
    fun addFriend(friend: Friend) {
        viewModelScope.launch { addFriendNow(friend) }
    }

    private suspend fun addFriendNow(friend: Friend) {
        when (val arrival = friendArrival(friend, _state.value.friends)) {
            is FriendArrival.Unchanged -> Unit
            is FriendArrival.New -> writeFriend(arrival.friend)
            // A **Followed line** becoming a **Contact**. Written as silently as a new
            // one: there was no key held, so nothing is being overwritten.
            is FriendArrival.Promotion -> writeFriend(arrival.friend)
            is FriendArrival.Conflict ->
                _state.update { it.copy(friendConflict = arrival) }
        }
    }

    /** The confirmed overwrite, and the only thing the prompt can do besides nothing. */
    fun confirmFriendOverwrite() {
        val pending = _state.value.friendConflict ?: return
        _state.update { it.copy(friendConflict = null) }
        viewModelScope.launch { writeFriend(pending.incoming) }
    }

    fun dismissFriendOverwrite() = _state.update { it.copy(friendConflict = null) }

    private suspend fun writeFriend(friend: Friend) {
        val current = _state.value.friends
        // De-dupe on setlist.fm username — the identity the list has always used.
        val held = current.firstOrNull { it.setlistfm.equals(friend.setlistfm, ignoreCase = true) }
        // A key already held is never dropped by a later, thinner way of meeting the same
        // person (#188). Only the radio carries a key — a link, a QR scan, a typed
        // username and a playlist collaborator all arrive without one — so writing the
        // card wholesale would silently unmake the **Contact**, permanently: the key is
        // collected in one moment and there is no second chance to collect it, only a
        // second **Exchange**. iOS has always done this; Android dropped it on a
        // confirmed overwrite from a keyless card.
        val incoming =
            if (friend.publicKey.isNullOrBlank()) friend.copy(publicKey = held?.publicKey) else friend
        val next = current.filterNot { it.setlistfm.equals(friend.setlistfm, ignoreCase = true) } + incoming
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

    /** A gig-invite deep link a contact sent: add their gig to my plans, same as pasting its link. */
    fun handleGigInvite(uri: Uri) {
        gigIdFromInvite(uri)?.let { addPlannedGig(it) }
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
                // The same runaway guard the shared-concerts lookup uses, named once.
                val shows = attendedConcerts(friend.setlistfm, maxPages = TimelineLogic.ATTENDED_PAGE_CAP)
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
                // The intersection and its paging cap are the logic layer's; see
                // TimelineLogic.ATTENDED_PAGE_CAP for what raising it would cost.
                val shared = logic.sharedConcerts(me, friend.setlistfm)
                _state.update {
                    // total == size so loadMoreSetlists() won't try to paginate this list.
                    it.copy(setlists = shared, setlistsTotal = shared.size, setlistsLoading = false)
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    // --- Exchange (meeting someone in person) + two-timeline comparison ---

    /**
     * My own card as a followed line, for the Nearby fast path. Blank username = nothing
     * to give.
     *
     * No public key here: Nearby's endpoint name is capped at 131 bytes total
     * (`NearbyNameLimitProbe.NEARBY_ENDPOINT_NAME_LIMIT`) with silent overflow, and a
     * base64 ECDSA P-256 SubjectPublicKeyInfo alone is already ~124 of those. The key
     * still reaches a Contact — over BLE's [myProbeCard] (ample GATT-read room) or a
     * shared QR/deep link — both unconstrained by Nearby's advert-sized budget.
     */
    private fun myCard(): Friend? = _state.value.mySetlistFmUser.trim()
        .ifBlank { null }
        ?.let { Friend(setlistfm = it, name = it) }

    /** The same card as a BLE payload: adds the public key #28 makes the identity. */
    private fun myProbeCard(): ProbeCard? = _state.value.mySetlistFmUser.trim()
        .ifBlank { null }
        ?.let { ProbeCard(name = it, publicKey = contactIdentityPublicKeyBase64(), setlistfm = it) }

    /**
     * Opens the Exchange: start every radio in parallel and collect whoever turns up.
     * People appear as they come into range, so the list is a live view of the room.
     */
    fun startExchange() {
        val me = myCard()
        val card = myProbeCard()
        if (me == null || card == null) {
            _state.update {
                it.copy(discovering = false, error = "Set your setlist.fm username first — it's the card you hand over.")
            }
            return
        }
        _state.update { it.copy(discovering = true, exchangePeers = emptyList(), connectingWith = null) }
        exchange.start(me, card)
    }

    /** Pulled down on the exchange screen: drop everything and listen again. */
    fun restartExchange() {
        val me = myCard() ?: return
        val card = myProbeCard() ?: return
        _state.update { it.copy(discovering = true, exchangePeers = emptyList()) }
        exchange.restart(me, card)
    }

    fun stopExchange() {
        exchange.stop()
        _state.update { it.copy(discovering = false, exchangePeers = emptyList(), connectingWith = null) }
    }

    fun exchangePermissions(): List<String> = exchange.requiredPermissions()

    /**
     * Bring a peer onto my timeline: the "row → Connecting with dizzi90 → connected"
     * sequence. On the Nearby path the card is already in hand and the middle is
     * zero-length; on BLE it connects and reads first. A BLE failure clears the
     * connecting state and leaves the radios running, so the QR offer stays available
     * rather than the tap landing on a dead end.
     */
    fun connectWith(peer: ExchangePeer) {
        _state.update { it.copy(connectingWith = peer.name) }
        exchange.connect(peer) { friend ->
            if (friend == null) {
                // Back to the live list — the radios never stopped, and the QR offer is
                // already on screen. A dangling snackbar (this screen has no host) would
                // only resurface on the next one.
                _state.update { it.copy(connectingWith = null) }
                return@connect
            }
            viewModelScope.launch { bringIn(friend) }
        }
    }

    /**
     * The landing an Exchange ends on, whichever side tapped: persist, say it happened,
     * draw the line, and stop the radios — holding a card is the end of looking.
     */
    private suspend fun bringIn(friend: Friend) {
        // Persist the friend before loading, or the load runs against the old list.
        addFriendNow(friend)
        // A card that would change someone I already hold has written nothing and left a
        // question open (#188). Landing anyway would report a swap that did not happen —
        // and stopping the radios mid-exchange is exactly what a hostile write wants.
        if (_state.value.friendConflict != null) return
        _state.update { it.copy(justConnected = true, connectingWith = null) }
        loadFriendTimelines()
        exchange.stop()
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

    /**
     * Hide or show one **Line** in the weave. The gesture is its own undo, so there is
     * one entry point and no separate restore. A new set each time, so remember() sees it.
     */
    fun toggleLineHidden(setlistfm: String) = _state.update {
        it.copy(
            hiddenLines = if (setlistfm in it.hiddenLines) it.hiddenLines - setlistfm
            else it.hiddenLines + setlistfm,
        )
    }

    fun openFestival(key: String) = _state.update {
        it.copy(openFestivals = it.openFestivals + key)
    }

    /** The gig behind a link, wherever it is already loaded — mine or any lane's. */
    fun knownGig(id: String): FmSetlist? =
        _state.value.setlists.firstOrNull { it.id == id }
            ?: _state.value.showsByFriend.values.firstNotNullOfOrNull { shows ->
                shows.firstOrNull { it.id == id }
            }

    /**
     * Asks setlist.fm whether the unidentified evenings on the timeline belong to a
     * **Festival**. The rule itself — which evenings, what counts as already asked, and
     * that the answers are stored — lives in the logic layer; this is the screen's
     * caller of it.
     */
    fun resolveFestivals() {
        val s = _state.value
        viewModelScope.launch {
            // Two passes rather than one concatenated list, so a night ahead and a
            // night behind can never be read as one evening. The future lane grows its
            // own Sections (#134) and they want identities too.
            val found = logic.resolveFestivals(s.setlists, s.festivals)
            val alsoAhead = logic.resolveFestivals(
                plannedLane(s.plannedGigs, s.attendanceByGig),
                found,
            )
            _state.update { it.copy(festivals = it.festivals + alsoAhead) }
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

    /**
     * Re-fetches the open show from setlist.fm. The one thing that changes under
     * you here is the setlist itself — you log a night, go and type the songs in
     * on the site, come back. Refreshes in place: the cached spine keeps its
     * order and every other night untouched.
     */
    fun refreshSelectedSetlist() {
        val open = _state.value.selectedSetlist ?: return
        // A local Gig's id is this app's, not setlist.fm's — asking them for it is a
        // guaranteed 404 dressed up as an error the user can do nothing about. The
        // way a local night gets a real record is adoption, not refresh.
        if (open.isLocal()) return
        if (_state.value.setlistsLoading) return
        _state.update { it.copy(setlistsLoading = true) }
        viewModelScope.launch {
            try {
                val fresh = setlistFm.setlist(open.id)
                val setlists = _state.value.setlists.map { if (it.id == fresh.id) fresh else it }
                // A gig I'm going to lives in its own list, so refreshing one has to
                // write back there — otherwise the night's setlist appears on screen
                // and is gone again on the next launch. Provenance is untouched:
                // songs landing is setlist.fm filling a record in, not evidence I went.
                val wasPlanned = _state.value.plannedGigs.any { it.id == fresh.id }
                _state.update {
                    it.copy(
                        setlists = setlists,
                        plannedGigs = if (wasPlanned) {
                            it.plannedGigs.map { g -> if (g.id == fresh.id) fresh else g }
                        } else {
                            it.plannedGigs
                        },
                        selectedSetlist = fresh,
                        setlistsLoading = false,
                    )
                }
                if (wasPlanned) timelines.savePlanned(fresh)
                val user = _state.value.userQuery.trim()
                if (user.isNotEmpty()) timelines.save(shows = mapOf(user to setlists))
            } catch (e: Exception) {
                // A refresh is optional freshness, never a fatal operation: the night
                // is already on screen from cache, with its artist, venue and date.
                // `fail` sets the global error, and doing that here tore the screen up
                // mid-gesture — the pull's own fling was still running, which is how a
                // 404 on a 1985 setlist came back as "measure is called on a
                // deactivated node". A notice says what happened and changes nothing.
                //
                // The id and code are logged because this only ever fails in the field,
                // on someone else's phone, where there is no other way to find out
                // which night and which status it was.
                android.util.Log.w("StationToStation", "refresh failed for setlist ${open.id}: ${e.message}")
                _state.update {
                    it.copy(
                        setlistsLoading = false,
                        notice = "setlist.fm didn't have that one just now — showing what's saved.",
                    )
                }
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

    // --- Gigs I'm going to ---

    /**
     * Furthest-future first, which is the same order the attended rows below already
     * use: up is always later, and a planned gig is not an exception to that.
     */
    private fun sortedPlanned(gigs: List<FmSetlist>): List<FmSetlist> =
        gigs.sortedByDescending { it.localDate() }

    /**
     * Adds a gig I'm going to, from whatever was pasted off setlist.fm — the page
     * url or the bare id.
     *
     * Fetched by id, never searched. setlist.fm's search index stops about a day
     * out (see #29), so a show weeks away cannot be found by artist, venue or date;
     * it can only be asked for by the id sitting in the url of the page the user
     * was on when they pressed "I'll be there".
     */
    fun addPlannedGig(linkOrId: String) {
        val id = parseSetlistId(linkOrId)
        if (id == null) {
            _state.update { it.copy(error = "That doesn't look like a setlist.fm gig link.") }
            return
        }
        if (_state.value.plannedGigs.any { it.id == id }) return
        _state.update { it.copy(planningLoading = true) }
        viewModelScope.launch {
            try {
                val gig = setlistFm.setlist(id)
                // Saved before the state update, not after, because the claim the lane
                // filters on comes back from the save. The old order left this path
                // with the same hole as the typed-in one.
                val attendance = timelines.savePlanned(gig)
                _state.update {
                    it.copy(
                        plannedGigs = sortedPlanned(it.plannedGigs.filterNot { g -> g.id == gig.id } + gig),
                        attendanceByGig = it.attendanceByGig + (gig.id to attendance),
                        planningLoading = false,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(planningLoading = false) }
                fail(e)
            }
        }
    }

    /**
     * A gig I'm going to, typed in: who is playing, where, and when.
     *
     * **The objection that kept this a paste box is obsolete.** `AddPlannedGigDialog`
     * defended taking only a setlist.fm link on two grounds. The first still holds —
     * setlist.fm's search index stops about a day out (#29), so a future gig cannot be
     * *found*. The second, that typing the details in "would invent a second record for
     * a gig setlist.fm already has", has not been true since the **Bill** shipped:
     * `markActPlayed` mints local **Gig**s for nights setlist.fm has never heard of, and
     * `adoptSetlistId` moves one onto the vendor id when setlist.fm catches up, with
     * every photo, offset, calendar link and playlist intact. The collision the comment
     * described is one the codebase learned to resolve two features ago.
     *
     * **No attendance is written**, which is the whole difference from [addLocalGig].
     * `savePlanned` records `PLANNED` for a gig with no claim on it, and a night I have
     * not been to yet has no claim to make. Writing `ATTENDED` here would be the app
     * asserting I was somewhere I have not been.
     */
    fun addPlannedGigByHand(artist: String, venue: String, date: String) {
        val night = parseFmDate(date)
        if (artist.isBlank() || night == null) {
            _state.update { it.copy(error = "A night needs who is playing and a date as dd-MM-yyyy.") }
            return
        }
        viewModelScope.launch {
            val gigId = timelines.createLocalGig(fmDate(night), artist.trim(), venue.trim())
            val gig = localGigSetlist(gigId, artist.trim(), night, venue.trim(), city = "")
            // The claim goes into state as well as onto disk. `plannedLane` filters on
            // it, so a gig added without it was written correctly and then drawn by
            // nothing — the night appeared only after a restart, which reads as Add
            // having done nothing at all.
            val attendance = timelines.savePlanned(gig)
            _state.update {
                it.copy(
                    plannedGigs = sortedPlanned(it.plannedGigs + gig),
                    attendanceByGig = it.attendanceByGig + (gig.id to attendance),
                    artistSuggestions = emptyList(),
                )
            }
        }
    }

    /**
     * Spellings for the artist name being typed, from MusicBrainz.
     *
     * Debounced rather than rate-limited: MusicBrainz asks for no more than a request a
     * second, and a search-as-you-type field would otherwise send one per keystroke.
     * Cancelling the previous job is also what keeps the answers in order — without it a
     * slow reply for "ka" can land after a fast one for "kaizers" and replace it.
     *
     * Failures are swallowed to an empty list on purpose. This is a prompt; a person who
     * is offline can still type the name, and an error snackbar for a suggestion that
     * did not arrive would be nagging about a service they did not ask to use.
     */
    fun suggestArtists(query: String) {
        artistSearch?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(artistSuggestions = emptyList()) }
            return
        }
        artistSearch = viewModelScope.launch {
            delay(350)
            val hits = runCatching { musicBrainz.searchArtists(query) }.getOrDefault(emptyList())
            _state.update { it.copy(artistSuggestions = hits) }
        }
    }

    /** The typed name was replaced by a picked one, so the list has done its job. */
    fun clearArtistSuggestions() {
        artistSearch?.cancel()
        _state.update { it.copy(artistSuggestions = emptyList()) }
    }

    /**
     * A night entered by hand — the zero-account floor's one door (#225).
     *
     * Nothing here is new machinery. `createLocalGig` has minted **Gig**s with no
     * setlist.fm id since the **Bill** shipped, and `localGigSetlist` has been
     * dressing them as an `FmSetlist` for every screen to draw. The floor was
     * always real; what was missing was a way onto it, because both affordances on
     * the empty spine led to setlist.fm.
     *
     * **Attendance is ATTENDED, never CHECKED_IN.** Typing a night in is a claim
     * about the past made now; a check-in is a claim the phone corroborated at the
     * venue on the night. Recording the two as the same thing would make the
     * provenance the Room shows a lie, which is the one thing this path must not do.
     *
     * A blank venue stays blank rather than becoming "": an unknown room is not a
     * place two gigs have in common, and `localGigSetlist` is careful about that.
     */
    fun addLocalGig(artist: String, venue: String, date: String) {
        val night = parseFmDate(date)
        if (artist.isBlank() || night == null) {
            _state.update { it.copy(error = "A night needs who played and a date as dd-MM-yyyy.") }
            return
        }
        viewModelScope.launch {
            val gigId = timelines.createLocalGig(fmDate(night), artist.trim(), venue.trim())
            val gig = localGigSetlist(gigId, artist.trim(), night, venue.trim(), city = "")
            val attendance = StoredAttendance(provenance = StoredAttendance.Provenance.ATTENDED)
            timelines.savePlanned(gig)
            timelines.saveAttendance(gigId, attendance)
            _state.update {
                it.copy(
                    plannedGigs = sortedPlanned(it.plannedGigs + gig),
                    attendanceByGig = it.attendanceByGig + (gigId to attendance),
                )
            }
        }
    }

    /**
     * A calendar event was just created for a gig; remember its URI. Presence of the
     * URI is what flips the swipe from "add to calendar" to "invite a friend" and
     * makes the tappable link appear, so this is what graduates the leaf. Persisted
     * so both survive a cold start.
     */
    fun markCalendarAdded(gigId: String, eventUri: String) {
        _state.update { it.copy(calendarEventByGig = it.calendarEventByGig + (gigId to eventUri)) }
        viewModelScope.launch { timelines.markCalendarAdded(gigId, eventUri) }
    }

    // --- Bills: a festival that isn't on setlist.fm and can't be yet (#34, #93) ---

    /**
     * Puts a **Bill** on the wall, then — and only if there is signal — goes looking
     * for each **Act**'s recent setlists so the field has something to tick off.
     *
     * The order matters and is the whole point: the **Bill** is written *first* and
     * unconditionally, so entering a lineup works with the radio off. The fetch is a
     * best-effort enrichment that runs while you still have wifi, because the one
     * place it definitely will not run is inside the enclosure.
     */
    fun addBill(name: String, city: String, from: String, to: String, lineup: String) {
        val acts = parseLineup(lineup)
        if (name.isBlank() || acts.isEmpty()) {
            _state.update { it.copy(error = "A bill needs a name and at least one act.") }
            return
        }
        val bill = StoredBill(
            id = java.util.UUID.randomUUID().toString(),
            name = name.trim(),
            city = city.trim(),
            from = from.trim(),
            to = to.trim(),
            acts = acts,
        )
        _state.update { it.copy(bills = it.bills + bill) }
        viewModelScope.launch {
            timelines.saveBill(bill)
            fetchCandidates(bill.id)
        }
    }

    fun removeBill(billId: String) {
        _state.update { it.copy(bills = it.bills.filterNot { b -> b.id == billId }) }
        viewModelScope.launch { timelines.removeBill(billId) }
    }

    /**
     * Fills in every unanswered **Act**'s candidate songs from setlist.fm.
     *
     * Opportunistic, never scheduled: the timeline calls this when a **Bill** is
     * *opened* and it holds acts nobody has an answer for. That is the same instinct
     * as the check-in's single foreground fix — computed because a screen is being
     * looked at, never a background job and never a timer. In the enclosure it will
     * fail, cost one round of failed requests, and stop; re-opening the Bill is the
     * retry, which is a gesture the owner makes when they have a reason to.
     *
     * An act is skipped once setlist.fm has *answered* about it, empty or not (see
     * [StoredAct.tried]) — so a small local act nobody has ever logged costs one
     * lookup in its life, while an act missed for want of signal is asked again.
     *
     * ponytail: sequential, one artist at a time. A lineup is a dozen names and this
     * runs on a screen open. Parallelise if a hundred-act bill ever shows up.
     */
    fun fetchCandidates(billId: String) {
        if (_state.value.billFetching != null) return
        val pending = _state.value.bills.firstOrNull { it.id == billId }?.acts.orEmpty()
            .withIndex().filter { (_, a) -> !a.tried && a.candidates.isEmpty() }
        if (pending.isEmpty()) return
        _state.update { it.copy(billFetching = billId) }
        viewModelScope.launch {
            try {
                for ((i, act) in pending) {
                    val answer = runCatching {
                        // The first exact-name match, and there may be five of them —
                        // which artist this landed on is recorded and shown, because a
                        // pool whose source is unnamed cannot be distrusted.
                        val artist = setlistFm.searchArtists(act.name).artist
                            .firstOrNull { it.name.equals(act.name, ignoreCase = true) }
                        artist to candidateSongs(
                            artist?.let { setlistFm.artistSetlists(it.mbid).setlist }.orEmpty(),
                        )
                        // A thrown request is *no answer*: leave the act untried so the
                        // next open asks again. Only a reply — including "no such
                        // artist" — settles the question.
                    }.getOrNull() ?: continue
                    val (artist, songs) = answer
                    // Re-read each time: the field may have dated this act in between,
                    // and a stale snapshot written back would undo it.
                    editBill(billId) { b ->
                        b.copy(
                            acts = b.acts.mapIndexed { j, a ->
                                if (j != i) a else a.copy(
                                    candidates = songs,
                                    matchedArtist = artist?.let { artistLabel(it.name, it.disambiguation) }.orEmpty(),
                                    mbid = artist?.mbid.orEmpty(),
                                    tried = true,
                                )
                            },
                        )
                    }
                }
            } finally {
                _state.update { it.copy(billFetching = null) }
            }
        }
    }

    /**
     * The pool came from the wrong band. Name one song you *know* they play, and the
     * right one is found by it.
     *
     * A picker would ask "which of these five identically-named artists?", which
     * nobody standing in a field can answer — the names are identical, that is the
     * entire problem. "Name a song you know they play" is always answerable and is
     * *meaningful*: it is the fact that actually distinguishes them.
     *
     * Done by pulling each same-named artist's recent setlists and looking, because
     * there is no other way: `/search/setlists` has no song parameter (verified, see
     * [playsSong]). Cost is one extra request per namesake, on a deliberate tap.
     *
     * The named song is used to *identify* and is never written into the **Log**.
     * Naming a song a band is known for is not a claim that they played it tonight,
     * and quietly recording it as one would be the exact fabrication this whole
     * feature is built to avoid.
     */
    fun disambiguateAct(gigId: String, song: String) {
        if (song.isBlank() || _state.value.billFetching != null) return
        val bill = _state.value.bills.firstOrNull { b -> b.acts.any { it.gigId == gigId } } ?: return
        val index = bill.acts.indexOfFirst { it.gigId == gigId }
        val act = bill.acts[index]
        _state.update { it.copy(billFetching = bill.id) }
        viewModelScope.launch {
            try {
                val namesakes = runCatching {
                    setlistFm.searchArtists(act.name).artist
                        .filter { it.name.equals(act.name, ignoreCase = true) }
                }.getOrNull() ?: run {
                    _state.update { it.copy(error = "Couldn't reach setlist.fm to check.") }
                    return@launch
                }
                for (artist in namesakes) {
                    val sets = runCatching { setlistFm.artistSetlists(artist.mbid).setlist }
                        .getOrNull() ?: continue
                    if (!playsSong(sets, song)) continue
                    val label = artistLabel(artist.name, artist.disambiguation)
                    val pool = candidateSongs(sets)
                    editBill(bill.id) { b ->
                        b.copy(
                            acts = b.acts.mapIndexed { j, a ->
                                if (j != index) a else a.copy(
                                    candidates = pool,
                                    matchedArtist = label,
                                    mbid = artist.mbid,
                                    tried = true,
                                )
                            },
                        )
                    }
                    // The label, never the bare name. The name is the ambiguous string
                    // — five bands answer to it — so "songs are from Silent Majority"
                    // confirms nothing at all. What distinguishes them is the
                    // disambiguation, and the song count says the swap actually landed.
                    _state.update {
                        it.copy(notice = "$label — ${pool.size} songs, from \"${song.trim()}\".")
                    }
                    return@launch
                }
                // Honest dead end. The pool is left exactly as it was rather than
                // cleared: a pool that might be wrong still beats no pool, and it is
                // labelled with whose it is.
                _state.update {
                    it.copy(
                        error = "No band called ${act.name} has \"${song.trim()}\" logged " +
                            "on setlist.fm.",
                    )
                }
            } finally {
                _state.update { it.copy(billFetching = null) }
            }
        }
    }

    /**
     * An **Act** played, tonight — the field gesture, and the cheapest thing in the
     * app. It mints the local **Gig** the act becomes, dates it, and records that I
     * was there with the strength a check-in carries, because tapping this is
     * something only a person standing in front of the stage does.
     *
     * The date is [billNight]'s, not the calendar's: at half one in the morning the
     * act you just watched played yesterday. But only while the clock is still inside
     * the **Bill**'s range — [gigNight] is what decides, and it returns null rather
     * than date a night at a festival that had not opened or had already ended. A
     * night the person picked off the range arrives as [chosen] and wins over the
     * clock, which by then knows nothing.
     *
     * A picked night is [StoredAttendance.Provenance.ATTENDED], not `CHECKED_IN`:
     * answering "which night was that?" days later is a recollection, and a check-in
     * is a thing only someone standing in front of the stage does.
     *
     * **The venue is left blank, not filled with the Bill's name (#128).** A poster
     * says which festival, never which room, and the room is one third of ADR-0002's
     * correspondence key — so writing "Ringnes Festival 2026" there does not merely
     * look wrong, it stops this night ever recognising itself in the setlist.fm record
     * of the same night, whose venue is "Verandaen, Skotbu". Blank is the honest state
     * and it resolves itself: `withGigFacts` fills the venue in the moment setlist.fm
     * describes this night. Until then the **Node** reads as the **Bill**'s city.
     */
    fun markActPlayed(
        billId: String,
        actIndex: Int,
        chosen: LocalDate? = null,
        now: LocalDateTime = LocalDateTime.now(),
    ) {
        val bill = _state.value.bills.firstOrNull { it.id == billId } ?: return
        val act = bill.acts.getOrNull(actIndex) ?: return
        if (act.gigId != null) return
        val night = gigNight(bill, chosen, now) ?: return
        viewModelScope.launch {
            val gigId = timelines.createLocalGig(fmDate(night), act.name, venue = "")
            val gig = localGigSetlist(gigId, act.name, night, venue = "", city = bill.city)
            timelines.savePlanned(gig)
            val attendance = if (chosen == null) {
                StoredAttendance(
                    provenance = StoredAttendance.Provenance.CHECKED_IN,
                    checkedInAt = System.currentTimeMillis(),
                )
            } else {
                StoredAttendance(provenance = StoredAttendance.Provenance.ATTENDED)
            }
            timelines.saveAttendance(gigId, attendance)
            _state.update {
                it.copy(
                    plannedGigs = sortedPlanned(it.plannedGigs + gig),
                    attendanceByGig = it.attendanceByGig + (gigId to attendance),
                )
            }
            editBill(billId) { b ->
                b.copy(acts = b.acts.mapIndexed { j, a -> if (j == actIndex) a.copy(gigId = gigId) else a })
            }
        }
    }

    /**
     * The name on the poster, corrected — and asked about again.
     *
     * A lineup is copied off a wall, and the wall is not authoritative about
     * spelling: the history says *The* Silent Majority where the programme says
     * Silent Majority, and `fetchCandidates` matches with exact `equals`, so one
     * character is the difference between a song pool and "no setlist.fm history".
     * Rather than guess at normalising names nobody has seen, let the person who is
     * standing in front of the stage fix it and ask upstream again.
     *
     * Clearing [StoredAct.tried] is the point: it is what makes the act eligible for
     * a lookup at all, and the pool, matched artist and mbid go with it because they
     * describe an answer to the *old* name.
     *
     * The night, if the act already has one, is deliberately untouched — it is a
     * record of what happened, not a line on a poster.
     */
    fun renameAct(billId: String, actIndex: Int, name: String) {
        val corrected = name.trim()
        val act = _state.value.bills.firstOrNull { it.id == billId }?.acts?.getOrNull(actIndex) ?: return
        if (corrected.isBlank() || corrected == act.name) return
        viewModelScope.launch {
            editBill(billId) { b ->
                b.copy(
                    acts = b.acts.mapIndexed { j, a ->
                        if (j != actIndex) a
                        else a.copy(name = corrected, candidates = emptyList(), matchedArtist = "", mbid = "", tried = false)
                    },
                )
            }
            fetchCandidates(billId)
        }
    }

    /**
     * An **Act** that never was on the poster. Added already dated, because a
     * **Surprise** is only ever discovered after it has happened — there is no state
     * in which an unannounced act is pending.
     *
     * Dated by the same rule as any other act, and refused outright when there is no
     * honest date to give it: a hand-typed act is the easiest place to fabricate a
     * night, not an exception to the invariant.
     */
    fun addSurpriseAct(
        billId: String,
        name: String,
        chosen: LocalDate? = null,
        now: LocalDateTime = LocalDateTime.now(),
    ) {
        if (name.isBlank()) return
        val bill = _state.value.bills.firstOrNull { it.id == billId } ?: return
        if (gigNight(bill, chosen, now) == null) return
        val at = bill.acts.size
        viewModelScope.launch {
            editBill(billId) { it.copy(acts = it.acts + StoredAct(name = name.trim(), surprise = true)) }
            markActPlayed(billId, at, chosen, now)
        }
    }

    /**
     * A mistap, undone — and what "undone" means depends on where the act came from.
     *
     * An act off the **Bill** goes back to having no night: the poster still says it
     * is playing, so there is something to return to. A **Surprise** was typed by
     * hand and has nothing to return to, so the whole act goes with the night.
     *
     * The **Gig** is deleted outright rather than merely unplanned. `removePlanned`
     * rightly refuses to erase a check-in, which is exactly what used to strand an
     * attendance claim for a night nothing pointed at any more.
     */
    fun unmarkAct(billId: String, actIndex: Int) {
        val act = _state.value.bills.firstOrNull { it.id == billId }?.acts?.getOrNull(actIndex) ?: return
        val gigId = act.gigId
        if (gigId == null && !act.surprise) return
        // An undated Surprise — a typo caught before it was ever tapped — has no gig
        // to strip, only an act to drop.
        if (gigId != null) {
            _state.update {
                it.copy(
                    plannedGigs = it.plannedGigs.filterNot { g -> g.id == gigId },
                    setlists = it.setlists.filterNot { g -> g.id == gigId },
                    attendanceByGig = it.attendanceByGig - gigId,
                    logsByGig = it.logsByGig - gigId,
                )
            }
        }
        viewModelScope.launch {
            // Refused when the night has media on it — that is not a mistap, and the
            // photos are irreplaceable. The act then simply stops being on the Bill
            // and the gig reappears as an ordinary night above today, still reachable.
            val gone = gigId == null || timelines.deleteGig(gigId)
            editBill(billId) { b ->
                b.copy(
                    acts = if (act.surprise && gone) {
                        b.acts.filterIndexed { j, _ -> j != actIndex }
                    } else {
                        b.acts.mapIndexed { j, a -> if (j == actIndex) a.copy(gigId = null) else a }
                    },
                )
            }
            if (!gone) {
                _state.update {
                    it.copy(notice = "That night has photos on it, so it's been kept.")
                }
            }
        }
    }

    /**
     * How many photographs a delete would destroy — the ones this app holds the
     * only copy of. Zero means every picture on the night also lives in the
     * gallery, so removing the night costs nothing that cannot be found again.
     *
     * The screen asks this to decide whether to stop and ask.
     */
    fun photosLostByDeleting(gigId: String): Int =
        _state.value.mediaBySetlist[gigId].orEmpty().count { photos.ownsBytes(it.ref) }

    /**
     * A night deleted from its own screen — the deliberate one, as opposed to
     * `unmarkAct`'s undo of a mistap.
     *
     * It exists because `unmarkAct` was the *only* route to [TimelineStore.deleteGig]
     * and it needs an **Act** on a live **Bill** to reach a gig. Remove the Bill and
     * every night its acts minted is stranded: nothing points at it and nothing can
     * delete it. Deletion must not depend on the poster still being up.
     *
     * Unlike the undo this takes the media with it, because someone reading the
     * night's own screen can see what is on it. The screen is responsible for asking
     * first when [photosLostByDeleting] says bytes would go — a pointer into the
     * gallery is not worth a dialog, the only copy of a photograph is.
     */
    fun deleteLocalGig(gigId: String) {
        val media = _state.value.mediaBySetlist[gigId].orEmpty()
        _state.update {
            it.copy(
                plannedGigs = it.plannedGigs.filterNot { g -> g.id == gigId },
                setlists = it.setlists.filterNot { g -> g.id == gigId },
                attendanceByGig = it.attendanceByGig - gigId,
                logsByGig = it.logsByGig - gigId,
                mediaBySetlist = it.mediaBySetlist - gigId,
                playlistsBySetlist = it.playlistsBySetlist - gigId,
                calendarEventByGig = it.calendarEventByGig - gigId,
                selectedSetlist = null,
                // An act still pointing at a deleted night would offer an undo for
                // something that is gone. The poster keeps the act; it just stops
                // claiming a gig, exactly as unmarkAct leaves it.
                bills = it.bills.map { b ->
                    b.copy(acts = b.acts.map { a -> if (a.gigId == gigId) a.copy(gigId = null) else a })
                },
            )
        }
        viewModelScope.launch {
            if (timelines.deleteGig(gigId, withMedia = true)) {
                media.forEach { photos.deleteOwnedBytes(it.id, it.ref) }
                _state.value.bills.forEach { timelines.saveBill(it) }
            }
        }
    }

    // --- The Log: what I saw, as opposed to what setlist.fm publishes ---

    fun logFor(gigId: String): StoredLog = _state.value.logsByGig[gigId] ?: StoredLog()

    /**
     * Edits my **Log** of a night. Asserted, never derived: the candidate pool is a
     * prompt and only a tap is a claim, so a song I *think* they played never becomes
     * a song they played by inaction.
     *
     * Editing songs never touches [StoredLog.closed]. Adding a song days later is
     * ordinary — the **Log** is the app's own record and stays editable forever —
     * and saying "that was the whole set" is a separate, deliberate sentence.
     *
     * Four edits rather than one "here is the new list", because a **Log** now carries
     * a **Remembered Line** beside each entry (#126) and a whole-list replacement
     * cannot say whether the third entry was deleted or renamed. The intent is what
     * keeps the two lists parallel, and [StoredLog] is the one place that does it.
     */
    fun addToLog(gigId: String, song: String) = writeLog(gigId) { it.adding(song) }

    fun removeFromLog(gigId: String, index: Int) = writeLog(gigId) { it.removingAt(index) }

    /**
     * A title replaces what was written, and what was written is kept beneath it. The
     * candidate was ranked, never chosen: only this tap decides.
     */
    fun correctLogEntry(gigId: String, index: Int, title: String) =
        writeLog(gigId) { it.correctingAt(index, title) }

    /** The way back. A wrong correction must not be a one-way door. */
    fun restoreLogEntry(gigId: String, index: Int) = writeLog(gigId) { it.restoringAt(index) }

    /**
     * The only thing that may **Close** a **Log**, and it is a person saying so. Not
     * publishing, not a refetch, not a song count: setlist.fm has nowhere to keep
     * this bit, so it never leaves the device and nothing coming back can set it.
     */
    fun setLogClosed(gigId: String, closed: Boolean) = writeLog(gigId) { it.copy(closed = closed) }

    private fun writeLog(gigId: String, edit: (StoredLog) -> StoredLog) {
        val updated = edit(logFor(gigId))
        _state.update { it.copy(logsByGig = it.logsByGig + (gigId to updated)) }
        viewModelScope.launch { timelines.saveLog(gigId, updated) }
    }

    /**
     * The light switch, at the outermost rung of my own **Line** (#145).
     *
     * Toggling rather than travelling: a light is not somewhere you go, so the motion
     * that turns it on turns it off and there is no way to be stranded under it.
     * Leaving it always drops the withheld placeholders, so the light comes on faithful
     * every time — the primary question is what a **Contact** sees.
     */
    fun toggleContactLight() = _state.update {
        it.copy(contactLight = !it.contactLight, showWithheld = false)
    }

    fun setShowWithheld(show: Boolean) = _state.update { it.copy(showWithheld = show) }

    /**
     * The artist's own songs, for correcting a **Log** entry (#126).
     *
     * Asked for only when the correction panel opens — a catalogue nobody is about to
     * read is a request nobody asked for — and asked once: the answer is kept forever, so
     * the second correction on the same night is offline and instant.
     *
     * Failure is silent and leaves the record alone. The free-text field is always
     * present, so no catalogue is a smaller panel rather than a broken one.
     */
    fun fetchCatalogue(mbid: String) {
        if (mbid.isBlank()) return
        if (_state.value.catalogueByArtist.containsKey(mbid)) return
        if (_state.value.catalogueFetching != null) return
        _state.update { it.copy(catalogueFetching = mbid) }
        viewModelScope.launch {
            val titles = runCatching { musicBrainz.catalogue(mbid) }.getOrDefault(emptyList())
            _state.update {
                it.copy(
                    catalogueFetching = null,
                    catalogueByArtist =
                    if (titles.isEmpty()) it.catalogueByArtist
                    else it.catalogueByArtist + (mbid to titles),
                )
            }
            if (titles.isNotEmpty()) timelines.saveCatalogue(mbid, titles)
        }
    }

    /**
     * Moves one of my photographs into [band] at [index] — the drag between bands, and
     * the reorder within one, which are the same operation (#162).
     *
     * A move between bands *is* the change to its **Personal** bit; there is no separate
     * gesture and no night-level grant above it. **Received media** is refused by
     * [moveMedia] rather than here: whose disposition it is belongs with the rule, not
     * with the caller.
     */
    fun moveGigMedia(setlistId: String, mediaId: String, band: Band, index: Int) = setGigMedia(
        setlistId,
        moveMedia(_state.value.mediaBySetlist[setlistId].orEmpty(), mediaId, band, index),
    )

    /**
     * Write, edit or clear my **Note** in one **Band** (#50).
     *
     * At most one of mine per band, so this is an upsert keyed by band rather than by
     * id: the write-line the finger landed on already said which one it means. Two
     * notes in a band would need arranging, arranging would need the handle, and the
     * thing being served is one opinion about one night.
     *
     * **Emptying it removes it.** A note with nothing in it is not something anyone
     * wrote, and leaving an empty record behind would make the shared band claim a
     * contributor who said nothing — which would turn a night green over blank text.
     */
    fun setGigNote(setlistId: String, band: Band, text: String) {
        val had = _state.value.mediaBySetlist[setlistId].orEmpty()
        val personal = band == Band.VAULT
        val mine = had.firstOrNull {
            it.kind == StoredMedia.Kind.NOTE && it.from == null && it.personal == personal
        }
        val written = text.trim()
        setGigMedia(
            setlistId,
            when {
                mine != null && written.isEmpty() -> had.filterNot { it.id == mine.id }
                mine != null -> had.map { if (it.id == mine.id) it.copy(text = written) else it }
                written.isEmpty() -> had
                else -> had + StoredMedia(
                    id = java.util.UUID.randomUUID().toString(),
                    kind = StoredMedia.Kind.NOTE,
                    // When it was written. It is what sorts received notes, and a note
                    // has no camera to ask for anything better.
                    capturedAt = System.currentTimeMillis(),
                    personal = personal,
                    text = written,
                )
            },
        )
    }

    /**
     * Set or unset the **Verdict** on one of my **Notes**.
     *
     * Tapping the one already set passes null, because unset has to stay reachable —
     * it is a real state, and a night I have stopped having an opinion about must not
     * be stuck wearing the one I had.
     */
    fun setGigVerdict(setlistId: String, noteId: String, verdict: String?) {
        val had = _state.value.mediaBySetlist[setlistId].orEmpty()
        // Mine only. A received note's verdict is its sender's statement and is not
        // mine to edit, the same way their photograph is not mine to reposition.
        if (had.none { it.id == noteId && it.from == null }) return
        setGigMedia(setlistId, had.map { if (it.id == noteId) it.copy(verdict = verdict) else it })
    }


    /** The **Act** a local **Gig** was minted from, if it came off a **Bill**. */
    fun actFor(gigId: String): StoredAct? =
        _state.value.bills.firstNotNullOfOrNull { bill -> bill.acts.firstOrNull { it.gigId == gigId } }

    /**
     * The night is now on setlist.fm — someone typed it in, possibly not me. The
     * local **Gig** takes their id and stops being a stub, which is the whole payoff
     * #34 names: only then can it be a **Crossing**.
     *
     * A pasted link rather than a search by artist+date. #34 sketched the search, but
     * the moment this is used is the moment you are looking at the page you just
     * created, so its url is in your hand and matching heuristics are a way to be
     * wrong about which night you meant.
     */
    fun adoptSetlistLink(gigId: String, linkOrId: String) {
        val setlistId = parseSetlistId(linkOrId)
        if (setlistId == null) {
            _state.update { it.copy(error = "That doesn't look like a setlist.fm link.") }
            return
        }
        viewModelScope.launch {
            if (!timelines.adoptSetlistId(gigId, setlistId)) {
                _state.update { it.copy(error = "That night already has a setlist.fm id.") }
                return@launch
            }
            _state.update { it.copy(notice = "Adopted — this night is on setlist.fm now.") }
            // The real record replaces the stub: it has the url, the songs whoever
            // typed them in logged, and an id friends' lines can meet at.
            runCatching { setlistFm.setlist(setlistId) }.getOrNull()?.let { fresh ->
                timelines.savePlanned(fresh)
                _state.update {
                    it.copy(
                        plannedGigs = sortedPlanned(it.plannedGigs.filterNot { g -> g.id == gigId } + fresh),
                        selectedSetlist = if (it.selectedSetlist?.id == gigId) fresh else it.selectedSetlist,
                    )
                }
                // An **Act** holds the id its night was minted with, and the line above
                // just changed the id that night is known by. Left alone the poster
                // points at nothing: the act's "open" leads nowhere, so the songs
                // whoever typed them in are unreachable — and because the timeline
                // hides a ticket only when some act claims its id, the night draws a
                // second time beside the **Bill** it belongs to.
                repointActs(from = gigId, to = fresh.id)
            }
        }
    }

    /**
     * Moves every **Act**'s pointer from one gig id to another — the other half of
     * adoption, which renames a night without moving any of its data.
     *
     * Touches only the **Bills** that actually point at [from], so publishing one act
     * doesn't rewrite a festival's whole poster.
     */
    private suspend fun repointActs(from: String, to: String) {
        val touched = _state.value.bills.filter { b -> b.acts.any { it.gigId == from } }
        if (touched.isEmpty()) return
        val updated = touched.map { b ->
            b.copy(acts = b.acts.map { a -> if (a.gigId == from) a.copy(gigId = to) else a })
        }
        _state.update { s ->
            s.copy(bills = s.bills.map { b -> updated.firstOrNull { it.id == b.id } ?: b })
        }
        updated.forEach { timelines.saveBill(it) }
    }

    /** Reads, edits and persists one **Bill**, so state and disk never disagree. */
    private suspend fun editBill(billId: String, edit: (StoredBill) -> StoredBill) {
        val bill = _state.value.bills.firstOrNull { it.id == billId } ?: return
        val updated = edit(bill)
        _state.update { it.copy(bills = it.bills.map { b -> if (b.id == billId) updated else b }) }
        timelines.saveBill(updated)
    }

    /** Forgets a gig I'm not going to after all. */
    fun removePlannedGig(gigId: String) {
        _state.update { it.copy(plannedGigs = it.plannedGigs.filterNot { g -> g.id == gigId }) }
        viewModelScope.launch { timelines.removePlanned(gigId) }
    }

    // --- Check in ---

    /**
     * True if any gig I know about could be checked into right now on the calendar
     * alone. Cheap and pure — it is what decides whether asking for the location
     * permission is warranted at all, so the prompt only ever appears on a night
     * there is actually something to check into.
     */
    fun checkInDue(now: LocalDateTime = LocalDateTime.now()): Boolean =
        _state.value.plannedGigs.any { gig ->
            canCheckInManually(gig, now) && !isCheckedIn(gig.id)
        }

    fun hasLocationPermission(): Boolean = where.hasPermission()

    fun isCheckedIn(gigId: String): Boolean =
        _state.value.attendanceByGig[gigId]?.provenance == StoredAttendance.Provenance.CHECKED_IN

    /**
     * One fix, once, when the timeline is opened: if it puts me at a gig I'm going
     * to tonight, offer to check in. Every failure along the way — permission
     * refused, no fix, no coordinates for the venue, too far away — is silently no
     * offer. Nothing here is retried, scheduled or run in the background.
     *
     * ponytail: linear over the planned gigs, geocoding only the one that passes
     * the city gate. You have a ticket for a handful of nights, not thousands.
     */
    fun offerCheckIn() {
        if (askedToCheckIn) return
        askedToCheckIn = true
        viewModelScope.launch {
            val now = LocalDateTime.now()
            val fix = where.currentFix() ?: return@launch
            val candidates = _state.value.plannedGigs.filterNot { isCheckedIn(it.id) }
            val gig = checkInCandidate(candidates, now, fix) ?: return@launch
            val venue = venueCoords(gig) ?: return@launch
            if (!atVenue(fix, venue)) return@launch
            _state.update { it.copy(checkInOffer = gig) }
        }
    }

    /** One-shot per launch: dismissing an offer must not make it reappear. */
    private var askedToCheckIn = false

    fun dismissCheckInOffer() = _state.update { it.copy(checkInOffer = null) }

    /**
     * The venue's coordinates, geocoded once and kept on the attendance record —
     * the same cache #29 reserved the fields for. Null for a venue the geocoder
     * can't place, which costs this gig its prompt and nothing else.
     */
    private suspend fun venueCoords(gig: FmSetlist): Pair<Double, Double>? {
        _state.value.attendanceByGig[gig.id]?.let { stored ->
            val lat = stored.venueLat
            val lon = stored.venueLon
            if (lat != null && lon != null) return lat to lon
        }
        val query = venueMapsQuery(gig.venue?.name, gig.venue?.city?.name) ?: return null
        val found = where.geocodeVenue(
            listOfNotNull(query, gig.venue?.city?.country?.name).joinToString(", "),
        ) ?: return null
        updateAttendance(gig.id) { it.copy(venueLat = found.first, venueLon = found.second) }
        return found
    }

    /**
     * I am here. Sets the provenance the whole issue exists for, with the moment it
     * happened — evidence of a different strength than setlist.fm's retroactive
     * flag, not a competing record. Not a gate on anything: the peer-attested badge
     * (#30) decorates this entry later, it doesn't replace it.
     */
    fun checkIn(gigId: String) {
        _state.update { it.copy(checkInOffer = null) }
        updateAttendance(gigId) {
            it.copy(
                provenance = StoredAttendance.Provenance.CHECKED_IN,
                checkedInAt = System.currentTimeMillis(),
            )
        }
    }

    /** Writes one gig's attendance to state and disk together, never one without the other. */
    private fun updateAttendance(gigId: String, edit: (StoredAttendance) -> StoredAttendance) {
        val updated = edit(_state.value.attendanceByGig[gigId] ?: StoredAttendance())
        _state.update { it.copy(attendanceByGig = it.attendanceByGig + (gigId to updated)) }
        viewModelScope.launch { timelines.saveAttendance(gigId, updated) }
    }

    fun selectSetlist(setlist: FmSetlist) {
        matchJob?.cancel()
        val artistName = setlist.artist?.name ?: ""
        // A closed **Log** is a setlist. #121 put it plainly — "the app is the source
        // of truth about what was observed and setlist.fm is a publication target" —
        // so a night whose set I said was complete converts like any other, whether or
        // not their record has caught up. Only when *closed*: an open Log is a night
        // still in progress, and offering to make a playlist of the first four songs
        // while the band is still on is not the same gesture.
        //
        // setlist.fm still wins where it has songs. It has the covers and the tape
        // markers, which a typed title cannot carry.
        val songs = setlist.songs().filter { it.name.isNotBlank() }.ifEmpty {
            _state.value.logsByGig[setlist.id]
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
        // Year – Artist – Where. The rule itself is the logic layer's, asserted by
        // the same cases on both platforms — it is the one that drifted before.
        val defaultName = TimelineLogic.playlistName(
            setlist, _state.value.setlists, _state.value.festivals,
        )
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
     * Offers the gig's own keepsakes first — already chosen for this night, so
     * they need no permission and no re-asking — then the gallery's same-night
     * match once that permission is granted. The gallery half is silent when
     * missing: the confirm screen asks for it instead, so a prompt only ever
     * follows a tap.
     */
    fun loadCoverCandidates() {
        val setlist = _state.value.selectedSetlist ?: return
        val date = setlist.localDate() ?: return
        val granted = photos.hasPermission()
        _state.update { it.copy(coverPermissionGranted = granted) }
        viewModelScope.launch {
            _state.update { it.copy(coverLoading = true) }
            val pinned = _state.value.mediaBySetlist[setlist.id].orEmpty().map { Uri.parse(it.ref) }
            val gallery = if (granted) photos.photosFrom(date).map { it.uri } else emptyList()
            val candidates = (pinned + gallery).distinct().map { CoverCandidate(it, photos.preview(it)) }
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
        // A different cover means the frame scrubbed out of the last one is moot.
        if (it.selectedCoverUri == uri) it
        else it.copy(selectedCoverUri = uri, selectedCoverFrameMs = 0L)
    }

    /** Where the scrubber landed on the chosen clip — the frame that becomes the cover. */
    fun setCoverFrame(atMs: Long) = _state.update {
        if (it.selectedCoverFrameMs == atMs) it else it.copy(selectedCoverFrameMs = atMs)
    }

    fun isVideoCover(uri: Uri): Boolean = photos.isVideo(uri)

    suspend fun videoDurationMs(uri: Uri): Long = photos.videoDurationMs(uri)

    suspend fun videoFrameAt(uri: Uri, atMs: Long): Bitmap? = photos.videoFrameAt(uri, atMs)

    /**
     * The Reliver's own pictures pinned to a gig, chosen freely from the system photo
     * picker rather than matched by date — this is "my picture of that night", not the
     * same-night search [loadCoverCandidates] does for a playlist cover.
     */
    fun addGigPhotos(setlistId: String, uris: List<Uri>, band: Band = Band.VAULT) {
        viewModelScope.launch {
            val had = _state.value.mediaBySetlist[setlistId].orEmpty()
            val wanted = uris.filterNot { u -> had.any { it.ref == u.toString() } }
            attach(setlistId, had, wanted.map { it to it }, band)
        }
    }

    /**
     * Same as [addGigPhotos], but for uris fresh out of the system photo picker: those
     * only grant read access for the running process, so they're copied into our own
     * storage first — otherwise the keepsake goes blank the next time the app launches.
     *
     * Kind and capture time are read off the *picked* uri, before the copy: that is
     * the one moment the gallery is guaranteed to answer, which is the whole premise
     * of #97. The copy is what the record points at afterwards.
     */
    fun addPickedGigPhotos(setlistId: String, uris: List<Uri>, band: Band = Band.VAULT) {
        viewModelScope.launch {
            val had = _state.value.mediaBySetlist[setlistId].orEmpty()
            attach(
                setlistId,
                had,
                uris.mapNotNull { picked -> photos.persistCopy(picked)?.let { it to picked } },
                band,
            )
        }
    }

    /**
     * Attaches [wanted] — each a `stored reference to read the facts from` pair,
     * which differ when the app has just copied the picked item into its own
     * storage. Generates both thumbnail tiers first, and drops anything whose
     * durable copy could not be written: an item with no floor under it is a
     * keepsake that will silently empty later, so a failure is said out loud here
     * rather than discovered in 2035.
     *
     * ponytail: sequential, which is the bounded queue — twenty photos at once is
     * the normal case, and one at a time on the IO dispatcher keeps the app
     * responsive without a scheduler. Widen it if attaching a night's worth ever
     * feels slow.
     */
    private suspend fun attach(
        setlistId: String,
        had: List<StoredMedia>,
        wanted: List<Pair<Uri, Uri>>,
        band: Band,
    ) {
        val fresh = mutableListOf<StoredMedia>()
        var failed = 0
        for ((ref, from) in wanted) {
            val id = java.util.UUID.randomUUID().toString()
            if (!photos.generateThumbnails(id, from)) {
                failed++
                continue
            }
            fresh += StoredMedia(
                id = id,
                kind = if (photos.isVideo(from)) StoredMedia.Kind.VIDEO else StoredMedia.Kind.PHOTO,
                ref = ref.toString(),
                capturedAt = photos.capturedAtMs(from),
                // The band the handle was released over *is* the answer. There is no
                // default path into this: every caller names one (#162).
                personal = band == Band.VAULT,
            )
        }
        // Normalised through the bands so a fresh item lands at the end of its own
        // run rather than after somebody else's media.
        if (fresh.isNotEmpty()) setGigMedia(setlistId, bandsOf(had + fresh).let { it.shared + it.received + it.vault })
        if (failed > 0) {
            _state.update {
                it.copy(error = "Couldn't read ${if (failed == 1) "that one" else "$failed of those"} — not attached.")
            }
        }
    }

    fun removeGigPhoto(setlistId: String, uri: Uri) {
        val had = _state.value.mediaBySetlist[setlistId].orEmpty()
        val (gone, kept) = had.partition { it.ref == uri.toString() }
        setGigMedia(setlistId, kept)
        // Removing means removing: the derived copies this app owns go with it.
        viewModelScope.launch { gone.forEach { photos.deleteThumbnails(it.id) } }
    }

    /** Drops a playlist link the app made — for when the playlist itself was deleted
     *  on Spotify, so the pointer to it here is now just dead weight. */
    fun removePlaylist(setlistId: String, url: String) {
        _state.update {
            it.copy(
                playlistsBySetlist = it.playlistsBySetlist +
                    (setlistId to it.playlistsBySetlist[setlistId].orEmpty().filterNot { p -> p.url == url }),
            )
        }
        viewModelScope.launch { timelines.removePlaylist(setlistId, url) }
    }

    /**
     * Where each song of [setlistId] starts in its recording, padded/trimmed to
     * [songCount]. Sized on read rather than trusted from disk: the setlist can be
     * edited on setlist.fm after a night was stamped, and a stored list of the old
     * length would otherwise shift every song's time by one.
     */
    fun songOffsets(mediaId: String?, songCount: Int): List<Long> {
        val stored = mediaId?.let { id ->
            _state.value.mediaBySetlist.values.firstNotNullOfOrNull { media ->
                media.firstOrNull { it.id == id }
            }
        }?.songOffsets.orEmpty()
        return List(songCount) { stored.getOrElse(it) { NOT_STAMPED } }
    }

    /**
     * Records that song [index] starts at [atMs] in the night's recording, or clears
     * it with [NOT_STAMPED].
     *
     * Only this one song moves. The recording and the setlist need not hold the same
     * songs — a clip setlist.fm left out sits in the gap between two stamps — so
     * nothing may be inferred about its neighbours from one stamp.
     */
    fun stampSong(mediaId: String, index: Int, atMs: Long, songCount: Int) {
        val offsets = songOffsets(mediaId, songCount).toMutableList()
        if (index !in offsets.indices) return
        offsets[index] = atMs
        _state.update {
            it.copy(
                mediaBySetlist = it.mediaBySetlist.mapValues { (_, media) ->
                    media.map { m -> if (m.id == mediaId) m.copy(songOffsets = offsets) else m }
                },
            )
        }
        viewModelScope.launch { timelines.saveSongOffsets(mediaId, offsets) }
    }


    private fun setGigMedia(setlistId: String, media: List<StoredMedia>) {
        _state.update { it.copy(mediaBySetlist = it.mediaBySetlist + (setlistId to media)) }
        viewModelScope.launch { timelines.saveMedia(setlistId, media) }
    }

    /**
     * Same same-night gallery search [loadCoverCandidates] does for a playlist cover,
     * offered here as one-tap adds to the gig's keepsakes instead of a single chosen
     * cover. Silent when permission is missing, for the same reason: the prompt only
     * ever follows a tap, never just opening the gig.
     */
    fun loadGigPhotoSuggestions() {
        val date = _state.value.selectedSetlist?.localDate() ?: return
        val granted = photos.hasPermission()
        _state.update { it.copy(gigPhotoSuggestionsPermissionGranted = granted) }
        if (!granted) return
        viewModelScope.launch {
            _state.update { it.copy(gigPhotoSuggestionsLoading = true) }
            val found = photos.photosFrom(date)
            val candidates = found.map { CoverCandidate(it.uri, photos.preview(it.uri)) }
            _state.update {
                it.copy(
                    gigPhotoSuggestions = candidates,
                    gigPhotoSuggestionsLoading = false,
                    gigPhotoSuggestionsSearched = true,
                )
            }
        }
    }

    /**
     * The grid picture for a gig keepsake.
     *
     * The **durable floor** first (#98): the copy the app owns is the thing still
     * here when the gallery reference is not, and reading a 30–60 KB JPEG is also
     * why the strip draws instantly rather than decoding a 12 MP original per cell.
     * The source is the fallback only for media attached before thumbnails existed.
     */
    suspend fun photoPreview(uri: Uri): MediaThumb {
        val record = mediaFor(uri)
        val bitmap = record?.let { photos.gridThumbnail(it.id) } ?: photos.preview(uri, sizePx = 320)
        return MediaThumb(bitmap, record?.kind?.let { it == StoredMedia.Kind.VIDEO } ?: photos.isVideo(uri))
    }

    /** The record for a keepsake, found by the reference the screens hold. */
    private fun mediaFor(uri: Uri): StoredMedia? =
        _state.value.mediaBySetlist.values.firstNotNullOfOrNull { media ->
            media.firstOrNull { it.ref == uri.toString() }
        }

    /** Whether a gig keepsake is a video clip rather than a photo — cheap metadata
     *  lookup, checked before opening the in-app viewer so it knows which to show. */
    fun isVideo(uri: Uri): Boolean = photos.isVideo(uri)

    /** A bigger decode of the same photo, for the in-app viewer rather than the
     *  strip's thumbnail. */
    suspend fun fullPhoto(uri: Uri): Bitmap? {
        val record = mediaFor(uri)
        // Cache tier, then the source, then the floor. The cache tier is already
        // full-screen quality and costs one small local read; the grid tier at the
        // end is what makes a lost original degrade to *slightly soft* rather than
        // to nothing. Absent cache is a normal state — nothing here depends on it.
        return record?.let { photos.cachedFullThumbnail(it.id) }
            ?: photos.preview(uri, sizePx = 1600)
            ?: record?.let { photos.gridThumbnail(it.id) }
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
                val coverError = s.selectedCoverUri?.let {
                    uploadCover(playlist.id, it, s.selectedCoverFrameMs)
                }
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
