import Foundation

/// The ticket files the app keeps (#568): the original a person shared, copied out of
/// the drop box at import when any of its **Admissions** did not redraw
/// (`redrawsExactly`). At the door the **Room** shows that file in place of a redraw it
/// could not make — the original *is* the barcode the venue issued. Tickets that redraw
/// keep nothing.
///
/// The files sit in the app's own Application Support, beside `timelines.json`, under
/// complete file protection: the same protection at rest as the **Admissions** that
/// name them (`StoredAdmission.original`). A name is a bare file name this type minted;
/// anything else resolves to nothing. The Kotlin twin is `TicketOriginals.kt`.
struct TicketOriginals {
    let directory: URL

    static let shared = TicketOriginals(directory:
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("ticket-originals", isDirectory: true))

    /// `source` copied in whole under a new name with its extension; that name, or nil
    /// where the copy failed.
    func keep(_ source: URL) -> String? {
        let ext = source.pathExtension.isEmpty ? "pdf" : source.pathExtension
        let name = "\(UUID().uuidString.lowercased()).\(ext)"
        guard let data = try? Data(contentsOf: source), !data.isEmpty else { return nil }
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try data.write(to: directory.appendingPathComponent(name), options: [.atomic, .completeFileProtection])
            return name
        } catch {
            return nil
        }
    }

    /// The kept file called `name`, or nil where there is none (a handover from another
    /// phone, or a name not minted here).
    func file(_ name: String?) -> URL? {
        guard let name, TicketInbox.isBareName(name) else { return nil }
        let url = directory.appendingPathComponent(name)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    /// Drops the kept file called `name`: a ticket discarded at the prompt, or a copy
    /// nothing came to name.
    func forget(_ name: String?) {
        guard let name, TicketInbox.isBareName(name) else { return }
        try? FileManager.default.removeItem(at: directory.appendingPathComponent(name))
    }
}
