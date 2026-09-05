import AVFoundation
import CoreImage.CIFilterBuiltins
import SwiftUI
import UIKit

// One way to meet someone. The radio runs behind this — BLE, and QR when it
// sulks — and the screen never says which found a person. The moment is one
// thing: a row appears with a real name, you tap, "Connecting with dizzi90",
// you are contacts. Ported from Android's ExchangeScreen.

private let ground = Color(red: 0x0E / 255, green: 0x0B / 255, blue: 0x14 / 255)
private let raised = Color(red: 0x17 / 255, green: 0x12 / 255, blue: 0x1F / 255)
private let ink = Color(red: 0xED / 255, green: 0xE9 / 255, blue: 0xF2 / 255)
private let muted = Color(red: 0x8B / 255, green: 0x82 / 255, blue: 0x99 / 255)
private let faint = Color(red: 0x5A / 255, green: 0x53 / 255, blue: 0x68 / 255)
private let lineLit = Color(red: 0x4A / 255, green: 0x3F / 255, blue: 0x63 / 255)
private let amber = Color(red: 0xE7 / 255, green: 0xB2 / 255, blue: 0x4C / 255)
private let slate = Color(red: 0x6D / 255, green: 0x7E / 255, blue: 0x9B / 255)

// The QR affordance is revealed on a timer, not immediately: showing it too early
// reads as "the radio gave up" when it hasn't. A quiet "use a code" once a couple
// of seconds pass with nobody found, primary once the radio has clearly missed
// its budget — both without stopping the scan.
private let qrOfferAfter: TimeInterval = 2.5
private let qrPrimaryAfter: TimeInterval = 7

