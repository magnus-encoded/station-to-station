package io.github.magnusencoded.stationtostation.data.gossip

import io.github.magnusencoded.stationtostation.data.GOSSIP_MAX_EPOCH_SECOND
import io.github.magnusencoded.stationtostation.data.GossipCheckIn
import java.time.Instant
import java.util.Base64

/**
 * What one device hands another over the gossip channel, as bytes (#416).
 *
 * Pure, and deliberately hand-built rather than left to a serialization library, for the
 * reason [gossipPayload][io.github.magnusencoded.stationtostation.data.gossipPayload]
 * gives: iOS (#417) has to produce the same bytes, and "whatever the two platforms' JSON
 * encoders happen to agree on" is not a specification. Tab-separated fields, newline-
 * separated records — a base64 value contains neither, and a gig id has been through
 * [isSafeGossipId][io.github.magnusencoded.stationtostation.data.isSafeGossipId], so no
 * field can carry its own separator.
 *
 * **Push-only, and this is the whole of it.** No *message* is ever read back from a peer:
 * every device runs both halves of the radio, so a device with something to say connects and
 * writes, and a device with nothing to say never has to be trusted for anything. The one
 * thing that is read is the challenge below, which is unsigned, names nobody and is believed
 * about nothing. That removes the authenticated-read direction entirely — there is no "prove
 * yourself to me so I can believe what you hand back", only "prove yourself to me before I
 * read what you pushed".
 *
 * ## The meeting, in two GATT operations — and they are iOS's two
 *
 * 1. The connecting side **reads the challenge** and gets [GossipChallenge]: a fresh nonce
 *    and the listener's token offer. It resolves the offer against its own token table. No
 *    match and it hangs up, having learnt nothing it did not already know from the
 *    advertisement.
 * 2. It **writes one [GossipPass]**: its own identity key, a signature over
 *    [gossipAuthPayload] of that nonce, and the batch. The listener checks the key is a
 *    **Contact**, checks the signature against the nonce it issued, and hands `(from,
 *    batch)` to the storm-gate.
 *
 * ## The advertised token is a shortcut, not the path
 *
 * [gossipAdvertisedToken][io.github.magnusencoded.stationtostation.data.gossip.gossipAdvertisedToken]
 * puts one token in this device's scan response, which lets an Android scanner recognise
 * another Android without opening a connection at all. **An iPhone cannot do that.**
 * CoreBluetooth's `startAdvertising` honours a local name and a service-UUID list and nothing
 * else, and a *backgrounded* iPhone drops the name and moves its service UUIDs into an
 * overflow area only another iOS device can read.
 *
 * So a scanner that treated missing manufacturer data as "not a **Contact**" would never
 * speak to an iPhone. The challenge read above is the path both platforms share; the
 * advertisement is a faster Android-only route beside it carrying the same token bytes.
 * Exactly the shape the **Card** already has — one payload, a cross-platform BLE route, and
 * an Android-only Nearby route beside it
 * ([NearbyPeers][io.github.magnusencoded.stationtostation.data.nearby.NearbyPeers],
 * ADR-0016).
 *
 * ## Framing, because a **Pass** does not fit in an MTU
 *
 * A **Pass** is written as **sequential chunks, every one at offset 0, terminated by a
 * zero-length write**. Not offsets, and this is not a free choice: a CoreBluetooth central
 * performs no prepared writes and silently truncates a `writeValue` past the negotiated MTU,
 * so an iPhone has to chunk by hand and every chunk it sends arrives looking like the start
 * of the value. Append-and-terminate is the one framing that reads identically on both
 * platforms, so Android writes and accepts it too. A zero-length write is therefore
 * meaningful and never ignored — it is what says "that was the whole **Pass**".
 *
 * The challenge, going the other way, *is* offset-addressed: a GATT read is a long read, the
 * reader asks again at a rising offset until it gets a short answer, and the whole payload is
 * addressable. The two directions are framed differently because GATT frames them
 * differently.
 */

/** The header a **Pass** starts with. Version in the string, same reasoning as elsewhere. */
const val GOSSIP_PASS_V1 = "station-to-station/gossip-pass/1"

/** The header a challenge starts with. */
const val GOSSIP_CHALLENGE_V1 = "station-to-station/gossip-challenge/1"

/**
 * The domain separator for the possession proof.
 *
 * The **Contact** identity key also answers the LAN reconcile challenge (#265) and signs
 * gossip payloads
 * ([GOSSIP_PAYLOAD_V1][io.github.magnusencoded.stationtostation.data.GOSSIP_PAYLOAD_V1]).
 * Three uses, three prefixes, so a signature made for one can never be presented as an
 * answer to another. Reconcile's nonce is a certificate fingerprint — raw digest bytes —
 * so producing one that begins with this ASCII prefix would take a preimage, not a choice.
 */
