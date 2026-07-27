import SwiftUI

struct FriendsView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var nav: Nav
    @State private var addQuery = ""
    @State private var linkQuery = ""
    @State private var cardURL: URL?

    var body: some View {
        let s = model.state
        List {
            Section {
                Text("Needed to find concerts you and a friend both attended, and to "
                    + "share your friend card. Friends aren't on any server — you swap "
                    + "cards directly.")
                    .font(.caption).foregroundStyle(.secondary)
                HStack {
                    TextField("setlist.fm username", text: Binding(
                        get: { s.mySetlistFmUser }, set: model.saveMySetlistFmUser))
                        .textFieldStyle(.roundedBorder)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    ShareLink(item: cardURL ?? URL(string: "setlist2spotify://friend")!) {
                        Image(systemName: "square.and.arrow.up")
                    }
                    .disabled(cardURL == nil)
                }
            } header: {
                Text("Your setlist.fm username")
            }

            Section {
                HStack {
                    TextField("Add friend by setlist.fm username", text: $addQuery)
                        .textFieldStyle(.roundedBorder)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    Button("Add") { model.addFriendByUsername(addQuery); addQuery = "" }
                        .buttonStyle(.bordered)
                }
                HStack {
                    TextField("Add from a shared Spotify playlist link", text: $linkQuery)
                        .textFieldStyle(.roundedBorder)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    Button("Find") { model.discoverFriendFromPlaylist(linkQuery); linkQuery = "" }
                        .buttonStyle(.bordered)
                }
            }

            if s.friends.isEmpty {
                Text("No friends yet. Share your card, open a friend's card link, or add "
                    + "one by username above. Tap a friend to see concerts you both attended.")
                    .font(.body).foregroundStyle(.secondary)
            } else {
                Section("Friends") {
                    ForEach(s.friends) { friend in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(friend.name)
                                Text("@\(friend.setlistfm)").font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                        }
                        .contentShape(Rectangle())
                        .onTapGesture { model.openSharedConcerts(friend); nav.push(.setlists) }
                        .swipeActions {
                            Button(role: .destructive) { model.removeFriend(friend) } label: {
                                Label("Remove", systemImage: "trash")
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Friends")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: s.mySetlistFmUser) {
            guard !s.mySetlistFmUser.trimmingCharacters(in: .whitespaces).isEmpty else {
                cardURL = nil; return
            }
            // Debounce: .task cancels on each keystroke, so this only fires once typing settles.
            try? await Task.sleep(nanoseconds: 400_000_000)
            if Task.isCancelled { return }
            cardURL = await model.myCardURL()
        }
    }
}
