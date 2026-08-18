import Foundation

// The sentence rendered above a Note, composed from what the record already
// holds (#50, ported for iOS by #170's parent #167).
//
// "I was here with Ozzy and Lemmy at Rockefeller, seeing this set."
//
// It is not text and it is never stored. A Preamble is the record showing
// itself, which is why it cannot be typed, cannot be edited, and does not
// travel: a receiver renders their own from their own facts, the same
// instinct that has contactManifest send ids in the sender's own space and
// leave translating to the far end.
//
// The reason it is derived rather than frozen is Reconcile. A Contact made
// in 2029 drops media into a night in 2026 and changes who the record knows
// was there. A sentence written at the time would then be a fact the record
// has since corrected, still sitting on the page in the first person — the
// app putting words in someone's mouth about who they spent an evening
// with. Deriving it means the sentence gets better as the record does.
//
// Every clause is droppable and none is faked. A night with no venue does
// not render "at ", and a night where nothing at all is known renders
// nothing rather than a sentence with holes in it. That last case is
// ordinary, not defensive: a 1992 import with no venue, no Crossing and no
// setlist is exactly the night someone most wants to write about from
// memory.

/// Compose the Preamble, or return empty when the record has nothing to say.
///
/// `people` are the names the record knows were there — Crossings on this
/// night, in the order the caller wants them read. `venue` is blank or nil
/// for a night whose place is unknown. `songCount` is how many songs the
/// linked record holds; zero means there is no set to point at.
func preamble(people: [String] = [], venue: String? = nil, songCount: Int = 0) -> String {
    let names = people.filter { $0.nilIfBlank != nil }
    let place = venue?.nilIfBlank
    // Nothing known: say nothing. "I was here" on its own is not a fact worth
    // a line, and it is the one clause that is true of every night in the
    // timeline.
    if names.isEmpty && place == nil && songCount <= 0 { return "" }

    var out = "I was here"
    if !names.isEmpty { out += " with \(readAsList(names))" }
    if let place { out += " at \(place)" }
    // Deliberately not "seeing 18 songs": the count is on screen already, and
    // a number in a first-person sentence reads as inventory rather than
    // memory.
    if songCount > 0 { out += ", seeing this set" }
    out += "."
    return out
}

/// "Ozzy", "Ozzy and Lemmy", "Ozzy, Lemmy and Ida".
///
/// No serial comma before the conjunction, and no "and" for one name — a
/// list of one that reads like a list is the tell that a sentence was
/// assembled rather than written.
func readAsList(_ names: [String]) -> String {
    switch names.count {
    case 0: return ""
    case 1: return names[0]
    default: return names.dropLast().joined(separator: ", ") + " and " + names.last!
    }
}
