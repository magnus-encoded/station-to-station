import Foundation

/// An artist's own songs, from MusicBrainz. The Swift twin of Android's
/// `data/musicbrainz/MusicBrainzClient.kt`.
///
/// **Why a second source at all.** The **Log** correction panel ranks against a pool,
/// and the pool setlist.fm can offer is empty for most small acts — including the
/// artist the whole feature was written for, where setlist.fm knows who they are and
/// holds no setlists with their songs. MusicBrainz holds 55 recordings for that same
/// artist.
///
/// **Why it needs no name matching.** setlist.fm's artist ids *are* MusicBrainz ids,
/// so this asks with an identity rather than a name and introduces no new ambiguity of
/// its own. It inherits whatever the earlier match got wrong — a wrong artist gives a
/// wrong catalogue — which is why the panel names the artist the pool came from.
///
/// **Why the answer may be cached forever.** MusicBrainz is CC0. Under ADR-0005 a
/// source that can require deletion or expiry of cached data is disqualified for
/// anything entering the permanent record; this one has no such clause. That matters
/// more than it sounds: correction is wanted in a field with no signal, which is
/// exactly where the gig was.
struct MusicBrainzClient {

    private static let page = 100
    static let maxTitles = 400
    /// A prompt, not a search result page: eight rows is what fits above a keyboard.
    static let artistLimit = 8
    /// Below this a query matches most of the database and the list is noise.
    static let minQuery = 2
    /// Required by MusicBrainz, and a blank or generic one gets blocked. Naming the
    /// application and a way to reach us is the deal for an open database.
    static let userAgent =
        "StationToStation/1.0 ( https://github.com/magnus-encoded/station-to-station )"

    private let session: URLSession

    init(session: URLSession = .shared) { self.session = session }

    /// Every song title MusicBrainz has for `mbid`, de-duplicated.
    ///
    /// Paged, because an artist with a long career exceeds one page and a truncated
    /// catalogue silently lacks the one title someone is looking for. Capped, because
    /// a catalogue is a prompt and nobody scrolls two thousand rows — and the
    /// free-text field is always there for what a cap leaves out.
    func catalogue(mbid: String, cap: Int = maxTitles) async -> [String] {
        guard !mbid.trimmingCharacters(in: .whitespaces).isEmpty else { return [] }
        var titles: [String] = []
        var offset = 0
        while titles.count < cap {
            guard let page = await recordings(mbid: mbid, offset: offset),
                  !page.recordings.isEmpty
            else { break }
            titles += page.recordings.map(\.title)
            offset += page.recordings.count
            if offset >= page.count { break }
            // MusicBrainz asks for no more than one request a second, and asking
            // nicely is the whole price of a source with no cache clause.
            try? await Task.sleep(nanoseconds: 1_000_000_000)
        }
        return Array(dedupe(titles).prefix(cap))
    }

    /// Artists whose name looks like `query`, best match first.
    ///
    /// The one place this app asks MusicBrainz by *name* rather than by identity, and
    /// it is deliberately not a resolution step: it offers spellings to a person who
    /// then picks one. Nothing is keyed on the **mbid** it returns — a planned gig
    /// typed in by hand is a local **Gig** either way — so a wrong pick here costs a
    /// wrong name and not a wrong join.
    ///
    /// The disambiguation comment comes back with the name because MusicBrainz has
    /// four artists called Nirvana and a list of four identical rows is worse than no
    /// list at all.
    func searchArtists(query: String, limit: Int = artistLimit) async -> [MbArtist] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard q.count >= Self.minQuery else { return [] }
        var comps = URLComponents(string: "https://musicbrainz.org/ws/2/artist")!
        comps.queryItems = [
            URLQueryItem(name: "query", value: q),
            URLQueryItem(name: "fmt", value: "json"),
            URLQueryItem(name: "limit", value: String(limit)),
        ]
        guard let url = comps.url else { return [] }

