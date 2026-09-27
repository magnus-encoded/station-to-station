import CoreGraphics
import Foundation

/// Where everything on the Settings **Field** goes, as numbers (#563).
///
/// The **Field** is a picture you move around in, and a picture is exactly the thing that
/// cannot be checked by looking once: a line that crosses a tile on one phone is fine on
/// another, and panning that loses the whole **Field** off the edge only happens at the
/// zoom nobody tried. So the drawing and the gestures both read `fieldLayout` and never
/// work out a position of their own, and `FieldGeometryTests` asserts what the eye would.
///
/// Everything here is in points, with its own point and rect types rather than SwiftUI's
/// or CoreGraphics' rects, so it carries over term for term from Android's
/// `FieldGeometry.kt` (where the unit is the dp, the same size in practice — see
/// `RowGeometry.swift`). Only `CGFloat` is borrowed, for arithmetic.
///
/// Read left to right it is **Outer** to **Inner**, like everywhere else: the inputs,
/// one strip per kind, run their lines into a join; **My timeline** sits on the trunk;
/// past it the lines split out to the **Alcoves**.
enum FieldDp {
    static let margin: CGFloat = 24
    static let tileSize: CGFloat = 56
    static let columnWidth: CGFloat = 92
    static let stripPad: CGFloat = 8
    /// The strip's title row, above its tiles.
    static let stripHeader: CGFloat = 30
    /// The name under a tile, with the 4 between them.
    static let labelRoom: CGFloat = 18
    static let laneGap: CGFloat = 6
    static let laneBottomPad: CGFloat = 10
    static let stripGap: CGFloat = 28
    static let cornerRadius: CGFloat = 8
    /// How far right of its tile a line turns down into its lane.
    static let dropOffset: CGFloat = 10
    /// From the inputs' shared right edge to where their lines meet.
    static let joinRun: CGFloat = 62
    /// Join to the timeline box, and the box to the split.
    static let hubGap: CGFloat = 22
    static let hubW: CGFloat = 72
    static let hubH: CGFloat = 210
    /// From the split to the alcove strips.
    static let alcoveRun: CGFloat = 54
    static let fitMargin: CGFloat = 16
    static let minZoom: CGFloat = 0.35
    static let maxZoom: CGFloat = 2.5
    /// Screen points of the **Field** that panning always leaves in view.
    static let keep: CGFloat = 48
    /// A rightward drag from the left edge past this goes back — `swipeRight`'s own
    /// threshold, where Android uses its `swipeRightToBack`'s 110.
    static let backOutThreshold: CGFloat = 90
}

struct FieldPoint: Equatable {
    let x: CGFloat
    let y: CGFloat
}

struct FieldRect: Equatable {
    let left: CGFloat
    let top: CGFloat
    let right: CGFloat
    let bottom: CGFloat

    var width: CGFloat { right - left }
    var height: CGFloat { bottom - top }
    var centerX: CGFloat { (left + right) / 2 }
    var centerY: CGFloat { (top + bottom) / 2 }

    func contains(_ p: FieldPoint) -> Bool {
        p.x >= left && p.x <= right && p.y >= top && p.y <= bottom
    }
}

/// One strip of tiles. Its `ids` run left to right in the order the graph lists them.
struct FieldStrip: Equatable {
    let strip: ServiceStrip
    let role: ServiceRole
    let rect: FieldRect
    let ids: [String]
}

struct FieldTile: Equatable {
    let id: String
    let center: FieldPoint
    let rect: FieldRect
}

/// An input's line: out of its tile, down into a lane of its own under the tiles, along
/// to the strip's right edge, then a curve to the join.
///
/// The leftmost tile takes the *lowest* lane. A line only ever runs right, under the
/// tiles to its right, whose lines turn down short of it into lanes above it — so no
/// line crosses another or a tile, however many a strip holds.
struct FieldLane: Equatable {
    let id: String
    /// The corners, before the curve. Drawn with `FieldDp.cornerRadius` at each bend.
    let corners: [FieldPoint]
    let join: FieldPoint
}

/// A line out to an **Alcove**: the same curve, from the split to the tile's left edge.
struct FieldAlcoveLine: Equatable {
    let id: String
    let from: FieldPoint
    let to: FieldPoint
}

/// How the **Field** sits on screen: `screen = offset + zoom * field`.
struct FieldView: Equatable {
    var zoom: CGFloat
    var offsetX: CGFloat
    var offsetY: CGFloat
}

private func coerce(_ v: CGFloat, _ lo: CGFloat, _ hi: CGFloat) -> CGFloat {
    min(max(v, lo), hi)
}

