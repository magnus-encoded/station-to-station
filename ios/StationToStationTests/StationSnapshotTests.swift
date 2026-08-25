import XCTest
import SwiftUI
@testable import StationToStation

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
        URL(fileURLWithPath: #filePath)   // …/ios/StationToStationTests/StationSnapshotTests.swift
            .deletingLastPathComponent()  // …/ios/StationToStationTests
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

        let mine = cache.shows[me] ?? []
        // Open every festival so the member-gig indentation is in the picture too.
        let collapsed = weaveTimelines(mine: mine, festivals: cache.festivalIdentities())
        let expanded = Set(collapsed.filter { $0.node.isSeveral }.map(\.key))
        let rows = weaveTimelines(
            mine: mine, festivals: cache.festivalIdentities(), expanded: expanded
        )

        // Render the rows directly (my own line, laneWidth 0). ImageRenderer can't
        // draw a ScrollView/LazyVStack, but a plain column of StationRow reproduces
        // the same geometry: continuity between rows, node position, indentation.
        let content = VStack(spacing: 0) {
            ForEach(Array(rows.enumerated()), id: \.element.key) { i, row in
                StationRow(
                    row: row,
                    next: rows.indices.contains(i + 1) ? rows[i + 1] : nil,
                    lanes: [],
                    laneWidth: 0,
                    highlight: i == 0,
                    onTap: {}
                )
            }
        }
        .frame(width: 393)
        .background(Color(red: 0x0E / 255, green: 0x0B / 255, blue: 0x14 / 255))
        .environment(\.colorScheme, .dark)

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
