import SwiftUI

struct SearchView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var nav: Nav
    @State private var tab = 0

    var body: some View {
        let s = model.state
        VStack(spacing: 0) {
            if !s.setlistFmReady {
                Button { nav.push(.settings) } label: {
                    Text("Add your setlist.fm API key in Settings to get started.")
                        .font(.subheadline)
                        .foregroundStyle(.red)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding()
                }
            }
            if !s.spotifyConnected {
                Button {
                    if s.spotifyLoginReady { model.loginSpotify() } else { nav.push(.settings) }
                } label: {
                    Text("Log in with Spotify").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(spotifyGreen)
                .padding(.horizontal).padding(.vertical, 8)
            }
            Picker("", selection: $tab) {
                Text("Search artist").tag(0)
                Text("My concerts").tag(1)
            }
            .pickerStyle(.segmented)
            .padding()

            if tab == 0 { ArtistTab() } else { UserTab() }
        }
        .navigationTitle("Station to Station")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                // The Timeline is home now; this returns to it.
                Button { nav.popToRoot() } label: { Image(systemName: "calendar") }
                Button { nav.push(.friends) } label: { Image(systemName: "person.2") }
                Button { nav.push(.settings) } label: { Image(systemName: "gearshape") }
            }
        }
    }
}

private struct ArtistTab: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var nav: Nav

    var body: some View {
        let s = model.state
        VStack(alignment: .leading) {
            HStack {
                TextField("Artist name", text: Binding(
                    get: { s.artistQuery }, set: model.setArtistQuery))
                    .textFieldStyle(.roundedBorder)
                    .autocorrectionDisabled()
                    .onSubmit { model.searchArtists() }
                Button { model.searchArtists() } label: { Image(systemName: "magnifyingglass") }
            }
            if s.searchLoading {
                ProgressView().frame(maxWidth: .infinity).padding()
            }
            List(s.artistResults) { artist in
                VStack(alignment: .leading) {
                    Text(artist.name)
                    if let d = artist.disambiguation?.nilIfBlank {
                        Text(d).font(.caption).foregroundStyle(.secondary)
                    }
                }
                .contentShape(Rectangle())
                .onTapGesture { model.openArtist(artist); nav.push(.setlists) }
            }
            .listStyle(.plain)
        }
        .padding(.horizontal)
    }
}

private struct UserTab: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var nav: Nav
    @State private var addingLocal = false

    var body: some View {
        let s = model.state
        VStack(alignment: .leading, spacing: 12) {
            Text("Load the concerts you marked as attended on setlist.fm. Enter your "
                + "setlist.fm username — the setlist.fm API has no app login, so no "
                + "password is needed here.")
                .font(.subheadline)
            HStack {
                TextField("setlist.fm user ID", text: Binding(
                    get: { s.userQuery }, set: model.setUserQuery))
                    .textFieldStyle(.roundedBorder)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .onSubmit { model.openUserAttended(); nav.push(.setlists) }
                Button {
                    model.openUserAttended(); nav.push(.setlists)
                } label: { Image(systemName: "magnifyingglass") }
            }
            Link("Forgot your username? Sign in on setlist.fm (Google login supported)",
                 destination: URL(string: "https://www.setlist.fm/signin")!)
                .font(.subheadline)
            Divider()
            // The one way in that does not end at setlist.fm, and the only one a
            // person without an account can act on (#347). It lives here because this
            // is the import surface — where Android's `ImportScreen` keeps it — and
            // the cold start is the other place it is offered.
            Button("Or type in a night you were at") { addingLocal = true }
                .font(.subheadline)
            Text("The poster in the window, the small venue nobody catalogues.")
                .font(.caption).foregroundStyle(.secondary)
            Spacer()
        }
        .padding(.horizontal)
        .sheet(isPresented: $addingLocal) {
            AddLocalGigSheet { artist, venue, date in
                model.addLocalGig(artist: artist, venue: venue, date: date)
                addingLocal = false
                nav.popToRoot()
            } onCancel: { addingLocal = false }
        }
    }
}
