import XCTest
@testable import StationToStation

final class GossipPlacementTests: XCTestCase {
    private func cases() throws -> [[String: Any]] {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("fixtures/gossip-placement/cases.json")
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [[String: Any]])
    }

    func testEverySharedCasePlacesReceivedLinesWhereTheCorpusSays() throws {
        let all = try cases()
        XCTAssertFalse(all.isEmpty)
        for item in all {
            let name = item["name"] as? String ?? ""
            let mine = (item["mine"] as! [Any]).map { $0 is NSNull ? nil : ($0 as! String) }
            var facts: [GossipEnvelope] = []
            for (author, lines) in item["theirs"] as! [String: [String: Any]] {
                for (line, value) in lines {
                    let versions = (value as? [Any]) ?? [value]
                    for version in versions {
                        let object = version as? [String: Any]
                        let text = object.map { $0["text"] as! String } ?? (version as! String)
                        let createdAt = object.map { ($0["createdAt"] as! NSNumber).int64Value } ?? 1000
                        facts.append(GossipEnvelope(gigId: "gig", scope: "scope", author: author, createdAt: createdAt,
                                                    expiresAt: 100000, kind: "log", line: Int(line)!, text: text))
                    }
                }
            }
            let actual = weaveGossip(base: mine, facts: facts.shuffled())
            let expected = item["expected"] as! [[String: Any]]
            XCTAssertEqual(expected.map { $0["base"] as? Int }, actual.map(\.base), name)
            XCTAssertEqual(expected.map { ($0["lines"] as! [String]).sorted() },
                           actual.map { $0.facts.map { "\($0.author):\($0.line)" }.sorted() }, name)
        }
    }
}
