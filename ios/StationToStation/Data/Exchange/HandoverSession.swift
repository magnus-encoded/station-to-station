import Foundation

/// One device handover, end to end, over an already-connected `wire` (#142). Ported from
/// Android's `exchange/HandoverSession.kt`, step for step, because the two ends of a
/// handover may well be one of each.
///
/// Two halves of one conversation, in a fixed order, with the **source** — the phone being
/// replaced — as the server, because it is the one that showed the QR and the one whose
/// approval the whole transfer hangs off:
///
///  1. the link key, proved by the joining phone against the nonce the source sends;
///  2. the accounts step, small and structured and acknowledged, before any bulk byte
///     moves (#143) — so a failure there costs seconds, not 4.6 GB;
///  3. the sealed manifest, verified by the receiver before it plans anything;
///  4. the receiver's request list, computed by `handoverPlan` and carried, not decided,
///     by the wire;
///  5. the items, streamed; then the end-of-items marker;
///  6. the receipt, counted by the receiver and sent back so both phones can say the same
///     thing about what happened.
///
/// **Cancelling is cancelling the connection.** Every suspension point here is a
/// continuation wrapping an `NWConnection` callback that will wait forever for bytes, so a
/// `Task` cancellation alone stops nothing — `HandoverExchange.stop()` cancels the
/// connection, and both sides fall out of their loops. What already landed stays landed.

enum HandoverPhase {
    case connecting, accounts, manifest, transfer, done, failed
}

struct HandoverProgress: Equatable {
    var phase: HandoverPhase = .connecting
    var bytesDone: Int64 = 0
    /// What was committed to up front, not what has been seen so far.
    var bytesTotal: Int64 = 0
    var items: Int = 0
    var itemsTotal: Int = 0
}

/// The largest single item worth accepting — Android's figure. Generous against a long
/// video, finite against a peer that declares a body no disk can hold.
private let maxItemBytes: Int64 = 4 << 30

// MARK: - The source's half

/// The phone being replaced. Returns the receiver's `HandoverReceipt`, or nil if the
/// joining phone could not prove it read the QR — in which case nothing at all was sent,
/// not even the manifest, which is the whole anti-spoofing guarantee.
///
/// `manifest` is already narrowed by the tick list (`deviceManifest`), so this function
/// cannot send what was not approved: an id requested but absent from the manifest is
/// simply not sent, however loudly it is asked for.
///
/// `accounts` is called unconditionally, whatever was ticked — the frame always travels,
/// carrying identities only when the row was declined (#143 story 11). Skipping it would
/// leave the receiver, which always reads one, parked on the manifest frame.
func runHandoverSource(
    wire: ContactConnection,
    linkKey: Data,
    allow: Set<String>,
    manifest: HandoverManifest,
    /// The accounts step, whole — see `AppModel.sendHandoverAccounts`. Always called;
    /// what the tick list decides is whether the payload it sends carries a credential
    /// or only identities (`AccountsMove` then reports what the far end did with it).
    accounts: (ContactConnection) async throws -> AccountsMove,
    /// A readable local file holding the full-resolution bytes for a media id, or nil if
    /// this device no longer has it. A file rather than a stream because a `PHAsset`'s
    /// bytes are not a path — the caller exports first, and owns cleaning up after.
    mediaSource: (String) async -> URL?,
    onProgress: @escaping (HandoverProgress) -> Void = { _ in }
) async throws -> HandoverReceipt? {
    guard try await verifyLinkKey(wire, linkKey: linkKey) else { return nil }

    onProgress(HandoverProgress(phase: .accounts))
    // Unconditional, whatever was ticked: the frame always travels, carrying identities
    // only when the row was declined (#143 story 11). What the tick list changes is the
    // *payload*, not whether it is sent.
    let step = try await accounts(wire)
    // Accounts complete before bytes begin. A half-finished credential move is the one
    // state worth refusing to build on.
    guard bulkMayStart(allow: allow, step: step) else {
        // Never `.acknowledged` here — `bulkMayStart` would then be true — so `step` is
        // exactly #143 story 9's "offered but did not complete".
        return HandoverReceipt(trouble: "the accounts step did not complete", accountsMove: step)
    }

    onProgress(HandoverProgress(phase: .manifest))
    try await writeJson(wire, sealManifest(key: linkKey, manifest: manifest))

    var offered: [String: OfferedMedia] = [:]
    for item in manifest.media { offered[item.id] = item }
    let ids = (try await readJson(wire, [String].self) ?? []).filter { offered[$0] != nil }
    let total = ids.reduce(Int64(0)) { $0 + (offered[$1]?.bytes ?? 0) }

    var sent: Int64 = 0
    var done = 0
    onProgress(HandoverProgress(phase: .transfer, bytesTotal: total, itemsTotal: ids.count))
    for id in ids {
        // An item I no longer hold is skipped rather than refused: the far end asked
        // because the manifest offered it, and a gallery can empty between the two moments.
        guard let url = await mediaSource(id),
              let handle = try? FileHandle(forReadingFrom: url),
              let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size]) as? NSNumber
        else { continue }
        defer { try? handle.close() }
        let length = size.intValue
        try await writeJson(wire, ItemHeader(id: id, bytes: Int64(length)))
        try await wire.writeBody(length: length) {
            let chunk = try handle.read(upToCount: 64 * 1024)
            sent += Int64(chunk?.count ?? 0)
            onProgress(HandoverProgress(phase: .transfer, bytesDone: sent, bytesTotal: total,
                                        items: done, itemsTotal: ids.count))
            return chunk
        }
        done += 1
        onProgress(HandoverProgress(phase: .transfer, bytesDone: sent, bytesTotal: total,
                                    items: done, itemsTotal: ids.count))
    }
    try await writeEndOfItems(wire)

    let receipt = try await readJson(wire, HandoverReceipt.self)
        ?? HandoverReceipt(trouble: "the other phone hung up before saying what landed")
    onProgress(HandoverProgress(phase: .done, bytesDone: sent, bytesTotal: total,
                                items: done, itemsTotal: ids.count))
    return receipt
}

