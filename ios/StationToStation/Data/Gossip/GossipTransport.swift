import CoreBluetooth
import Foundation

// The CoreBluetooth half of the gossip channel (#417) — the plumbing under `GossipWire.swift`
// and `gossipStormGate`, which are where every decision actually lives (ADR-0001). Nothing in
// this file judges a message. It moves bytes, hands them to the gate, and keeps what the gate
// says to keep.
//
// The Exchange's own radio is `BleExchange.swift` and stays exactly as ADR-0016 left it:
// foreground, screen-gated, its own service UUID, untouched by anything here. This is the one
// narrow carve-out ADR-0019 argues for, and it is a separate file for the same reason it is a
// separate service — so that "does this run in the background?" is answered by which file you
// are reading.
//
// ============================================================================
// WHAT DELIVERY ACTUALLY LOOKS LIKE ON IOS. Read this before believing a bug report.
// ============================================================================
//
// iOS background BLE is opportunistic. It is not a guarantee, it is not a queue, and it has no
// SLA. Everything below is platform behaviour, not a limitation of this code, and a user-facing
// claim that a check-in "will reach" a **Contact** would be false:
//
//   * **Scanning is throttled.** A backgrounded central's scan is coalesced with every other
//     app's and serviced on the system's schedule. A peer that would be found in under a second
//     in the foreground can take minutes in the background, or the length of the meeting and
//     therefore never.
//   * **Duplicate advertisements are never delivered.** `CBCentralManagerScanOptionAllowDuplicatesKey`
//     is ignored in the background, so a peer already discovered in this scan session is seen
//     once and not again. A meeting that fails is not automatically retried by the radio.
//   * **`withServices:` is mandatory.** A background scan with a nil service list returns
//     nothing at all. Hence the fixed `gossipServiceUUIDString` — the rotating token cannot be
//     the thing scanned for.
//   * **A backgrounded iPhone advertises almost nothing.** The local name is dropped, and the
//     service UUID moves out of the advertisement into a manufacturer-specific "overflow" area
//     that only another **iOS** device explicitly scanning for that exact UUID can see. The
//     practical consequence, stated plainly because it is a product fact and not a detail: a
//     backgrounded iPhone is **invisible to an Android scanner**. iOS↔Android gossip works when
//     the iPhone is the one scanning, or when the iPhone is in the foreground. iPhone↔iPhone
//     works in both directions, slowly.
//   * **The app is not running most of the time.** Delivery happens because the system relaunches
//     this app into the background when the radio has something to say, calls
//     `willRestoreState`, and gives it a few seconds. That is why every decision this file makes
//     is written to `GossipLedger` immediately rather than held in memory.
//   * **The user can end it at any time.** Force-quitting the app stops restoration until the
//     next manual launch; Low Power Mode, a Bluetooth toggle, or simply denying the permission
//     all end it silently.
//
// So: a check-in relayed here reaches a **Contact** *eventually and probably*, over a night, if
// the two phones are in the same place for long enough. That is worth building — it is exactly
// the walk-home case ADR-0019 exists for — and it is not a message channel. Nothing in this app
// should tell someone their arrival "was sent".
//
// ============================================================================
// The meeting, in three GATT operations
// ============================================================================
//
//   1. The central reads `token` and gets the peripheral's `gossipTokenOffer` — one token per
//      **Contact** the peripheral holds, for the current bucket. It resolves that against its
//      own secrets. No match, and it hangs up having learnt nothing but "some Station to
//      Station device is nearby", which is what it already knew from the advertisement.
//   2. The central writes `inbox`: the single token for the pair it just resolved, then its
//      batch. That token is what tells the peripheral who is talking — the secret is symmetric,
//      so the same value resolves from either end.
//   3. The central reads `outbox`, which the peripheral prepared in step 2 for exactly that
//      **Contact**. A connection that never resolved reads zero bytes.
//
// The central resolves first and the peripheral second, deliberately: the side that initiated
// the connection is the side that spends its own privacy first. A device that connects, reads
// the offer and leaves has learnt nothing it could not have learnt by standing there.

