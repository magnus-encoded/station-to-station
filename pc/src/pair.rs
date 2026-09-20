//! Becoming a **Contact**, over the one door the phone already opens.
//!
//! No new transport and no change on the phone: `BleCardPeripheral` (#30/#87) already
//! advertises a GATT service with a readable card and a writable inbound characteristic,
//! and it does so **only while the Exchange screen is open**. This is the BLE central
//! half of that — read their **Card**, write ours — which is the same thing another phone
//! does when the two are held together.
//!
//! That the write is unverified is not an oversight here any more than it is there:
//! **ADR-0016**, presence is the authentication. This machine has to be in the room, with
//! someone holding the phone on that screen. A door that did not need that would be the
//! bug, and is why there is no `pair --key <paste>`.

use crate::identity::{Contact, Identity};
use btleplug::api::{Central as _, Manager as _, Peripheral as _, ScanFilter, WriteType};
use btleplug::platform::{Manager, Peripheral};
use std::fmt::Write as _;
use std::time::Duration;
use uuid::{uuid, Uuid};

/// The three UUIDs from `BleProbe.kt`. A typo here is a silent no-discovery that costs a
/// device session to find, so they are pinned by the test at the bottom.
const SERVICE_UUID: Uuid = uuid!("7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7711");
const CARD_READ_UUID: Uuid = uuid!("7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7712");
const CARD_WRITE_UUID: Uuid = uuid!("7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7713");

const SCAN_TIME: Duration = Duration::from_secs(8);

/// A phone found advertising the service, before anything has been read off it.
pub struct Found {
    peripheral: Peripheral,
    pub address: String,
}

pub async fn scan() -> Result<Vec<Found>, String> {
    let manager = Manager::new().await.map_err(|e| e.to_string())?;
    let adapter = manager
        .adapters()
        .await
        .map_err(|e| e.to_string())?
        .into_iter()
        .next()
        .ok_or_else(|| "no bluetooth adapter (try `bluetoothctl power on`)".to_owned())?;

    adapter
        .start_scan(ScanFilter {
            services: vec![SERVICE_UUID],
        })
        .await
        .map_err(|e| e.to_string())?;
    tokio::time::sleep(SCAN_TIME).await;
    let peripherals = adapter.peripherals().await.map_err(|e| e.to_string())?;
    let _ = adapter.stop_scan().await;

    let mut found = Vec::new();
    for peripheral in peripherals {
        let advertises = peripheral
            .properties()
            .await
            .ok()
            .flatten()
            .is_some_and(|props| props.services.contains(&SERVICE_UUID));
        if advertises {
            let address = peripheral.address().to_string();
            found.push(Found {
                peripheral,
                address,
            });
        }
    }
    Ok(found)
}

/// One connection, both cards. A read cannot carry our card to them, so we write it to
/// the inbound characteristic on the same connection — one visit exchanges both, exactly
/// as #87 describes.
pub async fn exchange(found: &Found, identity: &Identity) -> Result<Contact, String> {
    found.peripheral.connect().await.map_err(|e| e.to_string())?;
    let result = exchange_connected(&found.peripheral, identity).await;
    let _ = found.peripheral.disconnect().await;
    result
}

async fn exchange_connected(peer: &Peripheral, identity: &Identity) -> Result<Contact, String> {
    peer.discover_services().await.map_err(|e| e.to_string())?;
    let characteristics = peer.characteristics();
    let read_card = characteristics
        .iter()
        .find(|c| c.uuid == CARD_READ_UUID)
        .ok_or_else(|| "no card characteristic — is the Exchange screen open?".to_owned())?;
    let write_card = characteristics
        .iter()
        .find(|c| c.uuid == CARD_WRITE_UUID)
        .ok_or_else(|| "no inbound characteristic on this peer".to_owned())?;

    // Theirs first: if their card is unreadable there is no Contact to make, and writing
    // ours to a peer we cannot name would leave the pairing half-done in one direction.
    let payload = peer.read(read_card).await.map_err(|e| e.to_string())?;
    let text = String::from_utf8_lossy(&payload);
    let theirs = parse_card(&text).ok_or_else(|| format!("unparseable card: {text:?}"))?;

    peer.write(write_card, &encode_card(identity), WriteType::WithResponse)
        .await
        .map_err(|e| e.to_string())?;

    Ok(theirs)
}

