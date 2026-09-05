import SwiftUI

enum Route: Hashable { case friends, setlists, confirm, settings, station, search, gig, exchange, programme, handover }

@MainActor
final class Nav: ObservableObject {
    @Published var path: [Route] = []
    func push(_ r: Route) { path.append(r) }
    func pop() { if !path.isEmpty { path.removeLast() } }
    func popToRoot() { path.removeAll() }
}

// The spine's accent, the same amber the Line and the rungs are drawn in. The app-wide
// tint used to be Spotify green, which every control then inherited — text fields,
// buttons, toggles — and the whole app read as if it were a Spotify client. Green means
// Spotify and only Spotify, said explicitly where it is meant (see GigView).
private let amber = Color(red: 0xE7 / 255, green: 0xB2 / 255, blue: 0x4C / 255)

/// Spotify's own green, for the handful of places that really are about Spotify:
/// the connect buttons, the now-playing dot on a Gig, the first-run door. Declared
/// once now that five files want the same exception to the tint.
let spotifyGreen = Color(red: 0x1D / 255, green: 0xB9 / 255, blue: 0x54 / 255)

@main
struct StationToStationApp: App {
    @StateObject private var model = AppModel()
    @StateObject private var nav = Nav()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            // The first-run door (#358), and nothing else is reachable behind it.
            // A splash pushed *onto* the stack could be dismissed by a back
            // gesture into a timeline nobody had asked to see yet.
            if !model.state.onboarded {
                SplashView()
                    .environmentObject(model)
                    .tint(amber)
                    .preferredColorScheme(.dark)
                    .appBanners(model)
            } else {
            NavigationStack(path: $nav.path) {
                // The Timeline is home; the setlist-to-Spotify converter stays
                // reachable behind search, exactly as on Android — nothing removed.
                StationView()
                    .navigationDestination(for: Route.self) { route in
                        switch route {
                        case .friends: FriendsView()
                        case .setlists: SetlistsView()
                        case .confirm: ConfirmView()
                        case .settings: SettingsView()
                        case .station: StationView()
                        case .search: SearchView()
                        case .gig: GigView()
                        case .exchange: ExchangeView()
                        case .programme: ProgrammeView()
                        case .handover: HandoverView()
                        }
                    }
            }
            .environmentObject(model)
            .environmentObject(nav)
            .tint(amber)
            // Nocturnal single theme: the Timeline is dark whatever the phone is.
            .preferredColorScheme(.dark)
            .appBanners(model)
            // Spotify's OAuth callback is handled by ASWebAuthenticationSession;
            // the app only needs to catch friend-card links here.
            .onOpenURL { url in
                // Everything rides one scheme now, station-to-station; the old
                // setlist2spotify scheme is still accepted so a friend card shared
                // before the rename still opens the app. The authority tells a friend
                // card apart from a timeline place, whose host is a line name — a line
                // literally named "friend" would collide, which is acceptable.
                guard url.scheme == "station-to-station" || url.scheme == "setlist2spotify"
                else { return }
                if url.host == "friend" {
                    model.handleFriendLink(url)
                    return
                }
                // An invite a contact sent, opening the night it names (#179). Until
                // this, every invite an Android phone shared was dead on arrival here.
                // The old phone's code (#142). It carries the address, the certificate
                // to pin and the key for the transfer, which is why any camera can open
                // it and only the phone that read it can join.
                if url.host == "handover" {
                    // Parsed here rather than trusting the host alone: a truncated or
                    // hand-typed link would otherwise land the reader on the *source*
                    // side's tick list — this phone offering to hand itself over, from a
                    // link that asked it to receive. A link that is not an invite is not
                    // a navigation.
                    guard parseHandoverInvite(url.absoluteString) != nil else { return }
                    model.joinHandover(url)
                    nav.popToRoot()
                    nav.push(.handover)
                    return
                }
                if url.host == "gig" {
                    model.handleGigInvite(url) { nav.popToRoot(); nav.push(.gig) }
                    return
                }
                // station-to-station://<host>/… — a Resolution reached without a
                // gesture, so CI and a URL bar can both get to the Spine (pinch
                // cannot be scripted). The Timeline is the root now, so routing
                // means popping to it and setting its Resolution, not pushing.
                //
                //   me                         → My timeline (single Line)
                //   fixture/<name>[/open]      → seed a bundled weave fixture; a
                //                                fixture with Lanes lands zoomed
                //                                out, /open uncollapses Festivals
                nav.popToRoot()
                let segments = url.pathComponents.filter { $0 != "/" }
                switch url.host {
                case "fixture":
                    if let name = segments.first {
                        model.loadFixture(name, open: segments.contains("open"))
                    }
                default:
                    // "me", nil, or a friend's line: my own Spine. Zoomed state
                    // is left as it is so a friend link can land on the strip.
                    if url.host == nil || url.host == "me" { model.setZoomedOut(false) }
                }
            }
            }
        }
        // A **Ticket** is deposited while this app is in the background — the share
        // sheet never brings it forward — so the inbox is read on the way back in
        // (#412). The cold-launch case is covered from `AppModel.init`, because a
        // launch that goes straight to active may never register as a *change*.
        .onChange(of: scenePhase) { phase in
            if phase == .active { model.drainTicketInbox() }
        }
    }
}

