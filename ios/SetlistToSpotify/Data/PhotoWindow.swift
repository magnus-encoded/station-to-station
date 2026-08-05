import Foundation

/// The night a **Gig** was photographed on, and what a keepsake's capture time is
/// when the file does not carry one.
///
/// This is the logic layer (ADR-0001): a domain decision, not a MediaStore or
/// PhotoKit detail, so it is the same rule on both platforms and both assert it
/// from the same cases. The plumbing that runs a query with it — `PhotoLibrary`
/// here, `PhotoRepository` there — is idiomatic per platform and is not.

/// The hour the night is taken to end. A set that starts at 23:00 is photographed
/// on the following calendar day, and setlist.fm dates the show by when it
/// *started*, so the window has to reach past midnight or the whole night is
/// missed.
let photoWindowEndHour = 6

/// The gig's date plus the small hours after it, in epoch milliseconds, inclusive
/// at both ends.
///
/// `gigDate` is setlist.fm's `dd-MM-yyyy`, which is what a **Gig** carries. Nil
/// for a date that will not parse — a night whose date is unknown has no window,
/// which is different from an empty one.
///
/// The calendar is injected so the window can be asserted against fixed epoch
/// numbers, the same ones Android's `PhotoWindowTest` asserts.
func photoWindow(gigDate: String, calendar: Calendar = .current) -> ClosedRange<Int64>? {
    let parts = gigDate.split(separator: "-").compactMap { Int($0) }
    guard parts.count == 3 else { return nil }
    var day = DateComponents()
    day.day = parts[0]
    day.month = parts[1]
    day.year = parts[2]
    // No hour set, so this is the start of that day in `calendar`'s own zone —
    // Android's `date.atStartOfDay(zone)`.
    guard let from = calendar.date(from: day),
          let nextDay = calendar.date(byAdding: .day, value: 1, to: from),
          let to = calendar.date(bySettingHour: photoWindowEndHour, minute: 0, second: 0, of: nextDay)
    else { return nil }
    return epochMs(from)...epochMs(to)
}

/// When the camera took it — not when it was attached, which is admin rather than
/// history.
///
/// `taken` is the camera's own stamp (EXIF on Android, `PHAsset.creationDate`
/// here) and is absent on anything carrying no timestamp. `added` is the library's
/// own record of first seeing it (`DATE_ADDED` / `PHAsset.modificationDate`) and
/// is at least the right order of magnitude for a night.
///
/// Nil when neither answers, because a wrong timestamp on a keepsake is worse than
/// an honest gap. A non-positive value counts as absent: a zero epoch is a missing
/// field written out rather than a photo taken in 1970.
func capturedAtMs(taken: Int64?, added: Int64?) -> Int64? {
    if let taken, taken > 0 { return taken }
    if let added, added > 0 { return added }
    return nil
}

/// Whether a keepsake belongs to the night `window` describes. Anything with no
/// usable timestamp at all is out: it cannot be shown to be from that night.
func isInPhotoWindow(_ window: ClosedRange<Int64>, taken: Int64?, added: Int64?) -> Bool {
    guard let at = capturedAtMs(taken: taken, added: added) else { return false }
    return window.contains(at)
}

func epochMs(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }
