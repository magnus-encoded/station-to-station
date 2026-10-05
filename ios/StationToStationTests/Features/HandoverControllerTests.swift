import XCTest
@testable import StationToStation

@MainActor
final class HandoverControllerTests: XCTestCase {

    private var fake: FakeState!
    private var settings: Settings!
    private var handover: HandoverController!
    private var savedUser: String?

    override func setUp() async throws {
        fake = FakeState()
        settings = Settings()
        handover = HandoverController(host: fake, settings: settings, timelines: TimelineStore(),
                                      spotify: SpotifyClient(settings), loadTimeline: {})
        savedUser = settings.mySetlistFmUser
    }

    override func tearDown() async throws {
        settings.saveMySetlistFmUser(savedUser ?? "")
        handover = nil
        settings = nil
        fake = nil
    }

    func testStoppingBeforeAnOutcomeSaysWhatArrivedWasKept() {
        fake.state.handover = HandoverUi(role: .receiver)

        handover.cancelHandover()

        XCTAssertEqual(fake.state.handover.error, "The transfer was stopped. What arrived was kept.")
        XCTAssertEqual(fake.state.handover.role, .receiver)
    }

    func testStoppingAfterAReceiptLeavesTheReceiptAlone() {
        var receipt = HandoverReceipt()
        receipt.landed = 3
        fake.state.handover = HandoverUi(role: .source, receipt: receipt)

        handover.cancelHandover()

        XCTAssertNil(fake.state.handover.error)
        XCTAssertEqual(fake.state.handover.receipt?.landed, 3)
    }

    func testStoppingAfterAnErrorKeepsThatError() {
        fake.state.handover = HandoverUi(role: .source, error: "The other phone went away.")

        handover.cancelHandover()

        XCTAssertEqual(fake.state.handover.error, "The other phone went away.")
    }

    func testLeavingTheScreenClearsTheHandover() {
        fake.state.handover = HandoverUi(role: .source, inviteUri: "station-to-station://handover",
                                          receipt: HandoverReceipt(), error: "gone")

        handover.dismissHandover()

        XCTAssertNil(fake.state.handover.role)
        XCTAssertNil(fake.state.handover.inviteUri)
        XCTAssertNil(fake.state.handover.receipt)
        XCTAssertNil(fake.state.handover.error)
    }

    func testALinkThatIsNotAnInviteStartsNothing() {
        handover.joinHandover(URL(string: "https://example.com/not-an-invite")!)

        XCTAssertNil(fake.state.handover.role)
    }

    func testArrivingIdentitiesBecomeMySetlistFmUser() async {
        fake.state.spotifyConnected = false

        await handover.storeHandoverAccounts(identitiesOnly(Identities(setlistFmUser: "wandering-owl")))

        XCTAssertEqual(fake.state.mySetlistFmUser, "wandering-owl")
        XCTAssertEqual(settings.mySetlistFmUser, "wandering-owl")
        XCTAssertFalse(fake.state.spotifyConnected)
    }

    func testAPayloadWithNothingInItChangesNothing() async {
        fake.state.mySetlistFmUser = "already-here"
        fake.state.spotifyConnected = false

        await handover.storeHandoverAccounts(AccountsPayload())

        XCTAssertEqual(fake.state.mySetlistFmUser, "already-here")
        XCTAssertFalse(fake.state.spotifyConnected)
    }
}
