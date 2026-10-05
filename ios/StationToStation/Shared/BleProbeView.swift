import SwiftUI
import UIKit

// #30 probe screen: two phones exchange a card over raw GATT and the phone reports
// where the time went. Ported from Android's BleProbeScreen.kt, minus the two
// knobs that measure something iOS does not have: CoreBluetooth negotiates MTU
// transparently (BleExchange.swift's header comment), and Nearby Connections has
// no iOS equivalent (NearbyPeers.kt:47), so there is no endpoint-name ceiling to
// probe here. What is left — advertise, scan, read, and how long each leg took —
// is exactly what iOS's real Exchange already runs every time (ExchangeSession),
// so this screen drives the same two classes rather than a copy of them.
//
// Throwaway measurement UI in the shape of Android's #30 screen: nothing here is
// wired into the real connect screen.

private let ground = Color(red: 0x0E / 255, green: 0x0B / 255, blue: 0x14 / 255)
private let ink = Color(red: 0xED / 255, green: 0xE9 / 255, blue: 0xF2 / 255)
private let muted = Color(red: 0x8B / 255, green: 0x82 / 255, blue: 0x99 / 255)

private let timeFormat: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "HH:mm:ss.SSS"
    return f
}()

/// One completed "Get card" attempt: how long the read took, and what came back.
private struct ProbeRun: Identifiable {
    let id = UUID()
    let peerName: String
    let ms: Int
    let card: ProbeCard?
}

struct BleProbeView: View {
    @State private var name = UIDevice.current.name
    @State private var setlistfm = "dizzi90"
    @State private var advertising = false
    @State private var scanning = false
    @State private var peers: [ExchangePeer] = []
    @State private var hits: [PeerHit] = []
    @State private var runs: [ProbeRun] = []
    @State private var log: [String] = []
    @State private var peripheral: BleCardPeripheral?
    // @State, not a plain `let`: SwiftUI re-evaluates this struct's body on every
    // state change, and only @State storage survives that — a plain property would
    // hand out a fresh CBCentralManager (and drop the scan) on the next redraw.
    @State private var central = BleCardCentral()

    private var card: ProbeCard {
        // ponytail: a throwaway key, not the Keychain identity #28 describes. The
        // probe measures bytes on the wire; whose bytes they are does not change
        // the timing.
        ProbeCard(name: name, publicKey: "8J+YgPCfmIDwn5iA8J+YgPCfmIDwn5iA8J+YgPCfmIA=", // 32 bytes
                  setlistfm: setlistfm.nilIfBlank)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Advert = service UUID, name in the local-name field. Card = "
                    + "characteristic read, reassembled across ATT_READ_BLOB by "
                    + "CoreBluetooth. Gives up at \(Int(exchangeTimeout))s and that "
                    + "is where QR takes over.")
                    .font(.caption).foregroundStyle(muted)

                TextField("Display name", text: $name)
                TextField("setlist.fm username", text: $setlistfm)
                    .autocorrectionDisabled().textInputAutocapitalization(.never)
                Text("This card is \(card.bytes().count) bytes on the wire.")
                    .font(.caption).foregroundStyle(muted)

                HStack {
                    Button(advertising ? "Stop advertising" : "Advertise") { advertising.toggle() }
                        .buttonStyle(.bordered)
                    Button(scanning ? "Stop scanning" : "Scan") { scanning.toggle() }
                        .buttonStyle(.bordered)
                }
                Text((advertising ? "\u{25CF} advertising" : "\u{25CB} not advertising") + "   "
                    + (scanning ? "\u{25CF} scanning" : "\u{25CB} not scanning"))
                    .font(.caption).foregroundStyle(muted)

                Divider()
                Text("Nearby (\(peers.count))").font(.headline)
                ForEach(peers) { peer in
                    VStack(alignment: .leading, spacing: 2) {
                        Text("\(peer.name) \u{00B7} discovered in \(peer.discoveryMs)ms")
                        Button("Get card") { getCard(peer) }
                    }
                    .padding(.vertical, 4)
                }

                Divider()
                Text("Exchanges (\(runs.count))").font(.headline)
                ForEach(runs) { run in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(run.card != nil ? "\u{2713} \(run.peerName) in \(run.ms)ms"
                             : "\u{2717} \(run.peerName) timed out after \(run.ms)ms")
                            .font(.subheadline)
                        if let card = run.card {
                            Text("card: \(card.name) \u{00B7} \(card.setlistfm ?? "no username")")
                                .font(.caption).foregroundStyle(muted)
                        }
                    }
                    .padding(.vertical, 4)
                }

                Divider()
                Text("Log").font(.headline)
                ForEach(Array(log.prefix(40).enumerated()), id: \.offset) { _, line in
                    Text(line).font(.caption).foregroundStyle(muted)
                }
            }
            .padding(20)
        }
        .background(ground.ignoresSafeArea())
        .foregroundStyle(ink)
        .navigationTitle("GATT card probe (#30)")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            central.onHit = { hit in
                if let i = hits.firstIndex(where: { $0.id == hit.id }) { hits[i] = hit }
                else { hits.append(hit) }
                peers = mergePeers(hits)
            }
            central.onLog = { appendLog($0) }
        }
        .onChange(of: advertising) { on in on ? startAdvertising() : stopAdvertising() }
        .onChange(of: scanning) { on in on ? central.start() : central.stop() }
        // A field edited while advertising should be reflected in the next card a
        // peer reads, the same way changing the name/username invalidates the
        // Compose `remember(card)` peripheral on Android.
        .onChange(of: name) { _ in if advertising { startAdvertising() } }
        .onChange(of: setlistfm) { _ in if advertising { startAdvertising() } }
        .onDisappear {
            stopAdvertising()
            central.stop()
        }
    }

    private func appendLog(_ line: String) {
        log.insert("\(timeFormat.string(from: Date()))  \(line)", at: 0)
    }

    private func startAdvertising() {
        peripheral?.stop()
        let p = BleCardPeripheral(card: card)
        p.onLog = { appendLog($0) }
        p.onCardWritten = { written in appendLog("server: card written by a peer (\(written.name))") }
        p.start()
        peripheral = p
    }

    private func stopAdvertising() {
        peripheral?.stop()
        peripheral = nil
    }

    private func getCard(_ peer: ExchangePeer) {
        let started = Date()
        central.readCard(peer, myCard: card) { got in
            let ms = Int(Date().timeIntervalSince(started) * 1000)
            runs.insert(ProbeRun(peerName: peer.name, ms: ms, card: got), at: 0)
            appendLog(got != nil ? "exchange with \(peer.name) in \(ms)ms" : "exchange with \(peer.name) timed out")
        }
    }
}
