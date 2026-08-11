package io.github.magnusencoded.stationtostation.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The envelope a handover manifest travels in (#142).
 *
 * **This is not the wire.** Confidentiality is the transport's job and the transport is
 * a standard one — TLS over the local link, keyed from the QR handshake, chosen so that
 * cipher suite negotiation handles the spread between a phone with AES instructions and
 * one without, rather than us writing and maintaining that switch. What is here is the
 * part that is decidable without a radio: **authenticating the manifest, as one unit,
 * before anything is written.**
 *
 * That is where the integrity budget goes, and the reason is asymmetric. A corrupt photo
 * is a wasted transfer and a visibly broken thumbnail. A corrupt manifest is silent and
 * semantic: a photograph attaching to the wrong night, or the **Personal** bit flipping
 * — the highest-stakes single bit in the payload, since flipping it exposes something
 * withheld with nothing in the UI to say so.
 */

/**
 * A manifest and the tag that says it arrived as it left.
 *
 * [alg] is carried rather than assumed, because the friend case will need a different
 * answer. Here both ends are the *same person's* devices holding the same key from the
 * QR, so a symmetric MAC is the right primitive and needs no PKI: there is no third
 * party to convince, only the two ends to agree. A **Contact**'s manifest will have to
 * be verified against their public key (#28), which is asymmetric, and naming the
 * algorithm now is what makes that an added value rather than a format change.
 */
@Serializable
data class SealedManifest(
    val alg: String = HMAC_SHA256,
    /** The manifest, as the exact bytes that were authenticated. */
    val payload: String = "",
    val mac: String = "",
) {
    companion object {
        const val HMAC_SHA256 = "HMAC-SHA256"
    }
}

private val wireJson = Json { encodeDefaults = true }

/**
 * Seals [manifest] with the key the QR carried.
 *
 * **The per-category counts are computed here**, so they are inside the tag rather than
 * beside it. "The manifest said 48 personal images and we received 48" only means
 * anything if the expected count cannot be truncated alongside the payload it describes.
 */
fun sealManifest(key: ByteArray, manifest: HandoverManifest): SealedManifest {
    val counted = manifest.copy(counts = manifest.media.groupingBy { it.category }.eachCount())
    val payload = wireJson.encodeToString(counted)
    return SealedManifest(payload = payload, mac = tag(key, payload))
}

/**
 * The manifest, or **null** if it does not verify — which is the whole contract: a
 * manifest that fails writes *nothing*, and `handoverPlan` takes that verdict as its
 * `verified` argument rather than deciding it. Failure is total by construction, not by
 * a caller remembering to check.
 *
 * Null covers every way this can go wrong — wrong key, altered payload, altered tag, an
 * algorithm we do not implement, payload that will not parse — because they are the same
 * outcome and distinguishing them for the sender's benefit is how a verifier grows an
 * oracle.
 */
fun openManifest(key: ByteArray, sealed: SealedManifest): HandoverManifest? {
    if (sealed.alg != SealedManifest.HMAC_SHA256) return null
    // Constant time: a byte-by-byte compare that returns early leaks how much of a
    // forged tag was right, which is enough to build the rest of it.
    if (!MessageDigest.isEqual(decode(sealed.mac), decode(tag(key, sealed.payload)))) return null
    return runCatching { wireJson.decodeFromString<HandoverManifest>(sealed.payload) }.getOrNull()
}

/**
 * Does what arrived match what the manifest said would? A count that disagrees means a
 * truncated transfer, which otherwise looks exactly like a smaller library.
 *
 * Asked of the *opened* manifest, so the numbers being compared are inside the tag.
 */
fun countsAgree(manifest: HandoverManifest, arrived: List<OfferedMedia>): Boolean =
    manifest.counts == arrived.groupingBy { it.category }.eachCount()

private fun tag(key: ByteArray, payload: String): String {
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
    return Base64.getEncoder().encodeToString(mac.doFinal(payload.toByteArray(Charsets.UTF_8)))
}

/** A tag that will not decode is a tag that does not match; never an exception. */
private fun decode(s: String): ByteArray = runCatching { Base64.getDecoder().decode(s) }.getOrDefault(ByteArray(0))
