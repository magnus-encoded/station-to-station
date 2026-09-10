import Foundation

// The GATT contract of the gossip channel (#417): the numbers and the framing two phones have
// to agree on before a single byte of gossip crosses between them. Pure Swift with no
// CoreBluetooth in sight, exactly as `CardWire.swift` is pure against `BleExchange.swift`
// (ADR-0001) — and for the harder reason as well: the transport above it cannot be asserted
// without two phones and a gig, so everything that *can* be decided from bytes alone is decided
// away from the radio, where XCTest reaches it.
//
// This file is deliberately *version-independent*. What a challenge and a **Pass** say to each
// other is `PublicGossip.swift`'s business and has already been replaced once; which service
// they say it over, and how a payload too big for an MTU is cut up, survived that replacement
// unchanged and would survive the next one. Keeping the two apart is what let the v1 documents
// be deleted without touching a UUID.
//
// ## The meeting, in two GATT operations
//
//   1. The connecting side **reads the challenge** and gets a fresh nonce and the listener's
//      relay key, proved. No proof, and it hangs up having learnt nothing but "some Station to
//      Station device is nearby", which is what the advertisement already told it.
//   2. It **writes one Pass**: its own relay key, a signature over `publicGossipAuthPayload` of
//      that nonce, and the batch. The listener checks the signature against the nonce *it*
//      issued — `GossipTransport.peripheralManager(_:didReceiveWrite:)` — and only then hands
//      `(from, batch)` to `PublicGossipState.receive`, which is the only thing that judges a
//      fact.
//
// **The listener never names itself durably.** The obvious challenge — "my identity key and a
// nonce" — would hand a stable, lifelong identifier to any radio that connects, in the
// background, all night. What it publishes instead is a nightly relay key (`GossipChannel`'s
// `relayScope`), so a stranger reads a number that means nothing and is different tomorrow.
//
// **Push-only.** Every device runs both halves of the radio, so a device with something to say
// connects and writes, and a device with nothing to say never has to be believed about
// anything. There is no "prove yourself so I can trust what you hand back", only "prove
// yourself before I read what you pushed".
//
// ## Why nothing identifying is in the advertisement, on this platform
//
// It would be the obvious place, and on Android it is one. CoreBluetooth's `startAdvertising`
// honours exactly two keys — a local name and a list of service UUIDs — so an iPhone cannot put
// arbitrary bytes in an advertisement at all (`BleExchange.swift` records the same fact for the
// Exchange's display name). Worse, a *backgrounded* iPhone drops the local name entirely and
// moves its service UUIDs into an overflow area that only another iOS device explicitly
// scanning for that exact UUID can see. So the challenge read is the path both platforms share,
// and an Android scanner that treated missing manufacturer data as "nobody worth talking to"
// would never speak to an iPhone at all.
//
// ## Framing, since a batch does not fit in an MTU
//
// The two directions are framed differently because GATT frames them differently:
//
//   * A **read** is a long read: the reader asks again at a rising offset until it gets a short
//     answer. The whole payload is addressable, so the writer serves slices of it and the
//     offset means what it says (`sliceForOffset`).
//   * A **write** is a sequence of appends, each at offset 0, terminated by a zero-length
//     write. Not offsets: CoreBluetooth performs no prepared writes and silently truncates a
//     `writeValue` past the MTU, so the sender chunks by hand and every chunk arrives at the
//     peripheral looking like the start of the value. Append-and-terminate is the one framing
//     that reads identically on both platforms, and Android's peripheral appends to match.
//
// A zero-length write is therefore meaningful and never ignored: it is what says "that was the
// whole Pass".

/// Fixed on both platforms, and deliberately not the Exchange's service. Change either and the
/// phones stop seeing each other.
///
/// A separate service, not another characteristic on `exchangeServiceUUIDString`, because the
/// two channels have opposite lifetimes: the Exchange advertises only while a human is on its
/// screen (ADR-0016) and this one advertises in the background (ADR-0019). One service would
/// mean a background scanner waking for every Exchange in the room, and an Exchange scanner
/// listing every pocket in the venue as a peer to tap.
let gossipServiceUUIDString = "7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7721"

/// Read first, by the connecting side: a fresh nonce and the listener's nightly relay key.
/// Never this device's own durable identity key.
let gossipChallengeCharacteristicUUIDString = "7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7722"

/// Written second, by the connecting side, once it has read the challenge: who it is for
/// tonight, its proof, and everything it has to offer.
let gossipPassCharacteristicUUIDString = "7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7723"

/// The hard ceiling on one **Pass**, in bytes.
///
/// Not the same bound as `gossipMaxBatch` and not a substitute for it: that one is about how
/// many facts are *judged*, this one is the transport's rule about how much memory a peer's
/// write is allowed to cost before anything has been decided at all. A hostile peer must not be
/// able to make this device hold a megabyte because it opened a GATT connection.
///
/// Sixty-four envelopes at their realistic worst is a little over 32 kB, so this leaves room
/// without leaving a hole. Anything larger is refused whole rather than truncated: half a
/// **Pass** is not a **Pass**.
let gossipMaxWireBytes = 40_000

/// The most facts one **Pass** carries, and the number `decodePublicGossipPass` truncates to.
///
/// A bound on judgement rather than on memory: it is what stops one meeting costing this device
/// an unbounded number of signature verifications, and it is the batch size `gossipAdmit`
/// reasons in.
let gossipMaxBatch = 64

/// How many bytes of nonce a listener demands back.
///
/// Thirty-two, matching the digest the signature is taken over anyway. The nonce exists so that
/// a recording of yesterday's **Pass** cannot be replayed as today's identity; the only property
/// that has to hold is that a listener never issues the same one twice, which at this width it
/// will not.
let gossipNonceBytes = 32

extension Data {
    /// The payload in pieces that fit one ATT write, in order.
    ///
    /// Chunked by hand because a CoreBluetooth central performs no long write: `writeValue`
    /// silently truncates anything past the negotiated MTU. The empty chunk that ends a
    /// **Pass** is added by the caller, not here — it is part of the protocol, not of splitting
    /// a buffer.
    func gossipChunks(by size: Int) -> [Data] {
        guard size > 0, !isEmpty else { return [self] }
        return stride(from: 0, to: count, by: size).map {
            subdata(in: $0..<Swift.min($0 + size, count))
        }
    }
}
