package io.github.magnusencoded.stationtostation.features.contacts

import android.net.Uri
import io.github.magnusencoded.stationtostation.ErrorKind
import io.github.magnusencoded.stationtostation.SetlistSource
import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.ble.ProbeCard
import io.github.magnusencoded.stationtostation.ble.probeCardFor
import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.FriendArrival
import io.github.magnusencoded.stationtostation.data.SettingsRepository
import io.github.magnusencoded.stationtostation.data.TimelineLogic
import io.github.magnusencoded.stationtostation.data.TimelineStore
import io.github.magnusencoded.stationtostation.data.exchange.ContactExchange
import io.github.magnusencoded.stationtostation.data.exchange.ExchangePeer
import io.github.magnusencoded.stationtostation.data.exchange.ExchangeSession
import io.github.magnusencoded.stationtostation.data.exchange.contactIdentityPublicKeyBase64
import io.github.magnusencoded.stationtostation.data.friendArrival
import io.github.magnusencoded.stationtostation.data.friendFromUri
import io.github.magnusencoded.stationtostation.data.holdLanes
import io.github.magnusencoded.stationtostation.data.laneKey
import io.github.magnusencoded.stationtostation.data.laneNeedsFetch
import io.github.magnusencoded.stationtostation.data.landNights
import io.github.magnusencoded.stationtostation.data.mySpine
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.SetlistFmClient
import io.github.magnusencoded.stationtostation.data.spineDismissals
import io.github.magnusencoded.stationtostation.data.spineJoins
import io.github.magnusencoded.stationtostation.data.spotify.SpotifyClient
import io.github.magnusencoded.stationtostation.data.toShareUri
import io.github.magnusencoded.stationtostation.data.withFriend
import io.github.magnusencoded.stationtostation.ui.MaybeNight
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Friends and the Exchange: who is on my timeline, how they got there, and whose
 * **Line** is held.
 *
 * Cross-feature work arrives as injected functions so the controller depends on no
 * other feature.
 */
