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
        let url = comps.url!
        let headers = ["x-api-key": apiKey, "Accept": "application/json"]

        var backoff: UInt64 = 1_000_000_000 // 1s in ns
        let maxAttempts = 3
        for attempt in 1...maxAttempts {
            // Forced over IPv4 (see IPv4Https): setlist.fm's IPv6/CloudFront edge
            // returns 406 to everything, and iOS's URLSession prefers IPv6.
            let resp = try await IPv4Https.get(url: url, headers: headers)
            switch resp.status {
            case 200...299: return resp.body
            case 429, 500...599: break // retry
            case 404: throw AppError("Not found (404). Check the name/ID and try again.")
            case 403: throw AppError("setlist.fm rejected the API key (403).")
            default:
                let body = String(data: resp.body, encoding: .utf8)?
                    .trimmingCharacters(in: .whitespacesAndNewlines).prefix(200) ?? ""
                throw AppError("setlist.fm error \(resp.status)\(body.isEmpty ? "" : ": \(body)")")
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

    /// One setlist, fresh — for when it was just edited on setlist.fm, and the only
    /// way a gig that has not happened yet can be fetched at all. See `parseSetlistId`.
    func setlist(_ setlistId: String) async throws -> FmSetlist {
        try await decoder.decode(FmSetlist.self, from: get("setlist/\(setlistId)", params: [:]))
    }

    /// Someone's Attended list, paged back through their history.
    ///
    /// setlist.fm returns newest first, so a flat page cap is a *window*, not a
    /// sample: a friend's first 60 shows can span ten days, and every night we
    /// actually shared would be older than the last fetched page — the lines
    /// could never meet however correct the drawing was. `backTo` is normally my
    /// own oldest gig, since nothing older than that can overlap.
    ///
    /// ponytail: `maxPages` is a runaway guard, not a policy.
    func attendedShows(
        _ userId: String,
        backTo: Date? = nil,
        maxPages: Int = 25
    ) async throws -> (shows: [FmSetlist], total: Int) {
        var all: [FmSetlist] = []
        var total = 0
        for page in 1...max(1, maxPages) {
            let resp = try await userAttended(userId, page: page)
            all += resp.setlist
            total = resp.total
            if all.count >= resp.total || resp.setlist.isEmpty { break }
            if let backTo, let pageOldest = resp.setlist.compactMap({ $0.localDate() }).min(),
               pageOldest < backTo { break }
        }
        return (all, total)
    }

    /// The Festival a setlist belongs to, e.g. "Øyafestivalen 2025" for a show
    /// whose venue is only "Tøyenparken".
    ///
    /// setlist.fm models festivals as a first-class entity but does not expose
    /// them in the REST API — the name lives only on the setlist's own web page,
    /// which links to `/festival/<year>/<slug>.html`. MusicBrainz has festival
    /// events too and needs no key, but its coverage is patchy, so it can't be
    /// the primary source.
    ///
    /// Returns nil on anything unexpected — the caller falls back to the venue name.
    func festivalName(setlistURL: String) async -> String? {
        guard let url = URL(string: setlistURL) else { return nil }
        // Same IPv4 forcing as the API — the setlist.fm website is behind the same
        // CloudFront. Best-effort: any failure just leaves the venue name.
        guard let resp = try? await IPv4Https.get(url: url, headers: ["Accept": "text/html"]),
              (200...299).contains(resp.status),
              let html = String(data: resp.body, encoding: .utf8)
        else { return nil }
        return parseFestivalName(html)
    }
}

/// The "played at a festival" link on a setlist page: title="View &lt;name&gt; details".
private let festivalLink = try! Regex(#"href="[^"]*?/festival/\d{4}/[^"]+"\s+title="View (.+?) detail"#)

func parseFestivalName(_ html: String) -> String? {
    guard let m = html.firstMatch(of: festivalLink), let group = m.output[1].substring
    else { return nil }
    return String(group).trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank
}
