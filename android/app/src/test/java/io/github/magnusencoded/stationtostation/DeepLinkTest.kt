package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.StoredLog
import org.junit.Assert.assertEquals
import java.time.LocalDate
import org.junit.Test

class DeepLinkTest {

    private val gigs = listOf(
        "a" to LocalDate.of(2026, 8, 1),
        "b" to LocalDate.of(2026, 8, 11),
    )

    @Test fun aDateOnAGigLandsOnThatGig() {
        assertEquals("b", nearestGig(gigs, LocalDate.of(2026, 8, 11)))
    }

    @Test fun aDateBetweenTwoGigsLandsOnTheCloser() {
        assertEquals("a", nearestGig(gigs, LocalDate.of(2026, 8, 5)))
        assertEquals("b", nearestGig(gigs, LocalDate.of(2026, 8, 8)))
    }

    @Test fun aTieGoesToTheEarlierGig() {
        assertEquals("a", nearestGig(gigs, LocalDate.of(2026, 8, 6)))
    }

    @Test fun aTieGoesToTheEarlierGigWhateverTheOrderGiven() {
        assertEquals("a", nearestGig(gigs.reversed(), LocalDate.of(2026, 8, 6)))
    }

    @Test fun aDateOutsideEveryGigLandsOnTheNearestEnd() {
        assertEquals("a", nearestGig(gigs, LocalDate.of(2020, 1, 1)))
        assertEquals("b", nearestGig(gigs, LocalDate.of(2030, 1, 1)))
    }

    @Test fun noGigsIsNowhere() {
        assertEquals(null, nearestGig(emptyList(), LocalDate.of(2026, 8, 6)))
    }

    @Test fun aNightBeforeTodayIsOneIWasAt() {
        val today = LocalDate.of(2026, 9, 29)
        assertEquals(NightKind.WAS_AT, nightKind(today.minusDays(1), today))
    }

    @Test fun todayIsAGigIAmGoingTo() {
        val today = LocalDate.of(2026, 9, 29)
        assertEquals(NightKind.GOING_TO, nightKind(today, today))
    }

    @Test fun aLaterNightOrNoDateIsAGigIAmGoingTo() {
        val today = LocalDate.of(2026, 9, 29)
        assertEquals(NightKind.GOING_TO, nightKind(today.plusDays(1), today))
        assertEquals(NightKind.GOING_TO, nightKind(null, today))
    }

    @Test fun aGigOnMyLineOpensWithoutBeingAdded() {
        assertEquals(OpenGigPlan.OPEN, planOpenGig("334c742d", onMyLine = true))
    }

    @Test fun anUnknownSetlistFmIdIsFetchedThenOpened() {
        assertEquals(OpenGigPlan.FETCH_THEN_OPEN, planOpenGig("334c742d", onMyLine = false))
    }

    @Test fun anUnknownIdWithNothingToFetchIsRefused() {
        assertEquals(OpenGigPlan.REFUSE, planOpenGig("not an id!", onMyLine = false))
    }

    private fun log(vararg songs: String) = songs.fold(StoredLog()) { l, s -> l.adding(s) }

    @Test fun bareItemsAppendInOrder() {
        assertEquals(listOf("A", "B", "C", "D"), log("A", "B").writing(listOf("C", "D"), emptyMap()).songs)
    }

    @Test fun aNumberedItemReplacesThatSong() {
        assertEquals(listOf("A", "X", "C"), log("A", "B", "C").writing(emptyList(), mapOf(2 to "X")).songs)
    }

    @Test fun aNumberJustPastTheEndAppends() {
        assertEquals(listOf("A", "B", "X"), log("A", "B").writing(emptyList(), mapOf(3 to "X")).songs)
    }

    @Test fun aNumberFurtherOutIsIgnored() {
        assertEquals(listOf("A", "B"), log("A", "B").writing(emptyList(), mapOf(4 to "X")).songs)
    }

    @Test fun consecutiveNumbersPastTheEndBuildTheLogPositionByPosition() {
        assertEquals(listOf("A", "B", "C"), log("A").writing(emptyList(), mapOf(2 to "B", 3 to "C")).songs)
    }

    @Test fun aReplacementKeepsTheWordsItReplaced() {
        assertEquals("B", log("A", "B").writing(emptyList(), mapOf(2 to "X")).rememberedAt(1))
    }
}
