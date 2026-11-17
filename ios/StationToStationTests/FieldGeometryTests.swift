import CoreGraphics
import XCTest
@testable import StationToStation

/// The Settings **Field**'s geometry (#563), asserted the way an eye would check it:
/// nothing crosses what it should not, the lines all arrive, and no pan or pinch loses
/// the **Field** off the screen. Mirrors Android's `FieldGeometryTest`, viewport for
/// viewport.
final class FieldGeometryTests: XCTestCase {

    private let graph = serviceGraph(ServicesAsKnown())
    private lazy var portrait = fieldLayout(graph, viewportW: 411, viewportH: 760)
    private lazy var landscape = fieldLayout(graph, viewportW: 891, viewportH: 330)

    private var inputStrips: [FieldStrip] { portrait.strips.filter { $0.role == .input } }
    private var alcoveStrips: [FieldStrip] { portrait.strips.filter { $0.role == .alcove } }

    /// Strictly inside: a line may run along an edge it leaves from.
    private func holds(_ r: FieldRect, _ p: FieldPoint) -> Bool {
        p.x > r.left && p.x < r.right && p.y > r.top && p.y < r.bottom
    }

    /// Every point along a polyline, `step` apart, so a segment cannot jump a rect.
    private func dense(_ points: [FieldPoint], step: CGFloat = 1) -> [FieldPoint] {
        var out: [FieldPoint] = []
        for i in 0..<(points.count - 1) {
            let a = points[i]
            let b = points[i + 1]
            let dx = b.x - a.x
            let dy = b.y - a.y
            let n = max(1, Int((dx * dx + dy * dy).squareRoot() / step))
            for k in 0..<n {
                let f = CGFloat(k) / CGFloat(n)
                out.append(FieldPoint(x: a.x + (b.x - a.x) * f, y: a.y + (b.y - a.y) * f))
            }
        }
        if let last = points.last { out.append(last) }
        return out
    }

    private func cross(_ o: FieldPoint, _ a: FieldPoint, _ b: FieldPoint) -> CGFloat {
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    }

    /// A proper crossing: each segment's ends strictly either side of the other.
    private func crosses(_ p1: FieldPoint, _ p2: FieldPoint, _ q1: FieldPoint, _ q2: FieldPoint) -> Bool {
        let d1 = cross(q1, q2, p1)
        let d2 = cross(q1, q2, p2)
        let d3 = cross(p1, p2, q1)
        let d4 = cross(p1, p2, q2)
        return d1 * d2 < 0 && d3 * d4 < 0
    }

    private func anyCrossing(_ a: [FieldPoint], _ b: [FieldPoint]) -> Bool {
        for i in 0..<(a.count - 1) {
            for j in 0..<(b.count - 1) where crosses(a[i], a[i + 1], b[j], b[j + 1]) {
                return true
            }
        }
        return false
    }

    private func onScreen(_ r: FieldRect, _ v: FieldView) -> FieldRect {
        FieldRect(
            left: v.offsetX + v.zoom * r.left, top: v.offsetY + v.zoom * r.top,
            right: v.offsetX + v.zoom * r.right, bottom: v.offsetY + v.zoom * r.bottom
        )
    }

    func testInputStripsShareOneRightEdgeAndTheWidestStripsWidth() {
        let strips = inputStrips
        XCTAssertEqual(strips.count, 3)
        XCTAssertEqual(Set(strips.map { $0.rect.right }).count, 1)
        XCTAssertEqual(Set(strips.map { $0.rect.left }).count, 1)
        XCTAssertEqual(strips[0].rect.width, 2 * FieldDp.stripPad + 3 * FieldDp.columnWidth)
        for i in 0..<(strips.count - 1) {
            XCTAssertEqual(strips[i + 1].rect.top - strips[i].rect.bottom, FieldDp.stripGap, accuracy: 0.001)
        }
    }

    func testEveryTileSitsInsideItsStripAndNoTwoOverlap() throws {
        for s in portrait.strips {
            for id in s.ids {
                let t = try XCTUnwrap(portrait.tile(id)).rect
                XCTAssertTrue(t.left >= s.rect.left && t.right <= s.rect.right
                    && t.top >= s.rect.top && t.bottom <= s.rect.bottom, id)
            }
        }
        for a in portrait.tiles {
            for b in portrait.tiles where b.id != a.id {
                let apart = a.rect.right <= b.rect.left || b.rect.right <= a.rect.left
                    || a.rect.bottom <= b.rect.top || b.rect.bottom <= a.rect.top
                XCTAssertTrue(apart, "\(a.id) overlaps \(b.id)")
            }
        }
    }

