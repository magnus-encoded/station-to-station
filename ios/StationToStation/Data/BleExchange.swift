import CoreBluetooth
import Foundation

// The CoreBluetooth half of the Exchange — the plumbing under CardWire.swift's
// logic (ADR-0001). Ported from Android's `BleCardPeripheral`/`BleCardCentral`,
// with two differences that are platform facts, not omissions:
//
//   * iOS cannot put manufacturer data in an advertisement, so the name rides
//     `CBAdvertisementDataLocalNameKey`. Android's scanner already accepts both.
//   * CoreBluetooth does not expose MTU negotiation and performs long reads
//     (ATT_READ_BLOB) transparently, so there is no MTU leg to implement or time.
//
// Everything runs on the main queue (`queue: nil`), so the callbacks below and
// the SwiftUI state they drive are on the same thread.

private let serviceUUID = CBUUID(string: exchangeServiceUUIDString)
private let cardUUID = CBUUID(string: cardCharacteristicUUIDString)
private let cardWriteUUID = CBUUID(string: cardWriteCharacteristicUUIDString)

/// The advertising half: hand out this phone's card to anyone who connects and reads.
final class BleCardPeripheral: NSObject, CBPeripheralManagerDelegate {
    private var manager: CBPeripheralManager?
    private let payload: Data
    private let displayName: String
    var onLog: ((String) -> Void)?

    /// A peer wrote their card to us. Being in the Exchange is the consent, so there
    /// is nothing to prompt — this lands exactly where a tapped card lands.
    ///
    /// The sender is not verified, and that is a decision rather than an oversight:
    /// **ADR-0016**, which also states what it does not cover. What bounds an unverified
    /// card is what it is allowed to *do* — `friendArrival` lets it add or promote,
    /// never silently rewrite a **Contact** already held (#188).
    var onCardWritten: ((ProbeCard) -> Void)?

    /// Accumulated write payload per central — a long write arrives in pieces.
    private var inbox: [UUID: Data] = [:]

    init(card: ProbeCard) {
        payload = card.bytes()
        displayName = truncateToBytes(card.name)
    }

    func start() {
        manager = CBPeripheralManager(delegate: self, queue: nil)
    }

    func stop() {
        manager?.stopAdvertising()
        manager?.removeAllServices()
        manager = nil
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        guard peripheral.state == .poweredOn else {
            onLog?("peripheral not available (state \(peripheral.state.rawValue))")
            return
        }
        // value: nil is required — a cached value cannot be served per-read, and
        // per-read is the whole point (see didReceiveRead).
        let characteristic = CBMutableCharacteristic(
            type: cardUUID, properties: .read, value: nil, permissions: .readable)
        // The way back: a read cannot carry my card to them, so they write it here.
        let inbound = CBMutableCharacteristic(
            type: cardWriteUUID, properties: .write, value: nil, permissions: .writeable)
        let service = CBMutableService(type: serviceUUID, primary: true)
        service.characteristics = [characteristic, inbound]
        peripheral.removeAllServices()
        peripheral.add(service)
        peripheral.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [serviceUUID],
            CBAdvertisementDataLocalNameKey: displayName,
        ])
        onLog?("advertising: \(payload.count)-byte card behind the characteristic")
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        guard request.characteristic.uuid == cardUUID else {
            peripheral.respond(to: request, withResult: .attributeNotFound)
            return
        }
        // Answer AT the requested offset. Ignoring it silently truncates any card
        // over ~22 bytes — the bug BleProbe.kt:142 records from the #18 probe.
        guard request.offset <= payload.count else {
            peripheral.respond(to: request, withResult: .invalidOffset)
            return
        }
        request.value = sliceForOffset(payload, request.offset)
        peripheral.respond(to: request, withResult: .success)
        onLog?("server: read at offset \(request.offset)")
    }

    /// A card written to us. A long write arrives as several requests in one call,
    /// each at its own offset; CoreBluetooth wants exactly one response, given on
    /// the *first* request, and withholding it hangs the writer for its whole
    /// timeout — which is the peer's entire exchange.
    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        guard let first = requests.first else { return }
        guard requests.allSatisfy({ $0.characteristic.uuid == cardWriteUUID }) else {
            peripheral.respond(to: first, withResult: .attributeNotFound)
            return
        }
        let id = first.central.identifier
        for request in requests {
            inbox[id] = writeAtOffset(inbox[id] ?? Data(), request.offset, request.value ?? Data())
        }
        peripheral.respond(to: first, withResult: .success)
        // ponytail: one completed write operation is one whole card. True for both
        // a short write and a long one, which the platform delivers batched here.
        guard let payload = inbox.removeValue(forKey: id) else { return }
        guard let written = parseProbeCard(String(decoding: payload, as: UTF8.self)) else {
            onLog?("server: unparseable card written (\(payload.count) bytes), ignored")
            return
        }
        onLog?("server: card written by a peer (\(payload.count) bytes)")
        onCardWritten?(written)
    }
}

