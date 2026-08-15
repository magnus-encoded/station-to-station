import XCTest
@testable import StationToStation

/// The contact's-eye view (#180), ported from Android's `ContactViewTest`.
///
/// #180's "done when" is that the withheld count matches Android's for the same night,
/// so these are twins on purpose: the same media, the same two answers. Every name is
/// invented.
final class ContactViewTests: XCTestCase {

    private func mine(_ id: String, personal: Bool = false) -> StoredMedia {
        StoredMedia(id: id, kind: StoredMedia.Kind.photo, ref: "ref-\(id)", personal: personal)
    }

    private func received(_ id: String, from: String, personal: Bool = false) -> StoredMedia {
        StoredMedia(id: id, kind: StoredMedia.Kind.photo, ref: "ref-\(id)",
                    from: from, personal: personal)
    }

    func testTheSharedBandIsWhatAContactIsOffered() {
        let media = [mine("a"), mine("b", personal: true), mine("c")]

        XCTAssertEqual(["a", "c"], visibleToContacts(media).map(\.id))
    }

    func testTheVaultIsWhatIAmHoldingBack() {
        let media = [mine("a"), mine("b", personal: true), mine("c")]

        XCTAssertEqual(["b"], withheldFromContacts(media).map(\.id))
    }

    /// The decision that is easiest to mistake for an oversight: passing a contact's
    /// photograph to my other contacts would be publishing on their behalf.
    func testReceivedMediaIsNeverReShared() {
        let media = [mine("a"), received("theirs", from: "ozzy")]

        XCTAssertEqual(["a"], visibleToContacts(media).map(\.id))
    }

    /// And it is not mine to withhold either — it is absent from *both* answers, which
    /// is the part a re-derived filter in a view would get wrong.
    func testReceivedMediaIsNotMineToWithholdEither() {
        let media = [mine("a", personal: true), received("theirs", from: "ozzy", personal: true)]

        XCTAssertEqual(["a"], withheldFromContacts(media).map(\.id))
    }

    /// Together the two halves account for exactly my own media, once each. A night
    /// cannot have an item that is neither exposed nor withheld.
    func testTheTwoHalvesPartitionMyOwnMedia() {
        let media = [mine("a"), mine("b", personal: true), mine("c"),
                     received("theirs", from: "lemmy")]

        let seen = visibleToContacts(media).map(\.id)
        let held = withheldFromContacts(media).map(\.id)

        XCTAssertEqual(["a", "b", "c"], (seen + held).sorted())
        XCTAssertTrue(Set(seen).isDisjoint(with: Set(held)))
    }

    /// A night sharing nothing stays, empty — a night that vanished would answer a
    /// question nobody asked.
    func testANightSharingNothingStaysAsAnEmptyNight() {
        let byNight = ["g1": [mine("a", personal: true)], "g2": [mine("b")]]

        let seen = contactMedia(byNight)

        XCTAssertEqual(["g1", "g2"], seen.keys.sorted())
        XCTAssertEqual([], seen["g1"]?.map(\.id))
        XCTAssertEqual(["b"], seen["g2"]?.map(\.id))
    }

    func testAnEmptyNightIsNotAnError() {
        XCTAssertEqual([], visibleToContacts([]).map(\.id))
        XCTAssertEqual([], withheldFromContacts([]).map(\.id))
    }
}
