import SwiftUI

private let raised = Color(red: 0x17 / 255, green: 0x12 / 255, blue: 0x1F / 255)
private let ink = Color(red: 0xED / 255, green: 0xE9 / 255, blue: 0xF2 / 255)
private let muted = Color(red: 0x8B / 255, green: 0x82 / 255, blue: 0x99 / 255)
private let lineCol = Color(red: 0x2E / 255, green: 0x27 / 255, blue: 0x40 / 255)
private let amber = Color(red: 0xE7 / 255, green: 0xB2 / 255, blue: 0x4C / 255)
private let crossed = Color(red: 0x6F / 255, green: 0xBF / 255, blue: 0x9C / 255)

/// A separate reading-order stop between the paired Nights (#580). The edge's
/// Lines have already bent in the row above, so continue at their destination x.
/// Under the Contact light keep the height and rails, hiding the question only.
struct MaybeMergeRow: View {
    let above: WovenRow
    let below: WovenRow
    let lanes: [Friend]
    let colours: [Int]
    let laneWidth: CGFloat
    let unlit: Bool
    let compare: (MaybeNight) -> Void

    var body: some View {
        HStack(spacing: 0) {
            Color.clear.frame(width: SpineWidth + laneWidth)
            VStack(alignment: .leading, spacing: 8) {
                ForEach(below.maybeAbove) { maybe in
                    Button { compare(maybe) } label: {
                        Text(maybePill(maybe) + " · Compare")
                            .font(.callout)
                            .foregroundStyle(ink)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 12)
                            .frame(minHeight: 44)
                            .background(raised, in: Capsule())
                            .overlay(Capsule().stroke(muted, style: StrokeStyle(lineWidth: 1, dash: [4, 3])))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(maybeMergeLabel(maybe))
                }
            }
            .padding(.vertical, 8)
            .padding(.trailing, 18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .opacity(unlit ? 0 : 1)
            .allowsHitTesting(!unlit)
            .accessibilityHidden(unlit)
        }
        .frame(minHeight: 60)
        .background(alignment: .leading) {
            Canvas { ctx, size in
                let drawn = rowGeometry(above, below, lanes, laneWidth, size.height, colours)
                for d in drawn {
                    var rail = Path()
                    rail.move(to: CGPoint(x: d.toX, y: 0))
                    rail.addLine(to: CGPoint(x: d.toX, y: size.height))
                    ctx.stroke(rail, with: .color(linePaint(d.colourAhead)), lineWidth: d.widthAhead)
                }
                if !unlit {
                    let to = drawn.first { $0.line == nodeHost(below, lanes) }?.toX ?? SpineLineX
                    var link = Path()
                    link.move(to: CGPoint(x: SpineLineX, y: 0))
                    link.addCurve(to: CGPoint(x: to, y: size.height),
                                  control1: CGPoint(x: SpineLineX, y: size.height * 0.5),
                                  control2: CGPoint(x: to, y: size.height * 0.5))
                    ctx.stroke(link, with: .color(ink.opacity(0.7)),
                               style: StrokeStyle(lineWidth: 1.5, dash: [3, 4]))
                }
            }
            .frame(width: SpineWidth + laneWidth)
            .accessibilityHidden(true)
        }
    }
}

func linePaint(_ role: LineColour) -> Color {
    switch role {
    case .meeting: return crossed
    case .mine(let present): return amber.opacity(present ? 0.85 : 0.4)
    case .rail(let colourIndex): return laneColor(colourIndex)
    case .absent: return lineCol
    }
}

/// One Canvas behind the row draws every Line where it runs through this row: mine
/// (amber) plus each friend's (Lane colour), bending toward the next row's node and
/// turning green wherever two or more lie on the same stretch. A Node is a ring you
/// see through, so every Line stops at its rim. Faithful to Android's PeopleRails.
struct PeopleRails: View {
    let row: WovenRow
    let next: WovenRow?
    let lanes: [Friend]
    /// The colour index each drawn Lane keeps (`laneColours`). Empty means nobody is
    /// hidden, where drawn index and colour index are the same thing.
    var colours: [Int] = []
    let laneWidth: CGFloat

    var body: some View {
        Canvas { ctx, size in draw(&ctx, size) }
    }

    /// Strokes the description and keeps no rule of its own (#116). Where a Line goes is
    /// `rowGeometry`'s answer; this decides only what a role looks like and how a bend is
    /// curved. A geometry rule that appears in here is a rule in the wrong place.
    private func draw(_ ctx: inout GraphicsContext, _ size: CGSize) {
        guard laneWidth > 0, !lanes.isEmpty else { return }
        let h = size.height
        let isFestival = row.node.isSeveral
        let nodeAt = nodeHost(row, lanes)

        for d in rowGeometry(row, next, lanes, laneWidth, h, colours) {
            let atColor = color(d.colour)

            if d.nodeY - d.nodeR > 0 {
                var p = Path()
                p.move(to: CGPoint(x: d.x, y: 0))
                p.addLine(to: CGPoint(x: d.x, y: d.nodeY - d.nodeR))
                ctx.stroke(p, with: .color(atColor), lineWidth: d.width)
            }

            var body = Path()
            body.move(to: CGPoint(x: d.x, y: d.nodeY + d.nodeR))
            body.addLine(to: CGPoint(x: d.x, y: h - d.bendLen))
            ctx.stroke(body, with: .color(atColor), lineWidth: d.width)

            var tail = Path()
            tail.move(to: CGPoint(x: d.x, y: h - d.bendLen))
            if d.toX == d.x {
                tail.addLine(to: CGPoint(x: d.x, y: h))
            } else {
                tail.addCurve(
                    to: CGPoint(x: d.toX, y: h),
                    control1: CGPoint(x: d.x, y: h - d.bendLen * 0.45),
                    control2: CGPoint(x: d.toX, y: h - d.bendLen * 0.55)
                )
            }
            ctx.stroke(tail, with: .color(color(d.colourAhead)), lineWidth: d.widthAhead)

            // One Node per night, drawn once by the innermost Line that was there.
            // Mine and festivals draw their own ring, so this only fills the gap for
            // a Gig of theirs.
            if d.present && !row.mine && !isFestival && d.line == nodeAt {
                let joined = linesAt(row, lanes).count > 1
                let r: CGFloat = 6
                let rect = CGRect(x: d.x - r, y: d.nodeY - r, width: 2 * r, height: 2 * r)
                // The host's *stable* colour, so hiding someone never repaints this.
                let hostColour = colours.indices.contains(d.line) ? colours[d.line] : d.line
                ctx.stroke(Path(ellipseIn: rect),
                           with: .color(joined ? crossed : laneColor(hostColour)), lineWidth: 2)
            }
        }
    }

    /// The Canvas is the only thing that knows what a role looks like — which is what
    /// lets the colour rules be asserted in a unit test with nothing rendered.
    private func color(_ role: LineColour) -> Color { linePaint(role) }
}
