import Foundation

/// Loose song-title equality: case, punctuation and spacing thrown away.
///
/// The Swift twin of Android's `sameSong`/`songKey` (`data/Bill.kt`).
///
/// "P.I.M.P." typed as "PIMP", "Don't" as "Dont". This is used for *recognition* —
/// deciding whether two rows are the same song — and **never for recording**, where
/// the title is kept exactly as it was written. Being strict here would fail the one
/// job it exists to do.
///
/// Distinct from the normalisation in `RankTitles.swift`, deliberately and on both
/// platforms: this one discards spacing, which is right for equality and wrong for the
/// substring search containment needs. Keeping them apart is what stopped *Sand*
/// matching inside "toothpick*s and* gum" (#207).
func sameSong(_ a: String, _ b: String) -> Bool { a.songKey() == b.songKey() }

extension String {
    func songKey() -> String {
        lowercased().filter { $0.isLetter || $0.isNumber }
    }
}
