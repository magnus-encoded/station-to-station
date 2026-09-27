package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.StoredAdmission
import io.github.magnusencoded.stationtostation.data.StoredAttendance
import io.github.magnusencoded.stationtostation.data.TicketOriginals
import io.github.magnusencoded.stationtostation.data.TimelineStore
import io.github.magnusencoded.stationtostation.data.mergedAdmissions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/** The ticket files kept for Admissions that do not redraw (#568), and the name each stored Admission carries. */
class TicketOriginalsTest {

    private fun dir(): File = Files.createTempDirectory("originals").toFile()

    @Test
    fun `a kept file is copied whole and found again by its name`() {
        val originals = TicketOriginals(dir())

        val name = originals.keep(ByteArrayInputStream("%PDF-synthetic".toByteArray()), "pdf")

        assertNotNull(name)
        assertEquals("%PDF-synthetic", originals.file(name)!!.readText())
    }

    @Test
    fun `an empty copy keeps nothing`() {
        val root = dir()

        assertNull(TicketOriginals(root).keep(ByteArrayInputStream(ByteArray(0)), "pdf"))
        assertEquals(0, root.listFiles()?.size ?: 0)
    }

    @Test
    fun `a forgotten file is gone`() {
        val originals = TicketOriginals(dir())
        val name = originals.keep(ByteArrayInputStream("%PDF".toByteArray()), "pdf")

        originals.forget(name)

        assertNull(originals.file(name))
    }

    @Test
    fun `a name that is not a bare file name resolves to nothing`() {
        val root = dir()
        File(root.parentFile, "outside.pdf").writeText("x")
        val originals = TicketOriginals(root)

        assertNull(originals.file("../outside.pdf"))
        assertNull(originals.file(null))
        assertNull(originals.file("missing.pdf"))
    }

    @Test
    fun `the same ticket again, now with its file, gives the stored Admission that file`() {
        val stored = listOf(StoredAdmission("U1lOVEg=", "maxicode"))
        val again = listOf(StoredAdmission("U1lOVEg=", "maxicode", original = "kept.pdf"))

        assertEquals(again, mergedAdmissions(stored, again))
        assertEquals(again, mergedAdmissions(again, stored))
        assertEquals(again, mergedAdmissions(again, listOf(StoredAdmission("U1lOVEg=", "maxicode", original = "other.pdf"))))
    }

    @Test
    fun `a record from another phone arrives with no original names`() {
        val theirs = StoredAttendance(admissions = listOf(StoredAdmission("U1lOVEg=", "maxicode", original = "theirs.pdf")))

        assertEquals(listOf<String?>(null), theirs.withoutOriginals().admissions.map { it.original })
        assertEquals("U1lOVEg=", theirs.withoutOriginals().admissions.single().payload)
    }

    @Test
    fun `an Admission's original survives a save and a load`() = runBlocking {
        val store = TimelineStore(File.createTempFile("timelines", ".json").also { it.delete() })
        val gigId = store.createLocalGig("24-06-2027", "Kaizers Orchestra", "Sentrum Scene")
        store.attachAdmissions(gigId, listOf(StoredAdmission("U1lOVEg=", "maxicode", 1, false, "kept.pdf")))

        assertEquals("kept.pdf", store.load().gigAttendance[gigId]?.admissions?.single()?.original)
    }
}
