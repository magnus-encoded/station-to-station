import XCTest
import CryptoKit
@testable import StationToStation

/// The **Seen with** record (#498, iOS twin): who a **Gig**'s **Room** can still name afterwards.
///
/// Its subject is the *evidence*, not the radio, which is why everything here is
/// `PublicGossipState` and nothing here is a BLE callback. A completed **Pass** is the one thing
/// the transport has to tell it about, through `PublicGossipState.rememberPass`; a **Check-in**
/// it already holds as a **Fact**. Mirrors Android's `SeenWithTest`.
final class SeenWithTests: XCTestCase {

    /// The whole of the line at once: order, dedup, and the two kinds of evidence side by side.
    ///
    /// A **Pass** is what puts a device in the count — hearing one of their **Facts** relayed by
    /// somebody else does not, and neither does an advertisement, which reaches nothing in here
    /// at all. A second **Pass** with the same device moves its place in the order and adds no
    /// second entry, because `metDevices` is keyed by device.
    func testDirectPassesOrderTheNamesAndStrangersAreCountedNotNamed() throws {
        var state = PublicGossipState()
        let ada = P256.Signing.PrivateKey()
        let bo = P256.Signing.PrivateKey()
        let adaGig = P256.Signing.PrivateKey()
        let boGig = P256.Signing.PrivateKey()
        _ = state.receive(try checkIn(gig: adaGig, card: ada, scope: "ada-scope", at: 1000), from: key(adaGig), now: 1100)
        _ = state.receive(try checkIn(gig: boGig, card: bo, scope: "bo-scope", at: 1200), from: key(boGig), now: 1300)
        state.recognizeContacts([key(ada), key(bo)], names: [key(ada): "Ada", key(bo): "Bo"])
        // Two Facts admitted and nothing met: only a completed Pass writes a device down, and
        // an advertisement — which reaches nothing in here at all — certainly does not.
        XCTAssertTrue(state.metDevices.isEmpty)

        state.rememberPass(key(adaGig), batch: [], activeGigId: "gig", now: 5_000)
        state.rememberPass(key(boGig), batch: [], activeGigId: "gig", now: 6_000)
        state.rememberPass("a-stranger-relay-key", batch: [], activeGigId: "gig", now: 7_000)
        // Met again later: Ada moves ahead of Bo, and nothing is duplicated.
        state.rememberPass(key(adaGig), batch: [], activeGigId: "gig", now: 8_000)

        let seen = state.seenWith(gigIds: ["gig"])
        XCTAssertEqual(seen.named, ["Ada", "Bo"])
        XCTAssertEqual(seen.others, 1)
        XCTAssertEqual(state.metDevices["gig"]?.count, 3)
        XCTAssertEqual(seenWithLine(seen), "Seen with Ada, Bo + 1 other")
    }

    /// A **Contact** known only from a **Check-in** is named and adds nothing to the device count,
    /// even when a witness **Carried** the last hop — attribution is the gate, not proximity —
    /// and they sort after everyone actually met.
    func testAWitnessCarriedCheckInNamesItsContactWithoutRaisingTheDeviceCount() throws {
        var state = PublicGossipState()
        let ada = P256.Signing.PrivateKey()
        let cleo = P256.Signing.PrivateKey()
        let adaGig = P256.Signing.PrivateKey()
        let cleoGig = P256.Signing.PrivateKey()
        let carried = try checkIn(gig: cleoGig, card: cleo, scope: "cleo-scope", at: 2000)
        let witness = try XCTUnwrap(GossipEnvelope(gigId: "gig", scope: "witness-scope", author: key(adaGig),
            createdAt: 2100, expiresAt: 100000, kind: "witness", text: carried.record())
            .signed { try? adaGig.signature(for: $0).derRepresentation })
        XCTAssertTrue(state.receive(witness, from: key(adaGig), now: 2200))
        _ = state.receive(try checkIn(gig: adaGig, card: ada, scope: "ada-scope", at: 1000), from: key(adaGig), now: 1100)
        state.recognizeContacts([key(ada), key(cleo)], names: [key(ada): "Ada", key(cleo): "Cleo"])
        state.rememberPass(key(adaGig), batch: [], activeGigId: "gig", now: 3000)

        let seen = state.seenWith(gigIds: ["gig"])
        XCTAssertEqual(seen.named, ["Ada", "Cleo"])
        XCTAssertEqual(seen.others, 0)
        XCTAssertEqual(seenWithLine(seen), "Seen with Ada, Cleo")
    }

