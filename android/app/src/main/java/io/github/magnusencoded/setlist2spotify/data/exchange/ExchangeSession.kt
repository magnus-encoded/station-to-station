package io.github.magnusencoded.setlist2spotify.data.exchange

import android.annotation.SuppressLint
import android.content.Context
import io.github.magnusencoded.setlist2spotify.ble.BleCardCentral
import io.github.magnusencoded.setlist2spotify.ble.BleCardPeripheral
import io.github.magnusencoded.setlist2spotify.ble.PeerHit
import io.github.magnusencoded.setlist2spotify.ble.ProbeCard
import io.github.magnusencoded.setlist2spotify.data.Friend
import io.github.magnusencoded.setlist2spotify.data.nearby.NearbyPeers
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
 *  - **Nearby** carries the whole card in the advertisement, so [setlistfm] is set on
 *    sight and [connect][ExchangeSession.connect] can add them with nothing over the air.
 *  - **BLE** gives only a display name in the scan response; [setlistfm] stays null and
 *    the card is read on tap.
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
 * **The key is a person's identity (#28), but neither radio has it on first sight the
 * same way.** Nearby hands over the whole card — key included — in the advertisement.
 * BLE shows a display name in the scan response and only yields the key after a connect.
 * So the same person can legitimately be two arrivals a second apart.
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

private fun friendFromCard(card: ProbeCard): Friend? {
    // The meeting only records people this app can draw a line for, which today means a
    // setlist.fm username — the same invariant the Nearby/QR card has always held. A card
    // without one is a contact with no timeline; storing that is the relationship layer's
    // job (#28/#29), not the meeting's.
    val user = card.setlistfm?.trim()?.ifBlank { null } ?: return null
    return Friend(setlistfm = user, name = card.name.ifBlank { user }, spotifyId = card.spotifyId)
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
    fun start(me: Friend, myCard: ProbeCard) {
        if (running) return
        running = true
        // NearbyPeers.start does its own permission check and reports the failure the
        // whole flow already listens for, so a missing grant surfaces once, not per radio.
        nearby.start(me)
        if (nearby.hasPermissions()) {
            peripheral = BleCardPeripheral(context, myCard).also { it.start() }
            central.start()
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        running = false
        nearby.stop()
        if (hasPermissions()) central.stop()
        peripheral?.let { if (hasPermissions()) it.stop() }
        peripheral = null
        bleHits.value = emptyList()
    }

    fun restart(me: Friend, myCard: ProbeCard) {
        stop()
        start(me, myCard)
    }

    /**
     * Bring one peer in. Nearby already handed over the whole card, so [onCard] fires at
     * once — the zero-length middle of "row → connecting → connected". BLE has shown only a
     * name, so it connects and reads (up to `EXCHANGE_TIMEOUT_MS`), then fires with the
     * card, or with null on failure so the caller can fall through to the QR offer.
     *
     * MTU negotiation is skipped: #30's eight-run median put the skip path at ~1170ms
     * inside the 2s budget, and the bump costs more setup than the longer read it saves.
     */
    @SuppressLint("MissingPermission")
    fun connect(peer: ExchangePeer, onCard: (Friend?) -> Unit) {
        peer.nearby?.let { onCard(it); return }
        val hit = peer.ble ?: run { onCard(null); return }
        central.readCard(hit, negotiateMtu = false) { timing ->
            onCard(timing.card?.let(::friendFromCard))
        }
    }
}