private let gossipService = CBUUID(string: gossipServiceUUIDString)
private let gossipTokenCharacteristic = CBUUID(string: gossipTokenCharacteristicUUIDString)
private let gossipInboxCharacteristic = CBUUID(string: gossipInboxCharacteristicUUIDString)
private let gossipOutboxCharacteristic = CBUUID(string: gossipOutboxCharacteristicUUIDString)

/// The restore identifiers. These are names iOS holds on this app's behalf across launches;
/// changing one is telling the system this is a different radio, and any session it was holding
/// for the old name is dropped.
private let gossipCentralRestoreId = "io.github.magnusencoded.stationtostation.gossip.central"
private let gossipPeripheralRestoreId = "io.github.magnusencoded.stationtostation.gossip.peripheral"

/// Whether this device has ever held a **Contact**, in `UserDefaults` rather than behind the
/// timeline cache's `async` load.
///
/// Load-bearing, not a cache: the managers below have to be constructed *during* launch for iOS
/// to hand back a restored session, and the contact list is not readable that early. Just as
/// important, it is what stops a phone that has never met anybody from constructing a
/// `CBCentralManager` at all — which would raise the Bluetooth permission prompt on first
/// launch, for a background feature the user has no Contacts to use. Today the prompt happens
/// where it belongs, on the Exchange screen, with a person in front of them.
private let gossipEnabledKey = "gossip.hasContacts"

/// How long one meeting may take before it is abandoned.
///
/// Three GATT round trips over a background connection; `exchangeTimeout` is sized for a human
/// waiting on a screen and is far too short. Nobody is watching this one, so it can afford to
/// be patient — but not unbounded, because a peer that stalls mid-write holds a connection slot
/// and iOS gives a backgrounded app very few.
private let gossipMeetingTimeout: TimeInterval = 20

/// How many peers one scan session talks to at once. CoreBluetooth will hold more, but a
/// backgrounded app has seconds of runtime, and four concurrent meetings that all time out is
/// the whole budget spent on nothing.
private let gossipMaxConcurrentMeetings = 4

/// The gossip radio: both halves, one lifetime, one queue.
///
/// A singleton, which this codebase otherwise avoids, because iOS makes it one whether we do or
/// not: a restore identifier names exactly one manager, and a second `CBCentralManager` created
/// with the same identifier is a second claim on one restored session. `ContactIdentity` is a
/// singleton for the same kind of reason — the platform owns the thing, and pretending
/// otherwise creates the bug.
///
/// Not unit-tested, and the file is arranged so that this is not a gap: every value it computes
/// comes from `GossipToken`/`GossipWire`, every decision from `gossipStormGate`, every retention
/// rule from `GossipLedger` and `GossipBudget` — all of which are asserted without a radio. What
/// is left here is CoreBluetooth's own behaviour, which only two real phones can exercise.
final class GossipTransport: NSObject {

    static let shared = GossipTransport()

    /// Not the main queue, unlike `BleExchange`. Nothing here drives a SwiftUI view, and a
    /// background relaunch has no main run loop worth competing for.
    private let queue = DispatchQueue(label: "io.github.magnusencoded.stationtostation.gossip",
                                      qos: .utility)

    private var central: CBCentralManager?
    private var peripheral: CBPeripheralManager?
    private var advertising = false

    /// Meetings in flight, keyed by the peripheral's per-boot identifier. Strong references:
    /// CoreBluetooth does not retain a `CBPeripheral` for you, and a released one silently ends
    /// the connection.
    private var meetings: [UUID: GossipMeeting] = [:]
    /// When each peripheral was last talked to, so a stranger in range is not reconnected to on
    /// every discovery callback. In memory only — a per-boot list of nearby radios is not
    /// something to write down.
    private var lastMet: [UUID: Date] = [:]

    /// Long writes arrive as a series of separate write operations (CoreBluetooth does not
    /// perform prepared writes), so the peripheral half accumulates per central and finalises on
    /// a zero-length write. Bounded by `gossipMaxWireBytes` and dropped when the meeting ends.
    private var inbox: [UUID: Data] = [:]
    private var inboxStartedAt: [UUID: Date] = [:]
    /// What the peripheral half has prepared for one central, once its write resolved it to a
    /// **Contact**.
    private var outbox: [UUID: Data] = [:]
    private var outboxContact: [UUID: String] = [:]

