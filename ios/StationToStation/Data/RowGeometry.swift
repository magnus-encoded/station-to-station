import CoreGraphics

// MARK: - The drawn row

/// What the Timelines resolution actually draws, as numbers. The iOS half of #116,
/// ported from Android's `RowGeometry.kt` rather than re-derived.
///
/// `rowGeometry` is the one seam. It is arithmetic over the primitives in
/// `Timeline.swift` — `laneStep`, `stripWidth`, `laneXf`, `linesAt`, `nodeHost`,
/// `lineDrawnOffset` — holds no state, touches no Canvas, and needs no device. Three
/// consumers read the same value and none recomputes any part of it: `PeopleRails`
/// strokes it, `LaneGeometryTests` asserts it, and the woven-row dump prints it.
///
/// **The open decision the spec left to this half, settled: the constants are shared.**
/// The tail bend, the stroke weights and the three node radii were listed as
/// Android-only and free to differ here, on the argument that a different point system
/// might want different numbers. They do not differ — this file already drew 56, 2, 1.2
/// and 11/7/5, term for term, because the Canvas was ported from the same source. A
/// SwiftUI point and an Android dp are both 1/163-ish of an inch in practice, so there
/// was never a reason for them to diverge, and two sets of numbers would be two things
/// to keep in step for no gain. If iOS ever wants its own, that is a decision to take
/// deliberately and to write down here.
struct DrawnLine: Equatable {
    /// `Spine` for mine, or a Lane index for a friend's.
    let line: Int
    /// Where the Line enters the row, and where it leaves it into the next one.
    let x: CGFloat
    let toX: CGFloat
    /// Where this row's Node sits down the row.
    let nodeY: CGFloat
    /// The gap the Line leaves at the Node's rim — the Node's outer radius when this
    /// Line was there, and zero when it was not. A Node is a ring you see through and a
    /// Line drawn inside one fills it in; a Line belonging to nobody present is not that
    /// night's business, so it runs straight past without a notch.
    let nodeR: CGFloat
    /// How much of the tail is spent bending toward the next row's x. Never longer than
    /// the room below the Node, or a short row draws its straight stretch backwards
    /// before turning.
    let bendLen: CGFloat
    /// Was this Line at this row.
    let present: Bool
    /// How many Lines lie on this one where it runs through the row, and the weight
    /// that implies.
    let people: Int
    let colour: LineColour
    let width: CGFloat
    /// The same three, for the last stretch of the row — which belongs to the **edge
    /// ahead**, and which *every* Line gets, not only the ones that bend. Company
    /// peeling away leaves a Line alone even when that Line never moves, so a Spine has
    /// to stop reading green there too or it claims a Crossing that ended.
    let peopleAhead: Int
    let colourAhead: LineColour
    let widthAhead: CGFloat
}

/// What a stretch of Line means, not what it looks like. The Canvas resolves a role to
/// a colour; the palette stays where palettes belong, and "more than one Line here means
/// meeting green" becomes assertable without rendering anything.
enum LineColour: Equatable {
    /// More than one Line on this stretch: they *are* one Line and must read as one.
    case meeting
    /// My own Spine. Dimmer on a night I was not at.
    case mine(present: Bool)
    /// A friend's Lane, by Lane index.
    case rail(lane: Int)
    /// A Line running past a night nobody on it attended.
    case absent
}

/// How much of a row's tail is spent bending toward the next Node. A junction belongs to
/// the edge between two Nodes, not to the sliver above the lower one.
let EdgeBend: CGFloat = 56

/// One person walking alone, and what each extra one on the same Line adds.
let LineStrokeWidth: CGFloat = 2
let PerPerson: CGFloat = 1.2

/// The x the Canvas actually strokes my Spine at: half a stroke off `SpineX`, the same
/// nudge the Node marker uses to centre itself on it.
let SpineLineX: CGFloat = SpineX + 1

