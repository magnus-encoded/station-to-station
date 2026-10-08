package io.github.magnusencoded.stationtostation.features.tour

import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.data.Admission
import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.ParsedTicket
import io.github.magnusencoded.stationtostation.data.QR_SYMBOLOGY
import io.github.magnusencoded.stationtostation.data.laneKey
import io.github.magnusencoded.stationtostation.data.parseFmDate
import io.github.magnusencoded.stationtostation.data.setlistfm.FmCity
import io.github.magnusencoded.stationtostation.data.setlistfm.FmCoords
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmVenue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * The **Demo world**'s **Contact**, their **Line** and the ticket they send (S7-S9).
 *
 * The Exchange happens on this phone only: nothing is sent over a radio. The **Contact** is
 * tagged demo, so a skip or the final purge takes it and their **Line** back; a real
 * **Contact** is never tagged or removed. The ticket sits on the demo **Gig**, which goes
 * with it.
 */
class TourMeetFriendEffects(
    private val friendName: () -> String,
    private val contacts: suspend () -> List<Friend>,
    private val demoGig: suspend () -> FmSetlist?,
    private val locate: suspend () -> Place?,
    private val placeVenue: suspend (FmSetlist) -> Unit,
    private val addContact: suspend (Friend) -> Boolean,
    private val landNights: suspend (String, List<FmSetlist>) -> Unit,
    private val importTicket: suspend (ParsedTicket, String) -> Boolean,
    private val purgeGigs: suspend (Set<String>) -> Set<String>,
    private val purgeContacts: suspend () -> List<Friend>,
    private val update: ((UiState) -> UiState) -> Unit,
    private val mintKey: (() -> String)? = null,
    private val extraNights: (FmSetlist) -> List<FmSetlist> = { emptyList() },
) {
    private val writes = Mutex()
    val name: String get() = friendName()

    /**
     * Lands the friend with the demo **Gig** on their **Line**, its venue where the person
     * stands. No [locate] fix leaves the venue without coordinates, and Check-in falls back
     * to by hand. Does nothing once [active] is false (skipped, replayed).
     */
    suspend fun exchange(active: () -> Boolean, exchanged: (Place?) -> Unit) {
        val fix = locate()
        writes.withLock {
            if (!active()) return
            val gig = demoGig() ?: return
            val held = contacts()
            val friend = held.firstOrNull { it.demo } ?: Friend("", name, publicKey = (mintKey ?: ::mintContactKey)(), demo = true)
            if (!friend.demo || held.any { it.publicKey == friend.publicKey && !it.demo }) return
            if (friend !in held && !addContact(friend)) return
            val placed = placed(gig, fix)
            placeVenue(placed)
            landNights(requireNotNull(friend.publicKey), listOf(placed) + extraNights(placed))
            if (active()) exchanged(fix)
        }
    }

    suspend fun friendKey(): String? = contacts().firstOrNull { it.demo }?.publicKey

    /** The friend's ticket for the demo **Gig**, through the import a shared ticket takes. */
    suspend fun importDemoTicket(active: () -> Boolean): Boolean = writes.withLock {
        if (!active()) return false
        val gig = demoGig() ?: return false
        val ticket = ticket(gig) ?: return false
        importTicket(ticket, gig.id) && active()
    }

    suspend fun purge() = writes.withLock {
        val lanes = contacts().filter { it.demo }.map { it.laneKey }.toSet()
        val taken = purgeGigs(lanes)
        val friends = purgeContacts()
        update {
            it.copy(
                friends = friends,
                showsByFriend = (it.showsByFriend - lanes).mapValues { (_, gigs) -> gigs.filterNot { gig -> gig.id in taken } },
                plannedGigs = it.plannedGigs.filterNot { gig -> gig.id in taken },
                attendanceByGig = it.attendanceByGig - taken,
            )
        }
    }

    companion object {
        fun placed(gig: FmSetlist, location: Place?): FmSetlist {
            val venue = gig.venue ?: FmVenue()
            val city = venue.city ?: FmCity()
            val coords = location?.let { FmCoords(it.latitude, it.longitude) }
            return gig.copy(venue = venue.copy(city = city.copy(coords = coords)))
        }

        fun ticket(gig: FmSetlist): ParsedTicket? {
            val artist = gig.artist?.name?.takeIf { it.isNotBlank() } ?: return null
            val date = gig.eventDate?.takeIf { parseFmDate(it) != null } ?: return null
            return ParsedTicket(
                admissions = listOf(Admission("tour-demo-ticket:${gig.id}".toByteArray(Charsets.UTF_8), QR_SYMBOLOGY)),
                artist = artist,
                venue = gig.venue?.name.orEmpty(),
                date = date,
            )
        }
    }
}

private fun mintContactKey(): String {
    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(ECGenParameterSpec("secp256r1"))
    return Base64.getEncoder().encodeToString(generator.generateKeyPair().public.encoded)
}
