import Foundation
import CryptoKit

/// Clashfinder: the programme source, and the only one. The Swift twin of Android's
/// `data/clashfinder/Clashfinder.kt` (#389, #390).
///
/// A clashfinder is a festival timetable somebody typed in — stage, act, start *and*
/// end, for ten thousand festivals rather than the one whose markup we happened to
/// parse. It replaced Øya's own page outright: clashfinder carries Øyafestivalen
/// every year, its 2026 entry holds 139 acts against the scraper's 83, and it
/// publishes the end times the scraper had to guess at.
///
/// **The account is the user's.** Every request carries `authUsername` and
/// `authPublicKey`, and there is deliberately no bundled fallback the way setlist.fm
/// has one: a single credential shared by every install concentrates all of this
/// app's traffic on one account against a host that runs active bot protection. The
/// cost is accepted and stated plainly on the screen — with no account there is no
/// programme feature at all, so the empty state has to say what to get and where.
///
/// **It suggests, it never decides.** Half of all clashfinders are editable by
/// anyone and the median one is still being edited the day before its own festival.
/// These are the *scheduled* times, not the played ones. Nothing here writes a
/// **Gig** on its own: a person picks acts off the **Programme** this feeds and
/// **Departures** commits them, exactly as they would off a poster (#391).
///
/// Data is CC BY-NC 3.0 — attribution is a condition of that licence, so the
/// `copyright` line the payload carries is stored with the acts and shown with them.
private let clashfinderOrigin = "https://clashfinder.com"
private let clashfinderHost = "\(clashfinderOrigin)/data"

/// The full index, not the curated one. Clashfinder publishes `events/events.json`,
/// about 1,100 hand-curated entries, and this, about 10,500. Øyafestivalen is
/// **not** a core clashfinder, so the curated index would silently omit the one
/// festival this feature was built for.
private let indexPath = "events/all.json"

/// One account's credentials, as every request carries them.
struct ClashfinderAuth {
    var user: String
    var publicKey: String
}

/// `authPublicKey`: sha256 of username and private key concatenated, no separator.
///
/// Read off the key generator on the API page rather than its prose, which gives the
/// ingredients and not the order — and a wrong order is a 401 indistinguishable from
/// a mistyped key. The digest is static per account, so it is computed when the
/// credential is saved and not per request.
func clashfinderPublicKey(user: String, privateKey: String) -> String {
    let digest = SHA256.hash(data: Data((user.trimmingCharacters(in: .whitespacesAndNewlines)
        + privateKey.trimmingCharacters(in: .whitespacesAndNewlines)).utf8))
    return digest.map { String(format: "%02x", $0) }.joined()
}

/// One line of the index, reduced to what the picker and the matcher actually use.
///
/// The full index is roughly 4 MB and carries the owner, the edit history, the ACL
/// and more besides. Parsing that every time the picker opens is not acceptable on a
/// phone, so it is reduced once on ingest and only this is cached.
struct ClashfinderFestival: Codable, Equatable, Identifiable {
    /// The identifier a document is fetched by — `oyafestivalen2026`.
    var id: String = ""
    /// What a person reads: "Øyafestivalen 2026".
    var name: String = ""
    /// ISO yyyy-MM-dd. The index gives it as a UTC-midnight epoch second.
    var start: String = ""
    var days: Int = 1
    var acts: Int = 0
    var stages: Int = 0
    /// How many times it has been edited — the best available signal for "somebody
    /// is actually keeping this one", and the tie-break between two entries for one
    /// festival.
    var edits: Int = 0
    /// Clashfinder's own curation flag. Rare, and worth ranking on where it is there.
    var core: Bool = false

    // fileprivate, not private: parseClashfinderIndex is a free function in this
    // file and formats with the same one, rather than standing up a second.
    fileprivate static let isoDay: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.calendar = Calendar(identifier: .gregorian)
        f.timeZone = TimeZone(identifier: "UTC")
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()

    func startsOn() -> Date? { Self.isoDay.date(from: start) }

