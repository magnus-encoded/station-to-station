import CryptoKit
import Foundation

/// The rotating per-**Contact** token: how two phones that have never been introduced by a
/// server recognise each other over a radio, without either of them broadcasting anything a
/// stranger can follow (#417, #416, ADR-0019).
///
/// Pure, and pure on purpose: this is the half that must agree byte for byte with Android,
/// and a rule that can only be exercised by standing between two phones is a rule that gets
/// checked once and then drifts. Android's twin is `data/gossip/GossipToken.kt`, and
/// `GossipTokenTests` asserts the same fixed vector its JVM twin does.
///
/// **What the token is for.** The gossip channel has no session and no directory: a device
/// wakes up, sees another Station to Station radio, and has to answer "is this one of the
/// people I have actually met, and which one" before it will hand over a single byte. The
/// obvious answer — advertise my public key — is the one thing this app has spent ADR-0016
/// and ADR-0019 refusing, because a stable identifier in the air is a device anyone can
/// follow down a street. So the answer is a value that changes every quarter of an hour,
/// that only people who have met can compute, and that means nothing at all to anyone else.
///
/// **Symmetric, which is what makes the handshake cheap.** Both ends sort the same two keys
/// and get the same bytes, so one value serves as both "here is who I am" and "here is who I
/// think you are" — there is no direction to get wrong and no second derivation to keep in
/// step.
///
/// ## Why this is keyed on two public keys and not on an ECDH shared secret
///
/// #408 and #417 both describe the token as "an HMAC of the ECDH-shared-secret and a time
/// bucket". This file did that first, and it was reconciled away on 2026-09-08 (ADR-0019,
/// "Token derivation") because **Android cannot compute it at all**:
///
/// - The durable **Contact** identity there is one AndroidKeyStore EC P-256 key created with
///   `PURPOSE_SIGN` and nothing else. AndroidKeyStore keys are immutable, so it cannot be
///   taught key agreement, and it is already on every phone that has ever made a **Contact**.
/// - `PURPOSE_AGREE_KEY` needs API 31; that app's minSdk is 26.
/// - A second, agreement-capable keypair would have to ride the **Card** handed over in
///   person — the new pairing step the issue rules out — and would strand every existing
///   **Contact** until the two people met again.
///
/// iOS *can* do ECDH against its own Secure Enclave key, which is exactly why this needed
/// deciding rather than discovering: a derivation only one of the two platforms can compute
/// is not a wire format, it is two apps that never recognise each other. The constraint is
/// about *key material*, not about a radio, so unlike the advertisement (see
/// `gossipTokenOffer`) there is no platform-specific layer that could paper over it. The
/// shared answer is the material both ends demonstrably hold and nobody else was given:
/// **each other's public keys**.
///
/// **What that costs, stated plainly.** A shared secret would be unguessable to everyone but
/// the pair; this is computable by anyone holding *both* public keys — a mutual **Contact**
/// of both people, or, per ADR-0019's own disclosure clause, a device that once relayed a
/// check-in authored by one of them and separately holds the other's key. Such a device can
/// tell that these two people are within radio range of it, and nothing more.
///
/// **So it is a recogniser, never an authenticator.** A token says "we have met"; it does
/// not prove possession of a private key. That is why every **Pass** additionally carries a
/// signature over a nonce the listener issued (`gossipAuthV1`), and why every message
/// inside one is verified again by `gossipStormGate`. Do not reuse this to gate anything a
/// signature does not already cover.
let gossipTokenV1 = "station-to-station/gossip-token/1"

/// How long one token stands before the next one replaces it.
///
/// The trade is followability against handshake cost. Shorter is more private — a token seen
/// twice an hour apart is two unrelated values — but every bucket boundary is a window in
/// which two phones can disagree, and a phone that has been asleep in a pocket wakes up with
/// a clock it has not checked against anybody. Fifteen minutes is long enough that a whole
/// meeting normally happens inside one bucket, and short enough that a token is stale by the
/// time anyone could have walked it anywhere.
///
/// Not derived from `gossipClockSkew`, which bounds a different thing (how far ahead a
/// *check-in* may be stamped). Two numbers because they answer two questions.
let gossipTokenBucketSeconds: Int64 = 15 * 60

