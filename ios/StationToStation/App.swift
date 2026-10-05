import SwiftUI
import UIKit

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

/// Ground, the colour the launch screen is drawn in (`LaunchGround` in the asset
/// catalogue), so lifting the launch look is the timeline appearing and nothing else.
private let launchGround = Color(red: 0x0E / 255, green: 0x0B / 255, blue: 0x14 / 255)

/// Spotify's own green, for the handful of places that really are about Spotify:
/// the connect buttons, the now-playing dot on a Gig, the first-run door. Declared
/// once now that five files want the same exception to the tint.
let spotifyGreen = Color(red: 0x1D / 255, green: 0xB9 / 255, blue: 0x54 / 255)

/// The one thing SwiftUI's `App` cannot do: be present during
/// `didFinishLaunchingWithOptions`.
///
/// CoreBluetooth only hands back a restored background session to a manager created inside
/// that call, synchronously — a manager built a moment later gets no `willRestoreState`, and
/// the gossip channel silently degrades to "works while the app is open", which is precisely
/// what ADR-0019's carve-out exists to avoid. Hence a delegate, for one line (#417).
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]?)
    -> Bool {
        // Does nothing at all on a phone that has never met anybody — including raising the
        // Bluetooth permission prompt, which stays where it belongs, on the Exchange screen
        // with a person standing in front of it.
        GossipTransport.shared.wakeAtLaunch()
        return true
    }
}

@main
struct StationToStationApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var model = AppModel()
    @StateObject private var nav = Nav()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            Group {
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
            // The launch look, held over the Timeline until its saved nights are on
            // it — Android's system splash does the same. The Timeline is underneath
            // all along, since appearing is what loads it; it is only kept out of
            // sight, and out of VoiceOver's reach, while it is still empty.
            .accessibilityHidden(!model.state.launched)
            .overlay {
                if !model.state.launched {
                    launchGround.ignoresSafeArea()
                }
            }
            .environmentObject(model)
            .environmentObject(nav)
            .tint(amber)
            // Nocturnal single theme: the Timeline is dark whatever the phone is.
            .preferredColorScheme(.dark)
            .appBanners(model) { nav.push(.settings) }
            // Spotify's OAuth callback is handled by ASWebAuthenticationSession;
            // the app only needs to catch friend-card links here.
            .onOpenURL { url in
                switch parseDeepLink(url.absoluteString) {
                case nil:
                    return
                case .passThrough(.friend, _):
                    model.handleFriendLink(url)
                case .passThrough(.handover, _):
                    // The old phone's code: the address, the certificate to pin and the
                    // key for the transfer, which is why any camera can open it and only
                    // the phone that read it can join. Parsed before trusting the host: a
                    // truncated or hand-typed link would land the reader on the *source*
                    // side's tick list, this phone offering to hand itself over.
                    guard parseHandoverInvite(url.absoluteString) != nil else { return }
                    model.handover.joinHandover(url)
                    nav.popToRoot()
                    nav.push(.handover)
                case .passThrough:
                    nav.popToRoot()
                case .some(let intent):
                    // The Timeline is the root, so routing is popping to it and setting
                    // its Resolution, never pushing it. Pinch cannot be scripted.
                    nav.popToRoot()
                    switch intent {
                    case .open(.settings, _): nav.push(.settings)
                    case .open(.programme, _): nav.push(.programme)
                    case .open(let screen, let date):
                        model.navigation.openTimeline(zoomedOut: screen == .timelines, date: date)
                    case .openGig(let id):
                        model.navigation.openGig(id) { nav.push(.gig) }
                    case .addGig(let artist, let venue, let date):
                        model.navigation.openAddGig(artist: artist, venue: venue, date: date)
                    case .writeToLog(let id, let appends, let replacements):
                        model.navigation.openGig(id) {
                            nav.push(.gig)
                            model.writeToLog(appends: appends, replacements: replacements)
                        }
                    case .legacyPlace(let id, let at):
                        model.navigation.openPlace(id, as: at) { nav.push(.gig) }
                    case .me:
                        model.navigation.setZoomedOut(false)
                    case .fixture(let name, let open):
                        model.loadFixture(name, open: open)
                    case .passThrough:
                        break
                    }
                }
            }
            }
            }
            // setlist.fm's automatic checks for local Gigs run while the app is in the
            // foreground and only then (#531): at launch, on coming back, and on their
            // timer. This is the launch that went straight to active, which `onChange`
            // below never sees; a background relaunch (the gossip radio's) starts nothing.
            .onAppear { if scenePhase == .active { model.startLookupChecks() } }
        }
        // A **Ticket** is deposited while this app is in the background — the share
        // sheet never brings it forward — so the inbox is read on the way back in
        // (#412). The cold-launch case is covered from `AppModel.init`, because a
        // launch that goes straight to active may never register as a *change*.
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .active:
                model.drainTicketInbox()
                model.startLookupChecks()
            case .inactive, .background:
                model.stopLookupChecks()
            @unknown default:
                break
            }
        }
    }
}