    /// How far this festival is from `day`, in days, counting its **whole run**.
    ///
    /// A festival in progress on `day` is distance zero. Matching on the start
    /// alone would miss somebody who is standing at day two of a three-day
    /// festival, which is exactly when they are most likely to be looking.
    // `calendar` is accepted for the caller's convenience but not used to do the
    // day math below: `from` is read off a UTC-midnight epoch (`startsOn`), and
    // measuring it against a *local* start-of-day mis-dates the festival by a
    // whole day for anyone west of Greenwich. Everything here stays on the same
    // UTC clock `startsOn` already committed to — see `utcCalendar` in
    // `Departures.swift`.
    func distanceFrom(_ day: Date, calendar: Calendar = .current) -> Int {
        guard let from = startsOn() else { return .max }
        let to = utcCalendar.date(byAdding: .day, value: max(days, 1) - 1, to: from) ?? from
        let (fromDay, toDay, dayDay) = (utcCalendar.startOfDay(for: from), utcCalendar.startOfDay(for: to), utcCalendar.startOfDay(for: day))
        if dayDay < fromDay {
            return utcCalendar.dateComponents([.day], from: dayDay, to: fromDay).day ?? 0
        } else if dayDay > toDay {
            return utcCalendar.dateComponents([.day], from: toDay, to: dayDay).day ?? 0
        }
        return 0
    }
}

/// The index as it arrives: an object keyed by identifier. `name` is the identifier
/// again and `desc` is the readable name, which is the opposite way round to how it
/// reads. Verified against the live index on 2026-08-31.
private struct IndexEntry: Decodable {
    var desc: String = ""
    var edits: Int = 0
    var numDays: Int = 1
    var numActs: Int = 0
    var numStages: Int = 0
    /// Unix seconds, UTC midnight of the first day.
    var startDate: Int = 0
    var isPrivate: Bool = false
    var coreClashfinder: Bool = false

    enum CodingKeys: String, CodingKey {
        case desc, edits, numDays, numActs, numStages, startDate
        case isPrivate = "private"
        case coreClashfinder
    }
}

/// The index into candidates. Pure: hand it the text, however you got it.
///
/// An entry with no date is dropped — the picker's whole order is nearness to
/// today, and an undated row could only ever sort last. A private one is dropped
/// too: it is a row that cannot be opened, and offering it is worse than not
/// listing it.
func parseClashfinderIndex(_ text: String) -> [ClashfinderFestival] {
    guard let data = text.data(using: .utf8),
          let raw = try? JSONDecoder().decode([String: IndexEntry].self, from: data)
    else { return [] }
    let isoDay = ClashfinderFestival.isoDay
    return raw.compactMap { id, e -> ClashfinderFestival? in
        guard !id.isEmpty, !e.isPrivate, e.startDate > 0 else { return nil }
        let start = Date(timeIntervalSince1970: TimeInterval(e.startDate))
        return ClashfinderFestival(
            id: id,
            name: e.desc.isEmpty ? id : e.desc,
            start: isoDay.string(from: start),
            days: max(e.numDays, 1),
            acts: e.numActs,
            stages: e.numStages,
            edits: e.edits,
            core: e.coreClashfinder
        )
    }
}

private let festivalEncoder = JSONEncoder()
private let festivalDecoder = JSONDecoder()

/// The reduced index, as cached.
func encodeFestivals(_ festivals: [ClashfinderFestival]) -> String {
    (try? festivalEncoder.encode(festivals)).flatMap { String(data: $0, encoding: .utf8) } ?? "[]"
}

func decodeFestivals(_ text: String) -> [ClashfinderFestival] {
    guard let data = text.data(using: .utf8),
          let festivals = try? festivalDecoder.decode([ClashfinderFestival].self, from: data)
    else { return [] }
    return festivals
}