    private let channel = GossipChannel.shared

    // --- Lifecycle ---

    /// Called from `application(_:didFinishLaunchingWithOptions:)`, synchronously, always.
    ///
    /// Synchronously and at launch because that is the only moment iOS will hand back a restored
    /// session; anything later and `willRestoreState` never arrives, connections the system was
    /// holding are dropped, and the feature quietly degrades to "works while the app is open",
    /// which is the failure mode this whole issue exists to avoid.
    func wakeAtLaunch() {
        guard UserDefaults.standard.bool(forKey: gossipEnabledKey) else { return }
        start()
    }

    /// Bring the radio up, or take it down, according to whether there is anybody to gossip
    /// with. Safe to call repeatedly; called whenever the **Contact** list changes.
    ///
    /// **The lifecycle, stated for review:** the gossip radio runs whenever this device holds at
    /// least one **Contact** — a **Friend** with a `publicKey`, which only an in-person Exchange
    /// can produce — and never otherwise. It is not tied to a gig, a check-in, or a screen. That
    /// is deliberate: a relay that only ran during one's own nights out would carry only one's
    /// own check-ins, and the whole point is the hop that happens on somebody else's walk home.
    /// The cost is honest and named in ADR-0019 — this is always-on infrastructure, and on iOS
    /// its battery cost is whatever the system decides to spend on a throttled background scan.
    func contactsChanged(_ friends: [Friend]) {
        let keys = contactKeysOf(friends)
        UserDefaults.standard.set(!keys.isEmpty, forKey: gossipEnabledKey)
        // The last **Contact** leaving takes the held messages with it: they are other people's
        // check-ins, kept only so they could be passed on, and there is nobody left to pass
        // them to. Same shape as `Friend.publicKey`'s "removing the Contact is the whole of
        // revocation", one level up.
        Task { [channel] in
            if keys.isEmpty { await channel.forgetAll() } else { await channel.setContacts(friends) }
        }
        queue.async { [weak self] in
            guard let self else { return }
            if keys.isEmpty { self.stopLocked() } else { self.startLocked() }
        }
    }

    private func start() { queue.async { [weak self] in self?.startLocked() } }

    private func startLocked() {
        if central == nil {
            central = CBCentralManager(delegate: self, queue: queue, options: [
                CBCentralManagerOptionRestoreIdentifierKey: gossipCentralRestoreId,
                // No power alert: nobody is looking at this screen, and a system alert raised by
                // a background relay would be the app interrupting someone to tell them about a
                // radio they never asked to turn on.
                CBCentralManagerOptionShowPowerAlertKey: false,
            ])
        }
        if peripheral == nil {
            peripheral = CBPeripheralManager(delegate: self, queue: queue, options: [
                CBPeripheralManagerOptionRestoreIdentifierKey: gossipPeripheralRestoreId,
                CBPeripheralManagerOptionShowPowerAlertKey: false,
            ])
        }
        scanLocked()
        advertiseLocked()
    }

    private func stopLocked() {
        central?.stopScan()
        for (_, meeting) in meetings { central?.cancelPeripheralConnection(meeting.peripheral) }
        meetings.removeAll()
        peripheral?.stopAdvertising()
        peripheral?.removeAllServices()
        advertising = false
        inbox.removeAll()
        inboxStartedAt.removeAll()
        outbox.removeAll()
        outboxContact.removeAll()
    }

