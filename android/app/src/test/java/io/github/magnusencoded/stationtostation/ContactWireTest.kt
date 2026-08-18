package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.exchange.certFingerprint
import io.github.magnusencoded.stationtostation.data.exchange.contactSessionContext
import io.github.magnusencoded.stationtostation.data.exchange.proveContactIdentity
import io.github.magnusencoded.stationtostation.data.exchange.verifyContactIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** Same DER as a real cert with one byte flipped — enough to give [certFingerprint] a
 * different answer without needing a second real certificate authority in the test. */
private class MismatchedCertificate(real: Certificate) : Certificate("X.509") {
    private val bytes = real.encoded.also { it[0] = it[0].inc() }
    override fun getEncoded() = bytes
    override fun verify(key: java.security.PublicKey?) = throw UnsupportedOperationException()
    override fun verify(key: java.security.PublicKey?, sigProvider: String?) = throw UnsupportedOperationException()
    override fun getPublicKey(): java.security.PublicKey = throw UnsupportedOperationException()
    override fun toString() = "MismatchedCertificate"
}

/**
 * The #257 session-auth half of `ContactWire`: no fingerprint is pinned ahead of the
 * handshake (there is no QR here), so this pins the thing that *is* checked — the
 * challenge-response signature — the same loopback-socket way [HandoverWireTest] pins
 * the QR-pinning half of #142.
 */
class ContactWireTest {

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
    fun `a known Contact's signature is matched to its own key`() {
        val (server, client) = handshake()
        val (privateKey, publicKey) = contactIdentity()
        val ownCert = server.session.localCertificates[0]

        val serverThread = Thread { proveContactIdentity(server, ownCert, privateKey) }
        serverThread.start()

        val peerCert = client.session.peerCertificates[0]
        val matched = verifyContactIdentity(client, peerCert, listOf("someone-else's-key", publicKey))
        serverThread.join(5000)
        server.close()
        client.close()

        assertEquals(publicKey, matched)
    }

    @Test
    fun `a stranger's signature matches none of the known Contacts`() {
        val (server, client) = handshake()
        val (strangerPrivateKey, _) = contactIdentity()
        val (_, knownPublicKey) = contactIdentity()
        val ownCert = server.session.localCertificates[0]

        val serverThread = Thread { proveContactIdentity(server, ownCert, strangerPrivateKey) }
        serverThread.start()

        val peerCert = client.session.peerCertificates[0]
        val matched = verifyContactIdentity(client, peerCert, listOf(knownPublicKey))
        serverThread.join(5000)
        server.close()
        client.close()

        assertNull("a signature from an unknown key must match nothing", matched)
    }

    @Test
    fun `the fingerprint binds the proof to this session, not a captured signature`() {
        // Same identity key, but proveContactIdentity is handed a certificate that does
        // not match what the client actually sees on this socket — standing in for a
        // signature captured off a different TLS session and replayed onto this one.
        val (server, client) = handshake()
        val (privateKey, publicKey) = contactIdentity()
        val wrongCert = MismatchedCertificate(server.session.localCertificates[0])

        val serverThread = Thread { proveContactIdentity(server, wrongCert, privateKey) }
        serverThread.start()

        val peerCert = client.session.peerCertificates[0]
        val matched = verifyContactIdentity(client, peerCert, listOf(publicKey))
        serverThread.join(5000)
        server.close()
        client.close()

        assertNull("a signature over the wrong session's fingerprint must not verify", matched)
    }
}