/// The candidates for `query`, nearest `on` first.
///
/// **Ordered by nearness, never filtered to the future.** A future-only filter is
/// both more code and less useful: it would exclude Øyafestivalen 2026, which has
/// already happened, and it would shut out anyone recording a festival they went
/// to. Only about 1% of the corpus is future-dated, so sorting by distance puts the
/// hundred-odd upcoming ones at the top by itself and leaves everything else
/// reachable.
///
/// Where two entries are equally near — and duplicates are common — the more
/// complete one comes first: curated, then most edited, then most acts. Both are
/// still listed. Two genuinely competing entries is a choice for the person, not a
/// guess for the app.
func rankFestivals(_ festivals: [ClashfinderFestival], on: Date, query: String = "", calendar: Calendar = .current) -> [ClashfinderFestival] {
    let needle = foldName(query)
    return festivals
        .filter { needle.isEmpty || foldName($0.name).contains(needle) || foldName($0.id).contains(needle) }
        .map { ($0, $0.distanceFrom(on, calendar: calendar)) }
        .sorted { a, b in
            if a.1 != b.1 { return a.1 < b.1 }
            if a.0.core != b.0.core { return a.0.core }
            if a.0.edits != b.0.edits { return a.0.edits > b.0.edits }
            if a.0.acts != b.0.acts { return a.0.acts > b.0.acts }
            return a.0.name < b.0.name
        }
        .map { $0.0 }
}

/// A name reduced to what two spellings of it have in common.
///
/// Diacritics off, case off, punctuation and spacing off — so "oya" finds
/// "Øyafestivalen" from a keyboard that has no Ø on it, which is most keyboards and
/// every hurry. The Nordic letters are listed out because they do **not**
/// decompose: NFD splits å into a + ring but leaves ø and æ exactly as they were, so
/// normalisation alone would fail the one case this feature is named after.
///
/// The same fold decides whether an artist name matches, so a festival search and
/// an artist match agree about what "the same name" means.
func foldName(_ text: String) -> String {
    let decomposed = text.lowercased().decomposedStringWithCanonicalMapping
    var out = ""
    for scalar in decomposed.unicodeScalars {
        if scalar.properties.canonicalCombiningClass != .notReordered { continue }
        if let folded = foldedLetters[Character(scalar)] {
            out += folded
        } else if scalar.properties.isAlphabetic || CharacterSet.decimalDigits.contains(scalar) {
            out.unicodeScalars.append(scalar)
        }
    }
    return out
}

private let foldedLetters: [Character: String] = ["ø": "o", "æ": "ae", "ß": "ss", "ł": "l", "đ": "d", "ð": "d"]

// MARK: - The event document

// Synthesized `Decodable` does not fall back to a property's default when a key is
// simply missing — it throws `keyNotFound`, and one absent field then takes the act
// it is on, then that stage's whole `[EventAct]`, then the entire document. That is
// exactly the half-built failure `parseClashfinderEvent`'s contract refuses: a
// malformed corner of a timetable must drop out, never take the festival with it.
// So every struct below reads its keys through this, and none of them can throw.
extension KeyedDecodingContainer {
    fileprivate func value<T: Decodable>(_ key: Key, or fallback: T) -> T {
        ((try? decodeIfPresent(T.self, forKey: key)) ?? nil) ?? fallback
    }
}

private struct EventDoc: Decodable {
    var name: String = ""
    var copyright: String = ""
    var lastEdit: String = ""
    var locations: [EventLocation] = []

    enum CodingKeys: String, CodingKey { case name, copyright, lastEdit, locations }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = c.value(.name, or: "")
        copyright = c.value(.copyright, or: "")
        lastEdit = c.value(.lastEdit, or: "")
        locations = c.value(.locations, or: [])
    }
}

private struct EventLocation: Decodable {
    var name: String = ""
    var events: [EventAct] = []

    enum CodingKeys: String, CodingKey { case name, events }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = c.value(.name, or: "")
        events = c.value(.events, or: [])
    }
}

private struct EventAct: Decodable {
    var name: String = ""
    /// "yyyy-MM-dd HH:mm", already local to the festival — the payload says so itself.
    var start: String = ""
    var end: String = ""
    /// Present on about one act in fifty, so it resolves nothing on its own — but
    /// where it is there it is the only thing in the payload that ties a typed name
    /// to an artist, exactly and for free.
    ///
    /// The payload carries **both** `mbId` and `mbid`, on the same act, with the
    /// same value. Only one is read, deliberately: declaring them as two keys for
    /// one field is not how `Decodable` works, so a duplicate would just be read
    /// twice for no cost — but only `mbId` is mapped, and `mbid` is left to be
    /// ignored as an unknown key.
    var mbId: String = ""