// MARK: - The receiver's half

/// The new phone. Returns the receipt it sent back, or nil if the manifest failed to
/// verify — and in that case **nothing is written**, which is the contract `openManifest`
/// exists to make total rather than conditional on a caller remembering to check.
///
/// `apply` is handed a *replan* rather than a plan: a function from "the cache as it stands
/// at the moment of writing" to the union to write. A 4.6 GB transfer takes long enough for
/// this device's own timeline to have moved on — a Contact reconcile landing, a note typed
/// — and writing a union computed against a cache read before all that would quietly
/// discard it. The store runs it under its own write lock (`TimelineStore.applyHandover`).
///
/// It is called whatever happens, cancellation included, so a stopped transfer still lands
/// exactly what arrived: items are independently addressed and complete-or-absent, so a
/// smaller union is a coherent library rather than a corrupt one. Nothing is checkpointed
/// anywhere, because nothing needs to be — re-running the handover asks for precisely the
/// remainder.
func runHandoverReceiver(
    wire: ContactConnection,
    linkKey: Data,
    /// Stores durably, *then* acks: the ack is the promise the source's credential clear
    /// is gated on, so it must not be sent a moment before the payload is on disk. Nil
    /// only for a connection dropped before any accounts frame arrived — a genuinely
    /// declined row still arrives as an (identities-only) payload, not as nil.
    accounts: (ContactConnection) async throws -> AccountsPayload?,
    mine: TimelineCache,
    gallery: [GalleryItem],
    receivedFile: (String, String) -> URL,
    apply: (@escaping (TimelineCache) -> HandoverPlan) async -> Void,
    onProgress: @escaping (HandoverProgress) -> Void = { _ in }
) async throws -> HandoverReceipt? {
    try await proveLinkKey(wire, linkKey: linkKey)

    onProgress(HandoverProgress(phase: .accounts))
    // Not "was the row ticked" — this side never sees the tick list — but "did a
    // credential actually arrive": a declined row still sends an identities-only
    // payload (#143 story 11), which is the same shape as "not part of this handover"
    // from the receipt's point of view (#143 story 9).
    let payload = try await accounts(wire)
    let accountsMove: AccountsMove = (payload?.credentials.isEmpty == false) ? .acknowledged : .notOffered

    onProgress(HandoverProgress(phase: .manifest))
    guard let sealed = try await readJson(wire, SealedManifest.self),
          let offer = openManifest(key: linkKey, sealed: sealed)
    else { return nil }

    // The receiver's `allow` is every category a device handover may carry, deliberately:
    // the source's tick list was applied when the manifest was built, and restating it here
    // would be a second copy of the same decision, in the one place that cannot see what
    // was ticked.
    let allow = categoriesFor(contact: false)
    let plan = handoverPlan(mine: mine, offer: offer, allow: allow, verified: true, gallery: gallery)

    try await writeJson(wire, plan.request)

    var kinds: [String: String] = [:]
    for item in offer.media { kinds[item.id] = item.kind }
    let expected = Set(plan.request)
    let total = offer.media.filter { expected.contains($0.id) }.reduce(Int64(0)) { $0 + $1.bytes }

    var arrived: [String: String] = [:]
    var bytes: Int64 = 0
    var trouble = ""
    do {
        try await receiveHandoverItems(
            wire, expected: expected, kinds: kinds, receivedFile: receivedFile,
            onBytes: { chunk in
                bytes += Int64(chunk)
                onProgress(HandoverProgress(phase: .transfer, bytesDone: bytes, bytesTotal: total,
                                            items: arrived.count, itemsTotal: expected.count))
            },
            onItem: { id, ref in arrived[id] = ref }
        )
    } catch {
        // Cancelled here, cancelled there, or the wifi went: the same outcome, and the same
        // coherent smaller library. Named for the receipt rather than swallowed.
        trouble = "the transfer stopped early — \(arrived.count) of \(expected.count) items arrived"
    }

    let landedItems = arrived
    let replan = { (current: TimelineCache) in
        handoverPlan(mine: current, offer: offer, allow: allow, verified: true,
                     gallery: gallery, received: landedItems)
    }
    await apply(replan)
    // The same function again for the receipt's tallies, against the cache the plan was
    // first computed from: counting what *this* transfer resolved, not what the union
    // happens to hold once everything else on the device is folded in.
    let landed = replan(mine)

    let receipt = HandoverReceipt(
        landed: arrived.count,
        bytes: bytes,
        held: landed.held.count,
        fromGallery: landed.fromGallery.count - arrived.count,
        withheld: landed.withheld.count,
        refused: landed.refused.count,
        requested: expected.count,
        countMismatch: landed.countMismatch,
        trouble: trouble,
        accountsMove: accountsMove
    )
    // Best effort: if the connection is already gone (the usual reason `trouble` is set),
    // the source simply reports what it sent. Failing to hand back a receipt must not undo
    // a transfer that actually landed.
    try? await writeJson(wire, receipt)
    onProgress(HandoverProgress(phase: trouble.isEmpty ? .done : .failed, bytesDone: bytes,
                                bytesTotal: total, items: arrived.count, itemsTotal: expected.count))
    return receipt
}

