import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    @State private var apiKey = ""
    @State private var clientId = ""

    var body: some View {
        let s = model.state
        Form {
            Section("Spotify") {
                if s.spotifyConnected {
                    Text("✓ Logged in with Spotify").foregroundStyle(.tint)
                    Text(scopeMessage(s.grantedScope)).font(.caption).foregroundStyle(.secondary)
                    Button("Log out") { model.disconnectSpotify() }
                } else {
                    Button("Log in with Spotify") {
                        model.saveSettings(apiKey: apiKey, clientId: clientId)
                        model.loginSpotify()
                    }
                    .disabled(!s.bundledSpotifyClientId && clientId.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                Text("To use a different Spotify app, create one at "
                    + "developer.spotify.com/dashboard with Web API enabled and redirect "
                    + "URI \(spotifyRedirectURI), paste its Client ID below, Save, then log "
                    + "out and back in.")
                    .font(.caption).foregroundStyle(.secondary)
                // The way past Spotify's five-user cap, which the paragraph above
                // only hints at. Android links the same page from the same place.
                Link("Step by step, and how to ask for a slot",
                     destination: URL(string: "https://magnus-encoded.github.io/station-to-station/")!)
                TextField("Spotify Client ID", text: $clientId)
                    .autocorrectionDisabled().textInputAutocapitalization(.never)
            }

            Section("setlist.fm") {
                if s.bundledSetlistFmKey {
                    Text("Using the bundled setlist.fm API key. The setlist.fm API has no "
                        + "user login — to load your attended concerts, just enter your "
                        + "setlist.fm username on the My concerts tab.")
                        .font(.caption).foregroundStyle(.secondary)
                } else {
                    Text("Request a free API key at api.setlist.fm.")
                        .font(.caption).foregroundStyle(.secondary)
                    TextField("setlist.fm API key", text: $apiKey)
                        .autocorrectionDisabled().textInputAutocapitalization(.never)
                }
            }

            Section {
                Button("Save") { model.saveSettings(apiKey: apiKey, clientId: clientId) }
            }
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            apiKey = s.setlistFmApiKey
            clientId = s.spotifyClientId
        }
    }

    private func scopeMessage(_ scope: String?) -> String {
        guard let scope else { return "Granted permissions unknown — log out and log in again." }
        return scope.contains("playlist-modify")
            ? "Playlist permissions granted (\(scope))"
            : "⚠ Playlist permissions MISSING (\(scope)) — log out and log in again."
    }
}