    // Writing `init(from:)` by hand is what stops the compiler synthesizing these.
    enum CodingKeys: String, CodingKey {
        case name, start, end, mbId
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = c.value(.name, or: "")
        start = c.value(.start, or: "")
        end = c.value(.end, or: "")
        mbId = c.value(.mbId, or: "")
    }
}

/// A MusicBrainz id: eight-four-four-four-twelve hex digits, and nothing else.
private let mbidPattern = try! NSRegularExpression(
    pattern: #"^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$"#)

private func isMbid(_ s: String) -> Bool {
    mbidPattern.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) != nil
}

/// "2026-08-11 21:15" as a date and a clock, or nil if it is neither.
private func splitStamp(_ stamp: String) -> (date: String, clock: String)? {
    let parts = stamp.trimmingCharacters(in: .whitespacesAndNewlines)
        .split(whereSeparator: { $0 == " " || $0 == "T" }).map(String.init)
    guard parts.count >= 2 else { return nil }
    let dateParts = parts[0].split(separator: "-")
    guard dateParts.count == 3, dateParts.allSatisfy({ Int($0) != nil }) else { return nil }
    let clock = String(parts[1].prefix(5))
    let clockParts = clock.split(separator: ":")
    guard clockParts.count == 2, clockParts.allSatisfy({ Int($0) != nil }) else { return nil }
    return (parts[0], clock)
}

/// The calendar day before `iso`, as `yyyy-MM-dd`.
private func dayBefore(_ iso: String) -> String {
    let parts = iso.split(separator: "-").compactMap { Int($0) }
    guard parts.count == 3 else { return iso }
    var comps = DateComponents()
    comps.year = parts[0]; comps.month = parts[1]; comps.day = parts[2]
    var cal = Calendar(identifier: .gregorian)
    cal.timeZone = TimeZone(identifier: "UTC")!
    guard let day = cal.date(from: comps),
          let prior = cal.date(byAdding: .day, value: -1, to: day)
    else { return iso }
    let c = cal.dateComponents([.year, .month, .day], from: prior)
    return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
}

/// **The night boundary is applied backwards here, on purpose.**
///
/// Clashfinder already dates an after-midnight act to the next calendar day: a
/// 00:45 set on Friday night is stamped Saturday. `ProgrammeAct.startsAt`
/// independently applies the same rule, pushing any start before `nightEndsHour`
/// forward a day, because that is the app's own night boundary and the record it
/// reads is written the other way round. Splitting the timestamp naively would
/// apply the shift *twice* and land the act a full day late. So the previous
/// calendar date is what gets stored, and the existing rule puts it back. The
/// correction happens once, here at the edge; nothing downstream changes.
private func eventActToProgrammeAct(_ act: EventAct, stage: String) -> ProgrammeAct? {
    let artist = act.name.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !artist.isEmpty, let (date, clock) = splitStamp(act.start) else { return nil }
    let night = clock < String(format: "%02d:00", nightEndsHour) ? dayBefore(date) : date
    let mbid = act.mbId.trimmingCharacters(in: .whitespacesAndNewlines)
    return ProgrammeAct(
        artist: artist,
        date: night,
        start: clock,
        stage: stage,
        // Only the clock: the date the end belongs to is derived from the night,
        // the same way the start's is, so a set running past midnight needs no
        // second rule. An end that lands before its own start is discarded
        // downstream (see `endTimes`).
        end: splitStamp(act.end)?.clock ?? "",
        // Checked at the edge, for `isClashfinderId`'s reason: this one comes off a
        // document anyone may edit and goes into the *path* of a setlist.fm
        // request. Anything that is not a MusicBrainz id is no worse than the
        // common case, which is no id at all.
        mbid: isMbid(mbid) ? mbid : ""
    )
}

