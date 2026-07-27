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
    /** Free-text note; for festival shows setlist.fm puts the festival name here. */
    val info: String? = null,
) {
    fun songs(): List<FmSong> = sets?.set.orEmpty().flatMap { it.song }
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
