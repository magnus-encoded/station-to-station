package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.clashfinder.ClashfinderFestival
import io.github.magnusencoded.stationtostation.data.clashfinder.billingLead
import io.github.magnusencoded.stationtostation.data.clashfinder.clashfinderPublicKey
import io.github.magnusencoded.stationtostation.data.clashfinder.matchArtist
import io.github.magnusencoded.stationtostation.data.clashfinder.parseClashfinderEvent
import io.github.magnusencoded.stationtostation.data.clashfinder.parseClashfinderIndex
import io.github.magnusencoded.stationtostation.data.clashfinder.FestivalSearch
import io.github.magnusencoded.stationtostation.data.clashfinder.rankFestivals
import io.github.magnusencoded.stationtostation.data.setlistfm.FmArtist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The clashfinder source, at the four seams the Øya parser's tests covered before it
 * was deleted: a realistically nested document parses, a field that is also a trap
 * survives, an entry missing something required is dropped rather than half-built, and
 * a document that has changed shape parses to nothing rather than to nonsense.
 *
 * The JSON here is written out rather than captured. A saved copy of a real document
 * would put a festival's programme in the repo, which is the thing this app
 * deliberately does not do; what is being tested is the *shape*, and that is
 * reproducible without anyone's data.
 */
class ClashfinderTest {

    private val document = """
        {
          "name": "Øyafestivalen 2026",
          "timezone": "Europe/Oslo",
          "copyright": "Clashfinder data CC BY-NC 3.0",
          "lastEdit": "2026-07-11 13:44:46",
          "locations": [
            {"name": "Amfiet", "events": [
              {"name": "Headline", "start": "2026-08-13 21:30", "end": "2026-08-13 23:15",
               "mbId": "b7ffd2af-418f-4be2-bdd1-22f8b48613da",
               "mbid": "b7ffd2af-418f-4be2-bdd1-22f8b48613da"},
              {"name": "Opener", "start": "2026-08-13 16:00", "end": "2026-08-13 16:45"}
            ]},
            {"name": "Klubben", "events": [
              {"name": "After Midnight", "start": "2026-08-14 00:45", "end": "2026-08-14 01:45"}
            ]}
          ]
        }
    """.trimIndent()

    @Test
    fun `a document flattens its stages into acts down the clock`() {
        val programme = parseClashfinderEvent(document, id = "oyafestivalen2026")

        assertEquals("Øyafestivalen 2026", programme.name)
        assertEquals("Clashfinder data CC BY-NC 3.0", programme.copyright)
        assertEquals("2026-07-11 13:44:46", programme.lastEdit)
        assertEquals(listOf("Opener", "Headline", "After Midnight"), programme.acts.map { it.artist })
        assertEquals("Amfiet", programme.acts[0].stage)
    }

    /**
     * The regression that matters most. Clashfinder has already dated the 00:45 set to
     * Saturday; [ProgrammeAct.startsAt] independently pushes anything before the night
     * boundary forward a day. Split naively, the shift lands twice and the act appears
     * a full day late. Asserted on the resulting moment rather than the stored date, so
     * it pins the behaviour and not the encoding.
     */
    @Test
    fun `an act after midnight belongs to the night before, not the next afternoon`() {
        val late = parseClashfinderEvent(document).acts.single { it.artist == "After Midnight" }

        assertEquals(LocalDateTime.parse("2026-08-14T00:45"), late.startsAt())
        assertEquals("2026-08-13", late.date)
    }

    /**
     * The payload carries both `mbId` and `mbid` on the same act with the same value.
     * Declared as two names for one field, the decoder sees a field supplied twice and
     * rejects the whole document — 139 acts lost to a spelling.
     */
    @Test
    fun `both spellings of the MusicBrainz id parse, and one of them is read`() {
        val headline = parseClashfinderEvent(document).acts.single { it.artist == "Headline" }

        assertEquals("b7ffd2af-418f-4be2-bdd1-22f8b48613da", headline.mbid)
    }

    /**
     * The id goes into the path of a setlist.fm request, off a document anyone may
     * edit. Anything that is not a MusicBrainz id is dropped at the edge, which leaves
     * the act in the common case: no id, resolved by name or not at all.
     */
    @Test
    fun `an id that is not a MusicBrainz id is dropped rather than used`() {
        val doc = """
            {"name": "F", "locations": [{"name": "Stage", "events": [
              {"name": "Act", "start": "2026-08-13 20:00", "mbId": "../../search/artists?q=x"}
            ]}]}
        """.trimIndent()

        assertEquals("", parseClashfinderEvent(doc).acts.single().mbid)
    }

