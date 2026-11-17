import XCTest
@testable import StationToStation

@MainActor
final class FakeStateTests: XCTestCase {

    private struct Boom: Error {}

    func testAWriteThroughTheHostIsTheStateTheTestReads() {
        let fake = FakeState()
        let host: StateHost = fake

        host.state.artistQuery = "Kvelertak"
        host.fail(Boom())

        XCTAssertEqual(fake.state.artistQuery, "Kvelertak")
        XCTAssertEqual(fake.failures.count, 1)
        XCTAssertTrue(fake.failures.first is Boom)
    }
}
