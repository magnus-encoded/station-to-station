import XCTest
@testable import StationToStation

@MainActor
final class HandoverControllerTests: XCTestCase {

    private var model: AppModel!
    private var savedUser: String?

    override func setUp() async throws {
        model = AppModel()
        savedUser = model.settings.mySetlistFmUser
    }

    override func tearDown() async throws {
        model.settings.saveMySetlistFmUser(savedUser ?? "")
        model = nil
    }

    func testStoppingBeforeAnOutcomeSaysWhatArrivedWasKept() {
        model.state.handover = HandoverUi(role: .receiver)

        model.cancelHandover()

        XCTAssertEqual(model.state.handover.error, "The transfer was stopped. What arrived was kept.")
        XCTAssertEqual(model.state.handover.role, .receiver)
    }

    func testStoppingAfterAReceiptLeavesTheReceiptAlone() {
        var receipt = HandoverReceipt()
        receipt.landed = 3
        model.state.handover = HandoverUi(role: .source, receipt: receipt)

        model.cancelHandover()

        XCTAssertNil(model.state.handover.error)
        XCTAssertEqual(model.state.handover.receipt?.landed, 3)
    }

    func testStoppingAfterAnErrorKeepsThatError() {
        model.state.handover = HandoverUi(role: .source, error: "The other phone went away.")

        model.cancelHandover()

        XCTAssertEqual(model.state.handover.error, "The other phone went away.")
    }

    func testLeavingTheScreenClearsTheHandover() {
        model.state.handover = HandoverUi(role: .source, inviteUri: "station-to-station://handover",
                                          receipt: HandoverReceipt(), error: "gone")

        model.dismissHandover()

        XCTAssertNil(model.state.handover.role)
        XCTAssertNil(model.state.handover.inviteUri)
        XCTAssertNil(model.state.handover.receipt)
        XCTAssertNil(model.state.handover.error)
    }

    func testALinkThatIsNotAnInviteStartsNothing() {
        model.joinHandover(URL(string: "https://example.com/not-an-invite")!)

        XCTAssertNil(model.state.handover.role)
    }

    func testArrivingIdentitiesBecomeMySetlistFmUser() async {
        model.state.spotifyConnected = false

        await model.storeHandoverAccounts(identitiesOnly(Identities(setlistFmUser: "wandering-owl")))

        XCTAssertEqual(model.state.mySetlistFmUser, "wandering-owl")
        XCTAssertEqual(model.settings.mySetlistFmUser, "wandering-owl")
        XCTAssertFalse(model.state.spotifyConnected)
    }

    func testAPayloadWithNothingInItChangesNothing() async {
        model.state.mySetlistFmUser = "already-here"
        model.state.spotifyConnected = false

        await model.storeHandoverAccounts(AccountsPayload())

        XCTAssertEqual(model.state.mySetlistFmUser, "already-here")
        XCTAssertFalse(model.state.spotifyConnected)
    }
}