struct ExchangeView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var nav: Nav
    @StateObject private var session = ExchangeSession()

    @State private var connectingWith: String?
    @State private var qrOffered = false
    @State private var qrPrimaryDue = false
    @State private var showCode = false
    @State private var cardURL: URL?

    private var qrPrimary: Bool {
        qrPrimaryDue && session.peers.isEmpty && connectingWith == nil
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                if session.bluetoothDenied {
                    note("Bluetooth is off for this app, so nobody can find you over the "
                        + "air. A code still works.")
                }
                if let connectingWith {
                    connecting(connectingWith)
                } else if qrPrimary {
                    note("No one turned up yet. Show your code, or scan theirs.")
                    qrExchange
                } else {
                    looking
                }

                // Revealing the code is not giving up: it sits alongside the live list.
                if qrOffered && !qrPrimary && connectingWith == nil {
                    if showCode {
                        qrExchange
                        Button("Hide code") { showCode = false }
                            .font(.system(size: 14)).tint(faint).padding(.top, 6)
                    } else {
                        Button("Show my code / scan theirs") { showCode = true }
                            .buttonStyle(.bordered).tint(amber).padding(.top, 20)
                    }
                }
                Spacer(minLength: 24)
            }
            .padding(.horizontal, 24)
        }
        .background(ground.ignoresSafeArea())
        .navigationTitle("Connect a timeline")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            // Opening this screen is the user saying they want to be found; the
            // Bluetooth prompt comes from starting the radios, here and nowhere else.
            // The peer tapped, not me: their card arrived over the write
            // characteristic, and it lands exactly where a tap lands.
            session.onFriendReceived = { friend in
                Task { @MainActor in land(friend) }
            }
            if let card = await model.myProbeCard() { session.start(card: card) }
            cardURL = await model.myCardURL()
            // #265: the same screen, a second radio. Only started once a Contact with a
            // key exists — starting it is what raises the local-network prompt, and
            // asking a first-time user for a permission with nothing to find would be
            // asking for nothing. Denial costs only this: BLE, QR and the Pointer path
            // are untouched, which is why nothing here is reported to the screen.
            await model.startContactExchange()
        }
        .task {
            try? await Task.sleep(nanoseconds: UInt64(qrOfferAfter * 1_000_000_000))
            qrOffered = true
            try? await Task.sleep(nanoseconds: UInt64((qrPrimaryAfter - qrOfferAfter) * 1_000_000_000))
            qrPrimaryDue = true
        }
        .onDisappear {
            session.stop()
            // Both radios stop together. Nothing about #265 outlives this screen: no
            // background service, no listener left advertising, no exported bytes left
            // in the outbox.
            model.stopContactExchange()
        }
    }

    /// The ambient "looking around you" state and the live list.
    private var looking: some View {
        VStack(spacing: 0) {
            note("Stand next to someone with the app open. When they appear, add them "
                + "and your timelines weave together.")
            Radar()
                .padding(.vertical, 24)
            if session.peers.isEmpty {
                Text("Looking for people around you\u{2026}")
                    .font(.system(size: 13)).foregroundStyle(faint)
            } else {
                Text("NEARBY")
                    .font(.system(size: 11, weight: .semibold)).kerning(1.5)
                    .foregroundStyle(faint)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.bottom, 8)
                ForEach(session.peers) { peer in
                    peerRow(peer)
                }
            }
        }
    }

    /// Row -> "Connecting with dizzi90" -> connected: the middle beat, 200ms or 2s.
    private func connecting(_ name: String) -> some View {
        VStack(spacing: 20) {
            ProgressView().tint(amber)
            Text("Connecting with \(name)")
                .font(.system(size: 18, design: .serif)).foregroundStyle(ink)
        }
        .padding(.vertical, 60)
    }

    private func peerRow(_ peer: ExchangePeer) -> some View {
        HStack(spacing: 12) {
            ZStack {
                Circle().strokeBorder(slate, lineWidth: 1.5)
                Text(peer.name.prefix(1).uppercased())
                    .font(.system(size: 15, design: .serif)).foregroundStyle(slate)
            }
            .frame(width: 34, height: 34)
            VStack(alignment: .leading, spacing: 2) {
                Text(peer.name).font(.system(size: 16, design: .serif)).foregroundStyle(ink)
                // Only shown once the card is in hand; a BLE peer is a name until then.
                if let user = peer.setlistfm {
                    Text("@\(user)").font(.system(size: 12)).foregroundStyle(muted)
                }
            }
            Spacer()
            Text("Add \u{203A}").font(.system(size: 13, weight: .semibold)).foregroundStyle(amber)
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 12).fill(raised))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(lineLit, lineWidth: 1))
        .contentShape(Rectangle())
        .onTapGesture { tap(peer) }
        .padding(.bottom, 8)
    }

    @MainActor
    private func tap(_ peer: ExchangePeer) {
        connectingWith = peer.name
        session.connect(peer) { friend in
            Task { @MainActor in
                connectingWith = nil
                guard let friend else {
                    // A failure leaves the radios running and reveals the code, so
                    // the tap does not land on a dead end.
                    qrOffered = true
                    showCode = true
                    return
                }
                land(friend)
            }
        }
    }

    @MainActor
    private func addScanned(_ scanned: String) {
        guard let url = URL(string: scanned), let friend = friendFromURL(url) else { return }
        // A scanned code carries no key, so this can only ever make a **Followed line**.
        land(friend)
    }

    /// The landing an Exchange ends on, whichever door the card came through: persist,
    /// open the weave, and leave the screen — holding a card is the end of looking.
    ///
    /// A card that would change someone already held has written nothing and left a
    /// question standing (#188). Landing anyway would report a swap that did not happen,
    /// and leaving the screen stops both radios mid-**Exchange** — which is exactly what
    /// a hostile write wants. The row stays, so the same person can be tapped again.
    @MainActor
    private func land(_ friend: Friend) {
        model.addFriend(friend)
        guard model.state.friendConflict == nil else { return }
        model.setZoomedOut(true)
        nav.popToRoot()
    }

    /// The QR fallback with role assignment: if both phones drop to "here's a code"
    /// nobody is scanning, so one side shows and the other toggles to scan.
    @ViewBuilder
    private var qrExchange: some View {
        if let cardURL {
            QrExchangeBody(cardURL: cardURL, username: model.state.mySetlistFmUser) { scanned in
                Task { @MainActor in addScanned(scanned) }
            }
        } else {
            VStack(spacing: 10) {
                Text("Set your setlist.fm username to make your card.")
                    .font(.system(size: 13)).foregroundStyle(muted)
                Button("Add your username") { nav.push(.friends) }
                    .buttonStyle(.bordered).tint(amber)
            }
            .padding(.vertical, 10)
        }
    }

    private func note(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 13)).foregroundStyle(muted)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 20)
    }
}

