import Foundation

@MainActor
final class GossipController {

    unowned let host: StateHost
    private let timelines: TimelineStore

    init(host: StateHost, timelines: TimelineStore) {
        self.host = host
        self.timelines = timelines
    }

    /// The nights the channel knows an end for: everything on the **Line**, plans included.
    private var knownNights: [FmSetlist] { host.state.timelineShows + host.state.plannedGigs }

    /// The witnessed mark, keyed by every id the night answers to.
    func refreshWitnessed(_ publicState: PublicGossipState) async {
        let cache = await timelines.load()
        host.state.witnessedGigs = gossipWitnessedIds(publicState.witnessedGigIds(), cache: cache)
        host.state.gossipGigAliases = gossipGigAliases(cache: cache)
    }

    /// The gossip channel's whole lifecycle, in one line: it follows the **Contact** list and
    /// nothing else (ADR-0019). Up when there is somebody to gossip with, down when
    /// there is not, and never tied to a screen the way the Exchange and Reconcile are.
    ///
    /// The nights go with it because a gig this device knows the date of has a real expiry —
    /// the end of that night (`gossipExpiry`) — and that is what a fact authored here claims,
    /// rather than a flat day from now. See `GossipChannel.setNightEnds` for what the channel
    /// does with the rest of them.
    func contactsChanged() {
        let ends = knownNights.reduce(into: [String: Date]()) { ends, gig in
            if let date = gig.eventDate, let end = gossipExpiry(gigDate: date) { ends[gig.id] = end }
        }
        let friends = host.state.friends
        Task {
            let cache = await timelines.load()
            let stoppedAt = GossipTransport.shared.stoppedAt
            let until = gossipActiveUntil(cache: cache, stoppedAt: stoppedAt)
            host.state.gossipActiveUntil = until
            // What the **Presence rows** draw, read at the same moment as what the radio is
            // told — two answers a moment apart would light a bullet for a night the transport
            // has just stopped for.
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            let eligible = gossipParticipationEnds(cache: cache)
            host.state.gossipEligibleUntil = eligible
            // Only the nights that could still gossip *now*: a deadline in the map is a moment,
            // not a promise, and every attended night in the timeline keeps a past one. Asked
            // unfiltered, one old stop would leave Settings offering Resume forever. The
            // bullet re-asks the same question on the **Room**'s own clock; this is what the
            // Settings button has instead of one.
            host.state.gossipStoppedGigs = gossipStoppedGigs(
                eligible: eligible.filter { $0.value > now },
                running: gossipParticipationEnds(cache: cache, stoppedAt: stoppedAt))
            host.state.gossipActiveGig = gossipActiveGigId(cache: cache, stoppedAt: stoppedAt,
                selected: GossipTransport.shared.selectedGigId, now: now)
            GossipTransport.shared.contactsChanged(friends, activeUntil: until)
        }
        Task { await GossipChannel.shared.setNightEnds(ends) }
        // Read back rather than pushed at the moment of witnessing, so a phone that was
        // closed when the witness arrived projects it the same way after a relaunch.
        Task { [weak self] in
            await GossipChannel.shared.observePresence { present, gigIds in
                Task { @MainActor in
                    self?.host.state.metAt.merge(present) { _, arrived in arrived }
                    self?.host.state.presentGigs = gigIds
                }
            }
        }
        Task { [weak self] in
            await GossipChannel.shared.observePublic { publicState in
                Task { @MainActor in
                    self?.host.state.publicGossip = publicState
                    await self?.refreshWitnessed(publicState)
                }
            }
        }
    }

    func blockGossip(_ author: String) { Task { await GossipChannel.shared.blockAuthor(author) } }

    /// The user-facing off switch — iOS's counterpart to Android's notification stop action.
    ///
    /// It goes through here rather than straight to `GossipTransport` so the screens'
    /// `gossipActiveUntil` is recomputed from the same policy the radio just acted on,
    /// instead of a screen keeping its own idea of whether gossip is running.
    ///
    /// The transport writes the *moment* of the stop rather than a flag, and
    /// `gossipParticipationUntil` only counts a check-in that outlives it: stopping ends
    /// tonight, checking in again later is unaffected, and reopening a **Log** afterwards
    /// does **not** resume — a stop the next edit undid would not be an off switch. Nothing
    /// here touches the **Facts** already received; only the radio's reason to run ends.
    func stopGossip() {
        GossipTransport.shared.stopParticipation()
        contactsChanged()
    }

    /// Stand at this **Gig**: the tap on a **Presence row**.
    ///
    /// `gigId` is the id the **Room** holds, which is the adopted one where the night has one;
    /// the selection is stored under the local id, because that is the only id for a night that
    /// cannot change under the device.
    ///
    /// It mints no **Check-in** and touches no attendance — choosing which night you are standing
    /// at is not a claim to have been at it, and that claim was already made by checking in. It
    /// does clear a stop, and with the Settings button that is the only thing that clears one: a
    /// dim bullet means *could be gossiping, is not*, and tapping it is the explicit Resume,
    /// where reopening a **Log** deliberately still is not.
    func selectGossipGig(_ gigId: String) {
        Task {
            let cache = await timelines.load()
            guard let local = cache.gigs[gigId] ?? cache.gigForSetlist(gigId) else { return }
            GossipTransport.shared.selectGig(localGigId: local.id)
            GossipTransport.shared.resumeParticipation()
            contactsChanged()
        }
    }

    /// Resume from Settings: the stop goes, and the fallback rule says which night the radio
    /// comes back for. The dim **Presence row** is the same act with a night named.
    func resumeGossip() {
        GossipTransport.shared.resumeParticipation()
        contactsChanged()
    }

    /// Re-read what the **Presence rows** draw, on the **Room**'s own clock.
    ///
    /// The deadlines are the only thing on that screen that changes without anybody doing
    /// anything, and a night's grace running out has to take its bullet with it while somebody is
    /// looking at the row — including handing the amber to whichever night is next, which is a
    /// different **Room** and only the timeline knows which. Same call as every other input, so
    /// the transport hears about it too.
    func refreshGossipPresence() { contactsChanged() }
}
