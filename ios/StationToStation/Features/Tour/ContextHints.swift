import Foundation

/// Where a pull-down does something, so each place gets its own **Context hint**.
enum HintPlace: String, Equatable {
    case room, programme
}

/// A **Context hint**, carrying the facts its rule reads. The rules are pure: app state
/// and the hints already seen in, a yes or no out.
enum ContextHint: Equatable {
    case longPress(editableRows: Int)
    case pullDown(HintPlace)
    case legendTap(hiding: Bool)
    case flyover(night: Date?, now: Date, songs: Int, photos: Int)
    case programme(festivals: Int)

    /// What `TourState.seenHints` remembers. A pull-down is one hint per place.
    var key: String {
        switch self {
        case .longPress: return "longPress"
        case .pullDown(let place): return "pullDown.\(place.rawValue)"
        case .legendTap: return "legendTap"
        case .flyover: return "flyover"
        case .programme: return "programme"
        }
    }

    /// Whether the feature is worth explaining now, before seen hints and the Tour are asked.
    var relevant: Bool {
        switch self {
        case .longPress(let rows): return rows >= 2
        case .pullDown: return true
        case .legendTap(let hiding): return hiding
        case .flyover(let night, let now, let songs, let photos):
            // Nothing worth seeing is an empty corridor, so no hint.
            guard let night, night < Calendar.current.startOfDay(for: now) else { return false }
            return songs > 0 && photos > 0
        case .programme(let festivals): return festivals >= 1
        }
    }

    var text: String {
        switch self {
        case .longPress: return "Long-press a row to lift it."
        case .pullDown(.room): return "Pull down to ask the source what it knows about this night now."
        case .pullDown(.programme): return "Pull down to fetch the festival's timetable again."
        case .legendTap: return "Hidden, not gone. Tap the name again to show the line."
        case .flyover: return "Try rotating your phone to travel through the night."
        case .programme: return "Open the festival Programme to plan the stages and see the clashes."
        }
    }
}

/// The one rule over every hint: relevant, never seen, and no Tour running.
func contextHintDue(_ hint: ContextHint, seen: Set<String>, tourRunning: Bool) -> Bool {
    !tourRunning && !seen.contains(hint.key) && hint.relevant
}