/// How many buckets either side of this device's own are still recognised.
///
/// One, so half an hour of drift between two phones is absorbed. Not more: every extra
/// bucket is another value a device will still answer to, which widens the window in which a
/// captured token is worth replaying and multiplies the work of resolving one.
let gossipTokenSkewBuckets: Int64 = 1

/// Truncation, in bytes, of the HMAC that becomes a token.
///
/// Eight, and this is Android's constraint rather than a preference of this platform's: a
/// BLE scan response has 31 bytes to spend and a manufacturer-data record costs 4 of them
/// before any payload. A truncated MAC is normally a real weakening; here it is not, because
/// a collision costs one pointless connection that then fails the possession proof. The
/// population being distinguished is one person's **Contact** list.
let gossipTokenBytes = 8

/// Which bucket a moment falls in: whole `gossipTokenBucketSeconds` since the epoch,
/// **floored**.
///
/// Floored rather than truncated, because Swift's `/` rounds toward zero and Kotlin's does
/// too — which would put every instant before 1970 in the bucket above the one Android's
/// `Math.floorDiv` puts it in. No real check-in happens in 1969; a test fixture might, and a
/// rule that is only right for positive numbers is a rule nobody can read off the code.
///
/// Nil for a `Date` that is not a finite number, or is outside `gossipMaxEpochSecond` — the
/// same totality `gossipEpochSeconds` exists for, and for the same reason: these values
/// arrive from a radio.
func gossipTokenBucket(_ now: Date) -> Int64? {
    guard let seconds = gossipEpochSeconds(now) else { return nil }
    let quotient = seconds / gossipTokenBucketSeconds
    let remainder = seconds % gossipTokenBucketSeconds
    return remainder != 0 && (seconds < 0) ? quotient - 1 : quotient
}

/// The token one **Contact** edge shows in one bucket: HMAC-SHA256 over the domain separator
/// and the bucket number, keyed by the two identity keys in sorted order, truncated and
/// hex-encoded.
///
/// **Sorted, so both ends compute the same value.** Whichever of the two is speaking, the
/// HMAC key is the same pair of strings in the same order — otherwise a token would only
/// ever be recognised in one direction, which is the kind of bug that looks like a flaky
/// radio.
///
/// Newlines are refused rather than escaped: the two keys are joined with one, so a key
/// containing a newline could make a different pair encode identically. The same reasoning,
/// and the same answer, as `gossipPayload`.
///
/// The bucket is decimal, unpadded — the one representation of an integer that cannot drift
/// between a `String(Int64)` and a `Long.toString()`. Lower-case hex out, so a token is
/// comparable as a plain string on both sides with no case rule to remember.
func gossipToken(mine: String, theirs: String, bucket: Int64) -> String? {
    guard !mine.isEmpty, !theirs.isEmpty,
          !mine.contains("\n"), !theirs.contains("\n")
    else { return nil }
    let pair = mine <= theirs ? "\(mine)\n\(theirs)" : "\(theirs)\n\(mine)"
    let message = Data("\(gossipTokenV1)\n\(bucket)".utf8)
    let mac = HMAC<SHA256>.authenticationCode(for: message,
                                              using: SymmetricKey(data: Data(pair.utf8)))
    return mac.prefix(gossipTokenBytes).map { String(format: "%02x", $0) }.joined()
}

