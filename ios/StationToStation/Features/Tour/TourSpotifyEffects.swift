import Foundation

/// The retry keeps songs, not a demo Gig. Real playlist receipts outlive every Demo world.
@MainActor
final class TourSpotifyEffects {
    private struct Export: Codable {
        var artist: String
        var songs: [String]
    }

    private static let retryKey = "tour.spotifyExport"
    private static let playlistsKey = "tour.spotifyPlaylists"
    private let host: StateHost
    private let store: UserDefaults
    private let settings: Settings
    private let playlist: PlaylistController
    private let login: SpotifyLogin
    private let characterLine: (String) -> String
    private var operation: UUID?

    init(host: StateHost, store: UserDefaults = .standard, settings: Settings,
         playlist: PlaylistController, login: SpotifyLogin,
         characterLine: @escaping (String) -> String) {
        self.host = host
        self.store = store
        self.settings = settings
        self.playlist = playlist
        self.login = login
        self.characterLine = characterLine
    }

    var coachLine: String { characterLine(TourStep.s19.rawValue) }

    var playlists: [StoredPlaylist] {
        guard let data = store.data(forKey: Self.playlistsKey),
              let held = try? JSONDecoder().decode([StoredPlaylist].self, from: data) else { return [] }
        return held
    }

    private var retry: Export? {
        guard let data = store.data(forKey: Self.retryKey) else { return nil }
        return try? JSONDecoder().decode(Export.self, from: data)
    }

    func prepare(for gig: FmSetlist, log: StoredLog) {
        guard operation == nil else { return }
        let export = Export(artist: gig.artist?.name ?? "",
                            songs: dedupe(log.songs).filter { $0.nilIfBlank != nil })
        if let data = try? JSONEncoder().encode(export) { store.set(data, forKey: Self.retryKey) }
    }

    func export() async -> TourEvent? {
        guard operation == nil else { return nil }
        let token = UUID()
        operation = token
        host.state.creatingPlaylist = true
        defer {
            if operation == token {
                operation = nil
                host.state.creatingPlaylist = false
            }
        }
        do {
            guard let retry, !retry.songs.isEmpty else { throw AppError("No songs selected") }
            let title = characterLine("playlist.title")
            let description = characterLine("playlist.description")
            guard title.nilIfBlank != nil, description.nilIfBlank != nil else {
                throw AppError("The Tour playlist text is not available yet.")
            }
            if !host.state.spotifyConnected {
                try await login.login()
                host.state.spotifyConnected = true
                host.state.grantedScope = settings.grantedScope
            }
            guard operation == token, !Task.isCancelled else { return nil }
            let setlist = FmSetlist(artist: FmArtist(name: retry.artist),
                                   sets: FmSets(set: [FmSet(song: retry.songs.map { FmSong(name: $0) })]))
            let made = try await playlist.exportSetlist(setlist, name: title, description: description)
            let held = playlists + [made]
            if let data = try? JSONEncoder().encode(held) { store.set(data, forKey: Self.playlistsKey) }
            guard operation == token, !Task.isCancelled else { return nil }
            store.removeObject(forKey: Self.retryKey)
            return .spotifyExported
        } catch {
            guard operation == token, !Task.isCancelled else { return nil }
            host.fail(error)
            return .spotifyDeclined
        }
    }

    func cancel() {
        operation = nil
        host.state.creatingPlaylist = false
    }

    func reset() {
        cancel()
        store.removeObject(forKey: Self.retryKey)
    }
}
