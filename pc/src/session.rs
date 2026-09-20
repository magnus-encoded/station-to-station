//! The LAN reconcile, this face's half.
//!
//! One shape only: **this machine is always the server**. It advertises
//! `_stationtostation._tcp` and waits; the phone's `ContactExchange` discovers it and
//! dials. That is not a simplification of the protocol, it is the whole of one side of
//! it — the server role in `ContactSession.kt` is fully specified, and a receiver has no
//! reason to also dial. Nothing here discovers.
//!
//! Everything after the handshake is symmetric except *order*, and the order is the
//! protocol: the server moves first at every step, so neither end ever blocks on a read
//! the other end is also waiting for.

use crate::identity::{verify_challenge, Contact, Identity};
use rand_core::{OsRng, RngCore as _};
use rustls::pki_types::{CertificateDer, PrivateKeyDer};
use rustls::server::danger::{ClientCertVerified, ClientCertVerifier};
use rustls::{DigitallySignedStruct, DistinguishedName, ServerConfig, SignatureScheme};
use serde::Deserialize;
use sha2::{Digest as _, Sha256};
use std::collections::{HashMap, HashSet};
use std::fs::File;
use std::io::{self, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

/// `HandoverWire.kt`'s cap, to the byte: bigger than any real manifest, small enough to
/// refuse a hostile length outright.
const MAX_FRAME_BYTES: i32 = 8 * 1024 * 1024;
/// `ContactSession.kt`'s `MAX_ITEM_BYTES`. Generous against a long video, finite against
/// a peer that declares a body no disk can hold.
const MAX_ITEM_BYTES: u64 = 4 << 30;
/// A stalled peer (open TCP, no bytes) must not tie up the loop forever — the same 15s
/// the phone's `SESSION_TIMEOUT_MS` allows.
const SESSION_TIMEOUT: Duration = Duration::from_secs(15);

const SERVICE_TYPE: &str = "_stationtostation._tcp.local.";

// --- The manifest, as much of it as a receiver needs ---------------------------------

/// Only the fields this face reads. Every unknown key is ignored, which is the interop
/// half (#267) and the reason a newer phone does not break an older PC: serde drops
/// unknown fields by default, matching `ignoreUnknownKeys` on the Kotlin decoder.
#[derive(Debug, Default, Deserialize)]
pub struct Manifest {
    #[serde(default)]
    pub media: Vec<OfferedMedia>,
}

#[derive(Debug, Default, Clone, Deserialize)]
pub struct OfferedMedia {
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub kind: String,
    #[serde(default)]
    pub bytes: i64,
    /// A **Note**'s text rides the manifest itself — it has no bytes to fetch.
    #[serde(default)]
    pub text: String,
}

#[derive(Deserialize)]
struct ItemHeader {
    #[serde(default)]
    id: String,
    #[serde(default)]
    bytes: i64,
}

/// Whether a media id from a peer is safe to use as a **filename**. An id arriving over
/// the wire is whatever the far end chose to send, and it reaches `File::create` as a
/// path component. An allow-list, character for character with `isSafeMediaId` on both
/// other platforms — the interesting characters are the ones nobody thought of.
pub fn is_safe_media_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 64
        && id
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

// --- Framing --------------------------------------------------------------------------

pub fn write_frame(writer: &mut impl Write, bytes: &[u8]) -> io::Result<()> {
    let length = u32::try_from(bytes.len()).map_err(io::Error::other)?;
    writer.write_all(&length.to_be_bytes())?;
    writer.write_all(bytes)?;
    writer.flush()
}

/// `None` on a clean close *between* frames. A stream that dies mid-frame is an error,
/// not "no more items" — the caller has to be able to tell those apart.
pub fn read_frame(reader: &mut impl Read) -> io::Result<Option<Vec<u8>>> {
    let mut header = [0_u8; 4];
    match reader.read_exact(&mut header) {
        Ok(()) => {}
        Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e),
    }
    // Signed, because `DataOutputStream.writeInt` is: a negative length is a thing the
    // wire can carry, and refusing it here is what stops it becoming a huge allocation.
    let declared = i32::from_be_bytes(header);
    if !(0..=MAX_FRAME_BYTES).contains(&declared) {
        return Err(io::Error::other(format!(
            "frame of {declared} bytes refused"
        )));
    }
    let length = usize::try_from(declared).map_err(io::Error::other)?;
    let mut buf = vec![0_u8; length];
    reader.read_exact(&mut buf)?;
    Ok(Some(buf))
}

