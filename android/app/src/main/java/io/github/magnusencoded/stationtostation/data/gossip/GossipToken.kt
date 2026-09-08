package io.github.magnusencoded.stationtostation.data.gossip

import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The rotating value a phone puts in its gossip advertisement so that a **Contact**
 * recognises it and a bystander cannot (#416, part of #408).
 *
 * Pure — no radio, no keystore, no clock — for the same reason the storm-gate
 * ([io.github.magnusencoded.stationtostation.data.gossipStormGate]) is: this is the half
 * that must agree byte for byte with iOS (#417), and a rule that can only be exercised by
 * standing between two phones is a rule that gets checked once and then drifts.
 *
 * **This is a rendezvous hint, not an authentication.** Matching a token is what makes this
 * device bother connecting; it proves nothing about who is on the other end. Possession of
 * the identity key is proved separately, per connection, by
 * [gossipAuthPayload][io.github.magnusencoded.stationtostation.data.gossip.gossipAuthPayload],
 * and every individual message is verified again by its own signature inside the gate. A
 * token that was replayed by a recorder in the room therefore buys an attacker one wasted
 * GATT connection and nothing else.
 *
 * ## Why this is not the ECDH the issue asked for
 *
 * #408 and #416 both describe the token as "an HMAC of the ECDH-shared-secret and a time
 * bucket". **That is not reachable from the key material an Exchange actually leaves
 * behind, and this file is the amendment** (see ADR-0019, "Token derivation", amended
 * 2026-09-08):
 *
 * - The durable **Contact** identity is an AndroidKeyStore ECDSA P-256 key created with
 *   `PURPOSE_SIGN` and nothing else ([contactIdentityPublicKeyBase64][io.github.magnusencoded.stationtostation.data.exchange.contactIdentityPublicKeyBase64]).
 *   AndroidKeyStore keys are immutable, so that key cannot be taught key agreement, and it
 *   is already on every phone that has ever made a **Contact**.
 * - `KeyProperties.PURPOSE_AGREE_KEY` needs API 31; this app is minSdk 26.
 * - A second, agreement-capable keypair would have to reach the peer somehow, and the only
 *   thing that carries a key is the **Card** handed over in person — which is exactly the
 *   "no new pairing step" the issue rules out, and would leave every existing **Contact**
 *   unable to gossip until they met again.
 *
 * So the token is keyed on the material both ends demonstrably hold and nobody else was
 * given: **each other's public keys**. What that costs, stated plainly rather than
 * discovered later: a shared secret would be unguessable to everyone but the pair, whereas
 * this is computable by anyone holding *both* public keys. That set is small but not empty
 * — a mutual **Contact** of both people, and, per ADR-0019's own disclosure clause, a
 * device that once relayed a `GossipCheckIn` authored by one of them and holds the other's
 * key. Such a device can tell that these two specific people are within radio range of it.
 * It learns nothing further: no message is readable, and nothing is accepted from it.
 */

/**
 * The domain separator the token HMAC is taken over.
 *
 * The same public keys key the HMAC and are also the identity a signature is checked
 * against, so the prefix is what keeps "a token for this pair, this quarter-hour" from
 * being confusable with any other use of that material. The version is in the string so a
 * later token shape is a different value rather than an ambiguous one — matching the
 * reasoning behind
 * [GOSSIP_PAYLOAD_V1][io.github.magnusencoded.stationtostation.data.GOSSIP_PAYLOAD_V1].
 */
const val GOSSIP_TOKEN_V1 = "station-to-station/gossip-token/1"

/**
 * How long one token stands before it rotates.
 *
 * The trade is between a passive tracker and a scan that lands. Too long and a bystander
 * who has somehow obtained both keys can follow a phone across a whole night on one value;
 * too short and two phones whose clocks disagree never compute the same token at the same
 * moment. Fifteen minutes is comfortably wider than
 * [GOSSIP_CLOCK_SKEW][io.github.magnusencoded.stationtostation.data.GOSSIP_CLOCK_SKEW]'s
 * five, which is what makes the neighbour-bucket tolerance in [gossipTokenTable] enough
 * rather than a guess.
 */
val GOSSIP_TOKEN_BUCKET: Duration = Duration.ofMinutes(15)

/**
 * How much of the HMAC actually rides the advertisement.
 *
 * Eight bytes, because a BLE scan response has 31 to spend and a manufacturer-data record
 * costs 4 of them before any payload. A truncated MAC is normally a real weakening; here it
 * is not, because a collision costs one pointless connection that then fails the possession
 * proof. Sized for the room it has to fit in, not for a security margin it does not need.
 */
const val GOSSIP_TOKEN_BYTES = 8

/**
 * Which quarter-hour [now] falls in, counted from the epoch.
 *
 * `floorDiv`, not `/`: an [Instant] before 1970 divides towards zero with the ordinary
 * operator, so two adjacent seconds either side of the epoch would land in the same bucket
 * and the sequence would fold back on itself. Nothing real is dated 1969 — but a clock that
 * has not been set yet reads 1970, and this is cheaper than reasoning about whether that
 * matters.
 */
fun gossipTokenBucket(now: Instant): Long =
    Math.floorDiv(now.epochSecond, GOSSIP_TOKEN_BUCKET.seconds)

/**
 * The token this pair advertises to each other during [bucket], or null when either key is
 * unusable as key material.
 *
 * **Sorted, so both ends compute the same value.** Whichever of the two is advertising, the
 * HMAC key is the same pair of strings in the same order — otherwise a token would only
 * ever be recognised in one direction, which is the kind of bug that looks like a flaky
 * radio.
 *
 * Newlines are refused rather than escaped: the two keys are joined with one, so a key
 * containing a newline could make a different pair encode identically. The same reasoning,
 * and the same answer, as [gossipPayload][io.github.magnusencoded.stationtostation.data.gossipPayload].
 */
fun gossipToken(mine: String, theirs: String, bucket: Long): ByteArray? {
    if (mine.isEmpty() || theirs.isEmpty()) return null
    if (mine.contains('\n') || theirs.contains('\n')) return null
    val pair = if (mine <= theirs) "$mine\n$theirs" else "$theirs\n$mine"
    val mac = runCatching {
        Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(pair.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        }
    }.getOrNull() ?: return null
    return mac.doFinal("$GOSSIP_TOKEN_V1\n$bucket".toByteArray(Charsets.UTF_8))
        .copyOf(GOSSIP_TOKEN_BYTES)
}

/** Lower-case hex, the form a token takes once it is a map key rather than bytes. */
fun gossipTokenHex(token: ByteArray): String = token.joinToString("") { "%02x".format(it) }

/**
 * Every token this device would recognise right now: hex token → the **Contact** key that
 * produced it.
 *
 * Built once per scan rather than per advertisement, because a scanner sees far more
 * advertisements than it has **Contacts** and the alternative is an HMAC per hit per
 * contact per bucket.
 *
 * **Three buckets, not one.** Two phones at the same gig can disagree by a minute either
 * way, and a token is only computed from whole quarter-hours — so a device whose clock sits
 * just the wrong side of a boundary would go unrecognised for as long as the disagreement
 * lasts. Accepting the neighbours costs three times the table and removes the whole class
 * of failure. It does not widen anything that matters: a token grants a connection, not
 * trust.
 *
 * A contact whose key is blank contributes nothing — that is a **Followed line**, and it
 * has no key to derive from, which is the same way the propagation rule is enforced in
 * [contactKeysOf][io.github.magnusencoded.stationtostation.data.contactKeysOf].
 */
fun gossipTokenTable(mine: String, contacts: Collection<String>, now: Instant): Map<String, String> {
    val bucket = gossipTokenBucket(now)
    val table = HashMap<String, String>(contacts.size * 3)
    for (contact in contacts) {
        for (offset in -1L..1L) {
            val token = gossipToken(mine, contact, bucket + offset) ?: continue
            table[gossipTokenHex(token)] = contact
        }
    }
    return table
}

/**
 * The **Contact** whose advertisement this is, or null for a stranger's.
 *
 * A token of the wrong length is not looked up at all: the advertisement is any radio's to
 * write, and a shorter one that happened to be a prefix of a real token would otherwise
 * match by accident.
 */
fun gossipTokenOwner(table: Map<String, String>, advertised: ByteArray?): String? {
    if (advertised == null || advertised.size != GOSSIP_TOKEN_BYTES) return null
    return table[gossipTokenHex(advertised)]
}

/**
 * The order this device advertises its **Contacts**' tokens in, and which one is up now.
 *
 * A token is *per pair*, so a phone with several **Contacts** has several to show and one
 * advertisement to show them in. It cycles: [GOSSIP_ADVERTISE_SLOT] on each, sorted so the
 * sequence is the same on every run and a test can say which one is up.
 *
 * The alternative — one token derived from my key alone, recognisable by everyone holding
 * it — is one advertisement rather than N and was rejected on purpose. A non-**Contact**
 * can come to hold my public key (ADR-0019's disclosure clause: it rides every message I
 * author, through devices I have never met), and under that scheme such a device could
 * follow this phone indefinitely. Requiring *both* keys keeps the set of people who can
 * recognise me down to people at least one of us has actually met.
 *
 * Null when there is nobody to advertise to, which is the state in which nothing should be
 * advertising at all.
 */
fun gossipAdvertisedToken(
    mine: String,
    contacts: Collection<String>,
    now: Instant,
): ByteArray? {
    val ordered = contacts.filter { it.isNotBlank() }.sorted()
    if (ordered.isEmpty()) return null
    val slot = Math.floorDiv(now.toEpochMilli(), GOSSIP_ADVERTISE_SLOT.toMillis())
    val theirs = ordered[Math.floorMod(slot, ordered.size.toLong()).toInt()]
    return gossipToken(mine, theirs, gossipTokenBucket(now))
}

/**
 * How long one **Contact**'s token holds the advertisement before the next one's turn.
 *
 * Four seconds against a scan that runs continuously: a handful of **Contacts** is a
 * full cycle in well under a minute, which is far shorter than anyone stands in radio range
 * of a friend at a gig. Longer than a second on purpose — every rotation is an advertising
 * restart, and the point of a background service is to be cheap.
 */
val GOSSIP_ADVERTISE_SLOT: Duration = Duration.ofSeconds(4)
