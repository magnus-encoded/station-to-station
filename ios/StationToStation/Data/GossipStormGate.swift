import CryptoKit
import Foundation

/// The gossip storm-gate (#410, part of #408): given what this device has already seen, a
/// batch of check-in messages handed over by one peer, and the time, decide what is
/// accepted and what is passed on.
///
/// Ported term for term from Android's `data/GossipStormGate.kt`, the same way
/// `contactReconcilePlan` is a port and not a re-derivation. Two implementations that can
/// disagree eventually will, and the direction of the disagreement here is *believing a
/// message the other twin would have thrown away*.
///
/// Pure, in the sense `contactReconcilePlan` is pure and for the same reasons: no radio, no
/// service, no persistence, no clock and no keychain. The seen-set arrives as an argument
/// and leaves as a value, "now" arrives as an argument, and the **Contact** keys arrive as
/// an argument — so the decision that must never be wrong can be asserted in a plain
/// XCTest run, with no phone, no second phone and no gig to stand at. The transport issues
/// (#416 Android, #417 iOS) call this as it stands and add nothing to it: a rule that grew
/// a second copy inside a CoreBluetooth delegate is a rule that will disagree with itself.
///
/// **The propagation rule this file exists to encode** — from `CONTEXT.md`, where the
/// distinction is spelled out as the ambiguity most likely to become a privacy bug: bytes
/// only ever cross a **Contact** edge. A **Contact** is someone whose public key was handed
/// over in person at an **Exchange**; a **Followed line** is public setlist.fm data that
/// grants nothing and holds no key at all. So a message is accepted only from a Contact
/// (`from`), only when it was signed by a Contact (`checkedInBy`), and is relayed only to
/// Contacts. There is no path in this file by which a Followed line authors, receives or
/// forwards anything — `contactKeysOf` is where that is enforced, and it is enforced by a
/// **Followed line** having no key to offer.
///
/// **What a relaying device discloses, deliberately. Read this before extending it.** A
/// message reaches anyone beyond the author's own Contacts only by my handing it to my
/// Contacts, who then drop it if the author is nobody they have met. Dropping it is not
/// unseeing it: what Carol receives, having never met Alice, is Alice's **stable identity
/// public key**, the gig, and the minute she arrived. That key is the same one Alice's
/// **Card** carries, so it is not an opaque token — anyone who ever scans Alice's card, or
/// correlates it with a card they already hold, can put a name to every check-in they once
/// relayed, retroactively. This is a real location disclosure across a Contact edge that
/// does not exist, and it is the price of a message travelling more than one hop.
///
/// It is bounded — by my own Contact list, and by a device that rejects a message never
/// relaying it — and it is what the ADR (#415) has to argue for explicitly. Narrowing it
/// without encryption would need me to know somebody else's Contact list, which is exactly
/// the thing this app has no server to ask; the honest alternative is a payload encrypted
/// per recipient, which nothing here does. Say so out loud rather than describing this as
/// "some key checked in somewhere".
///
/// **What this does not do: store and forward.** A message is relayed once, when it is
/// first accepted, because "relay only if it has not been seen" is the rule the feature
/// asked for (#408, and this issue's own criteria). A Contact who was out of range at that
/// moment therefore never hears about it from me, however much of the night is left. This
/// function does not stop a transport keeping the message and offering it again later — the
/// seen set governs *acceptance*, not what a radio holds — but no second relay is planned
/// here. Whether that is worth building is a decision for #415 and the transports.

/// The domain separator every gossip payload starts with.
///
/// A **Contact**'s identity key also answers the LAN reconcile challenge (#265) over bytes
/// of that session's choosing. Prefixing the payload keeps the two uses apart: a signature
/// made here can never be replayed as an answer there, or the reverse, because neither side
/// will ever be asked to sign bytes beginning with the other's prefix. The version is in
/// the string so a later envelope is a different payload rather than an ambiguous one.
let gossipPayloadV1 = "station-to-station/gossip-checkin/1"

/// How far ahead of this device's clock a check-in may be stamped and still be believed.
///
/// Two phones at the same gig can disagree by a minute or two, and dropping a real arrival
/// over that would be the feature failing at the one moment it exists for. Small on purpose
/// all the same: without any bound, a future-dated check-in would ride `gossipMaxLifetime`
/// out from a date of the sender's choosing and outlive the night it claims to be about.
let gossipClockSkew: TimeInterval = 5 * 60

