//! The PC's durable Contact identity, and the Contacts it has met.
//!
//! Android keeps this in the `AndroidKeyStore` and iOS mints a fresh per-session cert;
//! this face has neither, so the keypair is a PKCS#8 file under the state directory. The
//! *shape* is what has to match, not the storage: one ECDSA P-256 keypair, generated
//! once, whose base64 X.509 `SubjectPublicKeyInfo` is what goes on a **Card** and what a
//! phone later checks a signature against. See `ContactIdentity.kt`.
//!
//! The same keypair backs the TLS certificate. It does not have to — trust never comes
//! from the certificate, only from a signature over its fingerprint (`ContactWire.kt`) —
//! but one key is one thing to lose.

use base64::Engine as _;
use p256::ecdsa::signature::{Signer as _, Verifier as _};
use p256::ecdsa::{Signature, SigningKey, VerifyingKey};
use p256::pkcs8::{DecodePrivateKey as _, DecodePublicKey as _, EncodePublicKey as _};
use serde::{Deserialize, Serialize};
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

const B64: base64::engine::general_purpose::GeneralPurpose = base64::engine::general_purpose::STANDARD;

pub struct Identity {
    pub name: String,
    signing_key: SigningKey,
    /// Base64 X.509 `SubjectPublicKeyInfo`. Computed once at load, because the only way it
    /// can fail is a key that could not have been loaded in the first place — and that
    /// belongs in the one function that already returns a `Result`, not at every use.
    public_key_base64: String,
    /// PKCS#8 DER, handed to rustls as the certificate's private key.
    pub key_pkcs8_der: Vec<u8>,
    /// The self-signed leaf, DER — what the session's fingerprint is taken over.
    pub cert_der: Vec<u8>,
    pub dir: PathBuf,
}

impl Identity {
    /// Loads the identity, generating it on first run. Everything lives in `dir`:
    /// `identity.p8`, `cert.der`, `contacts.json`, `media/`.
    pub fn load_or_create(dir: &Path, name: &str) -> io::Result<Self> {
        fs::create_dir_all(dir)?;
        let key_path = dir.join("identity.p8");
        let cert_path = dir.join("cert.der");

        let (key_pkcs8_der, cert_der) = if key_path.exists() && cert_path.exists() {
            (fs::read(&key_path)?, fs::read(&cert_path)?)
        } else {
            let minted = mint_cert(name).map_err(other)?;
            fs::write(&key_path, &minted.0)?;
            fs::write(&cert_path, &minted.1)?;
            minted
        };

        // This file *is* the identity: anything that can read it can be this machine to
        // every phone that ever paired with it. Narrowed on every load rather than only at
        // mint, so a key written by a build that predates this is fixed the next time it
        // opens. The certificate beside it is public by design and stays as it is.
        restrict_to_owner(&key_path)?;
        let signing_key = SigningKey::from_pkcs8_der(&key_pkcs8_der).map_err(other)?;
        let spki = VerifyingKey::from(&signing_key)
            .to_public_key_der()
            .map_err(other)?;
        fs::create_dir_all(dir.join("media"))?;
        Ok(Self {
            name: name.to_owned(),
            signing_key,
            public_key_base64: B64.encode(spki.as_bytes()),
            key_pkcs8_der,
            cert_der,
            dir: dir.to_path_buf(),
        })
    }

    /// Byte for byte what Android puts on a `ProbeCard.publicKey`, so a phone can pin it
    /// without knowing what made it.
    pub fn public_key_base64(&self) -> &str {
        &self.public_key_base64
    }

    /// `SHA256withECDSA`, DER signature — Java's `Signature` default for EC, which is
    /// what `verifyChallenge` on the far end feeds back into `Signature.verify`.
    pub fn sign(&self, message: &[u8]) -> Vec<u8> {
        let signature: Signature = self.signing_key.sign(message);
        signature.to_der().as_bytes().to_vec()
    }

    pub fn contacts_path(&self) -> PathBuf {
        self.dir.join("contacts.json")
    }

    pub fn contacts(&self) -> Vec<Contact> {
        fs::read(self.contacts_path())
            .ok()
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default()
    }

