package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.AccountsPayload
import io.github.magnusencoded.stationtostation.data.Credentials
import io.github.magnusencoded.stationtostation.data.Identities
import io.github.magnusencoded.stationtostation.data.SealedManifest
import io.github.magnusencoded.stationtostation.data.exchange.PinnedTrustManager
import io.github.magnusencoded.stationtostation.data.exchange.certFingerprint
import io.github.magnusencoded.stationtostation.data.exchange.copyExactly
import io.github.magnusencoded.stationtostation.data.exchange.keyManagersFor
import io.github.magnusencoded.stationtostation.data.exchange.proveLinkKey
import io.github.magnusencoded.stationtostation.data.exchange.readAccountsAck
import io.github.magnusencoded.stationtostation.data.exchange.readAccountsStep
import io.github.magnusencoded.stationtostation.data.exchange.readItemHeader
import io.github.magnusencoded.stationtostation.data.exchange.readManifest
import io.github.magnusencoded.stationtostation.data.exchange.sslClientContext
import io.github.magnusencoded.stationtostation.data.exchange.sslServerContext
import io.github.magnusencoded.stationtostation.data.exchange.verifyLinkKey
import io.github.magnusencoded.stationtostation.data.exchange.writeAccountsAck
import io.github.magnusencoded.stationtostation.data.exchange.writeAccountsStep
import io.github.magnusencoded.stationtostation.data.exchange.writeEndOfItems
import io.github.magnusencoded.stationtostation.data.exchange.writeFrame
import io.github.magnusencoded.stationtostation.data.exchange.writeItem
import io.github.magnusencoded.stationtostation.data.exchange.writeManifest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.X509ExtendedKeyManager

/**
 * The transport half of #142, run as plain JVM sockets over loopback — exactly what the
 * issue's Testing Decisions calls for: framing, resumption and cancel-coherence need no
 * radio. The actual encryption claim ("nobody on the wire can read this") is a packet
 * capture, not a unit test, and is not attempted here — see the PR description.
 *
 * The server's identity is a fixture certificate (`handover-fixture.p12`, an invented
 * CN, generated once with `openssl` and committed the same way `debug.keystore` already
 * is) rather than the real on-device `AndroidKeyStore` path, which does not exist off a
 * phone. That path is exercised by `AndroidKeyStoreCert` in production and is not
 * unit-testable; everything downstream of "here is a certificate and a private key" is.
 */
class HandoverWireTest {

    private val linkKey = "the key the QR carried".toByteArray()
    private val wrongLinkKey = "a guess from someone watching over your shoulder".toByteArray()

    private fun fixtureKeyStore(): KeyStore {
        val ks = KeyStore.getInstance("PKCS12")
        javaClass.classLoader!!.getResourceAsStream("handover-fixture.p12")!!.use {
            ks.load(it, "handover-fixture".toCharArray())
        }
        return ks
    }

    private fun fixtureCert(ks: KeyStore): X509Certificate =
        ks.getCertificate("handover-fixture") as X509Certificate

    /** A loopback TLS pair: a real server socket accepting a real pinned client socket,
     * each on its own thread, so the framing code runs over an actual handshake rather
     * than a mock. */
    private fun handshake(
        pinFingerprint: ByteArray = certFingerprint(fixtureCert(fixtureKeyStore())),
    ): Pair<javax.net.ssl.SSLSocket, javax.net.ssl.SSLSocket> {
        val ks = fixtureKeyStore()
        val server = sslServerContext(ks, "handover-fixture".toCharArray())
            .serverSocketFactory.createServerSocket(0) as SSLServerSocket
        var accepted: javax.net.ssl.SSLSocket? = null
        val serverThread = Thread {
            accepted = server.accept() as javax.net.ssl.SSLSocket
            accepted!!.startHandshake()
        }
        serverThread.start()
        val client = sslClientContext(pinFingerprint)
            .socketFactory.createSocket("127.0.0.1", server.localPort) as javax.net.ssl.SSLSocket
        client.startHandshake()
        serverThread.join(5000)
        server.close()
        return accepted!! to client
    }

