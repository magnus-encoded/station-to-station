import Foundation

// The wire layer of the gossip channel (#417), and the cross-platform contract two phones
// have to agree on byte for byte before a single check-in crosses between them. Pure Swift
// with no CoreBluetooth in sight, exactly as `CardWire.swift` is pure against
// `BleExchange.swift` (ADR-0001) — and for the harder reason as well: the transport above it
// cannot be asserted without two phones and a gig, so everything that *can* be decided from
// bytes alone is decided here, where XCTest reaches it.
//
// Android's twin is `data/gossip/GossipWire.kt`, and the assertions in `GossipWireTests` are
// the assertions Android runs on the JVM.
//
// ## The meeting, in two GATT operations
//
//   1. The connecting side **reads the challenge** and gets a fresh nonce and the listener's
//      `gossipTokenOffer`. It resolves that offer against its own token table. No match, and
//      it hangs up having learnt nothing but "some Station to Station device is nearby",
//      which is what it already knew from the advertisement.
//   2. It **writes one Pass**: its own identity key, a signature over `gossipAuthPayload` of
//      that nonce, and the batch. The listener checks the key belongs to a **Contact**,
//      checks the signature against the nonce *it* issued, and hands `(from, batch)` to
//      `gossipStormGate` — the only thing that judges a message.
//
// **The listener never names itself.** The obvious challenge — "my identity key and a nonce"
// — would hand a stable, lifelong identifier to any radio that connects, in the background,
// all night. That is the exact disclosure the rotating token exists to prevent, so the
// listener publishes per-pair tokens instead and a stranger reads numbers that mean nothing
// and are different in a quarter of an hour.
//
// **Push-only.** Every device runs both halves of the radio, so a device with something to
// say connects and writes, and a device with nothing to say never has to be believed about
// anything. There is no "prove yourself so I can trust what you hand back", only "prove
// yourself before I read what you pushed".
//
// ## Why the token is not in the advertisement, on this platform
//
// It would be the obvious place, and on Android it is one. CoreBluetooth's `startAdvertising`
// honours exactly two keys — a local name and a list of service UUIDs — so an iPhone cannot
// put arbitrary bytes in an advertisement at all (`BleExchange.swift` records the same fact
// for the Exchange's display name). Worse, a *backgrounded* iPhone drops the local name
// entirely and moves its service UUIDs into an overflow area that only another iOS device
// explicitly scanning for that exact UUID can see.
//
// So Android's scan-response token is a shortcut that saves *Android* a connection when it
// meets another Android, and the challenge read is the path both platforms share. An Android
// scanner that treated missing manufacturer data as "not a Contact" would never speak to an
// iPhone; it connects and reads instead. Same shape as the **Card**: one payload, a
// cross-platform BLE route, and a faster Android-only route beside it carrying identical
// bytes.
//
// ## Framing, since a batch does not fit in an MTU
//
// The two directions are framed differently because GATT frames them differently:
//
//   * A **read** is a long read: the reader asks again at a rising offset until it gets a
//     short answer. The whole payload is addressable, so the writer serves slices of it and
//     the offset means what it says (`sliceForOffset`).
//   * A **write** is a sequence of appends, each at offset 0, terminated by a zero-length
//     write. Not offsets: CoreBluetooth performs no prepared writes and silently truncates a
//     `writeValue` past the MTU, so the sender chunks by hand and every chunk arrives at the
//     peripheral looking like the start of the value. Append-and-terminate is the one framing
//     that reads identically on both platforms, and Android's peripheral appends to match.
//
// A zero-length write is therefore meaningful and never ignored: it is what says "that was
// the whole Pass".

