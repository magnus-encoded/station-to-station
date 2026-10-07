import Foundation

/// The **Gig** record: adding to and taking from my **Line**, its **Log**, its Notes and
/// Verdicts, and checking in.
@MainActor
final class GigController {

    private let host: StateHost
    private let timelines: TimelineStore
    private let setlistFm: SetlistFmClient
    private let location: DeviceLocation
    private let gossip: GossipController
    private let publishLog: (String, String, Date, [Int: String]) async -> Void

    /// One-shot per launch: dismissing an offer must not make it reappear (#174).
    private var askedToCheckIn = false
    var onCheckedIn: ((String) -> Void)?
    /// The clock a **Log** edit is stamped with; the **Demo clock** for the demo **Gig** only.
    var logNow: ((String) -> Date)?
    /// True when the **Demo world** owns the edit, bypassing timeline persistence and radio publication.
    var onLogWritten: ((String, StoredLog, StoredLog, Int64) -> Bool)?

    init(
        host: StateHost,
        timelines: TimelineStore,
        setlistFm: SetlistFmClient,
        location: DeviceLocation,
        gossip: GossipController,
        publishLog: @escaping (String, String, Date, [Int: String]) async -> Void = { gigId, localId, expiry, changes in
            await GossipChannel.shared.publishLog(gigId: gigId, localGigId: localId, expiry: expiry, changes: changes)
        }
    ) {
        self.host = host
        self.timelines = timelines
        self.setlistFm = setlistFm
        self.location = location
        self.gossip = gossip
        self.publishLog = publishLog
    }

    /// How many pictures a delete would destroy — the ones this app holds the last
    /// copy of. Zero means every keepsake on the night is still in the library too,
    /// so removing the night costs nothing that cannot be found again.
    ///
    /// The sheet asks this to decide *what to say*, not whether to ask. Android asks
    /// only when bytes would go; iOS asks always, because the **Log** goes either way
    /// and a written record is not a pointer into anything.
    func photosLostByDeleting(_ gigId: String) -> Int {
        (host.state.mediaBySetlist[gigId] ?? [])
            .filter { PhotoLibrary.holdsOnlyCopy(mediaId: $0.id, ref: $0.ref) }
            .count
    }

    /// Where the **Gig** is held. Read from the raw attended list under my own key, never from
    /// `timelineShows`: that one also carries the nights I attended here, which is what this
    /// has to tell apart.
    func standing(_ gigId: String) -> GigStanding {
        gigStanding(
            gigId,
            held: host.state.plannedGigs.map(\.id),
            attendedOnSetlistFm: (host.state.showsByFriend[host.state.mySetlistFmUser.trimmingCharacters(in: .whitespaces)] ?? []).map(\.id))
    }

    /// A night deleted from its own screen.
    ///
    /// Unlike the mistap undo this takes the media with it, because someone reading
    /// the night's own screen can see what is on it.
    ///
    /// Any **Gig** this phone holds a record of can go, its setlist.fm id or not. One held only
    /// by my setlist.fm attended list has no delete; see `gigMenu`.
    func deleteGig(_ gigId: String, keepingPlaylists: Bool = false) {
        let media = host.state.mediaBySetlist[gigId] ?? []
        let me = host.state.mySetlistFmUser.trimmingCharacters(in: .whitespaces)
        host.state.plannedGigs.removeAll { $0.id == gigId }
        host.state.timelineShows.removeAll { $0.id == gigId }
        // The cached copy of my attended list goes too, or the night comes back as soon as
        // the Spine is read again. Setlist.fm itself is not touched.
        host.state.showsByFriend[me] = (host.state.showsByFriend[me] ?? []).filter { $0.id != gigId }
        host.state.attendanceByGig[gigId] = nil
        host.state.mediaBySetlist[gigId] = nil
        host.state.calendarEventByGig[gigId] = nil
        if host.state.selectedSetlist?.id == gigId {
            host.state.selectedSetlist = nil
            host.state.gigLog = StoredLog()
        }
        Task {
            guard await timelines.deleteGig(gigId, withMedia: true, anyId: true, attendedLane: me,
                                            keepingPlaylists: keepingPlaylists) else { return }
            for item in media { PhotoLibrary.deleteThumbnails(item.id) }
        }
    }