const val GOSSIP_AUTH_V1 = "station-to-station/gossip-auth/1"

/**
 * How many bytes of nonce a listener demands back.
 *
 * Thirty-two, matching the digest the signature is taken over anyway. The nonce exists so
 * that a recording of yesterday's **Pass** cannot be replayed as today's identity; the only
 * property that has to hold is that a listener never issues the same one twice, which at
 * this width it will not.
 */
const val GOSSIP_NONCE_BYTES = 32

/**
 * The hard ceiling on one **Pass**, in bytes.
 *
 * Not the same bound as
 * [GOSSIP_MAX_BATCH][io.github.magnusencoded.stationtostation.data.GOSSIP_MAX_BATCH] and
 * not a substitute for it: that one is the gate's rule about how many messages are *read*,
 * this one is the transport's rule about how much memory a peer's write is allowed to cost
 * before anything has been decided at all. A hostile **Contact** must not be able to make
 * this device hold a megabyte because it opened a GATT connection.
 *
 * Sixty-four messages at their realistic worst — a 64-character id, a 64-character gig id,
 * a ~124-character key, two timestamps and a ~96-character signature — is a little over
 * 32 KB, so this leaves room without leaving a hole. Anything larger is refused whole
 * rather than truncated: half a **Pass** is not a **Pass**.
 */
const val GOSSIP_MAX_WIRE_BYTES = 40_000

/**
 * What a listener answers the challenge read with: a fresh nonce, and its
 * [gossipTokenOffer] for the current bucket.
 *
 * **The listener never names itself here, and that is the point.** The obvious challenge —
 * "my identity key and a nonce" — would hand a stable, lifelong identifier to any radio that
 * connects, all night, in the background, which is the exact disclosure the rotating
 * **Token** exists to prevent and which ADR-0019 refuses. Instead the listener publishes the
 * per-pair tokens only its **Contacts** can resolve; a stranger reads a set of numbers that
 * mean nothing and are different in a quarter of an hour.
 *
 * The connecting side resolves the offer against its own
 * [gossipTokenTable][io.github.magnusencoded.stationtostation.data.gossip.gossipTokenTable],
 * learns *which* **Contact** it has met, and only then decides what to hand over — which is
 * what [GossipHeld]'s outbox rule needs in order to not hand a message back to its author.
 */
data class GossipChallenge(val nonce: ByteArray, val tokens: List<String>) {
    override fun equals(other: Any?): Boolean =
        other is GossipChallenge && nonce.contentEquals(other.nonce) && tokens == other.tokens

    override fun hashCode(): Int = 31 * nonce.contentHashCode() + tokens.hashCode()
}

/**
 * The challenge on the wire: the header, the base64 nonce, then one hex token per line.
 *
 * Line-separated with no escaping problem to get wrong: base64 contains no newline and a
 * token is 16 hex characters and can contain nothing else.
 */
fun encodeGossipChallenge(challenge: GossipChallenge): ByteArray =
    (listOf(GOSSIP_CHALLENGE_V1, Base64.getEncoder().encodeToString(challenge.nonce)) +
        challenge.tokens.filter(::isSafeGossipToken))
        .joinToString("\n")
        .toByteArray(Charsets.UTF_8)

/**
 * Null for anything that is not a challenge, or whose nonce is not [GOSSIP_NONCE_BYTES].
 *
 * Malformed token lines are dropped rather than failing the whole read: a peer on a later
 * build may publish something this one does not understand, and the tokens it *does*
 * understand are still worth resolving.
 */
fun decodeGossipChallenge(bytes: ByteArray?): GossipChallenge? {
    if (bytes == null || bytes.isEmpty() || bytes.size > GOSSIP_MAX_WIRE_BYTES) return null
    val lines = String(bytes, Charsets.UTF_8).split('\n')
    if (lines.size < 2 || lines[0] != GOSSIP_CHALLENGE_V1) return null
    val nonce = runCatching { Base64.getDecoder().decode(lines[1]) }.getOrNull() ?: return null
    if (nonce.size != GOSSIP_NONCE_BYTES) return null
    return GossipChallenge(nonce, lines.drop(2).filter(::isSafeGossipToken))
}

