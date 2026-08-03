import XCTest
import SwiftUI
@testable import SetlistToSpotify

/// A rendered snapshot of the Timeline, uploaded by CI as an artifact. The
/// timeline is pure geometry (a continuous Spine, Nodes on it, festivals and
/// their member gigs) and the only way to check that geometry without a device
/// has been to sideload and squint. `ImageRenderer` draws the real SwiftUI view
/// off a seeded fixture, so a build carries a picture of what it would look like.
///
/// Not an assertion — it never fails the build; it just leaves a PNG. Seeds
/// `state` directly rather than `loadFixture` (that reads `Bundle.main`, absent
/// in the test host) and relies on `ImageRenderer` not firing `onAppear`, so the
/// seeded spine is what gets drawn.
@MainActor
final class StationSnapshotTests: XCTestCase {

    private var repoRoot: URL {
        URL(fileURLWithPath: #filePath)   // …/ios/SetlistToSpotifyTests/StationSnapshotTests.swift
            .deletingLastPathComponent()  // …/ios/SetlistToSpotifyTests
            .deletingLastPathComponent()  // …/ios
            .deletingLastPathComponent()  // repo root
    }

    private struct Me: Decodable { let me: String }

    func testRenderTimelineForReview() throws {
        // three-lines-tons-of-rock has a standalone gig (Ghost) and a festival
        // cluster (Ekebergsletta), the exact mix that reads wrong on-device.
        let fixture = repoRoot.appendingPathComponent("fixtures/weave/three-lines-tons-of-rock/timelines.json")
        let data = try Data(contentsOf: fixture)
        let cache = try JSONDecoder().decode(TimelineCache.self, from: data)
        let me = try JSONDecoder().decode(Me.self, from: data).me

        let model = AppModel()
        model.state.mySetlistFmUser = me
        model.state.timelineShows = cache.shows[me] ?? []
        model.state.festivalNames = cache.festivalNames
        model.state.zoomedOut = false  // my own line — the reported resolution
        // Open every festival so the member-gig indentation is in the picture too.
        let rows = weaveTimelines(mine: model.state.timelineShows, festivalNames: cache.festivalNames)
        model.state.expandedFestivals = Set(rows.filter { $0.node.isFestival }.map(\.key))

        let content = StationView()
            .environmentObject(model)
            .environmentObject(Nav())
            .frame(width: 393, height: 1400)

        let renderer = ImageRenderer(content: content)
        renderer.scale = 2
        // Instrumentation, not an assertion: a render that can't produce an image
        // must never block the IPA. Skip, don't fail.
        guard let png = renderer.uiImage?.pngData() else {
            throw XCTSkip("ImageRenderer produced no image")
        }
        let outDir = repoRoot.appendingPathComponent("ios/snapshot-out")
        try FileManager.default.createDirectory(at: outDir, withIntermediateDirectories: true)
        let out = outDir.appendingPathComponent("timeline.png")
        try png.write(to: out)
        print("SNAPSHOT_PATH=\(out.path)")
    }
}
