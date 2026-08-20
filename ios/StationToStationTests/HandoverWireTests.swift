import XCTest
@testable import StationToStation

/// The two things a handover's transport decides on its own (#142): whether a manifest
/// arrived as it left, and whether a link someone pointed a camera at is an invite at all.
///
/// The sockets are not here — `Network.framework` needs a device and a network, and iOS's
/// precedent (`ContactCertTests`) is to check the parts that can be checked without one.
/// The seal is the higher-stakes half anyway: TLS being wrong fails loudly, a manifest
/// being wrong fails silently.
final class HandoverWireTests: XCTestCase {

    private let key = Data("the key the code carried".utf8)

    private func manifest() -> HandoverManifest {
        var cache = TimelineCache()
        cache.gigs = ["g1": StoredGig(id: "g1", date: "12-06-2026", artist: "Paper Cranes",
                                      venue: "The Long Room", setlistId: "sl-1")]
        cache.gigMedia = ["g1": [
            StoredMedia(id: "p1", kind: StoredMedia.Kind.photo, ref: "asset/mine/p1"),
            StoredMedia(id: "v1", kind: StoredMedia.Kind.video, ref: "asset/mine/v1", personal: true),
        ]]
        return deviceManifest(cache, allow: categoriesFor(contact: false),
                              identities: Identities(setlistFmUser: "wandering-owl"))
    }

    func testASealedManifestOpensWithTheSameKey() {
        let sealed = sealManifest(key: key, manifest: manifest())

        let opened = openManifest(key: key, sealed: sealed)

        XCTAssertEqual(opened?.media.map(\.id).sorted(), ["p1", "v1"])
        XCTAssertEqual(opened?.identities.setlistFmUser, "wandering-owl")
        XCTAssertEqual(opened?.timeline.gigs["g1"]?.artist, "Paper Cranes")
    }

    /// The counts are computed *inside* the seal, so "it said 48 and 48 arrived" means
    /// something: the number cannot be truncated alongside the list it describes.
    func testTheCountsRideInsideTheTag() {
        let sealed = sealManifest(key: key, manifest: manifest())

        let opened = openManifest(key: key, sealed: sealed)

        XCTAssertEqual(opened?.counts[StoredMedia.Kind.photo], 1)
        XCTAssertEqual(opened?.counts[categoryOf(kind: StoredMedia.Kind.video, personal: true)], 1)
    }

    /// The highest-stakes single bit in the payload: flipping `personal` exposes something
    /// withheld with nothing in the UI to say so. Altered payload, unaltered tag — nil.
    func testAnAlteredPayloadDoesNotOpen() {
        var sealed = sealManifest(key: key, manifest: manifest())
        sealed.payload = sealed.payload.replacingOccurrences(of: "\"personal\":true",
                                                             with: "\"personal\":false")

        XCTAssertNil(openManifest(key: key, sealed: sealed))
    }

    func testTheWrongKeyDoesNotOpen() {
        let sealed = sealManifest(key: key, manifest: manifest())

        XCTAssertNil(openManifest(key: Data("some other phone's code".utf8), sealed: sealed))
    }

    /// A tag that will not decode is a tag that does not match, never an error — and an
    /// algorithm we do not implement is refused rather than assumed.
    func testJunkTagsAndUnknownAlgorithmsAreRefusedQuietly() {
        var sealed = sealManifest(key: key, manifest: manifest())
        let honest = sealed.mac
        sealed.mac = "not base64 at all!!"
        XCTAssertNil(openManifest(key: key, sealed: sealed))

        sealed.mac = honest
        sealed.alg = "HMAC-SHA1"
        XCTAssertNil(openManifest(key: key, sealed: sealed))
    }

    // MARK: - The invite

    func testTheInviteSurvivesARoundTrip() {
        let invite = HandoverInvite(host: "192.168.1.23", port: 41234,
                                    fingerprint: Data((0..<32).map { UInt8($0) }),
                                    linkKey: Data(repeating: 7, count: 32))

        XCTAssertEqual(parseHandoverInvite(invite.uri), invite)
    }

    /// A friend card, a link typed by hand, a truncated scan: the same cases Android's
    /// parser refuses, because the two have to agree on what a code *is*.
    func testJunkIsRefused() {
        XCTAssertNil(parseHandoverInvite("station-to-station://friend?u=someone"))
        XCTAssertNil(parseHandoverInvite("station-to-station://handover?h=1.2.3.4&p=0&f=aa&k=bb"))
        XCTAssertNil(parseHandoverInvite("station-to-station://handover?h=1.2.3.4&p=41234&f=aa"))
        XCTAssertNil(parseHandoverInvite("station-to-station://handover?h=1.2.3.4&p=41234&f=xyz&k=bb"))
        XCTAssertNil(parseHandoverInvite("station-to-station://handover?h=1.2.3.4&p=41234&f=abc&k=bb"))
    }

    /// The fingerprint compare behind the pinning verify block. Same bytes, same answer;
    /// one byte different, or one byte short, and it is a different certificate.
    func testTheFingerprintCompareIsExact() {
        let a = Data((0..<32).map { UInt8($0) })
        XCTAssertTrue(constantTimeEquals(a, Data((0..<32).map { UInt8($0) })))
        XCTAssertFalse(constantTimeEquals(a, Data((0..<32).map { UInt8($0 == 31 ? 0 : $0) })))
        XCTAssertFalse(constantTimeEquals(a, a.dropLast()))
    }
}
