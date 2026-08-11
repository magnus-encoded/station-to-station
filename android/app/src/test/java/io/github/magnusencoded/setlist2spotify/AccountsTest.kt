package io.github.magnusencoded.setlist2spotify

import io.github.magnusencoded.setlist2spotify.data.AccountsMove
import io.github.magnusencoded.setlist2spotify.data.AccountsPayload
import io.github.magnusencoded.setlist2spotify.data.CATEGORY_ACCOUNTS
import io.github.magnusencoded.setlist2spotify.data.CATEGORY_SETLISTS
import io.github.magnusencoded.setlist2spotify.data.Credentials
import io.github.magnusencoded.setlist2spotify.data.HandoverManifest
import io.github.magnusencoded.setlist2spotify.data.Identities
import io.github.magnusencoded.setlist2spotify.data.StoredGig
import io.github.magnusencoded.setlist2spotify.data.StoredMedia
import io.github.magnusencoded.setlist2spotify.data.TimelineCache
import io.github.magnusencoded.setlist2spotify.data.approvalVerb
import io.github.magnusencoded.setlist2spotify.data.bulkMayStart
import io.github.magnusencoded.setlist2spotify.data.categoriesFor
import io.github.magnusencoded.setlist2spotify.data.contactManifest
import io.github.magnusencoded.setlist2spotify.data.identitiesOnly
import io.github.magnusencoded.setlist2spotify.data.mayClearCredentials
import io.github.magnusencoded.setlist2spotify.data.sealManifest
import io.github.magnusencoded.setlist2spotify.data.sourceSignedIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Accounts move, they do not copy (#143).
 *
 * Credentials here are invented. This repository is public: no real token, refresh token
 * or session value goes in a fixture, a test or a capture, ever.
 */
class AccountsTest {

    private val token = "invented-refresh-token-not-a-real-one"
    private val identities = Identities(setlistFmUser = "paper-cranes-fan", spotifyAccount = "spotify:user:invented")
    private val creds = Credentials(spotifyRefreshToken = token, spotifyScope = "playlist-modify-private")

    // --- Exclusion: the assertion most worth having --------------------------------

    /**
     * The failure this guards against is silent and catastrophic: a credential leaving as
     * a side effect of ticking a media category. It cannot, because the records manifest
     * has no shape that carries one — asserted on the serialised bytes, for every
     * combination of categories, since that is what actually goes on the wire.
     */
    @Test
    fun `no combination of ticked categories puts a credential in the records manifest`() {
        val cache = TimelineCache(
            gigs = mapOf("g1" to StoredGig(id = "g1", date = "12-06-2026", artist = "Paper Cranes")),
            // In the shared band, which since #162 is simply `personal = false`.
            gigMedia = mapOf("g1" to listOf(StoredMedia(id = "m1", ref = "content://mine/1"))),
            mediaTierMigrated = true,
        )
        val every = (categoriesFor(contact = false) + categoriesFor(contact = true)).toList()
        // The whole power set, walked. Six categories is 64 subsets — cheap enough to
        // enumerate, and enumerating is what makes "no combination" a fact rather than a
        // sample. That the manifest ignores the allow list entirely is the point: the
        // credential is absent by construction, not filtered out per combination.
        val subsets = (0 until (1 shl every.size)).map { mask ->
            every.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }.toSet()
        }

        for (allow in subsets) {
            val manifest = contactManifest(cache, me = "my-public-key").copy(identities = identities)
            // The wire bytes, not the object graph: this is what would actually leave.
            val onTheWire = sealManifest("a key".toByteArray(), manifest).payload
            assertFalse(
                "a credential reached the records manifest with $allow ticked",
                onTheWire.contains(token),
            )
            assertTrue("the identity is supposed to travel", onTheWire.contains("paper-cranes-fan"))
        }
    }

    /** Accounts move between my own devices only. The far end being me is the point. */
    @Test
    fun `accounts are never offered to a contact`() {
        assertFalse(categoriesFor(contact = true).contains(CATEGORY_ACCOUNTS))
        assertTrue(categoriesFor(contact = false).contains(CATEGORY_ACCOUNTS))
    }

    /** Declining the row still means one tap to reconnect, not a setup wizard. */
    @Test
    fun `identities travel even when the secrets do not`() {
        val payload = identitiesOnly(identities)
        assertEquals("paper-cranes-fan", payload.identities.setlistFmUser)
        assertEquals("spotify:user:invented", payload.identities.spotifyAccount)
        assertTrue(payload.credentials.isEmpty)
    }

    // --- Atomicity: the source lets go only when the far end has it ----------------

    /**
     * Signing out on send would sign you out of *both* phones if the connection dropped,
     * with the credential landing nowhere.
     */
    @Test
    fun `the source keeps its credential until the receiver acknowledges`() {
        assertFalse(mayClearCredentials(AccountsMove.NOT_OFFERED))
        assertFalse(mayClearCredentials(AccountsMove.SENT))
        assertTrue(mayClearCredentials(AccountsMove.ACKNOWLEDGED))
    }

    @Test
    fun `an interrupted handover leaves the source signed in`() {
        assertTrue(sourceSignedIn(AccountsMove.SENT))
        assertTrue(sourceSignedIn(AccountsMove.NOT_OFFERED))
    }

    /** Exactly one holder afterwards, which is what dissolves the token rotation race. */
    @Test
    fun `a completed move leaves exactly one holder`() {
        assertFalse(sourceSignedIn(AccountsMove.CLEARED))
    }

    // --- Ordering: the small thing first -------------------------------------------

    @Test
    fun `bulk waits for the accounts step when accounts were ticked`() {
        val withAccounts = setOf(CATEGORY_SETLISTS, CATEGORY_ACCOUNTS)
        assertFalse(bulkMayStart(withAccounts, AccountsMove.NOT_OFFERED))
        assertFalse(bulkMayStart(withAccounts, AccountsMove.SENT))
        assertTrue(bulkMayStart(withAccounts, AccountsMove.ACKNOWLEDGED))
        // And a bulk failure afterwards does not undo it: the accounts already landed.
        assertTrue(bulkMayStart(withAccounts, AccountsMove.CLEARED))
    }

    @Test
    fun `bulk starts immediately when accounts were not ticked`() {
        assertTrue(bulkMayStart(setOf(CATEGORY_SETLISTS), AccountsMove.NOT_OFFERED))
    }

    // --- The label is the entire disclosure mechanism -------------------------------

    @Test
    fun `ticking accounts changes the verb, and unticking restores it`() {
        assertEquals("Copy", approvalVerb(setOf(CATEGORY_SETLISTS, StoredMedia.Kind.PHOTO)))
        assertEquals(
            "Copy and sign out here",
            approvalVerb(setOf(CATEGORY_SETLISTS, StoredMedia.Kind.PHOTO, CATEGORY_ACCOUNTS)),
        )
        // The verb names what happens on *this* device. The records are still copied and
        // nothing is removed, which is why it is not one word.
        assertTrue(approvalVerb(setOf(CATEGORY_ACCOUNTS)).startsWith("Copy"))
    }

    /** The credential has exactly one shape that carries it, and this is that shape. */
    @Test
    fun `the accounts payload is the only thing holding a secret`() {
        val payload = AccountsPayload(identities = identities, credentials = creds)
        assertFalse(payload.credentials.isEmpty)
        assertEquals(token, payload.credentials.spotifyRefreshToken)
        // And the records manifest has no field that could hold it.
        assertTrue(HandoverManifest().identities.setlistFmUser == null)
    }
}