    /// The **Gig** already on my line for this night and this artist, if there is
    /// one. `nightIso` is `yyyy-MM-dd`, as `ProgrammeAct.date` gives it — the query
    /// half of `nameKey` is folded the same way `diff.remove`'s keys already are.
    func onLine(nightIso: String, artist: String, mbid: String = "") -> FmSetlist? {
        guard let night = parseISODateUTC(nightIso) else { return nil }
        let key = nameKey(artist)
        return (host.state.timelineShows + host.state.plannedGigs).first { gig in
            guard gig.localDate() == night else { return false }
            if !mbid.isEmpty, let gigMbid = gig.artist?.mbid, gigMbid == mbid { return true }
            return nameKey(gig.artist?.name ?? "") == key
        }
    }

    /// A night this app minted, now catalogued on setlist.fm, takes their id.
    ///
    /// A pasted link rather than a search by artist and date. #34 sketched the search,
    /// but the moment this is used is the moment you are looking at the page you just
    /// created, so its url is in your hand — and matching heuristics are a way to be
    /// wrong about which night you meant.
    func adoptSetlistLink(gigId: String, linkOrId: String) {
        guard let setlistId = parseSetlistId(linkOrId) else {
            host.state.error = "That doesn't look like a setlist.fm link."
            host.state.errorKind = nil
            return
        }
        Task {
            if !(await adoptSetlist(gigId: gigId, setlistId: setlistId, fresh: nil, notice: true)) {
                host.state.error = "That night already has a setlist.fm id."
                host.state.errorKind = nil
            }
        }
    }

    /// Local **Gig** `gigId` takes setlist.fm's `setlistId`: `adoptSetlistLink`'s pasted
    /// link, a search hit the person picked, or one the automatic checks were sure of
    /// (#531). `fresh` is the record already in hand from a search, which saves asking
    /// setlist.fm for it again; nil fetches it. `notice` shows "Adopted". False where the
    /// night already had an id, or is gone, and nothing was changed.
    @discardableResult
    func adoptSetlist(gigId: String, setlistId: String, fresh: FmSetlist?, notice: Bool) async -> Bool {
        // Read before the adoption, because afterwards the night answers to the new id and
        // the deadline that decides whether the radio is still running would be keyed by it.
        // A night that already had an id is not an adoption, and authors nothing.
        let before = await timelines.load()
        let until = before.gigs[gigId]?.setlistId == nil
            ? gossipParticipationEnds(cache: before, stoppedAt: GossipTransport.shared.stoppedAt)[gigId] ?? 0
            : 0
        guard await timelines.adoptSetlistId(gigId: gigId, setlistId: setlistId) else { return false }
        await GossipChannel.shared.adoptedGigId(gigId: setlistId, formerGigId: gigId,
                                               localGigId: gigId, until: until)
        // Whether or not anything was authored, the night now answers to a second id
        // and the mark has to follow it.
        await gossip.refreshWitnessed(host.state.publicGossip)
        if notice { host.state.notice = "Adopted — this night is on setlist.fm now." }
        // The real record replaces the stub: it has the url, the songs whoever typed
        // them in logged, and an id friends' lines can meet at.
        let record: FmSetlist?
        if let fresh, fresh.id == setlistId {
            record = fresh
        } else {
            record = try? await setlistFm.setlist(setlistId)
        }
        guard let real = record else { return true }
        host.state.attendanceByGig[real.id] = await timelines.savePlanned(real)
        host.state.plannedGigs = sortedPlanned(host.state.plannedGigs.filter { $0.id != gigId && $0.id != real.id } + [real])
        if host.state.selectedSetlist?.id == gigId { host.state.selectedSetlist = real }
        return true
    }

    /// Forgets a gig I'm not going to after all.
    func removePlannedGig(_ gigId: String) {
        host.state.plannedGigs = host.state.plannedGigs.filter { $0.id != gigId }
        host.state.attendanceByGig[gigId] = nil
        Task { await timelines.removePlanned(setlistId: gigId) }
    }

    /// `write-to-log`: the same path typing into the Log takes, once the **Gig** is open.
    func writeToLog(appends: [String], replacements: [Int: String]) {
        writeLog { $0.writing(appends: appends, replacements: replacements, now: $1) }
    }

    /// True if any gig I know about could be checked into right now on the
    /// calendar alone. Cheap and pure — it is what decides whether asking for
    /// the location permission is warranted at all, so the prompt only ever
    /// appears on a night there is actually something to check into.
    func checkInDue(now: Date = Date()) async -> Bool {
        let cache = await timelines.load()
        let attendance = cache.attendance()
        return cache.planned().contains { gig in
            canCheckInManually(gig: gig, now: now) && attendance[gig.id]?.provenance != "checked_in"
        }
    }

    func hasLocationPermission() -> Bool { location.hasPermission }

    func requestLocationPermission() { location.requestPermission() }

