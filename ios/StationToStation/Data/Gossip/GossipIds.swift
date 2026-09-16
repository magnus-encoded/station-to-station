import Foundation

/// The three rules the gossip channel needs before it has decided anything: what may be used as
/// an id, when a night stops being news, and whose keys are in the audience.
///
/// Carved out of the v1 storm gate when the v1 pipeline was deleted, and kept out of
/// `PublicGossip.swift` because they are not the v2 wire format — nothing in this file knows
/// what an envelope looks like. They are the vocabulary every layer above shares: the codec
/// validates ids with `isSafeGossipId`, `AppModel` draws a night's ceiling with `gossipExpiry`,
/// and the channel draws its audience with `contactKeysOf`. Android's twin is
/// `data/gossip/GossipIds.kt`, function for function.

/// Whether a `gigId` (or an author scope) is safe to hold, compare and propagate.
///
/// Deliberately **stricter than `isSafeMediaId`**, and deliberately not it, though it is the
/// same shape and exists for the same reason (this id reaches a store as a key and could
/// reach a file path). `isSafeMediaId` is Unicode-aware on both platforms in ways that do
/// not agree: Kotlin measures UTF-16 code units and asks `Char.isLetterOrDigit`, Swift
/// measures grapheme clusters and asks `CharacterSet.alphanumerics`, so an astral-plane
/// alphanumeric or a combining mark is accepted by one twin and refused by the other. On a
/// media id that is a nuisance; on a gossip id it is a fact that propagates through
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

/// When a fact about `gigDate` (setlist.fm's `dd-MM-yyyy`) stops being news: the end of that
/// gig's own night.
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
