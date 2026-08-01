import SwiftUI

enum Route: Hashable { case friends, setlists, confirm, settings, station }

@MainActor
final class Nav: ObservableObject {
    @Published var path: [Route] = []
    func push(_ r: Route) { path.append(r) }
    func pop() { if !path.isEmpty { path.removeLast() } }
    func popToRoot() { path.removeAll() }
}

private let spotifyGreen = Color(red: 0x1D / 255, green: 0xB9 / 255, blue: 0x54 / 255)

@main
struct SetlistToSpotifyApp: App {
    @StateObject private var model = AppModel()
    @StateObject private var nav = Nav()

    var body: some Scene {
        WindowGroup {
            NavigationStack(path: $nav.path) {
                SearchView()
                    .navigationDestination(for: Route.self) { route in
                        switch route {
                        case .friends: FriendsView()
                        case .setlists: SetlistsView()
                        case .confirm: ConfirmView()
                        case .settings: SettingsView()
                        case .station: StationView()
                        }
                    }
            }
            .environmentObject(model)
            .environmentObject(nav)
            .tint(spotifyGreen)
            .appBanners(model)
            // Spotify's OAuth callback is handled by ASWebAuthenticationSession;
            // the app only needs to catch friend-card links here.
            .onOpenURL { url in
                if url.scheme == "setlist2spotify", url.host == "friend" {
                    model.handleFriendLink(url)
                }
                // station-to-station://<line>/<gig> — a Resolution reached without
                // a gesture, so CI and a URL bar can both get there. Only my own
                // line ("me") resolves today; a friend's line and the gig segment
                // are recognised and ignored rather than silently mis-routed.
                if url.scheme == "station-to-station" {
                    nav.popToRoot()
                    if url.host == nil || url.host == "me" { nav.push(.station) }
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
