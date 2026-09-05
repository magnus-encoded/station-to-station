import UIKit
import UniformTypeIdentifiers

/// The share sheet's face of ticket parsing (#412).
///
/// It reads the PDF, parses it, drops the result in the inbox and says so. It
/// deliberately does **not** ask the person to confirm anything: confirming needs the
/// nights already on the **Line** to match against, and those live in the app's own
/// store, which this process must not open — ADR-0019. So the last thing it says is
/// where to finish.
///
/// UIKit rather than SwiftUI for one screen with one label and one button: a hosting
/// controller is a second framework loaded into a process that is memory-budgeted for
/// rasterizing a PDF page.
final class ShareViewController: UIViewController {

    private let status = UILabel()
    private let spinner = UIActivityIndicatorView(style: .medium)
    private let done = UIButton(type: .system)

    override func viewDidLoad() {
        super.viewDidLoad()
        buildInterface()
        Task { await run() }
    }

    private func run() async {
        guard let data = await pdfData() else {
            return finish("That doesn't look like a ticket PDF.")
        }
        let read = await Task.detached(priority: .userInitiated) {
            let extracted = TicketExtractor.extract(pdf: data)
            return parseTicket(qr: extracted.qr, blocks: extracted.blocks)
        }.value

        let ticket: Ticket
        switch read {
        case .ticket(let found): ticket = found
        case .nothingUsable: ticket = Ticket()
        }

        guard TicketInbox.deposit(ticket) else {
            return finish("Station to Station couldn't be reached on this install.")
        }
        finish(summary(read))
    }

    /// What was found, said plainly. A parse that read nothing says so here rather
    /// than in the app: the person is still holding the ticket, and "it read nothing"
    /// is worth knowing before they switch apps.
    private func summary(_ parse: TicketParse) -> String {
        guard case .ticket(let ticket) = parse else {
            return "Nothing could be read from this PDF. Open Station to Station to "
                + "add the night yourself."
        }
        var found: [String] = []
        if let artist = ticket.artist { found.append(artist) }
        if let venue = ticket.venue { found.append(venue) }
        if let date = ticket.date { found.append(shortDate(date)) }
        if ticket.qr != nil { found.append("a QR code") }
        return found.joined(separator: " · ")
            + "\n\nOpen Station to Station to put it on your line."
    }

    private func shortDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .none
        return formatter.string(from: date)
    }

    private func pdfData() async -> Data? {
        let items = (extensionContext?.inputItems as? [NSExtensionItem]) ?? []
        for item in items {
            for provider in item.attachments ?? [] {
                guard provider.hasItemConformingToTypeIdentifier(UTType.pdf.identifier)
                else { continue }
                if let data = await load(provider) { return data }
            }
        }
        return nil
    }

    /// The bytes, however the sender chose to vend them. Mail hands over a file URL and
    /// a wallet app hands over the data itself; a reader that only knew one of those
    /// would work from one app and silently not from the other.
    private func load(_ provider: NSItemProvider) async -> Data? {
        let type = UTType.pdf.identifier
        let direct: Data? = await withCheckedContinuation { continuation in
            provider.loadDataRepresentation(forTypeIdentifier: type) { data, _ in
                continuation.resume(returning: data)
            }
        }
        if let direct { return direct }
        return await withCheckedContinuation { continuation in
            provider.loadItem(forTypeIdentifier: type) { item, _ in
                guard let url = item as? URL else { return continuation.resume(returning: nil) }
                continuation.resume(returning: try? Data(contentsOf: url))
            }
        }
    }

    private func finish(_ message: String) {
        spinner.stopAnimating()
        status.text = message
        done.isHidden = false
    }

    @objc private func complete() {
        extensionContext?.completeRequest(returningItems: nil)
    }

    private func buildInterface() {
        // The app's own nocturnal theme, and its amber. Both are restated rather than
        // imported: the app's palette lives in SwiftUI in a target this one does not
        // link, and one screen is not worth sharing a colour file for.
        view.backgroundColor = UIColor(red: 0.06, green: 0.06, blue: 0.07, alpha: 1)
        let amber = UIColor(red: 0.906, green: 0.698, blue: 0.298, alpha: 1)

        let title = UILabel()
        title.text = "Ticket"
        title.font = .systemFont(ofSize: 12, weight: .semibold)
        title.textColor = amber
        title.textAlignment = .center

        status.text = "Reading the ticket…"
        status.font = .systemFont(ofSize: 15)
        status.textColor = .white
        status.numberOfLines = 0
        status.textAlignment = .center

        spinner.color = amber
        spinner.startAnimating()

        done.setTitle("Done", for: .normal)
        done.tintColor = amber
        done.isHidden = true
        done.addTarget(self, action: #selector(complete), for: .touchUpInside)

        let stack = UIStackView(arrangedSubviews: [title, spinner, status, done])
        stack.axis = .vertical
        stack.alignment = .center
        stack.spacing = 16
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            stack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 32),
            stack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -32),
        ])
    }
}
