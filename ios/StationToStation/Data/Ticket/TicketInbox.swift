import Foundation

/// One **Ticket** the Share Extension read, waiting for the app to do something
/// about it. See ADR-0019 for why the handover is a drop box and not a shared store.
struct TicketDeposit: Codable, Equatable, Identifiable, Sendable {
    var id: String = UUID().uuidString.lowercased()
    /// Epoch millis, so several tickets shared in a row are drained in the order they
    /// were shared rather than whatever order the directory lists.
    var depositedAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    var ticket: Ticket
}

/// The one-way drop box between the Share Extension and the app.
///
/// **The extension only ever writes and the app only ever reads and deletes.** That
/// asymmetry is the whole design: `TimelineStore` is a single JSON file serialised by
/// an in-process `actor`, which is no lock at all across two processes, so a second
/// writer would be a lost-update bug that only shows up as a night quietly missing.
/// Each deposit is a separate file with exactly one writer, and a rename is atomic, so
/// nothing here needs a lock. ADR-0019 has the argument in full.
///
/// **What is in the container is deliberately small.** The parse happens in the
/// extension, so the PDF itself — which carries a name, an order number and sometimes
/// a card fragment — is never written anywhere. Only the four facts and the QR payload
/// cross, and they are deleted the moment the app has read them.
enum TicketInbox {

    /// Must match the App Group on both targets' entitlements. Changing it strands
    /// whatever an already-installed extension has deposited.
    static let appGroup = "group.io.github.magnusencoded.stationtostation"

    /// Nil when the group is not provisioned — a build signed by an Apple ID without
    /// the App Groups capability, which is the ordinary case for a sideload. Callers
    /// must degrade rather than crash: the extension says it could not reach the app,
    /// and the app simply has nothing to drain.
    static var directory: URL? {
        guard let container = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: appGroup)
        else { return nil }
        let dir = container.appendingPathComponent("ticket-inbox", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// Writes one deposit. Returns false when there is no container to write into.
    ///
    /// `.part` first and renamed only once the bytes are down, because a reader in
    /// another process can list the directory mid-write; the rename is what makes an
    /// item appear complete or not at all.
    @discardableResult
    static func deposit(_ ticket: Ticket) -> Bool {
        guard let dir = directory else { return false }
        let deposit = TicketDeposit(ticket: ticket)
        guard let data = try? JSONEncoder().encode(deposit) else { return false }
        let partial = dir.appendingPathComponent("\(deposit.id).part")
        let final = dir.appendingPathComponent("\(deposit.id).json")
        do {
            // Protected: the container is in the device backup, and a ticket QR is a
            // credential for getting through a door.
            try data.write(to: partial, options: [.atomic, .completeFileProtection])
            try FileManager.default.moveItem(at: partial, to: final)
            return true
        } catch {
            try? FileManager.default.removeItem(at: partial)
            return false
        }
    }

    /// Everything waiting, oldest first, taken out of the box as it is read.
    ///
    /// A file that will not decode is deleted with the rest rather than left behind:
    /// one bad deposit that is retried forever would wedge every later ticket behind
    /// it, and there is nothing to recover from a half-parsed drop box.
    static func drain() -> [TicketDeposit] {
        guard let dir = directory,
              let files = try? FileManager.default.contentsOfDirectory(
                at: dir, includingPropertiesForKeys: nil)
        else { return [] }
        var deposits: [TicketDeposit] = []
        for file in files where file.pathExtension == "json" {
            if let data = try? Data(contentsOf: file),
               let deposit = try? JSONDecoder().decode(TicketDeposit.self, from: data) {
                deposits.append(deposit)
            }
            try? FileManager.default.removeItem(at: file)
        }
        return deposits.sorted { $0.depositedAt < $1.depositedAt }
    }
}
