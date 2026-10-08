package io.github.magnusencoded.stationtostation

import android.app.Application
import io.github.magnusencoded.stationtostation.features.planning.PlanningController
import io.github.magnusencoded.stationtostation.features.tickets.TicketsController
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.magnusencoded.stationtostation.features.settings.SettingsController
import io.github.magnusencoded.stationtostation.features.settings.asSettingsStore
import io.github.magnusencoded.stationtostation.features.settings.asSpotifyLogin
import io.github.magnusencoded.stationtostation.data.Band
import io.github.magnusencoded.stationtostation.features.contacts.ContactsController
import io.github.magnusencoded.stationtostation.features.setlists.SetlistController
import io.github.magnusencoded.stationtostation.features.gig.GigController
import io.github.magnusencoded.stationtostation.features.gig.GigMediaController
import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.FriendArrival
import io.github.magnusencoded.stationtostation.data.friendArrival
import io.github.magnusencoded.stationtostation.data.laneKey
import io.github.magnusencoded.stationtostation.data.landNights
import io.github.magnusencoded.stationtostation.data.spineDismissals
import io.github.magnusencoded.stationtostation.data.spineJoins
import io.github.magnusencoded.stationtostation.data.MediaOffer
import io.github.magnusencoded.stationtostation.data.mySpine
import io.github.magnusencoded.stationtostation.data.withFriend
import io.github.magnusencoded.stationtostation.ble.probeCardFor
import io.github.magnusencoded.stationtostation.data.DeviceLocation
import io.github.magnusencoded.stationtostation.data.DeviceTimelinePlumbing
import io.github.magnusencoded.stationtostation.data.Festivals
import io.github.magnusencoded.stationtostation.data.LoadedSpine
import io.github.magnusencoded.stationtostation.data.ProgrammeAct
import io.github.magnusencoded.stationtostation.data.StoredProgramme
import io.github.magnusencoded.stationtostation.data.programmeDays
import io.github.magnusencoded.stationtostation.data.clashfinder.ClashfinderClient
import io.github.magnusencoded.stationtostation.data.SettingsRepository
import io.github.magnusencoded.stationtostation.data.StoredAdmission
import io.github.magnusencoded.stationtostation.data.TicketOriginals
import io.github.magnusencoded.stationtostation.data.keepingOriginal
import io.github.magnusencoded.stationtostation.data.needsOriginal
import io.github.magnusencoded.stationtostation.data.originals
import io.github.magnusencoded.stationtostation.data.StoredAttendance
import io.github.magnusencoded.stationtostation.data.StoredFestival
import io.github.magnusencoded.stationtostation.data.ProgrammeDiff
import io.github.magnusencoded.stationtostation.data.actKey
import io.github.magnusencoded.stationtostation.data.nameKey
import io.github.magnusencoded.stationtostation.data.playedActs
import io.github.magnusencoded.stationtostation.data.programmeFestivalId
import io.github.magnusencoded.stationtostation.data.StoredLog
import io.github.magnusencoded.stationtostation.data.bandsOf
import io.github.magnusencoded.stationtostation.data.fmDate
import io.github.magnusencoded.stationtostation.data.holdLanes
import io.github.magnusencoded.stationtostation.data.laneNeedsFetch
import io.github.magnusencoded.stationtostation.data.isLocal
import io.github.magnusencoded.stationtostation.data.localGigSetlist
import io.github.magnusencoded.stationtostation.data.moveMedia
import io.github.magnusencoded.stationtostation.data.parseFmDate
import io.github.magnusencoded.stationtostation.data.plannedLane
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.StoredPlaylist
import io.github.magnusencoded.stationtostation.data.Admission
import io.github.magnusencoded.stationtostation.data.ParsedTicket
import io.github.magnusencoded.stationtostation.data.TicketRouting
import io.github.magnusencoded.stationtostation.data.findDate
import io.github.magnusencoded.stationtostation.data.matchKnownNight
import io.github.magnusencoded.stationtostation.data.PdfTicketExtractor
import io.github.magnusencoded.stationtostation.data.onDevice
import io.github.magnusencoded.stationtostation.data.parseTicket
import io.github.magnusencoded.stationtostation.data.checkedForRedraw
import io.github.magnusencoded.stationtostation.data.routeTicket
import io.github.magnusencoded.stationtostation.data.QR_SYMBOLOGY
import io.github.magnusencoded.stationtostation.data.TimelineLogic
import io.github.magnusencoded.stationtostation.data.TimelineCache
import io.github.magnusencoded.stationtostation.data.TimelineStore
import io.github.magnusencoded.stationtostation.data.friendFromUri
import io.github.magnusencoded.stationtostation.data.photos.PhotoRepository
import io.github.magnusencoded.stationtostation.features.playlist.PlaylistController
import io.github.magnusencoded.stationtostation.data.sfmStamp
import io.github.magnusencoded.stationtostation.data.sfmUserFromDescription
import io.github.magnusencoded.stationtostation.data.spotifyPlaylistId
import io.github.magnusencoded.stationtostation.data.toShareUri
import io.github.magnusencoded.stationtostation.features.navigation.NavigationController
import io.github.magnusencoded.stationtostation.ui.MaybeNight
import io.github.magnusencoded.stationtostation.ui.TimelineNode
import io.github.magnusencoded.stationtostation.ui.atVenue
import io.github.magnusencoded.stationtostation.ui.canCheckInManually
import io.github.magnusencoded.stationtostation.ui.checkInCandidate
import io.github.magnusencoded.stationtostation.ui.venueMapsQuery
import io.github.magnusencoded.stationtostation.ble.ProbeCard
import io.github.magnusencoded.stationtostation.data.AccountsMove
import io.github.magnusencoded.stationtostation.features.handover.HandoverController
import io.github.magnusencoded.stationtostation.data.AccountsPayload
import io.github.magnusencoded.stationtostation.data.CATEGORY_ACCOUNTS
import io.github.magnusencoded.stationtostation.data.Credentials
import io.github.magnusencoded.stationtostation.data.HandoverManifest
import io.github.magnusencoded.stationtostation.data.Identities
import io.github.magnusencoded.stationtostation.data.categoriesFor
import io.github.magnusencoded.stationtostation.data.deviceManifest
import io.github.magnusencoded.stationtostation.data.identitiesOnly
import io.github.magnusencoded.stationtostation.data.mayClearCredentials
import io.github.magnusencoded.stationtostation.data.unionAttendance
import io.github.magnusencoded.stationtostation.data.unionLog
import io.github.magnusencoded.stationtostation.data.unionMedia
import io.github.magnusencoded.stationtostation.data.unionPlaylists
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
import io.github.magnusencoded.stationtostation.data.gossip.contactKeysOf
import io.github.magnusencoded.stationtostation.data.gossip.contactNamesOf
import io.github.magnusencoded.stationtostation.data.gossip.gossipExpiry
import io.github.magnusencoded.stationtostation.data.gossip.GossipService
import io.github.magnusencoded.stationtostation.features.gossip.GossipController
import io.github.magnusencoded.stationtostation.features.tour.CoachMark
import io.github.magnusencoded.stationtostation.features.tour.ContextHint
import io.github.magnusencoded.stationtostation.features.tour.TourState
import io.github.magnusencoded.stationtostation.features.tour.tourController
import io.github.magnusencoded.stationtostation.data.gossip.GossipStore
import io.github.magnusencoded.stationtostation.data.gossip.gigDatesOf
import io.github.magnusencoded.stationtostation.data.gossip.gossipGigTonight
import io.github.magnusencoded.stationtostation.data.gossip.GigIdentity
import io.github.magnusencoded.stationtostation.data.gossip.GossipEnvelope
import io.github.magnusencoded.stationtostation.data.gossip.gossipGigAliases
import io.github.magnusencoded.stationtostation.data.gossip.gossipActiveGigId
import io.github.magnusencoded.stationtostation.data.gossip.gossipParticipationEnds
import io.github.magnusencoded.stationtostation.data.gossip.gossipStoppedGigs
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
import io.github.magnusencoded.stationtostation.data.setlistfm.SetlistFmRateLimited
import io.github.magnusencoded.stationtostation.data.setlistfm.parseSetlistId
import io.github.magnusencoded.stationtostation.data.setlistfm.LOOKUP_FRICTION_MESSAGE
import io.github.magnusencoded.stationtostation.data.setlistfm.LookupGig
import io.github.magnusencoded.stationtostation.data.setlistfm.LookupPlan
import io.github.magnusencoded.stationtostation.data.setlistfm.LookupOutcome
import io.github.magnusencoded.stationtostation.data.setlistfm.ManualLookup
import io.github.magnusencoded.stationtostation.data.setlistfm.TicketImport
import io.github.magnusencoded.stationtostation.data.setlistfm.TicketSetlistFm
import io.github.magnusencoded.stationtostation.data.setlistfm.TicketSetlistFmAnswer
import io.github.magnusencoded.stationtostation.data.setlistfm.asStoredHit
import io.github.magnusencoded.stationtostation.data.setlistfm.chipHits
import io.github.magnusencoded.stationtostation.data.setlistfm.manualSetlistFmLookup
import io.github.magnusencoded.stationtostation.data.setlistfm.setlistFmLookupOutcome
import io.github.magnusencoded.stationtostation.data.setlistfm.setlistFmLookupPlan
import io.github.magnusencoded.stationtostation.data.setlistfm.ticketImport
import io.github.magnusencoded.stationtostation.data.SetlistFmMatch
import io.github.magnusencoded.stationtostation.data.StoredSetlistFmHit
import io.github.magnusencoded.stationtostation.data.matchSetlistFm
import io.github.magnusencoded.stationtostation.data.spotify.SpotifyClient
import io.github.magnusencoded.stationtostation.data.spotify.SpotifyTrack
import io.github.magnusencoded.stationtostation.data.spotify.rankCandidates
import java.io.Closeable
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.NonCancellable
import java.net.Socket
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

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
 * Why an error is on screen, where that changes what can be offered about it.
 *
 * Only the cases a screen acts on differently are named; everything else has no kind and
 * is shown as its message alone.
 */