    @Test
    fun `a published end time survives onto the record`() {
        val headline = parseClashfinderEvent(document).acts.single { it.artist == "Headline" }

        assertEquals("23:15", headline.end)
        assertEquals(LocalDateTime.parse("2026-08-13T23:15"), headline.endsAt())
    }

    @Test
    fun `an act missing a required field is dropped, never half-built`() {
        val broken = """
            {"name": "Half a festival", "locations": [
              {"name": "Amfiet", "events": [
                {"name": "No start"},
                {"name": "", "start": "2026-08-13 20:00"},
                {"name": "Fine", "start": "2026-08-13 20:00"}
              ]},
              {"name": "", "events": [{"name": "No stage", "start": "2026-08-13 20:00"}]}
            ]}
        """.trimIndent()

        assertEquals(listOf("Fine"), parseClashfinderEvent(broken).acts.map { it.artist })
    }

    @Test
    fun `a document that has changed shape parses to nothing, not to nonsense`() {
        assertTrue(parseClashfinderEvent("""{"error":"not found"}""").acts.isEmpty())
        assertTrue(parseClashfinderEvent("<html>we redesigned the api</html>").acts.isEmpty())
    }

    // --- The index and the picker ----------------------------------------------------

    private val index = """
        {
          "oyafestivalen2026": {"name":"oyafestivalen2026","desc":"Øyafestivalen 2026",
            "edits":4,"numDays":5,"numActs":139,"numStages":16,"startDate":1786406400,
            "private":false,"coreClashfinder":false},
          "oyastub": {"name":"oyastub","desc":"Oyafestivalen 2026 (stub)",
            "edits":1,"numDays":5,"numActs":1,"numStages":1,"startDate":1786406400,
            "private":false,"coreClashfinder":false},
          "nextyear": {"name":"nextyear","desc":"Some Other Festival 2027",
            "edits":9,"numDays":2,"numActs":80,"numStages":4,"startDate":1817856000,
            "private":false,"coreClashfinder":true},
          "secret": {"name":"secret","desc":"Private Party 2026",
            "edits":2,"numDays":1,"numActs":10,"numStages":1,"startDate":1786406400,
            "private":true,"coreClashfinder":false}
        }
    """.trimIndent()

    private val festivals = parseClashfinderIndex(index)

    @Test
    fun `the index reduces to what the picker uses`() {
        val oya = festivals.single { it.id == "oyafestivalen2026" }

        assertEquals("Øyafestivalen 2026", oya.name)
        assertEquals("2026-08-11", oya.start)
        assertEquals(5, oya.days)
        assertEquals(139, oya.acts)
        assertEquals(16, oya.stages)
    }

    @Test
    fun `a private clashfinder is not offered, because it cannot be opened`() {
        assertTrue(festivals.none { it.id == "secret" })
    }

    @Test
    fun `candidates are ordered by nearness to the day, past as well as future`() {
        // Last week beats next year: the picker exists to find the festival you are
        // about to be at or have just been to, not to filter history out of reach.
        val ranked = rankFestivals(festivals, on = LocalDate.parse("2026-08-20"))

        assertEquals("oyafestivalen2026", ranked.first().id)
    }

    @Test
    fun `a festival in progress on the day matches on its whole run`() {
        // Day two of five. Matching on the start alone misses exactly the person who is
        // standing in the field with the phone out.
        val oya = festivals.single { it.id == "oyafestivalen2026" }

        assertEquals(0L, oya.distanceFrom(LocalDate.parse("2026-08-12")))
        assertEquals(0L, oya.distanceFrom(LocalDate.parse("2026-08-15")))
        assertEquals(1L, oya.distanceFrom(LocalDate.parse("2026-08-16")))
    }

    @Test
    fun `a one-act stub ranks below the full timetable, and is still listed`() {
        val ranked = rankFestivals(festivals, on = LocalDate.parse("2026-08-11")).map { it.id }

        assertEquals(listOf("oyafestivalen2026", "oyastub"), ranked.take(2))
        assertTrue("oyastub" in ranked)
    }

    @Test
    fun `two genuinely competing entries both survive`() {
        // Same festival, two spellings, both real. The app orders them; the person
        // chooses. Guessing between them is how you end up reading a fan's fantasy
        // line-up believing it is the programme.
        val ranked = rankFestivals(festivals, on = LocalDate.parse("2026-08-11"))

        assertEquals(2, ranked.count { it.start == "2026-08-11" })
    }

