import XCTest
@testable import StationToStation

/// The same assertions Android's `CardWireTest.kt` runs on the JVM. This is the
/// cross-platform contract: a wire change on one side should fail a test here
/// rather than a demo in a room with two phones.
final class CardWireTests: XCTestCase {

    private let card = ProbeCard(
        name: "Magnus Vikan",
        publicKey: "8J+YgPCfmIDwn5iA8J+YgPCfmIDwn5iA8J+YgPCfmIA=", // 32 bytes, base64
        setlistfm: "dizzi90",
        spotifyId: "dizziness"
    )

    func testRoundTrips() {
        XCTAssertEqual(card, parseProbeCard(card.encode()))
    }

    func testSurvivesReservedCharactersInTheName() {
        let awkward = ProbeCard(name: "\u{00C6}rlig & co =?#", publicKey: "AAAA", setlistfm: "x y")
        XCTAssertEqual(awkward, parseProbeCard(awkward.encode()))
    }

    func testRejectsAnythingWithoutAKey() {
        XCTAssertNil(parseProbeCard("station-to-station://friend?u=dizzi90&name=Magnus"))
        XCTAssertNil(parseProbeCard("station-to-station://callback?code=abc"))
        XCTAssertNil(parseProbeCard("nonsense"))
    }

    /// The premise: the card cannot ride an advertisement, but must fit a sane MTU.
    func testCardIsTooBigForAnAdvertAndSmallEnoughForOneMtuRead() {
        let size = card.bytes().count
        XCTAssertTrue(size > 31, "\(size) bytes should not fit a 31-byte advert")
        XCTAssertTrue(size <= 185, "\(size) bytes should fit one 185-byte ATT read")
    }

    /// The bytes Android's encoder produces, pinned literally. Both sides agree on
    /// Java form encoding — a space is `+`, and that is exactly where URLComponents
    /// would have disagreed.
    func testEncodesLikeAndroid() {
        XCTAssertEqual(
            "station-to-station://friend?name=Magnus+Vikan&k=AAAA&u=dizzi90",
            ProbeCard(name: "Magnus Vikan", publicKey: "AAAA", setlistfm: "dizzi90").encode()
        )
        XCTAssertEqual(
            "station-to-station://friend?name=%C3%86rlig+%26+co&k=a%2Fb%3D",
            ProbeCard(name: "\u{00C6}rlig & co", publicKey: "a/b=").encode()
        )
        // The pinned Android expectation: a space and a non-ASCII character in one
        // name. Swift's own `addingPercentEncoding` would give %20 here, and
        // `removingPercentEncoding` would read Android's card back as "Anna+\u{00D8}"
        // — a silently wrong name rather than an error. This is the form-encoded
        // convention, not RFC 3986; Android is the reference side.
        XCTAssertEqual(
            "station-to-station://friend?name=Anna+%C3%98&k=AAAA",
            ProbeCard(name: "Anna \u{00D8}", publicKey: "AAAA").encode()
        )
        XCTAssertEqual("Anna \u{00D8}",
                       parseProbeCard("station-to-station://friend?name=Anna+%C3%98&k=AAAA")?.name)
    }

    /// A literal `+` in a name must survive as a `+`, which is why decoding
    /// percent-escapes and un-plussing happen in one left-to-right pass rather
    /// than as two string replacements — `%2B` would not survive the naive order.
    func testALiteralPlusInANameSurvives() {
        let plus = ProbeCard(name: "Sunn O))) + Boris", publicKey: "AAAA", setlistfm: "dizzi90")
        XCTAssertEqual(
            "station-to-station://friend?name=Sunn+O%29%29%29+%2B+Boris&k=AAAA&u=dizzi90",
            plus.encode()
        )
        XCTAssertEqual(plus, parseProbeCard(plus.encode()))
    }

    /// A card read at the default MTU comes back as a rising-offset series of
    /// ATT_READ_BLOB requests. This is the server side of that — reject it and a
    /// truncated card looks exactly like a success, which is the bug the old #18
    /// probe had before it handled offset at all.
    func testAChunkedReadAtDefaultMtuStillReturnsTheWholeCard() {
        let payload = card.bytes()
        XCTAssertTrue(payload.count > 20, "test card should need chunking to be a meaningful check")

        let chunkSize = 20 // MTU 23 minus the 3-byte ATT overhead
        var reassembled = Data()
        var reads = 0
        while reassembled.count < payload.count {
            // sliceForOffset hands back everything from offset onward — the server's
            // job is only to answer at the right offset. The radio (simulated here)
            // is what caps one PDU and drives the next read at the next offset.
            let slice = sliceForOffset(payload, reassembled.count)
            reassembled += slice.prefix(chunkSize)
            reads += 1
            XCTAssertTrue(reads <= payload.count, "should not spin forever")
        }
        // One extra blob read past the end, as a real client issues once the offset
        // lands exactly on the payload boundary — must come back empty, not throw.
        XCTAssertEqual(0, sliceForOffset(payload, payload.count).count)
        XCTAssertTrue(reads > 1, "\(reads) reads should need more than one round trip at MTU 23")
        XCTAssertEqual(payload, reassembled)
    }