fn read_frame_required(reader: &mut impl Read) -> io::Result<Vec<u8>> {
    read_frame(reader)?.ok_or_else(|| io::Error::other("connection closed mid-session"))
}

const COPY_BUFFER: usize = 64 * 1024;
/// The same figure as a `u64`, declared rather than cast — `as` is denied, and a cast
/// here would be the one place in this file where a width conversion is unchecked.
const COPY_BUFFER_U64: u64 = 64 * 1024;

/// Copies exactly `length` bytes — no more, no less — so a short body is a hard error
/// rather than a silently truncated item.
fn copy_exactly(reader: &mut impl Read, writer: &mut impl Write, length: u64) -> io::Result<()> {
    let mut buf = vec![0_u8; COPY_BUFFER];
    let mut remaining = length;
    while remaining > 0 {
        let want = usize::try_from(remaining.min(COPY_BUFFER_U64)).unwrap_or(COPY_BUFFER);
        let chunk = buf
            .get_mut(..want)
            .ok_or_else(|| io::Error::other("copy buffer too small"))?;
        let read = reader.read(chunk)?;
        if read == 0 {
            return Err(io::Error::other(format!(
                "connection closed after {} of {length} bytes",
                length.saturating_sub(remaining)
            )));
        }
        let written = buf
            .get(..read)
            .ok_or_else(|| io::Error::other("short read overran the buffer"))?;
        writer.write_all(written)?;
        remaining = remaining.saturating_sub(u64::try_from(read).unwrap_or(remaining));
    }
    Ok(())
}

fn cert_fingerprint(der: &[u8]) -> Vec<u8> {
    Sha256::digest(der).to_vec()
}

// --- The session ----------------------------------------------------------------------

pub struct Landed {
    pub contact: String,
    pub files: Vec<PathBuf>,
    pub notes: Vec<String>,
}

/// One visit, start to finish, over an already-TLS-connected stream. `None` for a peer
/// that is not a known Contact — nothing is exchanged with a stranger, not even a
/// manifest.
pub fn run_session<S: Read + Write>(
    stream: &mut S,
    peer_cert_der: &[u8],
    identity: &Identity,
    contacts: &[Contact],
) -> io::Result<Option<Landed>> {
    let Some(matched) = mutual_auth(stream, peer_cert_der, identity, contacts)? else {
        return Ok(None);
    };

    // Manifests, server first. Mine is empty and that is honest: this face holds no
    // timeline, so it offers nothing. `{}` decodes to a manifest at every default on the
    // far end, because every field there carries one.
    write_frame(stream, b"{}")?;
    let theirs: Manifest = serde_json::from_slice(&read_frame_required(stream)?)
        .map_err(|e| io::Error::other(format!("unreadable manifest: {e}")))?;

    // A **Note** arrives complete with the manifest — text, no bytes — so it is taken
    // here, before a single photo moves.
    let notes: Vec<String> = theirs
        .media
        .iter()
        .filter(|m| m.kind == KIND_NOTE && !m.text.is_empty())
        .map(|m| m.text.clone())
        .collect();

    let media_dir = identity.dir.join("media");
    let request = what_to_ask_for(&theirs, &media_dir);

    write_frame(stream, serde_json::to_string(&request)?.as_bytes())?;
    let _their_request: Vec<String> =
        serde_json::from_slice(&read_frame_required(stream)?).unwrap_or_default();

    // Server sends first. There is nothing to send, but the end-of-items marker is
    // unconditional on the sending side and the far end reads it either way.
    write_frame(stream, &[])?;

    let kinds: HashMap<&str, &str> = theirs
        .media
        .iter()
        .map(|m| (m.id.as_str(), m.kind.as_str()))
        .collect();
    let files = receive_items(stream, &request, &media_dir, &kinds)?;

    Ok(Some(Landed {
        contact: matched,
        files,
        notes,
    }))
}

