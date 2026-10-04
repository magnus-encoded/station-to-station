package io.github.magnusencoded.stationtostation.features.gossip

import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.data.TimelineStore
import io.github.magnusencoded.stationtostation.data.gossip.GigIdentity
import io.github.magnusencoded.stationtostation.data.gossip.GossipEnvelope
import io.github.magnusencoded.stationtostation.data.gossip.GossipStore
import io.github.magnusencoded.stationtostation.data.gossip.gossipActiveGigId
import io.github.magnusencoded.stationtostation.data.gossip.gossipActiveUntil
import io.github.magnusencoded.stationtostation.data.gossip.gossipExpiry
import io.github.magnusencoded.stationtostation.data.gossip.gossipParticipationEnds
import io.github.magnusencoded.stationtostation.data.gossip.gossipStoppedGigs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

/**
 * Distributes facts this phone holds. Check-in establishes an attendance fact and calls
 * [gossipAbout] to propagate it; the radio and the **Presence rows** follow from [sync].
 *
 * [radio] is told when the gossip service has a reason to run, and until when.
 */
class GossipController(
    private val state: () -> UiState,
    private val update: ((UiState) -> UiState) -> Unit,
    private val gossip: GossipStore,
    private val timelines: TimelineStore,
    private val radio: (activeUntil: Instant?) -> Unit,
    private val scope: CoroutineScope,
) {
    fun block(author: String) {
        scope.launch {
            gossip.updatePublic(System.currentTimeMillis()) { it.blocked.add(it.recognition[author] ?: author) }
        }
    }

    /**
     * Author this phone's own check-in as a public **Envelope** and start carrying it.
     *
     * It goes into the same store, and through the same
     * [receive][io.github.magnusencoded.stationtostation.data.gossip.PublicGossipState.receive],
     * that an envelope arriving off the radio does — `local = true` marking only that the
     * transport did not vouch for the sender, because there was no transport. There is no
     * second authoring path to keep working, and no way for a check-in this device made to
     * be shaped differently from one it relays.
     *
     * **The author is a temporary **Gig** key, not this device's **Contact** identity.** The
     * scope is bound to the *local* **Gig** rather than its external id, so a setlist.fm id
     * arriving later does not rotate who the night's entries were written by; the durable
     * Contact key appears nowhere on the wire, only inside the masked attribution proof.
     *
     * Quietly does nothing where there is nothing to do: a **Gig** with no date to expire
     * against, no local **Gig** to bind a scope to, or a signer that refuses. A check-in is a
     * fact about this timeline first; whether anyone hears about it is secondary, and an
     * error about the secondary thing would be noise on a night out.
     */
    suspend fun gossipAbout(gigId: String) {
        val cache = timelines.load()
        val localGig = cache.gigs[gigId] ?: cache.gigForSetlist(gigId) ?: return
        val gigDate = runCatching { LocalDate.parse(localGig.date,
            java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy")) }.getOrNull() ?: return
        val authorScope = gossip.authorScope(localGig.id)
        val identity = GigIdentity(authorScope)
        // The same night-end ceiling a relay would have capped the claim at, so this device
        // asks for exactly as long as a stranger carrying for it would have allowed.
        val createdAt = Instant.now()
        val public = GossipEnvelope(
            gigId = gigId, scope = authorScope, author = identity.publicKey(),
            createdAt = createdAt.toEpochMilli(), expiresAt = gossipExpiry(gigDate).toEpochMilli(),
            kind = "request",
            attribution = identity.attribution(),
        ).signed(identity::sign)
        if (public != null) {
            val now = System.currentTimeMillis()
            gossip.updatePublic(now) { it.receive(public, "", now, local = true) }
        }
    }

    /**
     * Bring the gossip radio into line with the one reason it may run: a **Gig** on this
     * timeline that has been checked in to and whose participation has not ended.
     *
     * Called from every place that answer can change: a launch, a check-in, a **Log** edit
     * that completes or reopens the set, a stop. The decision itself is
     * [gossipRelayShouldRun][io.github.magnusencoded.stationtostation.data.gossip.gossipRelayShouldRun],
     * which is where it is argued and where a reviewer should push back on it.
     */
    suspend fun sync() {
        val stoppedAt = gossip.stoppedAt()
        radio(gossipActiveUntil(timelines, stoppedAt))
        // What the **Presence rows** draw, read at the same moment as what the radio is told —
        // two answers a moment apart would light a bullet for a night the service has just
        // stopped transmitting for.
        val cache = timelines.load()
        val active = gossipActiveGigId(timelines, stoppedAt, gossip.selectedGigId(), System.currentTimeMillis())
        val eligible = gossipParticipationEnds(timelines)
        val running = gossipParticipationEnds(timelines, stoppedAt)
        update {
            it.copy(
                gossipEligibleUntil = eligible,
                gossipActiveGig = active?.let(cache::keyOf),
                gossipStoppedGigs = gossipStoppedGigs(eligible, running),
            )
        }
    }

    /**
     * Re-read what the **Presence rows** draw, on the **Room**'s own clock.
     *
     * The deadlines are the only thing on this screen that changes without anybody doing
     * anything, and a night's grace running out has to take its bullet with it while somebody is
     * looking at the row — including handing the amber to whichever night is next. Same call as
     * every other input, so the service hears about it too.
     */
    fun refresh() { scope.launch { sync() } }

    /**
     * Stand at this **Gig**: the tap on a **Presence row**.
     *
     * [gigId] is the id the **Room** holds, which is the adopted one where the night has one;
     * the selection is stored under the local id, because that is the only id for a night that
     * cannot change under the device.
     *
     * It mints no **Check-in** and touches no attendance — choosing which night you are standing
     * at is not a claim to have been at it, and the claim was already made by checking in. It
     * does clear a stop, and that is the only thing that clears one: a dim bullet means "could
     * be gossiping, isn't", and tapping it is the explicit Resume the story asks for, where
     * reopening a **Log** deliberately still is not.
     */
    fun selectGig(gigId: String) {
        scope.launch {
            val local = timelines.load().gigs.values.firstOrNull { it.id == gigId || it.setlistId == gigId }
                ?: return@launch
            gossip.selectGig(local.id)
            if (gossip.stoppedAt() > 0) gossip.resumeParticipation()
            sync()
        }
    }
}
