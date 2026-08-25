import Foundation

/// Mirrors the retry behaviour of the Python CLI and the Android client: retry on
/// 429/5xx with exponential backoff, fail fast on other HTTP errors.
final class SetlistFmClient {

    private let apiKeyProvider: () -> String?
    private let decoder = JSONDecoder()

    init(apiKeyProvider: @escaping () -> String?) {
        self.apiKeyProvider = apiKeyProvider
    }

    private func get(
        _ path: String,
        params: [String: String?],
        // setlist.fm returns 404, not an empty 200, for a real user whose attended
        // list has zero shows — a brand-new account looks identical to a typo'd
        // username unless this call site is told to read that 404 as "no shows"
        // rather than "no such user".
        notFoundIsEmpty: Bool = false
    ) async throws -> Data {
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
            case 404 where notFoundIsEmpty: return Data(#"{"total":0}"#.utf8)
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
            get("user/\(userId)/attended", params: ["p": "\(page)"], notFoundIsEmpty: true))
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

    /// The **Festival** a setlist belongs to — the identity and the name. Nil when
    /// that night belongs to no festival, which is the common and correct answer.
    ///
    /// setlist.fm models festivals as a first-class entity but does not expose them in
    /// the REST API. The setlist's own web page links to `/festival/<year>/<slug>.html`
    /// and the slug in that href is the vendor's own key — the same across every act
    /// and every year's edition — which is what a stored identity is derived from.
    /// MusicBrainz has festival events too and needs no key, but its coverage is
    /// patchy, so it can't be the primary source.
    ///
    /// **ponytail: the setlist page only.** Android follows the link and reads the
    /// range, the day grouping and the set times off the festival page as well. Here
    /// those three come back nil, which is exactly what ADR-0004 asks of a field that
    /// could not be read — an identity still lands, a **Node** still says
    /// "Øyafestivalen 2025", and membership falls back to the **Gigs** carrying the
    /// id. Port `parseFestivalPage` when this side needs the running order too.
    func festivalAt(setlistURL: String) async -> ScrapedFestival? {
        guard let url = URL(string: setlistURL) else { return nil }
        // Same IPv4 forcing as the API — the setlist.fm website is behind the same
        // CloudFront. Best-effort: any failure leaves the question open.
        guard let resp = try? await IPv4Https.get(url: url, headers: ["Accept": "text/html"]),
              (200...299).contains(resp.status),
              let html = String(data: resp.body, encoding: .utf8)
        else { return nil }
        return parseFestivalLink(html)
    }
}

/// A **Festival** as setlist.fm's pages give it up, every field independently nullable.
///
/// Not a `StoredFestival`: this is what was *read*, and turning it into an identity the
/// app owns — minting the local id, deciding it is scraped rather than authored — is
/// the logic layer's job and not the scraper's.
struct ScrapedFestival: Equatable {
    var name: String?
    /// e.g. `tons-of-rock-2026-6bd52ece`, out of the href — the vendor's own key.
    var slug: String?
    /// Where the festival page is, relative to the setlist page.
    var href: String?
    /// dd-MM-yyyy, the shape setlist.fm sends everywhere else in this app.
    var rangeFrom: String?
    var rangeTo: String?
    /// dd-MM-yyyy to the setlist.fm ids the source says played that day.
    var dayMembership: [String: [String]]?
    /// Setlist.fm id to `HH:mm`, for the acts whose start time was published.
    var setTimes: [String: String]?
}

/// The "played at a festival" link on a setlist page: title="View &lt;name&gt; details".
private let festivalLink =
    try! Regex(#"href="([^"]*?/festival/\d{4}/([^"/]+)\.html)"\s+title="View (.+?) detail"#)

/// The identity and the name, off the setlist page. Nil when the page carries no
/// festival link at all, which is what "this was not a festival" looks like.
func parseFestivalLink(_ html: String) -> ScrapedFestival? {
    guard let m = html.firstMatch(of: festivalLink) else { return nil }
    func group(_ i: Int) -> String? {
        guard let s = m.output[i].substring else { return nil }
        return String(s).trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank
    }
    return ScrapedFestival(name: group(3), slug: group(2), href: group(1))
}

/// Kept for the one fact the label used to be: see `parseFestivalLink`.
func parseFestivalName(_ html: String) -> String? { parseFestivalLink(html)?.name }