/// Surfaces the model's transient error/notice as native alerts (the iOS analog
/// of the Android snackbars), centralised so every screen inherits them.
private struct BannersModifier: ViewModifier {
    @ObservedObject var model: AppModel

    func body(content: Content) -> some View {
        content
            .alert("Error", isPresented: Binding(
                get: { model.state.error != nil },
                set: { if !$0 { model.consumeError() } }
            )) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(model.state.error ?? "")
            }
            .alert("", isPresented: Binding(
                get: { model.state.notice != nil },
                set: { if !$0 { model.consumeNotice() } }
            )) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(model.state.notice ?? "")
            }
            // The one question a handed-over card has to ask (#188): it names someone I
            // already hold and says something different about them. Mounted here with the
            // banners rather than on the Exchange screen, because a card arrives through
            // four doors — a link, a QR scan, a radio, a typed username — and a link can
            // land while any screen is up. Cancel is the default and the dismissal, so
            // doing nothing is never an accidental yes. Two labelled buttons, which is
            // what VoiceOver announces.
            .alert("Change this contact?", isPresented: Binding(
                get: { model.state.friendConflict != nil },
                set: { if !$0 { model.dismissFriendOverwrite() } }
            ), presenting: model.state.friendConflict) { _ in
                Button("Keep mine", role: .cancel) { model.dismissFriendOverwrite() }
                Button("Use the card") { model.confirmFriendOverwrite() }
            } message: { conflict in
                Text(conflict.keyChanged
                     ? "\(conflict.existing.name) (@\(conflict.existing.setlistfm)) seems to be "
                       + "on a different phone than last time you saw them. Confirm you still "
                       + "want to share."
                     : "A card for @\(conflict.existing.setlistfm) says something different from "
                       + "what you have.\n\nNow: \(conflict.existing.name)\n"
                       + "Card: \(conflict.incoming.name)\n\nTheir timeline does not change "
                       + "either way — only the name you see against it.")
            }
            // A shared **Ticket**, waiting to be confirmed (#412). Mounted here with
            // the banners for the reason the card conflict above is: a Ticket arrives
            // from another process while any screen is up, and the prompt is not the
            // Timeline's to own.
            .sheet(item: Binding(
                get: { model.state.ticketDrafts.first },
                set: { if $0 == nil { model.dismissTicket() } }
            )) { draft in
                ConfirmTicketSheet(ticket: draft.ticket) { artist, venue, date in
                    model.confirmTicket(artist: artist, venue: venue, date: date)
                } onCancel: {
                    model.dismissTicket()
                }
                .environmentObject(model)
                .tint(amber)
                .preferredColorScheme(.dark)
            }
    }
}

extension View {
    func appBanners(_ model: AppModel) -> some View { modifier(BannersModifier(model: model)) }
}
