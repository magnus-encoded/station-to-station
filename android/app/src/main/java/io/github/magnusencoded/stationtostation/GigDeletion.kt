package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.GigDeletionOutcome
import io.github.magnusencoded.stationtostation.data.GigStorage

/**
 * Drops the **Gig** from screen state, including the cached attended list, or it returns when my **Line** is next read.
 * The selection closes only for this **Gig**: a long-press delete must not close another
 * **Gig**'s **Room**. One **Log** is held per **Gig**, so it goes with the **Gig**.
 */
fun UiState.deletingGig(gigId: String, me: String): UiState = copy(
    plannedGigs = plannedGigs.filterNot { it.id == gigId },
    setlists = setlists.filterNot { it.id == gigId },
    showsByFriend = showsByFriend + (me to showsByFriend[me].orEmpty().filterNot { it.id == gigId }),
    attendanceByGig = attendanceByGig - gigId,
    logsByGig = logsByGig - gigId,
    mediaBySetlist = mediaBySetlist - gigId,
    playlistsBySetlist = playlistsBySetlist - gigId,
    calendarEventByGig = calendarEventByGig - gigId,
    selectedSetlist = selectedSetlist?.takeUnless { it.id == gigId },
)

/** The storage decides first. The screen follows only a [GigDeletionOutcome.DELETED]; a KEPT changes nothing. */
suspend fun deleteFromStorage(
    gigId: String,
    storage: GigStorage,
    thenOnScreen: () -> Unit,
): GigDeletionOutcome =
    storage.delete(gigId).also { if (it == GigDeletionOutcome.DELETED) thenOnScreen() }
