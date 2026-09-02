import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    @State private var apiKey = ""
    @State private var clientId = ""
    @State private var clashfinderUser = ""
    @State private var clashfinderPrivateKey = ""

    var body: some View {
        let s = model.state
        Form {
            Section("Spotify") {
                if s.spotifyConnected {
                    // Green, said out loud, because here it really is about Spotify.
                    Text("✓ Logged in with Spotify").foregroundStyle(spotifyGreen)
                    Text(scopeMessage(s.grantedScope)).font(.caption).foregroundStyle(.secondary)
                    Button("Log out") { model.disconnectSpotify() }
                } else {
                    Button("Log in with Spotify") {
                        model.saveSettings(apiKey: apiKey, clientId: clientId)
                        model.loginSpotify()
                    }
                    .tint(spotifyGreen)
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

            Section("clashfinder") {
                Text("Powers the Programme tab: a timetable for the festival you pick, "
                    + "with clashes worked out for you. No account, no programme feature "
                    + "— there is deliberately no shared credential here (see #390). "
                    + "Create a free account and its private key at clashfinder.com.")
                    .font(.caption).foregroundStyle(.secondary)
                TextField("clashfinder username", text: $clashfinderUser)
                    .autocorrectionDisabled().textInputAutocapitalization(.never)
                SecureField("clashfinder private key", text: $clashfinderPrivateKey)
                Button("Save clashfinder account") {
                    model.saveClashfinderAccount(user: clashfinderUser, privateKey: clashfinderPrivateKey)
                }
                .disabled(clashfinderUser.trimmingCharacters(in: .whitespaces).isEmpty
                    || clashfinderPrivateKey.trimmingCharacters(in: .whitespaces).isEmpty)
            }

            // Cards are swapped peer to peer and never expire on their own, so
            // without this the only way to drop a lane was to wipe the app.
            // Friends lists the same people; what is new here is the lane — how
            // many nights of theirs this device is actually holding.
            Section("Known timelines") {
                if s.friends.isEmpty {
                    Text("Nobody yet. Swipe left from your timeline to swap cards with "
                        + "someone, and their line opens beside yours.")
                        .font(.caption).foregroundStyle(.secondary)
                } else {
                    ForEach(s.friends) { friend in
                        VStack(alignment: .leading) {
                            Text(friend.name)
                            Text("@\(friend.setlistfm) · \(s.showsByFriend[friend.setlistfm]?.count ?? 0) shows")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        .swipeActions {
                            Button(role: .destructive) { model.removeFriend(friend) } label: {
                                Label("Remove", systemImage: "trash")
                            }
                        }
                    }
                }
            }

            Section {
                Button("Save") { model.saveSettings(apiKey: apiKey, clientId: clientId) }
            }

            // The whole of #142 from this side: one deliberate act, on the phone
            // being replaced, that never runs by itself.
            Section {
                NavigationLink("Move to a new phone") { HandoverView() }
            }

            // #30 field-test: dev-only screen, not part of the shipped feature set.
            Section {
                NavigationLink("GATT card probe (#30 field test)") { BleProbeView() }
            }
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            apiKey = s.setlistFmApiKey
            clientId = s.spotifyClientId
            clashfinderUser = s.clashfinderUser
            clashfinderPrivateKey = s.clashfinderPrivateKey
        }
    }

    private func scopeMessage(_ scope: String?) -> String {
        guard let scope else { return "Granted permissions unknown — log out and log in again." }
        return scope.contains("playlist-modify")
            ? "Playlist permissions granted (\(scope))"
            : "⚠ Playlist permissions MISSING (\(scope)) — log out and log in again."
    }
}