struct FieldLayout: Equatable {
    let strips: [FieldStrip]
    let tiles: [FieldTile]
    let lanes: [FieldLane]
    let alcoveLines: [FieldAlcoveLine]
    let join: FieldPoint
    let split: FieldPoint
    let timeline: FieldRect
    /// The input strips together: what the default view is fitted to.
    let inputBlock: FieldRect
    let width: CGFloat
    let height: CGFloat
    let viewportW: CGFloat
    let viewportH: CGFloat

    func tile(_ id: String) -> FieldTile? { tiles.first { $0.id == id } }

    /// The inputs filling the height, with room around them, and never wider than the
    /// screen: in portrait that is the width that binds. Then left-aligned, so the
    /// **Field** opens on its **Outer** edge, or centred when all of it fits.
    var defaultView: FieldView {
        let zoom = coerce(
            min(
                viewportH / (inputBlock.height + 2 * FieldDp.fitMargin),
                viewportW / (inputBlock.right + FieldDp.fitMargin)
            ),
            FieldDp.minZoom, FieldDp.maxZoom
        )
        return FieldView(
            zoom: zoom,
            offsetX: leftEdgeOffsetX(zoom),
            offsetY: viewportH / 2 - zoom * inputBlock.centerY
        )
    }

    /// The furthest right the **Field** can sit at `zoom`: its left edge at the screen's,
    /// or centred when it is narrower than the screen. Being here is what lets a
    /// rightward drag mean back rather than pan.
    func leftEdgeOffsetX(_ zoom: CGFloat) -> CGFloat { max(0, (viewportW - width * zoom) / 2) }

    /// Zoom inside its limits, and at least `FieldDp.keep` of the **Field** on screen each way.
    func clamp(_ view: FieldView) -> FieldView {
        let zoom = coerce(view.zoom, FieldDp.minZoom, FieldDp.maxZoom)
        return FieldView(
            zoom: zoom,
            offsetX: within(view.offsetX, FieldDp.keep - width * zoom, leftEdgeOffsetX(zoom)),
            offsetY: within(view.offsetY, FieldDp.keep - height * zoom, viewportH - FieldDp.keep)
        )
    }

    /// Zoom by `factor` keeping the point under the fingers (`cx`, `cy`, screen) where it is.
    func zoomAbout(_ view: FieldView, cx: CGFloat, cy: CGFloat, factor: CGFloat) -> FieldView {
        let zoom = coerce(view.zoom * factor, FieldDp.minZoom, FieldDp.maxZoom)
        let k = zoom / view.zoom
        return clamp(FieldView(zoom: zoom, offsetX: cx - (cx - view.offsetX) * k, offsetY: cy - (cy - view.offsetY) * k))
    }

    func pan(_ view: FieldView, dx: CGFloat, dy: CGFloat) -> FieldView {
        clamp(FieldView(zoom: view.zoom, offsetX: view.offsetX + dx, offsetY: view.offsetY + dy))
    }

    func atLeftEdge(_ view: FieldView) -> Bool { view.offsetX >= leftEdgeOffsetX(view.zoom) - 0.5 }

    private func within(_ v: CGFloat, _ lo: CGFloat, _ hi: CGFloat) -> CGFloat {
        lo > hi ? (lo + hi) / 2 : coerce(v, lo, hi)
    }
}

