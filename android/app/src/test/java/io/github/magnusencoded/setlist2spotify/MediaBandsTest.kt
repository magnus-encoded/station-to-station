package io.github.magnusencoded.setlist2spotify

import io.github.magnusencoded.setlist2spotify.data.Band
import io.github.magnusencoded.setlist2spotify.data.ReleaseHint
import io.github.magnusencoded.setlist2spotify.data.StoredMedia
import io.github.magnusencoded.setlist2spotify.data.bandsOf
import io.github.magnusencoded.setlist2spotify.data.hintForAdding
import io.github.magnusencoded.setlist2spotify.data.hintForMoving
import io.github.magnusencoded.setlist2spotify.data.moveMedia
import io.github.magnusencoded.setlist2spotify.data.toBands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two bands, and what letting go would do (#162).
 *
 * Every fixture is invented — this repository is public and no real night, person or
 * photograph belongs in a test.
 */
class MediaBandsTest {

    private fun mine(id: String, personal: Boolean = false, at: Long? = null) =
        StoredMedia(id = id, ref = "content://invented/$id", personal = personal, capturedAt = at)

    private fun theirs(id: String, from: String, at: Long? = null) =
        StoredMedia(id = id, ref = "content://invented/$id", from = from, capturedAt = at)

    private fun ids(media: List<StoredMedia>) = media.map { it.id }

    // ---- bands and order ---------------------------------------------------

    @Test
    fun `mine splits by the personal bit and theirs is always shared`() {
        val bands = bandsOf(listOf(mine("a"), mine("b", personal = true), theirs("t", "ida")))
        assertEquals(listOf("a"), ids(bands.shared))
        assertEquals(listOf("b"), ids(bands.vault))
        assertEquals(listOf("t"), ids(bands.received))
    }

    @Test
    fun `received media never lands in the vault however its bit is set`() {
        // A sender's own bit is their business and says nothing about my bands.
        val given = theirs("t", "ida").copy(personal = true)
        val bands = bandsOf(listOf(mine("a"), given))
        assertTrue(bands.vault.isEmpty())
        assertEquals(listOf("t"), ids(bands.received))
    }

    @Test
    fun `my own order is kept exactly as stored`() {
        val bands = bandsOf(listOf(mine("c", at = 300), mine("a", at = 100), mine("b", at = 200)))
        assertEquals(listOf("c", "a", "b"), ids(bands.shared))
    }

    @Test
    fun `received media is ordered by capture time`() {
        val bands = bandsOf(listOf(theirs("late", "ida", at = 900), theirs("early", "tor", at = 100)))
        assertEquals(listOf("early", "late"), ids(bands.received))
    }

    @Test
    fun `received media without a capture time keeps its arrival order, last`() {
        val bands = bandsOf(
            listOf(theirs("unknown", "ida"), theirs("stamped", "tor", at = 100)),
        )
        assertEquals(listOf("stamped", "unknown"), ids(bands.received))
    }

    @Test
    fun `a new arrival never moves one of my photographs`() {
        // The property the ordering exists for: Reconcile has no time bound, so a
        // Contact made years later drops media into an old night.
        val before = listOf(mine("a"), mine("b"))
        val after = before + theirs("t", "ida", at = 1)
        assertEquals(ids(bandsOf(before).shared), ids(bandsOf(after).shared))
    }

    // ---- contributors ------------------------------------------------------

    @Test
    fun `an empty night has no contributors`() {
        assertEquals(0, bandsOf(emptyList()).contributors)
        assertTrue(!bandsOf(emptyList()).crossed)
    }

    @Test
    fun `only mine is one contributor`() {
        val bands = bandsOf(listOf(mine("a"), mine("b")))
        assertEquals(1, bands.contributors)
        assertTrue(!bands.crossed)
    }

    @Test
    fun `one sender and nothing of mine is one contributor`() {
        val bands = bandsOf(listOf(theirs("t", "ida"), theirs("u", "ida"), mine("v", personal = true)))
        assertEquals(1, bands.contributors)
        assertTrue(!bands.crossed)
    }

    @Test
    fun `mine plus one sender is a crossing`() {
        assertTrue(bandsOf(listOf(mine("a"), theirs("t", "ida"))).crossed)
    }

    @Test
    fun `two senders and nothing of mine is a crossing`() {
        assertTrue(bandsOf(listOf(theirs("t", "ida"), theirs("u", "tor"))).crossed)
    }

    // ---- the release hint, from both gestures ------------------------------

    @Test
    fun `adding to shared where one sender waits promises a crossing`() {
        val night = listOf(theirs("t", "ida"))
        assertEquals(ReleaseHint.GAINED, hintForAdding(night, Band.SHARED))
    }

    @Test
    fun `adding to the vault promises nothing`() {
        val night = listOf(theirs("t", "ida"))
        assertEquals(ReleaseHint.NONE, hintForAdding(night, Band.VAULT))
    }

    @Test
    fun `adding to a band already crossed promises nothing`() {
        val night = listOf(mine("a"), theirs("t", "ida"))
        assertEquals(ReleaseHint.NONE, hintForAdding(night, Band.SHARED))
    }

    @Test
    fun `adding where nobody else is promises nothing`() {
        assertEquals(ReleaseHint.NONE, hintForAdding(listOf(mine("a", personal = true)), Band.SHARED))
    }

    @Test
    fun `dragging up out of the vault earns the identical promise`() {
        // Same act, differently sourced — and the same answer, from one derivation.
        val night = listOf(mine("v", personal = true), theirs("t", "ida"))
        assertEquals(ReleaseHint.GAINED, hintForMoving(night, "v", Band.SHARED))
        assertEquals(hintForAdding(listOf(theirs("t", "ida")), Band.SHARED), hintForMoving(night, "v", Band.SHARED))
    }

    @Test
    fun `dragging my last shared photograph down warns the crossing is lost`() {
        val night = listOf(mine("a"), theirs("t", "ida"))
        assertEquals(ReleaseHint.LOST, hintForMoving(night, "a", Band.VAULT))
    }

    @Test
    fun `dragging one down while another of mine stays warns nothing`() {
        val night = listOf(mine("a"), mine("b"), theirs("t", "ida"))
        assertEquals(ReleaseHint.NONE, hintForMoving(night, "a", Band.VAULT))
    }

    @Test
    fun `dragging down where nobody else is warns nothing`() {
        assertEquals(ReleaseHint.NONE, hintForMoving(listOf(mine("a")), "a", Band.VAULT))
    }

    @Test
    fun `reordering inside a band never promises or warns`() {
        val night = listOf(mine("a"), mine("b"), theirs("t", "ida"))
        assertEquals(ReleaseHint.NONE, hintForMoving(night, "a", Band.SHARED))
    }

    @Test
    fun `a received photograph offers no hint because it cannot move`() {
        val night = listOf(mine("a", personal = true), theirs("t", "ida"))
        assertEquals(ReleaseHint.NONE, hintForMoving(night, "t", Band.VAULT))
    }

    // ---- moving ------------------------------------------------------------

    @Test
    fun `moving between bands flips the personal bit`() {
        val night = listOf(mine("a"))
        val after = moveMedia(night, "a", Band.VAULT, 0)
        assertTrue(after.single().personal)
        assertTrue(!moveMedia(after, "a", Band.SHARED, 0).single().personal)
    }

    @Test
    fun `reordering within a band leaves every bit untouched`() {
        val night = listOf(mine("a"), mine("b"), mine("c"), mine("v", personal = true))
        val after = moveMedia(night, "c", Band.SHARED, 0)
        assertEquals(listOf("c", "a", "b"), ids(bandsOf(after).shared))
        assertEquals(night.associate { it.id to it.personal }, after.associate { it.id to it.personal })
    }

    @Test
    fun `a received photograph refuses to move`() {
        val night = listOf(mine("a"), theirs("t", "ida"))
        assertEquals(night, moveMedia(night, "t", Band.VAULT, 0))
    }

    @Test
    fun `an unknown id leaves the night alone`() {
        val night = listOf(mine("a"))
        assertEquals(night, moveMedia(night, "nope", Band.VAULT, 0))
    }

    @Test
    fun `my own media always ends up left of anyone else's`() {
        val night = listOf(theirs("t", "ida"), mine("v", personal = true))
        val after = moveMedia(night, "v", Band.SHARED, 99)
        assertEquals(listOf("v", "t"), after.map { it.id })
    }

    @Test
    fun `an out of range index is clamped rather than throwing`() {
        val night = listOf(mine("a"), mine("b"))
        assertEquals(listOf("b", "a"), ids(bandsOf(moveMedia(night, "b", Band.SHARED, -5)).shared))
    }

    // ---- the upgrade -------------------------------------------------------

    @Test
    fun `an unshared night sends all of my media to the vault`() {
        val night = listOf(mine("a"), mine("b"), theirs("t", "ida"))
        val after = toBands(night, nightShared = false)
        assertTrue(bandsOf(after).shared.isEmpty())
        assertEquals(listOf("a", "b"), ids(bandsOf(after).vault))
    }

    @Test
    fun `a night that was actually shared keeps its media shared`() {
        val night = listOf(mine("a"), mine("b", personal = true))
        val after = toBands(night, nightShared = true)
        assertEquals(listOf("a"), ids(bandsOf(after).shared))
        assertEquals(listOf("b"), ids(bandsOf(after).vault))
    }

    @Test
    fun `the upgrade never touches received media`() {
        val after = toBands(listOf(theirs("t", "ida")), nightShared = false)
        assertEquals(listOf("t"), ids(bandsOf(after).received))
        assertTrue(bandsOf(after).vault.isEmpty())
    }

    @Test
    fun `running the upgrade twice changes nothing the second time`() {
        val night = listOf(mine("a"), theirs("t", "ida"))
        val once = toBands(night, nightShared = false)
        assertEquals(once, toBands(once, nightShared = false))
    }
}
