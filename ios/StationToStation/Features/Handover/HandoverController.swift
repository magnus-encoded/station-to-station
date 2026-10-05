import Foundation

@MainActor
final class HandoverController {
    unowned let host: StateHost
    private let settings: Settings
    private let timelines: TimelineStore
    private let spotify: SpotifyClient
    private let loadTimeline: () -> Void

    init(host: StateHost, settings: Settings, timelines: TimelineStore, spotify: SpotifyClient,
         loadTimeline: @escaping () -> Void) {
        self.host = host
        self.settings = settings
        self.timelines = timelines
        self.spotify = spotify
        self.loadTimeline = loadTimeline
    }

    private lazy var handoverExchange = HandoverExchange()

    /// Media id → the asset id its bytes live under, filled in when the manifest is built
    /// and read when the far end asks for an item. Held rather than re-derived per item:
    /// the alternative is loading the whole cache once per requested photograph.
    private var handoverRefs: [String: String] = [:]

    /// The old phone: listen, show the code, and hand over exactly what was ticked.
    ///
    /// The manifest is built *after* somebody connects rather than before the code is
    /// shown, because hashing the library walks every keepsake — the code should be on
    /// screen while that happens, not after it.
    func offerHandover(_ allow: Set<String>) {
        host.state.handover = HandoverUi(role: .source)
        handoverExchange.offer(
            allow: allow,
            manifest: { [timelines, weak self] in
                let cache = await timelines.load()
                var refs: [String: String] = [:]
                for item in cache.gigMedia.values.flatMap({ $0 }) { refs[item.id] = item.ref }
                await self?.rememberHandoverRefs(refs)
                let identities = await self?.myIdentities() ?? Identities()
                return await hashedDeviceManifest(cache, allow: allow, identities: identities)
            },
            accounts: { [weak self] wire in
                guard let self else { return .notOffered }
                return try await self.sendHandoverAccounts(wire, payload: await self.accountsPayload(allow))
            },
            mediaSource: { [weak self] id in
                guard let self, let ref = await self.handoverRef(id) else { return nil }
                return await PhotoLibrary.reconcileExport(assetId: ref, mediaId: id)
            },
            invite: { [weak self] uri in self?.host.state.handover.inviteUri = uri },
            progress: { [weak self] p in self?.host.state.handover.progress = p },
            finished: { [weak self] receipt, trouble in
                self?.host.state.handover.receipt = receipt
                self?.host.state.handover.error = trouble
            }
        )
    }

    /// The new phone, from the code the old one is showing. A link that is not a handover
    /// invite is not this screen's business and is left alone.
    func joinHandover(_ url: URL) {
        guard let invite = parseHandoverInvite(url.absoluteString) else { return }
        host.state.handover = HandoverUi(role: .receiver)
        handoverExchange.join(
            invite,
            mine: { [timelines] in await timelines.load() },
            gallery: { [timelines] in
                let windows = await timelines.load().gigs.values
                    .compactMap { photoWindow(gigDate: $0.date) }
                return await PhotoLibrary.galleryItems(dates: windows)
            },
            accounts: { [weak self] wire in try await self?.receiveHandoverAccounts(wire) },
            apply: { [timelines] replan in
                let written = await timelines.applyHandover(replan)
                // The grid draws from the durable thumbnail tier and never from `ref`
                // (#98), so an item that skipped this would land as a blank cell. Both
                // ways an item can land need it: bytes that came over the wire, and a
                // hash match resolved against my own library under the sender's media id.
                for (id, ref) in written.fromGallery where !ref.isEmpty {
                    await PhotoLibrary.writeReconcileTiers(mediaId: id, ref: ref)
                }
            },
            progress: { [weak self] p in self?.host.state.handover.progress = p },
            finished: { [weak self] receipt, trouble in
                self?.host.state.handover.receipt = receipt
                self?.host.state.handover.error = trouble
                self?.loadTimeline()
            }
        )
    }

    /// Stops whatever is running. Cancelling is closing the connection — see
    /// `HandoverExchange` — and what already landed stays landed.
    func cancelHandover() {
        handoverExchange.stop()
        if host.state.handover.receipt == nil && host.state.handover.error == nil {
            host.state.handover.error = "The transfer was stopped. What arrived was kept."
        }
    }