/// Surfaces the model's transient error/notice as native alerts (the iOS analog
/// of the Android snackbars), centralised so every screen inherits them.
private struct BannersModifier: ViewModifier {
    @ObservedObject var model: AppModel
    /// Where "Add your own key" goes. Nil behind the splash, which has no stack to
    /// push Settings onto — the nudge is an offer, and an offer that cannot be taken
    /// is simply not made.
    var onOpenSettings: (() -> Void)?
    /// The ticket draft the confirm sheet is showing.
    @State private var shownTicketDraft: UUID?

    func body(content: Content) -> some View {
        content
            .safeAreaInset(edge: .bottom) { MaybeUndoBanner(model: model) }
            .alert("Error", isPresented: Binding(
                get: { model.state.error != nil },
                set: { if !$0 { model.consumeError() } }
            )) {
                // The one error with something to do about it: the bundled setlist.fm
                // key is shared by every tester and today's requests are gone, and a
                // free key of your own is a short trip to Settings away (#457).
                if model.state.errorKind == .setlistFmSharedQuota, let onOpenSettings {
                    Button(addOwnKeyAction) { onOpenSettings() }
                    Button("Not now", role: .cancel) {}
                } else {
                    Button("OK", role: .cancel) {}
                }
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
                Text(conflict.message)
            }
            // A shared **Ticket**, waiting to be confirmed (#412). Mounted here with
            // the banners for the reason the card conflict above is: a Ticket arrives
            // from another process while any screen is up, and the prompt is not the
            // Timeline's to own.
            //
            // Every answer names the draft it was given for: a swipe-down dismisses the
            // one on screen (`shownTicketDraft`), never whichever is first by then, since
            // dismissing a draft also deletes its inbox deposit.
            .sheet(item: Binding(
                get: { model.state.ticketDrafts.first },
                set: { if $0 == nil, let shown = shownTicketDraft { model.dismissTicket(shown) } }
            )) { draft in
                ConfirmTicketSheet(ticket: draft.ticket, possibleMatch: draft.possibleMatch,
                                   setlistFm: draft.setlistFm) { artist, venue, date, chosen in
                    model.confirmTicket(draft.id, artist: artist, venue: venue, date: date,
                                        chosenSetlistId: chosen)
                } onCancel: {
                    model.dismissTicket(draft.id)
                }
                .onAppear { shownTicketDraft = draft.id }
                // Only Cancel drops the ticket. A stray swipe-down would throw away a
                // parsed ticket, its barcodes and its inbox deposit.
                .interactiveDismissDisabled()
                .environmentObject(model)
                .tint(amber)
                .preferredColorScheme(.dark)
            }
    }
}

extension View {
    func appBanners(_ model: AppModel, onOpenSettings: (() -> Void)? = nil) -> some View {
        modifier(BannersModifier(model: model, onOpenSettings: onOpenSettings))
    }
}
