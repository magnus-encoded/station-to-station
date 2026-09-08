import Foundation

// The wire layer of the gossip channel (#417), and the cross-platform contract two phones
// have to agree on byte for byte before a single check-in crosses between them. Pure Swift
// with no CoreBluetooth in sight, exactly as `CardWire.swift` is pure against
// `BleExchange.swift` (ADR-0001) — and for the harder reason as well: the transport above
// it cannot be asserted without two phones and a gig, so everything that *can* be decided
// from bytes alone is decided here, where XCTest reaches it.
//
// Android's twin is `data/GossipWire.kt`, and the assertions in `GossipWireTests` are the
// assertions Android runs on the JVM.
//
// Wire shape:
//   advertisement  — the 128-bit service UUID, and nothing else. See below.
//   token (read)   — this device's `gossipTokenOffer`, one token per line.
//   inbox (write)  — the writer's own token for the resolved pair, then its batch.
//   outbox (read)  — the reader's token as the peripheral computed it, then its batch.
//
// **Why the token is not in the advertisement, on this platform.** It would be the obvious
// place, and on Android it is one. CoreBluetooth's `startAdvertising` honours exactly two
// keys — a local name and a list of service UUIDs — so an iPhone cannot put arbitrary bytes
// in an advertisement at all (`BleExchange.swift` records the same fact for the Exchange's
// display name). Worse, a *backgrounded* iPhone drops the local name entirely and moves its
// service UUIDs into a special "overflow" area that only another iOS device explicitly
// scanning for that exact UUID can see. So on iOS the token cannot ride the advertisement,
// and it rides the first GATT read instead. The derivation is identical to Android's — that
// is the part that must match — and only the carrier differs, the same way the Exchange's
// name rides manufacturer data on one platform and a local name on the other.
//
// **Framing, since a batch does not fit in an MTU.** Both directions are a byte stream over
// one attribute, and the two directions are framed differently because GATT frames them
// differently:
//
//   * A **read** is a long read: the reader asks again at a rising offset until it gets a
//     short answer. The whole payload is addressable, so the writer serves slices of it and
//     the offset means what it says (`sliceForOffset`).
//   * A **write** is a sequence of appends, each at offset 0, terminated by a zero-length
//     write. Not offsets: CoreBluetooth does not perform prepared writes and silently
//     truncates a `writeValue` past the MTU, so the sender chunks by hand and every chunk
//     arrives at the peripheral looking like the start of the value. Append-and-terminate is
//     the one framing that reads identically on both platforms. Android's twin must chunk its
//     writes the same way — sequential, offset 0, empty write to finish — or an iPhone will
//     see only the last chunk of every batch it is handed.
//
// A zero-length write is therefore meaningful and never ignored: it is what says "that was
// the whole batch, you may prepare the reply now".

/// Fixed on both platforms, and deliberately not the Exchange's service. Change either and
/// the phones stop seeing each other.
///
/// A separate service, not another characteristic on `exchangeServiceUUIDString`, because the
/// two channels have opposite lifetimes: the Exchange advertises only while a human is on its
/// screen (ADR-0016) and this one advertises in the background (ADR-0019). One service would
/// mean a background scanner waking for every Exchange in the room, and an Exchange scanner
/// listing every pocket in the venue as a peer to tap.
let gossipServiceUUIDString = "7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7721"

/// Read first, by the connecting side: whose Contacts this device could be.
let gossipTokenCharacteristicUUIDString = "7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7722"

/// Written second, by the connecting side, once it knows who it is talking to: its token for
/// this pair, then everything it has to offer.
let gossipInboxCharacteristicUUIDString = "7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7723"

/// Read third: what the advertising side has to offer back, prepared once the write above
/// told it who is asking. Empty for a connection that never resolved to a **Contact** — a
/// stranger gets a zero-length read, not a batch and not an error.
let gossipOutboxCharacteristicUUIDString = "7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7724"

/// The most bytes read or accepted in one direction of one meeting.
///
/// `gossipMaxBatch` already bounds how many messages are *judged*; this bounds what is
/// allocated before anything has been judged at all, which is the part a peer controls. A
/// full 64-message batch is around 22 kB, so this is roughly one and a half of those — over
/// BLE, generous.
let gossipMaxWireBytes = 32 * 1024

/// The header every token offer starts with.
let gossipTokenListV1 = "station-to-station/gossip-tokens/1"

/// The header every batch starts with. A version in the string so a later envelope is a
/// different document rather than an ambiguous one, exactly as `gossipPayloadV1` is.
let gossipBatchV1 = "station-to-station/gossip-batch/1"

/// One peer's whole offer: who it says it is on this edge, and what it is handing over.
///
/// `token` is not trusted as an identity by this file — `gossipResolveToken` turns it into a
/// **Contact** key, and `gossipStormGate` decides what that key is worth. This type only
/// says the bytes parsed.
struct GossipBatch: Equatable {
    var token: String
    var messages: [GossipCheckIn]
}