/// Every token this device would recognise right now: token → the **Contact** key that
/// produced it.
///
/// Built once per scan rather than per hit, because a scanner sees far more radios than it
/// has **Contacts** and the alternative is an HMAC per hit per contact per bucket.
///
/// **Three buckets, not one.** Two phones at the same gig can disagree by a minute either
/// way, and a token is only computed from whole quarter-hours — so a device whose clock sits
/// just the wrong side of a boundary would go unrecognised for as long as the disagreement
/// lasts. Accepting the neighbours costs three times the table and removes the whole class of
/// failure. It does not widen anything that matters: a token grants a connection, not trust.
///
/// A **Contact** whose key is blank contributes nothing — that is a **Followed line**, and it
/// has no key to derive from, which is the same way the propagation rule is enforced in
/// `contactKeysOf`.
func gossipTokenTable(mine: String, contacts: [String], now: Date) -> [String: String] {
    guard let bucket = gossipTokenBucket(now) else { return [:] }
    var table: [String: String] = [:]
    for contact in contacts where !contact.trimmingCharacters(in: .whitespaces).isEmpty {
        for offset in -gossipTokenSkewBuckets...gossipTokenSkewBuckets {
            guard let token = gossipToken(mine: mine, theirs: contact, bucket: bucket + offset)
            else { continue }
            table[token] = contact
        }
    }
    return table
}

/// What this device publishes when somebody connects: its current-bucket token for every
/// **Contact** it holds, sorted and deduplicated.
///
/// **This is the cross-platform half of recognition, and the advertisement is not.** Android
/// additionally puts one of these in its scan response, so that an Android meeting another
/// Android can decide not to connect without paying for a connection. This platform cannot:
/// `startAdvertising` honours a local name and a service UUID list and nothing else, and a
/// *backgrounded* iPhone drops the name and moves its service UUID into an overflow area only
/// another iOS device reads. So the advertisement is a shortcut on one platform and the
/// challenge read is the contract on both — the same split the **Card** already lives with,
/// where one payload rides a shared BLE route and a faster Android-only route beside it.
///
/// One token per **Contact**, because the token is per pair and a listening device has no
/// idea which of its **Contacts** has just connected. Sorted rather than in **Contact**
/// order, which is the point: the position of a token in the list must say nothing about
/// which **Contact** produced it, or the ordering would leak the shape of a private list to
/// anyone who connects twice.
///
/// **What connecting to this discloses, plainly:** the *number* of **Contacts** this device
/// holds. There is no way to publish a per-pair token set and hide its size short of padding
/// to a fixed count, which would then cap how many **Contacts** anyone may have. It is named
/// here rather than left to be found, and it is smaller than the disclosure ADR-0019 already
/// accepts under "Disclosure to the relaying device". What it deliberately does *not*
/// disclose is this device's own identity key.
func gossipTokenOffer(mine: String, contacts: [String], now: Date) -> [String] {
    guard let bucket = gossipTokenBucket(now) else { return [] }
    let tokens = contacts
        .filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        .compactMap { gossipToken(mine: mine, theirs: $0, bucket: bucket) }
    return Set(tokens).sorted()
}

/// Which **Contact**, if any, an offered set of tokens belongs to.
///
/// Nil when nothing matches, which is the ordinary case and not an error: most Station to
/// Station radios in range belong to people this device has never met, and the right response
/// to one is to hang up having learnt nothing.
///
/// **Ambiguity resolves to nil as well.** Two **Contacts** whose tokens both appear in one
/// offer is either a collision or a device presenting a set it assembled from elsewhere, and
/// guessing between them would attribute a **Pass** to the wrong person — which is precisely
/// the `from` argument `gossipStormGate` trusts to be right.
func gossipResolveOffer(_ offered: [String], table: [String: String]) -> String? {
    let matches = Set(offered.compactMap { table[$0] })
    return matches.count == 1 ? matches.first : nil
}

/// The shape of a token, checked before it is compared or logged: 16 lower-case hex
/// characters and nothing else.
///
/// A token arrives from a radio and is used as a dictionary key, so it gets the same
/// treatment `isSafeGossipId` gives a gig id — ASCII only, so the two platforms cannot read
/// the same bytes differently.
func isSafeGossipToken(_ token: String) -> Bool {
    let bytes = Array(token.utf8)
    guard bytes.count == gossipTokenBytes * 2 else { return false }
    return bytes.allSatisfy { byte in
        (byte >= UInt8(ascii: "0") && byte <= UInt8(ascii: "9"))
            || (byte >= UInt8(ascii: "a") && byte <= UInt8(ascii: "f"))
    }
}
