package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.HandoverManifest
import io.github.magnusencoded.stationtostation.data.OfferedMedia
import io.github.magnusencoded.stationtostation.data.StoredGig
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.TimelineCache
import io.github.magnusencoded.stationtostation.data.exchange.contactSessionContext
import io.github.magnusencoded.stationtostation.data.exchange.receiveRequested
import io.github.magnusencoded.stationtostation.data.exchange.runContactSession
import io.github.magnusencoded.stationtostation.data.exchange.writeEndOfItems
import io.github.magnusencoded.stationtostation.data.exchange.writeItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * A whole LAN reconcile visit end to end (#257), over the same loopback-socket rig
 * [ContactWireTest] uses for the auth half alone. Names are invented, same reason as
 * [ContactReconcileTest].
 */
class ContactSessionTest {

    private fun fixtureKeyStore(): KeyStore {
        val ks = KeyStore.getInstance("PKCS12")
        javaClass.classLoader!!.getResourceAsStream("handover-fixture.p12")!!.use {
            ks.load(it, "handover-fixture".toCharArray())
        }
        return ks
    }

    private fun contactIdentity(): Pair<PrivateKey, String> {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        return pair.private to Base64.getEncoder().encodeToString(pair.public.encoded)
    }

    private fun handshake(): Pair<SSLSocket, SSLSocket> {
        val password = "handover-fixture".toCharArray()
        val server = contactSessionContext(fixtureKeyStore(), password)
            .serverSocketFactory.createServerSocket(0) as SSLServerSocket
        var accepted: SSLSocket? = null
        val serverThread = Thread {
            accepted = (server.accept() as SSLSocket).apply { wantClientAuth = true }
            accepted!!.startHandshake()
        }
        serverThread.start()
        val client = contactSessionContext(fixtureKeyStore(), password)
            .socketFactory.createSocket("127.0.0.1", server.localPort) as SSLSocket
        client.startHandshake()
        serverThread.join(5000)
        server.close()
        return accepted!! to client
    }

    @Test
    fun `two known Contacts land each other's requested media on the gig they share`() {
        val (server, client) = handshake()
        val (serverPrivate, serverPublic) = contactIdentity()
        val (clientPrivate, clientPublic) = contactIdentity()
        val serverCert = server.session.localCertificates[0]
        val clientCert = client.session.localCertificates[0]

        val tmp = File.createTempFile("contact-session", "").apply { delete(); mkdirs() }
        val serverSourceFile = File(tmp, "server-source.bin").apply { writeBytes("server photo".toByteArray()) }
        val clientReceivedFile = File(tmp, "client-received.bin")

        val serverManifest = HandoverManifest(
            timeline = TimelineCache(
                gigs = mapOf("s-gig" to StoredGig(id = "s-gig", setlistId = "sl-1")),
                gigMedia = mapOf("s-gig" to listOf(StoredMedia(id = "server-photo", kind = StoredMedia.Kind.PHOTO))),
            ),
            media = listOf(
                OfferedMedia(id = "server-photo", gigId = "s-gig", kind = StoredMedia.Kind.PHOTO, hash = "h-server"),
            ),
        )
        val clientManifest = HandoverManifest()
        val clientMine = TimelineCache(gigs = mapOf("c-gig" to StoredGig(id = "c-gig", setlistId = "sl-1")))

        var serverResult: Map<String, List<StoredMedia>>? = null
        val serverThread = Thread {
            serverResult = runContactSession(
                socket = server,
                isServer = true,
                ownCert = serverCert,
                privateKey = serverPrivate,
                candidates = listOf(clientPublic),
                myManifest = serverManifest,
                mine = TimelineCache(gigs = mapOf("s-gig" to StoredGig(id = "s-gig", setlistId = "sl-1"))),
                gallery = emptyList(),
                mediaSource = { id -> if (id == "server-photo") serverSourceFile.length() to FileInputStream(serverSourceFile) else null },
                receivedFile = { id, _ -> File(tmp, "unused-$id.bin") },
            )
        }
        serverThread.start()

        val clientResult = runContactSession(
            socket = client,
            isServer = false,
            ownCert = clientCert,
            privateKey = clientPrivate,
            candidates = listOf(serverPublic),
            myManifest = clientManifest,
            mine = clientMine,
            gallery = emptyList(),
            mediaSource = { null },
            receivedFile = { _, _ -> clientReceivedFile },
        )
        serverThread.join(5000)
        server.close()
        client.close()

        assertEquals(emptyMap<String, List<StoredMedia>>(), serverResult)
        val landed = clientResult!!.getValue("c-gig").single()
        assertEquals("server-photo", landed.id)
        assertEquals("server photo", clientReceivedFile.readText())
        assertEquals(clientReceivedFile.toURI().toString(), landed.ref)
    }

    /**
     * A sender is free to put whatever it likes on the wire (#267). "You offered it and I
     * declined" must not become "you sent it anyway and I stored it" — and, just as
     * importantly, dropping an item must not desync the stream: the body still has to be
     * walked, or the next header lands in the middle of somebody's photograph.
     *
     * Driven against [receiveRequested] directly rather than through [runContactSession],
     * because a well-behaved peer sends exactly what was asked for by construction.
     */
    @Test
    fun `an item nobody asked for is drained and dropped without desyncing the stream`() {
        val tmp = File.createTempFile("contact-drain", "").apply { delete(); mkdirs() }

        val server = java.net.ServerSocket(0)
        val sender = Thread {
            server.accept().use { out ->
                // Not requested. Not stored — but the bytes are still coming.
                "unwanted photo".toByteArray().let { writeItem(out, "unasked", it.size.toLong(), it.inputStream()) }
                // An id that would escape its directory, refused for a second reason.
                "escape".toByteArray().let { writeItem(out, "../../evil", it.size.toLong(), it.inputStream()) }
                // Asked for, and arriving after both — it lands only if the drains above
                // left the stream exactly where the next header begins.
                "wanted photo".toByteArray().let { writeItem(out, "wanted", it.size.toLong(), it.inputStream()) }
                writeEndOfItems(out)
            }
        }
        sender.start()

        val landed = java.net.Socket("127.0.0.1", server.localPort).use { receiver ->
            receiveRequested(
                socket = receiver,
                expected = setOf("wanted"),
                receivedFile = { id, _ -> File(tmp, "$id.bin") },
                refFor = { it.toURI().toString() },
                kinds = emptyMap(),
            )
        }
        sender.join(5000)
        server.close()

        assertEquals(setOf("wanted"), landed.keys)
        assertEquals("wanted photo", File(tmp, "wanted.bin").readText())
        assertFalse(File(tmp, "unasked.bin").exists())
    }

    @Test
    fun `a stranger gets no manifest at all`() {
        val (server, client) = handshake()
        val (serverPrivate, _) = contactIdentity()
        val (strangerPrivate, _) = contactIdentity()
        val (_, knownPublic) = contactIdentity()
        val serverCert = server.session.localCertificates[0]
        val clientCert = client.session.localCertificates[0]

        val serverThread = Thread {
            runContactSession(
                socket = server,
                isServer = true,
                ownCert = serverCert,
                privateKey = serverPrivate,
                candidates = listOf(knownPublic),
                myManifest = HandoverManifest(),
                mine = TimelineCache(),
                gallery = emptyList(),
                mediaSource = { null },
                receivedFile = { _, _ -> File.createTempFile("unused", ".bin") },
            )
        }
        serverThread.start()

        val clientResult = runContactSession(
            socket = client,
            isServer = false,
            ownCert = clientCert,
            privateKey = strangerPrivate,
            candidates = listOf(knownPublic),
            myManifest = HandoverManifest(),
            mine = TimelineCache(),
            gallery = emptyList(),
            mediaSource = { null },
            receivedFile = { _, _ -> File.createTempFile("unused", ".bin") },
        )
        serverThread.join(5000)
        server.close()
        client.close()

        assertNull(clientResult)
    }
}
