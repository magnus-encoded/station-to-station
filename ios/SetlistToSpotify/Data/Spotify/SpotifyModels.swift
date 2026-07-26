import Foundation

struct TokenResponse: Decodable {
    @DefaultCodable<EmptyString> var accessToken = ""
    @DefaultCodable<ExpiresDefault> var expiresIn: Double = 3600
    var refreshToken: String?
    @DefaultCodable<EmptyString> var scope = ""

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case expiresIn = "expires_in"
        case refreshToken = "refresh_token"
        case scope
    }
}

struct SpotifyUser: Decodable {
    @DefaultCodable<EmptyString> var id = ""
    var displayName: String?
    var product: String?

    enum CodingKeys: String, CodingKey {
        case id
        case displayName = "display_name"
        case product
    }
}

struct TrackSearchResponse: Decodable {
    var tracks: TrackPage?
}

struct TrackPage: Decodable {
    @DefaultCodable<EmptyArray<SpotifyTrack>> var items: [SpotifyTrack] = []
}

struct SpotifyTrack: Decodable, Identifiable {
    @DefaultCodable<EmptyString> var id = ""
    @DefaultCodable<EmptyString> var name = ""
    @DefaultCodable<EmptyString> var uri = ""
    @DefaultCodable<EmptyArray<SpotifyArtist>> var artists: [SpotifyArtist] = []
    var album: SpotifyAlbum?
    @DefaultCodable<LongZero> var durationMs: Int64 = 0

    enum CodingKeys: String, CodingKey {
        case id, name, uri, artists, album
        case durationMs = "duration_ms"
    }

    func artistNames() -> String { artists.map(\.name).joined(separator: ", ") }
}

struct SpotifyArtist: Decodable {
    @DefaultCodable<EmptyString> var name = ""
}

struct SpotifyAlbum: Decodable {
    var name: String?
}

struct PlaylistResponse: Decodable {
    @DefaultCodable<EmptyString> var id = ""
    @DefaultCodable<EmptyStringMap> var externalUrls: [String: String] = [:]

    enum CodingKeys: String, CodingKey {
        case id
        case externalUrls = "external_urls"
    }
}

/// A playlist as returned by GET /playlists/{id}; carries who made it and its description.
struct SimplePlaylist: Decodable {
    @DefaultCodable<EmptyString> var id = ""
    @DefaultCodable<EmptyString> var name = ""
    var description: String?
    var owner: SpotifyUser?
}