enum class ErrorKind {
    /** setlist.fm refused the *bundled* key: a free key of their own is the way out. */
    SETLISTFM_SHARED_QUOTA,
}

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

/** A pre-filled add dialog; [date] is in the form's own dd-MM-yyyy, blank when the link gave none. */
data class AddGigLink(val artist: String, val venue: String, val date: String)

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
    /** The clashfinder account, as typed. There is no bundled one and never will be. */
    val clashfinderUser: String = "",
    val clashfinderPrivateKey: String = "",
    /** Both halves are on this phone, so the programme button has somewhere to go. */
    val clashfinderReady: Boolean = false,
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
    /**
     * The **Collection** whose walk is open, landscape face only (#313). Entered from
     * the Line and left the same way — a state at a place, never a route, so nothing
     * here has a back stack entry of its own.
     */
    val selectedCollection: TimelineNode.Several? = null,
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
    /**
     * Media **Contacts** sent for Nights of theirs I have not joined, by their Night id
     * (#405): offered, never filed, and shown on my Night of the same date until I answer.
     */
    val mediaOffers: Map<String, MediaOffer> = emptyMap(),
    /**
     * What I said about a **Contact**'s Night that nothing else links to mine (#405),
     * under the ids the **Spine** uses: their Night id → my Night id it is ([nightJoins]),
     * and → the Nights of mine it is not ([nightsApart]). Mine alone; the weave reads both.
     */
    val nightJoins: Map<String, String> = emptyMap(),
    val nightsApart: Map<String, Set<String>> = emptyMap(),
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
    /** The name on my **Card** when there is no username to name it (#405). */
    val myCardName: String = "",
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
    /**
     * **Gigs** whose own check-in a directly-present device witnessed (#442).
     *
     * A decoration on [attendanceByGig], never a substitute for it: the user saying they
     * were there and a stranger's phone agreeing are two different claims, and a night
     * with nobody else running the radio is still a night they attended.
     */
    val witnessedGigs: Set<String> = emptySet(),
    val publicGossip: io.github.magnusencoded.stationtostation.data.gossip.PublicGossipState = io.github.magnusencoded.stationtostation.data.gossip.PublicGossipState(),
    /**
     * Every id a night has been known by, under each of them (#496, #498).
     *
     * A **Room** asks the gossip record about *its* night, and the id it holds is whichever one
     * the night is displayed under now. Anything adopted, merged or relabelled has an older id
     * the record may still be filed under, so the lookup is a set. See [gossipGigAliases].
     */
    val gossipGigAliases: Map<String, Set<String>> = emptyMap(),
    /**
     * When each night *could* **Gossip** until, by the id its **Room** holds — the stop
     * deliberately not applied (#500).
     *
     * A stop zeroes every participation deadline, and the dim bullet is the one control that can
     * undo a stop; asking the stopped-aware deadline here would make it undrawable. What the
     * radio actually runs on stays [gossipActiveUntil] with the stop, as it was.
     */
    val gossipEligibleUntil: Map<String, Long> = emptyMap(),
    /** The **Active Gig**, under the id its **Room** holds. See [gossipActiveGigId]. */
    val gossipActiveGig: String? = null,
    /**
     * The nights a stop actually ended, by the id their **Room** holds — the dim half of a
     * **Presence row**'s bullet.
     *
     * A set and not a flag, because a stop is not global: it ends the nights already stood in,
     * and a **Check-in** made *after* it is a fresh consent the earlier stop says nothing about.
     * One boolean here drew a dim bullet on a night the radio was plainly running for.
     */
    val gossipStoppedGigs: Set<String> = emptySet(),
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
     * Whose **Line** is tapped out of the weave, by setlist.fm username, together with
     * when each was turned off (#396) — the legend's recency order sorts by it, and it
     * is what makes "turned off last night" survive a launch. Still a reading aid and
     * not a decision about a person: nothing is sent, and it says nothing about the
     * relationship.
     *
     * Held here for the same reason as [openFestivals] — opening a gig disposes the
     * timeline, and a filter set up to survive a change of **Resolution** must survive
     * going one rung **Inner** and coming back.
     *
     * Keyed by person, so someone who has never been hidden is visible — adding a
     * **Followed line** or a **Contact** is never silently a no-op.
     */
    val hiddenAt: Map<String, Long> = emptyMap(),
    /**
     * A gig a `station-to-station://` link asked for, and how it wants to be shown.
     * The timeline is the one place that can find a gig's row — a gig inside a
     * collapsed festival has no row until the festival opens — so it does the
     * revealing and clears this when done.
     */
    val linkedGig: String? = null,
    val linkedGigAs: GigLink? = null,
    /** A screen a link asked for, acted on by the navigation and cleared when done. */
    val linkScreen: LinkScreen? = null,
    /** The night a link wants the **Line** scrolled to; the timeline finds the nearest **Gig** and clears it. */
    val linkedDate: LocalDate? = null,
    /** An add dialog a link asked for, pre-filled and unsaved; the timeline opens it and clears this. */
    val addGigLink: AddGigLink? = null,
    /** Set when the playlist was made but its cover could not be uploaded. */
    val coverUploadError: String? = null,
    /** The device handover on screen, if one is running or has just finished (#142). */
    val handover: HandoverUi = HandoverUi(),
    /**
     * A shared PDF ticket's best-effort guess, waiting on a person before anything
     * is written (#411). Set for every parse short of a complete, unambiguous one —
     * including a parse that found nothing at all, which the confirm screen reads
     * as "couldn't read this ticket" rather than a silent failure (ADR-0004: a
     * partial or absent result is a state to show, never an error to hide).
     *
     * A queue, oldest first, as iOS's `ticketDrafts` is (the #441 review): with one slot,
     * a second share while the prompt was open dropped the first. The dialog shows
     * [pendingTicket], the head; answering it shows the next.
     */
    val pendingTickets: List<PendingTicket> = emptyList(),
    // Transient error surfaced as a snackbar
    val error: String? = null,
    /**
     * What kind of thing [error] is, for the screens that offer a way out of one.
     *
     * A bare message cannot be acted on: the shared setlist.fm quota running out is the
     * one error the app can hand the user a button for (#457), and telling it apart from
     * "no results" or "that isn't a link" takes more than the words.
     */
    val errorKind: ErrorKind? = null,
    /**
     * The shared setlist.fm key is believed spent (#457) and no key of the user's own is
     * saved. What Settings leads its setlist.fm section with.
     */
    val setlistFmSharedQuotaSpent: Boolean = false,
    // Transient non-error notice (e.g. "Added a friend from that playlist")
    val notice: String? = null,
    /** True once the first-run **Tour** has been offered. */
    val onboarded: Boolean = false,
    val tour: TourState = TourState(),
    val contextHint: ContextHint? = null,
    /** The coach mark the **Tour** last asked for; null when none is up. */
    val coachMark: CoachMark? = null,
    /** True while an install onboarded before the **Tour** existed is being offered it. */
    val tourUpgradePrompt: Boolean = false,
    /**
     * True once launch has read what the first screen needs: the settings (so
     * [onboarded] is known) and the saved timeline, Festivals and all. The system
     * splash stays up until then, so a returning user never sees onboarding, an
     * empty timeline or a "0 shows" count on the way to their own.
     */
    val launched: Boolean = false,
) {
    /** Who is currently tapped out. Derived so there is only [hiddenAt] to keep in step. */
    val hiddenLines: Set<String> get() = hiddenAt.keys

    /** The ticket the confirm dialog is showing: the oldest of [pendingTickets]. */
    val pendingTicket: PendingTicket? get() = pendingTickets.firstOrNull()
}

