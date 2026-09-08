import SwiftUI

/// Moving to a new phone (#142), from this side of it. Ported from Android's
/// `HandoverScreen`, and the same shape: tick what travels, approve it in words that name
/// what happens *here*, show the code, watch it move.
///
/// The two roles look quite different on purpose. The old phone chooses and approves; the
/// new phone only watches, because it was handed a link and has nothing to decide.

private let ground = Color(red: 0x0E / 255, green: 0x0B / 255, blue: 0x14 / 255)
private let raised = Color(red: 0x17 / 255, green: 0x12 / 255, blue: 0x1F / 255)
private let ink = Color(red: 0xED / 255, green: 0xE9 / 255, blue: 0xF2 / 255)
private let muted = Color(red: 0x8B / 255, green: 0x82 / 255, blue: 0x99 / 255)
private let amber = Color(red: 0xE7 / 255, green: 0xB2 / 255, blue: 0x4C / 255)

/// One line of the tick list. `categories` is what it stands for on the wire — a row is a
/// sentence to a person and a set of categories to `deviceManifest`, and only one of those
/// two is worth writing twice.
private struct Choice: Identifiable {
    let id: String
    let title: String
    let detail: String
    let categories: Set<String>
    var ticked: Bool
}

struct HandoverView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var nav: Nav

    /// **Nothing starts ticked**, matching Android: every category that leaves this phone
    /// was chosen rather than merely not un-chosen, and the button stays disabled until at
    /// least one is. The row that goes unread is the row that does not travel.
    @State private var choices: [Choice] = [
        Choice(id: "nights", title: "Nights",
               detail: "Every gig, its setlist, your log, your check-ins and playlists.",
               categories: [categorySetlists], ticked: false),
        Choice(id: "media", title: "Photos, videos and notes",
               detail: "Everything attached to a night that is not in the vault.",
               categories: [StoredMedia.Kind.photo, StoredMedia.Kind.video, StoredMedia.Kind.note],
               ticked: false),
        Choice(id: "vault", title: "The vault",
               detail: "What you marked personal. It stays personal on the new phone.",
               categories: [categoryOf(kind: StoredMedia.Kind.photo, personal: true),
                            categoryOf(kind: StoredMedia.Kind.video, personal: true),
                            categoryOf(kind: StoredMedia.Kind.note, personal: true)],
               ticked: false),
        Choice(id: "accounts", title: "Accounts",
               detail: "Spotify moves rather than copies: this phone signs out once the other one has it.",
               categories: [categoryAccounts], ticked: false),
    ]

    private var allow: Set<String> {
        choices.filter(\.ticked).reduce(into: Set<String>()) { $0.formUnion($1.categories) }
    }

    private var handover: HandoverUi { model.state.handover }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                if handover.receipt != nil || handover.error != nil {
                    outcome
                } else if handover.role == nil {
                    tickList
                } else {
                    running
                }
            }
            .padding(20)
        }
        .background(ground.ignoresSafeArea())
        .navigationTitle("Move to a new phone")
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear { model.dismissHandover() }
    }

    // MARK: - Before

    private var tickList: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("The new phone opens the code below. Nothing leaves this phone — what "
                + "travels is a copy, and this one keeps everything.")
                .font(.callout).foregroundStyle(muted)

            ForEach($choices) { $choice in
                Button {
                    choice.ticked.toggle()
                } label: {
                    HStack(alignment: .top, spacing: 12) {
                        Image(systemName: choice.ticked ? "checkmark.square.fill" : "square")
                            .foregroundStyle(choice.ticked ? amber : muted)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(choice.title).foregroundStyle(ink)
                            Text(choice.detail).font(.caption).foregroundStyle(muted)
                        }
                        Spacer()
                    }
                    .padding(12)
                    .background(raised, in: RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
            }

            Button {
                model.offerHandover(allow)
            } label: {
                // The verb names what happens on *this* device, which is the surprising
                // part — the records are copied and nothing is removed, while the
                // accounts genuinely leave. Same rule as Android's `approvalVerb`.
                Text(approvalVerb(allow))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
            }
            .buttonStyle(.borderedProminent)
            .disabled(allow.isEmpty)
        }
    }

    // MARK: - During

    private var running: some View {
        VStack(alignment: .leading, spacing: 16) {
            if handover.role == .source {
                if let uri = handover.inviteUri, let image = qrImage(uri) {
                    Text("Open this on the new phone. The code carries the address, the "
                        + "certificate to trust and the key for this transfer — nobody "
                        + "who did not read it can join.")
                        .font(.callout).foregroundStyle(muted)
                    Image(uiImage: image)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: 260)
                        .padding(12)
                        .background(Color.white, in: RoundedRectangle(cornerRadius: 12))
                        .frame(maxWidth: .infinity)
                } else {
                    ProgressView().tint(amber)
                    Text("Opening a door on this network…").foregroundStyle(muted)
                }
            }

            progress(handover.progress)

            Button("Stop", role: .destructive) { model.cancelHandover() }
                .buttonStyle(.bordered)
        }
    }

    private func progress(_ p: HandoverProgress) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(phaseWords(p)).foregroundStyle(ink)
            if p.phase == .transfer && p.bytesTotal > 0 {
                // A total that a video's unknown size left short is a floor, not a promise:
                // clamped so the bar never overshoots and the words never read "8 MB of 4 MB".
                let total = max(p.bytesTotal, p.bytesDone)
                ProgressView(value: Double(p.bytesDone), total: Double(max(total, 1)))
                    .tint(amber)
                Text("\(humanBytes(p.bytesDone)) of \(humanBytes(total))")
                    .font(.caption).foregroundStyle(muted)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(raised, in: RoundedRectangle(cornerRadius: 12))
    }

    private func phaseWords(_ p: HandoverProgress) -> String {
        switch p.phase {
        case .connecting: return "Waiting for the other phone…"
        case .accounts: return "Moving who you are…"
        case .manifest: return "Agreeing on what to send…"
        case .transfer:
            if p.itemsTotal == 0 { return "Nothing left to fetch — it is all already here." }
            return "Item \(min(p.items + 1, p.itemsTotal)) of \(p.itemsTotal)"
        case .done: return "Done."
        case .failed: return "Stopped."
        }
    }

    // MARK: - After

    private var outcome: some View {
        VStack(alignment: .leading, spacing: 14) {
            if let error = handover.error {
                Text(error).foregroundStyle(amber)
            }
            if let receipt = handover.receipt {
                VStack(alignment: .leading, spacing: 6) {
                    line("Arrived", "\(receipt.landed) item\(receipt.landed == 1 ? "" : "s")"
                        + " (\(humanBytes(receipt.bytes)))")
                    // Nothing when accounts were not part of this handover — declining
                    // the row is a supported outcome (#143 story 11) and the receipt
                    // should not mention a step that never ran (#143 story 9).
                    if receipt.accountsMove != .notOffered {
                        line("Accounts", receipt.accountsMove == .acknowledged ? "Arrived" : "Did not complete")
                    }
                    if receipt.fromGallery > 0 {
                        line("Already in the library", "\(receipt.fromGallery) — no bytes needed")
                    }
                    if receipt.held > 0 { line("Already here", "\(receipt.held)") }
                    if receipt.withheld > 0 { line("Not sent", "\(receipt.withheld)") }
                    if receipt.refused > 0 { line("Refused", "\(receipt.refused)") }
                    if receipt.countMismatch {
                        Text("The other phone listed a different number of items than it "
                            + "described. What arrived is here; something was cut short.")
                            .font(.caption).foregroundStyle(amber)
                    }
                    if !receipt.trouble.isEmpty {
                        Text(receipt.trouble).font(.caption).foregroundStyle(amber)
                    }
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(raised, in: RoundedRectangle(cornerRadius: 12))
            }
            Button("Done") { nav.pop() }
                .buttonStyle(.borderedProminent)
        }
    }

    private func line(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).foregroundStyle(muted)
            Spacer()
            Text(value).foregroundStyle(ink)
        }
    }
}

/// Bytes as a person reads them. Decimal units — kB is 1000 B, as the labels say and as
/// Android's own formatter and the Files app both count — one decimal, and no dependency.
func humanBytes(_ bytes: Int64) -> String {
    if bytes < 1000 { return "\(bytes) B" }
    let units = ["kB", "MB", "GB", "TB"]
    var value = Double(bytes) / 1000
    var unit = 0
    while value >= 1000 && unit < units.count - 1 {
        value /= 1000
        unit += 1
    }
    return String(format: "%.1f %@", value, units[unit])
}