/// Fixed on both platforms, and deliberately not the Exchange's service. Change either and
/// the phones stop seeing each other.
///
/// A separate service, not another characteristic on `exchangeServiceUUIDString`, because the
/// two channels have opposite lifetimes: the Exchange advertises only while a human is on its
/// screen (ADR-0016) and this one advertises in the background (ADR-0019). One service would
/// mean a background scanner waking for every Exchange in the room, and an Exchange scanner
/// listing every pocket in the venue as a peer to tap.
let gossipServiceUUIDString = "7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7721"

/// Read first, by the connecting side: a fresh nonce and whose **Contacts** this device could
/// be. Never this device's own key.
let gossipChallengeCharacteristicUUIDString = "7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7722"

/// Written second, by the connecting side, once it knows who it is talking to: who it is, its
/// proof, and everything it has to offer.
let gossipPassCharacteristicUUIDString = "7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7723"

/// The hard ceiling on one **Pass**, in bytes.
///
/// Not the same bound as `gossipMaxBatch` and not a substitute for it: that one is the gate's
/// rule about how many messages are *judged*, this one is the transport's rule about how much
/// memory a peer's write is allowed to cost before anything has been decided at all. A hostile
/// **Contact** must not be able to make this device hold a megabyte because it opened a GATT
/// connection.
///
/// Sixty-four messages at their realistic worst — a 64-character id, a 64-character gig id, a
/// ~124-character key, two timestamps and a ~96-character signature — is a little over 32 kB,
/// so this leaves room without leaving a hole. Anything larger is refused whole rather than
/// truncated: half a **Pass** is not a **Pass**.
let gossipMaxWireBytes = 40_000

/// The header every challenge starts with.
let gossipChallengeV1 = "station-to-station/gossip-challenge/1"

/// The header every **Pass** starts with. A version in the string so a later envelope is a
/// different document rather than an ambiguous one, exactly as `gossipPayloadV1` is.
let gossipPassV1 = "station-to-station/gossip-pass/1"

/// The domain separator for the possession proof.
///
/// The **Contact** identity key also answers the LAN reconcile challenge (#265) and signs
/// gossip payloads (`gossipPayloadV1`). Three uses, three prefixes, so a signature made for
/// one can never be presented as an answer to another. Reconcile's nonce is a certificate
/// fingerprint — raw digest bytes — so producing one that begins with this ASCII prefix would
/// take a preimage, not a choice.
let gossipAuthV1 = "station-to-station/gossip-auth/1"

/// How many bytes of nonce a listener demands back.
///
/// Thirty-two, matching the digest the signature is taken over anyway. The nonce exists so
/// that a recording of yesterday's **Pass** cannot be replayed as today's identity; the only
/// property that has to hold is that a listener never issues the same one twice, which at
/// this width it will not.
let gossipNonceBytes = 32

/// What a listener answers the challenge read with.
///
/// `tokens` is not an identity by itself — `gossipResolveOffer` turns it into a **Contact**
/// key, and even then the key is only a hint until the **Pass** proves possession.
struct GossipChallenge: Equatable {
    var nonce: Data
    var tokens: [String]
}

/// One **Pass**: who claims to be pushing, their proof, and what they push.
struct GossipPass: Equatable {
    /// The pushing peer's identity key, base64 X.509 SubjectPublicKeyInfo.
    var from: String
    /// Base64 DER over `gossipAuthPayload` of the nonce this device issued.
    var proof: String
    var batch: [GossipCheckIn]
}

/// The bytes a peer signs to prove it holds the key it claims.
///
/// The nonce is base64'd rather than concatenated raw so that the payload is text throughout
/// and the two platforms cannot disagree about byte order or padding in the middle of a
/// signed value.
func gossipAuthPayload(_ nonce: Data) -> Data {
    Data("\(gossipAuthV1)\n\(nonce.base64EncodedString())".utf8)
}

/// The challenge on the wire: the header, the base64 nonce, then one token per line.
///
/// Line-separated with no escaping problem to get wrong: base64 contains no newline, and a
/// token is 16 hex characters and can contain nothing else.
func encodeGossipChallenge(_ challenge: GossipChallenge) -> Data {
    let lines = [gossipChallengeV1, challenge.nonce.base64EncodedString()]
        + challenge.tokens.filter(isSafeGossipToken)
    return Data(lines.joined(separator: "\n").utf8)
}