    func testNameTruncationStaysInsideTheScanResponse() {
        let long = "Bj\u{00F8}rn-Kristian \u{00C6}\u{00F8}\u{00E5}stad-Hemmelighetsfull"
        let cut = truncateToBytes(long)
        XCTAssertTrue(cut.utf8.count <= scanResponseNameBudget)
        XCTAssertTrue(long.hasPrefix(cut))
        XCTAssertEqual("dizzi90", truncateToBytes("dizzi90"))
        // A name that is all emoji must not be cut mid-character.
        let emoji = truncateToBytes(String(repeating: "\u{1F600}", count: 10))
        XCTAssertEqual(emoji, String(decoding: Data(emoji.utf8), as: UTF8.self))
        XCTAssertEqual(6, emoji.count) // 6 x 4 bytes = 24, the 7th would be 28
    }

    /// The mirror of Android's `nameFrom()`: its name arrives as manufacturer data
    /// behind a 2-byte little-endian company id. iOS's own name arrives as a local
    /// name instead, which is the asymmetry neither side can fix.
    func testReadsAndroidsNameOffManufacturerData() {
        var payload = Data([0xFF, 0xFF])
        payload += Data("dizzi90".utf8)
        XCTAssertEqual("dizzi90", nameFromManufacturerData(payload))
        // Someone else's company id is not our name.
        XCTAssertNil(nameFromManufacturerData(Data([0x4C, 0x00]) + Data("nope".utf8)))
        XCTAssertNil(nameFromManufacturerData(Data([0xFF, 0xFF])))
    }

    /// A row you cannot label is worse than no row, and two people who share a
    /// display name must never collapse into one.
    func testMergeDropsNamelessHitsAndKeepsSameNamedStrangersApart() {
        let peers = mergePeers([
            PeerHit(id: "a", name: "Ozzy", rssi: -50, discoveryMs: 100),
            PeerHit(id: "b", name: "Ozzy", rssi: -60, discoveryMs: 200),
            PeerHit(id: "c", name: nil, rssi: -70, discoveryMs: 300),
            PeerHit(id: "d", name: "  ", rssi: -70, discoveryMs: 300),
            PeerHit(id: "a", name: "Ozzy", rssi: -40, discoveryMs: 100),
        ])
        XCTAssertEqual(["a", "b"], peers.map(\.id))
        // A BLE peer is a name until the card is read on tap.
        XCTAssertNil(peers.first?.setlistfm)
    }

    /// The card written back is the same card, through the same parser. A typo in the
    /// UUID is a silent no-discovery that costs a device session to find, so it is
    /// pinned here rather than in a room with two phones.
    func testTheWriteCharacteristicUUIDIsFixedOnBothPlatforms() {
        XCTAssertEqual("7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7713", cardWriteCharacteristicUUIDString)
    }

    func testAWrittenCardBecomesTheSameFriendAsAReadOne() {
        let written = String(decoding: card.bytes(), as: UTF8.self)
        XCTAssertEqual(friendFromCard(card), friendFromCard(parseProbeCard(written)!))
        XCTAssertEqual("dizzi90", friendFromCard(card)?.setlistfm)
    }

    func testAnUnparseableWrittenPayloadYieldsNothing() {
        XCTAssertNil(parseProbeCard("half a card"))
        XCTAssertNil(parseProbeCard(""))
        // …and a card with no username is not a blank friend, it is no friend.
        XCTAssertNil(friendFromCard(ProbeCard(name: "Magnus", publicKey: "AAAA")))
    }

    /// A card longer than one ATT PDU arrives as a rising-offset series of write
    /// requests. The server must place each chunk at its offset; assuming one write
    /// carried the payload is the read path's truncation bug, in reverse.
    func testAChunkedWriteAtDefaultMtuStillReassemblesTheWholeCard() {
        let payload = card.bytes()
        let chunkSize = 20 // MTU 23 minus the 3-byte ATT overhead
        XCTAssertTrue(payload.count > chunkSize, "test card should need chunking to be meaningful")
        var accumulated = Data()
        var offset = 0
        while offset < payload.count {
            let onWire = payload.subdata(in: offset..<min(offset + chunkSize, payload.count))
            accumulated = writeAtOffset(accumulated, offset, onWire)
            offset += onWire.count
        }
        XCTAssertEqual(card, parseProbeCard(String(decoding: accumulated, as: UTF8.self)))
    }

    func testFriendFromCardNeedsASetlistFmUsername() {
        XCTAssertNil(friendFromCard(ProbeCard(name: "Magnus", publicKey: "AAAA")))
        let friend = friendFromCard(card)
        XCTAssertEqual("dizzi90", friend?.setlistfm)
        XCTAssertEqual("Magnus Vikan", friend?.name)
        XCTAssertEqual("dizziness", friend?.spotifyId)
    }
}