    @Test
    fun `the client pins the exact certificate the QR fingerprint named`() {
        val (server, client) = handshake()
        assertTrue(server.isConnected)
        assertTrue(client.isConnected)
        server.close()
        client.close()
    }

    /**
     * The direct unit test the coordinator asked for, independent of a real handshake:
     * a [PinnedTrustManager] that ever accepts a non-matching certificate — an empty
     * `checkServerTrusted`, or one that logs instead of throwing — is invisible to any
     * test that only exercises the matching case, and is the single most common way this
     * class of code silently defeats itself. This asserts the throw directly.
     */
    @Test
    fun `PinnedTrustManager throws on a certificate that does not match, and never on client trust`() {
        val cert = fixtureCert(fixtureKeyStore())
        val wrongFingerprint = ByteArray(32) { 0x42 }
        val tm = PinnedTrustManager(wrongFingerprint)

        try {
            tm.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")
            throw AssertionError("expected a fingerprint mismatch to throw CertificateException")
        } catch (e: java.security.cert.CertificateException) {
            // expected
        }

        // The matching fingerprint must be accepted — otherwise the negative test above
        // would be meaningless (it could be throwing unconditionally).
        val matching = PinnedTrustManager(certFingerprint(cert))
        matching.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA") // must not throw

        // No client certificate is ever requested by this transport (see sslServerContext),
        // so accepting one here would be a silent widening of trust nobody asked for.
        try {
            matching.checkClientTrusted(arrayOf(cert), "ECDHE_ECDSA")
            throw AssertionError("expected checkClientTrusted to be refused unconditionally")
        } catch (e: java.security.cert.CertificateException) {
            // expected
        }
    }

    @Test
    fun `a fingerprint that does not match refuses the handshake`() {
        val wrongFingerprint = ByteArray(32) { 0x42 }
        try {
            handshake(pinFingerprint = wrongFingerprint)
            throw AssertionError("expected the handshake to be refused")
        } catch (e: SSLHandshakeException) {
            // expected: pinning rejected the certificate before any data moved.
        }
    }

    @Test
    fun `a device that cannot produce the link key gets nothing`() {
        val (server, client) = handshake()
        Thread {
            val ok = verifyLinkKey(server, linkKey)
            assertFalse("wrong key must not verify", ok)
            server.close() // never sends the manifest
        }.start()

        proveLinkKey(client, wrongLinkKey)
        assertNull("no manifest ever arrives when auth failed", readManifest(client))
        client.close()
    }

    @Test
    fun `the right link key unlocks the manifest and the items behind it`() {
        val (server, client) = handshake()
        val sealed = SealedManifest(payload = "{\"media\":[]}", mac = "deadbeef")
        val items = listOf("m1" to "one".toByteArray(), "m2" to "two-two".toByteArray())

        val serverThread = Thread {
            assertTrue(verifyLinkKey(server, linkKey))
            writeManifest(server, sealed)
            for ((id, bytes) in items) writeItem(server, id, bytes.size.toLong(), ByteArrayInputStream(bytes))
            writeEndOfItems(server)
            server.close()
        }
        serverThread.start()

        proveLinkKey(client, linkKey)
        assertEquals(sealed, readManifest(client))

        val received = LinkedHashMap<String, ByteArray>()
        while (true) {
            val header = readItemHeader(client) ?: break
            val (id, length) = header
            val out = ByteArrayOutputStream()
            copyExactly(client.getInputStream(), out, length)
            received[id] = out.toByteArray()
        }
        serverThread.join(5000)
        client.close()

        assertEquals(items.size, received.size)
        for ((id, bytes) in items) assertArrayEquals(bytes, received[id])
    }

