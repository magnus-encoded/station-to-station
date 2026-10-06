import SwiftUI

private let ground = Color(red: 0x0E / 255, green: 0x0B / 255, blue: 0x14 / 255)
private let ink = Color(red: 0xED / 255, green: 0xE9 / 255, blue: 0xF2 / 255)
private let muted = Color(red: 0x8B / 255, green: 0x82 / 255, blue: 0x99 / 255)
private let faint = Color(red: 0x5A / 255, green: 0x53 / 255, blue: 0x68 / 255)
private let amber = Color(red: 0xE7 / 255, green: 0xB2 / 255, blue: 0x4C / 255)

/// "Are you here?" — the one thing a check-in asks (#174). Shown only when a
/// fix already put the phone at the venue on the night, so it states what it
/// thinks and offers the two honest answers.
struct CheckInDialog: View {
    let gig: FmSetlist
    let onCheckIn: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Are you here?")
                .font(.system(size: 19, design: .serif)).foregroundStyle(ink)
            Text("\(gig.artist?.name ?? "This show") at \(gig.venue?.name ?? "the venue"), tonight.")
                .font(.system(size: 13)).foregroundStyle(muted)
            Text("Checking in records that you were at it — on this phone, nowhere else.")
                .font(.system(size: 11)).foregroundStyle(faint)
            Spacer(minLength: 12)
            HStack {
                Spacer()
                Button("Not now") { onDismiss() }.foregroundStyle(faint)
                Button("Check in") { onCheckIn() }.foregroundStyle(amber)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(ground.ignoresSafeArea())
    }
}