/// Nil for anything that is not a challenge, or whose nonce is not `gossipNonceBytes`.
///
/// Malformed token lines are dropped rather than failing the whole read: a peer running a
/// later build may publish something this one does not understand, and the tokens it *does*
/// understand are still worth resolving.
func decodeGossipChallenge(_ data: Data) -> GossipChallenge? {
    guard !data.isEmpty, data.count <= gossipMaxWireBytes else { return nil }
    var lines = String(decoding: data, as: UTF8.self).split(separator: "\n",
                                                            omittingEmptySubsequences: false)
    guard lines.count >= 2, lines[0] == gossipChallengeV1[...],
          let nonce = Data(base64Encoded: String(lines[1])), nonce.count == gossipNonceBytes
    else { return nil }
    lines.removeFirst(2)
    return GossipChallenge(nonce: nonce, tokens: lines.map(String.init).filter(isSafeGossipToken))
}

/// A **Pass** as it goes over the wire: the header, the claim line, then one tab-separated
/// line per message.
///
/// Tab-separated, and safe to be: a `messageId` is hex, a `gigId` has been through
/// `isSafeGossipId`, a key and a signature are base64, and the times are decimals — none of
/// which can contain a tab or a newline. The fields that could are checked anyway, here and in
/// `gossipPayload`, because "cannot happen" is how a separator injection gets written.
///
/// Times are epoch **seconds**, decimal — the same representation `gossipPayload` signs over,
/// so a message that survives this encoding round-trips to the identical id and the identical
/// signature check.
///
/// A message that cannot be canonically encoded is **dropped from the batch** rather than
/// failing the whole **Pass**: the rest of the night's news is still worth pushing. Nil only
/// for a claim line that cannot be written, or a **Pass** over the size ceiling.
func encodeGossipPass(_ pass: GossipPass) -> Data? {
    guard !pass.from.isEmpty, !pass.proof.isEmpty,
          isWireSafe(pass.from), isWireSafe(pass.proof)
    else { return nil }
    let records = pass.batch.compactMap(gossipPassLine)
    let text = ([gossipPassV1, "\(pass.from)\t\(pass.proof)"] + records).joined(separator: "\n")
    let bytes = Data(text.utf8)
    return bytes.count > gossipMaxWireBytes ? nil : bytes
}

private func gossipPassLine(_ message: GossipCheckIn) -> String? {
    guard let checkedInAt = gossipEpochSeconds(message.checkedInAt),
          let expiresAt = gossipEpochSeconds(message.expiresAt)
    else { return nil }
    let fields = [message.messageId, message.gigId, message.checkedInBy,
                  String(checkedInAt), String(expiresAt), message.signature]
    guard fields.allSatisfy({ !$0.isEmpty && isWireSafe($0) }) else { return nil }
    return fields.joined(separator: "\t")
}

/// Nil for anything that is not a **Pass**: the wrong header, no claim line, an oversized
/// write, or bytes that are not UTF-8.
///
/// Individual records that do not parse are **skipped, not fatal**. The alternative — one
/// unreadable record discarding a peer's whole batch — hands any device in the chain a way to
/// stop a message it does not like by corrupting the one next to it. Nothing is trusted either
/// way: what survives here still has to get past `gossipStormGate`, which recomputes every id
/// and checks every signature, and which is also where `gossipMaxBatch` is applied.
func decodeGossipPass(_ data: Data) -> GossipPass? {
    guard !data.isEmpty, data.count <= gossipMaxWireBytes else { return nil }
    var lines = String(decoding: data, as: UTF8.self).split(separator: "\n",
                                                            omittingEmptySubsequences: false)
    guard lines.count >= 2, lines[0] == gossipPassV1[...] else { return nil }
    let claim = lines[1].split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
    guard claim.count == 2, !claim[0].isEmpty, !claim[1].isEmpty else { return nil }
    lines.removeFirst(2)
    return GossipPass(from: claim[0], proof: claim[1],
                      batch: lines.compactMap(gossipMessageFromLine))
}

