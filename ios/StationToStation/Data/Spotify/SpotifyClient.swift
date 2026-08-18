import Foundation
import CryptoKit
import AuthenticationServices
import UIKit

let spotifyRedirectURI = "station-to-station://callback"
private let spotifyScopes = "playlist-modify-public playlist-modify-private user-read-private ugc-image-upload"

struct AddTracksResult { let added: Int; let refused: [String] }

final class SpotifyClient {

    private let settings: Settings
    private let decoder = JSONDecoder()
    private var authSession: ASWebAuthenticationSession?
    private let authAnchor = AuthAnchor()

    init(_ settings: Settings) { self.settings = settings }

    // --- OAuth (Authorization Code with PKCE) ---
    //
    // Android launches a browser and catches the station-to-station://callback deep
    // link. iOS has ASWebAuthenticationSession, which runs the whole round trip
    // and hands back the callback URL directly — no URL-scheme plumbing for the
    // redirect, and the sheet auto-dismisses.

    @MainActor
    func login() async throws {
        guard let clientId = settings.spotifyClientIdValue else {
            throw AppError("Spotify Client ID is not configured. Set it in Settings.")
        }
        let verifier = generateCodeVerifier()
        settings.savePkceVerifier(verifier)
        var comps = URLComponents(string: "https://accounts.spotify.com/authorize")!
        comps.queryItems = [
            .init(name: "client_id", value: clientId),
            .init(name: "response_type", value: "code"),
            .init(name: "redirect_uri", value: spotifyRedirectURI),
            .init(name: "code_challenge_method", value: "S256"),
            .init(name: "code_challenge", value: codeChallenge(verifier)),
            .init(name: "scope", value: spotifyScopes),
            // Always show consent so a stale grant without playlist scopes can't
            // be silently reused.
            .init(name: "show_dialog", value: "true"),
        ]
        let callback = try await authenticate(url: comps.url!)
        let cc = URLComponents(url: callback, resolvingAgainstBaseURL: false)
        if let error = cc?.queryItems?.first(where: { $0.name == "error" })?.value {
            throw AppError("Spotify login failed: \(error)")
        }
        guard let code = cc?.queryItems?.first(where: { $0.name == "code" })?.value else {
            throw AppError("Spotify login returned no authorization code.")
        }
        try await exchangeCodeForTokens(code)
    }