/// The longest a message may live when this device cannot work the gig's night out for
/// itself: one night, off this platform's own `nightEndsHour` rather than a number typed
/// here again.
///
/// A relay hop is usually a device that has never heard of the gig — that is the whole point
/// of relaying — so it has no night end to check the claim against. It still refuses to hold
/// anything longer than a night could possibly last (00:00 through `nightEndsHour` the next
/// morning, so 30 hours), which is what stops one message with a generous `expiresAt` from
/// circulating for ever.
///
/// A duration rather than a window, so it is the same 30 hours on the two nights a year a
/// timezone's clocks move; the window itself, where this device knows the gig, comes from
/// `nightWindow` and is exact.
let gossipMaxLifetime: TimeInterval = TimeInterval(24 + nightEndsHour) * 3600

/// The most messages one peer's batch is read for in a single run.
///
/// Every gate below is under the author's control except membership of my **Contact** list,
/// so a Contact whose phone has been taken over can mint valid check-ins as fast as it can
/// sign them. This does not stop that — see the note on `gossipStormGate` — but it does stop
/// one handover from costing an unbounded number of signature verifications and an unbounded
/// number of seen entries.
///
/// Nothing is lost by hitting it: the overflow is rejected as `.batchLimit` and *not*
/// remembered, so the same messages are accepted normally when they are offered again.
/// Generous against real traffic — a night's check-ins among people who have all met each
/// other is a handful, not sixty-four.
let gossipMaxBatch = 64

/// The furthest from the epoch a check-in may claim to be, in seconds: about the year 33,658.
///
/// A range check rather than a taste check. A `Date` is a `Double`, so a peer's decoded
/// `expiresAt` of `1e30`, or a NaN out of a malformed number, is a value this platform will
/// happily build and then **trap** on when it is narrowed to an integer — a crash reachable
/// from a peer's bytes. Kotlin cannot reach that, but it applies the same bound so that both
/// twins refuse exactly the same envelopes rather than one refusing what the other relays.
let gossipMaxEpochSecond: Int64 = 1_000_000_000_000

/// One check-in, as it travels.
///
/// `messageId` is content-addressed: the SHA-256 of `gossipPayload`, hex. It is not a name
/// the sender chooses, and `gossipStormGate` recomputes it rather than trusting it — an id a
/// relay could choose freely is a message that is never a duplicate, which is the storm this
/// gate is named for.
///
/// `gigId` is the gig the check-in is about, in whatever form means the same thing on two
/// people's timelines — setlist.fm's id where the night has one (#28). This file treats it
/// as an opaque key: it is only ever compared, and handed to the caller's own `nightEndFor`
/// lookup.
///
/// `checkedInBy` is the checking-in **Contact**'s public key, base64 X.509
/// SubjectPublicKeyInfo — the same encoding `Friend.publicKey` holds and the radio carries.
/// `signature` is base64 DER over `gossipPayload`, by that key.
///
/// Times are absolute `Date`s rather than wall-clock: a relay hop can be in another timezone
/// from the gig, and a local 06:00 would mean a different moment to each of them.
struct GossipCheckIn: Equatable {
    var messageId: String
    var gigId: String
    var checkedInBy: String
    var checkedInAt: Date
    var expiresAt: Date
    var signature: String
}

/// Why a candidate did not get in. Local diagnosis only; nothing is reported to a peer.
enum GossipReject: Equatable {
    /// The peer that handed over the batch is not a **Contact**. Nothing in it is read.
    case senderNotAContact
    /// A field that could not be an id at all — empty, over-long, or path-shaped.
    case malformed
    /// Signed by a key I hold no **Exchange** with. A **Followed line** lands here too.
    case authorNotAContact
    /// The id is not the hash of the payload: a re-labelled message, dodging dedup.
    case forgedId
    /// No valid signature by `checkedInBy` over the payload.
    case badSignature
    /// Stamped further ahead than `gossipClockSkew` allows.
    case futureDated
    /// Past its expiry, or past the night it claims to be about.
    case expired
    /// Already in the seen set, or earlier in this same batch.
    case alreadySeen
    /// Past `gossipMaxBatch` in one handover, and so not read at all. Not remembered
    /// either: offer it again and it is judged on its merits.
    case batchLimit
}

struct GossipRejected: Equatable {
    var messageId: String
    var reason: GossipReject
}

/// One accepted message and the **Contacts** to pass it to. `to` never contains the peer it
/// arrived from (which would echo it straight back) nor its own author (who plainly knows),
/// and is sorted so that the same input yields the same plan.
struct GossipRelay: Equatable {
    var message: GossipCheckIn
    var to: [String]
}

