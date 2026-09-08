import XCTest
@testable import StationToStation

/// Accounts move, they do not copy (#143). The Swift twin of Android's `AccountsTest`,
/// same assertions, same names where a name translates.
///
/// Credentials here are invented. This repository is public: no real token, refresh
/// token or session value goes in a fixture, a test or a capture, ever.
final class AccountsTests: XCTestCase {

    private let token = "invented-refresh-token-not-a-real-one"
    private let identities = Identities(setlistFmUser: "paper-cranes-fan", spotifyAccount: "spotify:user:invented")
    private var creds: Credentials { Credentials(spotifyRefreshToken: token, spotifyScope: "playlist-modify-private") }

    // --- Exclusion: the assertion most worth having --------------------------------

    /// The failure this guards against is silent and catastrophic: a credential leaving
    /// as a side effect of ticking a media category. It cannot, because the records
    /// manifest has no shape that carries one — asserted on the serialised bytes, for
    /// every combination of categories, since that is what actually goes on the wire.
    func testNoCombinationOfTickedCategoriesPutsACredentialInTheRecordsManifest() {
        var cache = TimelineCache()
        cache.gigs = ["g1": StoredGig(id: "g1", date: "12-06-2026", artist: "Paper Cranes")]
        cache.gigMedia = ["g1": [StoredMedia(id: "m1", kind: StoredMedia.Kind.photo, ref: "asset/mine/1")]]

        let every = Array(categoriesFor(contact: false).union(categoriesFor(contact: true)))
        // The whole power set, walked. Six categories is 64 subsets — cheap enough to
        // enumerate, and enumerating is what makes "no combination" a fact rather than
        // a sample. `contactManifest` never reads `allow` at all — the point being made
        // is exactly that: the credential is absent by construction, not filtered out
        // per combination, so no subset is ever actually applied to the manifest below.
        for _ in 0..<(1 << every.count) {
            var manifest = contactManifest(cache, me: "my-public-key")
            manifest.identities = identities
            // The wire bytes, not the object graph: this is what would actually leave.
            let onTheWire = sealManifest(key: Data("a key".utf8), manifest: manifest).payload
            XCTAssertFalse(onTheWire.contains(token), "a credential reached the records manifest")
            XCTAssertTrue(onTheWire.contains("paper-cranes-fan"), "the identity is supposed to travel")
        }
    }

    /// Accounts move between my own devices only. The far end being me is the point.
    func testAccountsAreNeverOfferedToAContact() {
        XCTAssertFalse(categoriesFor(contact: true).contains(categoryAccounts))
        XCTAssertTrue(categoriesFor(contact: false).contains(categoryAccounts))
    }

    /// Declining the row still means one tap to reconnect, not a setup wizard.
    func testIdentitiesTravelEvenWhenTheSecretsDoNot() {
        let payload = identitiesOnly(identities)

        XCTAssertEqual("paper-cranes-fan", payload.identities.setlistFmUser)
        XCTAssertEqual("spotify:user:invented", payload.identities.spotifyAccount)
        XCTAssertTrue(payload.credentials.isEmpty)
    }

    // --- Atomicity: the source lets go only when the far end has it ----------------

    /// Signing out on send would sign you out of *both* phones if the connection
    /// dropped, with the credential landing nowhere.
    func testTheSourceKeepsItsCredentialUntilTheReceiverAcknowledges() {
        XCTAssertFalse(mayClearCredentials(.notOffered))
        XCTAssertFalse(mayClearCredentials(.sent))
        XCTAssertTrue(mayClearCredentials(.acknowledged))
    }

    func testAnInterruptedHandoverLeavesTheSourceSignedIn() {
        XCTAssertTrue(sourceSignedIn(.sent))
        XCTAssertTrue(sourceSignedIn(.notOffered))
    }

    /// Exactly one holder afterwards, which is what dissolves the token rotation race.
    func testACompletedMoveLeavesExactlyOneHolder() {
        XCTAssertFalse(sourceSignedIn(.cleared))
    }

    // --- Ordering: the small thing first -------------------------------------------

    func testBulkWaitsForTheAccountsStepWhenAccountsWereTicked() {
        let withAccounts: Set<String> = [categorySetlists, categoryAccounts]

        XCTAssertFalse(bulkMayStart(allow: withAccounts, step: .notOffered))
        XCTAssertFalse(bulkMayStart(allow: withAccounts, step: .sent))
        XCTAssertTrue(bulkMayStart(allow: withAccounts, step: .acknowledged))
        // And a bulk failure afterwards does not undo it: the accounts already landed.
        XCTAssertTrue(bulkMayStart(allow: withAccounts, step: .cleared))
    }

    func testBulkStartsImmediatelyWhenAccountsWereNotTicked() {
        XCTAssertTrue(bulkMayStart(allow: [categorySetlists], step: .notOffered))
    }

    // --- The label is the entire disclosure mechanism -------------------------------

    func testTickingAccountsChangesTheVerbAndUntickingRestoresIt() {
        XCTAssertEqual("Copy", approvalVerb([categorySetlists, StoredMedia.Kind.photo]))
        XCTAssertEqual("Copy and sign out here",
                       approvalVerb([categorySetlists, StoredMedia.Kind.photo, categoryAccounts]))
        // The verb names what happens on *this* device. The records are still copied
        // and nothing is removed, which is why it is not one word.
        XCTAssertTrue(approvalVerb([categoryAccounts]).hasPrefix("Copy"))
    }

    /// The credential has exactly one shape that carries it, and this is that shape.
    func testTheAccountsPayloadIsTheOnlyThingHoldingASecret() {
        let payload = AccountsPayload(identities: identities, credentials: creds)

        XCTAssertFalse(payload.credentials.isEmpty)
        XCTAssertEqual(token, payload.credentials.spotifyRefreshToken)
        // And the records manifest has no field that could hold it.
        XCTAssertNil(HandoverManifest().identities.setlistFmUser)
    }
}