    @Test
    fun `a query without diacritics finds a name that has them`() {
        val found = rankFestivals(festivals, on = LocalDate.parse("2026-08-11"), query = "oyafest")

        assertEquals(listOf("oyafestivalen2026", "oyastub"), found.map { it.id })
    }

    @Test
    fun `search covers the whole catalogue and not the nearest few`() {
        val found = rankFestivals(festivals, on = LocalDate.parse("2026-08-11"), query = "other festival")

        assertEquals(listOf("nextyear"), found.map { it.id })
    }

    @Test
    fun `the prepared catalogue answers a query the same way ranking it fresh does`() {
        val on = LocalDate.parse("2026-08-11")
        val search = FestivalSearch(festivals, on)

        assertEquals(rankFestivals(festivals, on).map { it.id }, search.search("").map { it.id })
        assertEquals(
            rankFestivals(festivals, on, query = "oyafest").map { it.id },
            search.search("oyafest").map { it.id },
        )
    }

    @Test
    fun `no match straddles the seam between a name and the id after it`() {
        // The two haystacks are one string per row, so the tail of the name plus the head
        // of the id must not read as a hit: "2026oyafestivalen" spans exactly that seam.
        val search = FestivalSearch(festivals, LocalDate.parse("2026-08-11"))

        assertTrue(search.search("2026oyafestivalen").isEmpty())
    }

    // --- Credentials -----------------------------------------------------------------

    /**
     * The order is the whole test. A wrong concatenation order is a 401
     * indistinguishable from a mistyped key, and this is the cheapest place to catch
     * it — the expected digest below is sha256 of the two strings in *this* order, so
     * swapping them fails here rather than in the field.
     *
     * Made-up credentials, deliberately: the order was checked against clashfinder's
     * own key generator once, and pinning it does not need anybody's real account. A
     * test vector is a value in a public repository forever.
     */
    @Test
    fun `the public key is sha256 of the username and the private key, in that order`() {
        assertEquals(
            "926593c0e73340310b674c5642a657b7072610f1480fefcbcd0c345d6d9f6059",
            clashfinderPublicKey("stationtostation", "not-a-real-private-key"),
        )
        // Trimmed, because a pasted key arrives with whatever the clipboard had on it.
        assertEquals(
            clashfinderPublicKey("stationtostation", "not-a-real-private-key"),
            clashfinderPublicKey("  stationtostation ", " not-a-real-private-key\n"),
        )
    }

    // --- Artist resolution -----------------------------------------------------------

    @Test
    fun `an exact name binds, diacritics and punctuation aside`() {
        val hits = listOf(FmArtist(mbid = "1", name = "Sigrid"), FmArtist(mbid = "2", name = "Sigríd"))

        assertEquals("1", matchArtist("Sigrid", hits)?.mbid)
    }

    @Test
    fun `a collaboration billing resolves to its lead artist`() {
        assertEquals("Four Tet", billingLead("Four Tet b2b Skrillex"))
        assertEquals("Beyoncé", billingLead("Beyoncé feat. Jay-Z"))
        // An ampersand is part of a band's name, not a collaboration marker.
        assertEquals("Nick Cave & The Bad Seeds", billingLead("Nick Cave & The Bad Seeds"))

        val hits = listOf(FmArtist(mbid = "7", name = "Four Tet"))
        assertEquals("7", matchArtist("Four Tet b2b Skrillex", hits)?.mbid)
    }

    /**
     * The top hit is the search's most confident answer and it is wrong. Its score
     * cannot gate anything — setlist.fm returns maximum confidence for results like
     * this — so nothing but exact equality may bind, and refusing is the correct
     * outcome: the act joins the **Bill** with the name as printed and nothing behind
     * it, which somebody can see and fix.
     */
    @Test
    fun `a near miss does not bind, however confident the search is`() {
        val hits = listOf(
            FmArtist(mbid = "1", name = "The Silent Disco Band"),
            FmArtist(mbid = "2", name = "Silent Disco DJs"),
        )

        assertNull(matchArtist("Silent Disco", hits))
        assertNull(matchArtist("Quiz in the Tent", hits))
    }

    @Test
    fun `a festival with no start date is not a candidate`() {
        val dateless = ClashfinderFestival(id = "x", name = "Undated")

        assertEquals(Long.MAX_VALUE, dateless.distanceFrom(LocalDate.parse("2026-08-11")))
    }
}
