package io.github.magnusencoded.stationtostation.features.tour

import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.isMyNight
import io.github.magnusencoded.stationtostation.data.visibleToContacts
import io.github.magnusencoded.stationtostation.data.weaveSetlist
import io.github.magnusencoded.stationtostation.ui.isPlanned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Shows a due **Context hint** and remembers it as seen, so it shows once. */
class TourHintEffects(
    private val state: () -> UiState,
    private val update: ((UiState) -> UiState) -> Unit,
    private val store: TourStore,
    private val scope: CoroutineScope,
) {
    fun offer(hint: ContextHint) {
        val current = state()
        if (current.tourUpgradePrompt || current.contextHint != null ||
            !contextHintDue(hint, current.tour.seenContextHints, current.tour.running)
        ) return
        val tour = current.tour.copy(seenContextHints = current.tour.seenContextHints + hint.key)
        update { it.copy(tour = tour, contextHint = hint) }
        scope.launch { store.saveTour(tour) }
    }

    /** The open **Gig**'s hints, one at a time. */
    fun offerInRoom(now: LocalDate = LocalDate.now()) {
        val current = state()
        val gig = current.selectedSetlist ?: return
        val held = current.mediaBySetlist[gig.id].orEmpty()
        val media = if (current.contactLight) visibleToContacts(held) else held
        val songs = weaveSetlist(gig.performed().map { it.name },
            current.logsByGig[gig.id]?.songs.orEmpty().filter { it.isNotBlank() })
        val editable = !current.contactLight && !isPlanned(current.attendanceByGig[gig.id]?.provenance) && isMyNight(
            gig.id, current.attendanceByGig[gig.id], current.setlists, current.plannedGigs,
        )
        offer(ContextHint.PullDown(HintPlace.Room))
        offer(ContextHint.LongPress(if (editable) media.count {
            it.from == null && (it.kind == StoredMedia.Kind.PHOTO || it.kind == StoredMedia.Kind.VIDEO)
        } else 0))
        offer(ContextHint.Flyover(
            night = gig.localDate(), now = now, songs = songs.size,
            photos = media.count { it.kind == StoredMedia.Kind.PHOTO },
        ))
    }

    fun offerProgramme() {
        val current = state()
        val festivals = (current.setlists + current.plannedGigs)
            .mapNotNull { current.festivals.of(it.id)?.id }.toSet()
        offer(ContextHint.Programme(festivals.size))
    }

    fun dismiss() = update { it.copy(contextHint = null) }
}
