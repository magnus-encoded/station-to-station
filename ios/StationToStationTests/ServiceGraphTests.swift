import XCTest
@testable import StationToStation

/// The Settings **Field**'s fold (#563): what each service says, and which are **Lit**.
///
/// `fixtures/service-graph/cases.json` is shared with Android's `ServiceGraphTest`, which
/// asserts it case for case; so is the hand-built dark-timeline case below, which no
/// fixture can reach. Everything here is synthetic.
final class ServiceGraphTests: XCTestCase {

    /// The repo root, found from this file rather than a bundle: the fixtures are
    /// deliberately not iOS resources.
    private func fixture(_ path: String) -> URL {
        URL(fileURLWithPath: #filePath)             // …/ios/StationToStationTests/ServiceGraphTests.swift
            .deletingLastPathComponent()            // …/ios/StationToStationTests
            .deletingLastPathComponent()            // …/ios
            .deletingLastPathComponent()            // repo root
            .appendingPathComponent("fixtures/\(path)")
    }

    /// Every field optional: the fixture leaves out whatever is a fresh install's default.
    private struct Known: Decodable {
        var setlistFmKeyAvailable: Bool?
        var setlistFmOwnKey: Bool?
        var setlistFmSharedQuotaSpent: Bool?
        var clashfinderUser: String?
        var clashfinderKey: Bool?
        var spotifyConnected: Bool?
        var spotifyScope: String?
        var knownTimelines: Int?
        var photos: String?
        var calendar: Bool?
        var location: Bool?
        var gigActive: Bool?

        func toKnown() throws -> ServicesAsKnown {
            let photoAccess = try XCTUnwrap(PhotoAccess(rawValue: photos ?? "none"), "photos: \(photos ?? "")")
            return ServicesAsKnown(
                setlistFmKeyAvailable: setlistFmKeyAvailable ?? false,
                setlistFmOwnKey: setlistFmOwnKey ?? false,
                setlistFmSharedQuotaSpent: setlistFmSharedQuotaSpent ?? false,
                clashfinderUser: clashfinderUser ?? "",
                clashfinderKey: clashfinderKey ?? false,
                spotifyConnected: spotifyConnected ?? false,
                spotifyScope: spotifyScope,
                knownTimelines: knownTimelines ?? 0,
                photos: photoAccess,
                calendar: calendar ?? false,
                location: location ?? false,
                gigActive: gigActive ?? false
            )
        }
    }

    private struct NodeExpect: Decodable {
        var lit: Bool
        var status: String
        var nextStep: String?
    }

    private struct Expect: Decodable {
        var timelineLit: Bool
        var alcoveLines: [String: Bool]
        var lit: [String]?
        var nodes: [String: NodeExpect]?
    }

    private struct Case: Decodable {
        var name: String
        var known: Known?
        var expect: Expect
    }

    private struct Cases: Decodable {
        var cases: [Case]
    }

    func testEveryCaseSaysWhatTheFixtureSays() throws {
        let data = try Data(contentsOf: fixture("service-graph/cases.json"))
        let cases = try JSONDecoder().decode(Cases.self, from: data).cases
        XCTAssertFalse(cases.isEmpty, "fixtures/service-graph/cases.json is empty")
        for c in cases {
            let graph = serviceGraph(try (c.known ?? Known()).toKnown())
            let e = c.expect
            XCTAssertEqual(graph.timelineLit, e.timelineLit, "\(c.name): timelineLit")
            for (id, lit) in e.alcoveLines {
                XCTAssertEqual(graph.alcoveLineLit(id), lit, "\(c.name): line to \(id)")
            }
            if let lit = e.lit {
                XCTAssertEqual(Set(graph.nodes.filter { $0.lit }.map { $0.id }), Set(lit), "\(c.name): lit")
            }
            for (id, want) in e.nodes ?? [:] {
                guard let node = graph.node(id) else {
                    XCTFail("\(c.name): no node \(id)")
                    continue
                }
                XCTAssertEqual(node.lit, want.lit, "\(c.name): \(id) lit")
                XCTAssertEqual(node.status, want.status, "\(c.name): \(id) status")
                XCTAssertEqual(node.nextStep, want.nextStep, "\(c.name): \(id) nextStep")
            }
        }
        print("ServiceGraphTests: \(cases.count) fixture cases")
    }

    func testNodesComeInDrawingOrderInTheirStrips() {
        let graph = serviceGraph(ServicesAsKnown())
        XCTAssertEqual(graph.nodes.map { $0.id }, [
            "setlistfm", "musicbrainz", "clashfinder",
            "photos", "tickets", "location",
            "contacts", "gossip",
            "spotify", "calendar",
        ])
        XCTAssertEqual(graph.nodes.map { $0.strip }, [
            .databases, .databases, .databases,
            .thisPhone, .thisPhone, .thisPhone,
            .otherPhones, .otherPhones,
            .services, .thisPhone,
        ])
        XCTAssertEqual(graph.nodes.filter { $0.role == .input }.count, 8)
        XCTAssertEqual(graph.nodes.filter { $0.experimental }.map { $0.id }, ["gossip"])
    }

    func testAScreenReaderHearsTheNameLitOrNotAndTheStatus() {
        let graph = serviceGraph(ServicesAsKnown(setlistFmKeyAvailable: true))
        XCTAssertEqual(graph.node("setlistfm")?.spoken, "setlist.fm, lit, shared key, bundled with the app")
        XCTAssertEqual(graph.node("clashfinder")?.spoken, "clashfinder, not lit, needs a free account")
        XCTAssertEqual(graph.node("spotify")?.spoken, "Spotify, not lit, not logged in. The shared app admits five people")
    }

    func testAnUnlitNodeAlwaysSaysWhatWouldLightItALitOneNeverDoes() {
        let knowns = [
            ServicesAsKnown(),
            ServicesAsKnown(setlistFmKeyAvailable: true, spotifyConnected: true),
        ]
        for known in knowns {
            for node in serviceGraph(known).nodes {
                if node.lit {
                    XCTAssertNil(node.nextStep, node.id)
                } else {
                    XCTAssertNotNil(node.nextStep, node.id)
                }
            }
        }
    }

    func testAnAlcoveLitOnADarkTimelineKeepsItsLineDark() {
        // Unreachable from ServicesAsKnown today (MusicBrainz and Ticket PDFs are always
        // lit), so built by hand: the rule the drawing follows, not today's services.
        func node(_ id: String, _ role: ServiceRole, lit: Bool) -> ServiceNode {
            ServiceNode(id: id, name: id, role: role, strip: .services, lit: lit, status: "", unlocks: [])
        }
        let dark = ServiceGraph(nodes: [
            node("musicbrainz", .input, lit: false),
            node("tickets", .input, lit: false),
            node("spotify", .alcove, lit: true),
            node("calendar", .alcove, lit: false),
        ])
        XCTAssertFalse(dark.timelineLit)
        XCTAssertEqual(dark.node("spotify")?.lit, true)
        XCTAssertFalse(dark.alcoveLineLit("spotify"))
        XCTAssertFalse(dark.alcoveLineLit("calendar"))

        let lit = ServiceGraph(nodes: dark.nodes.map { (n: ServiceNode) -> ServiceNode in
            var copy = n
            if copy.id == "tickets" { copy.lit = true }
            return copy
        })
        XCTAssertTrue(lit.timelineLit)
        XCTAssertTrue(lit.alcoveLineLit("spotify"))
        XCTAssertFalse(lit.alcoveLineLit("calendar"))
        // An input has no line out of the timeline, lit or not.
        XCTAssertFalse(lit.alcoveLineLit("tickets"))
    }
}