/// One clashfinder document into a timetable. Pure: hand it the text, however you
/// got it.
///
/// The by-stage nesting is flattened out, because a night is read down the clock
/// and not down one stage. An act missing a name, a start or a stage is dropped
/// rather than half-built — a partial act clashes with nothing and is invisible on
/// the timetable, which is the failure that would actually matter. A document that
/// has changed shape yields nothing rather than nonsense.
func parseClashfinderEvent(_ text: String, id: String = "") -> StoredProgramme {
    guard let data = text.data(using: .utf8),
          let doc = try? JSONDecoder().decode(EventDoc.self, from: data)
    else { return StoredProgramme(id: id) }
    var acts: [ProgrammeAct] = []
    for location in doc.locations {
        let stage = location.name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !stage.isEmpty else { continue }
        acts += location.events.compactMap { eventActToProgrammeAct($0, stage: stage) }
    }
    var seen = Set<[String]>()
    let distinct = acts.filter { seen.insert([$0.artist, $0.date, $0.start, $0.stage]).inserted }
    return StoredProgramme(
        id: id,
        name: doc.name.trimmingCharacters(in: .whitespacesAndNewlines),
        copyright: doc.copyright.trimmingCharacters(in: .whitespacesAndNewlines),
        lastEdit: doc.lastEdit.trimmingCharacters(in: .whitespacesAndNewlines),
        acts: distinct.sorted { a, b in
            let sa = a.startsAt() ?? .distantFuture
            let sb = b.startsAt() ?? .distantFuture
            return sa != sb ? sa < sb : a.stage < b.stage
        }
    )
}

// MARK: - Artist resolution

private let billingSplits = [" b2b ", " vs ", " vs. ", " feat ", " feat. ", " ft ", " ft. ", " featuring "]

/// The lead artist of a billing: everything before the collaboration clause.
///
/// A timetable bills a back-to-back set as "Artist b2b Other" and a guest spot as
/// "Artist feat. Someone". Neither string is an artist setlist.fm has ever heard of,
/// but the name in front of it is. An ampersand is deliberately **not** a
/// separator: it is part of the name in Nick Cave & The Bad Seeds and in half the
/// bands ever formed.
func billingLead(_ name: String) -> String {
    let lower = name.lowercased()
    let cut = billingSplits.compactMap { s -> String.Index? in
        guard let r = lower.range(of: s), r.lowerBound > lower.startIndex else { return nil }
        return r.lowerBound
    }.min()
    guard let cut else { return name.trimmingCharacters(in: .whitespacesAndNewlines) }
    let offset = lower.distance(from: lower.startIndex, to: cut)
    let idx = name.index(name.startIndex, offsetBy: offset)
    return String(name[name.startIndex..<idx]).trimmingCharacters(in: .whitespacesAndNewlines)
}

/// The one search hit that may be bound to `name` — or nil, which is a real answer.
///
/// **Never bind on a weak match.** setlist.fm's search returns its maximum
/// confidence score for results that are plainly wrong, so the score cannot be used
/// as a gate at all; only exact equality under `foldName` can. The cost of refusing
/// is that a **Programme** row goes forward with the name as printed and no
/// artist behind it, which is a visibly incomplete row somebody can still commit.
/// The cost of binding wrongly is a timeline quietly full of plausible-looking
/// mistakes, which nobody ever notices to fix.
///
/// This is also what keeps the entries on a timetable that are not music — a film,
/// a quiz, a notice, a silent disco — from becoming bands. They stay visible on the
/// programme, as published; they simply never match an artist.
func matchArtist(_ name: String, _ hits: [FmArtist]) -> FmArtist? {
    let key = foldName(billingLead(name))
    guard !key.isEmpty else { return nil }
    return hits.first { foldName($0.name) == key }
}

// MARK: - The network

/// Ids come off a document and go into a URL path, so they are checked rather than
/// trusted. Clashfinder's own ids are this alphabet.
func isClashfinderId(_ id: String) -> Bool {
    !id.isEmpty && id.count <= 64 && id.allSatisfy { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }
}

