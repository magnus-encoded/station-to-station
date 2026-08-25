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

    /// The **Festival** a setlist belongs to — the identity, the name, the range, which
    /// acts played which day, and when each of them went on. Nil when that night
    /// belongs to no festival, which is the common and correct answer.
    ///
    /// setlist.fm models festivals as a first-class entity but does not expose them in
    /// the REST API. The setlist's own web page links to `/festival/<year>/<slug>.html`,
    /// and that page carries the rest. MusicBrainz has festival events too and needs no
    /// key, but its coverage is patchy, so it can't be the primary source.
    ///
    /// **Two pages, one per festival, paid once.** The volume is unchanged in the way
    /// that matters: a night is asked about once ever, and the second fetch only
    /// happens for a night that turned out to be at a festival at all.
    ///
    /// Everything degrades independently, per ADR-0004: a festival page that cannot be
    /// read at all still yields the identity and the name off the setlist page.
    /// Term for term with Android's `festivalAt`.
    func festivalAt(setlistURL: String) async -> ScrapedFestival? {
        guard let setlistHtml = await html(setlistURL),
              var found = parseFestivalLink(setlistHtml)
        else { return nil }
        guard let href = found.href,
              let pageURL = URL(string: href, relativeTo: URL(string: setlistURL))?.absoluteURL,
              let pageHtml = await html(pageURL.absoluteString)
        else { return found }
        // The name off the setlist page is the one we came for; the festival page's own
        // <h1> is a second opinion on it, never a replacement for the identity.
        let page = parseFestivalPage(pageHtml)
        found.rangeFrom = page.rangeFrom
        found.rangeTo = page.rangeTo
        found.dayMembership = page.dayMembership
        found.setTimes = page.setTimes
        return found
    }

    /// One page, as text. Nil on anything at all going wrong — see `festivalAt`. Same
    /// IPv4 forcing as the API: the setlist.fm website is behind the same CloudFront.
    private func html(_ urlString: String) async -> String? {
        guard let url = URL(string: urlString),
              let resp = try? await IPv4Https.get(url: url, headers: ["Accept": "text/html"]),
              (200...299).contains(resp.status)
        else { return nil }
        return String(data: resp.body, encoding: .utf8)
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

// MARK: - The festival page
//
// Three facts and three shapes, each read on its own so that a redesign upstream costs
// the field it touched and never the night. A pure function over a string: it holds
// nothing and touches no device, which is why it lives at the logic layer under
// ADR-0001 and is asserted by the same cases as Android's `parseFestivalPage`.

/// `<div class="condensed dateBlock dtstart">…<span class="value-title" title="2026-06-24">`.
private let dtStart =
    try! Regex(#"(?s)dateBlock dtstart.{0,400}?value-title" title="(\d{4}-\d{2}-\d{2})""#)

/// The human range beside it: `<span>Wed June 24, 2026 - Sat June 27, 2026</span>`.
private let pageRangeSpan =
    try! Regex(#"<span>\w{3} (\w+ \d{1,2}, \d{4}) - \w{3} (\w+ \d{1,2}, \d{4})</span>"#)

/// `<p class="…GroupedVenueDayBySubVenue-eventDate …">Wednesday, June 24, 2026</p>`.
private let dayHeading = "GroupedVenueDayBySubVenue-eventDate"
private let dayDate = try! Regex(#"^[^>]*>([^<]+)<"#)

/// One act's row on the day's list, with the scheduled start where there is one.
private let dayItem = "FestivalSetlistListItem-root"
private let itemTime = try! Regex(#"(?s)scheduledStart.{0,600}?<p[^>]*>([^<]+)</p>"#)
private let itemSetlist = try! Regex(#"/setlist/[^"]*?-([0-9a-f]{5,10})\.html"#)

private let pageDayFormat = fmFormatter("EEEE, MMMM d, yyyy")
private let pageRangeFormat = fmFormatter("MMMM d, yyyy")
private let fmDateFormat = fmFormatter("dd-MM-yyyy")
private let pageTimeFormat = fmFormatter("h:mm a")
private let twentyFourHour = fmFormatter("HH:mm")

/// The range, the day grouping and the set times off a festival page. Every field is
/// whatever could be read and nil otherwise — a page shaped wrong yields nothing rather
/// than nonsense, and one unreadable field never takes the others with it.
func parseFestivalPage(_ html: String) -> ScrapedFestival {
    let range = html.firstMatch(of: pageRangeSpan)
    var days: [String: [String]] = [:]
    var times: [String: String] = [:]
    for chunk in html.components(separatedBy: dayHeading).dropFirst() {
        let date = chunk.firstMatch(of: dayDate)
            .flatMap { group($0, 1) }
            .flatMap { fmDate($0, pageDayFormat) }
        var ids: [String] = []
        for item in chunk.components(separatedBy: dayItem).dropFirst() {
            guard let m = item.firstMatch(of: itemSetlist), let id = group(m, 1) else { continue }
            ids.append(id)
            if let text = item.firstMatch(of: itemTime).flatMap({ group($0, 1) }),
               let time = pageTime(text) {
                times[id] = time
            }
        }
        if let date, !ids.isEmpty { days[date] = ids }
    }
    let iso = html.firstMatch(of: dtStart)
        .flatMap { group($0, 1) }
        .flatMap { isoDay.date(from: $0) }
        .map { fmDateFormat.string(from: $0) }
    return ScrapedFestival(
        rangeFrom: iso ?? range.flatMap { group($0, 1) }.flatMap { fmDate($0, pageRangeFormat) },
        rangeTo: range.flatMap { group($0, 2) }.flatMap { fmDate($0, pageRangeFormat) },
        dayMembership: days.isEmpty ? nil : days,
        setTimes: times.isEmpty ? nil : times
    )
}

private let isoDay = fmFormatter("yyyy-MM-dd")

/// One capture group of a match built from a runtime pattern, as a plain String.
private func group(_ match: Regex<AnyRegexOutput>.Match, _ i: Int) -> String? {
    guard let s = match.output[i].substring else { return nil }
    return String(s).nilIfBlank
}

/// A date the page wrote its way, in the one shape this app stores dates in.
private func fmDate(_ text: String, _ format: DateFormatter) -> String? {
    guard let d = format.date(from: text.trimmingCharacters(in: .whitespacesAndNewlines))
    else { return nil }
    return fmDateFormat.string(from: d)
}

/// "2:00 pm" as "14:00", so the latest set time is also the largest string. Uppercased
/// because `en_US_POSIX` spells its day-period symbols AM and PM and parses strictly.
private func pageTime(_ text: String) -> String? {
    let cleaned = text.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    guard let d = pageTimeFormat.date(from: cleaned) else { return nil }
    return twentyFourHour.string(from: d)
}
