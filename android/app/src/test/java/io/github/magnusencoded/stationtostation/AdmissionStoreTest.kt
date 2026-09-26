package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.StoredAdmission
import io.github.magnusencoded.stationtostation.data.StoredAttendance
import io.github.magnusencoded.stationtostation.data.TimelineStore
import io.github.magnusencoded.stationtostation.data.toAdmissionBase64
import io.github.magnusencoded.stationtostation.data.unionAttendance
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A night's **Admissions** as stored (#441): the list that replaced `ticketQr`, its
 * migration, appending a second ticket, and the file both twins read.
 *
 * `fixtures/timeline/admissions/` is shared with iOS's `TimelineStoreTests`, which
 * asserts the same four things of it (see that folder's README). Payloads are synthetic.
 */
class AdmissionStoreTest {

    private fun tempFile(contents: String? = null): File =
        File.createTempFile("timelines", ".json").also { if (contents == null) it.delete() else it.writeText(contents) }

    private fun admission(text: String, symbology: String = "qr", page: Int = 0, corroborated: Boolean = false) =
        StoredAdmission(text.toByteArray().toAdmissionBase64(), symbology, page, corroborated)

    /** Walk up from the module dir: the fixtures sit at the repo root, outside android/. */
    private fun fixture(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "fixtures/timeline/admissions/timelines.json") }
            .firstOrNull { it.isFile }
            ?: error("fixtures/timeline/admissions not found above ${File("").absolutePath}")

    private val fixtureAdmissions = listOf(
        StoredAdmission("U1lOVEhFVElDLVRJTUVMSU5FLVFSLTE=", "qr", 0, false),
        StoredAdmission("MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDIx", "code128", 1, true),
    )
    private val legacyAdmission = StoredAdmission("U1lOVEhFVElDLUxFR0FDWS1RUg==", "qr", 0, false)

    // --- The shared file ------------------------------------------------------

    @Test
    fun `the shared file loads, saves and reloads without losing an Admission`() = runBlocking {
        val file = tempFile(fixture().readText())
        val store = TimelineStore(file)

        val loaded = store.load()
        assertEquals(fixtureAdmissions, loaded.gigAttendance["g-admissions"]?.admissions)
        assertEquals(listOf(legacyAdmission), loaded.gigAttendance["g-legacy"]?.admissions)
        assertEquals(StoredAttendance.Provenance.CHECKED_IN, loaded.gigAttendance["g-legacy"]?.provenance)
        assertEquals(1782410400000L, loaded.gigAttendance["g-legacy"]?.checkedInAt)

        // Any save rewrites the whole file.
        store.save(festivalNames = mapOf("x" to "Unrelated"))
        val reloaded = store.load()
        assertEquals(fixtureAdmissions, reloaded.gigAttendance["g-admissions"]?.admissions)
        assertEquals(listOf(legacyAdmission), reloaded.gigAttendance["g-legacy"]?.admissions)

        // The written shape, key for key: what iOS reads back.
        val attendance = Json.parseToJsonElement(file.readText()).jsonObject.getValue("gigAttendance").jsonObject
        for ((gig, record) in attendance) {
            val claim = record.jsonObject
            assertFalse("$gig still writes ticketQr", "ticketQr" in claim)
            for (a in claim.getValue("admissions").jsonArray) {
                assertEquals(setOf("payload", "symbology", "page", "corroborated"), (a as JsonObject).keys)
            }
        }
    }

    // --- Migration, as behaviour ---------------------------------------------

    @Test
    fun `an old ticketQr reads as exactly one uncorroborated QR Admission on page 0`() = runBlocking {
        val store = TimelineStore(
            tempFile(
                """{"gigs":{"g1":{"id":"g1","date":"14-09-2026","artist":"Paper Cranes","venue":"","createdAt":1}},""" +
                    """"gigAttendance":{"g1":{"provenance":"planned","ticketQr":"VEtULTlGMzE="}}}""",
            ),
        )

        val admissions = store.load().gigAttendance["g1"]?.admissions

        assertEquals(listOf(StoredAdmission("VEtULTlGMzE=", "qr", 0, false)), admissions)
        assertEquals("TKT-9F31", admissions!!.single().payloadBytes!!.toString(Charsets.UTF_8))
    }

    @Test
    fun `an old ticketQr that is not base64 migrates to nothing, and the night survives`() = runBlocking {
        val store = TimelineStore(
            tempFile(
                """{"gigs":{"g1":{"id":"g1","date":"14-09-2026","artist":"Paper Cranes","venue":"","createdAt":1}},""" +
                    """"gigAttendance":{"g1":{"provenance":"attended","ticketQr":"not base64 at all!"}}}""",
            ),
        )

        val claim = store.load().gigAttendance["g1"]

        assertEquals(StoredAttendance.Provenance.ATTENDED, claim?.provenance)
        assertEquals(emptyList<StoredAdmission>(), claim?.admissions)
    }

    @Test
    fun `an Admission missing fields costs those fields, not the timeline`() = runBlocking {
        val store = TimelineStore(
            tempFile(
                """{"gigs":{"g1":{"id":"g1","date":"14-09-2026","artist":"Paper Cranes","venue":"","createdAt":1}},""" +
                    """"gigAttendance":{"g1":{"admissions":[{"payload":"VEtULTlGMzE="}]}}}""",
            ),
        )

        val loaded = store.load()

        assertEquals(setOf("g1"), loaded.gigs.keys)
        assertEquals(listOf(StoredAdmission("VEtULTlGMzE=", "", 0, false)), loaded.gigAttendance["g1"]?.admissions)
    }

    // --- Attaching: appended, one per payload (stories 18, 19) ----------------

    @Test
    fun `a second ticket for the night adds its Admissions and the same one twice adds nothing`() = runBlocking {
        val store = TimelineStore(tempFile())
        val id = store.createLocalGig("14-09-2026", "Paper Cranes", "The Long Room")

        store.attachAdmissions(id, listOf(admission("SYNTHETIC-1")))
        store.attachAdmissions(id, listOf(admission("SYNTHETIC-2", "code128", page = 1)))
        val settled = store.attachAdmissions(id, listOf(admission("SYNTHETIC-1"), admission("SYNTHETIC-2", "code128", page = 1)))

        val expected = listOf(admission("SYNTHETIC-1"), admission("SYNTHETIC-2", "code128", page = 1))
        assertEquals(expected, settled.admissions)
        assertEquals(expected, store.load().gigAttendance[id]?.admissions)
    }

    @Test
    fun `attaching Admissions takes nothing off the claim already there`() = runBlocking {
        val store = TimelineStore(tempFile())
        val id = store.createLocalGig("14-09-2026", "Paper Cranes", "The Long Room")
        store.saveAttendance(id, StoredAttendance(StoredAttendance.Provenance.CHECKED_IN, checkedInAt = 42L, venueLat = 59.9))

        val settled = store.attachAdmissions(id, listOf(admission("SYNTHETIC-1")))

        assertEquals(StoredAttendance.Provenance.CHECKED_IN, settled.provenance)
        assertEquals(42L, settled.checkedInAt)
        assertEquals(59.9, settled.venueLat!!, 0.0)
        assertEquals(listOf(admission("SYNTHETIC-1")), settled.admissions)
    }

    // --- Merging two records of one night -------------------------------------

    @Test
    fun `the stronger claim wins and both sides' Admissions are kept`() {
        val kept = StoredAttendance(StoredAttendance.Provenance.CHECKED_IN, checkedInAt = 42L, admissions = listOf(admission("A")))
        val other = StoredAttendance(StoredAttendance.Provenance.PLANNED, admissions = listOf(admission("B"), admission("A")))

        val merged = unionAttendance(kept, other)

        assertEquals(StoredAttendance.Provenance.CHECKED_IN, merged.provenance)
        assertEquals(42L, merged.checkedInAt)
        assertEquals(listOf(admission("A"), admission("B")), merged.admissions)
        assertEquals(merged, unionAttendance(other, kept))
        assertTrue(unionAttendance(StoredAttendance(), StoredAttendance()).admissions.isEmpty())
    }
}