    /// One fix, once, when the timeline is opened: if it puts me at a gig I'm
    /// going to tonight, offer to check in. Every failure along the way —
    /// permission refused, no fix, no coordinates for the venue, too far away —
    /// is silently no offer. Nothing here is retried, scheduled or run in the
    /// background.
    ///
    /// ponytail: linear over the planned gigs, geocoding only the one that
    /// passes the city gate. You have a ticket for a handful of nights, not
    /// thousands.
    func offerCheckIn() {
        if askedToCheckIn { return }
        askedToCheckIn = true
        Task {
            guard let fix = await location.currentFix() else { return }
            let cache = await timelines.load()
            let attendance = cache.attendance()
            let candidates = cache.planned().filter { attendance[$0.id]?.provenance != "checked_in" }
            guard let gig = checkInCandidate(gigs: candidates, now: Date(), where: fix) else { return }
            guard let venue = await venueCoords(gig, cache: cache) else { return }
            guard atVenue(where: fix, venue: venue) else { return }
            host.state.checkInOffer = gig
        }
    }

    func dismissCheckInOffer() { host.state.checkInOffer = nil }

    /// The venue's coordinates, geocoded once and kept on the attendance record
    /// — the same fields #29/#174 reserved for it on Android. Nil for a venue
    /// the geocoder can't place, which costs this gig its prompt and nothing else.
    private func venueCoords(_ gig: FmSetlist, cache: TimelineCache) async -> (lat: Double, lon: Double)? {
        if let stored = cache.attendance()[gig.id], let lat = stored.venueLat, let lon = stored.venueLon {
            return (lat, lon)
        }
        guard let query = venueMapsQuery(venueName: gig.venue?.name, city: gig.venue?.city?.name)
        else { return nil }
        guard let found = await location.geocodeVenue(
            [query, gig.venue?.city?.country?.name].compactMap { $0 }.joined(separator: ", ")
        ) else { return nil }
        // Only the coordinates, onto the record as it is after the geocode: `cache` was
        // read before it, and saving a record built from that would put back its old
        // Admissions and claim over a ticket attached or a check-in made meanwhile (the
        // #441 review). Android's `updateAttendance { it.copy(…) }`.
        let settled = await timelines.updateAttendance(setlistId: gig.id) {
            $0.venueLat = found.lat
            $0.venueLon = found.lon
        }
        host.state.attendanceByGig[gig.id] = settled
        if host.state.selectedSetlist?.id == gig.id { host.state.selectedAttendance = settled }
        return found
    }

    /// I am here. Sets the provenance the whole issue exists for, with the
    /// moment it happened — evidence of a different strength than setlist.fm's
    /// retroactive flag, not a competing record.
    func checkIn(_ gigId: String) {
        host.state.checkInOffer = nil
        Task {
            // Only the claim changes. The ticket's Admissions and the venue's coordinates
            // are carried across the check-in by editing the record in place, read and
            // written under one lock — Android's `updateAttendance { it.copy(…) }` (#412).
            let checkedInAt = epochMs(Date())
            let attendance = await timelines.updateAttendance(setlistId: gigId) {
                $0.provenance = "checked_in"
                $0.checkedInAt = checkedInAt
            }
            // The claim goes into state as well as onto disk: the night has just stopped
            // being a plan, and the lane it leaves is drawn from this map.
            host.state.attendanceByGig[gigId] = attendance
            if host.state.selectedSetlist?.id == gigId { host.state.selectedAttendance = attendance }
            onCheckedIn?(gigId)
            // And into the gossip channel, signed, to be carried by whoever this phone meets
            // between now and the end of this night (#417). Nothing is promised by this: see
            // `GossipTransport` on what iOS background delivery actually is.
            let gigDate = host.state.knownNights.first { $0.id == gigId }?.eventDate
            let cache = await timelines.load()
            guard let localGig = cache.gigs[gigId] ?? cache.gigForSetlist(gigId) else { return }
            _ = await GossipChannel.shared.checkedIn(gigId: gigId, localGigId: localGig.id, gigDate: gigDate)
            gossip.contactsChanged()
        }
    }

