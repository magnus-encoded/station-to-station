import Foundation

/// Mirrors the retry behaviour of the Python CLI and the Android client: retry on
/// 429/5xx with exponential backoff, fail fast on other HTTP errors.
final class SetlistFmClient {

    private let apiKeyProvider: () -> String?
    private let decoder = JSONDecoder()

    init(apiKeyProvider: @escaping () -> String?) {
        self.apiKeyProvider = apiKeyProvider
    }

    private func get(_ path: String, params: [String: String?]) async throws -> Data {
        guard let apiKey = apiKeyProvider() else {
            throw AppError("setlist.fm API key is not configured. Set it in Settings.")
        }
        var comps = URLComponents(string: "https://api.setlist.fm/rest/1.0/\(path)")!
        comps.queryItems = params.compactMap { k, v in v.map { URLQueryItem(name: k, value: $0) } }
        var request = URLRequest(url: comps.url!)
        request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        var backoff: UInt64 = 1_000_000_000 // 1s in ns
        let maxAttempts = 3
        for attempt in 1...maxAttempts {
            let (data, response) = try await URLSession.shared.data(for: request)
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            switch code {
            case 200...299: return data
            case 429, 500...599: break // retry
            case 404: throw AppError("Not found (404). Check the name/ID and try again.")
            case 403: throw AppError("setlist.fm rejected the API key (403).")
            default: throw AppError("setlist.fm error \(code)")
            }
            if attempt == maxAttempts { break }
            try await Task.sleep(nanoseconds: backoff)
            backoff *= 2
        }
        throw AppError("setlist.fm is rate limiting or unavailable. Try again in a moment.")
    }

    func searchArtists(_ name: String, page: Int = 1) async throws -> ArtistSearchResponse {
        try await decoder.decode(ArtistSearchResponse.self, from:
            get("search/artists", params: ["artistName": name, "p": "\(page)", "sort": "relevance"]))
    }

    func artistSetlists(_ mbid: String, page: Int = 1) async throws -> SetlistsResponse {
        try await decoder.decode(SetlistsResponse.self, from:
            get("artist/\(mbid)/setlists", params: ["p": "\(page)"]))
    }

    func userAttended(_ userId: String, page: Int = 1) async throws -> SetlistsResponse {
        try await decoder.decode(SetlistsResponse.self, from:
            get("user/\(userId)/attended", params: ["p": "\(page)"]))
    }
}