    func testEveryInputHasALaneAndEveryLaneEndsAtTheJoin() {
        XCTAssertEqual(portrait.lanes.map { $0.id }, graph.nodes.filter { $0.role == .input }.map { $0.id })
        for lane in portrait.lanes {
            XCTAssertEqual(lanePolyline(lane).last, portrait.join, lane.id)
        }
    }

    func testNoLaneRunsThroughAStripOtherThanItsOwnOrThroughAnyTile() throws {
        for lane in portrait.lanes {
            let own = try XCTUnwrap(inputStrips.first { $0.ids.contains(lane.id) })
            let points = dense(lanePolyline(lane, samples: 64))
            for s in portrait.strips where s != own {
                XCTAssertFalse(points.contains { holds(s.rect, $0) }, "\(lane.id) enters \(s.strip)")
            }
            for t in portrait.tiles {
                XCTAssertFalse(points.contains { holds(t.rect, $0) }, "\(lane.id) crosses tile \(t.id)")
            }
            XCTAssertFalse(points.contains { holds(portrait.timeline, $0) }, "\(lane.id) crosses the timeline")
        }
    }

    func testNoTwoLanesCrossBeforeTheyMeetAtTheJoin() {
        // All of them meet at the join, so the final approach is left out.
        var lines: [String: [FieldPoint]] = [:]
        for lane in portrait.lanes { lines[lane.id] = Array(lanePolyline(lane, samples: 64).dropLast(4)) }
        for (a, la) in lines {
            for (b, lb) in lines where b > a {
                XCTAssertFalse(anyCrossing(la, lb), "\(a) crosses \(b)")
            }
        }
    }

    func testTheLeftmostTileTakesTheLowestLane() {
        for s in inputStrips {
            let laneYs: [CGFloat] = s.ids.compactMap { id in portrait.lanes.first { $0.id == id }?.corners.last?.y }
            XCTAssertEqual(laneYs.count, s.ids.count)
            XCTAssertEqual(laneYs, laneYs.sorted(by: >))
            XCTAssertTrue(laneYs.allSatisfy { $0 < s.rect.bottom })
            // Below the names under the tiles.
            let floor = s.rect.top + FieldDp.stripHeader + FieldDp.tileSize + FieldDp.labelRoom
            XCTAssertTrue(laneYs.allSatisfy { $0 >= floor })
        }
    }

    func testTheTrunkIsStraightThroughTheTimeline() {
        XCTAssertEqual(portrait.join.y, portrait.split.y)
        XCTAssertEqual(portrait.join.y, portrait.timeline.centerY, accuracy: 0.001)
        XCTAssertEqual(portrait.inputBlock.centerY, portrait.join.y, accuracy: 0.001)
        XCTAssertTrue(portrait.join.x < portrait.timeline.left && portrait.split.x > portrait.timeline.right)
        XCTAssertTrue(alcoveStrips.allSatisfy { $0.rect.left > portrait.split.x })
    }

    func testAlcoveLinesCrossNeitherEachOtherNorAnotherStrip() throws {
        XCTAssertEqual(portrait.alcoveLines.map { $0.id }, ["spotify", "calendar"])
        var lines: [String: [FieldPoint]] = [:]
        for line in portrait.alcoveLines { lines[line.id] = curvePoints(line.from, line.to, samples: 64) }
        for line in portrait.alcoveLines {
            let tile = try XCTUnwrap(portrait.tile(line.id))
            XCTAssertEqual(line.to.x, tile.rect.left)
            let own = try XCTUnwrap(alcoveStrips.first { $0.ids.contains(line.id) })
            let points = dense(lines[line.id] ?? [])
            for s in portrait.strips where s != own {
                XCTAssertFalse(points.contains { holds(s.rect, $0) }, "\(line.id) enters \(s.strip)")
            }
            XCTAssertFalse(points.contains { holds(portrait.timeline, $0) })
        }
        // They leave the split together, so the first stretch is left out.
        let spotify = Array((lines["spotify"] ?? []).dropFirst(4))
        let calendar = Array((lines["calendar"] ?? []).dropFirst(4))
        XCTAssertFalse(anyCrossing(spotify, calendar))
    }

    func testTheFieldHoldsEverythingWithAMargin() {
        let right = portrait.strips.map { $0.rect.right }.max() ?? 0
        let bottom = max(portrait.strips.map { $0.rect.bottom }.max() ?? 0, portrait.timeline.bottom)
        XCTAssertEqual(portrait.width, right + FieldDp.margin, accuracy: 0.001)
        XCTAssertEqual(portrait.height, bottom + FieldDp.margin, accuracy: 0.001)
        XCTAssertTrue(portrait.timeline.top >= 0)
    }

