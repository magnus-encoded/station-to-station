package io.github.magnusencoded.stationtostation

import org.junit.Assert.assertEquals
import org.junit.Test

class GigMenuTest {
    @Test fun aNightHeldOnlyHereIsHereOnlyWhateverIdItHas() {
        assertEquals(GigStanding.HERE_ONLY, gigStanding("534b1301", held = listOf("534b1301"), attendedOnSetlistFm = listOf("aaaa")))
    }

    @Test fun aNightHeldHereAndOnMyAttendedListIsBoth() {
        assertEquals(GigStanding.HERE_AND_SETLIST_FM, gigStanding("aaaa", held = listOf("aaaa"), attendedOnSetlistFm = listOf("aaaa")))
    }

    @Test fun aNightOnlyMyAttendedListHasIsSetlistFmOnly() {
        assertEquals(GigStanding.SETLIST_FM_ONLY, gigStanding("aaaa", held = emptyList(), attendedOnSetlistFm = listOf("aaaa")))
    }

    @Test fun aGigHeldHereCanAlwaysBeDeleted() {
        assertEquals(listOf(GigMenuItem.DELETE), gigMenu(GigStanding.HERE_ONLY, hasPage = true))
        assertEquals(
            listOf(GigMenuItem.OPEN_ON_SETLIST_FM, GigMenuItem.DELETE),
            gigMenu(GigStanding.HERE_AND_SETLIST_FM, hasPage = true),
        )
    }

    @Test fun aGigOnlySetlistFmHasGoesToSetlistFmInsteadOfDeleting() {
        assertEquals(listOf(GigMenuItem.OPEN_ON_SETLIST_FM), gigMenu(GigStanding.SETLIST_FM_ONLY, hasPage = true))
    }

    @Test fun aGigOnlySetlistFmHasAndNoPageHasNoMenu() {
        assertEquals(emptyList<GigMenuItem>(), gigMenu(GigStanding.SETLIST_FM_ONLY, hasPage = false))
    }
}
