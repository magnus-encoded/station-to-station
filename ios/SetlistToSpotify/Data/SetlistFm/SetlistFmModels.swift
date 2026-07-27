import Foundation

struct ArtistSearchResponse: Decodable {
    @DefaultCodable<EmptyArray<FmArtist>> var artist: [FmArtist] = []
    @DefaultCodable<IntZero> var total = 0
}

struct FmArtist: Decodable, Identifiable {
    @DefaultCodable<EmptyString> var mbid = ""
    @DefaultCodable<EmptyString> var name = ""
    var disambiguation: String?

    var id: String { mbid }
}

struct SetlistsResponse: Decodable {
    @DefaultCodable<EmptyArray<FmSetlist>> var setlist: [FmSetlist] = []
    @DefaultCodable<IntZero> var total = 0
}

struct FmSetlist: Decodable, Identifiable {
    @DefaultCodable<EmptyString> var id = ""
    var eventDate: String?
    var artist: FmArtist?
    var venue: FmVenue?
    var tour: FmTour?
    var sets: FmSets?
    var url: String?

    func songs() -> [FmSong] { (sets?.set ?? []).flatMap(\.song) }

    func venueLine() -> String {
        let v = venue?.name ?? "Unknown venue"
        return [v, venue?.city?.name, venue?.city?.country?.name]
            .compactMap { $0 }
            .joined(separator: ", ")
    }
}

struct FmVenue: Decodable {
    var name: String?
    var city: FmCity?
}

struct FmCity: Decodable {
    var name: String?
    var country: FmCountry?
}

struct FmCountry: Decodable {
    var name: String?
}

struct FmTour: Decodable {
    var name: String?
}

struct FmSets: Decodable {
    @DefaultCodable<EmptyArray<FmSet>> var set: [FmSet] = []
}

struct FmSet: Decodable {
    var name: String?
    var encore: Int?
    @DefaultCodable<EmptyArray<FmSong>> var song: [FmSong] = []
}

struct FmSong: Decodable {
    @DefaultCodable<EmptyString> var name = ""
    var info: String?
    @DefaultCodable<BoolFalse> var tape = false
    var cover: FmArtist?
}