/**
 * Furthest-future first, which is the same order the attended rows below already
 * use: up is always later, and a planned gig is not an exception to that.
 */
internal fun sortedPlanned(gigs: List<FmSetlist>): List<FmSetlist> =
    gigs.sortedByDescending { it.localDate() }

/**
 * The state after a local **Gig** took [setlistId] (#515): everything the screens read by
 * gig id, moved from [localId] to the new one in one step.
 *
 * The store needs no such move — it keys by its own id and adoption only changes `keyOf` —
 * but the screens key by that answer. A **Room** reading its **Log** under the new id before
 * this ran found none, and the next line written saved that empty **Log** over the real one.
 * Its **Check-in** went missing the same way, and with it the Log editor itself.
 *
 * Where [setlistId] already holds an entry, the store has merged two **Gigs** into one
 * (`adoptSetlistId`, #128), and the two entries combine by the same unions the store reads
 * them back with; nothing is overwritten. The gossip projections keep the old id beside the
 * new one, because the record may still be filed under either.
 */
internal fun UiState.adopting(localId: String, setlistId: String): UiState {
    fun <V> Map<String, V>.moved(union: (V, V) -> V): Map<String, V> {
        val mine = this[localId] ?: return this
        val theirs = this[setlistId]
        return this - localId + (setlistId to if (theirs == null) mine else union(theirs, mine))
    }
    fun FmSetlist.moved() = if (id == localId) copy(id = setlistId) else this
    fun Set<String>.alsoAdopted() = if (localId in this) this + setlistId else this
    val aliases = gossipGigAliases[localId].orEmpty() + gossipGigAliases[setlistId].orEmpty() +
        localId + setlistId
    return copy(
        logsByGig = logsByGig.moved(::unionLog),
        attendanceByGig = attendanceByGig.moved(::unionAttendance),
        calendarEventByGig = calendarEventByGig.moved { kept, _ -> kept },
        mediaBySetlist = mediaBySetlist.moved(::unionMedia),
        playlistsBySetlist = playlistsBySetlist.moved(::unionPlaylists),
        setlists = setlists.map { it.moved() },
        plannedGigs = plannedGigs.map { it.moved() },
        selectedSetlist = selectedSetlist?.moved(),
        checkInOffer = checkInOffer?.moved(),
        linkedGig = if (linkedGig == localId) setlistId else linkedGig,
        witnessedGigs = witnessedGigs.alsoAdopted(),
        gossipEligibleUntil = gossipEligibleUntil[localId]
            ?.let { gossipEligibleUntil + (setlistId to it) } ?: gossipEligibleUntil,
        gossipActiveGig = if (gossipActiveGig == localId) setlistId else gossipActiveGig,
        gossipStoppedGigs = gossipStoppedGigs.alsoAdopted(),
        gossipGigAliases = gossipGigAliases + aliases.associateWith { aliases },
    )
}

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

