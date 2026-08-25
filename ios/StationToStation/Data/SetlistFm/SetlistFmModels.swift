import Foundation

struct ArtistSearchResponse: Decodable {
    @DefaultCodable<EmptyArray<FmArtist>> var artist: [FmArtist] = []
    @DefaultCodable<IntZero> var total = 0
}

struct FmArtist: Codable, Identifiable {
    @DefaultCodable<EmptyString> var mbid = ""
    @DefaultCodable<EmptyString> var name = ""
    var disambiguation: String?

    var id: String { mbid }

    init(mbid: String = "", name: String = "", disambiguation: String? = nil) {
        self._mbid = DefaultCodable(wrappedValue: mbid)
        self._name = DefaultCodable(wrappedValue: name)
        self.disambiguation = disambiguation
    }
}

struct SetlistsResponse: Decodable {
    @DefaultCodable<EmptyArray<FmSetlist>> var setlist: [FmSetlist] = []
    @DefaultCodable<IntZero> var total = 0
}

/// setlist.fm sends the event date as dd-MM-yyyy. Fixed locale and UTC so a
/// phone's region can't change which day a gig lands on, and so the day
/// arithmetic behind festival clustering never meets a DST jump.
func fmFormatter(_ pattern: String) -> DateFormatter {
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US_POSIX")
    f.timeZone = TimeZone(secondsFromGMT: 0)
    f.dateFormat = pattern
    return f
}

private let fmDateParser = fmFormatter("dd-MM-yyyy")

/// A dd-MM-yyyy date as this app stores every date — a **Festival**'s own range
/// included, which is not on a setlist and so has nowhere else to be parsed. The twin
/// of Android's `parseFmDate`.
func parseFmDate(_ text: String) -> Date? { fmDateParser.date(from: text) }
private let readableFormatter = fmFormatter("d MMMM yyyy")
private let yearFormatter = fmFormatter("yyyy")

struct FmSetlist: Codable, Identifiable {
    @DefaultCodable<EmptyString> var id = ""
    var eventDate: String?
    var artist: FmArtist?
    var venue: FmVenue?
    var tour: FmTour?
    var sets: FmSets?
    var url: String?
    /// Free-text note. Arbitrary — "First show in Norway", not the festival name.
    var info: String?

    init(
        id: String = "",
        eventDate: String? = nil,
        artist: FmArtist? = nil,
        venue: FmVenue? = nil,
        tour: FmTour? = nil,
        sets: FmSets? = nil,
        url: String? = nil,
        info: String? = nil
    ) {
        self._id = DefaultCodable(wrappedValue: id)
        self.eventDate = eventDate
        self.artist = artist
        self.venue = venue
        self.tour = tour
        self.sets = sets
        self.url = url
        self.info = info
    }

    /// The raw record, exactly as setlist.fm logged it. See `performed()`.
    func songs() -> [FmSong] { (sets?.set ?? []).flatMap(\.song) }

    /// The songs the band actually played. `songs()` also carries tape tracks —
    /// walk-on and interval recordings that were in the room but nobody counts as
    /// part of the set — and the nameless placeholders setlist.fm emits for a song
    /// no one could identify. Every count a user reads means this list.
    func performed() -> [FmSong] { songs().filter { !$0.tape && $0.name.nilIfBlank != nil } }

    func venueLine() -> String {
        let v = venue?.name ?? "Unknown venue"
        return [v, venue?.city?.name, venue?.city?.country?.name]
            .compactMap { $0 }
            .joined(separator: ", ")
    }

    /// The gig's date, or nil when setlist.fm sent something unparseable.
    func localDate() -> Date? { eventDate.flatMap { fmDateParser.date(from: $0) } }

    /// Leads the playlist name, so a library of setlists sorts by year.
    func year() -> String? {
        if let d = localDate() { return yearFormatter.string(from: d) }
        let tail = eventDate?.split(separator: "-").last.map(String.init)
        return tail?.count == 4 ? tail : nil
    }

    /// "24 June 2026" — fixed to English so a playlist does not read differently
    /// depending on the phone's locale.
    func readableDate() -> String? {
        if let d = localDate() { return readableFormatter.string(from: d) }
        return eventDate
    }
}

struct FmVenue: Codable {
    var name: String?
    var city: FmCity?

    init(name: String? = nil, city: FmCity? = nil) {
        self.name = name
        self.city = city
    }
}

struct FmCity: Codable {
    var name: String?
    var country: FmCountry?
    /// setlist.fm ships coordinates for the **city**, never the venue. The coarse
    /// gate check-in uses before paying for a geocode — see `CheckIn.swift`.
    var coords: FmCoords?

    init(name: String? = nil, country: FmCountry? = nil, coords: FmCoords? = nil) {
        self.name = name
        self.country = country
        self.coords = coords
    }
}

/// City-centre coordinates, both optional because setlist.fm omits them on records it
/// has no position for — and a half-known point is no point at all.
struct FmCoords: Codable {
    var lat: Double?
    var long: Double?

    init(lat: Double? = nil, long: Double? = nil) {
        self.lat = lat
        self.long = long
    }
}

struct FmCountry: Codable {
    var name: String?
}

struct FmTour: Codable {
    var name: String?
}

struct FmSets: Codable {
    @DefaultCodable<EmptyArray<FmSet>> var set: [FmSet] = []

    init(set: [FmSet] = []) { self._set = DefaultCodable(wrappedValue: set) }
}

struct FmSet: Codable {
    var name: String?
    var encore: Int?
    @DefaultCodable<EmptyArray<FmSong>> var song: [FmSong] = []

    init(name: String? = nil, encore: Int? = nil, song: [FmSong] = []) {
        self.name = name
        self.encore = encore
        self._song = DefaultCodable(wrappedValue: song)
    }
}

struct FmSong: Codable {
    @DefaultCodable<EmptyString> var name = ""
    var info: String?
    @DefaultCodable<BoolFalse> var tape = false
    var cover: FmArtist?

    init(name: String = "", info: String? = nil, tape: Bool = false, cover: FmArtist? = nil) {
        self._name = DefaultCodable(wrappedValue: name)
        self.info = info
        self._tape = DefaultCodable(wrappedValue: tape)
        self.cover = cover
    }
}
