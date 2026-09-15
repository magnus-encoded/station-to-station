import Foundation

/// A **Log**: the ordered songs *I* observed at one **Gig**, on my own device.
///
/// The Swift twin of Android's `StoredLog` (`data/Bill.kt`). Ported rather than
/// re-derived, and the tests are ported with it — the two shapes share one cache
/// file, so a rule that holds on one side and not the other is a corruption with
/// extra steps.
///
/// Not setlist.fm's setlist. That is the published shared record; this is the
/// witness statement, and the two are kept apart because **the app is the source of
/// truth about what was observed and setlist.fm is a publication target**. A **Log**
/// is freely editable forever — remembering a song three days later costs nothing —
/// and **Publish** never writes back into it.
///
/// `closed` is the whole reason this is a record rather than a list of strings. A set
/// captured by ticking off songs an artist has played before is **incomplete by
/// construction**: the candidate pool cannot contain a new song, a cover, a guest
/// spot, or anything by an artist setlist.fm has never heard of. So a **Log** starts
/// **Open** and only a person may say otherwise. The bit never makes the round trip —
/// setlist.fm has nowhere to keep it, so a published set coming back would look
/// finished when it isn't, and that is unrecoverable by construction.
///
/// A blank entry in `songs` is a **Gap**: they played something and I could not name
/// it. An acknowledged gap is a true fact; the same song silently missing is the
/// record lying about its own certainty.
struct StoredLog: Codable, Equatable {
    var songs: [String] = []
    var closed: Bool = false
    /// The **Remembered Line**: the words originally written where a title replaced
    /// them, blank where nothing was replaced. Parallel to `songs`, same length.
    ///
    /// A **Log** is written in the dark while the band is still playing, so sometimes
    /// what gets typed is not the title but the only words that could be caught. The
    /// line someone remembered is not inferior data waiting to be replaced — for the
    /// **Reliver** it is often *the* memory, and replacing it makes the record more
    /// correct and less true (#126).
    ///
    /// An older cache has no `remembered` at all, which reads as "nothing was ever
    /// replaced" — exactly true. Parallel lists only stay parallel if one place keeps
    /// them so: that place is the functions below, and nothing else may edit
    /// `songs` directly.
    var remembered: [String] = []
    /// When each entry was typed — epoch millis, set once by `adding` and never
    /// touched again. Parallel to `songs`, same length.
    ///
    /// Deliberately the moment of *entry*, not a guess at when the song started. A
    /// **Log** is written in the dark, one-handed, often a beat behind the band, so
    /// "when I typed this" is the one fact the phone can state without asking the user
    /// to be more certain than they are. Correcting a title (`correctingAt`) does not
    /// move it — the memory was written at that moment even if the word for it changed
    /// later. This is the primitive later work (crowd corroboration of a set's timing,
    /// the landscape walk's photo-to-song reconstruction) builds on; nothing here does
    /// that inference yet.
    ///
    /// An older cache has no `enteredAt` at all, which decodes as "unknown" (`0`) per
    /// entry — never backfilled, never guessed.
    var enteredAt: [Int64] = []
    /// Explicit completion; edits preserve it and explicit reopening clears it.
    var completedAt: Int64? = nil
    var lineNumbers: [Int] = []
    var nextLineNumber: Int = 0

    init(songs: [String] = [], closed: Bool = false, remembered: [String] = [], enteredAt: [Int64] = [], completedAt: Int64? = nil, lineNumbers: [Int] = [], nextLineNumber: Int = 0) {
        self.songs = songs
        self.closed = closed
        self.remembered = remembered
        self.enteredAt = enteredAt
        self.completedAt = completedAt
        self.lineNumbers = lineNumbers
        self.nextLineNumber = nextLineNumber
    }

