package io.github.magnusencoded.stationtostation.data.gossip

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

/**
 * One live connection, from whichever end opened it.
 *
 * [contactKey] is null until the connection has named somebody, and that is the honest state
 * rather than a gap to be filled in: a device is a MAC address until it either offers a
 * challenge this device can resolve or writes a **Pass** whose signature checks out. A screen
 * that guessed a name before then would be showing an unproven claim.
 */
data class GossipPeer(
    val address: String,
    val contactKey: String? = null,
    val since: Instant = Instant.now(),
)

/** The last thing worth a line of text, and when it happened. */
data class GossipNote(val text: String, val at: Instant = Instant.now())

/**
 * What the radio is doing right now.
 *
 * [inbound] is a map because any number of peers may dial this device's server at once;
 * [outbound] is a single value because [GossipCentral][io.github.magnusencoded.stationtostation.ble.GossipCentral]
 * opens one connection at a time, deliberately — see its `busy` flag.
 */
data class GossipStatus(
    val running: Boolean = false,
    val scanning: Boolean = false,
    val advertising: Boolean = false,
    val inbound: Map<String, GossipPeer> = emptyMap(),
    val outbound: GossipPeer? = null,
    val note: GossipNote? = null,
    /** Why the relay is or is not running, from the last [GossipService.sync]. */
    val gate: String? = null,
)

/**
 * The radio's state, for a screen to show while somebody stands there watching for it.
 *
 * **Diagnostic, and process-wide on purpose.** [GossipService] runs in this app's process, so
 * a `StateFlow` reaches the UI without a binder, a broadcast, or a second copy of the radio's
 * state kept somewhere it can go stale. Nothing here is persisted and nothing here is a
 * source of truth — [GossipStore] is, and every acceptance decision is still the storm-gate's.
 * Losing all of it to a process death is correct rather than a gap: a connection that did not
 * survive the process was not live.
 *
 * Written from binder threads and the main thread both, which is why it is a `MutableStateFlow`
 * updated with [update] rather than a var: the peripheral half's callbacks and the central
 * half's overlap freely, and a read-modify-write of a map between them would lose peers.
 */
object GossipRadioStatus {

    private val _status = MutableStateFlow(GossipStatus())
    val status: StateFlow<GossipStatus> = _status.asStateFlow()

    fun radioStarted() = _status.update { it.copy(running = true) }

    /** Everything the radio knew, gone — except why it was meant to be running. */
    fun radioStopped() = _status.update { GossipStatus(gate = it.gate) }

    fun gate(text: String) = _status.update { it.copy(gate = text) }

    fun scanning(on: Boolean) = _status.update { it.copy(scanning = on) }

    fun advertising(on: Boolean) = _status.update { it.copy(advertising = on) }

    fun peerArrived(address: String) = _status.update {
        it.copy(inbound = it.inbound + (address to GossipPeer(address)))
    }

    /** A **Pass** from this address proved whose it was. */
    fun peerNamed(address: String, contactKey: String) = _status.update {
        val peer = it.inbound[address] ?: return@update it
        it.copy(inbound = it.inbound + (address to peer.copy(contactKey = contactKey)))
    }

    fun peerLeft(address: String) = _status.update { it.copy(inbound = it.inbound - address) }

    fun pushOpened(address: String) = _status.update { it.copy(outbound = GossipPeer(address)) }

    /** The challenge resolved: this is who the connection is with. */
    fun pushNamed(contactKey: String) = _status.update {
        it.copy(outbound = it.outbound?.copy(contactKey = contactKey))
    }

    fun pushClosed() = _status.update { it.copy(outbound = null) }

    fun note(text: String) = _status.update { it.copy(note = GossipNote(text)) }
}