/// The token offer, as it goes over the read.
///
/// Line-separated because a token is 32 hex characters and can contain nothing else, so
/// there is no escaping problem to get wrong on one platform and not the other.
func encodeGossipTokens(_ tokens: [String]) -> Data {
    Data(([gossipTokenListV1] + tokens.filter(isSafeGossipToken)).joined(separator: "\n").utf8)
}

/// Nil for anything that is not a token offer. Malformed lines are dropped rather than
/// failing the whole read: a peer running a later build may publish something this one does
/// not understand, and the tokens it *does* understand are still worth resolving.
func decodeGossipTokens(_ data: Data) -> [String]? {
    guard data.count <= gossipMaxWireBytes else { return nil }
    var lines = String(decoding: data, as: UTF8.self).split(separator: "\n",
                                                            omittingEmptySubsequences: false)
    guard lines.first == gossipTokenListV1[...] else { return nil }
    lines.removeFirst()
    return lines.map(String.init).filter(isSafeGossipToken)
}

/// A batch as it goes over the wire: the header, the sender's token for this edge, then one
/// tab-separated line per message.
///
/// Tab-separated, and safe to be: a `messageId` is hex, a `gigId` has been through
/// `isSafeGossipId`, a key and a signature are base64, and the times are decimals — none of
/// which can contain a tab or a newline. The fields that could (`gigId`, `checkedInBy`) are
/// checked anyway, here and in `gossipPayload`, because "cannot happen" is how a separator
/// injection gets written.
///
/// Times are epoch **seconds**, decimal — the same representation `gossipPayload` signs over,
/// so a message that survives this encoding round-trips to the identical id and the identical
/// signature check. Nil for a message that cannot be canonically encoded at all, which is the
/// same message `gossipPayload` refuses.
func encodeGossipBatch(token: String, messages: [GossipCheckIn]) -> Data? {
    guard isSafeGossipToken(token) else { return nil }
    var lines = [gossipBatchV1, token]
    for message in messages.prefix(gossipMaxBatch) {
        guard let line = gossipBatchLine(message) else { return nil }
        lines.append(line)
    }
    return Data(lines.joined(separator: "\n").utf8)
}

private func gossipBatchLine(_ message: GossipCheckIn) -> String? {
    guard isSafeGossipId(message.gigId), !message.messageId.isEmpty,
          !message.checkedInBy.isEmpty, !message.signature.isEmpty,
          let checkedInAt = gossipEpochSeconds(message.checkedInAt),
          let expiresAt = gossipEpochSeconds(message.expiresAt)
    else { return nil }
    let fields = [message.messageId, message.gigId, message.checkedInBy,
                  String(checkedInAt), String(expiresAt), message.signature]
    guard fields.allSatisfy({ !$0.contains("\t") && !$0.contains("\n") }) else { return nil }
    return fields.joined(separator: "\t")
}

/// Nil for anything that is not a batch. A malformed *line* is dropped, not fatal, for the
/// same forward-compatibility reason `decodeGossipTokens` drops one — and because a peer that
/// can invalidate a whole handover with one bad line is a peer that can stop a night's
/// check-ins reaching anybody.
///
/// Nothing here judges a message. Every field it produces is still the author's claim, and
/// `gossipStormGate` is the only thing that decides otherwise: this refuses bytes it cannot
/// read, and passes on bytes it can.
func decodeGossipBatch(_ data: Data) -> GossipBatch? {
    guard data.count <= gossipMaxWireBytes else { return nil }
    var lines = String(decoding: data, as: UTF8.self).split(separator: "\n",
                                                            omittingEmptySubsequences: false)
    guard lines.count >= 2, lines[0] == gossipBatchV1[...] else { return nil }
    let token = String(lines[1])
    guard isSafeGossipToken(token) else { return nil }
    lines.removeFirst(2)
    // Truncated at `gossipMaxBatch` rather than rejected wholesale: the gate rejects the
    // overflow as `.batchLimit` anyway, and refusing the read would throw away the 64
    // messages that were fine along with the ones over the line.
    let messages = lines.prefix(gossipMaxBatch).compactMap(gossipMessageFromLine)
    return GossipBatch(token: token, messages: messages)
}

private func gossipMessageFromLine(_ line: Substring) -> GossipCheckIn? {
    let fields = line.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
    guard fields.count == 6,
          let checkedInAt = Int64(fields[3]), let expiresAt = Int64(fields[4]),
          abs(checkedInAt) <= gossipMaxEpochSecond, abs(expiresAt) <= gossipMaxEpochSecond,
          !fields[0].isEmpty, !fields[2].isEmpty, !fields[5].isEmpty
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

/// My own arrival, ready to travel: the message a check-in mints for the gossip channel.
///
/// The one place a message is *authored* rather than relayed, so it is the one place the id
/// and the signature are produced rather than checked. Built through `gossipPayload` and
/// `gossipMessageId` — the same functions `gossipStormGate` will recompute on the far end,
/// which is what guarantees a message this device mints is a message the other twin accepts.
///
/// `expiresAt` is the gig's own night end where this device knows the date (`gossipExpiry`),
/// and `gossipMaxLifetime` from the check-in where it does not. Never longer: the ceiling in
/// `gossipStormGate` would cap it on arrival anyway, and a claim nobody honours is a claim
/// not worth signing.
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