    /// Where a **Pass** lands. Their own signed claim names its night; anything else is this
    /// phone's own position, which is the active **Gig**. A **Fact** they were merely **Carrying**
    /// for somebody else says nothing about where the device handing it over is standing.
    func testAPassLandsOnTheGigItsOwnClaimNamesOrElseOnTheActiveGig() throws {
        var state = PublicGossipState()
        let theirs = P256.Signing.PrivateKey()
        let claim = try checkIn(gig: theirs, card: nil, scope: "their-scope", at: 1000, gigId: "their-gig")
        state.rememberPass(key(theirs), batch: [claim], activeGigId: "tonight", now: 4000)
        XCTAssertEqual(Set(state.metDevices.keys), ["their-gig"])

        // Someone else's claim in the same batch is Carried, not evidence about the carrier.
        let other = P256.Signing.PrivateKey()
        let relayed = try checkIn(gig: other, card: nil, scope: "other-scope", at: 1000, gigId: "elsewhere")
        state.rememberPass("a-relay-key", batch: [relayed], activeGigId: "tonight", now: 5000)
        XCTAssertEqual(Set(state.metDevices.keys), ["their-gig", "tonight"])
        XCTAssertEqual(state.seenWith(gigIds: ["tonight"]).others, 1)
        XCTAssertTrue(state.seenWith(gigIds: ["elsewhere"]).isEmpty)
    }

    /// Block is admission, not a rewrite of the evening (ADR-0021). Their device was met and is
    /// counted; their name is not said. A **Check-in** of theirs that only ever arrived relayed
    /// leaves no trace at all, because `receive` never stored it.
    func testABlockedContactIsCountedAnonymouslyWhenMetAndNotAtAllWhenOnlyRelayed() throws {
        var state = PublicGossipState()
        let blockedCard = P256.Signing.PrivateKey()
        let blockedGig = P256.Signing.PrivateKey()
        let awayCard = P256.Signing.PrivateKey()
        let awayGig = P256.Signing.PrivateKey()
        _ = state.receive(try checkIn(gig: blockedGig, card: blockedCard, scope: "blocked-scope", at: 1000), from: key(blockedGig), now: 1100)
        // Only ever relayed — a third party's key delivered it, so `receive` stored it but no
        // device of theirs was ever met.
        _ = state.receive(try checkIn(gig: awayGig, card: awayCard, scope: "away-scope", at: 1200), from: "some-relay", now: 1300)
        state.recognizeContacts([key(blockedCard), key(awayCard)],
            names: [key(blockedCard): "Blocked", key(awayCard): "Away"])
        state.blocked.insert(key(blockedCard))
        state.blocked.insert(key(awayCard))
        state.rememberPass(key(blockedGig), batch: [], activeGigId: "gig", now: 4000)

        let seen = state.seenWith(gigIds: ["gig"])
        XCTAssertEqual(seen.named, [])
        XCTAssertEqual(seen.others, 1)
        XCTAssertEqual(seenWithLine(seen), "Seen with 1 other")
    }

    /// The record is not presence. It is written down, it comes back after a restart in the same
    /// order, and `prune` — the end of the night for everything the transport keeps — does not
    /// touch it.
    func testTheRecordSurvivesRestartAndOutlivesTheNightsGossip() throws {
        var state = PublicGossipState()
        let ada = P256.Signing.PrivateKey()
        let adaGig = P256.Signing.PrivateKey()
        _ = state.receive(try checkIn(gig: adaGig, card: ada, scope: "ada-scope", at: 1000), from: key(adaGig), now: 1100)
        state.recognizeContacts([key(ada)], names: [key(ada): "Ada"])
        state.rememberPass(key(adaGig), batch: [], activeGigId: "gig", now: 2000)
        state.rememberPass("stranger", batch: [], activeGigId: "gig", now: 3000)

        let encoded = try JSONEncoder().encode(state)
        var restored = try JSONDecoder().decode(PublicGossipState.self, from: encoded)
        restored.prune(now: 1_000_000)
        XCTAssertTrue(restored.held.isEmpty)
        let seen = restored.seenWith(gigIds: ["gig"])
        XCTAssertEqual(seen.named, ["Ada"])
        XCTAssertEqual(seen.others, 1)
    }