    /// Write, edit or clear my Note in one Band (#50, porting Android's
    /// `setGigNote`).
    ///
    /// At most one of mine per band, so this is an upsert keyed by band rather
    /// than by id: the write-line the finger landed on already said which one
    /// it means. Two notes in a band would need arranging, arranging would
    /// need the handle, and the thing being served is one opinion about one
    /// night.
    ///
    /// Emptying it removes it. A note with nothing in it is not something
    /// anyone wrote, and leaving an empty record behind would make the shared
    /// band claim a contributor who said nothing — which would turn a night
    /// green over blank text.
    func setGigNote(_ band: Band, text: String) {
        guard let setlist = host.state.selectedSetlist else { return }
        let had = host.state.gigMedia
        let personal = band == .vault
        let mine = had.first { $0.kind == StoredMedia.Kind.note && $0.from == nil && $0.personal == personal }
        let written = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let media: [StoredMedia]
        if let mine, written.isEmpty {
            media = had.filter { $0.id != mine.id }
        } else if let mine {
            media = had.map { item in
                guard item.id == mine.id else { return item }
                var m = item
                m.text = written
                return m
            }
        } else if written.isEmpty {
            media = had
        } else {
            media = had + [StoredMedia(
                id: UUID().uuidString.lowercased(),
                kind: StoredMedia.Kind.note,
                // When it was written. It is what sorts received notes, and a
                // note has no camera to ask for anything better.
                capturedAt: epochMs(Date()),
                personal: personal,
                text: written
            )]
        }
        host.state.mediaBySetlist[setlist.id] = media
        Task { await timelines.saveMedia(setlistId: setlist.id, media: media) }
    }

    /// Set or unset the Verdict on one of my Notes (porting Android's
    /// `setGigVerdict`).
    ///
    /// Tapping the one already set passes nil, because unset has to stay
    /// reachable — it is a real state, and a night I have stopped having an
    /// opinion about must not be stuck wearing the one I had.
    func setGigVerdict(_ noteId: String, verdict: String?) {
        guard let setlist = host.state.selectedSetlist else { return }
        let had = host.state.gigMedia
        // Mine only. A received note's verdict is its sender's statement and
        // is not mine to edit, the same way their photograph is not mine to
        // reposition.
        guard had.contains(where: { $0.id == noteId && $0.from == nil }) else { return }
        let media = had.map { item -> StoredMedia in
            guard item.id == noteId else { return item }
            var m = item
            m.verdict = verdict
            return m
        }
        host.state.mediaBySetlist[setlist.id] = media
        Task { await timelines.saveMedia(setlistId: setlist.id, media: media) }
    }

    /// A lookup's settled record into state, for the chip and the open night.
    func storeAttendance(_ gigId: String, _ settled: StoredAttendance) {
        host.state.attendanceByGig[gigId] = settled
        if host.state.selectedSetlist?.id == gigId { host.state.selectedAttendance = settled }
    }

    /// Asserted, never derived: a song I *think* they played never becomes a song
    /// they played by inaction, so this is a tap, not a diff against a candidate
    /// pool. Editing songs never touches `closed` — "that was the whole set" is a
    /// separate, deliberate sentence.
    func addToLog(_ song: String) { writeLog { $0.adding(song, now: $1) } }

    func removeFromLog(_ index: Int) { writeLog { log, _ in log.removingAt(index) } }

    /// A title replaces entry `index`, and what was written moves beneath it (#126).
    func correctLogEntry(_ index: Int, title: String) { writeLog { log, _ in log.correctingAt(index, title: title) } }

    /// The way back. A wrong correction must not be a one-way door.
    func restoreLogEntry(_ index: Int) { writeLog { log, _ in log.restoringAt(index) } }

    /// The only thing that may **Close** a **Log**, and it is a person saying so.
    /// setlist.fm has nowhere to keep this bit, so it never leaves the device.
    func setLogClosed(_ closed: Bool) {
        writeLog { $0.completing(closed, now: $1) }
    }

    private func writeLog(_ edit: (StoredLog, Int64) -> StoredLog) {
        guard let setlist = host.state.selectedSetlist else { return }
        let before = host.state.gigLog
        let now = epochMs(logNow?(setlist.id) ?? Date())
        let updated = edit(before, now)
        host.state.gigLog = updated
        if onLogWritten?(setlist.id, before, updated, now) == true { return }
        Task {
            await timelines.saveLog(setlistId: setlist.id, log: updated)
            let cache = await timelines.load()
            if let local = cache.gigs[setlist.id] ?? cache.gigForSetlist(setlist.id),
               let date = setlist.eventDate, let end = gossipExpiry(gigDate: date),
               let until = gossipParticipationUntil(checkedInAt: cache.attendance()[setlist.id]?.checkedInAt,
                    closed: updated.closed, completedAt: updated.completedAt, nightEnd: end, stoppedAt: GossipTransport.shared.stoppedAt), Date() < until {
                await publishLog(setlist.id, local.id, end, gossipLogChanges(before: before, after: updated))
            }
            gossip.contactsChanged()
        }
    }
}