class ContactsController(
    private val state: () -> UiState,
    private val update: ((UiState) -> UiState) -> Unit,
    private val settings: SettingsRepository,
    private val timelines: TimelineStore,
    private val spotify: SpotifyClient,
    private val setlistFm: SetlistFmClient,
    private val logic: TimelineLogic,
    private val exchange: ExchangeSession,
    private val contactExchange: ContactExchange,
    private val scope: CoroutineScope,
    private val fail: (Exception) -> Unit,
    private val errorKindOf: (Throwable) -> ErrorKind?,
    private val isSharedQuota: (Throwable) -> Boolean,
    private val adoptSetlist: suspend (String, String, FmSetlist?, Boolean) -> Boolean,
    private val syncGossip: suspend () -> Unit,
) {

    /** My shareable identity card, or null until I've set my setlist.fm username. */
    suspend fun myCardUri(): Uri? {
        val me = state().mySetlistFmUser.trim()
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
        scope.launch { addFriendNow(friend) }
    }

    private suspend fun addFriendNow(friend: Friend) {
        when (val arrival = friendArrival(friend, state().friends)) {
            is FriendArrival.Unchanged -> Unit
            is FriendArrival.New -> writeFriend(arrival.friend)
            // A **Followed line** becoming a **Contact**. Written as silently as a new
            // one: there was no key held, so nothing is being overwritten.
            is FriendArrival.Promotion -> writeFriend(arrival.friend)
            is FriendArrival.Conflict ->
                update { it.copy(friendConflict = arrival) }
        }
    }

    fun confirmFriendOverwrite() {
        val pending = state().friendConflict ?: return
        update { it.copy(friendConflict = null) }
        scope.launch { writeFriend(pending.incoming) }
    }

    fun dismissFriendOverwrite() = update { it.copy(friendConflict = null) }

    private suspend fun writeFriend(friend: Friend) {
        // De-duped on the key, then the username (#405), and never dropping a key or a
        // username a thinner card is silent about. See [withFriend].
        val next = withFriend(state().friends, friend)
        settings.saveFriends(next)
        update { it.copy(friends = next) }
    }

    fun addFriendByUsername(username: String) {
        val u = username.trim()
        if (u.isNotEmpty()) addFriend(Friend(setlistfm = u))
    }

    fun handleFriendLink(uri: Uri) {
        friendFromUri(uri)?.let { addFriend(it) }
    }

    fun removeFriend(friend: Friend) {
        scope.launch {
            // By Lane, not by username: two Contacts without an account share a blank one.
            val next = state().friends.filterNot { it.laneKey == friend.laneKey }
            settings.saveFriends(next)
            update { it.copy(friends = next) }
        }
    }

    /**
     * A **Contact**'s **Nights**, off a **Reconcile** (#405): held under their Lane, on disk
     * and on screen, so the Lane draws now and after a relaunch without asking anyone.
     * [contactKey] is the key that verified; a Contact removed mid-session lands nothing.
     */
    internal suspend fun landContactNights(contactKey: String, nights: List<FmSetlist>) {
        val friend = state().friends.firstOrNull { it.publicKey?.trim() == contactKey.trim() } ?: return
        val key = friend.laneKey
        timelines.mergeContactNights(key, nights)
        update {
            it.copy(showsByFriend = it.showsByFriend + (key to landNights(it.showsByFriend[key], nights)))
        }
    }

    /**
     * "Same Night" to a *maybe* (#405): their Night [night] is joined to my Night [key].
     * Mine alone — nothing is sent — and from here the Spine draws it **Joined** and what
     * they send for it lands directly.
     */
    fun joinNight(night: String, key: String) = scope.launch {
        timelines.joinNight(night, key)
        val cache = timelines.load()
        update { it.copy(nightJoins = cache.spineJoins()) }
    }

    /** "Not the same" to a *maybe* (#405): the marker goes, and stays gone. Mine alone. */
    fun dismissMaybe(night: String, key: String) = scope.launch {
        timelines.dismissMaybe(night, key)
        val cache = timelines.load()
        update { it.copy(nightsApart = cache.spineDismissals()) }
    }

    /**
     * "Same night", then "Take it" (#580): my typed-by-hand Night adopts their setlist.fm
     * entry, so both Nights answer to one id and meet without a join. Where the adoption
     * can't happen (the Night already took an id) it falls back to [joinNight].
     */
    fun adoptMaybe(maybe: MaybeNight) = scope.launch {
        if (!adoptSetlist(maybe.mine.id, maybe.theirs.id, null, true)) {
            joinNight(maybe.theirs.id, maybe.mine.id).join()
        }
    }

    /** Undo of [joinNight] (#580): the *maybe* is asked again. */
    fun unjoinNight(night: String, key: String) {
        scope.launch {
            timelines.unjoinNight(night, key)
            val cache = timelines.load()
            update { it.copy(nightJoins = cache.spineJoins()) }
        }
    }

    /** Undo of [dismissMaybe] (#580): the *maybe* is asked again. */
    fun undismissMaybe(night: String, key: String) {
        scope.launch {
            timelines.undismissMaybe(night, key)
            val cache = timelines.load()
            update { it.copy(nightsApart = cache.spineDismissals()) }
        }
    }

    /** Loads a friend's whole attended-concert timeline for the Connect screen. */
    fun viewFriendTimeline(friend: Friend) {
        // Nobody to ask about a Contact with no account: what the Reconcile brought is the
        // whole of their Line, and it is already here (#405).
        if (friend.setlistfm.isBlank()) {
            update {
                it.copy(
                    viewingFriend = friend,
                    viewedFriendShows = it.showsByFriend[friend.laneKey].orEmpty(),
                    viewedFriendLoading = false,
                )
            }
            return
        }
        update {
            it.copy(viewingFriend = friend, viewedFriendShows = emptyList(), viewedFriendLoading = true)
        }
        scope.launch {
            try {
                // The same runaway guard the shared-concerts lookup uses, named once.
                val shows = attendedConcerts(friend.setlistfm, maxPages = TimelineLogic.ATTENDED_PAGE_CAP)
                update { it.copy(viewedFriendShows = shows, viewedFriendLoading = false) }
                // What this screen just learned is the **Line** too: the timelines view
                // must never be behind it.
                landLine(friend, shows)
            } catch (e: Exception) {
                update {
                    it.copy(
                        viewedFriendLoading = false,
                        error = e.message ?: "Could not load ${friend.name}'s shows",
                        errorKind = errorKindOf(e),
                        setlistFmSharedQuotaSpent =
                            it.setlistFmSharedQuotaSpent || isSharedQuota(e),
                    )
                }
            }
        }
    }

    /** [shows] fresh from setlist.fm, held as [friend]'s **Line** on screen and on disk. */
    private suspend fun landLine(friend: Friend, shows: List<FmSetlist>) {
        val fetched = mapOf(friend.laneKey to shows)
        update { it.copy(showsByFriend = holdLanes(it.showsByFriend, fetched)) }
        timelines.save(shows = fetched)
    }

    /**
     * Asks setlist.fm for [friend]'s **Line** again, back to my oldest **Gig** or to the
     * oldest one held, whichever is older, so the answer never cuts a **Line** short.
     * A failure keeps the last good copy.
     */
    internal fun refreshLine(friend: Friend) {
        if (friend.setlistfm.isBlank()) return
        val oldest = listOfNotNull(
            state().setlists.mapNotNull { it.localDate() }.minOrNull(),
            state().showsByFriend[friend.laneKey].orEmpty().mapNotNull { it.localDate() }.minOrNull(),
        ).minOrNull()
        scope.launch {
            runCatching { attendedBackTo(friend.setlistfm, oldest) }.getOrNull()
                ?.let { landLine(friend, it) }
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
        val me = state().mySetlistFmUser.trim()
        // Either of us without an account: the intersection is of what this phone already
        // holds — my Spine and their Lane — rather than of two setlist.fm lists (#405).
        if (friend.setlistfm.isBlank() || me.isEmpty()) {
            val theirs = state().showsByFriend[friend.laneKey].orEmpty().mapTo(HashSet()) { it.id }
            update {
                it.copy(
                    sharedWith = friend,
                    source = SetlistSource.USER,
                    setlistsTitle = "You & ${friend.name}",
                    setlists = emptyList(),
                    setlistsPage = 1,
                    setlistsTotal = 0,
                    setlistsLoading = true,
                )
            }
            scope.launch {
                val shared = timelines.load().mySpine(me).filter { it.id in theirs }
                update {
                    it.copy(setlists = shared, setlistsTotal = shared.size, setlistsLoading = false)
                }
            }
            return
        }
        update {
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
        scope.launch {
            try {
                // The intersection and its paging cap are the logic layer's; see
                // TimelineLogic.ATTENDED_PAGE_CAP for what raising it would cost.
                val shared = logic.sharedConcerts(me, friend.setlistfm)
                update {
                    // total == size so loadMoreSetlists() won't try to paginate this list.
                    it.copy(setlists = shared, setlistsTotal = shared.size, setlistsLoading = false)
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

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
    private fun myCard(): Friend? = state().mySetlistFmUser.trim()
        .ifBlank { null }
        ?.let { Friend(setlistfm = it, name = it) }

    /**
     * My card for the radio: the public key #28 makes the identity, and a username only if
     * I have one (#405). Without one it is named by [UiState.myCardName]; see
     * [probeCardFor]. Only the radio carries this — a link cannot carry a key, so the QR
     * and share link stay username-only.
     */
    private fun myProbeCard(): ProbeCard? = probeCardFor(
        setlistfm = state().mySetlistFmUser,
        name = state().myCardName,
        publicKey = contactIdentityPublicKeyBase64(),
    )

    /** The name on a card with no username (#405). Restarts a running Exchange to hand it over. */
    fun saveMyCardName(name: String) {
        val trimmed = name.trim()
        scope.launch {
            settings.saveMyCardName(trimmed)
            update { it.copy(myCardName = trimmed) }
            if (state().discovering || state().exchangePeers.isNotEmpty()) {
                exchange.restart(myCard(), myProbeCard())
            }
        }
    }

    /**
     * Opens the Exchange: start every radio in parallel and collect whoever turns up.
     * People appear as they come into range, so the list is a live view of the room.
     */
    fun startExchange() {
        // No username is not a reason to keep anyone off this screen. It only means
        // there is no card to hand over, so the advertising radios stay quiet while
        // scanning runs as usual — the room is still visible, and a card handed to me
        // is still mine to take (#225's "you can take their card", now actually wired).
        //
        // This used to return early with `error` set. `error` is a failure channel and
        // this screen hosts no snackbar, so the message surfaced on whatever screen the
        // user opened next, reading as a fault on an unrelated page.
        update { it.copy(discovering = true, exchangePeers = emptyList(), connectingWith = null) }
        exchange.start(myCard(), myProbeCard())
    }

    /** Pulled down on the exchange screen: drop everything and listen again. */
    fun restartExchange() {
        update { it.copy(discovering = true, exchangePeers = emptyList()) }
        exchange.restart(myCard(), myProbeCard())
    }

    fun stopExchange() {
        exchange.stop()
        update { it.copy(discovering = false, exchangePeers = emptyList(), connectingWith = null) }
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
        update { it.copy(connectingWith = peer.name) }
        exchange.connect(peer) { friend ->
            if (friend == null) {
                // Back to the live list — the radios never stopped, and the QR offer is
                // already on screen. A dangling snackbar (this screen has no host) would
                // only resurface on the next one.
                update { it.copy(connectingWith = null) }
                return@connect
            }
            scope.launch { bringIn(friend) }
        }
    }

    /**
     * The landing an Exchange ends on, whichever side tapped: persist, say it happened,
     * draw the line, and stop the radios — holding a card is the end of looking.
     */
    internal suspend fun bringIn(friend: Friend) {
        // Persist the friend before loading, or the load runs against the old list.
        addFriendNow(friend)
        // A card that would change someone I already hold has written nothing and left a
        // question open (#188). Landing anyway would report a swap that did not happen —
        // and stopping the radios mid-exchange is exactly what a hostile write wants.
        if (state().friendConflict != null) return
        update { it.copy(justConnected = true, connectingWith = null) }
        loadFriendTimelines()
        exchange.stop()
        // A first **Contact** is the moment the gossip radio stops being pointless (#416).
        syncGossip()
    }

    fun consumeJustConnected() = update { it.copy(justConnected = false) }

    /** Loads every known friend's attended shows for the woven (zoomed-out) view. */
    fun loadFriendTimelines() {
        val friends = state().friends
        if (friends.isEmpty()) return
        val myOldest = state().setlists.mapNotNull { it.localDate() }.minOrNull()
        // Cached-and-complete is the common case, and refetching every lane on every
        // zoom-out is the call volume the store exists to remove — but a lane cut off
        // at 60 shows is not complete, however cached it is. See [laneNeedsFetch].
        val stale = friends.filter { friend ->
            laneNeedsFetch(friend, state().showsByFriend[friend.laneKey], myOldest)
        }
        if (stale.isEmpty()) return
        update { it.copy(timelinesLoading = true) }
        scope.launch {
            // A failed fetch is left out entirely, so the friend keeps their last good
            // lane; an empty answer is kept, so it is not asked for again (#405).
            val loaded = stale.mapNotNull { friend ->
                runCatching { attendedBackTo(friend.setlistfm, myOldest) }.getOrNull()
                    ?.let { friend.setlistfm to it }
            }.toMap()
            update {
                it.copy(showsByFriend = holdLanes(it.showsByFriend, loaded), timelinesLoading = false)
            }
            timelines.save(shows = loaded)
        }
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
        if (state().friends.any { !it.publicKey.isNullOrBlank() }) contactExchange.start()
    }

    /** Called when the Exchange screen goes away — see [contactExchange]'s doc comment. */
    fun stopContactExchange() = contactExchange.stop()
}
