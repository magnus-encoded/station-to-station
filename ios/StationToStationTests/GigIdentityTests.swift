import XCTest
import Security
@testable import StationToStation

final class GigIdentityTests: XCTestCase {
    func testPersistedGigKeyExportsSPKIAndSignsDEREnvelopes() throws {
        let scopes = (0..<2).map { _ in "instrumented-\(UUID().uuidString)" }
        #if targetEnvironment(simulator)
        let probe: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: "station-to-station.gossip-test-probe",
                                    kSecAttrAccount as String: scopes[0],
                                    kSecValueData as String: Data([1])]
        let status = SecItemAdd(probe as CFDictionary, nil)
        if status == errSecMissingEntitlement {
            throw XCTSkip("Simulator test host has no Keychain entitlement (-34018)")
        }
        XCTAssertEqual(status, errSecSuccess)
        SecItemDelete(probe as CFDictionary)
        #endif
        defer {
            for scope in scopes {
                SecItemDelete([kSecClass as String: kSecClassGenericPassword,
                               kSecAttrService as String: "station-to-station.gossip-gig",
                               kSecAttrAccount as String: scope] as CFDictionary)
            }
        }
        let author = try XCTUnwrap(GigIdentity.publicKeyBase64(scope: scopes[0]))
        XCTAssertEqual(author, GigIdentity.publicKeyBase64(scope: scopes[0]))
        XCTAssertNotEqual(author, try XCTUnwrap(GigIdentity.publicKeyBase64(scope: scopes[1])))
        let draft = GossipEnvelope(gigId: "device-test", scope: scopes[0], author: author,
                                   createdAt: 1000, expiresAt: 100000, kind: "log", line: 3,
                                   text: "Karma Police")
        var fact = try XCTUnwrap(draft.signed { GigIdentity.sign(scope: scopes[0], $0) })
        XCTAssertTrue(fact.valid())
        fact.text = "changed"
        XCTAssertFalse(fact.valid())
    }
}