/// The challenge-response, both directions, in the fixed order the far end also runs:
/// the server round first (I verify, they prove), then the client round. Returns the
/// matched Contact's name, or `None` for a peer that is not one.
fn mutual_auth<S: Read + Write>(
    stream: &mut S,
    peer_cert_der: &[u8],
    identity: &Identity,
    contacts: &[Contact],
) -> io::Result<Option<String>> {
    // The nonce is signed together with the fingerprint of the certificate they
    // presented, which is what binds the proof to this exact TLS session — a signature
    // captured off another connection carries the wrong fingerprint and fails.
    let mut nonce = [0_u8; 32];
    OsRng.fill_bytes(&mut nonce);
    write_frame(stream, &nonce)?;
    let signature = read_frame_required(stream)?;
    let mut expected = cert_fingerprint(peer_cert_der);
    expected.extend_from_slice(&nonce);

    let matched = contacts
        .iter()
        .find(|c| verify_challenge(&expected, &signature, &c.public_key));
    // Dropped without a reason surfaced back: an unrecognised peer learns nothing about
    // why. The caller closes the socket, which is what stops their own pending verify
    // round from blocking on a read that will never be answered.
    let Some(matched) = matched else {
        return Ok(None);
    };

    let their_nonce = read_frame_required(stream)?;
    let mut to_sign = cert_fingerprint(&identity.cert_der);
    to_sign.extend_from_slice(&their_nonce);
    write_frame(stream, &identity.sign(&to_sign))?;
    Ok(Some(matched.name.clone()))
}

const KIND_NOTE: &str = "note";
const KIND_VIDEO: &str = "video";
const KIND_PHOTO: &str = "photo";

/// Everything with bytes that is not already on disk. That last clause is the whole of
/// this face's "do I already have this": there is no gallery to hash against and no
/// timeline to diff, so the file either exists or it does not.
fn what_to_ask_for(offer: &Manifest, media_dir: &Path) -> Vec<String> {
    offer
        .media
        .iter()
        .filter(|m| {
            is_safe_media_id(&m.id)
                && m.kind != KIND_NOTE
                && m.bytes > 0
                && !media_dir.join(file_name(&m.id, &m.kind)).exists()
        })
        .map(|m| m.id.clone())
        .collect()
}

/// `PhotoRepository.receivedMediaFile`'s convention, so bytes landed here are named the
/// same as the same bytes landed on a phone.
fn file_name(id: &str, kind: &str) -> String {
    let ext = if kind == KIND_VIDEO { "mp4" } else { "jpg" };
    format!("{id}.{ext}")
}

/// Always drains to the end-of-items marker, even when nothing was asked for. A header
/// for anything not in `expected` is drained and dropped: a sender is free to put
/// whatever it likes on the wire, and "you offered it and I declined" must not become
/// "you sent it anyway and I stored it".
fn receive_items<S: Read + Write>(
    stream: &mut S,
    expected: &[String],
    dir: &Path,
    kinds: &HashMap<&str, &str>,
) -> io::Result<Vec<PathBuf>> {
    std::fs::create_dir_all(dir)?;
    let wanted: HashSet<&str> = expected.iter().map(String::as_str).collect();
    let mut landed = Vec::new();
    loop {
        let frame = read_frame(stream)?
            .ok_or_else(|| io::Error::other("closed before the end-of-items marker"))?;
        if frame.is_empty() {
            break;
        }
        let header: ItemHeader = serde_json::from_slice(&frame)
            .map_err(|e| io::Error::other(format!("bad item header: {e}")))?;
        // A length outside these bounds is not a bad item, it is a stream that cannot be
        // walked: a negative one leaves the body sitting where the next header should be,
        // desyncing every frame after it.
        let length = u64::try_from(header.bytes)
            .ok()
            .filter(|n| *n <= MAX_ITEM_BYTES)
            .ok_or_else(|| io::Error::other(format!("item of {} bytes refused", header.bytes)))?;

        if !is_safe_media_id(&header.id) || !wanted.contains(header.id.as_str()) {
            // The bytes are coming whether or not they are wanted, so they are drained
            // rather than abandoned mid-item.
            copy_exactly(stream, &mut io::sink(), length)?;
            continue;
        }
        let kind = kinds.get(header.id.as_str()).copied().unwrap_or(KIND_PHOTO);
        let path = dir.join(file_name(&header.id, kind));
        let mut out = File::create(&path)?;
        copy_exactly(stream, &mut out, length)?;
        landed.push(path);
    }
    Ok(landed)
}

// --- TLS ------------------------------------------------------------------------------