    /// Adoption (#496) renames the night, and the record has to follow without the device being
    /// counted once under each id. The union is over device identity, not over map entries.
    func testAnAdoptedGigIdSeesOneRecordAndNotTwo() {
        var state = PublicGossipState()
        state.rememberPass("their-relay", batch: [], activeGigId: "local-id", now: 1000)
        state.rememberPass("their-relay", batch: [], activeGigId: "setlist-id", now: 2000)
        XCTAssertEqual(state.seenWith(gigIds: ["local-id", "setlist-id"]).others, 1)
        XCTAssertEqual(state.seenWith(gigIds: ["local-id"]).others, 1)
    }

    /// The attribution rule: recognition cannot be revoked, so removing a **Contact** does not
    /// turn a name this device already learned back into a stranger on an old night.
    func testRemovingAContactLeavesTheirNameOnTheNightAlreadyRecognised() throws {
        var state = PublicGossipState()
        let ada = P256.Signing.PrivateKey()
        let adaGig = P256.Signing.PrivateKey()
        _ = state.receive(try checkIn(gig: adaGig, card: ada, scope: "ada-scope", at: 1000), from: key(adaGig), now: 1100)
        state.recognizeContacts([key(ada)], names: [key(ada): "Ada"])
        state.rememberPass(key(adaGig), batch: [], activeGigId: "gig", now: 2000)
        state.recognizeContacts([], names: [:])

        XCTAssertEqual(state.seenWith(gigIds: ["gig"]).named, ["Ada"])
        XCTAssertEqual(state.seenWith(gigIds: ["gig"]).others, 0)
    }

    /// The known divergence, pinned so nobody "fixes" it into a lie.
    ///
    /// A **Pass** proves the peer's night-scoped **Gig** key when it carried their own claim and
    /// their nightly relay key otherwise, and **Attribution** is exactly the reason nothing links
    /// the two: the relay key names nobody, by design (ADR-0021). So a **Contact** met both ways
    /// is named once *and* counted once among the others. This device cannot see that they are
    /// the same phone, and folding them would be it asserting something it does not know.
    func testAContactMetOnBothKeysIsNamedOnceAndStillCountedOnce() throws {
        var state = PublicGossipState()
        let ada = P256.Signing.PrivateKey()
        let adaGig = P256.Signing.PrivateKey()
        _ = state.receive(try checkIn(gig: adaGig, card: ada, scope: "ada-scope", at: 1000), from: key(adaGig), now: 1100)
        state.recognizeContacts([key(ada)], names: [key(ada): "Ada"])
        state.rememberPass(key(adaGig), batch: [], activeGigId: "gig", now: 2000)
        state.rememberPass("adas-nightly-relay-key", batch: [], activeGigId: "gig", now: 3000)

        let seen = state.seenWith(gigIds: ["gig"])
        XCTAssertEqual(seen.named, ["Ada"])
        XCTAssertEqual(seen.others, 1)
        XCTAssertEqual(seenWithLine(seen), "Seen with Ada + 1 other")
    }

    // --- Helpers ---

    private func key(_ of: P256.Signing.PrivateKey) -> String {
        of.publicKey.derRepresentation.base64EncodedString()
    }

    /// A check-in signed by a nightly Gig key, sealed to `card` when there is one to recognise.
    private func checkIn(gig: P256.Signing.PrivateKey, card: P256.Signing.PrivateKey?,
                         scope: String, at: Int64 = 1000, gigId: String = "gig") throws -> GossipEnvelope {
        let author = key(gig)
        var draft = GossipEnvelope(gigId: gigId, scope: scope, author: author,
                                   createdAt: at, expiresAt: 100000, kind: "request")
        if let card {
            let durable = card.publicKey.derRepresentation.base64EncodedString()
            let signature = try card.signature(for: gossipIdentityBinding(scope: scope, author: author)).derRepresentation
            let sealed = try AES.GCM.seal(signature, using: gossipRecognitionKey(durable: durable, scope: scope))
            draft.attribution = try XCTUnwrap(sealed.combined).base64EncodedString()
        }
        return try XCTUnwrap(draft.signed { try? gig.signature(for: $0).derRepresentation })
    }
}