        var request = URLRequest(url: url)
        request.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        guard let (data, response) = try? await session.data(for: request),
              let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode)
        else { return [] }
        return Array(parseArtists(String(decoding: data, as: UTF8.self)).prefix(limit))
    }

    /// Nil on any failure. A gig screen must not throw because a catalogue is
    /// unavailable — per ADR-0004 a source going quiet costs the enrichment, never
    /// the night.
    private func recordings(mbid: String, offset: Int) async -> RecordingsPage? {
        var comps = URLComponents(string: "https://musicbrainz.org/ws/2/recording")!
        comps.queryItems = [
            URLQueryItem(name: "artist", value: mbid),
            URLQueryItem(name: "fmt", value: "json"),
            URLQueryItem(name: "limit", value: String(Self.page)),
            URLQueryItem(name: "offset", value: String(offset)),
        ]
        guard let url = comps.url else { return nil }

        var request = URLRequest(url: url)
        request.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        guard let (data, response) = try? await session.data(for: request),
              let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode)
        else { return nil }
        return parseRecordings(String(decoding: data, as: UTF8.self))
    }
}

struct RecordingsPage: Codable, Equatable {
    var count: Int = 0
    var recordings: [Recording] = []

    enum CodingKeys: String, CodingKey {
        case count = "recording-count"
        case recordings
    }

    init(count: Int = 0, recordings: [Recording] = []) {
        self.count = count
        self.recordings = recordings
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        count = (try? c.decodeIfPresent(Int.self, forKey: .count)) ?? nil ?? 0
        recordings = (try? c.decodeIfPresent([Recording].self, forKey: .recordings)) ?? nil ?? []
    }
}

/// One artist MusicBrainz offered for a typed name.
///
/// `disambiguation` is MusicBrainz's own one-line note — "US grunge band", "Norwegian
/// rock band" — and is empty for most artists. It is carried because without it the
/// four artists called Nirvana are four identical rows.
struct MbArtist: Codable, Equatable, Identifiable {
    var name: String
    var mbid: String
    var disambiguation: String = ""

    var id: String { mbid }
}

private struct ArtistsPage: Codable {
    var artists: [ArtistHit] = []

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        artists = (try? c.decodeIfPresent([ArtistHit].self, forKey: .artists)) ?? nil ?? []
    }

    init() {}
}

private struct ArtistHit: Codable {
    var id: String = ""
    var name: String = ""
    var score: Int = 0
    var disambiguation: String = ""

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = (try? c.decodeIfPresent(String.self, forKey: .id)) ?? nil ?? ""
        name = (try? c.decodeIfPresent(String.self, forKey: .name)) ?? nil ?? ""
        score = (try? c.decodeIfPresent(Int.self, forKey: .score)) ?? nil ?? 0
        disambiguation =
            (try? c.decodeIfPresent(String.self, forKey: .disambiguation)) ?? nil ?? ""
    }
}

/// Parsing kept apart from fetching, as with `parseRecordings`.
///
/// Sorted by MusicBrainz's own score rather than trusting document order, and nameless
/// or idless hits are dropped — a row a person cannot read is not a suggestion, and one
/// with no id cannot be followed up later.
func parseArtists(_ body: String) -> [MbArtist] {
    guard let data = body.data(using: .utf8),
          let page = try? JSONDecoder().decode(ArtistsPage.self, from: data)
    else { return [] }
    return page.artists
        .filter { !$0.name.isEmpty && !$0.id.isEmpty }
        .sorted { $0.score > $1.score }
        .map { MbArtist(name: $0.name, mbid: $0.id, disambiguation: $0.disambiguation) }
}

struct Recording: Codable, Equatable {
    var title: String = ""

    init(title: String = "") { self.title = title }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        title = (try? c.decodeIfPresent(String.self, forKey: .title)) ?? nil ?? ""
    }
}

/// Parsing kept apart from fetching, so the shape of a reply is asserted without a
/// socket. A reply we cannot read is an empty page, never an exception on a gig screen.
func parseRecordings(_ body: String) -> RecordingsPage {
    guard let data = body.data(using: .utf8),
          let page = try? JSONDecoder().decode(RecordingsPage.self, from: data)
    else { return RecordingsPage() }
    return page
}

/// One entry per song, not per recording.
///
/// MusicBrainz lists every recording an artist has: a studio take, a live take, a
/// remaster and a radio edit are four rows and one song. Presented raw, the panel would
/// offer the same title four times and bury the rest. Compared with `sameSong`, the
/// normalisation recognition already uses everywhere, so "Don't" and "Dont" are one row
/// here exactly as they are one song there. **The first spelling wins**, because it is
/// a title and titles are kept as they are written.
func dedupe(_ titles: [String]) -> [String] {
    var out: [String] = []
    for t in titles {
        if t.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { continue }
        if !out.contains(where: { sameSong($0, t) }) { out.append(t) }
    }
    return out
}