private struct QrExchangeBody: View {
    let cardURL: URL
    let username: String
    let onScanned: (String) -> Void
    @State private var scanning = false

    var body: some View {
        VStack(spacing: 10) {
            if scanning {
                Text("Point the camera at their code.")
                    .font(.system(size: 13)).foregroundStyle(muted)
                QRScanner(onCode: onScanned)
                    .frame(height: 260)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
            } else if let qr = qrImage(cardURL.absoluteString) {
                Image(uiImage: qr)
                    .interpolation(.none)
                    .resizable()
                    .frame(width: 220, height: 220)
                    .padding(14)
                    .background(Color.white)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                Text("@\(username)")
                    .font(.system(size: 14, weight: .semibold)).foregroundStyle(ink)
            }
            Button(scanning ? "Show my code instead" : "I'll scan theirs instead") {
                scanning.toggle()
            }
            .font(.system(size: 14)).tint(amber)
            // The friend deep link is registered, so a link works wherever a code can't.
            ShareLink(item: cardURL) { Text("Share a link instead").font(.system(size: 14)) }
                .tint(amber)
        }
        .padding(.top, 8)
    }
}

/// A slow pulse to say the app is listening for other phones.
private struct Radar: View {
    @State private var pulse: CGFloat = 0

    var body: some View {
        ZStack {
            Circle()
                .strokeBorder(amber, lineWidth: 1.5)
                .frame(width: 60 + pulse * 60, height: 60 + pulse * 60)
                .opacity((1 - pulse) * 0.5)
            ZStack {
                Circle().fill(amber.opacity(0.16))
                Circle().strokeBorder(amber, lineWidth: 1.5)
                Text("\u{25E6}").font(.system(size: 20)).foregroundStyle(amber)
            }
            .frame(width: 56, height: 56)
        }
        .frame(width: 120, height: 120)
        .onAppear {
            withAnimation(.linear(duration: 1.8).repeatForever(autoreverses: false)) { pulse = 1 }
        }
    }
}

/// One context rather than one per call. It owns GPU resources, and a **Room** with a
/// ticket in it re-renders far more often than the Exchange screen ever did.
private let qrContext = CIContext()

/// CoreImage does QR codes; no dependency needed for either half of the fallback.
/// Shared with the handover screen, which shows one for a quite different reason.
func qrImage(_ text: String) -> UIImage? { qrImage(Data(text.utf8)) }

/// The same generator over bytes, for a payload that was never text to begin with —
/// a ticket's barcode is whatever the venue encoded (#414), and rounding it through a
/// `String` would mangle any of it that is not UTF-8.
///
/// Nil for an empty payload: CIQRCodeGenerator will happily encode nothing, and a code
/// that scans to an empty string is worse at a door than no code at all.
func qrImage(_ payload: Data, correction: String = "M") -> UIImage? {
    guard !payload.isEmpty else { return nil }
    let filter = CIFilter.qrCodeGenerator()
    filter.message = payload
    filter.correctionLevel = correction
    guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)),
          let cg = qrContext.createCGImage(output, from: output.extent) else { return nil }
    return UIImage(cgImage: cg)
}

// --- The scanner half: AVCaptureMetadataOutput, .qr, and nothing else. ---

private struct QRScanner: UIViewControllerRepresentable {
    let onCode: (String) -> Void

    func makeUIViewController(context: Context) -> ScannerController {
        let controller = ScannerController()
        controller.onCode = onCode
        return controller
    }

    func updateUIViewController(_ controller: ScannerController, context: Context) {}
}

final class ScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onCode: ((String) -> Void)?
    private let session = AVCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else { return }
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { return }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        view.layer.addSublayer(layer)
        preview = layer
        startCapture()
    }

    private func startCapture() {
        let capture = session
        DispatchQueue.global(qos: .userInitiated).async { capture.startRunning() }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        let capture = session
        DispatchQueue.global(qos: .userInitiated).async { capture.stopRunning() }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput,
                        didOutput metadataObjects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        guard let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let value = object.stringValue else { return }
        let capture = session
        DispatchQueue.global(qos: .userInitiated).async { capture.stopRunning() }
        onCode?(value)
    }
}