    /// Adds a Contact. Returns whether anything changed.
    ///
    /// The small half of #188's arrival rule, which is all this face needs: a key not
    /// held is added, a key already held is a no-op. Nothing here can rewrite one — two
    /// cards under one name are two Contacts, and which of them a session matched is
    /// decided by the signature, never by the name.
    pub fn remember(&self, contact: Contact) -> io::Result<bool> {
        let mut all = self.contacts();
        if all.iter().any(|held| held.public_key == contact.public_key) {
            return Ok(false);
        }
        all.push(contact);
        fs::write(self.contacts_path(), serde_json::to_vec_pretty(&all)?)?;
        Ok(true)
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Contact {
    pub name: String,
    /// Base64 X.509 `SubjectPublicKeyInfo`.
    pub public_key: String,
    #[serde(default)]
    pub setlistfm: Option<String>,
}

/// True only if `signature` proves possession of the key behind `public_key_base64` over
/// exactly `message`. A malformed key verifies false rather than raising — a Contact
/// stored before this field existed is a candidate to drop, not an error. Twin of
/// `verifyChallenge` in `ContactChallenge.kt`.
pub fn verify_challenge(message: &[u8], signature: &[u8], public_key_base64: &str) -> bool {
    let Ok(spki) = B64.decode(public_key_base64) else {
        return false;
    };
    let Ok(key) = VerifyingKey::from_public_key_der(&spki) else {
        return false;
    };
    let Ok(parsed) = Signature::from_der(signature) else {
        return false;
    };
    key.verify(message, &parsed).is_ok()
}

/// `chmod 600`, on the platforms that have one.
#[cfg(unix)]
fn restrict_to_owner(path: &Path) -> io::Result<()> {
    use std::os::unix::fs::PermissionsExt as _;
    fs::set_permissions(path, fs::Permissions::from_mode(0o600))
}

#[cfg(not(unix))]
fn restrict_to_owner(_path: &Path) -> io::Result<()> {
    // Windows inherits the user profile's ACL, which is already owner-only.
    Ok(())
}

fn mint_cert(name: &str) -> Result<(Vec<u8>, Vec<u8>), rcgen::Error> {
    let key_pair = rcgen::KeyPair::generate_for(&rcgen::PKCS_ECDSA_P256_SHA256)?;
    let mut params = rcgen::CertificateParams::new(vec![name.to_owned()])?;
    // rcgen defaults to 1975..4096, which openssl accepts and Conscrypt does not: the
    // phone drops the connection the moment it reads this certificate, and closes without
    // a TLS alert, so nothing is logged at either end. A window a real certificate could
    // have is the whole fix. Nothing checks these dates for trust — trust comes from the
    // signature over the fingerprint (ContactWire.kt) — they only have to be sane.
    params.not_before = rcgen::date_time_ymd(2024, 1, 1);
    params.not_after = rcgen::date_time_ymd(2044, 1, 1);
    let cert = params.self_signed(&key_pair)?;
    Ok((key_pair.serialize_der(), cert.der().to_vec()))
}

fn other<E: std::fmt::Display>(error: E) -> io::Error {
    io::Error::other(error.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn signs_what_the_phone_will_verify() {
        let dir = std::env::temp_dir().join(format!("sts-id-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let id = Identity::load_or_create(&dir, "test").unwrap();
        let message = b"fingerprint||nonce";
        assert!(verify_challenge(message, &id.sign(message), id.public_key_base64()));
        assert!(!verify_challenge(
            b"something else",
            &id.sign(message),
            id.public_key_base64()
        ));

        // The identity is durable, or a phone that paired yesterday cannot recognise this
        // machine today.
        let again = Identity::load_or_create(&dir, "test").unwrap();
        assert_eq!(id.public_key_base64(), again.public_key_base64());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_key_already_held_is_not_added_twice() {
        let dir = std::env::temp_dir().join(format!("sts-remember-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let id = Identity::load_or_create(&dir, "test").unwrap();
        let card = Contact {
            name: "Ada".to_owned(),
            public_key: "AAAA".to_owned(),
            setlistfm: None,
        };
        assert!(id.remember(card.clone()).unwrap());
        assert!(!id.remember(card).unwrap());
        assert_eq!(id.contacts().len(), 1);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
