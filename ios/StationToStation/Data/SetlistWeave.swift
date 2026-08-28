import Foundation

// The night's set as one line, woven from the two records that describe it (#268).
// The Swift twin of Android's `weaveSetlist` (`data/SetlistWeave.kt`), asserted by
// the same cases in `SetlistWeaveTests.swift`.
//
// **Two lists of the same night is one list too many.** setlist.fm's record and my
// Log were drawn as separate lists, one above the other, so a song both of them held
// was printed twice — and the reader had to do the alignment in their head to find
// out that the two lists agreed. Which is precisely the thing an inline diff exists
// to stop doing: matching lines are shown *once*, marked as matching.
//
// **Neither side is merged into the other.** This produces a reading order and
// nothing else; the two records stay exactly as they were on disk, which is the rule
// StoredLog was built around — someone else filling in what I missed is the good
// case, and quietly overwriting either side with the other loses a fact.
//
// **Why a real diff and not an index walk.** The two orders are the same order right
// up until they aren't: my log is what I managed to type, so it drops songs, and one
// dropped song puts every later index out by one against the published set. Pairing
// by position would then report every remaining song as a disagreement. An LCS is
// the ordinary answer to that and it costs nothing on a set of thirty.

/// One line of the woven set: a published row, one of my log's entries, or both.
///
/// Indices rather than titles, so the caller keeps whatever each side actually holds
/// — a cover credit and a tape marker on one, a Remembered Line and a Gap on the
/// other. Never both nil.
struct WovenSong: Equatable {
    let published: Int?
    let logged: Int?

    init(published: Int? = nil, logged: Int? = nil) {
        self.published = published
        self.logged = logged
    }

    /// The strongest thing a row can say: two records, independently, agree.
    var both: Bool { published != nil && logged != nil }
}

/// Align the published titles against my logged ones, longest-common-subsequence.
///
/// `published` is optional per entry so a row that is not a song — an encore marker —
/// can be passed through in its place without ever matching anything. A Gap (a blank
/// log entry) matches nothing either: "one I couldn't name" is a statement that no
/// title was captured, so pairing it with a published title would be inventing the
/// very claim it exists to avoid making.
///
/// On a tie, the published row goes first: the shared record is the spine, and mine
/// is what hangs off it.
func weaveSetlist(published: [String?], logged: [String]) -> [WovenSong] {
    func match(_ i: Int, _ j: Int) -> Bool {
        guard let p = published[i] else { return false }
        let l = logged[j]
        return !l.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && sameSong(p, l)
    }

    // lcs[i][j] = length of the longest common subsequence of the tails from i and j.
    var lcs = Array(repeating: Array(repeating: 0, count: logged.count + 1),
                    count: published.count + 1)
    for i in stride(from: published.count - 1, through: 0, by: -1) {
        for j in stride(from: logged.count - 1, through: 0, by: -1) {
            lcs[i][j] = match(i, j)
                ? lcs[i + 1][j + 1] + 1
                : max(lcs[i + 1][j], lcs[i][j + 1])
        }
    }

    var out: [WovenSong] = []
    var i = 0
    var j = 0
    while i < published.count && j < logged.count {
        if match(i, j) {
            out.append(WovenSong(published: i, logged: j)); i += 1; j += 1
        } else if lcs[i + 1][j] >= lcs[i][j + 1] {
            out.append(WovenSong(published: i)); i += 1
        } else {
            out.append(WovenSong(logged: j)); j += 1
        }
    }
    while i < published.count { out.append(WovenSong(published: i)); i += 1 }
    while j < logged.count { out.append(WovenSong(logged: j)); j += 1 }
    return out
}
