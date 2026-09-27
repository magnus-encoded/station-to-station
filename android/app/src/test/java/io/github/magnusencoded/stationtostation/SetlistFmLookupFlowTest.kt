package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.MatchLevel
import io.github.magnusencoded.stationtostation.data.ParsedTicket
import io.github.magnusencoded.stationtostation.data.SetlistFmCandidate
import io.github.magnusencoded.stationtostation.data.SetlistFmMatch
import io.github.magnusencoded.stationtostation.data.StoredAttendance
import io.github.magnusencoded.stationtostation.data.StoredSetlistFmHit
import io.github.magnusencoded.stationtostation.data.StoredSetlistFmLookup
import io.github.magnusencoded.stationtostation.data.TicketRouting
import io.github.magnusencoded.stationtostation.data.TimelineStore
import io.github.magnusencoded.stationtostation.data.setlistfm.FmArtist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmCity
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmVenue
import io.github.magnusencoded.stationtostation.data.setlistfm.LookupGig
import io.github.magnusencoded.stationtostation.data.setlistfm.LookupOutcome
import io.github.magnusencoded.stationtostation.data.setlistfm.SetlistsResponse
import io.github.magnusencoded.stationtostation.data.setlistfm.TicketImport
import io.github.magnusencoded.stationtostation.data.setlistfm.setlistFmLookupOutcome
import io.github.magnusencoded.stationtostation.data.setlistfm.setlistFmLookupPlan
import io.github.magnusencoded.stationtostation.data.setlistfm.setlistFmQuestion
import io.github.magnusencoded.stationtostation.data.setlistfm.ticketImport
import io.github.magnusencoded.stationtostation.data.unionAttendance
import io.github.magnusencoded.stationtostation.data.unionSetlistFmLookup
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

/**
 * #531's lookup flow, decided without the network: what one lookup comes to, what the
 * loop looks up next, what a shared ticket becomes, the question a chip row asks, and
 * the stored state all of that leaves behind.
 *
 * `fixtures/setlistfm-outcome/`, `fixtures/setlistfm-lookup/plan.json` and
 * `fixtures/setlistfm-question/cases.json` are shared with iOS's
 * `SetlistFmLookupFlowTests`, which asserts them case for case; so is the ticketImport
 * table's every case name. Everything here is synthetic.
 */
class SetlistFmLookupFlowTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Walk up from the module dir: the fixtures sit at the repo root, outside android/. */
    private fun fixture(path: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "fixtures/$path") }
            .firstOrNull { it.exists() }
            ?: error("fixtures/$path not found above ${File("").absolutePath}")

    private fun tempFile(contents: String? = null): File =
        File.createTempFile("timelines", ".json").also { if (contents == null) it.delete() else it.writeText(contents) }

    // --- fixtures/setlistfm-outcome ------------------------------------------

    @Serializable
    private data class TicketIn(val artist: String? = null, val venue: String? = null, val date: String? = null)

    @Serializable
    private data class Before(val now: Long, val lookup: StoredSetlistFmLookup? = null)

    @Serializable
    private data class Expected(
        val outcome: String,
        val adopt: String? = null,
        val ask: List<String> = emptyList(),
        val after: StoredSetlistFmLookup,
    )

    @Test
    fun `every outcome case comes to its answer and its stored state`() {
        val cases = fixture("setlistfm-outcome").listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }
        assertTrue("fixtures/setlistfm-outcome is empty", cases.isNotEmpty())

        for (dir in cases) {
            val name = dir.name
            val input = json.decodeFromString<TicketIn>(File(dir, "ticket.json").readText())
            val hits = json.decodeFromString<SetlistsResponse>(File(dir, "search-setlists.json").readText()).setlist
            val before = json.decodeFromString<Before>(File(dir, "before.json").readText())
            val expected = json.decodeFromString<Expected>(File(dir, "expected.json").readText())
            val ticket = ParsedTicket(artist = input.artist, venue = input.venue, date = input.date)

            val outcome = setlistFmLookupOutcome(ticket, hits, emptyList(), before.lookup, before.now)
            when (outcome) {
                is LookupOutcome.Adopt -> {
                    assertEquals("$name: outcome", expected.outcome, "adopt")
                    assertEquals("$name: adopted id", expected.adopt, outcome.hit.id)
                }
                is LookupOutcome.Ask -> {
                    assertEquals("$name: outcome", expected.outcome, "ask")
                    assertEquals("$name: candidates, best first", expected.ask, outcome.candidates.map { it.setlist.id })
                }
                is LookupOutcome.Nothing -> assertEquals("$name: outcome", expected.outcome, "nothing")
            }
            assertEquals("$name: stored lookup after", expected.after, outcome.next)
        }
    }

    // --- fixtures/setlistfm-lookup/plan.json ---------------------------------

    @Serializable
    private data class PlanGig(
        val id: String,
        val date: String,
        val local: Boolean,
        val lookup: StoredSetlistFmLookup? = null,
        val participationUntil: String? = null,
    )

    @Serializable
    private data class PlanCase(
        val name: String,
        val now: String,
        val sharedKey: Boolean = true,
        val sharedQuotaSpentAt: String? = null,
        val gigs: List<PlanGig>,
        val dueNow: List<String>,
        val nextWakeAt: String? = null,
    )

    @Serializable
    private data class PlanFile(val cases: List<PlanCase>)

    @Test
    fun `every plan case looks up the right Gigs in order and wakes when it should`() {
        val cases = json.decodeFromString<PlanFile>(fixture("setlistfm-lookup/plan.json").readText()).cases
        assertTrue("plan.json has no cases", cases.isNotEmpty())

        for (c in cases) {
            val plan = setlistFmLookupPlan(
                gigs = c.gigs.map { g ->
                    LookupGig(g.id, g.date, g.local, g.lookup, g.participationUntil?.let { Instant.parse(it) })
                },
                now = Instant.parse(c.now),
                zone = ZoneOffset.UTC,
                sharedKey = c.sharedKey,
                sharedQuotaSpentAt = c.sharedQuotaSpentAt?.let { Instant.parse(it).toEpochMilli() },
            )
            assertEquals("${c.name}: due now", c.dueNow, plan.dueNow)
            assertEquals("${c.name}: next wake", c.nextWakeAt?.let { Instant.parse(it) }, plan.nextWakeAt)
        }
    }

    // --- fixtures/setlistfm-question/cases.json ------------------------------

    @Serializable
    private data class QuestionCase(
        val name: String,
        val yourVenue: String? = null,
        val fromTicket: Boolean,
        val hit: StoredSetlistFmHit,
        val expect: String? = null,
    )

    @Serializable
    private data class QuestionFile(val cases: List<QuestionCase>)

    @Test
    fun `every question case asks exactly its question or none`() {
        val cases = json.decodeFromString<QuestionFile>(fixture("setlistfm-question/cases.json").readText()).cases
        assertTrue("cases.json has no cases", cases.isNotEmpty())

        for (c in cases) {
            assertEquals(c.name, c.expect, setlistFmQuestion(c.yourVenue, c.fromTicket, c.hit))
        }
    }

    @Test
    fun `a candidate asks what its stored hit would`() {
        val candidate = SetlistFmCandidate(hit("t1000002", "Driftshallen"), MatchLevel.Strong, MatchLevel.Strong, MatchLevel.Weak)
        assertEquals(
            "Your ticket says Kjøkkenhagen Scene. setlist.fm lists it at Driftshallen. Same gig?",
            setlistFmQuestion("Kjøkkenhagen Scene", true, candidate),
        )
        assertEquals(
            StoredSetlistFmHit("t1000002", "Ferrous Owls", "Driftshallen", "Tromsø", "12-03-2027", "weak"),
            StoredSetlistFmHit.of(candidate),
        )
    }

    // --- ticketImport: the #531 table ----------------------------------------
    // Case names and summaries are word for word with iOS's `SetlistFmLookupFlowTests`.

    private fun hit(id: String, venue: String = "Kjøkkenhagen Scene") = FmSetlist(
        id = id,
        eventDate = "12-03-2027",
        artist = FmArtist(name = "Ferrous Owls"),
        venue = FmVenue(name = venue, city = FmCity(name = "Tromsø")),
        url = "https://www.setlist.fm/setlist/ferrous-owls/2027/$id.html",
    )

    private val sure = SetlistFmCandidate(hit("t1000001"), MatchLevel.Strong, MatchLevel.Strong, MatchLevel.Strong)
    private val question = listOf(
        SetlistFmCandidate(hit("t1000002", "Driftshallen"), MatchLevel.Strong, MatchLevel.Strong, MatchLevel.Weak),
        SetlistFmCandidate(hit("t1000003", "Kaikanten"), MatchLevel.Strong, MatchLevel.Weak, MatchLevel.NoMatch),
    )
    private val localGig = FmSetlist(id = "g-local", eventDate = "12-03-2027", artist = FmArtist(name = "Ferrous Owls"))
    private val fmGig = hit("t9000001")

    private val newNight = TicketRouting.NewPlannedGig("Ferrous Owls", "Kjøkkenhagen Scene", "12-03-2027", emptyList())
    private val unsure = TicketRouting.NeedsConfirmation(ParsedTicket(artist = "Ferrous Owls"), possibleMatch = null)

    private data class ImportCase(
        val name: String,
        val routing: TicketRouting,
        val match: SetlistFmMatch?,
        val knownIds: Set<String>,
        val expect: String,
    )

    private val importCases = listOf(
        ImportCase("known local Gig: attach, then look it up", TicketRouting.AlreadyKnown(localGig), null, setOf("t9000001"), "attachThenLookUp g-local"),
        ImportCase("known setlist.fm Gig: attach", TicketRouting.AlreadyKnown(fmGig), null, setOf("t9000001"), "attach t9000001"),
        ImportCase("new night, sure hit already on the Line: attach to it", newNight, SetlistFmMatch.Linked(sure), setOf("t9000001", "t1000001"), "attach t1000001"),
        ImportCase("new night, sure hit: mint from setlist.fm", newNight, SetlistFmMatch.Linked(sure), setOf("t9000001"), "mintFromSetlistFm t1000001"),
        ImportCase("new night, a question: prompt", newNight, SetlistFmMatch.Ask(question), setOf("t9000001"), "prompt [t1000002,t1000003] preselected none"),
        ImportCase("new night, no match: mint local", newNight, SetlistFmMatch.NoMatch, setOf("t9000001"), "mintLocal"),
        ImportCase("new night, not looked up: mint local", newNight, null, setOf("t9000001"), "mintLocal"),
        ImportCase("unsure parse, sure hit: prompt with it ticked", unsure, SetlistFmMatch.Linked(sure), setOf("t9000001"), "prompt [t1000001] preselected t1000001"),
        ImportCase("unsure parse, a question: prompt", unsure, SetlistFmMatch.Ask(question), setOf("t9000001"), "prompt [t1000002,t1000003] preselected none"),
        ImportCase("unsure parse, no match: prompt with none", unsure, SetlistFmMatch.NoMatch, setOf("t9000001"), "prompt [] preselected none"),
        ImportCase("unsure parse, not looked up: prompt with none", unsure, null, setOf("t9000001"), "prompt [] preselected none"),
    )

    /** One line per result, spelt the same on iOS, so the two tables compare by eye. */
    private fun TicketImport.summary(): String = when (this) {
        is TicketImport.Attach -> "attach $gigId"
        is TicketImport.AttachThenLookUp -> "attachThenLookUp $gigId"
        is TicketImport.MintFromSetlistFm -> "mintFromSetlistFm ${hit.id}"
        TicketImport.MintLocal -> "mintLocal"
        is TicketImport.Prompt ->
            "prompt [${candidates.joinToString(",") { it.setlist.id }}] preselected ${preselectedId ?: "none"}"
    }

    @Test
    fun `every routing and lookup comes to the import the table says`() {
        for (c in importCases) {
            assertEquals(c.name, c.expect, ticketImport(c.routing, c.match, c.knownIds).summary())
        }
    }

    // --- The stored state -----------------------------------------------------

    private val chip = listOf(
        StoredSetlistFmHit("t1000002", "Ferrous Owls", "Driftshallen", "Tromsø", "12-03-2027", "weak"),
        StoredSetlistFmHit("t1000003", "Ferrous Owls", "Kaikanten", "Tromsø", "13-03-2027", "noMatch"),
    )

    @Test
    fun `asking sets the chip, and None of these rejects it and clears the snapshot`() {
        val asked = StoredSetlistFmLookup(lastLookupAt = 5, rejectedIds = listOf("t0000001")).asking(chip)
        assertEquals(listOf("t1000002", "t1000003"), asked.pendingHitIds)
        assertEquals(chip, asked.pendingHits)
        assertTrue(asked.possibleMatchPending)

        val rejected = asked.rejectingPending()
        assertEquals(
            StoredSetlistFmLookup(lastLookupAt = 5, rejectedIds = listOf("t0000001", "t1000002", "t1000003")),
            rejected,
        )
    }

    @Test
    fun `pendingHits round-trips through the store`() = runBlocking {
        val file = tempFile()
        val store = TimelineStore(file)
        val lookup = StoredSetlistFmLookup(lastLookupAt = 1804881600000, rejectedIds = listOf("t0000001")).asking(chip)
        store.saveAttendance("t9000001", StoredAttendance(setlistFmLookup = lookup))

        val reloaded = TimelineStore(file).load().attendance()["t9000001"]
        assertEquals(lookup, reloaded?.setlistFmLookup)
        assertTrue(file.readText().contains("\"pendingHits\""))
    }

    @Test
    fun `a malformed lookup costs only what is malformed, never the night`() = runBlocking {
        val store = TimelineStore(
            tempFile(
                """{"gigs":{"g1":{"id":"g1","date":"12-03-2027","artist":"Ferrous Owls","venue":"","createdAt":1}},""" +
                    """"gigAttendance":{"g1":{"provenance":"planned","setlistFmLookup":{"lastLookupAt":"soon",""" +
                    """"rejectedIds":["t0000001",7],"pendingHitIds":["t1000002","t1000003"],"pendingHits":[""" +
                    """{"id":"t1000002","artist":"Ferrous Owls","venue":"Driftshallen","city":"Tromsø","date":"12-03-2027","venueLevel":"weak"},""" +
                    """"not an object",null,7,{"id":5,"venue":"Kaikanten","venueLevel":3}],"future":true}}}}""",
            ),
        )

        val loaded = store.load()

        assertEquals(setOf("g1"), loaded.gigs.keys)
        assertEquals(
            StoredAttendance(
                provenance = StoredAttendance.Provenance.PLANNED,
                setlistFmLookup = StoredSetlistFmLookup(
                    pendingHitIds = listOf("t1000002", "t1000003"),
                    pendingHits = listOf(chip[0], StoredSetlistFmHit(venue = "Kaikanten")),
                ),
            ),
            loaded.gigAttendance["g1"],
        )
    }

    @Test
    fun `a lookup that is not an object reads as never looked up and the night survives`() = runBlocking {
        val store = TimelineStore(
            tempFile(
                """{"gigs":{"g1":{"id":"g1","date":"12-03-2027","artist":"Ferrous Owls","venue":"","createdAt":1}},""" +
                    """"gigAttendance":{"g1":{"provenance":"attended","setlistFmLookup":"oops"}}}""",
            ),
        )

        val loaded = store.load()

        assertEquals(StoredAttendance(provenance = StoredAttendance.Provenance.ATTENDED), loaded.gigAttendance["g1"])
    }

    @Test
    fun `a lookup missing fields reads them as empty`() = runBlocking {
        val store = TimelineStore(
            tempFile(
                """{"gigs":{"g1":{"id":"g1","date":"12-03-2027","artist":"Ferrous Owls","venue":"","createdAt":1}},""" +
                    """"gigAttendance":{"g1":{"provenance":"planned","setlistFmLookup":{"lastLookupAt":7}}}}""",
            ),
        )

        assertEquals(StoredSetlistFmLookup(lastLookupAt = 7), store.load().gigAttendance["g1"]?.setlistFmLookup)
    }

    // --- unionAttendance ------------------------------------------------------

    @Test
    fun `the lookup survives a merge whichever record wins`() {
        val lookup = StoredSetlistFmLookup(lastLookupAt = 5, rejectedIds = listOf("t0000001")).asking(chip)
        val planned = StoredAttendance(setlistFmLookup = lookup)
        val attended = StoredAttendance(provenance = StoredAttendance.Provenance.ATTENDED)

        assertEquals(lookup, unionAttendance(planned, attended).setlistFmLookup)
        assertEquals(lookup, unionAttendance(attended, planned).setlistFmLookup)
        assertEquals(StoredAttendance.Provenance.ATTENDED, unionAttendance(planned, attended).provenance)
    }

    @Test
    fun `two lookups merge their rejections, keep the later stamp, and drop a chip hit either side rejected`() {
        val winner = StoredSetlistFmLookup(lastLookupAt = 5, rejectedIds = listOf("t0000001")).asking(chip)
        val other = StoredSetlistFmLookup(lastLookupAt = 9, rejectedIds = listOf("t1000002", "t0000001"))

        assertEquals(
            StoredSetlistFmLookup(
                lastLookupAt = 9,
                rejectedIds = listOf("t0000001", "t1000002"),
                pendingHitIds = listOf("t1000003"),
                pendingHits = listOf(chip[1]),
            ),
            unionSetlistFmLookup(winner, other),
        )
    }

    @Test
    fun `the chip comes from the other side when the winner has none`() {
        val winner = StoredSetlistFmLookup(lastLookupAt = 9)
        val other = StoredSetlistFmLookup(lastLookupAt = 5).asking(chip)

        assertEquals(StoredSetlistFmLookup(lastLookupAt = 9).asking(chip), unionSetlistFmLookup(winner, other))
        assertEquals(other, unionSetlistFmLookup(null, other))
        assertEquals(winner, unionSetlistFmLookup(winner, null))
        assertNull(unionSetlistFmLookup(null, null))
    }

    // --- editSetlistFmLookup --------------------------------------------------

    @Test
    fun `editing the lookup of a night nobody claimed mints nothing`() = runBlocking {
        val file = tempFile()
        val store = TimelineStore(file)
        val gigId = store.createLocalGig("12-03-2027", "Ferrous Owls", "Kjøkkenhagen Scene")

        assertNull(store.editSetlistFmLookup(gigId) { it.lookedUp(5) })
        assertNull(store.editSetlistFmLookup("nowhere") { it.lookedUp(5) })

        val loaded = TimelineStore(file).load()
        assertTrue(loaded.gigAttendance.isEmpty())
        assertEquals(setOf(gigId), loaded.gigs.keys)
    }

    @Test
    fun `editing the lookup changes only the lookup, starting from never looked up`() = runBlocking {
        val file = tempFile()
        val store = TimelineStore(file)
        val gigId = store.createLocalGig("12-03-2027", "Ferrous Owls", "Kjøkkenhagen Scene")
        store.saveAttendance(gigId, StoredAttendance(provenance = StoredAttendance.Provenance.PLANNED, venueLat = 1.5))

        var seen: StoredSetlistFmLookup? = null
        val settled = store.editSetlistFmLookup(gigId) { seen = it; it.lookedUp(5).asking(chip) }

        assertEquals(StoredSetlistFmLookup(), seen)
        val expected = StoredAttendance(
            provenance = StoredAttendance.Provenance.PLANNED,
            venueLat = 1.5,
            setlistFmLookup = StoredSetlistFmLookup(lastLookupAt = 5).asking(chip),
        )
        assertEquals(expected, settled)
        assertEquals(expected, TimelineStore(file).load().gigAttendance[gigId])
    }

    @Test
    fun `the lookup travels with the night when it adopts a setlist fm id`() = runBlocking {
        val file = tempFile()
        val store = TimelineStore(file)
        val gigId = store.createLocalGig("12-03-2027", "Ferrous Owls", "Kjøkkenhagen Scene")
        store.saveAttendance(gigId, StoredAttendance())
        store.editSetlistFmLookup(gigId) { it.lookedUp(5).asking(chip).rejectingPending() }

        assertTrue(store.adoptSetlistId(gigId, "t1000001"))

        val lookup = TimelineStore(file).load().attendance()["t1000001"]?.setlistFmLookup
        assertEquals(StoredSetlistFmLookup(lastLookupAt = 5, rejectedIds = listOf("t1000002", "t1000003")), lookup)
        // And the new id names the same record to edit.
        assertEquals(lookup?.lookedUp(9), store.editSetlistFmLookup("t1000001") { it.lookedUp(9) }?.setlistFmLookup)
    }

    @Test
    fun `the lookup survives adopting an id another record already holds`() = runBlocking {
        val file = tempFile()
        val store = TimelineStore(file)
        val gigId = store.createLocalGig("12-03-2027", "Ferrous Owls", "Kjøkkenhagen Scene")
        store.saveAttendance(gigId, StoredAttendance())
        store.editSetlistFmLookup(gigId) { it.lookedUp(5).asking(chip) }
        store.saveAttendance("t1000001", StoredAttendance(provenance = StoredAttendance.Provenance.ATTENDED))

        assertTrue(store.adoptSetlistId(gigId, "t1000001"))

        val record = TimelineStore(file).load().attendance()["t1000001"]
        assertEquals(StoredAttendance.Provenance.ATTENDED, record?.provenance)
        assertEquals(StoredSetlistFmLookup(lastLookupAt = 5).asking(chip), record?.setlistFmLookup)
    }
}
