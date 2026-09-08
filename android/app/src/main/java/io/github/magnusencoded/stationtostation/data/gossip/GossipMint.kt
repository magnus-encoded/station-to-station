package io.github.magnusencoded.stationtostation.data.gossip

import io.github.magnusencoded.stationtostation.data.GossipCheckIn
import io.github.magnusencoded.stationtostation.data.gossipMessageId
import io.github.magnusencoded.stationtostation.data.gossipPayload
import io.github.magnusencoded.stationtostation.data.isSafeGossipId
import java.time.Instant
import java.util.Base64

/**
 * The one message this device ever authors: *I arrived at this Gig, at this time* (#416).
 *
 * Pure, with the signing handed in, so the shape of a minted message can be asserted
 * without an AndroidKeyStore — the same split
 * [signChallenge][io.github.magnusencoded.stationtostation.data.exchange.signChallenge] and
 * [contactIdentityPrivateKey][io.github.magnusencoded.stationtostation.data.exchange.contactIdentityPrivateKey]
 * already draw.
 *
 * A minted message goes through the storm-gate like anyone else's, and that is not
 * ceremony: it is what puts this device's own check-in into the seen set with a capped
 * expiry, so that a copy of it arriving back around the chain is recognised as already seen
 * rather than accepted a second time.
 */

/**
 * A signed check-in, or null when there is nothing honest to sign.
 *
 * Null rather than a partly-formed message for each of the reasons a message could not
 * survive its own gate anyway — an unsafe gig id, an author with no key, an expiry already
 * past, or a signer that failed. Minting something the gate would immediately reject would
 * put a broken message in the outbox and cost every **Contact** in range a verification.
 *
 * [expiresAt] is the end of the gig's own night
 * ([gossipExpiry][io.github.magnusencoded.stationtostation.data.gossipExpiry]), which is
 * the same ceiling a relay caps the claim at — so this device asks for exactly what a
 * stranger would have allowed it, and no more.
 */
fun mintGossipCheckIn(
    gigId: String,
    checkedInBy: String,
    checkedInAt: Instant,
    expiresAt: Instant,
    sign: (ByteArray) -> ByteArray?,
): GossipCheckIn? {
    if (!isSafeGossipId(gigId) || checkedInBy.isBlank()) return null
    if (!expiresAt.isAfter(checkedInAt)) return null
    // The id and the signature are not part of what is signed or hashed, so a draft
    // carrying neither produces exactly the bytes the finished message will.
    val draft = GossipCheckIn(
        messageId = "",
        gigId = gigId,
        checkedInBy = checkedInBy,
        checkedInAt = checkedInAt,
        expiresAt = expiresAt,
        signature = "",
    )
    val payload = gossipPayload(draft) ?: return null
    val messageId = gossipMessageId(draft) ?: return null
    val signature = runCatching { sign(payload) }.getOrNull() ?: return null
    return draft.copy(
        messageId = messageId,
        signature = Base64.getEncoder().encodeToString(signature),
    )
}