struct GossipPlan: Equatable {
    /// New, unexpired, and signed by a **Contact**: safe to act on.
    var accepted: [GossipCheckIn] = []
    /// The subset with somebody left to tell, and who to tell.
    var relay: [GossipRelay] = []
    /// The seen set to keep: message id → the expiry this device will hold it to.
    var seen: [String: Date] = [:]
    /// Everything dropped, and why.
    var rejected: [GossipRejected] = []
}

/// Whether a `gigId` is safe to hold, compare and propagate.
///
/// Deliberately **stricter than `isSafeMediaId`**, and deliberately not it, though it is the
/// same shape and exists for the same reason (this id reaches a store as a key and could
/// reach a file path). `isSafeMediaId` is Unicode-aware on both platforms in ways that do
/// not agree: Kotlin measures UTF-16 code units and asks `Char.isLetterOrDigit`, Swift
/// measures grapheme clusters and asks `CharacterSet.alphanumerics`, so an astral-plane
/// alphanumeric or a combining mark is accepted by one twin and refused by the other. On a
/// media id that is a nuisance; on a gossip id it is a message that propagates through
/// iPhones and dies at every Android hop, which is the exact asymmetry a ported file exists
/// to prevent.
///
/// ASCII only, therefore: with no character above 0x7F, code units, scalars, graphemes and
/// bytes are all the same count, and the two implementations cannot read the rule
/// differently. Nothing real is lost — a gig id is a UUID or a setlist.fm id.
func isSafeGossipId(_ id: String) -> Bool {
    let bytes = Array(id.utf8)
    guard !bytes.isEmpty, bytes.count <= 64 else { return false }
    return bytes.allSatisfy { byte in
        (byte >= UInt8(ascii: "a") && byte <= UInt8(ascii: "z"))
            || (byte >= UInt8(ascii: "A") && byte <= UInt8(ascii: "Z"))
            || (byte >= UInt8(ascii: "0") && byte <= UInt8(ascii: "9"))
            || byte == UInt8(ascii: "-") || byte == UInt8(ascii: "_")
    }
}

/// The bytes a check-in is signed over and identified by.
///
/// Newline-separated, with the fields that can hold arbitrary text checked for newlines
/// first: without that, a value could carry the separator and make two different messages
/// encode identically. Times are epoch **seconds** in decimal, which is the one
/// representation that cannot drift between the platforms — no formatter, no locale, no
/// calendar, no timezone. Rounded down, matching `Instant.epochSecond`.
///
/// Neither `messageId` nor `signature` is part of it: the id is the hash of this, so it
/// cannot also be inside it, and the signature is over it.
///
/// Nil for anything that cannot be canonically encoded, which `gossipStormGate` treats as a
/// rejection. Android's twin is `gossipPayload` in `GossipStormGate.kt`, byte for byte.
func gossipPayload(_ message: GossipCheckIn) -> Data? {
    guard !message.gigId.isEmpty, !message.checkedInBy.isEmpty,
          !message.gigId.contains("\n"), !message.checkedInBy.contains("\n")
    else { return nil }
    guard let checkedInAt = gossipEpochSeconds(message.checkedInAt),
          let expiresAt = gossipEpochSeconds(message.expiresAt)
    else { return nil }
    let fields = [
        gossipPayloadV1,
        message.gigId,
        message.checkedInBy,
        String(checkedInAt),
        String(expiresAt),
    ]
    return Data(fields.joined(separator: "\n").utf8)
}

/// Whole seconds since the epoch, floored — the same value Kotlin's `Instant.epochSecond`
/// carries, including for a time before 1970, where rounding towards zero would not.
///
/// Nil rather than a trap for anything outside `gossipMaxEpochSecond`, and for a `Date` that
/// is not a finite number at all. This is not hypothetical tidiness: a `Date` is a `Double`,
/// so a peer's decoded `expiresAt` of `1e30` — or a NaN out of a malformed number — is a
/// perfectly constructible value, and `Int64(_:)` on it aborts the process. Every date here
/// arrived over the radio, so the conversion has to be total. Android cannot reach this: a
/// Kotlin `Instant` is bounded when it is built, but it applies the same range so that the
/// two twins refuse exactly the same envelopes.
func gossipEpochSeconds(_ date: Date) -> Int64? {
    let seconds = date.timeIntervalSince1970.rounded(.down)
    guard seconds.isFinite, abs(seconds) <= Double(gossipMaxEpochSecond) else { return nil }
    return Int64(seconds)
}

