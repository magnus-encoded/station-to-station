package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.ui.PhotoAccess
import io.github.magnusencoded.stationtostation.ui.ServiceGraph
import io.github.magnusencoded.stationtostation.ui.ServiceNode
import io.github.magnusencoded.stationtostation.ui.ServiceRole
import io.github.magnusencoded.stationtostation.ui.ServiceStrip
import io.github.magnusencoded.stationtostation.ui.ServicesAsKnown
import io.github.magnusencoded.stationtostation.ui.serviceGraph
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Settings **Field**'s fold (#563): what each service says, and which are **Lit**.
 *
 * `fixtures/service-graph/cases.json` is shared with iOS's `ServiceGraphTests`, which
 * asserts it case for case; so is the hand-built dark-timeline case below, which no
 * fixture can reach. Everything here is synthetic.
 */
class ServiceGraphTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Walk up from the module dir: the fixtures sit at the repo root, outside android/. */
    private fun fixture(path: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "fixtures/$path") }
            .firstOrNull { it.exists() }
            ?: error("fixtures/$path not found above ${File("").absolutePath}")

    @Serializable
    private data class Known(
        val setlistFmKeyAvailable: Boolean = false,
        val setlistFmOwnKey: Boolean = false,
        val setlistFmSharedQuotaSpent: Boolean = false,
        val clashfinderUser: String = "",
        val clashfinderKey: Boolean = false,
        val spotifyConnected: Boolean = false,
        val spotifyScope: String? = null,
        val knownTimelines: Int = 0,
        val photos: String = "none",
        val calendar: Boolean = false,
        val location: Boolean = false,
        val gigActive: Boolean = false,
    ) {
        fun toKnown() = ServicesAsKnown(
            setlistFmKeyAvailable = setlistFmKeyAvailable,
            setlistFmOwnKey = setlistFmOwnKey,
            setlistFmSharedQuotaSpent = setlistFmSharedQuotaSpent,
            clashfinderUser = clashfinderUser,
            clashfinderKey = clashfinderKey,
            spotifyConnected = spotifyConnected,
            spotifyScope = spotifyScope,
            knownTimelines = knownTimelines,
            photos = PhotoAccess.valueOf(photos.uppercase()),
            calendar = calendar,
            location = location,
            gigActive = gigActive,
        )
    }

    @Serializable
    private data class NodeExpect(val lit: Boolean, val status: String, val nextStep: String? = null)

    @Serializable
    private data class Expect(
        val timelineLit: Boolean,
        val alcoveLines: Map<String, Boolean>,
        val lit: List<String>? = null,
        val nodes: Map<String, NodeExpect> = emptyMap(),
    )

    @Serializable
    private data class Case(val name: String, val known: Known = Known(), val expect: Expect)

    @Serializable
    private data class Cases(val cases: List<Case>)

    @Test
    fun `every case says what the fixture says`() {
        val cases = json.decodeFromString<Cases>(fixture("service-graph/cases.json").readText()).cases
        assertTrue("fixtures/service-graph/cases.json is empty", cases.isNotEmpty())
        cases.forEach { case ->
            val graph = serviceGraph(case.known.toKnown())
            val e = case.expect
            assertEquals("${case.name}: timelineLit", e.timelineLit, graph.timelineLit)
            e.alcoveLines.forEach { (id, lit) ->
                assertEquals("${case.name}: line to $id", lit, graph.alcoveLineLit(id))
            }
            e.lit?.let { assertEquals("${case.name}: lit", it.toSet(), graph.nodes.filter { n -> n.lit }.map { n -> n.id }.toSet()) }
            e.nodes.forEach { (id, want) ->
                val node = graph.node(id) ?: error("${case.name}: no node $id")
                assertEquals("${case.name}: $id lit", want.lit, node.lit)
                assertEquals("${case.name}: $id status", want.status, node.status)
                assertEquals("${case.name}: $id nextStep", want.nextStep, node.nextStep)
            }
        }
        println("ServiceGraphTest: ${cases.size} fixture cases")
    }

    @Test
    fun `nodes come in drawing order, in their strips`() {
        val graph = serviceGraph(ServicesAsKnown())
        assertEquals(
            listOf(
                "setlistfm", "musicbrainz", "clashfinder",
                "photos", "tickets", "location",
                "contacts", "gossip",
                "spotify", "calendar",
            ),
            graph.nodes.map { it.id },
        )
        assertEquals(
            listOf(
                ServiceStrip.DATABASES, ServiceStrip.DATABASES, ServiceStrip.DATABASES,
                ServiceStrip.THIS_PHONE, ServiceStrip.THIS_PHONE, ServiceStrip.THIS_PHONE,
                ServiceStrip.OTHER_PHONES, ServiceStrip.OTHER_PHONES,
                ServiceStrip.SERVICES, ServiceStrip.THIS_PHONE,
            ),
            graph.nodes.map { it.strip },
        )
        assertEquals(8, graph.nodes.count { it.role == ServiceRole.INPUT })
        assertEquals(listOf("gossip"), graph.nodes.filter { it.experimental }.map { it.id })
    }

    @Test
    fun `a screen reader hears the name, lit or not, and the status`() {
        val graph = serviceGraph(ServicesAsKnown(setlistFmKeyAvailable = true))
        assertEquals("setlist.fm, lit, shared key, bundled with the app", graph.node("setlistfm")!!.spoken)
        assertEquals("clashfinder, not lit, needs a free account", graph.node("clashfinder")!!.spoken)
        assertEquals("Spotify, not lit, not logged in. The shared app admits five people", graph.node("spotify")!!.spoken)
    }

    @Test
    fun `an unlit node always says what would light it, a lit one never does`() {
        listOf(ServicesAsKnown(), ServicesAsKnown(setlistFmKeyAvailable = true, spotifyConnected = true)).forEach { known ->
            serviceGraph(known).nodes.forEach {
                if (it.lit) assertEquals(it.id, null, it.nextStep) else assertTrue(it.id, it.nextStep != null)
            }
        }
    }

    @Test
    fun `an alcove lit on a dark timeline keeps its line dark`() {
        // Unreachable from ServicesAsKnown today (MusicBrainz and Ticket PDFs are always
        // lit), so built by hand: the rule the drawing follows, not today's services.
        fun node(id: String, role: ServiceRole, lit: Boolean) =
            ServiceNode(id, id, role, ServiceStrip.SERVICES, lit, "", emptyList())
        val dark = ServiceGraph(
            listOf(
                node("musicbrainz", ServiceRole.INPUT, lit = false),
                node("tickets", ServiceRole.INPUT, lit = false),
                node("spotify", ServiceRole.ALCOVE, lit = true),
                node("calendar", ServiceRole.ALCOVE, lit = false),
            ),
        )
        assertFalse(dark.timelineLit)
        assertTrue(dark.node("spotify")!!.lit)
        assertFalse(dark.alcoveLineLit("spotify"))
        assertFalse(dark.alcoveLineLit("calendar"))

        val lit = ServiceGraph(dark.nodes.map { if (it.id == "tickets") it.copy(lit = true) else it })
        assertTrue(lit.timelineLit)
        assertTrue(lit.alcoveLineLit("spotify"))
        assertFalse(lit.alcoveLineLit("calendar"))
        // An input has no line out of the timeline, lit or not.
        assertFalse(lit.alcoveLineLit("tickets"))
    }
}
