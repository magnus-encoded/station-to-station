# Intercepting ourselves: the wire capture procedure (#142)

The claim is that a handover between two phones is unreadable to anyone else on the
network. "We armed it and saw no plaintext" is not evidence for that, because a
misconfigured capture produces exactly the same result. So the procedure has an order, and
the order is the point:

1. **Capture the transfer unencrypted and reconstruct a photograph and readable **Log**
   text out of it.** This proves the rig, not the app.
2. **Arm it and run the same rig again.** Only now does "nothing usable" mean something.

Run 1 is the positive control. Skipping it makes run 2 worthless.

## What never goes in this repository

**A pre-encryption capture is by construction a plaintext copy of real personal data.**
No `.pcap`, no `.pcapng`, no extracted media, no log excerpt containing anything from a
real timeline. Nothing captured here is committed, ever.

That is also why the rig sends synthetic material: `HandoverDebugHarness.syntheticTestPhoto`
draws a PNG on the device at run time, and the **Log** line it sends is invented. A capture
of the control run therefore contains a picture that was never anyone's night.

## The rig

`HandoverDebugHarness.kt`, reachable only by an explicit intent in a **debug** build —
never from a screen, so it cannot be tapped by accident or ship. The debug build carries
its own `.debug` applicationId suffix, so it installs alongside a release/alpha build.

Host phone (prints its address, and a certificate fingerprint in the armed pass):

```sh
adb shell am start \
  -n io.github.magnusencoded.stationtostation.debug/io.github.magnusencoded.stationtostation.MainActivity \
  -a io.github.magnusencoded.stationtostation.HANDOVER_DEBUG \
  --es role host --es linkKey deadbeef --ez insecure true
```

Joining phone, once the host is listening:

```sh
adb shell am start \
  -n io.github.magnusencoded.stationtostation.debug/io.github.magnusencoded.stationtostation.MainActivity \
  -a io.github.magnusencoded.stationtostation.HANDOVER_DEBUG \
  --es role join --es host 192.168.1.23 --es linkKey deadbeef --ez insecure true
```

`adb logcat -s HandoverDebug` on each phone shows what happened, including the path the
received PNG was written to — `adb pull` it and open it. The armed pass is the same two
commands with `--ez insecure false`, plus `--es fingerprint <the host's logged value>` on
the joining side.

## Where to point the capture

**First pass: one endpoint on the desktop.** The practical rig is the host running on a
laptop rather than a phone, so its traffic crosses the host adapter and can be captured
directly — no monitor mode, no WPA2 key, no card that supports either. Filter on the
handover port:

```sh
# Wireshark/tshark on the interface the laptop uses for that network
tshark -i wlan0 -f 'tcp port 8942' -w /tmp/handover-control.pcapng
```

For run 1 (`insecure true`), "Follow TCP stream" shows the frames: a length-prefixed
**Log** line in readable UTF-8, then an item header naming the id and byte count, then the
PNG. Save the raw stream bytes, cut the PNG out at its `\x89PNG` header, and open it. That
is interception, demonstrated.

For run 2 (`insecure false`), the same filter shows a TLS handshake and then application
data records. Nothing to follow, no PNG magic anywhere in the stream, no readable text.

**Second pass, later: device to device.** The faithful threat model is two phones on a
real network with a third machine listening, which needs monitor mode plus the network
key. That is a second pass on purpose; the first one already answers whether the bytes are
armed, and it answers it with an instrument we have proven reads plaintext when plaintext
is there.

## What the capture cannot tell you

Confidentiality only. That the manifest cannot be *altered* is a different property with a
different instrument: `sealManifest`/`openManifest` and their tests (`WireTest`), plus
`HandoverSessionTest`'s assertion that a tampered manifest writes nothing at all. Neither
claim substitutes for the other.
