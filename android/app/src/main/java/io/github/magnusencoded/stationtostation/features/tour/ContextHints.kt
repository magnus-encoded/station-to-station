package io.github.magnusencoded.stationtostation.features.tour

import java.time.LocalDate

/** Where a pull-down does something, so each place gets its own **Context hint**. */
enum class HintPlace(val key: String, val text: String) {
    Room("room", "Pull down to ask the source what it knows about this night now."),
    Programme("programme", "Pull down to fetch the festival's timetable again."),
    Exchange("exchange", "Pull down to look for people nearby again."),
}

/**
 * A **Context hint**, carrying the facts its rule reads. The rules are pure: app state and
 * the hints already seen in, a yes or no out.
 */
sealed interface ContextHint {
    /** What `TourState.seenContextHints` remembers. A pull-down is one hint per place. */
    val key: String
    /** Whether the feature is worth explaining now, before seen hints and the Tour are asked. */
    val relevant: Boolean
    val text: String

    data class LongPress(val editableRows: Int) : ContextHint {
        override val key = "longPress"
        override val relevant get() = editableRows >= 2
        override val text = "Long-press a photo or video to lift it."
    }

    data class PullDown(val place: HintPlace) : ContextHint {
        override val key get() = "pullDown.${place.key}"
        override val relevant = true
        override val text get() = place.text
    }

    data class LegendTap(val hiding: Boolean) : ContextHint {
        override val key = "legendTap"
        override val relevant get() = hiding
        override val text = "Hidden, not gone. Tap the name again to show the line."
    }

    data class Flyover(val night: LocalDate?, val now: LocalDate, val songs: Int, val photos: Int) : ContextHint {
        override val key = "flyover"
        // Nothing worth seeing is an empty corridor, so no hint.
        override val relevant get() = night != null && night < now && songs > 0 && photos > 0
        override val text = "Try rotating your phone to travel through the night."
    }

    data class Programme(val festivals: Int) : ContextHint {
        override val key = "programme"
        override val relevant get() = festivals >= 1
        override val text = "Open the festival Programme to plan the stages and see the clashes."
    }
}

/** The one rule over every hint: relevant, never seen, and no Tour running. */
fun contextHintDue(hint: ContextHint, seen: Set<String>, tourRunning: Boolean): Boolean =
    !tourRunning && hint.key !in seen && hint.relevant