    private func scanLocked() {
        guard let central, central.state == .poweredOn else { return }
        // The service list is mandatory in the background — a nil list discovers nothing — and
        // duplicates are refused there whatever this asks for, so it asks for the behaviour it
        // will actually get rather than one that only holds in the foreground.
        central.scanForPeripherals(withServices: [gossipService],
                                   options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
    }

    private func advertiseLocked() {
        guard let peripheral, peripheral.state == .poweredOn, !advertising else { return }
        let token = CBMutableCharacteristic(type: gossipTokenCharacteristic, properties: .read,
                                            value: nil, permissions: .readable)
        let inboxCharacteristic = CBMutableCharacteristic(type: gossipInboxCharacteristic,
                                                          properties: .write, value: nil,
                                                          permissions: .writeable)
        let outboxCharacteristic = CBMutableCharacteristic(type: gossipOutboxCharacteristic,
                                                           properties: .read, value: nil,
                                                           permissions: .readable)
        let service = CBMutableService(type: gossipService, primary: true)
        service.characteristics = [token, inboxCharacteristic, outboxCharacteristic]
        peripheral.removeAllServices()
        peripheral.add(service)
        // No `CBAdvertisementDataLocalNameKey`. The Exchange advertises a display name because a
        // human is choosing a row on a screen; here there is no screen, and a name in the air is
        // precisely the stable, followable identifier the rotating token exists to avoid. iOS
        // drops it in the background anyway — this says so on purpose rather than by accident.
        peripheral.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [gossipService]])
        advertising = true
    }

    // --- The central's side of a meeting ---

    private func abandon(_ id: UUID, cancel: Bool = true) {
        guard let meeting = meetings.removeValue(forKey: id) else { return }
        meeting.timeout?.cancel()
        if cancel { central?.cancelPeripheralConnection(meeting.peripheral) }
    }

    private func beginMeeting(_ peripheral: CBPeripheral) {
        let id = peripheral.identifier
        guard meetings[id] == nil, meetings.count < gossipMaxConcurrentMeetings else { return }
        if let last = lastMet[id], Date().timeIntervalSince(last) < gossipPeerCooldown { return }
        lastMet[id] = Date()
        let meeting = GossipMeeting(peripheral: peripheral)
        let work = DispatchWorkItem { [weak self] in self?.abandon(id) }
        meeting.timeout = work
        queue.asyncAfter(deadline: .now() + gossipMeetingTimeout, execute: work)
        meetings[id] = meeting
        peripheral.delegate = self
        central?.connect(peripheral, options: nil)
    }

    /// Step 2: the offer resolved to a **Contact**, so write our own token and batch to them.
    ///
    /// Chunked by hand. `writeValue(_:type:.withResponse)` silently truncates anything past the
    /// negotiated MTU — CoreBluetooth performs no prepared write — which `BleExchange.swift`
    /// records for the Exchange's 130-byte card and which a 20 kB batch would fall straight
    /// into. Each chunk is acknowledged before the next goes out, and a zero-length write ends
    /// the message.
    private func send(_ meeting: GossipMeeting, to contact: String) {
        Task { [weak self] in
            guard let self else { return }
            let payload = await self.channel.outgoing(for: contact, now: Date())
            self.queue.async {
                guard self.meetings[meeting.peripheral.identifier] != nil,
                      let characteristic = meeting.inbox
                else { return }
                let limit = max(20, meeting.peripheral.maximumWriteValueLength(for: .withResponse))
                meeting.contact = contact
                meeting.pending = payload.map { $0.chunked(by: limit) } ?? []
                // The terminator, always: an empty batch is still a meeting, and the peripheral
                // has to know the write finished before it can prepare a reply.
                meeting.pending.append(Data())
                self.writeNext(meeting, characteristic)
            }
        }
    }

    private func writeNext(_ meeting: GossipMeeting, _ characteristic: CBCharacteristic) {
        guard !meeting.pending.isEmpty else {
            meeting.peripheral.readValue(for: meeting.outbox ?? characteristic)
            return
        }
        let chunk = meeting.pending.removeFirst()
        meeting.peripheral.writeValue(chunk, for: characteristic, type: .withResponse)
    }
}

/// One meeting in flight. A class so the delegate callbacks can find it by peripheral and
/// mutate it in place.
private final class GossipMeeting {
    let peripheral: CBPeripheral
    var inbox: CBCharacteristic?
    var outbox: CBCharacteristic?
    var contact: String?
    var pending: [Data] = []
    var timeout: DispatchWorkItem?

    init(peripheral: CBPeripheral) { self.peripheral = peripheral }
}

private extension Data {
    func chunked(by size: Int) -> [Data] {
        guard size > 0, !isEmpty else { return isEmpty ? [] : [self] }
        return stride(from: 0, to: count, by: size).map {
            subdata(in: $0..<Swift.min($0 + size, count))
        }
    }
}

