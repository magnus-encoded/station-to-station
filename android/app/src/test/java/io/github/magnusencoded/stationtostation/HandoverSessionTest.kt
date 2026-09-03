package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.AccountsMove
import io.github.magnusencoded.stationtostation.data.AccountsPayload
import io.github.magnusencoded.stationtostation.data.CATEGORY_ACCOUNTS
import io.github.magnusencoded.stationtostation.data.CATEGORY_SETLISTS
import io.github.magnusencoded.stationtostation.data.Credentials
import io.github.magnusencoded.stationtostation.data.HandoverManifest
import io.github.magnusencoded.stationtostation.data.HandoverPlan
import io.github.magnusencoded.stationtostation.data.Identities
import io.github.magnusencoded.stationtostation.data.OfferedMedia
import io.github.magnusencoded.stationtostation.data.StoredGig
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.TimelineCache
import io.github.magnusencoded.stationtostation.data.deviceManifest
import io.github.magnusencoded.stationtostation.data.exchange.HandoverInvite
import io.github.magnusencoded.stationtostation.data.exchange.HandoverReceipt
import io.github.magnusencoded.stationtostation.data.exchange.parseHandoverInvite
import io.github.magnusencoded.stationtostation.data.exchange.proveLinkKey
import io.github.magnusencoded.stationtostation.data.exchange.readAccountsAck
import io.github.magnusencoded.stationtostation.data.exchange.readAccountsStep
import io.github.magnusencoded.stationtostation.data.exchange.readRequest
import io.github.magnusencoded.stationtostation.data.exchange.runHandoverReceiver
import io.github.magnusencoded.stationtostation.data.exchange.runHandoverSource
import io.github.magnusencoded.stationtostation.data.exchange.toUri
import io.github.magnusencoded.stationtostation.data.exchange.verifyLinkKey
import io.github.magnusencoded.stationtostation.data.exchange.writeAccountsAck
import io.github.magnusencoded.stationtostation.data.exchange.writeAccountsStep
import io.github.magnusencoded.stationtostation.data.exchange.writeItem
import io.github.magnusencoded.stationtostation.data.exchange.writeManifest
import io.github.magnusencoded.stationtostation.data.identitiesOnly
import io.github.magnusencoded.stationtostation.data.sealManifest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * A whole device handover, end to end, over a plain loopback pair (#142).
 *
 * No TLS here on purpose: `HandoverWireTest` already proves the pinned handshake, and the
 * sequencing this file is about — accounts before bytes, a manifest that must verify
 * before anything is written, a cancelled transfer leaving a coherent smaller library —
 * is the same code on either socket. Names and bytes are invented, per the repo's fixture
 * policy.
 */
class HandoverSessionTest {

    private val linkKey = "the key the code carried".toByteArray()

    private fun tempDir(name: String): File =
        File.createTempFile(name, "").apply { delete(); mkdirs() }

    /** One night, one photograph, in the source's own ids. */
    private fun sourceCache(ref: String, personal: Boolean = false) = TimelineCache(
        gigs = mapOf("gig-1" to StoredGig(id = "gig-1", setlistId = "sl-1", artist = "Paper Cranes")),
        gigMedia = mapOf(
            "gig-1" to listOf(
                StoredMedia(id = "photo-1", kind = StoredMedia.Kind.PHOTO, ref = ref, personal = personal),
            ),
        ),
    )

    private fun withSizes(manifest: HandoverManifest, bytes: Long, hash: String) =
        manifest.copy(media = manifest.media.map { it.copy(bytes = bytes, hash = hash) })

    /** A connected pair of plain sockets: whoever hosts, whoever joins. */
    private fun pair(): Pair<ServerSocket, Socket> {
        val server = ServerSocket(0)
        val client = Socket("127.0.0.1", server.localPort)
        return server to client
    }

    @Test
    fun `a handover moves the accounts step, then the manifest, then the bytes, and lands a union`() {
        val dir = tempDir("handover-happy")
        val source = File(dir, "source.bin").apply { writeBytes("a photograph of the front row".toByteArray()) }
        val landing = File(dir, "landed.bin")

        val cache = sourceCache(source.toURI().toString())
        val allow = setOf(CATEGORY_SETLISTS, StoredMedia.Kind.PHOTO, CATEGORY_ACCOUNTS)
        val manifest = withSizes(
            deviceManifest(cache, allow, Identities(setlistFmUser = "wandering-owl")),
            source.length(),
            "hash-1",
        )
        val payload = AccountsPayload(
            identities = Identities(setlistFmUser = "wandering-owl"),
            credentials = Credentials(spotifyRefreshToken = "invented-refresh-token", spotifyScope = "invented-scope"),
        )

        val (server, client) = pair()
        var sourceReceipt: HandoverReceipt? = null
        val hosting = Thread {
            server.accept().use { socket ->
                runBlocking {
                    sourceReceipt = runHandoverSource(
                        socket = socket,
                        linkKey = linkKey,
                        allow = allow,
                        manifest = manifest,
                        accounts = { s ->
                            writeAccountsStep(s, payload)
                            if (readAccountsAck(s)) AccountsMove.ACKNOWLEDGED else AccountsMove.SENT
                        },
                        mediaSource = { id ->
                            if (id == "photo-1") source.length() to FileInputStream(source) else null
                        },
                    )
                }
            }
        }
        hosting.start()

        var arrivedAccounts: AccountsPayload? = null
        var applied: HandoverPlan? = null
        val receipt = client.use { socket ->
            runBlocking {
                runHandoverReceiver(
                    socket = socket,
                    linkKey = linkKey,
                    accounts = { s ->
                        arrivedAccounts = readAccountsStep(s)
                        writeAccountsAck(s)
                        arrivedAccounts
                    },
                    mine = TimelineCache(),
                    gallery = emptyList(),
                    receivedFile = { _, _ -> landing },
                    apply = { replan -> applied = replan(TimelineCache()) },
                )
            }
        }
        hosting.join(5000)
        server.close()

        // The credential moved as its own acknowledged step, before a byte of media.
        assertEquals("invented-refresh-token", arrivedAccounts?.credentials?.spotifyRefreshToken)
        assertEquals(1, receipt?.landed)
        assertEquals(1, receipt?.requested)
        assertEquals(source.length(), receipt?.bytes)
        assertEquals("", receipt?.trouble)
        // The receipt is honest that accounts were part of this handover and arrived (#143
        // story 9) — reusing the same `AccountsMove` the acknowledged step already is.
        assertEquals(AccountsMove.ACKNOWLEDGED, receipt?.accountsMove)
        // Both phones say the same thing about the transfer.
        assertEquals(receipt, sourceReceipt)
        assertEquals("a photograph of the front row", landing.readText())

        // The union that would be written: their night, their photograph, pointed at the
        // file that actually arrived.
        val landed = applied!!.merged.gigMedia.getValue("gig-1").single()
        assertEquals("photo-1", landed.id)
        assertEquals(landing.toURI().toString(), landed.ref)
        assertEquals("Paper Cranes", applied!!.merged.gigs.getValue("gig-1").artist)
    }

    /**
     * Accounts ticked, but the receiver's ack never arrives: `bulkMayStart` refuses the
     * bulk transfer and the source hands back its own receipt on the spot, never reaching
     * the receiver's. That receipt must still be honest that accounts were offered and did
     * not complete (#143 story 9) — never silently NOT_OFFERED, which would read as
     * "the row was not ticked" when it plainly was.
     */
    @Test
    fun `a receipt says accounts did not complete when the ack never arrives`() {
        val allow = setOf(CATEGORY_SETLISTS, CATEGORY_ACCOUNTS)
        val (server, client) = pair()
        var sourceReceipt: HandoverReceipt? = null
        val hosting = Thread {
            server.accept().use { socket ->
                runBlocking {
                    sourceReceipt = runHandoverSource(
                        socket = socket,
                        linkKey = linkKey,
                        allow = allow,
                        manifest = HandoverManifest(),
                        // Sent, but the receiver hangs up before acking.
                        accounts = { s -> writeAccountsStep(s, AccountsPayload()); AccountsMove.SENT },
                        mediaSource = { null },
                    )
                }
            }
        }
        hosting.start()

        client.use { socket ->
            proveLinkKey(socket, linkKey)
            readAccountsStep(socket) // read the frame, never ack, then close
        }
        hosting.join(5000)
        server.close()

        assertEquals(AccountsMove.SENT, sourceReceipt?.accountsMove)
        assertEquals("the accounts step did not complete", sourceReceipt?.trouble)
    }

    @Test
    fun `an item the source never offered is not sent, however loudly it is asked for`() {
        val dir = tempDir("handover-unoffered")
        val secret = File(dir, "vault.bin").apply { writeBytes("a photograph nobody ticked".toByteArray()) }

        // Ticked: photos. Not ticked: the vault. The manifest is built from the tick list,
        // so the vault item is not in it at all.
        val cache = sourceCache(secret.toURI().toString(), personal = true)
        val allow = setOf(CATEGORY_SETLISTS, StoredMedia.Kind.PHOTO)
        val manifest = deviceManifest(cache, allow)
        assertTrue(manifest.media.isEmpty())

        val (server, client) = pair()
        val hosting = Thread {
            server.accept().use { socket ->
                runBlocking {
                    runHandoverSource(
                        socket = socket,
                        linkKey = linkKey,
                        allow = allow,
                        manifest = manifest,
                        accounts = { AccountsMove.NOT_OFFERED },
                        mediaSource = { id -> secret.length() to FileInputStream(secret) },
                    )
                }
            }
        }
        hosting.start()

        val landing = File(dir, "landed.bin")
        val receipt = client.use { socket ->
            runBlocking {
                runHandoverReceiver(
                    socket = socket,
                    linkKey = linkKey,
                    accounts = { null },
                    mine = TimelineCache(),
                    gallery = emptyList(),
                    receivedFile = { _, _ -> landing },
                    apply = {},
                    // The receiver asks for nothing because nothing was offered; the point
                    // is that even a source asked for "photo-1" directly would not have it.
                )
            }
        }
        hosting.join(5000)
        server.close()

        assertEquals(0, receipt?.landed)
        assertFalse(landing.exists())
    }

    @Test
    fun `a phone that cannot produce the link key is sent nothing at all`() {
        val (server, client) = pair()
        var sourceResult: HandoverReceipt? = HandoverReceipt(landed = -1)
        var accountsRan = false
        val hosting = Thread {
            server.accept().use { socket ->
                runBlocking {
                    sourceResult = runHandoverSource(
                        socket = socket,
                        linkKey = linkKey,
                        allow = setOf(CATEGORY_SETLISTS),
                        manifest = HandoverManifest(),
                        accounts = { accountsRan = true; AccountsMove.NOT_OFFERED },
                        mediaSource = { null },
                    )
                }
            }
        }
        hosting.start()

        var applied = false
        val receipt = client.use { socket ->
            runBlocking {
                runHandoverReceiver(
                    socket = socket,
                    linkKey = "a guess from someone watching over your shoulder".toByteArray(),
                    accounts = { null },
                    mine = TimelineCache(),
                    gallery = emptyList(),
                    receivedFile = { _, _ -> File(tempDir("never"), "never.bin") },
                    apply = { applied = true },
                )
            }
        }
        hosting.join(5000)
        server.close()

        assertNull(sourceResult)
        assertFalse(accountsRan)
        // No manifest ever arrived, so there was never a plan and nothing was written.
        assertNull(receipt)
        assertFalse(applied)
    }

    @Test
    fun `a manifest that fails verification writes nothing`() {
        val (server, client) = pair()
        // A hand-driven source: the link key round is honest, the manifest is not.
        val hosting = Thread {
            server.accept().use { socket ->
                verifyLinkKey(socket, linkKey)
                val sealed = sealManifest(
                    linkKey,
                    HandoverManifest(
                        timeline = TimelineCache(gigs = mapOf("gig-1" to StoredGig(id = "gig-1", setlistId = "sl-1"))),
                        media = listOf(OfferedMedia(id = "photo-1", gigId = "gig-1", hash = "h")),
                    ),
                )
                // One bit of the payload, flipped in transit — the case the tag exists for.
                writeManifest(socket, sealed.copy(payload = sealed.payload.replace("\"personal\":false", "\"personal\":true")))
            }
        }
        hosting.start()

        var applied = false
        val receipt = client.use { socket ->
            runBlocking {
                runHandoverReceiver(
                    socket = socket,
                    linkKey = linkKey,
                    accounts = { null },
                    mine = TimelineCache(),
                    gallery = emptyList(),
                    receivedFile = { _, _ -> File(tempDir("never"), "never.bin") },
                    apply = { applied = true },
                )
            }
        }
        hosting.join(5000)
        server.close()

        assertNull(receipt)
        assertFalse(applied)
    }

    @Test
    fun `a transfer that stops part way keeps what arrived and says so`() {
        val dir = tempDir("handover-cut")
        val manifest = HandoverManifest(
            timeline = TimelineCache(
                gigs = mapOf("gig-1" to StoredGig(id = "gig-1", setlistId = "sl-1")),
                gigMedia = mapOf(
                    "gig-1" to listOf(
                        StoredMedia(id = "photo-1", kind = StoredMedia.Kind.PHOTO),
                        StoredMedia(id = "photo-2", kind = StoredMedia.Kind.PHOTO),
                    ),
                ),
            ),
            media = listOf(
                OfferedMedia(id = "photo-1", gigId = "gig-1", hash = "h1", bytes = 5),
                OfferedMedia(id = "photo-2", gigId = "gig-1", hash = "h2", bytes = 5),
            ),
        )

        val (server, client) = pair()
        val hosting = Thread {
            server.accept().use { socket ->
                verifyLinkKey(socket, linkKey)
                writeManifest(socket, sealManifest(linkKey, manifest))
                readRequest(socket)
                // The first item lands whole. Then the phone is picked up and walked out
                // of the room: no second item, no end-of-items marker.
                "first".toByteArray().let { writeItem(socket, "photo-1", it.size.toLong(), it.inputStream()) }
            }
        }
        hosting.start()

        var applied: HandoverPlan? = null
        val receipt = client.use { socket ->
            runBlocking {
                runHandoverReceiver(
                    socket = socket,
                    linkKey = linkKey,
                    accounts = { null },
                    mine = TimelineCache(),
                    gallery = emptyList(),
                    receivedFile = { id, _ -> File(dir, "$id.bin") },
                    apply = { replan -> applied = replan(TimelineCache()) },
                )
            }
        }
        hosting.join(5000)
        server.close()

        assertEquals(1, receipt?.landed)
        assertTrue(receipt!!.trouble.isNotEmpty())
        // A coherent smaller library: the photograph that arrived is attached, the one
        // that did not is simply absent — no record pointing at a file that is not there.
        val landed = applied!!.merged.gigMedia.getValue("gig-1")
        assertEquals(listOf("photo-1"), landed.map { it.id })
        assertEquals("first", File(dir, "photo-1.bin").readText())
        // And it is still on the request list next time, which is the whole of resumption.
        assertEquals(listOf("photo-2"), applied!!.request)
    }

    @Test
    fun `the invite survives a round trip and junk is refused`() {
        val invite = HandoverInvite("192.168.1.23", 41234, ByteArray(32) { it.toByte() }, ByteArray(32) { 7 })
        assertEquals(invite, parseHandoverInvite(invite.toUri()))

        assertNull(parseHandoverInvite("station-to-station://friend?u=someone"))
        assertNull(parseHandoverInvite("station-to-station://handover?h=1.2.3.4&p=0&f=aa&k=bb"))
        assertNull(parseHandoverInvite("station-to-station://handover?h=1.2.3.4&p=41234&f=aa"))
        assertNull(parseHandoverInvite("station-to-station://handover?h=1.2.3.4&p=41234&f=xyz&k=bb"))
    }

    /**
     * The accounts row unticked, wired exactly as the two `AppViewModel` halves wire it:
     * the source always writes the step and waits for the ack, the receiver always reads
     * one. A source that skipped the frame when the row was unticked would leave the
     * receiver reading the *sealed manifest* as an accounts payload, and everything after
     * it a frame out of step — so this asserts the ordinary case, which is the one nobody
     * ticks accounts for.
     */
    @Test
    fun `records without accounts still send the identities frame, and the manifest still lands`() {
        val dir = tempDir("handover-no-accounts")
        val source = File(dir, "source.bin").apply { writeBytes("a photograph of the encore".toByteArray()) }
        val landing = File(dir, "landed.bin")

        val cache = sourceCache(source.toURI().toString())
        val allow = setOf(CATEGORY_SETLISTS, StoredMedia.Kind.PHOTO)
        val identities = Identities(setlistFmUser = "wandering-owl")
        val manifest = withSizes(deviceManifest(cache, allow, identities), source.length(), "hash-1")

        val (server, client) = pair()
        val hosting = Thread {
            server.accept().use { socket ->
                runBlocking {
                    runHandoverSource(
                        socket = socket,
                        linkKey = linkKey,
                        allow = allow,
                        manifest = manifest,
                        accounts = { s ->
                            writeAccountsStep(s, identitiesOnly(identities))
                            if (readAccountsAck(s)) AccountsMove.ACKNOWLEDGED else AccountsMove.SENT
                        },
                        mediaSource = { source.length() to FileInputStream(source) },
                    )
                }
            }
        }
        hosting.start()

        var arrivedAccounts: AccountsPayload? = null
        var applied: HandoverPlan? = null
        val receipt = client.use { socket ->
            runBlocking {
                runHandoverReceiver(
                    socket = socket,
                    linkKey = linkKey,
                    accounts = { s ->
                        arrivedAccounts = readAccountsStep(s)
                        writeAccountsAck(s)
                        arrivedAccounts
                    },
                    mine = TimelineCache(),
                    gallery = emptyList(),
                    receivedFile = { _, _ -> landing },
                    apply = { replan -> applied = replan(TimelineCache()) },
                )
            }
        }
        hosting.join(5000)
        server.close()

        // Who I am travelled; nothing that acts as me did.
        assertEquals("wandering-owl", arrivedAccounts?.identities?.setlistFmUser)
        assertNull(arrivedAccounts?.credentials?.spotifyRefreshToken)
        assertEquals(1, receipt?.landed)
        assertEquals("", receipt?.trouble)
        // The row was not ticked, so the receipt says nothing arrived on that front either
        // (#143 story 9) — an identities-only frame is not a credential move.
        assertEquals(AccountsMove.NOT_OFFERED, receipt?.accountsMove)
        assertEquals("a photograph of the encore", landing.readText())
        assertEquals("photo-1", applied!!.merged.gigMedia.getValue("gig-1").single().id)
    }
}
