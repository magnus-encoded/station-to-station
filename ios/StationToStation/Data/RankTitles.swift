import Foundation

/// The artist's own titles, ranked against the words someone wrote down (#126).
///
/// The Swift twin of Android's `rankTitles` (`data/Bill.kt`), ported with its fixtures
/// rather than re-derived from the description — #126 asks for exactly that, and the
/// two implementations answering differently would mean a **Log** corrected on one
/// phone and re-opened on the other offered a different first candidate.
///
/// **Nothing here classifies.** "This string is not a known song" is genuinely
/// ambiguous between "a title we don't know" and "a lyric whose title differs" — real
/// titles are frequently whole sentences, and remembered lines frequently contain the
/// title — so any classifier applied to the string alone is wrong in both directions.
/// This only *orders* candidates; a tap is what decides.
///
/// The whole catalogue is returned, never a filtered shortlist: a remembered line
/// sharing no words with any title still has to be correctable, so a low-ranking
/// answer must stay reachable. When nothing matches, the order is simply the order it
/// came in, which reads as "nothing confident" rather than promoting a bad match.
///
/// A **contained** title outranks scattered overlap. "Toothpicks and Gum" appears in
/// "All held together by toothpicks and gum" as a contiguous phrase, which is a
/// stronger signal than the same words spread across a sentence, and it is worth a
/// whole point — more than any overlap can reach.
func rankTitles(_ line: String, _ catalogue: [String]) -> [String] {
    guard !line.isBlank else { return catalogue }
    let phrase = line.phrase()
    let words = line.titleWords()

    // Sorted by a precomputed score, and `enumerated` keeps it stable: Swift's sort is
    // not, and two equally-scored titles swapping places between openings of the same
    // sheet would be the kind of churn that makes a list feel untrustworthy.
    return catalogue.enumerated()
        .map { pair -> (index: Int, title: String, score: Double) in
            let title = pair.element
            let t = title.phrase()
            let contained = !t.isBlank && phrase.contains(t) ? 1.0 : 0.0
            let tokens = title.titleWords()
            let overlap = tokens.isEmpty ? 0.0
                : Double(tokens.filter { words.contains($0) }.count) / Double(tokens.count)
            return (pair.offset, title, contained + overlap)
        }
        .sorted { a, b in a.score != b.score ? a.score > b.score : a.index < b.index }
        .map(\.title)
}

private extension String {
    var isBlank: Bool { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    /// Lowercased words, punctuation gone, apostrophes closed rather than split.
    ///
    /// Splitting on every non-alphanumeric would make "Don't" into "don" and "t", so
    /// it would never meet the "dont" someone typed — which is the case Android's
    /// `songKey` exists to handle.
    func normalisedWords() -> [String] {
        lowercased()
            .replacingOccurrences(of: "'", with: "")
            .replacingOccurrences(of: "\u{2019}", with: "")
            .split(whereSeparator: { !$0.isLetter && !$0.isNumber })
            .map(String.init)
    }

    func titleWords() -> Set<String> { Set(normalisedWords()) }

    /// The same words, in order, with a space at each end.
    ///
    /// Containment is tested on this and not on a spacing-stripped key: on those terms
    /// the title *Sand* is inside "toothpick**s and** gum", and a false containment is
    /// worth a whole point here, so a two-word coincidence would be promoted above the
    /// title someone actually wrote. The padding is what forces a match to begin and
    /// end where a word does.
    func phrase() -> String { " " + normalisedWords().joined(separator: " ") + " " }
}