/// Accepts any client certificate. There is nothing to pin ahead of the handshake —
/// identity is established afterwards, over the signature, not during it. Deliberately as
/// permissive as `AcceptAnyTrustManager` on the Kotlin side, and for the same reason:
/// **trust never comes from the certificate.** Anything that later pins one, remembers one
/// between sessions, or ties one to the identity key ends interop silently, because the
/// three faces do not present the same kind of certificate and never will.
#[derive(Debug)]
struct AcceptAnyClientCert {
    schemes: Vec<SignatureScheme>,
    provider: Arc<rustls::crypto::CryptoProvider>,
}

impl ClientCertVerifier for AcceptAnyClientCert {
    fn root_hint_subjects(&self) -> &[DistinguishedName] {
        &[]
    }

    fn verify_client_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _now: rustls::pki_types::UnixTime,
    ) -> Result<ClientCertVerified, rustls::Error> {
        Ok(ClientCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.schemes.clone()
    }

    fn offer_client_auth(&self) -> bool {
        true
    }

    /// Mandatory, not optional: `proveContactIdentity` signs the fingerprint of the
    /// certificate the peer presented, and that fingerprint has nothing to bind to unless
    /// both sides actually put a certificate on the wire.
    fn client_auth_mandatory(&self) -> bool {
        true
    }
}

fn server_config(identity: &Identity) -> Result<Arc<ServerConfig>, rustls::Error> {
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let verifier = Arc::new(AcceptAnyClientCert {
        schemes: provider
            .signature_verification_algorithms
            .supported_schemes(),
        provider: Arc::clone(&provider),
    });
    let cert = CertificateDer::from(identity.cert_der.clone());
    let key = PrivateKeyDer::try_from(identity.key_pkcs8_der.clone())
        .map_err(|e| rustls::Error::General(e.to_string()))?;
    let config = ServerConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()?
        .with_client_cert_verifier(verifier)
        .with_single_cert(vec![cert], key)?;
    Ok(Arc::new(config))
}

// --- Listening ------------------------------------------------------------------------

/// Advertise on the LAN and receive, one session at a time, until interrupted. Blocking:
/// it is the one thing the CLI is doing while it runs.
pub fn listen(identity: &Identity, on_event: &dyn Fn(String)) -> io::Result<()> {
    let listener = TcpListener::bind(("0.0.0.0", 0))?;
    let port = listener.local_addr()?.port();
    let config = server_config(identity).map_err(io::Error::other)?;

    let daemon = mdns_sd::ServiceDaemon::new().map_err(io::Error::other)?;
    // The instance name is arbitrary for identity — presence is not identity (#257) — but
    // it is NOT arbitrary for discovery. `ContactPeers.onServiceFound` drops any instance
    // whose name equals the one this device registered, to skip its own advertisement
    // echoing back. Publish the constant "station-to-station" and a phone that also kept
    // that name discards this machine as itself, silently. So: unique per host.
    let instance = format!("station-to-station-{}", hostname());
    let addresses = lan_address();
    // Only the address that actually reaches the phone. `enable_addr_auto` publishes every
    // interface — on this Pi that is four, including a link-local and a WireGuard tunnel —
    // and `NsdManager` resolves to a single `info.host`, so a wrong pick is a dial into
    // nothing that `connectTo` swallows without a log.
    let service = mdns_sd::ServiceInfo::new(
        SERVICE_TYPE,
        &instance,
        &format!("{}.local.", hostname()),
        addresses.as_slice(),
        port,
        None,
    )
    .map_err(io::Error::other)?;
    daemon.register(service).map_err(io::Error::other)?;
    on_event(format!(
        "listening on {port}, advertised as {instance}.{SERVICE_TYPE} at {addresses:?}"
    ));
    on_event("open the app on the phone and let it find you. ctrl-c to stop.".to_owned());

    for accepted in listener.incoming() {
        match accepted {
            Ok(stream) => serve(stream, &config, identity, on_event),
            Err(e) => on_event(format!("accept failed: {e}")),
        }
    }
    Ok(())
}