/** One **Pass**: who claims to be pushing, their proof, and what they push. */
data class GossipPass(
    /** The pushing peer's identity key, base64 X.509 SubjectPublicKeyInfo. */
    val from: String,
    /** Base64 DER over [gossipAuthPayload] of the nonce this device issued. */
    val proof: String,
    val batch: List<GossipCheckIn>,
)

/**
 * The bytes a peer signs to prove it holds the key it claims.
 *
 * The nonce is base64'd rather than concatenated raw so that the payload is text throughout
 * and the two platforms cannot disagree about byte order or padding in the middle of a
 * signed value.
 */
fun gossipAuthPayload(nonce: ByteArray): ByteArray =
    "$GOSSIP_AUTH_V1\n${Base64.getEncoder().encodeToString(nonce)}".toByteArray(Charsets.UTF_8)

/**
 * Null for anything that cannot be encoded unambiguously — a key or signature carrying a
 * separator, or a message [gossipPayload][io.github.magnusencoded.stationtostation.data.gossipPayload]
 * itself would refuse. A message that cannot be written is dropped from the batch rather
 * than failing the whole **Pass**: the rest of the night's news is still worth pushing.
 */
fun encodeGossipPass(pass: GossipPass): ByteArray? {
    if (pass.from.isBlank() || pass.proof.isBlank()) return null
    if (!isWireSafe(pass.from) || !isWireSafe(pass.proof)) return null
    val records = pass.batch.mapNotNull(::encodeRecord)
    val text = (listOf(GOSSIP_PASS_V1, "${pass.from}\t${pass.proof}") + records)
        .joinToString("\n")
    val bytes = text.toByteArray(Charsets.UTF_8)
    return if (bytes.size > GOSSIP_MAX_WIRE_BYTES) null else bytes
}

/**
 * Null for anything that is not a **Pass**: the wrong header, no claim line, an oversized
 * write, or bytes that are not UTF-8.
 *
 * Individual records that do not parse are **skipped, not fatal**. The alternative — one
 * unreadable record discarding a peer's whole batch — hands any device in the chain a way
 * to stop a message it does not like by corrupting the one next to it. Nothing is trusted
 * either way: what survives here still has to get past the storm-gate, which recomputes
 * every id and checks every signature.
 */
fun decodeGossipPass(bytes: ByteArray?): GossipPass? {
    if (bytes == null || bytes.isEmpty() || bytes.size > GOSSIP_MAX_WIRE_BYTES) return null
    val lines = String(bytes, Charsets.UTF_8).split('\n')
    if (lines.size < 2 || lines[0] != GOSSIP_PASS_V1) return null
    val claim = lines[1].split('\t')
    if (claim.size != 2) return null
    val from = claim[0]
    val proof = claim[1]
    if (from.isBlank() || proof.isBlank()) return null
    return GossipPass(from, proof, lines.drop(2).mapNotNull(::decodeRecord))
}

private fun encodeRecord(message: GossipCheckIn): String? {
    val fields = listOf(
        message.messageId,
        message.gigId,
        message.checkedInBy,
        message.checkedInAt.epochSecond.toString(),
        message.expiresAt.epochSecond.toString(),
        message.signature,
    )
    if (fields.any { it.isEmpty() || !isWireSafe(it) }) return null
    return fields.joinToString("\t")
}

/**
 * One record back into a message, or null.
 *
 * The times are parsed with the same bound
 * [GOSSIP_MAX_EPOCH_SECOND][io.github.magnusencoded.stationtostation.data.GOSSIP_MAX_EPOCH_SECOND]
 * states, and for the same reason: a peer's decimal is a peer's decimal, and
 * [Instant.ofEpochSecond] throws on values the gate would otherwise never get to see.
 */
private fun decodeRecord(line: String): GossipCheckIn? {
    val f = line.split('\t')
    if (f.size != 6) return null
    if (f.any { it.isEmpty() }) return null
    val checkedInAt = f[3].toLongOrNull()?.let(::boundedInstant) ?: return null
    val expiresAt = f[4].toLongOrNull()?.let(::boundedInstant) ?: return null
    return GossipCheckIn(
        messageId = f[0],
        gigId = f[1],
        checkedInBy = f[2],
        checkedInAt = checkedInAt,
        expiresAt = expiresAt,
        signature = f[5],
    )
}

internal fun boundedInstant(epochSecond: Long): Instant? =
    if (epochSecond > GOSSIP_MAX_EPOCH_SECOND || epochSecond < -GOSSIP_MAX_EPOCH_SECOND) null
    else Instant.ofEpochSecond(epochSecond)

private fun isWireSafe(value: String): Boolean = !value.contains('\n') && !value.contains('\t')