/**
 * A ticket pipeline's guess, on screen for confirmation (#411). [possibleMatch] is a
 * hint, not a decision the dialog is bound by — a person may still say "no, that's a
 * different night" and get a new plan instead.
 */
data class PendingTicket(
    val parsed: ParsedTicket,
    val possibleMatch: FmSetlist?,
    /** Its own identity, so the dialog's answer names the ticket it was given for. */
    val id: String = UUID.randomUUID().toString(),
    /**
     * What the import's setlist.fm lookup offered (#531), drawn above "None of these";
     * null where no lookup was made (no artist or date on the ticket).
     */
    val setlistFm: TicketSetlistFm? = null,
)

/** What the confirm dialog's Save does with a [PendingTicket]: see [PendingTicket.confirmedAs]. */
sealed interface ConfirmedTicket {
    /** The confirmed values name a night already known: its Admissions go onto it. */
    data class Attach(val gigId: String, val admissions: List<Admission>) : ConfirmedTicket

    /** They name no known night: a new planned gig, carrying the Admissions. */
    data class Mint(
        val artist: String,
        val venue: String,
        val night: LocalDate,
        val admissions: List<Admission>,
    ) : ConfirmedTicket
}

/**
 * Where this ticket lands once a person has said what it is (#526) — decided on the
 * confirmed [artist], [venue] and [night], never on [PendingTicket.possibleMatch], as
 * iOS's `confirmTicket` does. That hint was found for what the parse read; a person who
 * edited the artist or the date has named some other night.
 *
 * Only an artist match attaches ([matchKnownNight]). [knownNightThatDay]'s same-date
 * possible match is not re-applied here: it is why routing asked, and the prompt shows
 * it, but a Save whose act is not that night's act is a night of its own. iOS draws the
 * same line (its `nightThatDay` is routing-only). The Admissions are the parse's,
 * whatever was edited (#441, story 16).
 */