/// The scanning half: find peers, then connect and read the card off one.
final class BleCardCentral: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    private var manager: CBCentralManager?
    private var found: [String: CBPeripheral] = [:]
    private var firstSeenAt: [String: Date] = [:]
    private var scanStartedAt = Date()
    private var reading: CBPeripheral?
    private var onCard: ((ProbeCard?) -> Void)?
    private var timeout: DispatchWorkItem?
    /// The card just read, held across the write-back leg: a failed write-back must
    /// not undo a successful read — one-way is better than none.
    private var read: ProbeCard?
    private var myPayload: Data?

    var onHit: ((PeerHit) -> Void)?
    var onState: ((CBManagerState) -> Void)?
    var onLog: ((String) -> Void)?

    func start() {
        scanStartedAt = Date()
        firstSeenAt.removeAll()
        found.removeAll()
        if manager == nil { manager = CBCentralManager(delegate: self, queue: nil) }
        else { scanIfReady() }
    }

    func stop() {
        manager?.stopScan()
        if let reading { manager?.cancelPeripheralConnection(reading) }
        reading = nil
        finish(nil)
    }

    private func scanIfReady() {
        guard let manager, manager.state == .poweredOn else { return }
        // Filtered by service UUID: an iPhone in the background only ever appears
        // to a scanner that names the service, and it keeps the list to this app.
        manager.scanForPeripherals(withServices: [serviceUUID],
                                   options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
        onLog?("scanning started")
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        onState?(central.state)
        scanIfReady()
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let id = peripheral.identifier.uuidString
        found[id] = peripheral
        let firstSeen = firstSeenAt[id] ?? Date()
        firstSeenAt[id] = firstSeen
        onHit?(PeerHit(
            id: id,
            name: nameFrom(advertisementData) ?? peripheral.name,
            rssi: RSSI.intValue,
            discoveryMs: Int(firstSeen.timeIntervalSince(scanStartedAt) * 1000)
        ))
    }

    /// Android sends manufacturer data; iOS sends a Complete Local Name. Accept both.
    private func nameFrom(_ advertisementData: [String: Any]) -> String? {
        if let data = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data,
           let name = nameFromManufacturerData(data) {
            return name
        }
        return (advertisementData[CBAdvertisementDataLocalNameKey] as? String)?.nilIfBlank
    }

    /// Connect, discover, read the card, then write `myCard` back on the same
    /// connection so one tap exchanges both cards. Gives up at `exchangeTimeout`,
    /// which in the shipped flow is the cue to fall through to QR rather than spin.
    func readCard(_ peer: ExchangePeer, myCard: ProbeCard, onCard: @escaping (ProbeCard?) -> Void) {
        guard let manager, let peripheral = found[peer.id] else { onCard(nil); return }
        finish(nil)
        self.onCard = onCard
        myPayload = myCard.bytes()
        reading = peripheral
        peripheral.delegate = self
        let work = DispatchWorkItem { [weak self] in
            self?.onLog?("gave up after \(exchangeTimeout)s")
            self?.finish(self?.read)
        }
        timeout = work
        DispatchQueue.main.asyncAfter(deadline: .now() + exchangeTimeout, execute: work)
        manager.connect(peripheral, options: nil)
    }

    private func finish(_ card: ProbeCard?) {
        timeout?.cancel()
        timeout = nil
        let callback = onCard
        onCard = nil
        read = nil
        myPayload = nil
        if let reading { manager?.cancelPeripheralConnection(reading) }
        reading = nil
        callback?(card)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral,
                        error: Error?) {
        finish(nil)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,
                        error: Error?) {
        // No-op once the read has landed; finish() has already cleared onCard.
        if peripheral.identifier == reading?.identifier { finish(read) }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil,
              let service = peripheral.services?.first(where: { $0.uuid == serviceUUID })
        else { finish(nil); return }
        peripheral.discoverCharacteristics([cardUUID, cardWriteUUID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService,
                    error: Error?) {
        guard error == nil,
              let characteristic = service.characteristics?.first(where: { $0.uuid == cardUUID })
        else { finish(nil); return }
        // No MTU leg: CoreBluetooth issues the blob reads and reassembles them.
        peripheral.readValue(for: characteristic)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard error == nil, let value = characteristic.value else { finish(nil); return }
        onLog?("read \(value.count) bytes")
        read = parseProbeCard(String(decoding: value, as: UTF8.self))
        // Hand mine over before letting go of the connection, so the peer never has
        // to tap. Anything that goes wrong here still finishes with the card read.
        guard let payload = myPayload,
              let inbound = characteristic.service?.characteristics?
                  .first(where: { $0.uuid == cardWriteUUID })
        else {
            onLog?("peer has no write characteristic — one-way exchange")
            finish(read)
            return
        }
        // ponytail: CoreBluetooth does not split a write-with-response, so a card
        // longer than one ATT PDU cannot go back this way. The card is ~130 bytes
        // against a negotiated 180+; if that ever changes, chunk it here.
        guard payload.count <= peripheral.maximumWriteValueLength(for: .withResponse) else {
            onLog?("card too long to write back (\(payload.count) bytes) — one-way exchange")
            finish(read)
            return
        }
        peripheral.writeValue(payload, for: inbound, type: .withResponse)
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        if let error { onLog?("write-back FAILED: \(error.localizedDescription)") }
        else { onLog?("wrote my card back") }
        finish(read)
    }
}

/// One Exchange: advertise my card, scan for others, read one on tap. Nearby
/// Connections has no iOS equivalent and is not replaced, so BLE is the only
/// radio here — the screen still cannot tell which one found someone.
final class ExchangeSession: ObservableObject {
    @Published var peers: [ExchangePeer] = []
    @Published var bluetoothDenied = false

    private let central = BleCardCentral()
    private var peripheral: BleCardPeripheral?
    private var hits: [PeerHit] = []
    private var running = false
    private var myCard: ProbeCard?

    /// Someone wrote their card to us — the other half of an Exchange the peer
    /// tapped. Treated exactly as a tapped card: being on this screen and
    /// advertising *is* the consent, so it is added, not prompted.
    var onFriendReceived: ((Friend) -> Void)?

    init() {
        central.onHit = { [weak self] hit in
            guard let self else { return }
            if let i = self.hits.firstIndex(where: { $0.id == hit.id }) { self.hits[i] = hit }
            else { self.hits.append(hit) }
            self.peers = mergePeers(self.hits)
        }
        central.onState = { [weak self] state in
            self?.bluetoothDenied = (state == .unauthorized)
        }
    }

    /// Being discoverable is opted into by standing on this screen, not a
    /// background state. Safe to call again while running.
    func start(card: ProbeCard) {
        if running { return }
        running = true
        myCard = card
        peripheral = BleCardPeripheral(card: card)
        peripheral?.onCardWritten = { [weak self] written in
            guard let friend = friendFromCard(written) else { return }
            self?.onFriendReceived?(friend)
        }
        peripheral?.start()
        central.start()
    }

    func stop() {
        running = false
        central.stop()
        peripheral?.stop()
        peripheral = nil
        myCard = nil
        hits = []
        peers = []
    }

    func restart(card: ProbeCard) {
        stop()
        start(card: card)
    }

    /// Bring one peer in: connect and read (up to `exchangeTimeout`), then fire
    /// with the friend, or nil on failure so the caller falls through to the QR
    /// offer rather than the tap landing on a dead end.
    func connect(_ peer: ExchangePeer, onFriend: @escaping (Friend?) -> Void) {
        guard let myCard else { onFriend(nil); return }
        central.readCard(peer, myCard: myCard) { card in onFriend(card.flatMap(friendFromCard)) }
    }
}
