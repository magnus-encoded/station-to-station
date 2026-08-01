package io.github.magnusencoded.setlist2spotify.data.setlistfm

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

@Serializable
data class ArtistSearchResponse(
    val artist: List<FmArtist> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val itemsPerPage: Int = 20,
)

@Serializable
data class FmArtist(
    val mbid: String = "",
    val name: String = "",
    val sortName: String? = null,
    val disambiguation: String? = null,
)

@Serializable
data class SetlistsResponse(
    val setlist: List<FmSetlist> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val itemsPerPage: Int = 20,
)

@Serializable
data class FmSetlist(
    val id: String = "",
    val eventDate: String? = null,
    val artist: FmArtist? = null,
    val venue: FmVenue? = null,
    val tour: FmTour? = null,
    val sets: FmSets? = null,
    val url: String? = null,
    /** Free-text note. Arbitrary — "First show in Norway", not the festival name. */
    val info: String? = null,
) {
    /** The raw record, exactly as setlist.fm logged it. See [performed]. */
    fun songs(): List<FmSong> = sets?.set.orEmpty().flatMap { it.song }

    /**
     * The songs the band actually played. [songs] also carries tape tracks — walk-on
     * and interval recordings that were in the room but nobody counts as part of the
     * set — and the nameless placeholders setlist.fm emits for a song no one could
     * identify. Every count and every number a user reads means this list, not [songs].
     */
    fun performed(): List<FmSong> = songs().filter { !it.tape && it.name.isNotBlank() }
    fun venueLine(): String {
        val v = venue?.name ?: "Unknown venue"
        val city = venue?.city?.name
        val country = venue?.city?.country?.name
        return listOfNotNull(v, city, country).joinToString(", ")
    }

    /** setlist.fm sends the event date as dd-MM-yyyy. */
    fun localDate(): LocalDate? = eventDate?.let {
        try {
            LocalDate.parse(it, DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH))
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /** Leads the playlist name, so a library of setlists sorts by year. */
    fun year(): String? =
        localDate()?.year?.toString() ?: eventDate?.substringAfterLast('-')?.takeIf { it.length == 4 }

    /**
     * "24 June 2026" — the playlist name carries only the year, so the day and
     * month have to survive in the description. Fixed to English so a playlist
     * does not read differently depending on the phone's locale.
     */
    fun readableDate(): String? =
        localDate()?.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)) ?: eventDate

    /** "24 Jun 2026" — the timeline row's date column, same format for every row on it. */
    fun readableDateShort(): String? =
        localDate()?.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)) ?: eventDate
}

@Serializable
data class FmVenue(
    val name: String? = null,
    val city: FmCity? = null,
)

@Serializable
data class FmCity(
    val name: String? = null,
    val country: FmCountry? = null,
    /** Present on every record the API returns — but of the *city*, not the venue. */
    val coords: FmCoords? = null,
)

/**
 * setlist.fm's city-centre coordinates. Free with every setlist, and too coarse to
 * say you are at a venue — good enough only to say you are in the right city.
 * `long`, not `lon`: that is the wire name.
 */
@Serializable
data class FmCoords(
    val lat: Double? = null,
    val long: Double? = null,
)

@Serializable
data class FmCountry(
    val name: String? = null,
)

@Serializable
data class FmTour(
    val name: String? = null,
)

@Serializable
data class FmSets(
    val set: List<FmSet> = emptyList(),
)

@Serializable
data class FmSet(
    val name: String? = null,
    val encore: Int? = null,
    val song: List<FmSong> = emptyList(),
)

@Serializable
data class FmSong(
    val name: String = "",
    val info: String? = null,
    val tape: Boolean = false,
    val cover: FmArtist? = null,
)