/// The bytes, item by item, until the end-of-items marker.
///
/// Deliberately **not** `ContactSession`'s `receiveRequested`, which deletes everything it
/// received when the connection drops: that is right for a reconcile, whose session returns
/// nothing on a throw and would otherwise leave files no timeline entry points at. A
/// handover is the opposite — a stopped transfer is supposed to leave a coherent smaller
/// library, so what arrived is reported through `onItem` as it lands and survives the throw.
///
/// Each item is written under a temporary name and moved into place only once every
/// declared byte has arrived, so nothing is visible under its real name half-written.
private func receiveHandoverItems(
    _ wire: ContactConnection,
    expected: Set<String>,
    kinds: [String: String],
    receivedFile: (String, String) -> URL,
    onBytes: (Int) -> Void,
    onItem: (String, String) -> Void
) async throws {
    while let header = try await readItemHeader(wire) {
        guard header.bytes >= 0, header.bytes <= maxItemBytes else {
            throw ContactWireError.refusedFrame(Int(clamping: header.bytes))
        }
        // A sender is free to put whatever it likes on the wire, and "you offered it and I
        // declined" must not become "you sent it anyway and I stored it".
        guard isSafeMediaId(header.id), expected.contains(header.id) else {
            try await wire.readBody(length: Int(header.bytes)) { _ in }
            continue
        }
        let destination = receivedFile(header.id, kinds[header.id] ?? StoredMedia.Kind.photo)
        let partial = destination.appendingPathExtension("part")
        try? FileManager.default.createDirectory(
            at: destination.deletingLastPathComponent(), withIntermediateDirectories: true
        )
        FileManager.default.createFile(atPath: partial.path, contents: nil)
        guard let handle = try? FileHandle(forWritingTo: partial) else {
            // The bytes are coming whether or not there is anywhere to put them, so they
            // have to be drained rather than abandoned mid-frame.
            try await wire.readBody(length: Int(header.bytes)) { _ in }
            continue
        }
        do {
            try await wire.readBody(length: Int(header.bytes)) {
                try handle.write(contentsOf: $0)
                onBytes($0.count)
            }
            try? handle.close()
            try? FileManager.default.removeItem(at: destination)
            try FileManager.default.moveItem(at: partial, to: destination)
            onItem(header.id, destination.path)
        } catch {
            try? handle.close()
            try? FileManager.default.removeItem(at: partial)
            throw error
        }
    }
}