    // By hand rather than synthesized, so a missing key falls back to the default
    // instead of throwing — kotlinx does that, and one absent field must not take the
    // whole cache down with it.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        songs = (try? c.decodeIfPresent([String].self, forKey: .songs)) ?? nil ?? []
        lineNumbers = (try? c.decodeIfPresent([Int].self, forKey: .lineNumbers)) ?? []
        nextLineNumber = (try? c.decodeIfPresent(Int.self, forKey: .nextLineNumber)) ?? 0
        completedAt = try? c.decodeIfPresent(Int64.self, forKey: .completedAt)
        closed = (try? c.decodeIfPresent(Bool.self, forKey: .closed)) ?? nil ?? false
        remembered = (try? c.decodeIfPresent([String].self, forKey: .remembered)) ?? nil ?? []
        enteredAt = (try? c.decodeIfPresent([Int64].self, forKey: .enteredAt)) ?? nil ?? []
    }

    func completing(_ closed: Bool, now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> StoredLog {
        var result = self
        result.closed = closed
        result.completedAt = closed ? (completedAt ?? now) : nil
        return result
    }

    func lineNumberAt(_ i: Int) -> Int { i < lineNumbers.count ? lineNumbers[i] : i }
    private var nextNumber: Int { max(nextLineNumber, (songs.indices.map(lineNumberAt).max() ?? -1) + 1) }

    /// Songs actually named. A **Gap** is in the record but is not a title.
    func named() -> [String] { songs.filter { !$0.isBlank } }

    var gaps: Int { songs.filter { $0.isBlank }.count }

    /// The words originally written at `i`, or nil where the entry is as typed.
    func rememberedAt(_ i: Int) -> String? {
        guard i >= 0, i < remembered.count, !remembered[i].isBlank else { return nil }
        return remembered[i]
    }

    /// When entry `i` was typed, or nil where no timestamp was ever recorded.
    func enteredAtOrNull(_ i: Int) -> Int64? {
        guard i >= 0, i < enteredAt.count, enteredAt[i] != 0 else { return nil }
        return enteredAt[i]
    }

    /// A song, at the end, in the order it was tapped in.
    func adding(_ song: String, now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> StoredLog {
        StoredLog(
            songs: songs + [song],
            closed: closed,
            remembered: aligned() + [""],
            enteredAt: alignedTimestamps() + [now], completedAt: completedAt,
            lineNumbers: songs.indices.map(lineNumberAt) + [nextNumber], nextLineNumber: nextNumber + 1
        )
    }

    /// One entry gone, and the words behind it with it.
    func removingAt(_ i: Int) -> StoredLog {
        guard i >= 0, i < songs.count else { return self }
        var s = songs, r = aligned(), t = alignedTimestamps()
        s.remove(at: i)
        r.remove(at: i)
        t.remove(at: i)
        return StoredLog(songs: s, closed: closed, remembered: r, enteredAt: t, completedAt: completedAt,
            lineNumbers: songs.indices.filter { $0 != i }.map(lineNumberAt), nextLineNumber: nextNumber)
    }

    /// `i` becomes `title`, and what was there moves into `remembered`.
    ///
    /// Two rules that are easy to get wrong and are asserted:
    ///
    /// - **A second correction keeps the first words.** They are the ones written in
    ///   the dark; a title I already chose is not a memory to preserve.
    /// - **A Gap is not corrected.** "One I couldn't name" is an acknowledged fact,
    ///   not an invitation to guess, so this leaves a blank entry alone.
    func correctingAt(_ i: Int, title: String) -> StoredLog {
        guard i >= 0, i < songs.count, !songs[i].isBlank, !title.isBlank else { return self }
        var s = songs, r = aligned()
        if r[i].isBlank { r[i] = songs[i] }
        s[i] = title
        return StoredLog(songs: s, closed: closed, remembered: r, enteredAt: alignedTimestamps(), completedAt: completedAt,
            lineNumbers: lineNumbers, nextLineNumber: nextLineNumber)
    }

    /// The words come back as the entry. A wrong correction is never a one-way door.
    func restoringAt(_ i: Int) -> StoredLog {
        guard let line = rememberedAt(i) else { return self }
        var s = songs, r = aligned()
        s[i] = line
        r[i] = ""
        return StoredLog(songs: s, closed: closed, remembered: r, enteredAt: alignedTimestamps(), completedAt: completedAt,
            lineNumbers: lineNumbers, nextLineNumber: nextLineNumber)
    }

    /// Every entry of `other` joins this **Log**; nothing handwritten is dropped.
    ///
    /// Where every entry on both sides knows when it was typed, the two interleave by
    /// `enteredAt` (a tie keeps this Log's entry first); otherwise `other`'s entries follow
    /// this Log's in their own order. Only an exact duplicate — same title, same
    /// `remembered`, same known timestamp — is folded away. This Log's entries keep their
    /// line numbers, because gossip already published under them; `other`'s arrive with
    /// fresh ones, since its numbers belonged to a **Gig** that no longer exists and would
    /// collide. Completion is left to the caller.
    func absorbing(_ other: StoredLog) -> StoredLog {
        typealias Entry = (song: String, line: String, at: Int64, number: Int?)
        let lines = aligned(), times = alignedTimestamps()
        let mine: [Entry] = songs.indices.map { (songs[$0], lines[$0], times[$0], lineNumberAt($0)) }
        let otherLines = other.aligned(), otherTimes = other.alignedTimestamps()
        let theirs: [Entry] = other.songs.indices
            .map { (other.songs[$0], otherLines[$0], otherTimes[$0], nil) }
            .filter { e in !(e.at != 0 && mine.contains { $0.song == e.song && $0.line == e.line && $0.at == e.at }) }
        if theirs.isEmpty { return self }
        let all = mine + theirs
        let ordered = all.allSatisfy { $0.at != 0 }
            ? all.enumerated().sorted { ($0.element.at, $0.offset) < ($1.element.at, $1.offset) }.map(\.element)
            : all
        var next = nextNumber
        let numbers = ordered.map { e -> Int in
            if let n = e.number { return n }
            defer { next += 1 }
            return next
        }
        return StoredLog(songs: ordered.map(\.song), closed: closed, remembered: ordered.map(\.line),
            enteredAt: ordered.map(\.at), completedAt: completedAt, lineNumbers: numbers, nextLineNumber: next)
    }

    /// `remembered` at `songs`'s length: an older cache carries none at all.
    private func aligned() -> [String] {
        (0..<songs.count).map { $0 < remembered.count ? remembered[$0] : "" }
    }

    /// `enteredAt` at `songs`'s length: an older cache carries none at all.
    private func alignedTimestamps() -> [Int64] {
        (0..<songs.count).map { $0 < enteredAt.count ? enteredAt[$0] : 0 }
    }
}

private extension String {
    /// Kotlin's `isBlank()`: empty, or nothing but whitespace.
    var isBlank: Bool { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}
