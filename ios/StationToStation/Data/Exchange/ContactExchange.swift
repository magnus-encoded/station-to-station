import Foundation
import Network

/// A peer that vanishes mid-session — walks out of the room, drops off the WiFi — must not
/// leave a session waiting on bytes that are never coming. TCP keepalive is what notices,
/// and it is the closest `Network.framework` offers to Android's `soTimeout`.
///
/// Idle seconds rather than total, so a long video transfer is never what trips it, and
/// generous because the far end may still be hashing its own library before its manifest
/// can be written — normal, not stalled.
///
/// ponytail: this catches a peer that *left*, not one that is present and silent, which
/// Android's per-read timeout does catch. A live peer holding a connection open and
/// saying nothing costs one suspended task until the Exchange screen closes and `stop()`
/// cancels it — bounded by the screen, which is the whole scope of this feature anyway.
/// If that ever matters, give each session an overall deadline rather than reaching for a
/// per-read one that does not exist here.
private let keepaliveIdleSeconds = 30
private let keepaliveProbes = 2
private let keepaliveIntervalSeconds = 5

/// One device's whole participation in #265 while the Exchange screen is open: advertise
/// and browse over the same WiFi via `ContactPeers`, accept or open a TLS connection for
/// whoever answers, and run `runContactSession` over it. The twin of Android's
/// `exchange/ContactExchange.kt`.
///
/// **Screen-scoped, not a background service** — no new permission beyond the local-network
/// one, no notification, no battery question to answer. `start`/`stop` sit on the same
/// `.task`/`.onDisappear` edge that already drives the BLE `ExchangeSession`. Android puts
/// its equivalent on the app's foreground lifecycle instead; iOS's real trigger is
/// reachable on its own, so the narrower scope is free here.
///
/// Each discovered peer is dialed at most once per `start()` — `ContactPeers` deduplicates —
/// so a device that keeps answering mDNS queries does not get reconciled with on every beacon.
@MainActor
final class ContactExchange {

    private let contactKeys: () async -> [String]
    private let manifest: () async -> HandoverManifest
    private let mine: () async -> TimelineCache
    private let gallery: () async -> [GalleryItem]
    private let onLanded: ([String: [StoredMedia]]) async -> Void

    init(contactKeys: @escaping () async -> [String],
         manifest: @escaping () async -> HandoverManifest,
         mine: @escaping () async -> TimelineCache,
         gallery: @escaping () async -> [GalleryItem],
         onLanded: @escaping ([String: [StoredMedia]]) async -> Void) {
        self.contactKeys = contactKeys
        self.manifest = manifest
        self.mine = mine
        self.gallery = gallery
        self.onLanded = onLanded
    }

    private let peers = ContactPeers()
    private var listener: NWListener?
    private var tls: ContactTlsIdentity?
    /// Each running session with the connection it is running over. **Both** are held, not
    /// just the task: cancelling a `Task` only takes effect where it is checked, and every
    /// suspension point in a session is a continuation wrapping an `NWConnection` callback
    /// that will happily wait forever for bytes. Cancelling the connection is what actually
    /// stops one, by making the read it is parked on fail.
    private var sessions: [(task: Task<Void, Never>, connection: NWConnection)] = []

    // Manifest and gallery hashing walk the whole library. Computed once per start(), not
    // once per discovered peer, so several Contacts on the same WiFi do not each trigger a
    // full re-hash of every photo and video.
    //
    // The timeline itself is deliberately *not* cached alongside them, matching Android:
    // it is what a plan diffs against, so a session running after another one landed media
    // must see what landed, or it re-requests bytes that are already here.
    private var warmup: Task<(HandoverManifest, [GalleryItem]), Never>?

