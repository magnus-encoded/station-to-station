"""Manual BlueZ peer: independent connection trials, signed v2 Pass, real ATT framing.

Run on the Pi with bleak and cryptography installed, alongside the opt-in
GossipRadioDeviceTest instrumentation (manual_ble_peer=true). Use plain ATT: BlueZ
5.66's default EATT setup initiates pairing and breaks this unpaired-peer test; see
docs/gossip-v2-next-pass.md for the measured comparison. No retry is hidden: each printed trial is one discovery/connection.
Acceptance must be checked in Pixel logcat; an ATT acknowledgement alone is not
application admission. This does not test the phone's central/send path.

Use --controls with manual_ble_controls=true and the device test
indirectControlsAreRejectedWithoutPoisoningDirectDelivery: four Passes send each
control through a different signer first, then directly from its author.
"""
import asyncio
import base64
import hashlib
import sys
import time

from bleak import BleakClient, BleakScanner
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

SERVICE = "7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7721"
CHALLENGE = "7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7722"
PASS = "7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7723"


def b64(value):
    return base64.b64encode(value).decode("ascii")


async def main(trials, controls=False):
    key = ec.generate_private_key(ec.SECP256R1())
    author = b64(key.public_key().public_bytes(serialization.Encoding.DER,
                                              serialization.PublicFormat.SubjectPublicKeyInfo))
    now = int(time.time() * 1000)
    fields = ["transport-test", "", "transport-test", author, str(now), str(now + 300_000),
              "log", "3", b64(("BLE framing test " + "x" * 400).encode()), ""]
    payload = ("station-to-station/gossip-fact/2\n" + "\n".join(fields)).encode()
    record = "\t".join([hashlib.sha256(payload).hexdigest(), *fields,
                        b64(key.sign(payload, ec.ECDSA(hashes.SHA256())))])
    relay_key = ec.generate_private_key(ec.SECP256R1())
    relay_author = b64(relay_key.public_key().public_bytes(serialization.Encoding.DER,
                                                        serialization.PublicFormat.SubjectPublicKeyInfo))
    controls_records = []
    for kind in ("request", "receipt"):
        control_fields = ["transport-test", "", "transport-test", author, str(now),
                          str(now + 300_000), kind, "-1", b64(b"useful-peer"), ""]
        control_payload = ("station-to-station/gossip-fact/2\n" + "\n".join(control_fields)).encode()
        controls_records.append("\t".join([hashlib.sha256(control_payload).hexdigest(),
            *control_fields, b64(key.sign(control_payload, ec.ECDSA(hashes.SHA256())))]))
    successes = 0
    for trial in range(1, trials + 1):
        started = time.monotonic()
        stage = "discovery"
        try:
            device = await BleakScanner.find_device_by_filter(
                lambda _, advert: SERVICE in advert.service_uuids, timeout=10)
            if device is None:
                print(f"trial {trial}: not discovered", flush=True)
                continue
            stage = "connection"
            async with BleakClient(device, timeout=12) as peer:
                stage = "challenge"
                challenge = (await peer.read_gatt_char(CHALLENGE)).decode().split("\n")
                assert len(challenge) == 4 and challenge[0] == "station-to-station/gossip-challenge/2"
                nonce = base64.b64decode(challenge[1], validate=True)
                assert len(nonce) == 32
                relay = serialization.load_der_public_key(base64.b64decode(challenge[2], validate=True))
                relay.verify(base64.b64decode(challenge[3], validate=True),
                             ("station-to-station/gossip-challenge-proof/2\n" + challenge[1]).encode(),
                             ec.ECDSA(hashes.SHA256()))
                connected = time.monotonic() - started
                # Replay first, then deliver the identical control directly. Rejection
                # must not poison the receiver's seen set.
                signer = relay_key if controls and trial % 2 else key
                sender = relay_author if controls and trial % 2 else author
                current_record = controls_records[(trial - 1) // 2] if controls else record
                proof = signer.sign(("station-to-station/gossip-auth/2\n" + challenge[1]).encode(),
                                 ec.ECDSA(hashes.SHA256()))
                packet = ("station-to-station/gossip-pass/2\n" + sender + "\t" + b64(proof)
                          + "\n" + current_record).encode()
                # Twenty bytes fit even the minimum ATT MTU. No guessed MTU value.
                for offset in range(0, len(packet), 20):
                    stage = f"write at byte {offset}/{len(packet)}"
                    await peer.write_gatt_char(PASS, packet[offset:offset + 20], response=True)
                stage = "empty terminator"
                await peer.write_gatt_char(PASS, b"", response=True)
                successes += 1
                print(f"trial {trial}: verified challenge in {connected:.2f}s; wrote {len(packet)}B "
                      f"plus terminator in {time.monotonic() - started:.2f}s", flush=True)
        except Exception as error:
            print(f"trial {trial}: {stage}: {type(error).__name__}: {error} after "
                  f"{time.monotonic() - started:.2f}s", flush=True)
    print(f"{successes}/{trials} completed; check receiver logcat for admission", flush=True)
    return successes == trials


if __name__ == "__main__":
    controls = "--controls" in sys.argv[1:]
    trials = 4 if controls else (int(sys.argv[1]) if len(sys.argv) > 1 else 6)
    if trials < 1:
        raise SystemExit("trials must be positive")
    raise SystemExit(0 if asyncio.run(main(trials, controls)) else 1)
