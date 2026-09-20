# The PC face

A **Contact** that stands still. No **Line**, no **Room**, no **Flyover** — this face gets
a key into a phone the way another phone does, then answers on the LAN and takes whatever
that phone offers. Two commands.

Built for the Raspberry Pi (`ssh pinet`, aarch64, Debian 12), but nothing here is Pi-shaped
beyond that: it is a plain Rust binary with no system libraries except D-Bus, which is what
BlueZ speaks.

## Build

Natively, on the Pi. Nothing is cross-compiled — a cross toolchain is a second thing to
keep working for a build that takes minutes on the target anyway.

```sh
ssh pinet
sudo apt install -y libdbus-1-dev pkg-config build-essential
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
source ~/.cargo/env

cd ~/station-to-station/pc
cargo build --release -j2      # -j2: 856 MB of RAM, and rustls links hard
cargo clippy --all-targets -j2
cargo test
./target/release/sts
```

A cold build is about seven minutes; after that it is under a minute.

## Lints

`Cargo.toml` carries [Tris Oaten's strict set](https://www.namtao.com/rust/#strict-lints):
`pedantic` and `nursery` at `deny`, plus the panic-denial group — `unwrap_used`,
`expect_used`, `indexing_slicing`, `arithmetic_side_effects`, `string_slice`, `panic`,
`exit`, `as_conversions` and the rest. `clippy.toml` lets tests keep `.unwrap()`, where a
panic is a failed assertion and nothing more.

**The panic half is the load-bearing one here**, and not as a matter of taste: this binary
sits in a corner receiving other people's photographs with nobody watching it. A panic on a
malformed frame is an outage that nobody is present to notice, so a wire parser that cannot
panic is worth the `try_from` noise it costs.

`nursery` is by definition the unstable group, and it is pinned at `deny` against whatever
toolchain the Pi happens to have. A `rustup update` can therefore fail this build on a lint
that did not exist when it was written. That is inherent to the recommendation rather than a
mistake in it — pin `rust-toolchain.toml` if that trade stops being worth it.

## The two commands

```
> pair
```

Scans for a phone advertising `7b7e6f2a-…-7711` — which it only does while its **Exchange**
screen is open — reads its **Card**, and writes ours to the inbound characteristic on the
same connection. One visit, both directions (#87). After it, the phone holds this machine's
public key as a **Contact**, and this machine holds the phone's.

**This has to happen in the room.** That is ADR-0016, not a limitation: presence is the
authentication, so the only door that can make a **Contact** is one that requires two
parties to be standing together. A door that worked over the internet would be the bug, and
is why there is no `pair --key <paste>`.

```
> listen
```

Advertises `_stationtostation._tcp` over mDNS and waits. The phone's `ContactExchange`
discovers it, dials, and the session runs: mutual TLS, challenge-response against the key
from `pair`, manifests, then the bytes. Media lands in `~/.station-to-station/media/` named
the way `PhotoRepository.receivedMediaFile` names it; **Notes** print, since they arrive
complete with the manifest and there is nothing to fetch for them.

## What it deliberately is not

- **It does not dial.** It advertises and it is dialed. The server role in
  `ContactSession.kt` is a whole side of the protocol on its own, and a receiver has no
  reason to also discover.
- **It offers nothing.** Its manifest is `{}`. There is no timeline here to share, and
  saying so honestly is one line rather than a whole half of a model.
- **It does not decide where anything belongs.** A phone lands media on a **Gig** matched
  by `setlistId`; this face has no gigs, so files land by id and the manifest is the record
  of what they were.

Those three are what make this small. Any of them could change; none of them is a stub.

## State

`~/.station-to-station/` — override with `STS_HOME`.

| | |
| --- | --- |
| `identity.p8` | the ECDSA P-256 keypair, PKCS#8. **This is the identity.** Lose it and every phone that paired stops recognising this machine. |
| `cert.der` | the self-signed leaf it presents at the TLS layer. Regenerable — trust never comes from the certificate. |
| `contacts.json` | who may be received from |
| `media/` | what arrived |

## Where the protocol actually lives

Nothing here is authored. Every constant and every ordering decision is read back off the
Android implementation, and the comments name the file each came from:

| here | there |
| --- | --- |
| `session.rs` framing, item headers, the 8 MiB and 4 GiB caps | `data/exchange/HandoverWire.kt` |
| `session.rs` session order, the drain rule, `is_safe_media_id` | `data/exchange/ContactSession.kt`, `data/ContactReconcile.kt` |
| `session.rs` challenge, accept-any certificates | `data/exchange/ContactWire.kt` |
| `identity.rs` P-256, base64 SPKI, `SHA256withECDSA` | `data/exchange/ContactIdentity.kt`, `ContactChallenge.kt` |
| `pair.rs` the three UUIDs, the card format | `ble/BleProbe.kt`, `ble/CardWire.kt` |

If one of those changes, this face breaks silently — it has no CI against the phone. That
is the standing cost of a third face, and `cargo test` covers only the half that can be
checked without one.
