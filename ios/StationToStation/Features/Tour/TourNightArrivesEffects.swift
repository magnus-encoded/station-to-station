import Foundation

/// The night arrives (S10–S13): the **Demo clock**, and the calendar event the demo **Gig**
/// gets.
///
/// The **Demo clock** is an instant on the demo **Gig**'s own date, kept across launches so
/// a resumed Tour shows the same moment. It is read only for the demo **Gig** and only while
/// the Tour runs; every other **Gig**, and every other path, keeps the real clock. The purge
/// clears it and deletes the calendar event, so after a skip or the end nothing of it is left.
@MainActor
final class TourNightArrivesEffects: DemoWorld {
    private static let clockKey = "tour.demoClock"
    private static let eventsKey = "tour.demoCalendarEvents"

    private let store: UserDefaults
    private let calendar: Calendar
    private let demoGig: () -> FmSetlist?
    private let deleteCalendarEvent: (String) -> Void

    init(store: UserDefaults = .standard, calendar: Calendar = .current,
         demoGig: @escaping () -> FmSetlist?,
         deleteCalendarEvent: @escaping (String) -> Void) {
        self.store = store
        self.calendar = calendar
        self.demoGig = demoGig
        self.deleteCalendarEvent = deleteCalendarEvent
    }

    /// The **Demo clock**, or nil before the script first advances it.
    var demoNow: Date? {
        (store.object(forKey: Self.clockKey) as? Double).map { Date(timeIntervalSince1970: $0) }
    }

    var calendarEventIds: [String] { store.stringArray(forKey: Self.eventsKey) ?? [] }

    func advance(to mark: DemoClockMark) {
        guard let date = demoGig()?.eventDate,
              let instant = Self.instant(mark, gigDate: date, calendar: calendar) else { return }
        store.set(instant.timeIntervalSince1970, forKey: Self.clockKey)
    }

    func recordCalendarEvent(_ eventId: String) {
        guard !calendarEventIds.contains(eventId) else { return }
        store.set(calendarEventIds + [eventId], forKey: Self.eventsKey)
    }

    func purge() {
        calendarEventIds.forEach(deleteCalendarEvent)
        store.removeObject(forKey: Self.eventsKey)
        store.removeObject(forKey: Self.clockKey)
    }

    /// Where each mark puts the clock, the same instants as Android: noon the day before,
    /// doors at 19:00, the show at 21:00, and noon the day after.
    static func instant(_ mark: DemoClockMark, gigDate: String, calendar: Calendar = .current) -> Date? {
        guard let day = gigDay(gigDate, calendar: calendar) else { return nil }
        let offset: Int, hour: Int
        switch mark {
        case .approaching: (offset, hour) = (-1, 12)
        case .doors: (offset, hour) = (0, 19)
        case .showStarted: (offset, hour) = (0, 21)
        case .after: (offset, hour) = (1, 12)
        }
        guard let target = calendar.date(byAdding: .day, value: offset, to: day) else { return nil }
        return calendar.date(bySettingHour: hour, minute: 0, second: 0, of: target)
    }

    /// Maps at the demo venue: the point S7 placed where the person stood, named as the
    /// venue is. Nil where no fix came, and the plain venue query is used instead.
    static func mapsURL(for gig: FmSetlist) -> URL? {
        guard let coords = gig.cityCoords() else { return nil }
        var components = URLComponents(string: "http://maps.apple.com/")
        components?.queryItems = [URLQueryItem(name: "ll", value: "\(coords.lat),\(coords.lon)")]
            + (gig.venue?.name?.nilIfBlank.map { [URLQueryItem(name: "q", value: $0)] } ?? [])
        return components?.url
    }
}