    @MainActor
    private func authenticate(url: URL) async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            let session = ASWebAuthenticationSession(
                url: url, callbackURLScheme: "station-to-station"
            ) { callbackURL, error in
                if let callbackURL {
                    continuation.resume(returning: callbackURL)
                } else if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(throwing: AppError("Spotify login was cancelled."))
                }
            }
            session.presentationContextProvider = authAnchor
            session.prefersEphemeralWebBrowserSession = false
            authSession = session
            session.start()
        }
    }

    private func exchangeCodeForTokens(_ code: String) async throws {
        guard let clientId = settings.spotifyClientIdValue else {
            throw AppError("Spotify Client ID is not configured.")
        }
        guard let verifier = settings.pkceVerifier else {
            throw AppError("Login session expired. Start the Spotify login again.")
        }
        let token = try await requestToken([
            "grant_type": "authorization_code",
            "code": code,
            "redirect_uri": spotifyRedirectURI,
            "client_id": clientId,
            "code_verifier": verifier,
        ])
        settings.saveTokens(access: token.accessToken, refresh: token.refreshToken,
                            expiresIn: token.expiresIn, scope: token.scope)
    }

    private func refreshAccessToken() async throws -> String {
        guard let clientId = settings.spotifyClientIdValue else {
            throw AppError("Spotify Client ID is not configured.")
        }
        guard let refresh = settings.refreshTokenValue else {
            throw AppError("Not connected to Spotify. Connect in Settings.")
        }
        let token = try await requestToken([
            "grant_type": "refresh_token",
            "refresh_token": refresh,
            "client_id": clientId,
        ])
        settings.saveTokens(access: token.accessToken, refresh: token.refreshToken,
                            expiresIn: token.expiresIn, scope: token.scope)
        return token.accessToken
    }

    /// Nil when unknown (logins predating scope persistence).
    func hasPlaylistScopes() -> Bool? {
        settings.grantedScope.map { $0.contains("playlist-modify") }
    }

    /// Cover upload needs a scope the app did not always ask for, so a login
    /// made before covers existed can create playlists but not illustrate them.
    func hasImageUploadScope() -> Bool {
        settings.grantedScope?.contains("ugc-image-upload") == true
    }

    private func requestToken(_ fields: [String: String]) async throws -> TokenResponse {
        var request = URLRequest(url: URL(string: "https://accounts.spotify.com/api/token")!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = formEncode(fields).data(using: .utf8)
        let (data, response) = try await URLSession.shared.data(for: request)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200...299).contains(code) else {
            throw AppError("Spotify token request failed (\(code)): \(String(data: data, encoding: .utf8) ?? "")")
        }
        return try decoder.decode(TokenResponse.self, from: data)
    }

    private func accessToken() async throws -> String {
        if let token = settings.validAccessToken { return token }
        return try await refreshAccessToken()
    }

    var isConnected: Bool { settings.refreshTokenValue != nil }

    // --- Web API ---

    private func call(_ build: (inout URLRequest) -> Void) async throws -> Data {
        let token = try await accessToken()
        var request = URLRequest(url: URL(string: "https://api.spotify.com")!)
        build(&request)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (data, response) = try await URLSession.shared.data(for: request)
        let http = response as? HTTPURLResponse
        let code = http?.statusCode ?? 0
        let path = request.url?.path ?? ""
        let method = request.httpMethod ?? "GET"
        guard (200...299).contains(code) else {
            let body = String(data: data, encoding: .utf8) ?? ""
            if code == 401 {
                throw AppError("Spotify session expired. Reconnect in Settings.")
            }
            if code == 403 {
                // Spotify's bare "Forbidden" body says nothing; the headers
                // distinguish a scope refusal from an edge/CDN block.
                let detail = ["www-authenticate", "server", "x-robots-tag"]
                    .compactMap { name in (http?.value(forHTTPHeaderField: name)).map { "\(name): \($0)" } }
                    .joined(separator: "; ")
                throw SpotifyForbidden(
                    "Spotify refused \(method) \(path) (403). \(body)"
                        + (detail.isEmpty ? "" : " [\(detail)]"))
            }
            throw AppError("Spotify API error \(code) on \(method) \(path): \(body)")
        }
        return data
    }

    func searchTracks(_ query: String, limit: Int = 5) async throws -> [SpotifyTrack] {
        var comps = URLComponents(string: "https://api.spotify.com/v1/search")!
        comps.queryItems = [
            .init(name: "q", value: query),
            .init(name: "type", value: "track"),
            .init(name: "limit", value: "\(limit)"),
        ]
        let url = comps.url!
        let data = try await call { $0.url = url }
        return try decoder.decode(TrackSearchResponse.self, from: data).tracks?.items ?? []
    }

    func createPlaylist(name: String, description: String, isPublic: Bool) async throws -> PlaylistResponse {
        let payload: [String: Any] = ["name": name, "description": description, "public": isPublic]
        let body = try JSONSerialization.data(withJSONObject: payload)
        // /me/playlists avoids the user-id round trip and the 403s the
        // /users/{id}/playlists endpoint gives on any id mismatch.
        let data = try await call {
            $0.url = URL(string: "https://api.spotify.com/v1/me/playlists")!
            $0.httpMethod = "POST"
            $0.setValue("application/json", forHTTPHeaderField: "Content-Type")
            $0.httpBody = body
        }
        return try decoder.decode(PlaylistResponse.self, from: data)
    }

    func currentUser() async throws -> SpotifyUser {
        let data = try await call { $0.url = URL(string: "https://api.spotify.com/v1/me")! }
        return try decoder.decode(SpotifyUser.self, from: data)
    }

    /// Reads a playlist by id — used to harvest the creator's setlist.fm stamp from a shared link.
    func getPlaylist(_ playlistId: String) async throws -> SimplePlaylist {
        var comps = URLComponents(string: "https://api.spotify.com/v1/playlists/\(playlistId)")!
        comps.queryItems = [.init(name: "fields", value: "id,name,description,owner(id,display_name)")]
        let url = comps.url!
        let data = try await call { $0.url = url }
        return try decoder.decode(SimplePlaylist.self, from: data)
    }

    /// Facts that explain a residual 403 while modifying a playlist we just made.
    private func diagnostics(_ playlistId: String) async -> String {
        do {
            let me = try await currentUser()
            let data = try await call {
                $0.url = URL(string: "https://api.spotify.com/v1/playlists/\(playlistId)?fields=owner(id),public,collaborative")!
            }
            let playlist = String(data: data, encoding: .utf8) ?? ""
            return "me=\(me.id) product=\(me.product ?? "?") playlist=\(playlist) scopes=\(settings.grantedScope ?? "nil")"
        } catch {
            return "diagnostics unavailable: \(userMessage(error))"
        }
    }

    /// Fills a freshly created playlist via POST /playlists/{id}/items. The old
    /// /tracks path was renamed in Spotify's Feb 2026 API migration and now 403s
    /// from the edge (envoy, no www-authenticate); /items takes the same body.
    func addTracks(_ playlistId: String, uris: [String]) async throws -> AddTracksResult {
        var seen = Set<String>()
        let clean = uris.filter { $0.hasPrefix("spotify:track:") && seen.insert($0).inserted }
        if clean.isEmpty { throw AppError("No valid Spotify track URIs to add.") }
        let url = URL(string: "https://api.spotify.com/v1/playlists/\(playlistId)/items")!
        do {
            for chunk in clean.chunked(into: 100) {
                let body = try JSONSerialization.data(withJSONObject: ["uris": chunk])
                _ = try await call {
                    $0.url = url
                    $0.httpMethod = "POST"
                    $0.setValue("application/json", forHTTPHeaderField: "Content-Type")
                    $0.httpBody = body
                }
            }
        } catch let e as SpotifyForbidden {
            throw SpotifyForbidden("\(e.message) | \(await diagnostics(playlistId))")
        }
        return AddTracksResult(added: clean.count, refused: [])
    }

    /// Sets the playlist cover. Spotify takes the JPEG base64-encoded as the raw
    /// body under an image/jpeg content type — not multipart, and not wrapped in
    /// JSON — and answers 202 with nothing in the body.
    func uploadCover(_ playlistId: String, jpeg: Data) async throws {
        let body = jpeg.base64EncodedData()
        _ = try await call {
            $0.url = URL(string: "https://api.spotify.com/v1/playlists/\(playlistId)/images")!
            $0.httpMethod = "PUT"
            $0.setValue("image/jpeg", forHTTPHeaderField: "Content-Type")
            $0.httpBody = body
        }
    }
}

// --- PKCE helpers ---

private func generateCodeVerifier() -> String {
    var bytes = [UInt8](repeating: 0, count: 64)
    _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
    return base64URL(Data(bytes))
}

private func codeChallenge(_ verifier: String) -> String {
    base64URL(Data(SHA256.hash(data: Data(verifier.utf8))))
}

private func base64URL(_ data: Data) -> String {
    data.base64EncodedString()
        .replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_")
        .replacingOccurrences(of: "=", with: "")
}

private func formEncode(_ fields: [String: String]) -> String {
    var allowed = CharacterSet.alphanumerics
    allowed.insert(charactersIn: "-._~")
    return fields.map { k, v in
        "\(k)=\(v.addingPercentEncoding(withAllowedCharacters: allowed) ?? v)"
    }.joined(separator: "&")
}

/// Presentation anchor for ASWebAuthenticationSession.
private final class AuthAnchor: NSObject, ASWebAuthenticationPresentationContextProviding {
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.keyWindow }
            .first ?? ASPresentationAnchor()
    }
}

extension Array {
    func chunked(into size: Int) -> [[Element]] {
        stride(from: 0, to: count, by: size).map { Array(self[$0 ..< Swift.min($0 + size, count)]) }
    }
}