/// The host refuses to answer this app at all.
///
/// Its bot protection serves a CAPTCHA interstitial in place of the data, and it
/// does so for a request carrying the account's own credentials and a clearance
/// cookie taken in a browser on the same device. There is nothing the caller can do
/// about it from here — only clashfinder can let this client through — so the
/// screen offers the file the user's own browser can still fetch.
///
/// Android tried routing its own client through a `WKWebView`-equivalent cookie
/// jar first (a `WebView`'s `CookieManager`), on the theory that taking the check
/// in a browser clears a cookie the app's own requests could then carry. It does
/// not: the check gates a client, and clashfinder never told that client whether
/// it counted. What shipped instead, and what this ports, is simpler — open the
/// address in the system browser, credentials and all, and let the person hand the
/// fetched file back in.
struct ClashfinderBlocked: Error {}

/// The address of one document, credentials and all — what a browser can still
/// fetch, and what `Programme: a bot check you can actually clear` on Android hands
/// to `Intent.ACTION_VIEW`.
///
/// The credentials go out in the query string on purpose: this is the one route to
/// the file while the app itself is refused, and it is the user's own key on their
/// own phone.
func clashfinderUrl(_ path: String, auth: ClashfinderAuth) -> String {
    var comps = URLComponents(string: "\(clashfinderHost)/\(path)")!
    comps.queryItems = [
        URLQueryItem(name: "authUsername", value: auth.user),
        URLQueryItem(name: "authPublicKey", value: auth.publicKey),
    ]
    return comps.url!.absoluteString
}

/// The two requests this feature makes: one index per refresh, one document per
/// festival a person actually picks.
///
/// **The budget is deliberately small.** The host serves a CAPTCHA interstitial
/// rather than a rate-limit status when it decides a client is a bot, so bulk
/// fetching is not available and mirroring the corpus is not an option — which is
/// fine, because matching on a name and a date needs neither.
final class ClashfinderClient {
    private let auth: () async -> ClashfinderAuth?
    private let session: URLSession

    init(auth: @escaping () async -> ClashfinderAuth?, session: URLSession = .shared) {
        self.auth = auth
        self.session = session
    }

    private func get(_ path: String) async throws -> String {
        guard let credentials = await auth() else {
            throw AppError("No clashfinder account yet. Add your username and private key in Settings.")
        }
        var comps = URLComponents(string: "\(clashfinderHost)/\(path)")!
        comps.queryItems = [
            URLQueryItem(name: "authUsername", value: credentials.user),
            URLQueryItem(name: "authPublicKey", value: credentials.publicKey),
        ]
        var request = URLRequest(url: comps.url!)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        switch status {
        case 200...299: break
        case 401, 403:
            throw AppError("clashfinder rejected the account. Check the username and " +
                "private key in Settings — the private key is not the password.")
        default:
            throw AppError("clashfinder returned \(status).")
        }
        let body = String(data: data, encoding: .utf8) ?? ""
        // The bot check answers 200-with-HTML rather than a status anyone can
        // read. Say what it is, because "could not read the programme" would send
        // someone looking for a bug in the parser.
        if !body.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("{") {
            throw ClashfinderBlocked()
        }
        return body
    }

    /// The whole catalogue, reduced. One request; the caller caches what comes back.
    func index() async throws -> [ClashfinderFestival] {
        let festivals = parseClashfinderIndex(try await get(indexPath))
        guard !festivals.isEmpty else { throw AppError("Could not read clashfinder's festival list.") }
        return festivals
    }

    /// One festival's timetable.
    ///
    /// An empty parse raises rather than returning an empty programme: a blank
    /// timetable on screen reads as a festival with no bands, and the caller would
    /// cache it.
    func event(_ id: String) async throws -> StoredProgramme {
        guard isClashfinderId(id) else { throw AppError("\(id) is not a clashfinder id.") }
        let programme = parseClashfinderEvent(try await get("event/\(id).json"), id: id)
        guard !programme.acts.isEmpty else { throw AppError("That clashfinder has no timetable in it yet.") }
        return programme
    }
}
