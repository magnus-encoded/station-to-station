import CryptoKit
import XCTest
@testable import StationToStation

/// The proof that turns "something answered on the network" into "a known Contact
/// answered" (#265). The twin of Android's `ContactChallengeTest`.
///
/// The signing half runs on a software key here rather than the Secure Enclave, which is
/// the point of splitting the file that way: the enclave is real hardware and does not
/// exist in a simulator, and the part where a mistake would be *silent* — a signature
/// that verifies when it should not — is exactly the part that needs no hardware.
final class ContactChallengeTests: XCTestCase {

    private let nonce = Data("a nonce nobody else has seen".utf8)

    private func identity() -> (key: P256.Signing.PrivateKey, publicKeyBase64: String) {
        let key = P256.Signing.PrivateKey()
        return (key, key.publicKey.derRepresentation.base64EncodedString())
    }

    func testASignatureVerifiesAgainstTheKeyThatMadeIt() {
        let me = identity()

        guard let signature = signChallenge(nonce, privateKey: me.key) else {
            return XCTFail("signing failed")
        }

        XCTAssertTrue(verifyChallenge(nonce, signature: signature,
                                      publicKeyBase64: me.publicKeyBase64))
    }

    /// A stranger on the same WiFi is not a Contact, and is not an error either — this is
    /// the whole of "a candidate whose signature doesn't check out is simply not
    /// surfaced".
    func testASignatureFromAnUnknownKeyDoesNotVerify() {
        let stranger = identity()
        let contact = identity()

        guard let signature = signChallenge(nonce, privateKey: stranger.key) else {
            return XCTFail("signing failed")
        }

        XCTAssertFalse(verifyChallenge(nonce, signature: signature,
                                       publicKeyBase64: contact.publicKeyBase64))
    }

    /// Freshness. A signature captured off one session and replayed into another answers
    /// a nonce nobody asked this time.
    func testAnAnswerToAnEarlierNonceDoesNotVerify() {
        let me = identity()
        let earlier = Data("last time's nonce".utf8)

        guard let signature = signChallenge(earlier, privateKey: me.key) else {
            return XCTFail("signing failed")
        }

        XCTAssertFalse(verifyChallenge(nonce, signature: signature,
                                       publicKeyBase64: me.publicKeyBase64))
    }

    /// A **Friend** added from a deep link, or added before the key field existed, has no
    /// key. That is a normal record and must read as "does not match", never as a crash
    /// and never as a pass.
    func testAMissingOrMalformedKeyVerifiesFalseRatherThanThrowing() {
        let me = identity()
        guard let signature = signChallenge(nonce, privateKey: me.key) else {
            return XCTFail("signing failed")
        }

        XCTAssertFalse(verifyChallenge(nonce, signature: signature, publicKeyBase64: nil))
        XCTAssertFalse(verifyChallenge(nonce, signature: signature, publicKeyBase64: ""))
        XCTAssertFalse(verifyChallenge(nonce, signature: signature, publicKeyBase64: "   "))
        XCTAssertFalse(verifyChallenge(nonce, signature: signature, publicKeyBase64: "not base64 at all!"))
        // Well-formed base64 of something that is not a key at all.
        XCTAssertFalse(verifyChallenge(nonce, signature: signature,
                                       publicKeyBase64: Data("hello".utf8).base64EncodedString()))
    }

    func testGarbageInPlaceOfASignatureVerifiesFalse() {
        let me = identity()

        XCTAssertFalse(verifyChallenge(nonce, signature: Data([0x01, 0x02, 0x03]),
                                       publicKeyBase64: me.publicKeyBase64))
        XCTAssertFalse(verifyChallenge(nonce, signature: Data(),
                                       publicKeyBase64: me.publicKeyBase64))
    }

    /// What the far end actually signs is the fingerprint of the certificate *this*
    /// session presented, with the nonce after it — which is what stops a valid signature
    /// from one connection being replayed onto another.
    func testTheBindingIsToBothTheCertificateAndTheNonce() {
        let me = identity()
        let oneSession = certFingerprint(Data("cert-A".utf8))
        let another = certFingerprint(Data("cert-B".utf8))

        guard let signature = signChallenge(oneSession + nonce, privateKey: me.key) else {
            return XCTFail("signing failed")
        }

        XCTAssertTrue(verifyChallenge(oneSession + nonce, signature: signature,
                                      publicKeyBase64: me.publicKeyBase64))
        XCTAssertFalse(verifyChallenge(another + nonce, signature: signature,
                                       publicKeyBase64: me.publicKeyBase64))
    }

    /// A key straight off a `ProbeCard` is base64 X.509 SubjectPublicKeyInfo — the same
    /// encoding Android's `publicKey.encoded` produces, which is the one detail a later
    /// cross-platform pass cannot renegotiate.
    func testAContactKeyDecodesFromItsBase64SubjectPublicKeyInfo() {
        let me = identity()

        let decoded = decodeContactPublicKey(me.publicKeyBase64)

        XCTAssertEqual(me.key.publicKey.derRepresentation, decoded?.derRepresentation)
        XCTAssertNil(decodeContactPublicKey(nil))
    }

    func testEveryNonceIsFreshAndFullLength() {
        guard let first = contactNonce(), let second = contactNonce() else {
            return XCTFail("no nonce")
        }

        XCTAssertEqual(32, first.count)
        XCTAssertNotEqual(first, second)
    }
}
