import Foundation

// The wire layer of the Exchange, ported term-for-term from Android's
// `ble/CardWire.kt` and the pure half of `ble/BleProbe.kt`. This file is the
// cross-platform contract: two phones only meet if both sides agree on these
// bytes, so it is pure Swift with no CoreBluetooth in sight (ADR-0001) and the
// assertions in CardWireTests are the same assertions Android runs on the JVM.
//
// Wire shape, and where the two platforms differ:
//   advertisement  — the 128-bit service UUID.
//   name           — Android advertises it as manufacturer data under company id
//                    0xFFFF (a Complete Local Name on Android is the *adapter*
//                    name); iOS cannot put manufacturer data in an advertisement
//                    at all, so it sends a Complete Local Name. Both scanners
//                    accept either. See `BleProbe.kt:40-46`.
//   characteristic — the whole card (~170-200 bytes), read after a connect.

/// Fixed on both platforms. Change either and the phones stop seeing each other.
let exchangeServiceUUIDString = "7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7711"
let cardCharacteristicUUIDString = "7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7712"

/// The other direction. A read is one-directional, so the central writes its own
/// card here on the same connection and one tap exchanges both cards. Same payload
/// as the read characteristic — `ProbeCard.encode()` bytes, one format, one parser.
let cardWriteCharacteristicUUIDString = "7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7713"

/// 0xFFFF is the SIG's "reserved for internal/testing use" company id.
let testCompanyId: UInt16 = 0xFFFF

/// Sized to the operation: a card exchange meant to take 2s has failed by 7, and
/// the cue is to fall through to QR rather than spin.
let exchangeTimeout: TimeInterval = 7

/// The card as it goes over the wire — the same deep link the QR fallback carries
/// (`station-to-station://friend?name=…&u=…&sid=…`) plus `k`, the public key #28
/// made the identity. Unknown query parameters are ignored, so an old build
/// reading a new card still gets a usable friend.
struct ProbeCard: Equatable {
    var name: String
    /// Ed25519 public key, base64. 32 bytes in, 44 chars out.
    var publicKey: String
    var setlistfm: String?
    var spotifyId: String?

    func encode() -> String {
        var s = "station-to-station://friend?name=" + formEncode(name)
        s += "&k=" + formEncode(publicKey)
        if let setlistfm { s += "&u=" + formEncode(setlistfm) }
        if let spotifyId { s += "&sid=" + formEncode(spotifyId) }
        return s
    }

    /// What actually goes into the characteristic. UTF-8, no framing.
    func bytes() -> Data { Data(encode().utf8) }
}

func parseProbeCard(_ payload: String) -> ProbeCard? {
    guard let mark = payload.range(of: "://friend?") else { return nil }
    let query = String(payload[mark.upperBound...])
    if query.isEmpty { return nil }
    var q: [String: String] = [:]
    for pair in query.split(separator: "&", omittingEmptySubsequences: false) {
        guard let eq = pair.firstIndex(of: "="), eq != pair.startIndex else { continue }
        q[formDecode(String(pair[pair.startIndex..<eq]))] = formDecode(String(pair[pair.index(after: eq)...]))
    }
    guard let key = q["k"]?.nilIfBlank else { return nil }
    return ProbeCard(
        name: q["name"]?.nilIfBlank ?? q["u"] ?? "",
        publicKey: key,
        setlistfm: q["u"]?.nilIfBlank,
        spotifyId: q["sid"]?.nilIfBlank
    )
}

// Java's `URLEncoder`/`URLDecoder` form encoding, byte for byte: alphanumerics
// and `.-*_` pass through, a space becomes `+`, everything else becomes %XX over
// its UTF-8 bytes. Hand-rolled rather than URLComponents because the other end is
// Android's URLDecoder, and `+` is exactly where the two disagree.
private func formEncode(_ s: String) -> String {
    var out = ""
    for b in Array(s.utf8) {
        switch b {
        case 0x41...0x5A, 0x61...0x7A, 0x30...0x39, 0x2E, 0x2D, 0x2A, 0x5F:
            out.append(Character(UnicodeScalar(b)))
        case 0x20:
            out.append("+")
        default:
            out += String(format: "%%%02X", b)
        }
    }
    return out
}

private func formDecode(_ s: String) -> String {
    let src = Array(s.utf8)
    var out: [UInt8] = []
    var i = 0
    func hex(_ b: UInt8) -> UInt8? {
        switch b {
        case 0x30...0x39: return b - 0x30
        case 0x41...0x46: return b - 0x41 + 10
        case 0x61...0x66: return b - 0x61 + 10
        default: return nil
        }
    }
    while i < src.count {
        if src[i] == 0x25, i + 2 < src.count, let hi = hex(src[i + 1]), let lo = hex(src[i + 2]) {
            out.append(hi << 4 | lo)
            i += 3
        } else {
            out.append(src[i] == 0x2B ? 0x20 : src[i])
            i += 1
        }
    }
    return String(decoding: out, as: UTF8.self)
}