/// The content address of a message: SHA-256 of `gossipPayload`, lower-case hex.
func gossipMessageId(_ message: GossipCheckIn) -> String? {
    guard let payload = gossipPayload(message) else { return nil }
    return SHA256.hash(data: payload).map { String(format: "%02x", $0) }.joined()
}

/// Does `signature` prove that `checkedInBy` wrote exactly this payload?
///
/// `verifyChallenge` is reused rather than restated: it is the same key material, the same
/// curve and the same DER-encoded ECDSA over SHA-256, already tested, and a second signature
/// routine would be a second place to get this wrong. Anything malformed — no signature, a
/// signature that is not base64, a key that is not a key — verifies false rather than
/// throwing, which is the same posture the rest of this app takes towards a peer's bytes.
func verifyGossipSignature(_ message: GossipCheckIn) -> Bool {
    guard let payload = gossipPayload(message),
          let signature = Data(base64Encoded: message.signature)
    else { return false }
    return verifyChallenge(payload, signature: signature, publicKeyBase64: message.checkedInBy)
}

/// When a check-in for `gigDate` (setlist.fm's `dd-MM-yyyy`) stops being news: the end of
/// that gig's own night.
///
/// `nightWindow` is the app's one answer to "is this night still going on", drawn off
/// `nightEndsHour` — a show that ends at 01:30 is still that night — and this reuses it
/// rather than inventing a second expiry rule that could drift from what the **Room**
/// believes. Nil for a date that will not parse, which is a gig with no night to expire at.
func gossipExpiry(gigDate: String, calendar: Calendar = .current) -> Date? {
    nightWindow(gigDate: gigDate, calendar: calendar)?.upperBound
}

/// The keys of everyone I have actually met: the gossip channel's whole audience.
///
/// Holding a key is exactly what makes a **Contact** — only the radio ever fills
/// `Friend.publicKey` in, in person, and a link or QR code can never carry one (#271). So a
/// **Followed line** simply has nothing to put in this set, which is how "never a Followed
/// line" is enforced here: not by a check that could be forgotten, but by there being no key
/// to check.
func contactKeysOf(_ friends: [Friend]) -> Set<String> {
    Set(friends.compactMap { $0.publicKey?.nilIfBlank })
}

