package io.github.magnusencoded.stationtostation.data.exchange

import android.annotation.SuppressLint
import android.content.Context
import io.github.magnusencoded.stationtostation.ble.BleCardCentral
import io.github.magnusencoded.stationtostation.ble.BleCardPeripheral
import io.github.magnusencoded.stationtostation.ble.PeerHit
import io.github.magnusencoded.stationtostation.ble.ProbeCard
import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.isPlausibleSetlistFmUser
import io.github.magnusencoded.stationtostation.data.nearby.NearbyPeers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Someone visible in an **Exchange**, and the card if we already hold it.
 *
 * The two mechanisms reveal a person at different moments, and this is the one place
 * that difference lives — the screen sees only a name and an optional `@username`, and
 * must never be able to tell which radio found someone.
 *
 *  - **Nearby** carries a keyless card in the advertisement, so [setlistfm] is set on sight
 *    and the row can be labelled with a username. The key does not fit an endpoint name
 *    (#272), so [connect][ExchangeSession.connect] still has to go and get it.
 *  - **BLE** gives only a display name in the scan response; [setlistfm] stays null and
 *    the card is read on tap.
 *
 * Either way the tap is a round trip. What differs is how much the row can say first.
 */
data class ExchangePeer(
    /** Dedup key. See [mergePeers] — a username for a Nearby peer, a device address for BLE. */
    val id: String,
    val name: String,
    val setlistfm: String?,
    internal val nearby: Friend? = null,
    internal val ble: PeerHit? = null,
)

/**
 * The dedup decision, made here and documented where the next person will find it.
 *
 * **The key is a person's identity (#28), and neither radio has it on first sight.** Nearby
 * hands over a card in the advertisement, but a key does not fit an endpoint name, so that
 * card is keyless and the connection made on tap is what carries the real one (#272). BLE
 * shows a display name in the scan response and only yields the key after a connect. So the
 * same person can legitimately be two arrivals a second apart.
 *
 * **Chosen: never merge across mechanisms on a guessed identity.** A Nearby peer is keyed
 * by the username it already carries; a BLE peer by its (stable, unique) device address.
 * They are only ever the same row once a *key* proves it — which for BLE is after the
 * card is read, by which point the person is already a contact and off this list.
 *
 * The alternative — collapsing a keyless BLE row into a Nearby row with the same display
 * name — is rejected on purpose: two different people who share a name (an "Ozzy" on each
 * platform) would see one row swallow the other, and *a row that vanishes while someone is
 * reaching for it is the one failure this must not have*. The cost of this choice is that a
 * single Android peer, seen by both radios at once, appears twice for a moment. That is a
 * brief duplicate — the tolerable direction — and it self-heals: tapping either row makes
 * them a contact, and both drop off together.
 *
 * BLE hits with no name are dropped: a row you cannot label ("Connecting with …?") is worse
 * than no row, and #30's scan response exists precisely so a real name arrives before any
 * connection.
 */
internal fun mergePeers(nearby: List<Friend>, ble: List<PeerHit>): List<ExchangePeer> {
    val fromNearby = nearby.map {
        ExchangePeer("sfm:${it.setlistfm.lowercase()}", it.name, it.setlistfm, nearby = it)
    }
    val fromBle = ble
        .filter { !it.name.isNullOrBlank() }
        .distinctBy { it.address }
        .map { ExchangePeer("ble:${it.address}", it.name!!, setlistfm = null, ble = it) }
    return fromNearby + fromBle
}

internal fun friendFromCard(card: ProbeCard): Friend? {
    // The meeting only records people this app can draw a line for, which today means a
    // setlist.fm username — the same invariant the Nearby/QR card has always held. A card
    // without one is a contact with no timeline; storing that is the relationship layer's
    // job (#28/#29), not the meeting's.
    // Checked, not merely non-blank: a card is written by any radio in range, and the
    // username goes into a setlist.fm path carrying our API key. See #187, and
    // [isPlausibleSetlistFmUser] for what the rule is and what it deliberately costs.
    val user = card.setlistfm?.trim()?.takeIf { isPlausibleSetlistFmUser(it) } ?: return null
    return Friend(
        setlistfm = user,
        name = card.name.ifBlank { user },
        spotifyId = card.spotifyId,
        publicKey = card.publicKey.trim().ifBlank { null },
    )
}

/**
 * One **Exchange**: the whole "someone is standing next to me" moment, over whatever radio
 * reaches them. Composes the three mechanisms and hands the screen a single list — the user
 * sees one flow, the model keeps its distinction between a followed line and a contact.
 *
 * **Nearby and BLE start together, never in sequence.** You cannot know the other person's
 * platform before you have found them, so a serial ladder makes the mixed-platform pair —
 * the common case in a mixed friend group — wait out one timeout before the other begins.
 * Both radios advertise and scan at once; whichever surfaces the person first wins, and
 * bringing them in stops both.
 */
class ExchangeSession(private val context: Context, scope: CoroutineScope) {

    private val nearby = NearbyPeers(context)
    private val central = BleCardCentral(context)
    private var peripheral: BleCardPeripheral? = null
    private val bleHits = MutableStateFlow<List<PeerHit>>(emptyList())
    private var running = false
    private var myCard: ProbeCard? = null

    /**
     * Someone sent their card to us — the other half of an **Exchange** the peer tapped.
     * Treated exactly as a tapped card: being on this screen and advertising *is* the
     * consent, so it is added, not prompted.
     *
     * **Fires on a binder thread (a BLE write) or the main thread (a Nearby payload), and
     * a consumer must not assume either.** Two feeders since #272; the thread is whatever
     * the radio that reached us dispatches on.
     */
    var onFriendReceived: ((Friend) -> Unit)? = null

    val peers: StateFlow<List<ExchangePeer>> =
        combine(nearby.peers, bleHits) { n, b -> mergePeers(n, b) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val failure: StateFlow<String?> get() = nearby.failure
    fun consumeFailure() = nearby.consumeFailure()
    fun requiredPermissions(): List<String> = nearby.requiredPermissions()
    fun hasPermissions(): Boolean = nearby.hasPermissions()

    init {
        central.onHit = { hit ->
            bleHits.update { list ->
                val i = list.indexOfFirst { it.address == hit.address }
                if (i >= 0) list.toMutableList().also { it[i] = hit } else list + hit
            }
        }
    }

    /** Starts all three radios in parallel. Safe to call again while running. */
    @SuppressLint("MissingPermission")
    fun start(me: Friend?, myCard: ProbeCard?) {
        if (running) return
        running = true
        this.myCard = myCard
        // Both radios deliver a card the same way: whoever tapped, the card that arrives
        // here has a key on it and becomes a contact by one route (#272).
        nearby.onCardReceived = { written ->
            friendFromCard(written)?.let { friend -> onFriendReceived?.invoke(friend) }
        }
        // NearbyPeers.start does its own permission check and reports the failure the
        // whole flow already listens for, so a missing grant surfaces once, not per radio.
        nearby.start(me, myCard)
        if (nearby.hasPermissions()) {
            // The advertising half is the half that needs a card. With no setlist.fm
            // username there is nothing to hand over — but scanning needs nothing of
            // mine, so the screen still finds the room and can still take a card.
            // `readCard` has always tolerated a null card of its own ("one-way
            // exchange", #87); this lets that path actually be reached.
            myCard?.let { card ->
                peripheral = BleCardPeripheral(context, card).also {
                    it.onCardWritten = { written ->
                        friendFromCard(written)?.let { friend -> onFriendReceived?.invoke(friend) }
                    }
                    it.start()
                }
            }
            central.start()
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        running = false
        nearby.onCardReceived = null
        nearby.stop()
        if (hasPermissions()) central.stop()
        peripheral?.let { if (hasPermissions()) it.stop() }
        peripheral = null
        myCard = null
        bleHits.value = emptyList()
    }

    fun restart(me: Friend?, myCard: ProbeCard?) {
        stop()
        start(me, myCard)
    }

    /**
     * Bring one peer in. Both radios go and fetch the card: Nearby opens a connection and
     * swaps cards over it, BLE connects and reads. Either fires with the card, or with null
     * on failure so the caller can fall through to the QR offer.
     *
     * **Nearby used to fire at once with the advertised card** — the zero-length middle of
     * "row → connecting → connected". That card had no key, so the **Exchange** completed
     * and made nobody a **Contact** (#272). The round trip is what buys the key, and a
     * failure now adds nobody rather than adding a keyless line that looks like success.
     *
     * MTU negotiation is skipped: #30's eight-run median put the skip path at ~1170ms
     * inside the 2s budget, and the bump costs more setup than the longer read it saves.
     */
    @SuppressLint("MissingPermission")
    fun connect(peer: ExchangePeer, onCard: (Friend?) -> Unit) {
        // Nearby's swap is mutual by construction — `exchangeCards` needs a card of mine
        // to request the connection with — so with no username that route can only
        // return null. BLE reads one-way, so it is the route that still works; take it
        // rather than the one that would fail and fall through to QR.
        if (myCard != null) {
            peer.nearby?.let { advertised ->
                nearby.exchangeCards(advertised) { card -> onCard(card?.let(::friendFromCard)) }
                return
            }
        }
        val hit = peer.ble ?: run { onCard(null); return }
        central.readCard(hit, negotiateMtu = false, myCard = myCard) { timing ->
            onCard(timing.card?.let(::friendFromCard))
        }
    }
}