private func gossipMessageFromLine(_ line: Substring) -> GossipCheckIn? {
    let fields = line.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
    guard fields.count == 6, fields.allSatisfy({ !$0.isEmpty }),
          let checkedInAt = Int64(fields[3]), let expiresAt = Int64(fields[4]),
          abs(checkedInAt) <= gossipMaxEpochSecond, abs(expiresAt) <= gossipMaxEpochSecond
    else { return nil }
    return GossipCheckIn(
        messageId: fields[0],
        gigId: fields[1],
        checkedInBy: fields[2],
        checkedInAt: Date(timeIntervalSince1970: TimeInterval(checkedInAt)),
        expiresAt: Date(timeIntervalSince1970: TimeInterval(expiresAt)),
        signature: fields[5]
    )
}

private func isWireSafe(_ value: String) -> Bool {
    !value.contains("\t") && !value.contains("\n")
}

/// My own arrival, ready to travel: the message a check-in mints for the gossip channel.
///
/// The one place a message is *authored* rather than relayed, so it is the one place the id
/// and the signature are produced rather than checked. Built through `gossipPayload` and
/// `gossipMessageId` — the same functions `gossipStormGate` will recompute on the far end,
/// which is what guarantees a message this device mints is a message the other twin accepts.
///
/// `expiresAt` is the gig's own night end where this device knows the date (`gossipExpiry`),
/// and `gossipMaxLifetime` from the check-in where it does not. Never longer: the ceiling in
/// `gossipStormGate` would cap it on arrival anyway, and a claim nobody honours is a claim not
/// worth signing.
///
/// `sign` is `ContactIdentity.sign` in the app and a test's own key in a test — the same
/// injection `gossipStormGate` takes for `verify`, for the same reason: the Secure Enclave
/// does not exist off a phone, and the envelope's shape must be assertable anyway.
func gossipCheckInMessage(gigId: String, gigDate: String?, publicKey: String, now: Date,
                          calendar: Calendar = .current,
                          sign: (Data) -> Data?) -> GossipCheckIn? {
    guard isSafeGossipId(gigId), !publicKey.isEmpty, let seconds = gossipEpochSeconds(now)
    else { return nil }
    let checkedInAt = Date(timeIntervalSince1970: TimeInterval(seconds))
    let night = gigDate.flatMap { gossipExpiry(gigDate: $0, calendar: calendar) }
    let expiresAt = night ?? checkedInAt.addingTimeInterval(gossipMaxLifetime)
    guard expiresAt > checkedInAt else { return nil }
    var message = GossipCheckIn(messageId: "", gigId: gigId, checkedInBy: publicKey,
                                checkedInAt: checkedInAt, expiresAt: expiresAt, signature: "")
    guard let payload = gossipPayload(message), let signature = sign(payload),
          let messageId = gossipMessageId(message)
    else { return nil }
    message.messageId = messageId
    message.signature = signature.base64EncodedString()
    return message
}

extension Data {
    /// The payload in pieces that fit one ATT write, in order.
    ///
    /// Chunked by hand because a CoreBluetooth central performs no long write: `writeValue`
    /// silently truncates anything past the negotiated MTU. The empty chunk that ends a
    /// **Pass** is added by the caller, not here — it is part of the protocol, not of
    /// splitting a buffer.
    func gossipChunks(by size: Int) -> [Data] {
        guard size > 0, !isEmpty else { return [self] }
        return stride(from: 0, to: count, by: size).map {
            subdata(in: $0..<Swift.min($0 + size, count))
        }
    }
}