/// The storm-gate decision.
///
/// `seen` is message id → the expiry this device is holding it to, `batch` is what one peer
/// just handed over, `from` is that peer's already-authenticated **Contact** key (the
/// transport proves possession before calling; this only asks whether the key is one of
/// mine), `now` is the clock, and `contacts` is `contactKeysOf`.
///
/// `nightEndFor` resolves a `gigId` to the end of that night as this device understands it,
/// or nil for a gig it has never heard of — the ordinary case on a relay hop. `verify` is
/// the signature check, defaulting to the real one; a test passes its own, and nothing else
/// should.
///
/// The order of the checks is deliberate, and it is cheapest-first *after* one hard rule:
/// `.forgedId` comes before everything that follows it, because until the id has been
/// recomputed from the payload it is not an identity, and a check that treated it as one
/// would be reading a name the sender chose. Nothing forged can then occupy a message id,
/// since an id that survives that check is a hash of content signed by a **Contact** — and
/// nothing but acceptance ever writes to the seen set.
///
/// With that settled, `.alreadySeen` sits immediately after it, ahead of the signature
/// check: a peer that hands over five thousand copies of one real message should cost five
/// thousand hash-table lookups, not five thousand ECDSA verifications. Expiry comes last
/// because it is the only gate that needs `nightEndFor`, and asking a caller's lookup about
/// a gig id that has not been through `isSafeGossipId` would be handing an unvalidated
/// string to somebody else's store.
///
/// Idempotent: feeding `GossipPlan.seen` back in leaves the accepted and relay lists empty,
/// and running the same arguments twice yields an equal plan — the relay audience is sorted
/// for that reason, since a `Set`'s iteration order is nobody's guarantee.
///
/// **What this cannot bound, and the transports must.** Every gate here is decided by the
/// message, and every field of the message is the author's except its key's membership of my
/// **Contact** list. So a Contact whose phone has been taken over can sign fifty thousand
/// check-ins that differ only in `checkedInAt`: each has a distinct honest id and a valid
/// signature, and each would be accepted, remembered for a night and relayed to everyone
/// else I have met. `gossipMaxBatch` caps what one handover costs; what it cannot cap is how
/// often a peer is allowed to hand one over, because nothing in a pure function knows the
/// time between calls. #416 and #417 own that, and a per-peer rate is the shape of it. Named
/// here rather than left to be discovered: the honest bound on this gate is "per batch", not
/// "per night".
func gossipStormGate(
    seen: [String: Date],
    batch: [GossipCheckIn],
    from: String,
    now: Date,
    contacts: Set<String>,
    nightEndFor: (String) -> Date? = { _ in nil },
    verify: (GossipCheckIn) -> Bool = verifyGossipSignature
) -> GossipPlan {
    // Pruned on every run, including the ones that decide nothing: the set is a device's
    // memory of live messages, and an entry past its expiry can never be resurrected by this
    // gate anyway — the same expiry that pruned it also rejects the message.
    var kept = seen.filter { $0.value > now }

    // A peer I have never exchanged keys with is handed nothing and read for nothing — the
    // same fail-safe shape `contactReconcilePlan` takes to an unverified peer.
    guard !from.isEmpty, contacts.contains(from) else {
        return GossipPlan(
            seen: kept,
            rejected: batch.map { GossipRejected(messageId: $0.messageId, reason: .senderNotAContact) }
        )
    }

    var plan = GossipPlan()
    for (index, message) in batch.enumerated() {
        if index >= gossipMaxBatch {
            plan.rejected.append(GossipRejected(messageId: message.messageId, reason: .batchLimit))
            continue
        }
        if let reason = gossipRejection(message, seen: kept, now: now, contacts: contacts,
                                        verify: verify) {
            plan.rejected.append(GossipRejected(messageId: message.messageId, reason: reason))
            continue
        }
        // Worked out once, here, and used for both the gate and what is remembered: it is
        // the only value that leans on the caller's `nightEndFor`, and a lookup that
        // answered differently twice would let a message pass the gate and be written into
        // the seen set already expired — pruned on the next run, offered again, accepted
        // again, relayed again.
        let expiry = gossipEffectiveExpiry(message, nightEndFor: nightEndFor)
        if expiry <= now {
            plan.rejected.append(GossipRejected(messageId: message.messageId, reason: .expired))
            continue
        }
        kept[message.messageId] = expiry
        plan.accepted.append(message)
        let to = contacts.filter { $0 != from && $0 != message.checkedInBy }.sorted()
        if !to.isEmpty { plan.relay.append(GossipRelay(message: message, to: to)) }
    }
    plan.seen = kept
    return plan
}

/// Nil when the message gets in. Every gate but expiry, in the order argued for above.
private func gossipRejection(
    _ message: GossipCheckIn,
    seen: [String: Date],
    now: Date,
    contacts: Set<String>,
    verify: (GossipCheckIn) -> Bool
) -> GossipReject? {
    if !isSafeGossipId(message.gigId) || message.messageId.isEmpty { return .malformed }
    if !contacts.contains(message.checkedInBy) { return .authorNotAContact }
    if gossipMessageId(message) != message.messageId { return .forgedId }
    if seen[message.messageId] != nil { return .alreadySeen }
    if !verify(message) { return .badSignature }
    if gossipSecond(message.checkedInAt) > now.addingTimeInterval(gossipClockSkew) {
        return .futureDated
    }
    return nil
}

/// How long this device will hold a message: what it claims, capped by what this device
/// knows.
///
/// `expiresAt` is inside the signed payload, so it is the author's own claim and a genuine
/// **Contact** could still be a compromised phone claiming a year. The cap is the gig's own
/// night where that is known, and one night from the check-in where it is not. Never
/// widened — an early expiry is honoured as it stands.
private func gossipEffectiveExpiry(_ message: GossipCheckIn,
                                   nightEndFor: (String) -> Date?) -> Date {
    let ceiling = nightEndFor(message.gigId)
        ?? gossipSecond(message.checkedInAt).addingTimeInterval(gossipMaxLifetime)
    return min(gossipSecond(message.expiresAt), ceiling)
}

/// A time exactly as it was signed and hashed: whole seconds, because that is all
/// `gossipPayload` carries.
///
/// Anything finer is a part of the envelope no signature covers, so a relay could add up to
/// a second of life to a message it passes on and the id would still check out. Under a
/// second is not much, but "a field a peer can change without breaking the signature decides
/// something" is the shape of the bug, not its size — so no decision here reads one.
private func gossipSecond(_ date: Date) -> Date {
    guard let seconds = gossipEpochSeconds(date) else { return date }
    return Date(timeIntervalSince1970: TimeInterval(seconds))
}