fun PendingTicket.confirmedAs(
    artist: String,
    venue: String,
    night: LocalDate,
    knownGigs: List<FmSetlist>,
): ConfirmedTicket {
    val confirmed = ParsedTicket(artist = artist.trim(), venue = venue.trim(), date = fmDate(night))
    return matchKnownNight(confirmed, knownGigs)
        ?.let { ConfirmedTicket.Attach(it.id, parsed.admissions) }
        ?: ConfirmedTicket.Mint(artist.trim(), venue.trim(), night, parsed.admissions)
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /** setlist.fm's page size for attended lists — used to resume a cached spine. */
        private const val SETLISTS_PER_PAGE = 20

    }

    val settings = SettingsRepository(application)
    private val timelines = TimelineStore(application)
    private val ticketOriginals = TicketOriginals.of(application)
    private val setlistFm = SetlistFmClient(
        keySource = { settings.setlistFmKey() },
        sharedQuotaSpentAt = { settings.sharedQuotaSpentAtValue() },
        recordSharedQuotaSpent = { settings.recordSharedQuotaSpent(it) },
    )
    private val musicBrainz = MusicBrainzClient()

    /**
     * The programme source. Public because the **Programme** screen does its own
     * fetching and caching — a timetable is a document on this phone, not a slice of
     * app state, and the one screen that reads it is the one that keeps it.
     */
    val clashfinder = ClashfinderClient { settings.clashfinderAuth() }

    suspend fun openClashfinderInBrowser(context: Context, path: String) =
        settingsController.openClashfinderInBrowser(context, path)

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
    private val contactExchange: ContactExchange = ContactExchange(
        context = application,
        scope = viewModelScope,
        photos = photos,
        contactKeys = { settings.friends.first().mapNotNull { it.publicKey } },
        manifest = {
            val cache = tour.offerable(timelines.load())
            val me = settings.mySetlistFmUser.first().orEmpty()
            handover.hashedManifest(contactManifest(cache, contactIdentityPublicKeyBase64(), me), cache)
        },
        mine = { tour.offerable(timelines.load()) },
        gallery = { handover.galleryForMatching(timelines.load()) },
        onLanded = { landing -> timelines.mergeContactMedia(landing) },
        lanesByKey = {
            val shows = timelines.load().shows
            settings.friends.first().mapNotNull { f ->
                f.publicKey?.let { key -> shows[f.laneKey]?.let { key to it } }
            }.toMap()
        },
        onNights = { key, nights, withdrawn ->
            val dropped = contacts.landContactNights(key, nights, withdrawn)
            dropped.forEach { photos.deleteOwnedBytes(it.id, it.ref) }
            if (withdrawn.isNotEmpty()) _state.update { it.copy(mediaOffers = timelines.load().mediaOffers) }
        },
        myNights = { timelines.load().mySpine(settings.mySetlistFmUser.first().orEmpty()) },
        onOffers = { offers ->
            timelines.holdMediaOffers(offers)
            val held = timelines.load().mediaOffers
            _state.update { it.copy(mediaOffers = held) }
        },
    )

    private val contacts: ContactsController = ContactsController(
        state = { _state.value },
        update = { change -> _state.update(change) },
        settings = settings,
        timelines = timelines,
        spotify = spotify,
        setlistFm = setlistFm,
        logic = logic,
        exchange = exchange,
        contactExchange = contactExchange,
        scope = viewModelScope,
        fail = { e -> fail(e) },
        errorKindOf = { e -> errorKindOf(e) },
        isSharedQuota = { e -> isSharedQuota(e) },
        adoptSetlist = { gigId, setlistId, fresh, notice -> adoptSetlist(gigId, setlistId, fresh, notice) },
        syncGossip = { gossipController.sync() },
    )

    /**
     * What this phone carries on the gossip channel (#416).
     *
     * The view model's only business with gossip is the three ends of it: minting this
     * phone's own check-in, telling the service whether it has a reason to run, and reading
     * back which of its own check-ins were witnessed (#442). Everything in between —
     * advertising, accepting, relaying, forgetting — is the service's, which is why nothing
     * else about a relayed message reaches [UiState].
     */
    private val gossip = GossipStore(application)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val gigController = GigController(
        state = { _state.value },
        update = { change -> _state.update(change) },
        timelines = timelines,
        photos = photos,
        where = where,
        setlistFm = setlistFm,
        gossip = gossip,
        scope = viewModelScope,
        setGigMedia = { id, media -> setGigMedia(id, media) },
        syncGossip = { gossipController.sync() },
        gossipAbout = { id -> gossipController.gossipAbout(id) },
        gigNow = { id, now -> tour.now(id, now) },
        venuePoint = { gig -> tour.venuePoint(gig) },
        onCheckedIn = { id -> tour.checkedIn(id) },
        onLogWritten = { id, before, after -> tour.logWritten(id, before, after) },
    )
    private val gigMedia = GigMediaController(
        state = { _state.value },
        update = { f -> _state.update(f) },
        timelines = timelines,
        photos = photos,
        scope = viewModelScope,
        claimAttach = { id -> tour.mediaClaim(id) },
    )
    private val gossipController = GossipController(
        state = { _state.value },
        update = { edit -> _state.update(edit) },
        gossip = gossip,
        timelines = timelines,
        radio = { activeUntil -> GossipService.sync(getApplication<Application>(), activeUntil) },
        scope = viewModelScope,
    )
    private val navigation = NavigationController(
        state = { _state.value },
        update = { change -> _state.update(change) },
        scope = viewModelScope,
        fetchSetlist = { setlistFm.setlist(it) },
        saveHiddenLines = { timelines.saveHiddenLines(it) },
        resolveFestivalsFor = { mine, known -> logic.resolveFestivals(mine, known) },
        refreshLine = { friend -> contacts.refreshLine(friend) },
        writeLog = ::writeLog,
        fail = ::fail,
    )
    val tour = tourController(application, _state, settings, timelines, viewModelScope, contacts, where, photos, setlistFm, musicBrainz) { ticket, gigId ->
        tickets.importTicket(ticket, gigId)
    }

    private val settingsController = SettingsController(
        update = { transform -> _state.update(transform) },
        settings = settings.asSettingsStore(),
        spotify = spotify.asSpotifyLogin(),
        scope = viewModelScope,
        fail = { fail(it) },
    )

    private val handover = HandoverController(
        state = { _state.value },
        update = { change -> _state.update(change) },
        application = application,
        settings = settings,
        spotify = spotify,
        photos = photos,
        timelines = timelines,
        scope = viewModelScope,
        restoreTimelines = { restoreTimelines() },
        offerable = { tour.offerable(it) },
    )

    private val playlist = PlaylistController(
        state = { _state.value },
        update = { transform -> _state.update(transform) },
        spotify = spotify,
        photos = photos,
        timelines = timelines,
        scope = viewModelScope,
        fail = ::fail,
        addFriend = ::addFriend,
    )

    fun exportTourPlaylist() {
        val id = _state.value.plannedGigs.firstOrNull { tour.isDemoGig(it.id) }?.id ?: return
        val gig = _state.value.plannedGigs.first { it.id == id }
        val log = tour.demoLogRecord(id)?.log ?: return
        val metadata = tour.character.playlist
        // Closing this demo conversion copy does not close the person's Log.
        val songs = log.songs.filter { it.isNotBlank() }.map { io.github.magnusencoded.stationtostation.data.setlistfm.FmSong(name = it) }
        val exportGig = gig.copy(sets = io.github.magnusencoded.stationtostation.data.setlistfm.FmSets(
            set = listOf(io.github.magnusencoded.stationtostation.data.setlistfm.FmSet(song = songs))))
        playlist.exportTour(exportGig, metadata.title, metadata.description) {
            tour.dispatch(io.github.magnusencoded.stationtostation.features.tour.TourEvent.SpotifyExported)
        }
    }

    private val planning: PlanningController = PlanningController(
        state = { _state.value },
        update = { f -> _state.update(f) },
        timelines = timelines,
        setlistFm = setlistFm,
        musicBrainz = musicBrainz,
        scope = viewModelScope,
        fail = ::fail,
        gigAdded = { tour.gigAdded(it) },
    )

    private val tickets: TicketsController = TicketsController(
        state = { _state.value },
        update = { f -> _state.update(f) },
        timelines = timelines,
        setlistFm = setlistFm,
        ticketOriginals = ticketOriginals,
        application = getApplication(),
        scope = viewModelScope,
        planning = planning,
        adoptSetlist = { gigId, setlistId, fresh, notice -> adoptSetlist(gigId, setlistId, fresh, notice) },
        lookUpLocalGig = { gigId, manual -> setlists.lookUpLocalGig(gigId, manual) },
    )

    private val setlists: SetlistController = SetlistController(
        state = { _state.value },
        update = { change -> _state.update(change) },
        setlistFm = setlistFm,
        timelines = timelines,
        setlistFmKey = { settings.setlistFmKey() },
        sharedQuotaSpentAtValue = { settings.sharedQuotaSpentAtValue() },
        gossipStoppedAt = { gossip.stoppedAt() },
        scope = viewModelScope,
        fail = ::fail,
        consumeError = ::consumeError,
        saveSettingsNow = ::saveSettingsNow,
        saveMySetlistFmUser = ::saveMySetlistFmUser,
        adoptSetlist = ::adoptSetlist,
        lineArtists = { planning.lineArtists() },
    )

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
                    setlistFmSharedQuotaSpent = settings.sharedQuotaSpentNow(),
                    bundledSpotifyClientId = settings.hasBundledSpotifyClientId(),
                    bundledSetlistFmKey = settings.hasBundledSetlistFmKey(),
                    bundledSpotifyHint = settings.bundledSpotifyClientIdHint(),
                    bundledSetlistFmHint = settings.bundledSetlistFmKeyHint(),
                    clashfinderUser = settings.clashfinderUser.first() ?: "",
                    clashfinderPrivateKey = settings.clashfinderPrivateKey.first() ?: "",
                    clashfinderReady = settings.clashfinderAuth() != null,
                    grantedScope = settings.grantedScope(),
                    mySetlistFmUser = settings.mySetlistFmUser.first() ?: "",
                    myCardName = settings.myCardName.first() ?: "",
                    friends = settings.friends.first(),
                    onboarded = settings.onboarded.first(),
                    tour = settings.tour.first(),
                )
            }
            restoreTimelines()
            tour.launch()
            // After the timeline is back, because the only reason the radio runs is a Gig
            // on this timeline that is still in participation.
            gossipController.sync()
        }
        // Witnessed check-in is the one gossip answer the screens ask for. Read from the
        // store rather than pushed at the moment of witnessing, so a phone that was closed
        // when the witness arrived projects it the same way after a restart.
        viewModelScope.launch {
            gossip.publicStates.collect { public ->
                val witnessed = public.apply { prune(System.currentTimeMillis()) }.witnessedGigIds()
                val gigs = timelines.load().gigs
                val adopted = gossip.adoptedIds()
                val projected = witnessed + witnessed.mapNotNull { adopted[it] ?: gigs[it]?.setlistId }
                val aliases = gossipGigAliases(timelines, adopted)
                _state.update {
                    it.copy(witnessedGigs = projected, publicGossip = public, gossipGigAliases = aliases)
                }
            }
        }
        viewModelScope.launch {
            settings.friends.collect { friends ->
                gossip.updatePublic(System.currentTimeMillis()) { it.recognizeContacts(contactKeysOf(friends), contactNamesOf(friends)) }
            }
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
                    _state.update { it.copy(error = message, errorKind = null, discovering = false) }
                    exchange.consumeFailure()
                }
            }
        }
        // #87: the peer tapped, not me — their card arrived over the write characteristic.
        // Same landing as a tap, so one tap brings both people in.
        exchange.onFriendReceived = { friend -> viewModelScope.launch { contacts.bringIn(friend) } }
    }

    fun startContactExchange() = contacts.startContactExchange()

    fun stopContactExchange() = contacts.stopContactExchange()

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
            cached.gigPlaylists.isEmpty() && cached.gigPlanned.isEmpty()
        ) {
            _state.update { it.copy(launched = true) }
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
                plannedGigs = planning.sortedPlanned(cached.planned()),
                logsByGig = it.logsByGig + cached.logs(),
                catalogueByArtist = it.catalogueByArtist + cached.catalogueByArtist,
                attendanceByGig = it.attendanceByGig + cached.attendance(),
                calendarEventByGig = it.calendarEventByGig + cached.calendarEvents(),
                hiddenAt = cached.hiddenLines,
                mediaOffers = cached.mediaOffers,
                nightJoins = cached.spineJoins(),
                nightsApart = cached.spineDismissals(),
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
        //
        // Launch is done at the first Spine, not after the Festival retry: that one
        // asks setlist.fm, and the splash never waits on the network.
        logic.loadSpine(me) { spine ->
            adoptSpine(spine, cached.attendedTotals[me])
            _state.update { it.copy(launched = true) }
        }
        _state.update { it.copy(launched = true) }
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

    fun consumeError() = _state.update { it.copy(error = null, errorKind = null) }
    fun consumeNotice() = _state.update { it.copy(notice = null) }

    /**
     * The one place a thrown thing becomes an error on screen — and, for a spent shared
     * setlist.fm quota, an error with something to do about it.
     */
    private fun fail(e: Exception) = _state.update {
        it.copy(
            error = e.message ?: "Something went wrong",
            errorKind = errorKindOf(e),
            searchLoading = false,
            setlistsLoading = false,
            creatingPlaylist = false,
            setlistFmSharedQuotaSpent = it.setlistFmSharedQuotaSpent || isSharedQuota(e),
        )
    }

    private fun errorKindOf(e: Throwable): ErrorKind? =
        if (isSharedQuota(e)) ErrorKind.SETLISTFM_SHARED_QUOTA else null

    private fun isSharedQuota(e: Throwable): Boolean =
        e is SetlistFmRateLimited && e.sharedKey

    fun saveSettings(apiKey: String, clientId: String) =
        settingsController.saveSettings(apiKey, clientId)

    suspend fun saveSettingsNow(apiKey: String, clientId: String) =
        settingsController.saveSettingsNow(apiKey, clientId)

    fun saveClashfinderCredentials(user: String, privateKey: String) =
        settingsController.saveClashfinderCredentials(user, privateKey)

    suspend fun buildSpotifyAuthUri(): Uri = settingsController.buildSpotifyAuthUri()

    fun handleAuthRedirect(uri: Uri) = settingsController.handleAuthRedirect(uri)

    fun disconnectSpotify() = settingsController.disconnectSpotify()

    suspend fun receiveHandoverAccounts(socket: Socket): AccountsPayload? =
        handover.receiveHandoverAccounts(socket)

    suspend fun sendHandoverAccounts(socket: Socket, payload: AccountsPayload): AccountsMove =
        handover.sendHandoverAccounts(socket, payload)

    fun offerHandover(allow: Set<String>) = handover.offerHandover(allow)

    fun joinHandover(uri: Uri) = handover.joinHandover(uri)

    fun cancelHandover() = handover.cancelHandover()

    fun dismissHandover() = handover.dismissHandover()

    // --- Friends (peer-to-peer) ---

    fun saveMySetlistFmUser(username: String) {
        val trimmed = username.trim()
        viewModelScope.launch {
            settings.saveMySetlistFmUser(trimmed)
            _state.update { it.copy(mySetlistFmUser = trimmed) }
        }
    }

    suspend fun myCardUri(): Uri? = contacts.myCardUri()

    fun addFriend(friend: Friend) = contacts.addFriend(friend)

    fun confirmFriendOverwrite() = contacts.confirmFriendOverwrite()

    fun dismissFriendOverwrite() = contacts.dismissFriendOverwrite()

    fun addFriendByUsername(username: String) = contacts.addFriendByUsername(username)

    fun handleFriendLink(uri: Uri) = contacts.handleFriendLink(uri)

    fun removeFriend(friend: Friend) = contacts.removeFriend(friend)

    /**
     * Yes to a **Contact**'s offer (#405): their media is filed on my Night [key] and their
     * Night [night] is joined, so what they send for it later lands there directly.
     */
    fun acceptMediaOffer(night: String, key: String) {
        viewModelScope.launch {
            timelines.acceptMediaOffer(night, key)
            val cache = timelines.load()
            _state.update {
                it.copy(
                    mediaOffers = cache.mediaOffers,
                    mediaBySetlist = it.mediaBySetlist + cache.media(),
                    nightJoins = cache.spineJoins(),
                )
            }
        }
    }

    /** No to a **Contact**'s offer (#405): my Night is left exactly as it was. */
    fun declineMediaOffer(night: String) {
        viewModelScope.launch {
            timelines.declineMediaOffer(night)
            val held = timelines.load().mediaOffers
            _state.update { it.copy(mediaOffers = held) }
        }
    }

    fun joinNight(night: String, key: String) = contacts.joinNight(night, key)

    fun dismissMaybe(night: String, key: String) = contacts.dismissMaybe(night, key)

    fun adoptMaybe(maybe: MaybeNight) = contacts.adoptMaybe(maybe)

    fun unjoinNight(night: String, key: String) = contacts.unjoinNight(night, key)

    fun undismissMaybe(night: String, key: String) = contacts.undismissMaybe(night, key)

    fun viewFriendTimeline(friend: Friend) = contacts.viewFriendTimeline(friend)

    fun openSharedConcerts(friend: Friend) = contacts.openSharedConcerts(friend)

    // --- Exchange (meeting someone in person) + two-timeline comparison ---

    fun saveMyCardName(name: String) = contacts.saveMyCardName(name)

    fun startExchange() = contacts.startExchange()

    fun restartExchange() = contacts.restartExchange()

    fun stopExchange() = contacts.stopExchange()

    fun exchangePermissions(): List<String> = contacts.exchangePermissions()

    fun connectWith(peer: ExchangePeer) = contacts.connectWith(peer)

    fun consumeJustConnected() = contacts.consumeJustConnected()

    fun setZoomedOut(on: Boolean) = navigation.setZoomedOut(on)

    fun handleLink(intent: LinkIntent) = navigation.handleLink(intent)

    fun linkGig(id: String, at: GigLink) = navigation.linkGig(id, at)

    fun consumeLinkScreen() = navigation.consumeLinkScreen()

    fun consumeLinkedDate() = navigation.consumeLinkedDate()

    fun consumeAddGigLink() = navigation.consumeAddGigLink()

    fun consumeGigLink() = navigation.consumeGigLink()

    fun toggleFestival(key: String) = navigation.toggleFestival(key)

    fun toggleLineHidden(lane: String) = navigation.toggleLineHidden(lane)

    fun openFestival(key: String) = navigation.openFestival(key)

    fun knownGig(id: String): FmSetlist? = navigation.knownGig(id)

    fun resolveFestivals() = navigation.resolveFestivals()

    fun loadFriendTimelines() = contacts.loadFriendTimelines()

    fun setArtistQuery(q: String) = setlists.setArtistQuery(q)
    fun setUserQuery(q: String) = setlists.setUserQuery(q)
    fun searchArtists() = setlists.searchArtists()
    fun openArtist(artist: FmArtist) = setlists.openArtist(artist)
    fun importAttended(username: String, apiKey: String?) = setlists.importAttended(username, apiKey)
    fun openUserAttended() = setlists.openUserAttended()
    fun refreshSelectedSetlist() = setlists.refreshSelectedSetlist()
    fun startLookupChecks() = setlists.startLookupChecks()
    fun stopLookupChecks() = setlists.stopLookupChecks()
    suspend fun setlistFmChipHits(gigId: String): List<StoredSetlistFmHit> = setlists.setlistFmChipHits(gigId)
    fun acceptSetlistFmMatch(gigId: String, setlistId: String) = setlists.acceptSetlistFmMatch(gigId, setlistId)
    fun rejectSetlistFmMatches(gigId: String) = setlists.rejectSetlistFmMatches(gigId)
    fun loadMoreSetlists() = setlists.loadMoreSetlists()

    // --- Matching ---

    fun openShow(setlist: FmSetlist) = navigation.openShow(setlist)

    fun openCollectionWalk(node: TimelineNode.Several) = navigation.openCollectionWalk(node)

    fun closeCollectionWalk() = navigation.closeCollectionWalk()

    fun addPlannedGig(linkOrId: String) = planning.addPlannedGig(linkOrId)
    fun addGig(artist: String, venue: String, date: String) = planning.addGig(artist, venue, date)
    fun joinGig(gig: FmSetlist) = planning.joinGig(gig)
    fun addPlannedGigByHand(artist: String, venue: String, date: String) = planning.addPlannedGigByHand(artist, venue, date)
    fun handleSharedTicketPdf(uri: Uri) = tickets.handleSharedTicketPdf(uri)
    fun handleTicketLink(uri: Uri) = tickets.handleTicketLink(uri)
    fun confirmPendingTicket(id: String, artist: String, venue: String, date: String, chosenSetlistId: String? = null) =
        tickets.confirmPendingTicket(id, artist, venue, date, chosenSetlistId)
    fun dismissPendingTicket(id: String) = tickets.dismissPendingTicket(id)
    fun suggestArtists(query: String) = planning.suggestArtists(query)
    fun clearArtistSuggestions() = planning.clearArtistSuggestions()
    fun addLocalGig(artist: String, venue: String, date: String) = planning.addLocalGig(artist, venue, date)
    suspend fun markCalendarAdded(gigId: String, eventUri: String, world: Int? = tour.calendarWorld(gigId)) {
        if (tour.calendarAdded(gigId, eventUri, world)) planning.markCalendarAdded(gigId, eventUri)
    }
    fun commitProgramme(
        programme: StoredProgramme,
        diff: ProgrammeDiff,
        picked: Set<String>,
        now: LocalDateTime = LocalDateTime.now(),
    ) = planning.commitProgramme(programme, diff, picked, now)

    fun standing(gigId: String): GigStanding = gigController.standing(gigId)

    fun photosLostByDeleting(gigId: String): Int = gigController.photosLostByDeleting(gigId)

    fun deleteGig(gigId: String) = gigController.deleteGig(gigId)

    // --- The Log: what I saw, as opposed to what setlist.fm publishes ---

    fun blockGossip(author: String) = gossipController.block(author)

    fun logFor(gigId: String): StoredLog = gigController.logFor(gigId)

    fun addToLog(gigId: String, song: String) = gigController.addToLog(gigId, song)

    fun removeFromLog(gigId: String, index: Int) = gigController.removeFromLog(gigId, index)

    fun correctLogEntry(gigId: String, index: Int, title: String) = gigController.correctLogEntry(gigId, index, title)

    fun restoreLogEntry(gigId: String, index: Int) = gigController.restoreLogEntry(gigId, index)

    fun setLogClosed(gigId: String, closed: Boolean) = gigController.setLogClosed(gigId, closed)

    private fun writeLog(gigId: String, edit: (StoredLog) -> StoredLog) = gigController.writeLog(gigId, edit)

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

    fun moveGigMedia(setlistId: String, mediaId: String, band: Band, index: Int) =
        gigMedia.moveGigMedia(setlistId, mediaId, band, index)

    fun setGigNote(setlistId: String, band: Band, text: String) = gigController.setGigNote(setlistId, band, text)

    fun setGigVerdict(setlistId: String, noteId: String, verdict: String?) =
        gigController.setGigVerdict(setlistId, noteId, verdict)

    fun adoptSetlistLink(gigId: String, linkOrId: String) = gigController.adoptSetlistLink(gigId, linkOrId)

    private suspend fun adoptSetlist(gigId: String, setlistId: String, fresh: FmSetlist?, notice: Boolean): Boolean =
        gigController.adoptSetlist(gigId, setlistId, fresh, notice)

    fun removePlannedGig(gigId: String) = gigController.removePlannedGig(gigId)

    fun checkInDue(now: LocalDateTime = LocalDateTime.now()): Boolean = gigController.checkInDue(now)

    fun hasLocationPermission(): Boolean = gigController.hasLocationPermission()

    fun isCheckedIn(gigId: String): Boolean = gigController.isCheckedIn(gigId)

    fun offerCheckIn() = gigController.offerCheckIn()

    fun dismissCheckInOffer() = gigController.dismissCheckInOffer()

    fun checkIn(gigId: String) = gigController.checkIn(gigId)

    fun refreshGossip() = gossipController.refresh()

    fun selectGossipGig(gigId: String) = gossipController.selectGig(gigId)

    fun selectSetlist(setlist: FmSetlist) = playlist.selectSetlist(setlist)
    fun toggleIncluded(index: Int) = playlist.toggleIncluded(index)
    fun chooseCandidate(index: Int, track: SpotifyTrack) = playlist.chooseCandidate(index, track)
    fun setPlaylistName(name: String) = playlist.setPlaylistName(name)
    fun setPlaylistPublic(public: Boolean) = playlist.setPlaylistPublic(public)
    fun discoverFriendFromPlaylist(link: String) = playlist.discoverFriendFromPlaylist(link)
    fun loadCoverCandidates() = playlist.loadCoverCandidates()
    fun setCover(uri: Uri?) = playlist.setCover(uri)
    fun setCoverFrame(atMs: Long) = playlist.setCoverFrame(atMs)
    fun researchSong(index: Int, query: String) = playlist.researchSong(index, query)
    fun createPlaylist() = playlist.createPlaylist()
    fun removePlaylist(setlistId: String, url: String) = playlist.removePlaylist(setlistId, url)

    fun isVideoCover(uri: Uri): Boolean = photos.isVideo(uri)

    suspend fun videoDurationMs(uri: Uri): Long = photos.videoDurationMs(uri)

    suspend fun videoFrameAt(uri: Uri, atMs: Long): Bitmap? = photos.videoFrameAt(uri, atMs)

    fun addGigPhotos(setlistId: String, uris: List<Uri>, band: Band = Band.VAULT) =
        gigMedia.addGigPhotos(setlistId, uris, band)

    fun addPickedGigPhotos(setlistId: String, uris: List<Uri>, band: Band = Band.VAULT) =
        gigMedia.addPickedGigPhotos(setlistId, uris, band)

    fun removeGigPhoto(setlistId: String, uri: Uri) = gigMedia.removeGigPhoto(setlistId, uri)

    fun songOffsets(mediaId: String?, songCount: Int): List<Long> = gigMedia.songOffsets(mediaId, songCount)

    fun stampSong(mediaId: String, index: Int, atMs: Long, songCount: Int) =
        gigMedia.stampSong(mediaId, index, atMs, songCount)

    fun loadGigPhotoSuggestions() = gigMedia.loadGigPhotoSuggestions()

    suspend fun photoPreview(uri: Uri): MediaThumb = gigMedia.photoPreview(uri)

    fun isVideo(uri: Uri): Boolean = gigMedia.isVideo(uri)

    suspend fun fullPhoto(uri: Uri): Bitmap? = gigMedia.fullPhoto(uri)

    private fun setGigMedia(setlistId: String, media: List<StoredMedia>) {
        _state.update { it.copy(mediaBySetlist = it.mediaBySetlist + (setlistId to media)) }
        viewModelScope.launch { timelines.saveMedia(setlistId, media) }
    }

}
