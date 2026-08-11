import SwiftUI

enum Route: Hashable { case friends, setlists, confirm, settings, station, search, gig, exchange }

@MainActor
final class Nav: ObservableObject {
    @Published var path: [Route] = []
    func push(_ r: Route) { path.append(r) }
    func pop() { if !path.isEmpty { path.removeLast() } }
    func popToRoot() { path.removeAll() }
}

private let spotifyGreen = Color(red: 0x1D / 255, green: 0xB9 / 255, blue: 0x54 / 255)

@main
struct StationToStationApp: App {
    @StateObject private var model = AppModel()
    @StateObject private var nav = Nav()

    var body: some Scene {
        WindowGroup {
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
                        }
                    }
            }
            .environmentObject(model)
            .environmentObject(nav)
            .tint(spotifyGreen)
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
    }
}

extension View {
    func appBanners(_ model: AppModel) -> some View { modifier(BannersModifier(model: model)) }
}
