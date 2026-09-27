package io.github.magnusencoded.stationtostation.data

import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * The ticket files the app keeps (#568): the original a person shared, copied in at
 * import when any of its Admissions did not redraw ([redrawsExactly]). At the door the
 * Room shows that file in place of a redraw it could not make — the original *is* the
 * barcode the venue issued. Tickets that redraw keep nothing.
 *
 * Copied rather than referenced: a shared Uri's grant ends with the share. The files sit
 * in the app's own storage beside `timelines.json`, with the same protection at rest as
 * the Admissions that name them ([StoredAdmission.original]). A name is a bare file name
 * this class minted; anything else resolves to nothing. The Swift twin is
 * `TicketOriginals.swift`.
 */
class TicketOriginals(private val dir: File) {

    /** [input] copied in whole, as a new file ending in [extension]; its name, or null where the copy failed. */
    fun keep(input: InputStream, extension: String): String? {
        val name = "${UUID.randomUUID()}.$extension"
        return try {
            dir.mkdirs()
            val file = File(dir, name)
            file.outputStream().use { input.copyTo(it) }
            if (file.length() > 0) name else null.also { file.delete() }
        } catch (e: Exception) {
            File(dir, name).delete()
            null
        }
    }

    /** The kept file called [name], or null where there is none (a handover from another phone, or a name not minted here). */
    fun file(name: String?): File? {
        if (name == null || !isMinted(name)) return null
        return File(dir, name).takeIf { it.isFile }
    }

    /** Drops the kept file called [name]: a ticket discarded at the prompt, or a copy nothing came to name. */
    fun forget(name: String?) {
        if (name != null && isMinted(name)) File(dir, name).delete()
    }

    companion object {
        /** The app's own: one directory, beside `timelines.json`. */
        fun of(context: android.content.Context) = TicketOriginals(File(context.filesDir, "ticket-originals"))
    }

    private fun isMinted(name: String) = name.isNotEmpty() && '/' !in name && '\\' !in name && !name.startsWith(".")
}

/** Every original this ticket's Admissions name, once each. */
val ParsedTicket.originals: Set<String> get() = admissions.mapNotNull { it.original }.toSet()

/**
 * This ticket with [original] set on every Admission that did not redraw (#568) —
 * those, and only those, are shown from the file at the door. Unchanged where every
 * Admission redraws, or [original] is null.
 */
fun ParsedTicket.keepingOriginal(original: String?): ParsedTicket =
    if (original == null) this else copy(admissions = admissions.map { if (it.redrawable == true) it else it.withOriginal(original) })

/** Whether any Admission of this ticket needs its original kept: one that did not redraw. */
val ParsedTicket.needsOriginal: Boolean get() = admissions.any { it.redrawable != true }