/// How much of a 31-byte scan response is left for the display name: 31 total
/// − 2 (length + AD type) − 2 (company id) = 27. iOS pays a different tax on a
/// Complete Local Name, but keeping one budget keeps one truncation rule.
let scanResponseNameBudget = 27

/// Longest prefix of `name` whose UTF-8 encoding fits in `budget` bytes. Cutting
/// on Character boundaries is why an emoji is never split in half.
func truncateToBytes(_ name: String, budget: Int = scanResponseNameBudget) -> String {
    var out = ""
    var used = 0
    for ch in name {
        let n = String(ch).utf8.count
        if used + n > budget { break }
        out.append(ch)
        used += n
    }
    return out
}

/// What the GATT server hands back for one read at `offset`. A payload longer
/// than one ATT PDU arrives as a rising-offset series of ATT_READ_BLOB requests
/// and the reading side reassembles them, so the server only has to answer each
/// slice correctly. Ignoring the offset (as the old #18 probe did) silently
/// truncates anything over ~22 bytes — `BleProbe.kt:142`.
func sliceForOffset(_ payload: Data, _ offset: Int) -> Data {
    offset >= payload.count ? Data() : payload.subdata(in: offset..<payload.count)
}

/// The write path's mirror of `sliceForOffset`. A card longer than one ATT PDU
/// arrives as a rising-offset series of write requests (a "long write") and only
/// the whole accumulation parses, so the server must place each chunk *at its
/// offset* rather than assume one write carried the payload — the same trap as the
/// read path, in reverse.
func writeAtOffset(_ existing: Data, _ offset: Int, _ chunk: Data) -> Data {
    guard offset >= 0 else { return existing }
    var out = [UInt8](existing)
    if out.count < offset + chunk.count {
        out += [UInt8](repeating: 0, count: offset + chunk.count - out.count)
    }
    out.replaceSubrange(offset..<(offset + chunk.count), with: [UInt8](chunk))
    return Data(out)
}

/// Android's name, off its manufacturer-data record: 2-byte little-endian company
/// id then UTF-8. Nil for anyone else's company id. The mirror of `nameFrom()`.
func nameFromManufacturerData(_ data: Data) -> String? {
    guard data.count > 2 else { return nil }
    let bytes = [UInt8](data)
    let company = UInt16(bytes[0]) | UInt16(bytes[1]) << 8
    guard company == testCompanyId else { return nil }
    return String(decoding: bytes[2...], as: UTF8.self).nilIfBlank
}

// --- Peers ---

/// Someone visible in an Exchange. The screen sees a name and nothing about which
/// radio found them; on iOS that is always BLE, but the shape is Android's so the
/// two screens stay readable as the same screen.
struct ExchangePeer: Identifiable, Equatable {
    /// Dedup key: the (stable, per-boot) peripheral identifier. Never a display
    /// name — two people called "Ozzy" must not collapse into one row, because a
    /// row that vanishes while someone is reaching for it is the failure to avoid.
    let id: String
    let name: String
    let setlistfm: String?
    /// Scan start to first advertisement — the discovery leg.
    var discoveryMs: Int = 0
}

/// BLE hits with no name are dropped: a row you cannot label ("Connecting with …?")
/// is worse than no row, and the name in the advertisement exists precisely so a
/// real name arrives before any connection.
func mergePeers(_ ble: [PeerHit]) -> [ExchangePeer] {
    var seen = Set<String>()
    return ble.compactMap { hit in
        guard let name = hit.name?.nilIfBlank, seen.insert(hit.id).inserted else { return nil }
        return ExchangePeer(id: hit.id, name: name, setlistfm: nil, discoveryMs: hit.discoveryMs)
    }
}

/// One advertisement seen by the central.
struct PeerHit: Equatable {
    let id: String
    let name: String?
    let rssi: Int
    let discoveryMs: Int
}

/// A read card becomes a friend only when it carries a setlist.fm username — the
/// same invariant the QR card has always held. A card without one is a contact
/// with no timeline; storing that is the relationship layer's job (#28/#29).
func friendFromCard(_ card: ProbeCard) -> Friend? {
    // Checked, not merely non-blank: a card is written by any radio in range, and the
    // username goes into a setlist.fm path carrying our API key. See #187, and
    // `isPlausibleSetlistFmUser` for the rule and what it deliberately costs.
    guard let user = card.setlistfm?.nilIfBlank?.trimmingCharacters(in: .whitespaces).nilIfBlank,
          isPlausibleSetlistFmUser(user)
    else { return nil }
    return Friend(setlistfm: user, name: card.name.nilIfBlank ?? user, spotifyId: card.spotifyId)
}
