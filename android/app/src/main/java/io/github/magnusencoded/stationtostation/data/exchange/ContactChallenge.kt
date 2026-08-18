package io.github.magnusencoded.stationtostation.data.exchange

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The proof a LAN reconcile session (#257) substitutes for a fresh QR handoff: sign a
 * nonce with the identity already pinned to a Friend at Exchange time, verify it
 * against that persisted key. Pure — no AndroidKeyStore, no socket — so this half is
 * checkable off-device; [contactIdentityPublicKeyBase64] and [signWithContactIdentity]
 * are the on-device counterpart this composes with.
 */
private const val ALGORITHM = "SHA256withECDSA"

fun signChallenge(nonce: ByteArray, privateKey: PrivateKey): ByteArray =
    Signature.getInstance(ALGORITHM).apply {
        initSign(privateKey)
        update(nonce)
    }.sign()

/**
 * True only if [signature] proves possession of the private key behind
 * [publicKeyBase64] over exactly [nonce]. A malformed or missing key verifies false
 * rather than throwing — a Friend added before this field existed, or a beacon that
 * matches nobody, is a candidate this simply drops, not an error.
 */
fun verifyChallenge(nonce: ByteArray, signature: ByteArray, publicKeyBase64: String?): Boolean {
    val publicKey = decodePublicKey(publicKeyBase64) ?: return false
    return runCatching {
        Signature.getInstance(ALGORITHM).apply {
            initVerify(publicKey)
            update(nonce)
        }.verify(signature)
    }.getOrDefault(false)
}

private fun decodePublicKey(base64: String?): PublicKey? {
    if (base64.isNullOrBlank()) return null
    return runCatching {
        val bytes = Base64.getDecoder().decode(base64)
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }.getOrNull()
}