    /// Leaves the screen: the session first, then the state the screen exists for.
    func dismissHandover() {
        handoverExchange.stop()
        handoverRefs = [:]
        host.state.handover = HandoverUi()
    }

    private func rememberHandoverRefs(_ refs: [String: String]) { handoverRefs = refs }

    private func handoverRef(_ id: String) -> String? { handoverRefs[id]?.nilIfBlank }

    /// The receiving half of the accounts step. Stored *before* the ack goes back, which
    /// is what the source's own sign-out is gated on — see `HandoverExchange.join`.
    func storeHandoverAccounts(_ payload: AccountsPayload) async {
        if let user = payload.identities.setlistFmUser?.nilIfBlank {
            settings.saveMySetlistFmUser(user)
            host.state.mySetlistFmUser = user
        }
        if let token = payload.credentials.spotifyRefreshToken?.nilIfBlank {
            settings.saveHandoverCredentials(refresh: token, scope: payload.credentials.spotifyScope)
            host.state.spotifyConnected = true
            host.state.grantedScope = payload.credentials.spotifyScope ?? host.state.grantedScope
        }
    }

    /// Receiving device's half of the accounts step (#143), mirroring Android's
    /// `receiveHandoverAccounts`. Reads the frame, stores whatever arrives durably via
    /// `storeHandoverAccounts` *before* acking — the ack is the promise the source's own
    /// clear is gated on — and hands the payload back so the receipt can say what became
    /// of it. Nil only if the connection dropped before any accounts frame arrived; a
    /// genuinely declined row still arrives as identities-only, not as nil.
    private func receiveHandoverAccounts(_ wire: ContactConnection) async throws -> AccountsPayload? {
        guard let payload = try await readJson(wire, AccountsPayload.self) else { return nil }
        await storeHandoverAccounts(payload)
        try await wire.writeFrame(accountsAck)
        return payload
    }

    /// Sending device's half — the phone being replaced. Sends `payload`, then signs out
    /// *here* only if the receiver's ack genuinely arrives (`mayClearCredentials`): a
    /// dropped connection after the send must never clear a credential that may exist
    /// nowhere else. This is the one call site of a handover-triggered
    /// `Settings.clearSpotifyAuth` — manual sign-out (`disconnectSpotify`) does not go
    /// through it and is untouched. Mirrors Android's `sendHandoverAccounts`.
    private func sendHandoverAccounts(_ wire: ContactConnection, payload: AccountsPayload) async throws -> AccountsMove {
        try await writeJson(wire, payload)
        let step: AccountsMove = (try await wire.readFrame() == accountsAck) ? .acknowledged : .sent
        // The payload, not the step, decides whether there is anything to let go of: an
        // identities-only frame (the accounts row unticked, #143 story 11) travels and is
        // acked exactly like a full one, and signing out on that ack would move an
        // account nobody asked to move.
        if mayClearCredentials(step), !payload.credentials.isEmpty {
            settings.clearSpotifyAuth()
            host.state.spotifyConnected = false
            host.state.grantedScope = nil
        }
        return step
    }

    /// Who I am, for the manifest's `identities` and for the accounts payload — mirrors
    /// Android's `myIdentities`.
    private func myIdentities() async -> Identities {
        let user = try? await spotify.currentUser()
        return Identities(setlistFmUser: host.state.mySetlistFmUser.trimmingCharacters(in: .whitespaces).nilIfBlank,
                          spotifyAccount: user?.id)
    }

    /// Credentials only when the row was ticked; identities travel either way (#143).
    /// Mirrors Android's `accountsPayload`.
    private func accountsPayload(_ allow: Set<String>) async -> AccountsPayload {
        let identities = await myIdentities()
        guard allow.contains(categoryAccounts) else { return identitiesOnly(identities) }
        return AccountsPayload(identities: identities,
                               credentials: Credentials(spotifyRefreshToken: settings.refreshTokenValue,
                                                        spotifyScope: settings.grantedScope))
    }
}