    /// Starting the listener and the browser is what raises iOS's local-network prompt.
    /// The caller decides whether there is anything worth searching for — see
    /// `ExchangeView`, which only calls this once a Contact with a key exists.
    func start() {
        if listener != nil { return }
        guard let tls = ContactTlsIdentity.make() else { return }
        self.tls = tls

        warmup = Task.detached(priority: .utility) { [manifest, gallery] in
            await (manifest(), gallery())
        }

        guard let listener = try? NWListener(using: parameters(tls)) else {
            tls.discard()
            self.tls = nil
            return
        }
        // A fixed, meaningless instance name: the advertisement says "a Station to Station
        // device is here" and nothing else. The device's own name — which is what Bonjour
        // would publish if this were left nil — is somebody's first name in practice.
        listener.service = NWListener.Service(name: "station-to-station",
                                              type: ContactPeers.serviceType)
        listener.serviceRegistrationUpdateHandler = { [peers] change in
            // Bonjour renames on collision, so the name asked for and the name registered
            // are not always the same string. This is the registered one.
            if case let .add(endpoint) = change, case let .service(name, _, _, _) = endpoint {
                peers.registered(name)
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            Task { @MainActor in self?.run(connection, isServer: true) }
        }
        listener.start(queue: .global(qos: .utility))
        self.listener = listener

        peers.deliverTo { [weak self] endpoint in
            Task { @MainActor in
                guard let self, let tls = self.tls else { return }
                self.run(NWConnection(to: endpoint, using: self.parameters(tls)), isServer: false)
            }
        }
        peers.start()
    }

    func stop() {
        // Nil'd before anything else, and `tls` is cleared below, so a discovery already in
        // flight on the browse queue finds nothing to dial by the time it reaches the
        // main actor.
        peers.deliverTo(nil)
        peers.stop()
        listener?.cancel()
        listener = nil
        for session in sessions {
            session.task.cancel()
            session.connection.cancel()
        }
        sessions = []
        warmup?.cancel()
        warmup = nil
        tls?.discard()
        tls = nil
        PhotoLibrary.clearReconcileOutbox()
    }

    /// Both ends accept whatever certificate the other presents, exactly as Android's
    /// `AcceptAnyTrustManager` does: mDNS announces presence rather than identity, so
    /// there is nothing to pin before the handshake. Trust is established afterwards, by
    /// `mutualContactAuth`, over a signature bound to this certificate's fingerprint.
    ///
    /// Both ends also *present* one, which is not optional: the fingerprint has nothing to
    /// bind to unless there is a certificate on the wire in both directions.
    ///
    /// **This is what makes iOS↔Android work at all (#267), and it is load-bearing.** The
    /// two platforms do not present the same kind of certificate and never will: Android
    /// hands over a durable `AndroidKeyStore` one that outlives the app, iOS mints a fresh
    /// per-session one here and throws it away in `stop()`. They interoperate only because
    /// **trust never comes from the certificate** — it comes from a signature over that
    /// certificate's fingerprint, made with the Contact key the two swapped in person.
    ///
    /// So: anything that later pins a certificate, remembers one between sessions, or ties
    /// one to the identity key ends interop silently. It would still pass every iOS↔iOS
    /// test, because on iOS both ends mint the same way. The same warning is on Android's
    /// `contactSessionContext`.
    private func parameters(_ tls: ContactTlsIdentity) -> NWParameters {
        let options = NWProtocolTLS.Options()
        let security = options.securityProtocolOptions
        if let identity = sec_identity_create(tls.identity) {
            sec_protocol_options_set_local_identity(security, identity)
        }
        sec_protocol_options_set_peer_authentication_required(security, true)
        sec_protocol_options_set_verify_block(security, { _, _, complete in
            complete(true)
        }, .global(qos: .utility))

        let tcp = NWProtocolTCP.Options()
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = keepaliveIdleSeconds
        tcp.keepaliveCount = keepaliveProbes
        tcp.keepaliveInterval = keepaliveIntervalSeconds
        let parameters = NWParameters(tls: options, tcp: tcp)
        parameters.includePeerToPeer = true
        return parameters
    }

    private func run(_ connection: NWConnection, isServer: Bool) {
        guard let ownCertificate = tls?.certificate else { connection.cancel(); return }
        let session = Task.detached(priority: .utility) { [warmup, contactKeys, mine, onLanded] in
            defer { connection.cancel() }
            guard let peerCertificate = await ready(connection) else { return }
            let candidates = await contactKeys()
            if candidates.isEmpty { return }
            guard let warmed = await warmup?.value else { return }
            let (manifest, gallery) = warmed
            let cache = await mine()

            var refById: [String: String] = [:]
            for item in cache.gigMedia.values.flatMap({ $0 }) { refById[item.id] = item.ref }

            let landing = try? await runContactSession(
                wire: ContactConnection(connection: connection, ownCertificate: ownCertificate),
                isServer: isServer,
                peerCertificate: peerCertificate,
                candidates: candidates,
                myManifest: manifest,
                mine: cache,
                gallery: gallery,
                mediaSource: { id in
                    guard let ref = refById[id] else { return nil }
                    return await PhotoLibrary.reconcileExport(assetId: ref, mediaId: id)
                },
                receivedFile: { id, kind in PhotoLibrary.receivedMediaFile(id: id, kind: kind) },
                // Straight to the timeline: a **Note** has no bytes to fetch and no
                // thumbnail to cut, so there is nothing between arriving and landing.
                landNotes: { notes in
                    if Task.isCancelled { return }
                    await onLanded(notes)
                }
            )
            guard let landing, !landing.isEmpty else { return }
            // The screen closed while this was in flight. `stop()` cancels the connection
            // too, so a session almost always dies at a read — but once the bytes are all
            // here there is no read left to fail, and nothing about #265 is supposed to
            // write to the timeline after the screen is gone.
            if Task.isCancelled { return }
            // The grid draws from the durable thumbnail tier, never from `ref`, so an
            // item that skipped this step would land as a blank cell (#98). A **Note** has
            // no bytes and no cell to fill — there is nothing to cut a thumbnail from.
            for item in landing.values.flatMap({ $0 }) where !item.ref.isEmpty {
                await PhotoLibrary.writeReconcileTiers(mediaId: item.id, ref: item.ref)
            }
            await onLanded(landing)
        }
        sessions.append((session, connection))
    }
}

/// Waits for the handshake and hands back the certificate the *peer* presented — the one
/// they signed the fingerprint of on their side. Nil if the connection never came up.
private func ready(_ connection: NWConnection) async -> Data? {
    let established = await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
        let answered = OneShot()
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                if answered.claim() { continuation.resume(returning: true) }
            case .failed, .cancelled:
                if answered.claim() { continuation.resume(returning: false) }
            case .waiting:
                // `.waiting` is not a failure to `Network.framework` — it means "cannot
                // connect right now, will keep retrying", and without this the task parks
                // there indefinitely. On a LAN that is a peer who walked out between
                // announcing themselves and being dialed; there is nothing to wait for,
                // and giving up costs at most one reconcile until the screen is reopened.
                if answered.claim() { continuation.resume(returning: false) }
            default:
                break
            }
        }
        connection.start(queue: .global(qos: .utility))
    }
    guard established,
          let metadata = connection.metadata(definition: NWProtocolTLS.definition)
            as? NWProtocolTLS.Metadata
    else { return nil }

    var leaf: Data?
    _ = sec_protocol_metadata_access_peer_certificate_chain(metadata.securityProtocolMetadata) { certificate in
        // The chain is walked leaf-first and there is only ever one here — a self-signed
        // session certificate has nothing to chain to.
        if leaf == nil {
            leaf = SecCertificateCopyData(sec_certificate_copy_ref(certificate).takeRetainedValue()) as Data
        }
    }
    return leaf
}

/// A continuation resumed twice is a crash, and a state handler can fire again.
private final class OneShot {
    private let lock = NSLock()
    private var done = false
    func claim() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if done { return false }
        done = true
        return true
    }
}
