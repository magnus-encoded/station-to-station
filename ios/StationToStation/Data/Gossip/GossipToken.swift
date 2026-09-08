import CryptoKit
import Foundation

/// The rotating per-**Contact** token: how two phones that have never been introduced by a
/// server recognise each other over a radio, without either of them broadcasting anything a
/// stranger can follow (#417, #416, ADR-0019).
///
/// Pure, and pure on purpose. The keychain half — turning two **Contact** identity keys into
/// the shared secret this file consumes — is `ContactIdentity.sharedSecret`, which needs an
/// enclave and cannot be asserted off a phone. Everything that decides *what the bytes are*
/// lives here, so the one thing that must be byte-identical on two platforms is the one
/// thing a plain XCTest run can pin. Android's twin is `data/GossipToken.kt`.
///
/// **What the token is for.** The gossip channel has no session and no directory: a device
/// wakes up, sees another Station to Station radio, and has to answer "is this one of the
/// people I have actually met, and which one" before it will hand over a single byte. The
/// obvious answer — advertise my public key — is the one thing this app has spent ADR-0016
/// and ADR-0019 refusing, because a stable identifier in the air is a device anyone can
/// follow down a street. So the answer is a value that changes every `gossipTokenBucket`,
/// that only the two ends of one **Contact** edge can compute, and that means nothing at all
/// to anyone else.
///
/// **Symmetric, which is what makes the handshake cheap.** ECDH gives Alice and Bob the same
/// secret from opposite ends, so `gossipToken` gives them the same token for the same bucket.
/// One value therefore serves as both "here is who I am" and "here is who I think you are" —
/// there is no direction to get wrong, and no second derivation to keep in step.
///
/// **What it does not do.** It is a recogniser, not an authenticator. A token proves the
/// presenter has held the pair secret at some point in the last half hour; it does not prove
/// possession of a private key in the way `verifyChallenge` does. That is deliberate and it
/// is safe *here* because the token gates nothing but a conversation: every message that
/// crosses it is separately signed and separately checked by `gossipStormGate`. A replayed
/// token buys a stranger the right to be handed a batch of messages they cannot read the
/// authors of and cannot forge — which is exactly the disclosure ADR-0019 already names and
/// accepts. Do not reuse this to gate anything a signature does not already cover.
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
/// 16 bytes — 128 bits — is far beyond what a collision needs to be uninteresting here (the
/// population is one person's **Contact** list), and it keeps the published set small enough
/// that a device with thirty Contacts still fits its whole offer in one BLE read.
let gossipTokenBytes = 16

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

/// The token for one **Contact** edge in one bucket: HMAC-SHA256 over the domain separator
/// and the bucket number, keyed by the pair's ECDH secret, truncated and hex-encoded.
///
/// The bucket is decimal, unpadded — the one representation of an integer that cannot drift
/// between a `String(Int64)` and a `Long.toString()`. Lower-case hex out, so a token is
/// comparable as a plain string on both sides with no case rule to remember.
///
/// The domain separator is not decoration. The same secret is the only thing standing
/// between this app's two uses of a **Contact**'s key material, and a value signed or MAC'd
/// for one purpose must never be a valid answer to the other — the same argument
/// `gossipPayloadV1` makes against `verifyChallenge`'s nonces.
func gossipToken(secret: Data, bucket: Int64) -> String {
    let message = Data("\(gossipTokenV1)\n\(bucket)".utf8)
    let mac = HMAC<SHA256>.authenticationCode(for: message, using: SymmetricKey(data: secret))
    return mac.prefix(gossipTokenBytes).map { String(format: "%02x", $0) }.joined()
}

/// Every token this device will still recognise for one **Contact**, oldest bucket first.
///
/// What a *reader* computes. The writer publishes one token — its current bucket — and this
/// is the set that token is looked up in, which is where `gossipTokenSkewBuckets` earns its
/// keep.
func gossipTokens(secret: Data, now: Date) -> [String] {
    guard let bucket = gossipTokenBucket(now) else { return [] }
    return (-gossipTokenSkewBuckets...gossipTokenSkewBuckets).map {
        gossipToken(secret: secret, bucket: bucket + $0)
    }
}

/// What this device publishes when somebody connects: its current-bucket token for every
/// **Contact** it holds, sorted, deduplicated.
///
/// One per **Contact**, because the secret is per-pair and a device advertising has no idea
/// which of its Contacts is standing in front of it. Sorted rather than in Contact order,
/// which is the point: the position of a token in the list must say nothing about which
/// **Contact** it belongs to, or the ordering would leak the shape of a private list to
/// anyone who connects twice.
///
/// **What connecting to this discloses, plainly:** the *number* of Contacts this device
/// holds. There is no way to publish a per-pair token set and hide its size, short of
/// padding to a fixed count that would then bound how many Contacts anyone may have. Named
/// here rather than left to be found — it is a smaller disclosure than the one ADR-0019
/// already accepts under "Disclosure to the relaying device", and it is bounded to devices
/// close enough to hold a BLE connection open.
func gossipTokenOffer(secrets: [String: Data], now: Date) -> [String] {
    guard let bucket = gossipTokenBucket(now) else { return [] }
    return Set(secrets.values.map { gossipToken(secret: $0, bucket: bucket) }).sorted()
}

/// Which **Contact**, if any, an offered set of tokens belongs to.
///
/// Nil when nothing matches, which is the ordinary case and not an error: most Station to
/// Station radios in range belong to people this device has never met, and the right
/// response to one is to hang up having learnt nothing.
///
/// Ambiguity resolves to nil as well. Two Contacts whose tokens both appear in one offer is
/// either a 128-bit collision or a device presenting a set it assembled from elsewhere, and
/// guessing between them would attribute a batch of messages to the wrong person — which is
/// precisely the `from` argument `gossipStormGate` trusts to be right.
func gossipResolveOffer(_ offered: [String], secrets: [String: Data], now: Date) -> String? {
    var found: String?
    for (contact, secret) in secrets.sorted(by: { $0.key < $1.key }) {
        let mine = Set(gossipTokens(secret: secret, now: now))
        guard offered.contains(where: { mine.contains($0) }) else { continue }
        if found != nil { return nil }
        found = contact
    }
    return found
}

/// Which **Contact** a single presented token belongs to — the mirror of
/// `gossipResolveOffer`, for the direction where the presenter already knows who it is
/// talking to and says so with one value.
func gossipResolveToken(_ token: String, secrets: [String: Data], now: Date) -> String? {
    gossipResolveOffer([token], secrets: secrets, now: now)
}

/// The shape of a token, checked before it is compared or logged: 32 lower-case hex
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