    /**
     * Resumption is a caller decision — "send only what is missing" — and the transport
     * needs no special-case support for it beyond items being independently addressed.
     * A dropped connection mid-item is a hard failure (#16: a partial item must never
     * look like a complete one), and a fresh session sending only the remainder reaches
     * the same union either way.
     */
    @Test
    fun `an interrupted transfer resumes without duplicating items already held`() {
        val allItems = listOf(
            "m1" to "first".toByteArray(),
            "m2" to "second".toByteArray(),
            "m3" to "third".toByteArray(),
        )

        // First session: sender delivers m1 completely, then drops the connection
        // instead of sending an end-of-items marker or a second header.
        val (server1, client1) = handshake()
        val serverThread1 = Thread {
            assertTrue(verifyLinkKey(server1, linkKey))
            writeItem(server1, "m1", allItems[0].second.size.toLong(), ByteArrayInputStream(allItems[0].second))
            server1.close()
        }
        serverThread1.start()
        proveLinkKey(client1, linkKey)

        val held = LinkedHashSet<String>()
        val (id1, len1) = readItemHeader(client1)!!
        val out1 = ByteArrayOutputStream()
        copyExactly(client1.getInputStream(), out1, len1)
        held += id1
        assertArrayEquals(allItems[0].second, out1.toByteArray())

        // The next read is a dropped connection, not a clean "no more items": that
        // distinction is exactly what tells a resumable transfer from a finished one.
        try {
            readItemHeader(client1)
            throw AssertionError("expected the dropped connection to surface as a failure")
        } catch (e: EOFException) {
            // expected
        }
        client1.close()
        serverThread1.join(5000)

        // Second session: resume by sending only what was not already held.
        val (server2, client2) = handshake()
        val remaining = allItems.filterNot { it.first in held }
        val serverThread2 = Thread {
            assertTrue(verifyLinkKey(server2, linkKey))
            for ((id, bytes) in remaining) writeItem(server2, id, bytes.size.toLong(), ByteArrayInputStream(bytes))
            writeEndOfItems(server2)
            server2.close()
        }
        serverThread2.start()
        proveLinkKey(client2, linkKey)

        val union = LinkedHashMap<String, ByteArray>()
        union[id1] = out1.toByteArray()
        while (true) {
            val header = readItemHeader(client2) ?: break
            val (id, length) = header
            val out = ByteArrayOutputStream()
            copyExactly(client2.getInputStream(), out, length)
            union[id] = out.toByteArray()
        }
        serverThread2.join(5000)
        client2.close()

        assertEquals(allItems.map { it.first }.toSet(), union.keys)
        for ((id, bytes) in allItems) assertArrayEquals(bytes, union[id])
    }

    // --- Accounts step (#143), over the real transport this file tests everything else on ---

    /**
     * The whole point of "sent first, acked before the clear": the payload arrives, the
     * receiver stores it durably, and only *then* does the source see a true come back.
     * `Accounts.kt`'s `mayClearCredentials` is the pure decision; this is the wire event
     * that is allowed to feed it.
     */
    @Test
    fun `the receiver's ack only arrives after it has the payload, and the source sees it`() {
        val (server, client) = handshake()
        val payload = AccountsPayload(
            identities = Identities(setlistFmUser = "paper-cranes-fan", spotifyAccount = "spotify:user:invented"),
            credentials = Credentials(spotifyRefreshToken = "invented-refresh-token-not-a-real-one"),
        )
        var stored: AccountsPayload? = null
        val serverThread = Thread {
            assertTrue(verifyLinkKey(server, linkKey))
            stored = readAccountsStep(server)
            // Acking is the promise the payload is durable — this line stands in for
            // that store, which is why the ack is written only after it.
            writeAccountsAck(server)
            server.close()
        }
        serverThread.start()

        proveLinkKey(client, linkKey)
        writeAccountsStep(client, payload)
        val acked = readAccountsAck(client)
        serverThread.join(5000)
        client.close()

        assertEquals(payload, stored)
        assertTrue("the source must see the ack before it may clear its credential", acked)
    }

