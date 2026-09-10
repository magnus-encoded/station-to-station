import CoreBluetooth
import Foundation

// The CoreBluetooth half of the gossip channel (#417) — the plumbing under `GossipGatt.swift`
// and `PublicGossip.swift`, which are where every decision actually lives (ADR-0001). Nothing
// in this file judges a fact. It moves bytes and hands them to `GossipChannel`, which hands
// them to `PublicGossipState.receive`, and keeps what that says to keep.
//
// It does own exactly one check, because nothing above it can: whether the peer that pushed a
// **Pass** holds the key it claims. `peripheralManager(_:didReceiveWrite:)` verifies the peer's
// signature over `publicGossipAuthPayload` of the nonce *this* device issued, and drops the
// whole Pass if it does not verify — so `from` is established fact by the time any envelope is
// named to the ledger.
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
// Public v2: read a nonce and proof from a temporary relay key, then write a Pass
// signed by this device's temporary relay key. No Contact token or durable key is
// transmitted. Each Envelope retains its own independent author signature.
// Writes are bounded chunks followed by one empty terminator.
// ============================================================================

private let gossipService = CBUUID(string: gossipServiceUUIDString)
private let gossipChallengeCharacteristic = CBUUID(string: gossipChallengeCharacteristicUUIDString)
private let gossipPassCharacteristic = CBUUID(string: gossipPassCharacteristicUUIDString)

/// The restore identifiers. These are names iOS holds on this app's behalf across launches;
/// changing one is telling the system this is a different radio, and any session it was holding
/// for the old name is dropped.
private let gossipCentralRestoreId = "io.github.magnusencoded.stationtostation.gossip.central"
private let gossipPeripheralRestoreId = "io.github.magnusencoded.stationtostation.gossip.peripheral"

/// Persist the last relay eligibility decision so CoreBluetooth restoration can begin
/// during launch, before the timeline's asynchronous load supplies tonight's state.
private let gossipEnabledKey = "gossip.hasContacts"

