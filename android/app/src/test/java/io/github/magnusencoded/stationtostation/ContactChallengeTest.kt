package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.exchange.signChallenge
import io.github.magnusencoded.stationtostation.data.exchange.verifyChallenge
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun p256KeyPair() =
    KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

class ContactChallengeTest {

    @Test
    fun `signature verifies against the matching public key`() {
        val pair = p256KeyPair()
        val nonce = "nonce-1".toByteArray()
        val signature = signChallenge(nonce, pair.private)
        val publicKeyBase64 = Base64.getEncoder().encodeToString(pair.public.encoded)

        assertTrue(verifyChallenge(nonce, signature, publicKeyBase64))
    }

    @Test
    fun `signature from a different key fails`() {
        val signer = p256KeyPair()
        val stranger = p256KeyPair()
        val nonce = "nonce-1".toByteArray()
        val signature = signChallenge(nonce, signer.private)
        val strangerKeyBase64 = Base64.getEncoder().encodeToString(stranger.public.encoded)

        assertFalse(verifyChallenge(nonce, signature, strangerKeyBase64))
    }

    @Test
    fun `tampered nonce fails`() {
        val pair = p256KeyPair()
        val signature = signChallenge("nonce-1".toByteArray(), pair.private)
        val publicKeyBase64 = Base64.getEncoder().encodeToString(pair.public.encoded)

        assertFalse(verifyChallenge("nonce-2".toByteArray(), signature, publicKeyBase64))
    }

    @Test
    fun `null or blank key fails rather than throwing`() {
        val signature = signChallenge("nonce-1".toByteArray(), p256KeyPair().private)

        assertFalse(verifyChallenge("nonce-1".toByteArray(), signature, null))
        assertFalse(verifyChallenge("nonce-1".toByteArray(), signature, ""))
        assertFalse(verifyChallenge("nonce-1".toByteArray(), signature, "not-base64-key!!"))
    }
}
