import Foundation
import Network

/// The device half of a handover (#142): a listener on the old phone, a dialled
/// connection on the new one, and the `ContactConnection` both ends hand to
/// `HandoverSession`. The twin of Android's `AppViewModel.offerHandover`/`joinHandover`,
/// kept out of `AppModel` for the reason `ContactExchange` is: everything decidable lives
/// in the session and the plan, and this is only sockets and lifetimes.
///
/// **Not mDNS.** A Contact reconcile browses because it does not know who is there; a
/// handover already knows — the QR carries the address, the port, the certificate to pin
/// and the link key. Nothing is advertised, so nothing on the network learns that a phone
/// is being replaced.
///
/// One session at a time, by construction: a handover is a deliberate act on a screen, and
/// a second one arriving mid-transfer is a spoof attempt or a mistake either way.
@MainActor
final class HandoverExchange {

    private var listener: NWListener?
    private var connection: NWConnection?
    private var tls: ContactTlsIdentity?
    private var session: Task<Void, Never>?

    /// Cancelling the connection is the cancel: every suspension point in a session is a
    /// continuation wrapping an `NWConnection` callback that would otherwise wait forever
    /// for bytes, so cancelling the `Task` alone stops nothing.
    func stop() {
        session?.cancel()
        session = nil
        connection?.cancel()
        connection = nil
        listener?.cancel()
        listener = nil
        tls?.discard()
        tls = nil
        PhotoLibrary.clearReconcileOutbox()
    }

    /// The phone being replaced. Mints a session certificate and a link key, listens on a
    /// port the system picks, hands back the invite for the QR, and runs one handover.
    ///
    /// `manifest` is built by the caller from the tick list (`deviceManifest`), so nothing
    /// this function does can widen what was approved. `accounts`, likewise built by the
    /// caller (`AppModel.sendHandoverAccounts`) so the credential itself and the gated
    /// `clearSpotifyAuth` stay device-layer concerns, not this socket-and-lifetime file's.
    func offer(
        allow: Set<String>,
        manifest: @escaping () async -> HandoverManifest,
        accounts: @escaping (ContactConnection) async throws -> AccountsMove,
        mediaSource: @escaping (String) async -> URL?,
        invite: @MainActor @escaping (String) -> Void,
        progress: @MainActor @escaping (HandoverProgress) -> Void,
        finished: @MainActor @escaping (HandoverReceipt?, String?) -> Void
    ) {
        stop()
        guard let tls = ContactTlsIdentity.make() else {
            finished(nil, "This phone could not make a certificate to hand over with.")
            return
        }
        self.tls = tls
        guard let host = localLinkAddress() else {
            stop()
            finished(nil, "This phone is not on a network to hand over across.")
            return
        }

        var generated = Data(count: 32)
        guard generated.withUnsafeMutableBytes({
            SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!)
        }) == errSecSuccess else {
            stop()
            finished(nil, "This phone could not generate a key for the transfer.")
            return
        }
        let linkKey = generated

        guard let listener = try? NWListener(using: offeringHandoverParameters(tls),
                                             on: .any) else {
            stop()
            finished(nil, "This phone could not open a port to hand over on.")
            return
        }
        self.listener = listener

        let certificate = tls.certificate
        listener.stateUpdateHandler = { state in
            guard case .ready = state, let port = listener.port else { return }
            Task { @MainActor in
                invite(HandoverInvite(host: host, port: Int(port.rawValue),
                                      fingerprint: certFingerprint(certificate),
                                      linkKey: linkKey).uri)
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            Task { @MainActor in
                guard let self, self.connection == nil else { connection.cancel(); return }
                // One phone, one handover: stop listening the moment somebody answers, so
                // a second dial cannot arrive halfway through the first one's transfer.
                self.listener?.cancel()
                self.listener = nil
                self.connection = connection
                self.session = Task.detached(priority: .utility) {
                    defer { connection.cancel() }
                    guard await awaitReady(connection) else {
                        await MainActor.run { finished(nil, "That phone could not connect.") }
                        return
                    }
                    let wire = ContactConnection(connection: connection, ownCertificate: certificate)
                    do {
                        let receipt = try await runHandoverSource(
                            wire: wire,
                            linkKey: linkKey,
                            allow: allow,
                            manifest: await manifest(),
                            accounts: accounts,
                            mediaSource: mediaSource,
                            onProgress: { p in Task { @MainActor in progress(p) } }
                        )
                        await MainActor.run {
                            if let receipt { finished(receipt, nil) }
                            else { finished(nil, "That phone could not prove it read this code.") }
                        }
                    } catch {
                        await MainActor.run { finished(nil, handoverTrouble(error)) }
                    }
                }
            }
        }
        listener.start(queue: .global(qos: .utility))
    }