/// The card as it goes over the wire — the same deep link the QR fallback and Nearby
/// carry, plus `k`, the base64 X.509 public key that is the identity. Built by hand
/// rather than with a URL crate so the size budget stays checkable.
pub fn encode_card(identity: &Identity) -> Vec<u8> {
    // `u` is not optional in practice: `friendFromCard` (ExchangeSession.kt) drops any
    // card without a plausible setlist.fm username, silently and before anything is
    // logged. This face has no setlist.fm account, so it presents its hostname — a
    // timeline that resolves to nothing, which is the truth about this machine.
    format!(
        "station-to-station://friend?name={}&u={}&k={}",
        esc(&identity.name),
        esc(&identity.name),
        esc(identity.public_key_base64())
    )
    .into_bytes()
}

/// Percent-encoding strict enough for `URLDecoder.decode` on the far end. Base64 carries
/// `+`, `/` and `=`, and `+` in particular decodes back as a *space* — escaping it is
/// what stops a key arriving corrupted rather than arriving visibly wrong.
fn esc(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for byte in text.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' => {
                out.push(char::from(byte));
            }
            // `write!` into a `String` cannot fail, and it is one allocation fewer than
            // pushing a `format!`.
            other => {
                let _ = write!(out, "%{other:02X}");
            }
        }
    }
    out
}

/// The inverse, over an iterator rather than an index: a `%` at the end of the string is
/// a truncated escape, not a panic.
fn unesc(text: &str) -> String {
    let mut out: Vec<u8> = Vec::with_capacity(text.len());
    let mut bytes = text.bytes();
    while let Some(byte) = bytes.next() {
        match byte {
            b'+' => out.push(b' '),
            b'%' => match (bytes.next(), bytes.next()) {
                (Some(high), Some(low)) => {
                    let pair = [high, low];
                    let hex = String::from_utf8_lossy(&pair);
                    match u8::from_str_radix(&hex, 16) {
                        Ok(decoded) => out.push(decoded),
                        Err(_) => out.extend_from_slice(&[b'%', high, low]),
                    }
                }
                (Some(high), None) => out.extend_from_slice(&[b'%', high]),
                _ => out.push(b'%'),
            },
            plain => out.push(plain),
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

/// Twin of `parseProbeCard`. A card with no `k` is not a Contact — that is #271's rule,
/// and it is the same rule here: without a key there is nothing to verify a LAN session
/// against, so there is nothing to store.
pub fn parse_card(payload: &str) -> Option<Contact> {
    let query = payload.split_once("://friend?")?.1;
    let mut name = None;
    let mut key = None;
    let mut user = None;
    for pair in query.split('&') {
        let Some((field, raw)) = pair.split_once('=') else {
            continue;
        };
        let value = unesc(raw);
        match field {
            "name" => name = Some(value),
            "k" => key = Some(value),
            "u" => user = Some(value),
            _ => {}
        }
    }
    let public_key = key.filter(|k| !k.is_empty())?;
    let setlistfm = user.filter(|u| !u.is_empty());
    Some(Contact {
        name: name
            .filter(|n| !n.is_empty())
            .or_else(|| setlistfm.clone())
            .unwrap_or_default(),
        public_key,
        setlistfm,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_uuids_are_the_ones_bleprobe_publishes() {
        assert_eq!(
            SERVICE_UUID.to_string(),
            "7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7711"
        );
        assert_eq!(
            CARD_READ_UUID.to_string(),
            "7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7712"
        );
        assert_eq!(
            CARD_WRITE_UUID.to_string(),
            "7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7713"
        );
    }

    /// A base64 key is the case a naive encoder breaks: `+` decodes back as a space.
    #[test]
    fn a_card_survives_its_own_encoding() {
        let key = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE+a/b=";
        let encoded = format!(
            "station-to-station://friend?name={}&k={}",
            esc("Ada Lovelace"),
            esc(key)
        );
        assert!(!encoded.contains('+'));
        let parsed = parse_card(&encoded).unwrap();
        assert_eq!(parsed.name, "Ada Lovelace");
        assert_eq!(parsed.public_key, key);
    }

    #[test]
    fn a_truncated_escape_is_text_and_not_a_panic() {
        assert_eq!(unesc("abc%"), "abc%");
        assert_eq!(unesc("abc%2"), "abc%2");
        assert_eq!(unesc("abc%zz"), "abc%zz");
    }

    #[test]
    fn a_card_with_no_key_is_not_a_contact() {
        assert!(parse_card("station-to-station://friend?name=Ada&u=ada").is_none());
        assert!(parse_card("not a card at all").is_none());
    }
}