extension GossipTransport: CBCentralManagerDelegate {

    /// The first delegate method after a background relaunch, and the reason the manager is
    /// constructed during `didFinishLaunchingWithOptions`.
    ///
    /// The peripherals iOS hands back are ones it was holding a connection to on this app's
    /// behalf. They arrive with no delegate — reattaching it is what lets an in-flight meeting
    /// continue rather than sit connected and silent until it times out.
    func centralManager(_ central: CBCentralManager, willRestoreState state: [String: Any]) {
        let restored = state[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? []
        for peripheral in restored {
            peripheral.delegate = self
            // Restarted rather than resumed: the restored connection carries no memory of which
            // GATT operation was in flight, and a meeting is three ordered steps. Beginning
            // again is cheap and idempotent — the gate throws away everything already seen.
            let meeting = GossipMeeting(peripheral: peripheral)
            let id = peripheral.identifier
            let work = DispatchWorkItem { [weak self] in self?.abandon(id) }
            meeting.timeout = work
            queue.asyncAfter(deadline: .now() + gossipMeetingTimeout, execute: work)
            meetings[id] = meeting
            if peripheral.state == .connected { peripheral.discoverServices([gossipService]) }
            else { central.connect(peripheral, options: nil) }
        }
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard central.state == .poweredOn else { return }
        scanLocked()
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        beginMeeting(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([gossipService])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral,
                        error: Error?) {
        abandon(peripheral.identifier, cancel: false)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,
                        error: Error?) {
        abandon(peripheral.identifier, cancel: false)
    }
}

extension GossipTransport: CBPeripheralDelegate {

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil,
              let service = peripheral.services?.first(where: { $0.uuid == gossipService })
        else { abandon(peripheral.identifier); return }
        peripheral.discoverCharacteristics(
            [gossipTokenCharacteristic, gossipInboxCharacteristic, gossipOutboxCharacteristic],
            for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService,
                    error: Error?) {
        guard error == nil, let meeting = meetings[peripheral.identifier],
              let characteristics = service.characteristics,
              let token = characteristics.first(where: { $0.uuid == gossipTokenCharacteristic })
        else { abandon(peripheral.identifier); return }
        meeting.inbox = characteristics.first { $0.uuid == gossipInboxCharacteristic }
        meeting.outbox = characteristics.first { $0.uuid == gossipOutboxCharacteristic }
        guard meeting.inbox != nil, meeting.outbox != nil else {
            abandon(peripheral.identifier); return
        }
        peripheral.readValue(for: token)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        let id = peripheral.identifier
        guard error == nil, let meeting = meetings[id], let value = characteristic.value
        else { abandon(id); return }

        if characteristic.uuid == gossipTokenCharacteristic {
            guard let offered = decodeGossipTokens(value) else { abandon(id); return }
            Task { [weak self] in
                guard let self else { return }
                let contact = await self.channel.resolve(offered, now: Date())
                self.queue.async {
                    // Nobody I have met. Hang up having learnt nothing, and — importantly — say
                    // nothing: no token of ours goes out to a device we could not place.
                    guard let contact else { self.abandon(id); return }
                    self.send(meeting, to: contact)
                }
            }
            return
        }

        if characteristic.uuid == gossipOutboxCharacteristic {
            guard let contact = meeting.contact else { abandon(id); return }
            Task { [weak self] in
                guard let self else { return }
                _ = await self.channel.receive(value, expecting: contact, now: Date())
                self.queue.async { self.abandon(id) }
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        let id = peripheral.identifier
        guard error == nil, let meeting = meetings[id] else { abandon(id); return }
        if meeting.pending.isEmpty {
            // Everything is across. Only now is the batch marked delivered, so a handover that
            // died halfway is offered again the next time these two phones meet.
            if let contact = meeting.contact, let outbox = meeting.outbox {
                Task { [weak self] in await self?.channel.confirmDelivery(to: contact) }
                peripheral.readValue(for: outbox)
                return
            }
            abandon(id)
            return
        }
        writeNext(meeting, characteristic)
    }
}

extension GossipTransport: CBPeripheralManagerDelegate {

    func peripheralManager(_ peripheral: CBPeripheralManager, willRestoreState state: [String: Any]) {
        // The services iOS was advertising on this app's behalf come back already added, so
        // re-adding them would fail with `alreadyAdvertising`/duplicate-UUID. Marking the
        // advertisement live is the whole of the restore.
        if let services = state[CBPeripheralManagerRestoredStateServicesKey] as? [CBMutableService],
           services.contains(where: { $0.uuid == gossipService }) {
            advertising = true
        }
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        guard peripheral.state == .poweredOn else { return }
        advertiseLocked()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        switch request.characteristic.uuid {
        case gossipTokenCharacteristic:
            Task { [weak self] in
                guard let self else { return }
                let offer = await self.channel.tokenOffer(now: Date())
                self.queue.async { self.answer(peripheral, request, with: offer) }
            }
        case gossipOutboxCharacteristic:
            // Empty for a central that never resolved: a stranger gets zero bytes, not an error
            // and not a batch. Nothing about this device's **Contacts** is inferable from it.
            let id = request.central.identifier
            let payload = outbox[id] ?? Data()
            answer(peripheral, request, with: payload)
            // The last slice of a long read is the only evidence this side gets that the batch
            // actually arrived, so it is where delivery is recorded. A central that gave up
            // halfway leaves the messages undelivered and is offered them again.
            if request.offset + (request.value?.count ?? 0) >= payload.count,
               let contact = outboxContact[id] {
                outboxContact[id] = nil
                Task { [weak self] in await self?.channel.confirmDelivery(to: contact) }
            }
        default:
            peripheral.respond(to: request, withResult: .attributeNotFound)
        }
    }

    /// Answer AT the requested offset — a long read arrives as a rising series of them, and
    /// ignoring it silently truncates anything over about 22 bytes. `sliceForOffset` is the
    /// Exchange's, already tested, rather than a second copy of the same arithmetic.
    private func answer(_ peripheral: CBPeripheralManager, _ request: CBATTRequest, with payload: Data) {
        guard request.offset <= payload.count else {
            peripheral.respond(to: request, withResult: .invalidOffset)
            return
        }
        request.value = sliceForOffset(payload, request.offset)
        peripheral.respond(to: request, withResult: .success)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        guard let first = requests.first else { return }
        guard requests.allSatisfy({ $0.characteristic.uuid == gossipInboxCharacteristic }) else {
            peripheral.respond(to: first, withResult: .attributeNotFound)
            return
        }
        let id = first.central.identifier
        // A stalled writer must not hold bytes here for ever; the buffer is dropped once a
        // meeting could not still be in progress.
        if let started = inboxStartedAt[id], Date().timeIntervalSince(started) > gossipMeetingTimeout {
            inbox[id] = nil
            inboxStartedAt[id] = nil
        }
        var accumulated = inbox[id] ?? Data()
        var finished = false
        for request in requests {
            let chunk = request.value ?? Data()
            if chunk.isEmpty { finished = true } else { accumulated.append(chunk) }
        }
        guard accumulated.count <= gossipMaxWireBytes else {
            inbox[id] = nil
            inboxStartedAt[id] = nil
            peripheral.respond(to: first, withResult: .insufficientResources)
            return
        }
        inbox[id] = accumulated
        if inboxStartedAt[id] == nil { inboxStartedAt[id] = Date() }
        peripheral.respond(to: first, withResult: .success)
        guard finished else { return }
        inbox[id] = nil
        inboxStartedAt[id] = nil
        Task { [weak self] in
            guard let self else { return }
            let now = Date()
            guard let contact = await self.channel.receive(accumulated, expecting: nil, now: now)
            else { return }
            // Prepared only after the gate has had the batch, so the reply is one this device
            // stands behind — including anything it just accepted and this **Contact** has not
            // had. The central reads it as step 3.
            let reply = await self.channel.outgoing(for: contact, now: now)
            self.queue.async {
                self.outbox[id] = reply ?? Data()
                self.outboxContact[id] = contact
            }
        }
    }
}
