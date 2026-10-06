import SwiftUI

private let amber = Color(red: 0xE7 / 255, green: 0xB2 / 255, blue: 0x4C / 255)

/// The offer on a **Gig** that is not mine: join it, as the date says.
struct JoinGigButton: View {
    @EnvironmentObject var model: AppModel
    let show: FmSetlist

    private var going: Bool {
        nightKind(date: isoDate(fromFm: show.eventDate), today: isoToday()) == .goingTo
    }

    var body: some View {
        Button(going ? "I am going too" : "I was there too") { model.planning.joinGig(show) }
            .font(.system(size: 15)).foregroundStyle(amber)
            .padding(.horizontal, 24).padding(.vertical, 8)
    }
}
