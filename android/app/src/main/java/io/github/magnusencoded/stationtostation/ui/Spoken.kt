package io.github.magnusencoded.stationtostation.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

// Two semantics-only modifiers for TalkBack (#164). Neither changes what is drawn.

/**
 * Status text that changes while focus is somewhere else — a lookup starting or
 * finishing, an error appearing in place, a transfer reaching its next phase. TalkBack
 * reads it out when it appears or its text changes, after whatever it is saying.
 *
 * Only for real status changes. Text that changes on every keystroke or every item
 * would talk over the person, which is worse than saying nothing.
 */
internal fun Modifier.spokenOnChange(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }

/** A screen's title or a section's header, so TalkBack's heading navigation stops on it. */
internal fun Modifier.asHeading(): Modifier = semantics { heading() }
