import SwiftUI

private let faint = Color(red: 0x5A / 255, green: 0x53 / 255, blue: 0x68 / 255)
private let amber = Color(red: 0xE7 / 255, green: 0xB2 / 255, blue: 0x4C / 255)

/// "AmandaSvea is also here", for the **Contacts** a **Pass** just proved are in the room.
///
/// `nil` for an empty room rather than a line saying so: "nobody is here" is a claim this
/// phone cannot make — a **Contact** across a hall, or with their phone in a pocket, is not
/// absent — so silence is the honest answer, and it keeps the line out of the way on every
/// quiet night. Three names collapse to a count because the sentence is a glance, not a list.
func alsoHereSentence(_ here: [String]) -> String? {
    switch here.count {
    case 0: return nil
    case 1: return "\(here[0]) is also here"
    case 2: return "\(here[0]) and \(here[1]) are also here"
    default: return "\(here[0]) and \(here.count - 1) others are also here"
    }
}

/// The **Seen with** line's words (#499).
///
/// "Seen with", not "here" and not "checked in": the record outlives the night's **Gossip**, and
/// a present-tense line on a **Gig** from last March would be the app claiming a room it is not
/// in. The unnamed devices are counted rather than listed because there is nothing to list —
/// a **Blind relay** carries a key, never a person — and they are still said out loud, because
/// silently dropping them would report a quieter room than this phone actually stood in.
///
/// Worded identically to Android's `seenWithLine`: two apps showing the same night should not
/// phrase it differently.
func seenWithLine(_ seen: SeenWith) -> String {
    let others = seen.others == 0 ? nil : "\(seen.others) other\(seen.others == 1 ? "" : "s")"
    let parts = [seen.named.isEmpty ? nil : seen.named.joined(separator: ", "), others].compactMap { $0 }
    return "Seen with " + parts.joined(separator: " + ")
}

/// The bullet on a **Presence row**: whether the radio is standing at this night (#501).
///
/// Amber for the **Active Gig**, which is this app's one meaning for amber — *mine* — and here
/// it says the radio is mine and it is here. `faint` for the off state: plainly present, plainly
/// not lit, and distinct from the amber at a glance rather than by a hue somebody has to
/// compare. The blink is what says this is the radio's state and not a label; one animation for
/// both colours, because two that pulsed differently would read as two kinds of thing.
struct GossipBulletMark: View {
    let bullet: GossipBullet
    @State private var dimmed = false

    var body: some View {
        Circle()
            .fill(bullet == .on ? amber : faint)
            .frame(width: 7, height: 7)
            .opacity(dimmed ? 0.25 : 1)
            .onAppear {
                withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) { dimmed = true }
            }
    }
}

/// A **Gig**'s **Presence row**: that this phone is standing here, and whether the radio is
/// standing here with it (#501).
///
/// The check-in line is the row, with the bullet in front of it and the whole line the tap
/// target — iOS's **Room** already says "✓ checked in" in amber there, and a second control
/// beside it would be two statements about one thing. The "is also here" and **Seen with** lines
/// stay where #484 and #499 put them, under the header: Android composes all three because its
/// bottom bar is one block, and copying that layout here would be a port of a screen rather than
/// of a decision.
///
/// A night that cannot **Gossip** draws no bullet and does nothing when tapped, which is the
/// honest reading of a control with nothing behind it.
///
/// The clock is here and not in the model: a night's grace runs out with nobody doing anything,
/// and the bullet has to go out while somebody is looking at it. `onExpiry` hands that same tick
/// back up, because the *other* night's row — the one that inherits the amber — is a different
/// **Room**, and only the timeline knows which night is next.
///
/// `eligibleUntil` is the deadline with **no stop applied** (see `gossipBullet`).
struct GossipPresenceRow: View {
    let label: String
    let eligibleUntil: Int64?
    let active: Bool
    let stopped: Bool
    let onSelect: () -> Void
    let onExpiry: () -> Void

    var body: some View {
        // No clock and no blink on a night with nothing to draw: most **Rooms** ever opened are
        // last year's, and a ticking view on each of them is a cost for a bullet that will
        // never appear.
        if (eligibleUntil ?? 0) <= 0 {
            line(nil)
        } else {
            TimelineView(.periodic(from: .now, by: 1)) { tick in
                let now = Int64(tick.date.timeIntervalSince1970 * 1000)
                let bullet = gossipBullet(eligibleUntil: eligibleUntil, active: active,
                                          stopped: stopped, now: now)
                line(bullet)
                    .onChange(of: bullet == nil) { gone in if gone { onExpiry() } }
            }
        }
    }

    private func line(_ bullet: GossipBullet?) -> some View {
        HStack(spacing: 6) {
            if let bullet { GossipBulletMark(bullet: bullet) }
            Text(label).font(.system(size: 13)).foregroundStyle(amber)
        }
        .padding(.top, 6)
        .contentShape(Rectangle())
        .onTapGesture { if bullet != nil { onSelect() } }
        .accessibilityElement(children: .combine)
        // "✓" is read as "check mark"; the words already say it (#164).
        .accessibilityLabel(label.replacingOccurrences(of: "\u{2713} ", with: ""))
        // The bullet's colour is its whole state, so say it — and say it is a control
        // only where a tap does something.
        .accessibilityValue(bullet.map { $0 == .on ? "gossip on" : "gossip off" } ?? "")
        .accessibilityAddTraits(bullet == nil ? [] : .isButton)
        .accessibilityHint(bullet == nil ? "" : "Stand at this gig — gossip speaks for it")
    }
}
