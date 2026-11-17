import Foundation

extension UiState {
    /// Every night on my **Line**: attended and planned.
    var knownNights: [FmSetlist] { timelineShows + plannedGigs }

    /// The artists already on my **Line**, for the matcher's artist check: one per
    /// MusicBrainz id.
    var lineArtists: [FmArtist] {
        var seen = Set<String>()
        return knownNights.compactMap(\.artist)
            .filter { !$0.mbid.trimmingCharacters(in: .whitespaces).isEmpty && seen.insert($0.mbid).inserted }
    }
}

/// Planned nights, newest first: the order the future lane is kept in.
func sortedPlanned(_ gigs: [FmSetlist]) -> [FmSetlist] {
    gigs.sorted { ($0.localDate() ?? .distantPast) > ($1.localDate() ?? .distantPast) }
}