    /// The new phone. Dials what the QR named, pinned to the certificate it named, and
    /// runs the receiving half.
    func join(
        _ invite: HandoverInvite,
        mine: @escaping () async -> TimelineCache,
        gallery: @escaping () async -> [GalleryItem],
        /// Stores durably, then acks — see `AppModel.receiveHandoverAccounts` — and hands
        /// back whatever arrived so the receipt can say what became of it (#143 story 9).
        accounts: @escaping (ContactConnection) async throws -> AccountsPayload?,
        /// Writes the union and whatever else a landing needs — see `AppModel`, which also
        /// cuts the thumbnails off the plan this returns.
        apply: @escaping (@escaping (TimelineCache) -> HandoverPlan) async -> Void,
        progress: @MainActor @escaping (HandoverProgress) -> Void,
        finished: @MainActor @escaping (HandoverReceipt?, String?) -> Void
    ) {
        stop()
        let endpoint = NWEndpoint.hostPort(host: NWEndpoint.Host(invite.host),
                                           port: NWEndpoint.Port(rawValue: UInt16(invite.port)) ?? .any)
        let connection = NWConnection(to: endpoint,
                                      using: pinnedHandoverParameters(fingerprint: invite.fingerprint))
        self.connection = connection
        session = Task.detached(priority: .utility) {
            defer { connection.cancel() }
            guard await awaitReady(connection) else {
                await MainActor.run {
                    finished(nil, "That phone did not answer, or it presented a different certificate.")
                }
                return
            }
            let wire = ContactConnection(connection: connection, ownCertificate: Data())
            do {
                let receipt = try await runHandoverReceiver(
                    wire: wire,
                    linkKey: invite.linkKey,
                    accounts: accounts,
                    mine: await mine(),
                    gallery: await gallery(),
                    receivedFile: { id, kind in PhotoLibrary.receivedMediaFile(id: id, kind: kind) },
                    apply: { replan in await apply(replan) },
                    onProgress: { p in Task { @MainActor in progress(p) } }
                )
                await MainActor.run {
                    if let receipt { finished(receipt, nil) }
                    else { finished(nil, "That transfer did not verify, so nothing was written.") }
                }
            } catch {
                await MainActor.run { finished(nil, handoverTrouble(error)) }
            }
        }
    }
}

/// What went wrong, in words worth showing on a screen. The far end is a phone in the same
/// room, so the useful distinction is "it stopped" versus "it refused", not the framework's
/// error code.
func handoverTrouble(_ error: Error) -> String {
    if error is CancellationError { return "The transfer was stopped." }
    switch error {
    case ContactWireError.closedMidFrame:
        return "The connection dropped part-way. What arrived was kept."
    case ContactWireError.refusedFrame:
        return "That phone sent something this one refused to read."
    default:
        return "The transfer stopped: \(error.localizedDescription)"
    }
}

/// This device's own IPv4 address on the link it is using, for the QR to name.
///
/// Bonjour is deliberately not used here — see the note at the top of this file — and the
/// invite's shape is Android's, which carries an address and a port. Wi-Fi (`en`) first,
/// then anything else routable, so a phone on a hotspot still produces a working code.
func localLinkAddress() -> String? {
    var head: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&head) == 0, let first = head else { return nil }
    defer { freeifaddrs(head) }

    var fallback: String?
    for interface in sequence(first: first, next: { $0.pointee.ifa_next }) {
        let flags = Int32(interface.pointee.ifa_flags)
        guard flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0,
              interface.pointee.ifa_addr?.pointee.sa_family == UInt8(AF_INET)
        else { continue }
        var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        guard getnameinfo(interface.pointee.ifa_addr, socklen_t(interface.pointee.ifa_addr.pointee.sa_len),
                          &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0
        else { continue }
        let address = String(cString: host)
        let name = String(cString: interface.pointee.ifa_name)
        if name.hasPrefix("en") { return address }
        if fallback == nil { fallback = address }
    }
    return fallback
}
