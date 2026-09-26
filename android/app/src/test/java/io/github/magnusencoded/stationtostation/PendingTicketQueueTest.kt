package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.ParsedTicket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Shared tickets waiting on the confirm prompt (the #441 review): a queue, as iOS's
 * `ticketDrafts` is. With one slot, a second share while the prompt was open replaced
 * the first, and the first was lost without a word.
 */
class PendingTicketQueueTest {

    private fun pending(artist: String) = PendingTicket(ParsedTicket(artist = artist), possibleMatch = null)

    @Test
    fun aSecondShareQueuesBehindTheTicketOnThePrompt() {
        val first = pending("Dumdumboys")
        val second = pending("Kaizers Orchestra")

        val state = UiState().queuingTicket(first).queuingTicket(second)

        assertEquals(first, state.pendingTicket)
        assertEquals(listOf(first, second), state.pendingTickets)
    }

    @Test
    fun answeringTheTicketOnThePromptShowsTheNext() {
        val first = pending("Dumdumboys")
        val second = pending("Kaizers Orchestra")
        val state = UiState().queuingTicket(first).queuingTicket(second)

        val next = state.answeringTicket(first.id)

        assertEquals(second, next.pendingTicket)
        assertNull(next.answeringTicket(second.id).pendingTicket)
    }

    @Test
    fun anAnswerNamesTheTicketItWasGivenFor() {
        // Two shares of the same PDF are two tickets on the queue, and answering one
        // never answers the other.
        val first = pending("Dumdumboys")
        val again = pending("Dumdumboys")
        assertNotEquals(first.id, again.id)

        val state = UiState().queuingTicket(first).queuingTicket(again).answeringTicket(again.id)

        assertEquals(listOf(first), state.pendingTickets)
    }
}