    func testTheDefaultViewFitsTheInputsInPortraitAndInLandscape() {
        for layout in [portrait, landscape] {
            let v = layout.defaultView
            let block = onScreen(layout.inputBlock, v)
            let at = "\(layout.viewportW)x\(layout.viewportH)"
            XCTAssertTrue(block.left >= 0 && block.right <= layout.viewportW, "\(at): \(block)")
            XCTAssertTrue(block.top >= 0 && block.bottom <= layout.viewportH, "\(at): \(block)")
            XCTAssertEqual(block.centerY, layout.viewportH / 2, accuracy: 0.01, at)
            XCTAssertEqual(layout.clamp(v), v, at)
            XCTAssertTrue(layout.atLeftEdge(v), at)
        }
    }

    func testTheDefaultViewStartsAtTheLeftEdgeOrCentredWhenTheFieldIsNarrower() {
        let p = portrait.defaultView
        XCTAssertTrue(portrait.width * p.zoom > portrait.viewportW)
        XCTAssertEqual(p.offsetX, 0)

        let l = landscape.defaultView
        XCTAssertTrue(landscape.width * l.zoom < landscape.viewportW)
        XCTAssertEqual(l.offsetX, (landscape.viewportW - landscape.width * l.zoom) / 2, accuracy: 0.001)
    }

    func testTheBackOutEdgeIsAsFarRightAsTheFieldCanBePanned() {
        for layout in [portrait, landscape] {
            for z in [FieldDp.minZoom, 0.8, 1.3, FieldDp.maxZoom] {
                let v = layout.clamp(FieldView(zoom: z, offsetX: 1e6, offsetY: 0))
                XCTAssertEqual(v.offsetX, layout.leftEdgeOffsetX(z))
                XCTAssertTrue(layout.atLeftEdge(v))
                XCTAssertFalse(layout.atLeftEdge(layout.pan(v, dx: -40, dy: 0)))
            }
        }
    }

    func testNoPanOrPinchLeavesLessThanKeepOfTheFieldOnScreen() {
        let extremes: [CGFloat] = [-1e6, -5000, -300, 0, 300, 5000, 1e6]
        let zooms: [CGFloat] = [0.01, FieldDp.minZoom, 1, FieldDp.maxZoom, 100]
        for layout in [portrait, landscape] {
            for z in zooms {
                for ox in extremes {
                    for oy in extremes {
                        let v = layout.clamp(FieldView(zoom: z, offsetX: ox, offsetY: oy))
                        XCTAssertTrue(v.zoom >= FieldDp.minZoom && v.zoom <= FieldDp.maxZoom)
                        let f = onScreen(FieldRect(left: 0, top: 0, right: layout.width, bottom: layout.height), v)
                        let seenW = min(f.right, layout.viewportW) - max(f.left, 0)
                        let seenH = min(f.bottom, layout.viewportH) - max(f.top, 0)
                        XCTAssertTrue(seenW >= min(FieldDp.keep, f.width) - 0.01, "\(v): \(seenW) wide")
                        XCTAssertTrue(seenH >= min(FieldDp.keep, f.height) - 0.01, "\(v): \(seenH) high")
                    }
                }
            }
        }
    }

    func testAPinchKeepsThePointUnderTheFingersWhereItIs() {
        let v = portrait.pan(portrait.defaultView, dx: -100, dy: 0)
        let cx: CGFloat = 200
        let cy: CGFloat = 300
        let fieldX = (cx - v.offsetX) / v.zoom
        let fieldY = (cy - v.offsetY) / v.zoom
        let z = portrait.zoomAbout(v, cx: cx, cy: cy, factor: 1.4)
        XCTAssertEqual(z.zoom, v.zoom * 1.4, accuracy: 0.0001)
        XCTAssertEqual(z.offsetX + z.zoom * fieldX, cx, accuracy: 0.01)
        XCTAssertEqual(z.offsetY + z.zoom * fieldY, cy, accuracy: 0.01)
        // And never past the limits, however hard.
        XCTAssertEqual(portrait.zoomAbout(v, cx: cx, cy: cy, factor: 50).zoom, FieldDp.maxZoom)
        XCTAssertEqual(portrait.zoomAbout(v, cx: cx, cy: cy, factor: 0.001).zoom, FieldDp.minZoom)
    }

    func testTheCurveLeavesAndArrivesLevel() {
        let pts = curvePoints(FieldPoint(x: 0, y: 0), FieldPoint(x: 100, y: 50), samples: 16)
        XCTAssertEqual(pts.count, 17)
        XCTAssertEqual(pts.first, FieldPoint(x: 0, y: 0))
        XCTAssertEqual(pts.last, FieldPoint(x: 100, y: 50))
        for i in 0..<(pts.count - 1) {
            XCTAssertTrue(pts[i + 1].x >= pts[i].x && pts[i + 1].y >= pts[i].y)
        }
    }
}