func fieldLayout(_ graph: ServiceGraph, viewportW: CGFloat, viewportH: CGFloat) -> FieldLayout {
    /// The graph's strips for one role, in first-seen order, each with its ids in order.
    func groups(_ role: ServiceRole) -> [(ServiceStrip, [String])] {
        var out: [(ServiceStrip, [String])] = []
        for node in graph.nodes where node.role == role {
            if let i = out.firstIndex(where: { $0.0 == node.strip }) {
                out[i].1.append(node.id)
            } else {
                out.append((node.strip, [node.id]))
            }
        }
        return out
    }

    let inputGroups = groups(.input)
    let alcoveGroups = groups(.alcove)
    let tileBand = FieldDp.stripHeader + FieldDp.tileSize + FieldDp.labelRoom

    // Every input strip is as wide as the widest, so their lines all leave from one
    // right edge and the curves to the join start level with each other.
    let maxTiles = inputGroups.map { $0.1.count }.max() ?? 0
    let inputW = 2 * FieldDp.stripPad + CGFloat(maxTiles) * FieldDp.columnWidth
    let rightEdge = FieldDp.margin + inputW

    var strips: [FieldStrip] = []
    var y = FieldDp.margin
    for (strip, ids) in inputGroups {
        let h = tileBand + CGFloat(ids.count) * FieldDp.laneGap + FieldDp.laneBottomPad
        strips.append(FieldStrip(
            strip: strip, role: .input,
            rect: FieldRect(left: FieldDp.margin, top: y, right: rightEdge, bottom: y + h),
            ids: ids
        ))
        y += h + FieldDp.stripGap
    }
    let inputBlock: FieldRect
    if let first = strips.first, let last = strips.last {
        inputBlock = FieldRect(left: FieldDp.margin, top: first.rect.top, right: rightEdge, bottom: last.rect.bottom)
    } else {
        inputBlock = FieldRect(left: FieldDp.margin, top: FieldDp.margin, right: rightEdge, bottom: FieldDp.margin)
    }
    let hubY = inputBlock.centerY
    let join = FieldPoint(x: rightEdge + FieldDp.joinRun, y: hubY)
    let timeline = FieldRect(
        left: join.x + FieldDp.hubGap, top: hubY - FieldDp.hubH / 2,
        right: join.x + FieldDp.hubGap + FieldDp.hubW, bottom: hubY + FieldDp.hubH / 2
    )
    let split = FieldPoint(x: timeline.right + FieldDp.hubGap, y: hubY)

    // The alcoves stand as one block centred on the trunk.
    let alcoveLeft = split.x + FieldDp.alcoveRun
    let alcoveH = tileBand + FieldDp.laneBottomPad
    let alcoveCount = CGFloat(alcoveGroups.count)
    let alcoveBlockH = alcoveCount * alcoveH + max(0, alcoveCount - 1) * FieldDp.stripGap
    y = hubY - alcoveBlockH / 2
    for (strip, ids) in alcoveGroups {
        let w = 2 * FieldDp.stripPad + CGFloat(ids.count) * FieldDp.columnWidth
        strips.append(FieldStrip(
            strip: strip, role: .alcove,
            rect: FieldRect(left: alcoveLeft, top: y, right: alcoveLeft + w, bottom: y + alcoveH),
            ids: ids
        ))
        y += alcoveH + FieldDp.stripGap
    }

    var tiles: [FieldTile] = []
    var lanes: [FieldLane] = []
    var alcoveLines: [FieldAlcoveLine] = []
    for s in strips {
        for (i, id) in s.ids.enumerated() {
            let c = FieldPoint(
                x: s.rect.left + FieldDp.stripPad + CGFloat(i) * FieldDp.columnWidth + FieldDp.columnWidth / 2,
                y: s.rect.top + FieldDp.stripHeader + FieldDp.tileSize / 2
            )
            let half = FieldDp.tileSize / 2
            let rect = FieldRect(left: c.x - half, top: c.y - half, right: c.x + half, bottom: c.y + half)
            tiles.append(FieldTile(id: id, center: c, rect: rect))
            if s.role == .input {
                let dropX = rect.right + FieldDp.dropOffset
                let laneY = s.rect.bottom - FieldDp.laneBottomPad - CGFloat(i) * FieldDp.laneGap
                lanes.append(FieldLane(
                    id: id,
                    corners: [
                        FieldPoint(x: rect.right, y: c.y),
                        FieldPoint(x: dropX, y: c.y),
                        FieldPoint(x: dropX, y: laneY),
                        FieldPoint(x: s.rect.right, y: laneY),
                    ],
                    join: join
                ))
            } else {
                alcoveLines.append(FieldAlcoveLine(id: id, from: split, to: FieldPoint(x: rect.left, y: c.y)))
            }
        }
    }

    let width = (strips.map { $0.rect.right }.max() ?? split.x) + FieldDp.margin
    let height = max(strips.map { $0.rect.bottom }.max() ?? 0, timeline.bottom) + FieldDp.margin
    return FieldLayout(
        strips: strips, tiles: tiles, lanes: lanes, alcoveLines: alcoveLines,
        join: join, split: split, timeline: timeline, inputBlock: inputBlock,
        width: width, height: height, viewportW: viewportW, viewportH: viewportH
    )
}

/// The curve every line ends on, flattened to `samples` segments: a cubic whose two
/// control points both sit halfway across, so it leaves `from` and arrives at `to`
/// horizontally. Lines that start level and end together never cross on the way.
func curvePoints(_ from: FieldPoint, _ to: FieldPoint, samples: Int = 16) -> [FieldPoint] {
    let mid = (from.x + to.x) / 2
    return (0...samples).map { (k: Int) -> FieldPoint in
        let t = CGFloat(k) / CGFloat(samples)
        let u = 1 - t
        let b0 = u * u * u
        let b1 = 3 * u * u * t
        let b2 = 3 * u * t * t
        let b3 = t * t * t
        return FieldPoint(
            x: b0 * from.x + (b1 + b2) * mid + b3 * to.x,
            y: (b0 + b1) * from.y + (b2 + b3) * to.y
        )
    }
}

/// A whole input line as points: its corners, then the curve to the join.
func lanePolyline(_ lane: FieldLane, samples: Int = 16) -> [FieldPoint] {
    guard let last = lane.corners.last else { return [] }
    return lane.corners + Array(curvePoints(last, lane.join, samples: samples).dropFirst())
}
