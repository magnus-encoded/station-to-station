package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.MatchLevel
import io.github.magnusencoded.stationtostation.data.ParsedTicket
import io.github.magnusencoded.stationtostation.data.SetlistFmCandidate
import io.github.magnusencoded.stationtostation.data.SetlistFmMatch
import io.github.magnusencoded.stationtostation.data.matchSetlistFm
import io.github.magnusencoded.stationtostation.data.rankSetlistFmHits
import io.github.magnusencoded.stationtostation.data.setlistfm.FmArtist
import io.github.magnusencoded.stationtostation.data.setlistfm.SetlistsResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The fixtures in `fixtures/setlistfm-match/` are the contract for #531's matcher: the
 * same **Ticket** and the same recorded `search/setlists` response must come to the
 * same answer, with the same level on every field, in Kotlin and in Swift.
 *
 * Adding a case is adding a directory — this test iterates, it does not enumerate.
 */
class SetlistFmMatchFixturesTest {

    /** What the parser handed over, plus the artists already on the **Line**. */
    @Serializable
    private data class Ticket(
        val artist: String? = null,
        val venue: String? = null,
        val date: String? = null,
        val line: List<FmArtist> = emptyList(),
    )

    @Serializable
    private data class Levels(val artist: String, val date: String, val venue: String? = null)

    @Serializable
    private data class Expected(
        val outcome: String,
        val linked: String? = null,
        val ask: List<String> = emptyList(),
        /** Every hit that survives, by setlist id. A hit absent here must be dropped. */
        val levels: Map<String, Levels> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Walk up from the module dir: the fixtures sit at the repo root, outside android/. */
    private fun fixturesDir(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "fixtures/setlistfm-match") }
            .firstOrNull { it.isDirectory }
            ?: error("fixtures/setlistfm-match not found above ${File("").absolutePath}")

    /** The fixture's spelling, so a failure reads the same as the file it came from. */
    private fun MatchLevel?.word(): String? = when (this) {
        MatchLevel.Strong -> "strong"
        MatchLevel.Weak -> "weak"
        MatchLevel.NoMatch -> "noMatch"
        null -> null
    }

    private fun SetlistFmCandidate.levels() = Levels(artist.word()!!, date.word()!!, venue.word())

    @Test
    fun everyCaseComesToItsExpectedAnswer() {
        val cases = fixturesDir().listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }
        assertTrue("fixtures/setlistfm-match is empty", cases.isNotEmpty())

        for (dir in cases) {
            val name = dir.name
            val input = json.decodeFromString<Ticket>(File(dir, "ticket.json").readText())
            val hits = json.decodeFromString<SetlistsResponse>(File(dir, "search-setlists.json").readText()).setlist
            val expected = json.decodeFromString<Expected>(File(dir, "expected.json").readText())
            val ticket = ParsedTicket(artist = input.artist, venue = input.venue, date = input.date)

            val ranked = rankSetlistFmHits(ticket, hits, input.line)
            assertEquals(
                "$name: levels of the surviving hits",
                expected.levels,
                ranked.associate { it.setlist.id to it.levels() },
            )

            when (val match = matchSetlistFm(ticket, hits, input.line)) {
                is SetlistFmMatch.Linked -> {
                    assertEquals("$name: outcome", expected.outcome, "linked")
                    assertEquals("$name: linked id", expected.linked, match.id)
                }
                is SetlistFmMatch.Ask -> {
                    assertEquals("$name: outcome", expected.outcome, "ask")
                    assertEquals("$name: candidates, best first", expected.ask, match.candidates.map { it.setlist.id })
                }
                SetlistFmMatch.NoMatch -> assertEquals("$name: outcome", expected.outcome, "noMatch")
            }
        }
    }

    /** No artist or no day is nothing to look up with — the spec's own precondition. */
    @Test
    fun aTicketWithoutArtistOrDateMatchesNothing() {
        val dir = File(fixturesDir(), "link-strong-on-every-field")
        val hits = json.decodeFromString<SetlistsResponse>(File(dir, "search-setlists.json").readText()).setlist
        val noArtist = ParsedTicket(venue = "Kjøkkenhagen Scene", date = "12-03-2027")
        val noDate = ParsedTicket(artist = "Ferrous Owls", venue = "Kjøkkenhagen Scene")
        assertEquals(SetlistFmMatch.NoMatch, matchSetlistFm(noArtist, hits, emptyList()))
        assertEquals(SetlistFmMatch.NoMatch, matchSetlistFm(noDate, hits, emptyList()))
    }
}
