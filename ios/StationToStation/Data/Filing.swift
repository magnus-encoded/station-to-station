import Foundation

// Filing a night with setlist.fm: the values the form asks for, and the door to it.
//
// The Swift twin of Android's `setlistPaste`, `filingFields` and `setlistEditEntry`
// (`data/Bill.kt`), asserted by the same cases. Pure — nothing here copies, opens or
// announces anything; what carries these across the app switch is the caller's, and
// the two platforms have different surfaces for that.

let setlistfmAddURL = "https://www.setlist.fm/edit"

/// The **Log** as setlist.fm's Text Field editor wants it: bare titles, one per line,
/// in the order they were played.
///
/// A Gap pastes as setlist.fm's own unknown marker rather than as nothing — dropping
/// it would publish a set silently claiming that song was not played.
func setlistPaste(_ log: StoredLog) -> String {
    log.songs
        .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        .map { $0.isEmpty ? "@Unknown[]" : $0 }
        .joined(separator: "\n")
}

/// One value the setlist.fm form wants, ready to be handed over.
///
/// `shown` and `value` differ for exactly one field — the songs, where the value is a
/// fourteen-line paste and what you want to *read* is "14 songs, in order". Everywhere
/// else they are the same string.
struct FilingField: Equatable {
    let label: String
    let shown: String
    let value: String
}

/// Everything the setlist.fm add form asks for, in the order it asks for it.
///
/// The clipboard holds one thing. The form wants five, and the app screen that knows
/// them is not on screen once the browser is — so the night's facts were being carried
/// across the app switch in the Historian's head, which is where a wrong venue comes
/// from.
///
/// **The order is the form's:** Add artist, Select event date, Add venue, then the
/// songs on the step after. Date before venue is not a preference — the venue field is
/// *disabled* until a date is set. A tray that offered Venue first would be offering a
/// value with nowhere to go.
///
/// Blank fields are dropped rather than posted empty: a night with no town typed in
/// should offer four values, not four and a lie.
func filingFields(_ setlist: FmSetlist, _ log: StoredLog) -> [FilingField] {
    var out: [FilingField] = []
    if let artist = setlist.artist?.name.nilIfBlank {
        out.append(FilingField(label: "Artist", shown: artist, value: artist))
    }
    // Shown as "5 August 2026" rather than 05-08-2026, because this one is **picked,
    // not pasted**: the field opens a calendar widget, so no string can land in it.
    // What the Historian actually does with this value is find that day in a month
    // grid, and a written-out month is the form of it that matches the gesture. The
    // raw date stays the copied value; it costs nothing and the clipboard is the
    // wrong place to editorialise.
    if let date = setlist.eventDate?.nilIfBlank {
        out.append(FilingField(label: "Date", shown: setlist.readableDate() ?? date, value: date))
    }
    if let venue = setlist.venue?.name?.nilIfBlank {
        out.append(FilingField(label: "Venue", shown: venue, value: venue))
    }
    if let city = setlist.venue?.city?.name?.nilIfBlank {
        out.append(FilingField(label: "City", shown: city, value: city))
    }
    if !log.songs.isEmpty {
        // Gaps are counted in, because they are in the paste and will appear in the
        // form. "13 songs" beside a fourteen-line paste is the kind of small
        // disagreement that makes someone distrust the whole handoff.
        let unnamed = log.gaps > 0 ? " \u{00B7} \(log.gaps) unnamed" : ""
        out.append(FilingField(label: "Songs",
                               shown: "\(log.songs.count) songs, in order" + unnamed,
                               value: setlistPaste(log)))
    }
    return out
}

/// Where the Historian is sent to file this night, and it is one of two places.
///
/// A gig setlist.fm already has — including the empty-setlist case, a record with a
/// page and no songs — goes to *its own page*, one click from the right edit form. A
/// gig they have never heard of goes to the generic add flow, which is the only door
/// there is. The clipboard carries the set either way.
func setlistEditEntry(_ setlist: FmSetlist) -> String { setlist.url ?? setlistfmAddURL }
