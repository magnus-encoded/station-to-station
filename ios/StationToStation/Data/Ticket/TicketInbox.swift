import Foundation

/// One **Ticket** the Share Extension read, waiting for the app to do something
/// about it. See ADR-0020 for why the handover is a drop box and not a shared store.
struct TicketDeposit: Codable, Equatable, Identifiable, Sendable {
    var id: String = UUID().uuidString.lowercased()
    /// Epoch millis, so several tickets shared in a row are drained in the order they
    /// were shared rather than whatever order the directory lists.
    var depositedAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    var ticket: Ticket
    /// The shared file itself, beside the deposit in the box (#568): its file name
    /// there, or nil where none was written. The app keeps it only when an
    /// **Admission** fails the redraw check, and it goes with the deposit either way.
    var original: String? = nil
}

/// The one-way drop box between the Share Extension and the app.
///
/// **The extension only ever writes and the app only ever reads and deletes.** That
/// asymmetry is the whole design: `TimelineStore` is a single JSON file serialised by
/// an in-process `actor`, which is no lock at all across two processes, so a second
/// writer would be a lost-update bug that only shows up as a night quietly missing.
/// Each deposit is a separate file with exactly one writer, and a rename is atomic, so
/// nothing here needs a lock. ADR-0020 has the argument in full.
///
/// **What is in the container is deliberately small, and short-lived.** The four
/// facts, which readings backed them, and the **Admissions** cross — never the
/// barcode's crop. The PDF itself (a name, an order number, sometimes a card fragment)
/// crosses beside them since #568, because only the app can tell whether a redraw
/// fails and the file is then the only thing that gets the person in. All of it is
/// under complete file protection and deleted the moment what the app made of it is
/// on disk; the app keeps the PDF, in its own store, only for a ticket that needs it.
enum TicketInbox {

    /// Must match the App Group on both targets' entitlements. Changing it strands
    /// whatever an already-installed extension has deposited.
    static let appGroup = "group.io.github.magnusencoded.stationtostation"

    /// The groups to try, in order: the declared one, then `group.<host app id>`.
    ///
    /// A re-signing sideloader renames the bundle id and the App Group the same way —
    /// Sideloader appends the team id to both, so the installed app is
    /// `….stationtostation.VHZW7G33CV` and holds `group.….stationtostation.VHZW7G33CV`.
    /// The declared name is then one the process is not entitled to, while the name
    /// derived from the host app's *installed* id is the one it holds. On a normally
    /// signed build the two candidates are the same string.
    ///
    /// Inside the extension the bundle id is the host's plus one component
    /// (`.ticketshare`), so that component is dropped to reach the host's.
    static func appGroupCandidates(bundleIdentifier: String?, isExtension: Bool) -> [String] {
        var candidates = [appGroup]
        if var host = bundleIdentifier {
            if isExtension, let dot = host.lastIndex(of: ".") { host = String(host[..<dot]) }
            let derived = "group.\(host)"
            if derived != appGroup { candidates.append(derived) }
        }
        return candidates
    }

    /// Nil when no group is provisioned — a build signed by an Apple ID without the
    /// App Groups capability. Callers must degrade rather than crash: the extension
    /// says it could not reach the app, and the app simply has nothing to drain.
    static var directory: URL? {
        let candidates = appGroupCandidates(
            bundleIdentifier: Bundle.main.bundleIdentifier,
            isExtension: Bundle.main.bundleURL.pathExtension == "appex"
        )
        guard let container = candidates.lazy.compactMap({
            FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: $0)
        }).first
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
    ///
    /// `original` is the shared file's bytes (#568), written first under its own name
    /// so a deposit that appears always has its file. One that cannot be written costs
    /// only the file: the ticket still crosses, and the app asks as it did before.
    @discardableResult
    static func deposit(_ ticket: Ticket, original: Data? = nil, extension ext: String = "pdf",
                        in box: URL? = directory) -> Bool {
        guard let dir = box else { return false }
        var deposit = TicketDeposit(ticket: ticket)
        if let original, !original.isEmpty {
            let name = "\(deposit.id).\(ext)"
            // Protected: the container is in the device backup, and a ticket is a
            // credential for getting through a door.
            if (try? original.write(to: dir.appendingPathComponent(name),
                                    options: [.atomic, .completeFileProtection])) != nil {
                deposit.original = name
            }
        }
        let partial = dir.appendingPathComponent("\(deposit.id).part")
        let final = dir.appendingPathComponent("\(deposit.id).json")
        do {
            let data = try JSONEncoder().encode(deposit)
            try data.write(to: partial, options: [.atomic, .completeFileProtection])
            try FileManager.default.moveItem(at: partial, to: final)
            return true
        } catch {
            try? FileManager.default.removeItem(at: partial)
            if let name = deposit.original { try? FileManager.default.removeItem(at: dir.appendingPathComponent(name)) }
            return false
        }
    }

    /// Where a deposit's shared file is in the box, or nil where it has none.
    static func originalURL(_ deposit: TicketDeposit, in box: URL? = directory) -> URL? {
        guard let dir = box, let name = deposit.original, isBareName(name) else { return nil }
        let url = dir.appendingPathComponent(name)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    static func isBareName(_ name: String) -> Bool {
        !name.isEmpty && !name.contains("/") && !name.hasPrefix(".")
    }

    /// Everything waiting, oldest first. **Read, not taken**: each stays in the box until
    /// the app has done something durable with it and calls `remove` (the #441 review).
    /// Routing runs a Vision pass per Admission, and a ticket taken out before that and
    /// killed during it was a ticket lost.
    ///
    /// A file that will not decode is deleted rather than left behind: one bad deposit
    /// that is retried forever would wedge every later ticket behind it, and there is
    /// nothing to recover from a half-parsed drop box.
    static func pending(in box: URL? = directory) -> [TicketDeposit] {
        guard let dir = box,
              let files = try? FileManager.default.contentsOfDirectory(
                at: dir, includingPropertiesForKeys: nil)
        else { return [] }
        var deposits: [TicketDeposit] = []
        for file in files where file.pathExtension == "json" {
            if let data = try? Data(contentsOf: file),
               let deposit = try? JSONDecoder().decode(TicketDeposit.self, from: data) {
                deposits.append(deposit)
            } else {
                remove(file.deletingPathExtension().lastPathComponent, in: dir)
            }
        }
        return deposits.sorted { $0.depositedAt < $1.depositedAt }
    }

    /// One deposit out of the box, once what it became is on disk: minted, attached, or
    /// answered at the prompt. Named by id, as `deposit` names the file.
    ///
    /// Its shared file goes with it: kept by now in the app's own store if it was needed.
    static func remove(_ id: String, in box: URL? = directory) {
        guard let dir = box else { return }
        if let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) {
            for file in files where file.pathExtension != "json"
                && file.deletingPathExtension().lastPathComponent == id {
                try? FileManager.default.removeItem(at: file)
            }
        }
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("\(id).json"))
    }
}
