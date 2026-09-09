package io.github.magnusencoded.stationtostation.data.gossip

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

/**
 * Which **Contacts** this phone has actually spoken to, and when — the one fact behind both
 * places that say "is also here".
 *
 * It lives here rather than inside [GossipService] because two surfaces need it and they must
 * not disagree: the notification is built on a clock in the service, and the **Exchange**
 * screen redraws on its own. A second copy of this map in the UI would drift from the one the
 * shade was built from, and the two would name different people in the same room.
 *
 * **Spoken to**, not seen. A radio advertisement is not presence — the token in it is
 * deliberately unlinkable, and a scan hit only says something is transmitting. A **Contact**
 * lands here when a signature has proved whose phone it was, in one direction or the other.
 * That is a stronger claim than proximity and a much weaker one than a check-in, which is
 * right for a line that only ever says somebody is nearby.
 *
 * In memory and process-wide on purpose. Nothing here is persisted, because presence that did
 * not survive the process was not presence: a phone that has been restarted has heard from
 * nobody since, and saying otherwise would be inventing a room. [GossipPolicy]'s
 * [gossipNearby] is what turns these timestamps into an answer; this only records them.
 */
object GossipPresence {

    private val _metAt = MutableStateFlow<Map<String, Instant>>(emptyMap())

    /** Every **Contact** heard from since the process started, most of them long stale. */
    val metAt: StateFlow<Map<String, Instant>> = _metAt.asStateFlow()

    /** These two phones just spoke, so this **Contact** is here. */
    fun met(contact: String, now: Instant = Instant.now()) = _metAt.update { it + (contact to now) }

    /** The relay stopped, so this phone is no longer in a position to claim anyone is near. */
    fun forget() = _metAt.update { emptyMap() }
}
