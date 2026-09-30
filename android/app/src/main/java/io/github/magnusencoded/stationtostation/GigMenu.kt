package io.github.magnusencoded.stationtostation

enum class GigMenuItem { OPEN_ON_SETLIST_FM, DELETE }

/** Where a **Gig** on my **Line** is held. */
enum class GigStanding {
    /** Only this phone has it: typed in, planned, joined from a **Contact**. */
    HERE_ONLY,

    /** This phone holds its own record of it, and my setlist.fm attended list has it too. */
    HERE_AND_SETLIST_FM,

    /** Only my setlist.fm attended list has it, so a fresh import would bring it back. */
    SETLIST_FM_ONLY,
}

/**
 * A setlist.fm id does not decide it: a night attended here and linked to setlist.fm
 * afterwards is held here, and so is one that also turns up on my attended list.
 */
fun gigStanding(id: String, held: Collection<String>, attendedOnSetlistFm: Collection<String>): GigStanding = when {
    id in held && id in attendedOnSetlistFm -> GigStanding.HERE_AND_SETLIST_FM
    id in attendedOnSetlistFm -> GigStanding.SETLIST_FM_ONLY
    else -> GigStanding.HERE_ONLY
}

/**
 * What a long press on a **Gig** offers. Delete is there whenever this phone holds a record
 * of it, so a memory can always be let go. A night my setlist.fm attended list has is also
 * left from there, by marking it "I was not there", so that is offered too; alone, it is
 * the only way off it.
 */
fun gigMenu(standing: GigStanding, hasPage: Boolean): List<GigMenuItem> = when (standing) {
    GigStanding.HERE_ONLY -> listOf(GigMenuItem.DELETE)
    GigStanding.HERE_AND_SETLIST_FM ->
        if (hasPage) listOf(GigMenuItem.OPEN_ON_SETLIST_FM, GigMenuItem.DELETE) else listOf(GigMenuItem.DELETE)
    GigStanding.SETLIST_FM_ONLY -> if (hasPage) listOf(GigMenuItem.OPEN_ON_SETLIST_FM) else emptyList()
}

fun GigStanding.caption(): String = when (this) {
    GigStanding.HERE_ONLY -> "Kept on this phone"
    GigStanding.HERE_AND_SETLIST_FM -> "On this phone and on setlist.fm"
    GigStanding.SETLIST_FM_ONLY -> "From your setlist.fm list"
}