/// Every Line this row draws, in points.
///
/// `laneWidth` is the strip's current width — the animation runs through here, so a
/// half-open strip is a case with an answer rather than a moment only a screenshot could
/// catch. `rowHeight` is the row's laid-out height; only `bendLen` depends on it.
///
/// A Lane that has not slid into view yet is absent from the result: the strip does not
/// stroke Lines nobody can see.
func rowGeometry(
    _ row: WovenRow,
    _ next: WovenRow?,
    _ lanes: [Friend],
    _ laneWidth: CGFloat,
    _ rowHeight: CGFloat
) -> [DrawnLine] {
    let step = laneStep(lanes.count)
    let strip = stripWidth(lanes.count)
    // Openness is scaled by the Lane count so the Lanes slide out one after another
    // rather than together — which is why a per-Line slide exists at all.
    let open = strip <= 0 ? 0 : min(max(laneWidth / strip, 0), 1) * CGFloat(lanes.count)

    // The geometry asks how many nights a node stands for, never what kind it is: a
    // Section and a Festival are one ring of the same size, drawn once (#166).
    let isFestival = row.node.isSeveral
    let nodeY: CGFloat = isFestival ? 15 : 13
    // The outer radius of whichever Node this row draws. A member gig's smaller ring
    // keeps its proportion by having its own radius, not by scaling the gig's.
    let nodeR: CGFloat = isFestival ? 11 : (row.depth > 0 ? 5 : 7)

    // Every Line is described the same way, mine included. Mine is `Spine` and is only
    // special in that its own Lane is the Spine — so it never slides, and a Crossing I
    // was at happens where I already am.
    let lines = [Spine] + Array(0..<lanes.count)

    func slideOf(_ line: Int) -> CGFloat { line == Spine ? 1 : min(max(open - CGFloat(line), 0), 1) }
    func xOf(_ offset: Int, _ line: Int) -> CGFloat {
        SpineLineX + (laneXf(offset, step) - SpineLineX) * slideOf(line)
    }

    // Was this Line at this row? A membership check on the one which-Line answer, not
    // another open-coded copy of it.
    func thereAt(_ r: WovenRow, _ line: Int) -> Bool { linesAt(r, lanes).contains(line) }

    // How many Lines lie on this one where it runs. Merged Lines are one Line by
    // definition, so without this two of them draw the same path twice and look exactly
    // like one — the weight is what says how many.
    func peopleAt(_ r: WovenRow, _ line: Int) -> Int {
        let here = lineDrawnOffset(r, line, lanes)
        return max(lines.filter { lineDrawnOffset(r, $0, lanes) == here && thereAt(r, $0) }.count, 1)
    }

    // How many travel a bend together: they must share *both* of its ends.
    func peopleAlong(_ to: WovenRow?, _ line: Int) -> Int {
        guard let to else { return peopleAt(row, line) }
        let a = lineDrawnOffset(row, line, lanes)
        let b = lineDrawnOffset(to, line, lanes)
        return max(lines.filter {
            lineDrawnOffset(row, $0, lanes) == a && lineDrawnOffset(to, $0, lanes) == b
        }.count, 1)
    }

    return lines.compactMap { line -> DrawnLine? in
        guard slideOf(line) > 0 else { return nil }

        let x = xOf(lineDrawnOffset(row, line, lanes), line)
        // A row with no next row leaves where it entered: the end of the Spine is
        // defined rather than whatever the loop happened to leave.
        let toX = next == nil ? x : xOf(lineDrawnOffset(next, line, lanes), line)
        let here = thereAt(row, line)
        let gap: CGFloat = here ? nodeR : 0

        let people = peopleAt(row, line)
        let ahead = peopleAlong(next, line)
        return DrawnLine(
            line: line,
            x: x,
            toX: toX,
            nodeY: nodeY,
            nodeR: gap,
            bendLen: max(min((rowHeight - nodeY - gap) * 0.8, EdgeBend), 0),
            present: here,
            people: people,
            colour: roleOf(people, here, line),
            width: widthOf(people),
            peopleAhead: ahead,
            colourAhead: roleOf(ahead, here, line),
            widthAhead: widthOf(ahead)
        )
    }
}

/// Colour follows the geometry rather than the endpoints: where Lines lie on top of each
/// other they *are* one Line and have to read as one, and where one peels away it is
/// alone again and takes its own colour back. Green comes from the *count*, so a Crossing
/// between two friends is green without me being one of them.
private func roleOf(_ people: Int, _ present: Bool, _ line: Int) -> LineColour {
    if people > 1 { return .meeting }
    if line == Spine { return .mine(present: present) }
    return present ? .rail(lane: line) : .absent
}

/// Weight says how many walk this stretch together.
private func widthOf(_ people: Int) -> CGFloat { LineStrokeWidth + PerPerson * CGFloat(people - 1) }

/// Whose Line this is, for a log a person reads.
func lineLabel(_ line: Int, _ lanes: [Friend]) -> String {
    line == Spine ? "spine" : "lane\(line)(\(lanes.indices.contains(line) ? lanes[line].setlistfm : "?"))"
}
