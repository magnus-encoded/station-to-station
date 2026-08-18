import EventKit
import Foundation

/// Inserts a calendar event for a gig you're going to and hands back its identifier —
/// the iOS twin of Android's `insertCalendarEvent` (`ui/CalendarInsert.kt`, #175).
///
/// EventKit's `eventIdentifier` is the handle kept in `gigCalendarEvent`, playing the
/// same role Android's content URI does: proof an event exists, and enough to find it
/// again later.
///
/// Returns nil on anything short of success — no date to place it on, access refused,
/// no writable calendar, or the store's own save failing — so the caller degrades to a
/// toast/banner exactly as Android's does. Never throws: a denied permission is not a
/// bug, and a crash on first calendar access is the one thing this must not do.
///
/// Runs on the actor EventKit already serializes its own work on; no explicit background
/// hop is needed the way Android's ContentResolver call needs one.
func insertCalendarEvent(_ setlist: FmSetlist) async -> String? {
    guard let day = setlist.localDate() else { return nil }
    let store = EKEventStore()
    guard await requestCalendarAccess(store),
          let calendar = writableCalendar(store)
    else { return nil }

    var comps = Calendar.current.dateComponents([.year, .month, .day], from: day)
    comps.hour = 19
    comps.minute = 0
    guard let start = Calendar.current.date(from: comps) else { return nil }

    let event = EKEvent(eventStore: store)
    event.calendar = calendar
    event.title = setlist.artist?.name ?? "Concert"
    event.location = setlist.venueLine()
    event.startDate = start
    // setlist.fm records no running time; a three-hour block is a sane default the
    // user trims in their own calendar app — same spirit as the 19:00 start.
    event.endDate = start.addingTimeInterval(3 * 60 * 60)

    do {
        try store.save(event, span: .thisEvent)
        return event.eventIdentifier
    } catch {
        return nil
    }
}

/// Which calendar to write into: EventKit's own default, or the first one that accepts
/// new events. Mirrors Android's "primary, else first visible" — good enough, since the
/// user can move the event in their own calendar app if it lands on the wrong one, and
/// choosing a calendar is not a decision this app owns.
private func writableCalendar(_ store: EKEventStore) -> EKCalendar? {
    store.defaultCalendarForNewEvents ?? store.calendars(for: .event).first { $0.allowsContentModifications }
}

/// Asks for calendar access, whichever API this iOS version has. iOS 17 split the old
/// blanket `requestAccess(to:)` into a full/write-only pair and deprecated it; targeting
/// iOS 16 (see project.yml) means the old call still has to work on the versions that
/// only have it, so this picks whichever one the running device supports.
private func requestCalendarAccess(_ store: EKEventStore) async -> Bool {
    if #available(iOS 17.0, *) {
        return (try? await store.requestFullAccessToEvents()) ?? false
    }
    return await withCheckedContinuation { continuation in
        store.requestAccess(to: .event) { granted, _ in continuation.resume(returning: granted) }
    }
}
