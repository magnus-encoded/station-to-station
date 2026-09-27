package io.github.magnusencoded.stationtostation.data.setlistfm

import io.github.magnusencoded.stationtostation.data.SetlistFmCandidate
import io.github.magnusencoded.stationtostation.data.StoredSetlistFmHit
import io.github.magnusencoded.stationtostation.data.StoredSetlistFmLookup
import io.github.magnusencoded.stationtostation.data.parseFmDate

/*
 * The setlist.fm half of a **Ticket** the confirm prompt is showing (#531): what the
 * import's lookup offered, and what the person's answer comes to. Pure, so the prompt's
 * three decisions — a sure hit preselected, an edited artist or date hiding the list,
 * "None of these" rejecting every hit offered — are held by tests rather than by the
 * dialog. The iOS twin rides on `TicketDraft`.
 */

/**
 * What the import's lookup found for a ticket that still needs confirming. [artist] and
 * [date] (dd-MM-yyyy) are what was looked up; [lookedUpAt] is when, epoch millis — the
 * request went out whatever came back. [candidates] may be empty: the lookup ran and
 * found nothing, which is still worth stamping on the Gig the prompt lands on.
 */
data class TicketSetlistFm(
    val candidates: List<SetlistFmCandidate>,
    val preselectedId: String?,
    val artist: String,
    val date: String,
    val lookedUpAt: Long,
) {
    /**
     * Whether the prompt shows [candidates] for the [artist] and [date] currently in its
     * fields. Once either is edited the person has named another night, and a list found
     * for the old one would be answering a question nobody is asking (decision 2).
     */
    fun offeredFor(artist: String, date: String): Boolean =
        candidates.isNotEmpty() && sameSearch(artist, date)

    /** Whether [artist] and [date] are still the ones looked up, give or take spacing. */
    fun sameSearch(artist: String, date: String): Boolean {
        val night = parseFmDate(date.trim()) ?: return false
        return artist.trim() == this.artist.trim() && night == parseFmDate(this.date)
    }

    /**
     * What Save with [chosenId] ticked comes to. A candidate chosen is that hit and
     * nothing rejected; null ("None of these") rejects every candidate offered (decision
     * 3). An edited search offered nothing, so it neither chooses nor rejects (decision
     * 2) — but only a search still standing stamps the lookup.
     */
    fun answer(artist: String, date: String, chosenId: String?): TicketSetlistFmAnswer {
        if (!sameSearch(artist, date)) return TicketSetlistFmAnswer.UNASKED
        val chosen = candidates.firstOrNull { it.setlist.id == chosenId }
        return if (chosen != null) {
            TicketSetlistFmAnswer(chosen.setlist, emptyList(), lookedUpAt)
        } else {
            TicketSetlistFmAnswer(null, candidates.map { it.setlist.id }, lookedUpAt)
        }
    }
}

/**
 * [TicketSetlistFm.answer]: the hit to take ([chosen]), or the ids to remember as not
 * this night ([rejectedIds]), and the lookup to stamp ([lookedUpAt], null for none).
 */
data class TicketSetlistFmAnswer(
    val chosen: FmSetlist?,
    val rejectedIds: List<String>,
    val lookedUpAt: Long?,
) {
    /** Whether a local Gig's stored lookup has anything to learn from this answer. */
    val recordsAnything: Boolean get() = lookedUpAt != null || rejectedIds.isNotEmpty()

    /** [lookup] with this answer written in: stamped, and the rejections added. */
    fun applyTo(lookup: StoredSetlistFmLookup): StoredSetlistFmLookup {
        val stamped = lookedUpAt?.let { lookup.lookedUp(maxOf(it, lookup.lastLookupAt ?: 0L)) } ?: lookup
        return stamped.copy(rejectedIds = (stamped.rejectedIds + rejectedIds).distinct())
    }

    companion object {
        /** The prompt showed no candidates for what was saved: nothing chosen, nothing rejected. */
        val UNASKED = TicketSetlistFmAnswer(null, emptyList(), null)
    }
}

/**
 * The hits a "Possible match on setlist.fm" chip draws: the stored snapshot, held to the
 * ids still pending (the authority), best first. Empty while ids are pending means the
 * snapshot was lost and the chip fetches each id when tapped.
 */
fun StoredSetlistFmLookup.chipHits(): List<StoredSetlistFmHit> =
    pendingHits.filter { it.id in pendingHitIds }.distinctBy { it.id }

/** A candidate row with no question to ask: `artist — venue, city — date`. */
fun StoredSetlistFmHit.line(): String =
    listOf(
        artist.trim(),
        listOf(venue.trim(), city.trim()).filter { it.isNotEmpty() }.joinToString(", "),
        date.trim(),
    ).filter { it.isNotEmpty() }.joinToString(" — ")

/** A stored hit as the matcher would have held it, for a chip whose snapshot was fetched afresh. */
fun FmSetlist.asStoredHit(): StoredSetlistFmHit = StoredSetlistFmHit(
    id = id,
    artist = artist?.name.orEmpty(),
    venue = venue?.name.orEmpty(),
    city = venue?.city?.name.orEmpty(),
    date = eventDate.orEmpty(),
    venueLevel = null,
)