/// How long one meeting may take before it is abandoned.
///
/// A read and a chunked write over a background connection; `exchangeTimeout` is sized for a human
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
/// comes from `GossipGatt`/`PublicGossip`, every decision from `PublicGossipState.receive` and
/// `GossipEnvelope.valid()`, every retention rule from `GossipLedger` and `GossipBudget` — all
/// of which are asserted without a radio. What is left here is CoreBluetooth's own behaviour,
/// which only two real phones can exercise, plus the possession check above, which needs a
/// second phone to exercise honestly.
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

    /// A **Pass** arrives as a series of separate write operations (CoreBluetooth does not
    /// perform prepared writes), so the peripheral half accumulates per central and finalises on
    /// a zero-length write. Bounded by `gossipMaxWireBytes` and dropped when the meeting ends.
    private var inbox: [UUID: Data] = [:]
    private var inboxStartedAt: [UUID: Date] = [:]

    /// Public v2 is delivered separately from the legacy check-in callback. This keeps
    /// application admission (including Block and projection) out of CoreBluetooth.
    var onPublicDelivery: ((PublicGossipDelivery) -> Void)?

    /// The nonce this listener last issued to each central, and the bytes it answered the
    /// challenge read with.
    ///
    /// Per central rather than global: two **Contacts** can be pushing at once, and a shared
    /// nonce would mean whichever read last decided what the other one had to sign. Spent on
    /// one **Pass**, so a peer that pushes twice has to read a new challenge for the second.
    ///
    /// A `CBPeripheralManager` gets no disconnect callback, so these are pruned by age instead
    /// — an unanswered challenge is dead once no meeting could still be using it, and a night
    /// in a crowded room must not accumulate one entry per stranger who read it.
    private var nonces: [UUID: Data] = [:]
    private var challenges: [UUID: Data] = [:]
    private var challengedAt: [UUID: Date] = [:]

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

    /// Contacts control attribution, independently of public relay participation.
    func contactsChanged(_ friends: [Friend], relayEnabled: Bool = true) {
        UserDefaults.standard.set(relayEnabled, forKey: gossipEnabledKey)
        Task { [channel] in await channel.setContacts(friends) }
        queue.async { [weak self] in
            guard let self else { return }
            if relayEnabled { self.startLocked() } else { self.stopLocked() }
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
        nonces.removeAll()
        challenges.removeAll()
        challengedAt.removeAll()
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
        let challenge = CBMutableCharacteristic(type: gossipChallengeCharacteristic,
                                                properties: .read, value: nil,
                                                permissions: .readable)
        let passing = CBMutableCharacteristic(type: gossipPassCharacteristic,
                                              properties: .write, value: nil,
                                              permissions: .writeable)
        let service = CBMutableService(type: gossipService, primary: true)
        service.characteristics = [challenge, passing]
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

    /// Step 2: the challenge resolved to a **Contact**, so sign their nonce and push.
    ///
    /// Chunked by hand. `writeValue(_:type:.withResponse)` silently truncates anything past the
    /// negotiated MTU — CoreBluetooth performs no prepared write — which `BleExchange.swift`
    /// records for the Exchange's 130-byte card and which a 20 kB batch would fall straight
    /// into. Each chunk is acknowledged before the next goes out, and a **zero-length write
    /// ends the Pass**: that terminator is part of the protocol, not padding, and Android's
    /// peripheral half is written to expect it.
    ///
    /// Nothing to say is a reason to hang up rather than to write. An empty **Pass** would cost
    /// a listener a signature verification to learn nothing, and the peer is still advertising
    /// a minute from now.
    private func send(_ meeting: GossipMeeting, to contact: String, nonce: Data) {
        Task { [weak self] in
            guard let self else { return }
            let payload = await self.channel.publicPass(to: contact, nonce: nonce, now: Date())
            self.queue.async {
                let id = meeting.peripheral.identifier
                guard self.meetings[id] != nil, let characteristic = meeting.pass,
                      let payload
                else { self.abandon(id); return }
                let limit = min(512, max(20, meeting.peripheral.maximumWriteValueLength(for: .withResponse)))
                meeting.contact = contact
                meeting.pending = payload.gossipChunks(by: limit) + [Data()]
                self.writeNext(meeting, characteristic)
            }
        }
    }

    private func writeNext(_ meeting: GossipMeeting, _ characteristic: CBCharacteristic) {
        guard !meeting.pending.isEmpty else { return }
        let chunk = meeting.pending.removeFirst()
        meeting.peripheral.writeValue(chunk, for: characteristic, type: .withResponse)
    }
}

/// One meeting in flight. A class so the delegate callbacks can find it by peripheral and
/// mutate it in place.
private final class GossipMeeting {
    let peripheral: CBPeripheral
    var pass: CBCharacteristic?
    var contact: String?
    var pending: [Data] = []
    var timeout: DispatchWorkItem?

    init(peripheral: CBPeripheral) { self.peripheral = peripheral }
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
            // GATT operation was in flight, and a meeting is two ordered steps. Beginning
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
            [gossipChallengeCharacteristic, gossipPassCharacteristic], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService,
                    error: Error?) {
        guard error == nil, let meeting = meetings[peripheral.identifier],
              let characteristics = service.characteristics,
              let challenge = characteristics.first(where: { $0.uuid == gossipChallengeCharacteristic }),
              let pass = characteristics.first(where: { $0.uuid == gossipPassCharacteristic })
        else { abandon(peripheral.identifier); return }
        meeting.pass = pass
        peripheral.readValue(for: challenge)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        let id = peripheral.identifier
        guard error == nil, characteristic.uuid == gossipChallengeCharacteristic,
              let meeting = meetings[id], let value = characteristic.value,
              let challenge = decodePublicGossipChallenge(value)
        else { abandon(id); return }
        send(meeting, to: challenge.from, nonce: challenge.nonce)
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        let id = peripheral.identifier
        guard error == nil, let meeting = meetings[id] else { abandon(id); return }
        guard meeting.pending.isEmpty else { writeNext(meeting, characteristic); return }
        // The terminator is across, so the whole **Pass** is. Only now is the batch marked
        // delivered — a handover that died halfway is offered again the next time these two
        // phones are in the same room.
        if let contact = meeting.contact {
            Task { [weak self] in await self?.channel.confirmDelivery(to: contact) }
        }
        abandon(id)
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

    /// The challenge read: a fresh nonce at offset 0, and the same answer for the slices that
    /// follow it.
    ///
    /// Re-reading from the start is what asks for a new nonce, which is also what makes a
    /// recorded exchange useless the moment the connection it belonged to ends. The answer is
    /// built asynchronously because the token offer needs the **Contact** list from the actor;
    /// a long read is a series of requests and CoreBluetooth is content for the response to
    /// come a moment later.
    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        guard request.characteristic.uuid == gossipChallengeCharacteristic else {
            peripheral.respond(to: request, withResult: .attributeNotFound)
            return
        }
        let id = request.central.identifier
        if request.offset > 0, let prepared = challenges[id] {
            answer(peripheral, request, with: prepared)
            return
        }
        forgetStaleChallenges()
        var nonce = Data(count: gossipNonceBytes)
        let generated = nonce.withUnsafeMutableBytes {
            SecRandomCopyBytes(kSecRandomDefault, gossipNonceBytes, $0.baseAddress!)
        }
        guard generated == errSecSuccess else {
            peripheral.respond(to: request, withResult: .unlikelyError)
            return
        }
        nonces[id] = nonce
        challengedAt[id] = Date()
        Task { [weak self] in
            guard let self else { return }
            let payload = await self.channel.challenge(nonce: nonce, now: Date())
            self.queue.async {
                self.challenges[id] = payload
                self.answer(peripheral, request, with: payload)
            }
        }
    }

    private func forgetStaleChallenges() {
        let cutoff = Date().addingTimeInterval(-gossipMeetingTimeout)
        for (id, issued) in challengedAt where issued < cutoff {
            challengedAt[id] = nil
            nonces[id] = nil
            challenges[id] = nil
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
        guard requests.allSatisfy({ $0.characteristic.uuid == gossipPassCharacteristic }) else {
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
            nonces[id] = nil
            challenges[id] = nil
            peripheral.respond(to: first, withResult: .insufficientResources)
            return
        }
        inbox[id] = accumulated
        if inboxStartedAt[id] == nil { inboxStartedAt[id] = Date() }
        peripheral.respond(to: first, withResult: .success)
        guard finished else { return }
        inbox[id] = nil
        inboxStartedAt[id] = nil
        // Spent: one nonce answers exactly one **Pass**, so a peer that pushes twice on one
        // connection has to read a new challenge for the second.
        guard let nonce = nonces.removeValue(forKey: id) else { return }
        challenges[id] = nil
        if let publicPass = decodePublicGossipPass(accumulated) {
            guard let proof = Data(base64Encoded: publicPass.proof),
                  verifyChallenge(publicGossipAuthPayload(nonce), signature: proof,
                                  publicKeyBase64: publicPass.from) else {
                return
            }
            onPublicDelivery?(PublicGossipDelivery(from: publicPass.from, pass: publicPass))
            Task { [channel] in await channel.receivePublic(publicPass, from: publicPass.from) }
        }
    }
}
