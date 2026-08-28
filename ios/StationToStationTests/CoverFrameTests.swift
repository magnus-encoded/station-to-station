import XCTest
@testable import StationToStation

/// Scrubbing a clip to its cover frame — the one part of it that can be wrong in a
/// way a machine can catch. The picture, the decode and the drag itself need a
/// device; clamping does not.
final class CoverFrameTests: XCTestCase {

    func testTheScrubRunsTopToBottomOfTheClip() {
        // Start of the clip at the top, end at the bottom — the direction the
        // timeline the night itself is read on runs.
        XCTAssertEqual(0, scrubFrameMs(y: 0, trackHeight: 200, durationMs: 60_000))
        XCTAssertEqual(30_000, scrubFrameMs(y: 100, trackHeight: 200, durationMs: 60_000))
        XCTAssertEqual(60_000, scrubFrameMs(y: 200, trackHeight: 200, durationMs: 60_000))
    }

    func testADragOffTheTrackClampsToTheClipsEnds() {
        // A finger leaves the track long before it leaves the screen, and seeking
        // past the end of a clip is how a scrubber returns nothing.
        XCTAssertEqual(0, scrubFrameMs(y: -400, trackHeight: 200, durationMs: 60_000))
        XCTAssertEqual(60_000, scrubFrameMs(y: 900, trackHeight: 200, durationMs: 60_000))
    }

    func testNothingToScrubAcrossIsTheStart() {
        // A duration still being read, or an asset that turned out not to be a clip.
        XCTAssertEqual(0, scrubFrameMs(y: 100, trackHeight: 200, durationMs: 0))
        XCTAssertEqual(0, scrubFrameMs(y: 100, trackHeight: 0, durationMs: 60_000))
    }
}
