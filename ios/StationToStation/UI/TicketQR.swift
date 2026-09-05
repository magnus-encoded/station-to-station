import SwiftUI

// The ticket, held up to be scanned (#414). Android draws the same bytes with the zxing
// dependency it already has; CoreImage is this platform's equivalent already in the box,
// so neither side takes a dependency for one barcode — `qrImage` is the Exchange's own
// generator, widened to bytes rather than copied.
//
// Whether this is drawn at all is `Room.qr`, from the fold both platforms read. Nothing
// here asks about check-ins or windows.

struct TicketQR: View {
    let payload: Data

    var body: some View {
        // The most redundancy the format offers, unlike the Exchange's default: a ticket
        // is read off a scratched phone in the dark by someone who wants the queue to
        // move, where a friend's card is scanned across a table.
        if let image = qrImage(payload, correction: "H") {
            // White is not a theme choice and does not follow the Room's ground: a
            // scanner reads contrast and that ground is nearly black. The margin is the
            // format's quiet zone rather than padding taste — a QR bled to its own edge
            // is one many readers will not see at all.
            Image(uiImage: image)
                .interpolation(.none)
                .resizable()
                .frame(width: 180, height: 180)
                .padding(12)
                .background(Color.white)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .accessibilityElement()
                .accessibilityLabel("Your ticket's QR code. Hold it up to be scanned.")
        }
    }
}
