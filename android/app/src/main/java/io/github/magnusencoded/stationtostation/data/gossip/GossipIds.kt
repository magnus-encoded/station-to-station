package io.github.magnusencoded.stationtostation.data.gossip

import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.ui.nightWindow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The three rules the gossip channel needs before it has decided anything: what may be
 * used as an id, when a night stops being news, and whose keys are in the audience.
 *
 * They were carved out of the v1 storm-gate when the v1 pipeline was deleted. They are
 * here rather than in [PublicGossip.kt][GossipEnvelope] because they are not the v2 wire
 * format: nothing in this file knows what an envelope looks like. They are the vocabulary
 * every layer above shares — the codec validates ids with [isSafeGossipId], the policy
 * draws expiry with [gossipExpiry], the service draws its audience with [contactKeysOf] —
 * and a file that only the layers above depend on is the one that can be read first.
 */

/**
 * Whether a **Gig** id or an author scope is safe to hold, compare and propagate.
 *
 * Deliberately **stricter than [isSafeMediaId][io.github.magnusencoded.stationtostation.data.isSafeMediaId]**,
 * and deliberately not it, though it is the same shape and exists for the same reason
 * (this id reaches a store as a key and could reach a file path). `isSafeMediaId` is
 * Unicode-aware on both platforms in ways that do not agree: Kotlin measures UTF-16 code
 * units and asks `Char.isLetterOrDigit`, Swift measures grapheme clusters and asks
 * `CharacterSet.alphanumerics`, so an astral-plane alphanumeric or a combining mark is
 * accepted by one twin and refused by the other. On a media id that is a nuisance; on a
 * gossip id it is an envelope that propagates through iPhones and dies at every Android
 * hop, which is the exact asymmetry a ported file exists to prevent.
 *
 * ASCII only, therefore: with no character above 0x7F, code units, scalars, graphemes and
 * bytes are all the same count, and the two implementations cannot read the rule
 * differently. Nothing real is lost — a gig id is a UUID or a setlist.fm id, and a scope is
 * a UUID this device minted.
 */
fun isSafeGossipId(id: String): Boolean =
    id.isNotEmpty() && id.length <= 64 && id.all {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_'
    }

/**
 * When something said about [gigDate] stops being news: the end of that gig's own night.
 *
 * [nightWindow] is the app's one answer to "is this night still going on", drawn off
 * `NIGHT_ENDS` — a show that ends at 01:30 is still that night — and this reuses it rather
 * than inventing a second expiry rule that could drift from what the **Room** believes.
 *
 * It is the ceiling in two places that must agree:
 * [gossipNightEnds] hands it to the relay as a gig's night end, and an authored
 * [GossipEnvelope]'s `expiresAt` is set from it. A device asks for exactly as long as a
 * stranger relaying for it would have allowed, and no longer.
 */
fun gossipExpiry(gigDate: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Instant =
    nightWindow(gigDate).endInclusive.atZone(zone).toInstant()

/**
 * The keys of everyone I have actually met.
 *
 * Holding a key is exactly what makes a **Contact** — only the radio ever fills
 * [Friend.publicKey] in, in person, and a link or QR code can never carry one (#271). So a
 * **Followed line** simply has nothing to put in this set, which is how "never a Followed
 * line" is enforced: not by a check that could be forgotten, but by there being no key to
 * check.
 *
 * Public gossip v2 no longer relays *to* this set — an envelope is authored under a
 * temporary **Gig** key and carried by any radio in range, which is the whole point of the
 * v2 design — so this is now a count and a lookup rather than an audience: the service
 * asks how many **Contacts** exist to decide whether the relay is worth running, and maps
 * a key to a name for the notification.
 */
fun contactKeysOf(friends: List<Friend>): Set<String> =
    friends.mapNotNullTo(LinkedHashSet<String>()) { friend ->
        friend.publicKey?.takeIf { it.isNotBlank() }
    }