fn serve(
    mut stream: TcpStream,
    config: &Arc<ServerConfig>,
    identity: &Identity,
    on_event: &dyn Fn(String),
) {
    let _ = stream.set_read_timeout(Some(SESSION_TIMEOUT));
    let _ = stream.set_write_timeout(Some(SESSION_TIMEOUT));

    let mut conn = match rustls::ServerConnection::new(Arc::clone(config)) {
        Ok(c) => c,
        Err(e) => return on_event(format!("tls setup failed: {e}")),
    };
    // Drive the handshake to completion before anything reads a certificate off it.
    if let Err(e) = conn.complete_io(&mut stream) {
        return on_event(format!("handshake failed: {e}"));
    }
    let Some(peer_cert) = conn
        .peer_certificates()
        .and_then(<[CertificateDer<'_>]>::first)
        .map(|c| c.as_ref().to_vec())
    else {
        return on_event("peer presented no certificate".to_owned());
    };

    let contacts = identity.contacts();
    let mut tls = rustls::Stream::new(&mut conn, &mut stream);
    match run_session(&mut tls, &peer_cert, identity, &contacts) {
        Ok(Some(landed)) => report(&landed, on_event),
        Ok(None) => on_event("a peer answered that is not a Contact — dropped".to_owned()),
        Err(e) => on_event(format!("session ended: {e}")),
    }
}

fn report(landed: &Landed, on_event: &dyn Fn(String)) {
    on_event(format!(
        "{}: {} file(s), {} note(s)",
        landed.contact,
        landed.files.len(),
        landed.notes.len()
    ));
    for file in &landed.files {
        on_event(format!("  {}", file.display()));
    }
    for note in &landed.notes {
        on_event(format!("  note: {note}"));
    }
}

fn hostname() -> String {
    std::fs::read_to_string("/etc/hostname")
        .map(|s| s.trim().to_owned())
        .ok()
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "station-to-station-pc".to_owned())
}

/// The local address on the route out — the one a peer on the same LAN can reach. No
/// packet is sent: connecting a UDP socket only fixes the kernel's source-address choice,
/// which is exactly the question being asked.
fn lan_address() -> Vec<std::net::IpAddr> {
    std::net::UdpSocket::bind("0.0.0.0:0")
        .and_then(|probe| {
            // Any off-link address: no packet is sent, so it need not exist or be reachable.
            probe.connect("192.0.2.1:9")?;
            probe.local_addr()
        })
        .map(|addr| vec![addr.ip()])
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frames_round_trip_the_way_datastream_writes_them() {
        let mut buf = Vec::new();
        write_frame(&mut buf, b"hello").unwrap();
        write_frame(&mut buf, &[]).unwrap();
        // Big-endian length prefix — `DataOutputStream.writeInt`.
        assert_eq!(&buf[..4], &[0, 0, 0, 5]);
        let mut reader = &buf[..];
        assert_eq!(read_frame(&mut reader).unwrap().unwrap(), b"hello");
        assert_eq!(read_frame(&mut reader).unwrap().unwrap(), Vec::<u8>::new());
        // A clean close between frames, which is not an error.
        assert!(read_frame(&mut reader).unwrap().is_none());
    }

    #[test]
    fn a_hostile_length_is_refused_rather_than_allocated() {
        let negative = (-1_i32).to_be_bytes();
        assert!(read_frame(&mut &negative[..]).is_err());
        let too_big = (MAX_FRAME_BYTES + 1).to_be_bytes();
        assert!(read_frame(&mut &too_big[..]).is_err());
    }

    #[test]
    fn an_id_that_could_escape_the_media_directory_is_not_safe() {
        assert!(is_safe_media_id("a1b2-c3_d4"));
        assert!(!is_safe_media_id("../../etc/passwd"));
        assert!(!is_safe_media_id(""));
        assert!(!is_safe_media_id(&"a".repeat(65)));
    }

    #[test]
    fn an_unrequested_item_is_drained_and_not_written() {
        let dir = std::env::temp_dir().join(format!("sts-items-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let mut wire = Vec::new();
        write_frame(&mut wire, br#"{"id":"unwanted","bytes":3}"#).unwrap();
        wire.extend_from_slice(b"xxx");
        write_frame(&mut wire, br#"{"id":"wanted","bytes":4}"#).unwrap();
        wire.extend_from_slice(b"abcd");
        write_frame(&mut wire, &[]).unwrap();

        let mut cursor = io::Cursor::new(wire);
        let kinds = HashMap::new();
        let landed = receive_items(&mut cursor, &["wanted".to_owned()], &dir, &kinds).unwrap();
        assert_eq!(landed.len(), 1);
        assert_eq!(std::fs::read(&landed[0]).unwrap(), b"abcd");
        // Offered, declined, sent anyway — and still not stored.
        assert!(!dir.join("unwanted.jpg").exists());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