    /**
     * The atomicity case at the wire level, not just the state-machine level `AccountsTest`
     * already covers: a connection dropped after the payload but before the ack must read
     * as "not acknowledged", never as a false positive that would let the source clear a
     * credential nobody durably holds yet.
     */
    @Test
    fun `a connection dropped after the payload but before the ack reads as unacknowledged`() {
        val (server, client) = handshake()
        val payload = AccountsPayload(credentials = Credentials(spotifyRefreshToken = "invented-token-two"))
        val serverThread = Thread {
            assertTrue(verifyLinkKey(server, linkKey))
            readAccountsStep(server)
            server.close() // dropped before writeAccountsAck — no ack is ever sent
        }
        serverThread.start()

        proveLinkKey(client, linkKey)
        writeAccountsStep(client, payload)
        val acked = readAccountsAck(client)
        serverThread.join(5000)
        client.close()

        assertFalse("a dropped connection must never read as an acknowledgement", acked)
    }

    /** Declining the row still sends an (identities-only) payload — never nothing — so
     * the receiver can offer one-tap reconnect (#143 story 11) without a bearer secret. */
    @Test
    fun `a declined accounts row still arrives as identities without credentials`() {
        val (server, client) = handshake()
        val identitiesOnly = AccountsPayload(identities = Identities(setlistFmUser = "paper-cranes-fan"))
        var received: AccountsPayload? = null
        val serverThread = Thread {
            assertTrue(verifyLinkKey(server, linkKey))
            received = readAccountsStep(server)
            writeAccountsAck(server)
            server.close()
        }
        serverThread.start()

        proveLinkKey(client, linkKey)
        writeAccountsStep(client, identitiesOnly)
        readAccountsAck(client)
        serverThread.join(5000)
        client.close()

        assertEquals("paper-cranes-fan", received?.identities?.setlistFmUser)
        assertTrue(received?.credentials?.isEmpty == true)
    }

    @Test
    fun `an item that closes short of its declared length fails rather than truncating silently`() {
        val (server, client) = handshake()
        val serverThread = Thread {
            assertTrue(verifyLinkKey(server, linkKey))
            writeFrame(server.getOutputStream(), "{\"id\":\"m1\",\"bytes\":100}".toByteArray())
            server.getOutputStream().write("short".toByteArray())
            server.getOutputStream().flush()
            server.close()
        }
        serverThread.start()
        proveLinkKey(client, linkKey)

        val (id, length) = readItemHeader(client)!!
        assertEquals("m1", id)
        assertEquals(100L, length)
        try {
            copyExactly(client.getInputStream(), ByteArrayOutputStream(), length)
            throw AssertionError("expected a short body to fail rather than truncate")
        } catch (e: EOFException) {
            // expected
        }
        serverThread.join(5000)
        client.close()
    }

    /**
     * On-device the keystore is `AndroidKeyStore` — one store holding every key the app
     * ever made — so leaving the choice to the factory is how a handshake ends up
     * presenting the durable Contact identity (which cannot sign a handshake at all)
     * instead of the key this session minted. Pinned, the alias is the answer whatever
     * key type the peer asks for.
     */
    @Test
    fun `a pinned alias is the key TLS presents, whatever else the keystore holds`() {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(fixtureKeyStore(), "handover-fixture".toCharArray())
        val pinned = keyManagersFor(kmf, "handover-fixture").first() as X509ExtendedKeyManager

        assertEquals("handover-fixture", pinned.chooseServerAlias("RSA", null, null))
        assertEquals("handover-fixture", pinned.chooseClientAlias(arrayOf("EC"), null, null))
        assertNotNull(pinned.getPrivateKey("handover-fixture"))
        assertNotNull(pinned.getCertificateChain("handover-fixture"))
    }
}
