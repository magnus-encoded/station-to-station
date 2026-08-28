import SwiftUI

// The Gig resolution: one night. Its real setlist as a spine, encores marked, and
// the playlist conversion still here (iOS already had it; #52 keeps it) — on
// swipe-left, the "act on this level" gesture, not a control. Reached by tapping
// a Gig Node on the Timeline.
//
// What this Room offers and what sits in its Alcove is one value, decided once by
// `gigOffers` (#177) and read by every part of the screen. Before this each part
// worked it out again from a different subset: the playlist was offered mid-set,
// on a night three weeks away, and on a night whose record holds nothing.

private let ground = Color(red: 0x0E / 255, green: 0x0B / 255, blue: 0x14 / 255)
private let raised = Color(red: 0x17 / 255, green: 0x12 / 255, blue: 0x1F / 255)
private let ink = Color(red: 0xED / 255, green: 0xE9 / 255, blue: 0xF2 / 255)
private let muted = Color(red: 0x8B / 255, green: 0x82 / 255, blue: 0x99 / 255)
private let faint = Color(red: 0x5A / 255, green: 0x53 / 255, blue: 0x68 / 255)
/// Mine. Never "the accent colour" — it means *mine*, at every Resolution
/// (same mark StationView draws its Spine with).
private let amber = Color(red: 0xE7 / 255, green: 0xB2 / 255, blue: 0x4C / 255)
private let spotifyGreen = Color(red: 0x1D / 255, green: 0xB9 / 255, blue: 0x54 / 255)

/// A row of the night: an encore divider, or a performed song (numbered; a tape
/// track has no number — it played but is not one of the band's songs).
private enum EventRow {
    case encore(Int)
    case song(number: Int?, name: String, cover: String?)
}

private func eventRows(_ setlist: FmSetlist) -> [EventRow] {
    var out: [EventRow] = []
    var n = 0
    var encores = 0
    for set in setlist.sets?.set ?? [] {
        if set.encore != nil { encores += 1; out.append(.encore(encores)) }
        for song in set.song where song.name.nilIfBlank != nil {
            out.append(.song(number: song.tape ? nil : { n += 1; return n }(),
                             name: song.name, cover: song.cover?.name))
        }
    }
    return out
}

struct GigView: View {
    @EnvironmentObject var model: AppModel
    /// SwiftUI's own opener rather than `UIApplication.shared.open`, so this file
    /// needs no UIKit import for one link.
    @Environment(\.openURL) private var openURL
    @EnvironmentObject var nav: Nav

    /// Which Log entry's correction panel is open, if any. One at a time: this is a
    /// room you are standing in, not a list of forms. It lives here rather than in
    /// LogEditor because the entries do — they are on the spine now (#268).
    @State private var correctingLog: Int?
    @State private var adopting = false
    @State private var deleting = false
    @State private var adoptLink = ""

    /// What this delete actually costs, said plainly. The count is of keepsakes whose
    /// original has already left the library, so this app's copy is the last one.
    private var deleteWarning: String {
        let lost = model.state.selectedSetlist.map { model.photosLostByDeleting($0.id) } ?? 0
        switch lost {
        case 0: return "The night, its Log and the keepsakes on it go. Your photographs stay in your library."
        case 1: return "Its photograph is only stored here. Deleting the night deletes it."
        default: return "Its \(lost) photographs are only stored here. Deleting the night deletes them."
        }
    }

    var body: some View {
        let show = model.state.selectedSetlist
        let rows = show.map(eventRows) ?? []
        let offers = show.map { roomOffers(for: $0) }
        ZStack {
            ground.ignoresSafeArea()
            if let show {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        header(show, offers?.room)
                        // The night's grid (#99): what I shot, above what was played,
                        // and part of the header block rather than a section under the
                        // set. Reading order is Grammar (ADR-0017, amended 2026-08-28),
                        // so this is Android's sequence and not a second opinion about
                        // it: the keepsakes are how the night is recognised, and they
                        // come before the record of it.
                        NightGrid()
                        // One list, not two (#268). setlist.fm's record and my Log
                        // are two descriptions of the same night, and printing them
                        // one under the other made the reader do the alignment in
                        // their head. Woven, a song both hold is a single line that
                        // says so — and neither record is changed by the other,
                        // which is still the rule: this decides reading order and
                        // nothing else.
                        let log = model.state.gigLog
                        let woven = weaveSetlist(published: rows.map(publishedTitle),
                                                 logged: log.songs)
                        if woven.isEmpty {
                            Text(emptySetLine)
                                .font(.system(size: 13)).foregroundStyle(muted)
                                .padding(.horizontal, 24).padding(.top, 8)
                        } else {
                            ForEach(Array(woven.enumerated()), id: \.offset) { _, line in
                                wovenRow(line, rows: rows, log: log, setlist: show,
                                         canLog: canLog(show))
                            }
                        }
                        // My own Log (#169), under the set and never taken away — it
                        // renders on a night's page forever after. The way in is under
                        // the entries because the entries *are* the set now (#268).
                        // A Log makes sense the moment I am known to have been there
                        // — a check-in, or a night this app minted itself, which only
                        // ever happens by someone standing in front of the stage
                        // tapping an Act. It stays available forever after that:
                        // remembering a song three days later must cost nothing.
                        // Android has gated this since the Log existed; iOS offered it
                        // on every night, a Contact's included.
                        if canLog(show) { LogEditor(setlist: show) }
                        // A Note is media too (#50, #170): a draft in the vault, a
                        // letter in the shared band, and the Preamble composed above it
                        // from what the record already knows. Last of the night's own
                        // material, and after the set on purpose — the sentence is
                        // written once the songs have been read back, which is what
                        // "analysis happens after the show" means as a layout.
                        NightNotes(preamble: gigPreamble(show), senderName: senderName)
                        // The Alcove's own controls, below the night's record rather
                        // than inside it — Android pins these in a `bottomBar`, and a
                        // fixture of the Room is not part of what the Room holds. The
                        // vehicle differs (pinned there, scrolled here) and that part
                        // is Expression.
                        // What this night already became (#360). With the Alcove's
                        // own controls, because a made playlist is a fixture of the
                        // Room rather than part of the night's record — and once a
                        // night has one, opening it is the offer and converting again
                        // is the aside.
                        madePlaylists(show)
                        plannedActions(show, offers?.alcove)
                    }
                    .padding(.top, 8)
                }
                // The Curtain over this Room's Window (#129): draw it back and see
                // what the source says about this night *now*. Which source that is
                // comes from `offers.curtain` — never from here — so it is never the
                // same request on a night three weeks away, a night being stood at,
                // and a night from 1992. Pull-to-refresh is the platform's own
                // answer to this and ships accessible (ADR-0017).
                .refreshable {
                    if let curtain = offers?.curtain { await model.pullCurtain(curtain) }
                }
            } else {
                Text("No gig selected.").foregroundStyle(muted)
            }
        }
        .toolbar {
            // Only for a night this app minted. A night that already has a setlist.fm
            // page has nothing to adopt, and offering it there would be an invitation
            // to claim a second id for one night (#128).
            if model.state.selectedSetlist?.url == nil {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { adopting = true } label: { Image(systemName: "link.badge.plus") }
                        .tint(faint)
                        .accessibilityLabel("This night is on setlist.fm now — paste its link")
                }
                // Reachable from the night itself, on purpose: the undo on a Bill's
                // act needs the Bill to still exist, and a night whose poster has been
                // taken down — or that was typed in with no poster at all (#347, #349)
                // — was left with no way out.
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { deleting = true } label: { Image(systemName: "trash") }
                        .tint(faint)
                        .accessibilityLabel("Delete this night")
                }
            }
            // The light switch (#180): a visible icon button (Android's is a bare
            // gesture on the timeline; GigView's swipes are already claimed by
            // back and the playlist, so a toolbar button is the reversible
            // choice here). VoiceOver gets the same verb-phrased label Android's
            // custom action uses rather than the symbol's own name.
            ToolbarItem(placement: .navigationBarTrailing) {
                Button { model.toggleContactLight() } label: {
                    Image(systemName: model.state.contactLight ? "lightswitch.on" : "lightswitch.off")
                }
                .tint(model.state.contactLight ? amber : faint)
                .accessibilityLabel(model.state.contactLight
                    ? "Turn the contact light off"
                    : "Turn the contact light on, see this night as a Contact does")
            }
        }
        .toolbarBackground(ground, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .navigationBarTitleDisplayMode(.inline)
        // Pasting the link to the record whoever created it just made. A pasted link
        // rather than a search by artist and date: the moment this is used is the moment
        // you are looking at the page you just created, so its url is in your hand, and
        // matching heuristics are a way to be wrong about which night you meant.
        .alert("It's on setlist.fm now", isPresented: $adopting) {
            TextField("setlist.fm link", text: $adoptLink)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button("Adopt") {
                if let show = model.state.selectedSetlist {
                    model.adoptSetlistLink(gigId: show.id, linkOrId: adoptLink)
                }
                adoptLink = ""
            }
            Button("Cancel", role: .cancel) { adoptLink = "" }
        } message: {
            Text("Paste the link. This night takes their id and stops being a stub — which is what lets a friend who was there meet you on it.")
        }
        // Asked always, where Android asks only when it holds the only copy of a
        // photograph. The Log goes either way, and a written record of what I heard is
        // not a pointer into anything that could give it back.
        .alert("Delete this night?", isPresented: $deleting) {
            Button("Delete", role: .destructive) {
                if let show = model.state.selectedSetlist {
                    model.deleteLocalGig(show.id)
                    nav.pop()
                }
            }
            Button("Keep it", role: .cancel) {}
        } message: {
            Text(deleteWarning + " There is no undo.")
        }
        // Act on this level: the Alcove, one step Inner. It holds exactly one thing
        // and it may be empty — empty while the band plays, and empty on a night
        // whose record nobody has filled in, where the playlist would convert
        // nothing. The playlist is what remains once the night is recorded.
        .swipeLeft { if offers?.alcove == .spotify { nav.push(.confirm) } }
        // The same move for VoiceOver, which takes the flick for itself. This used
        // to be a button, and the button was reachable; the gesture on its own is
        // not, so the grammar cannot cost a reader the action.
        .accessibilityAction(named: "Make a Spotify playlist") {
            if offers?.alcove == .spotify { nav.push(.confirm) }
        }
        .accessibilityAction(named: model.state.contactLight
            ? "Turn the contact light off"
            : "Turn the contact light on, see this night as a Contact does") {
            model.toggleContactLight()
        }
        // Back is a chevron with no label, and the swipe that also does it is a
        // gesture VoiceOver consumes.
        .accessibilityAction(.escape) { nav.pop() }
    }

    /// The night's own facts, for the Preamble over a Note (#50). Derived on
    /// every render and never stored: Reconcile has no time bound, so who the
    /// record knows was here changes, and a frozen sentence would be the app
    /// putting words in my mouth about an evening it has since learned more
    /// about.
    private func gigPreamble(_ show: FmSetlist) -> String {
        let alsoThere = model.state.friends.filter { f in
            !f.setlistfm.isEmpty && (model.state.showsByFriend[f.setlistfm] ?? []).contains { $0.id == show.id }
        }.map(\.name)
        return preamble(people: alsoThere, venue: show.venue?.name, songCount: show.performed().count)
    }

    /// A sender is a public key (#28) and a Contact's name lives on the
    /// friends list under a setlist.fm handle. Nothing joins the two yet, so
    /// the promise degrades to "someone else" rather than inventing a name.
    private func senderName(_ key: String) -> String? {
        model.state.friends.first { $0.setlistfm == key }?.name
    }

    /// The state of this night, as known — one value, decided once (#177).
    ///
    /// An editor nobody has typed in is not a **Log**: `state.gigLog` always holds
    /// something so there is a thing to render, and the decision needs the difference
    /// between "never started" and "started and still open".
    private func roomOffers(for show: FmSetlist) -> GigOffers {
        let log = model.state.gigLog
        return gigOffers(
            GigAsKnown(
                window: show.eventDate.flatMap { nightWindow(gigDate: $0) },
                provenance: model.state.selectedAttendance?.provenance,
                log: log.songs.isEmpty && !log.closed ? nil : log,
                // A local **Gig** wears this app's own id, not setlist.fm's, so it is
                // not *linked* — the same `takeUnless(localGig)` Android applies. Fed
                // whole it would make an unposted night look recorded, and the Alcove
                // would offer the playlist where the set still needs handing over.
                setlistId: show.url == nil ? nil : show.id,
                songCount: show.performed().count,
                calendarEvent: model.state.calendarEventByGig[show.id]
            ),
            now: Date()
        )
    }

    private func header(_ show: FmSetlist, _ room: Room?) -> some View {
        // Manual check-in (#174) is the only one there is when location was refused
        // or the venue couldn't be geocoded — the **Room**'s own offer, the same
        // night window the ambient one draws, no location involved at all.
        let checkedIn = model.state.selectedAttendance?.provenance == "checked_in"
        return VStack(alignment: .leading, spacing: 4) {
            Text(show.readableDate() ?? "Unknown date")
                .font(.system(size: 11, weight: .semibold)).kerning(1).foregroundStyle(faint)
            Text(show.artist?.name ?? "Unknown artist")
                .font(.system(size: 26, design: .serif)).foregroundStyle(ink)
            Text(show.venueLine()).font(.system(size: 14)).foregroundStyle(muted)
            if checkedIn {
                Text("\u{2713} checked in").font(.system(size: 13)).foregroundStyle(amber)
                    .padding(.top, 6)
            } else if room?.checkIn == true {
                Text("I'm here — check in").font(.system(size: 13)).foregroundStyle(amber)
                    .padding(.top, 6)
                    .onTapGesture { model.checkIn(show.id) }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 24).padding(.bottom, 16)
    }

    /// Calendar, maps and "I'm not going" (#175) — only for a gig I actually hold a
    /// ticket for. Which of the two calendar words is showing, and whether either is,
    /// Every playlist this night has been turned into.
    ///
    /// Every one of them, not the last: each url may be in somebody's hands, so
    /// converting a night again must not make an earlier link unreachable from here.
    ///
    /// Dropping one is a context menu rather than Android's long-press — the gesture
    /// there has no iOS equivalent that announces itself, and a destructive action
    /// reachable only by holding something is one VoiceOver cannot find. It drops the
    /// *link*, for a playlist deleted on Spotify, and never the night.
    @ViewBuilder
    private func madePlaylists(_ show: FmSetlist) -> some View {
        let made = model.state.playlistsBySetlist[show.id] ?? []
        if !made.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(made, id: \.url) { playlist in
                    Button {
                        if let url = URL(string: playlist.url) { openURL(url) }
                    } label: {
                        HStack(spacing: 8) {
                            Circle().fill(spotifyGreen).frame(width: 7, height: 7)
                            // One playlist needs no naming; several have to be told
                            // apart, because the one you sent is a particular one.
                            Text(made.count == 1
                                 ? "Open the playlist \u{2197}"
                                 : "\(playlist.name.nilIfBlank ?? "Playlist") \u{2197}")
                                .font(.system(size: 14)).foregroundStyle(spotifyGreen)
                            Spacer()
                        }
                        .contentShape(Rectangle())
                        .padding(.vertical, 6)
                    }
                    .contextMenu {
                        Button(role: .destructive) {
                            model.removePlaylist(show.id, url: playlist.url)
                        } label: {
                            Label("Drop this link", systemImage: "trash")
                        }
                    }
                }
            }
            .padding(.horizontal, 24).padding(.top, 14)
        }
    }

    /// comes from the **Alcove**: a gig that already happened, or one being stood at,
    /// has nothing left to put on a calendar. Maps and "I'm not going" have no such
    /// gate — a venue is worth finding whether the night is ahead or behind.
    @ViewBuilder
    private func plannedActions(_ show: FmSetlist, _ alcove: Alcove?) -> some View {
        if model.state.plannedGigs.contains(where: { $0.id == show.id }) {
            let mapsQuery = venueMapsQuery(venueName: show.venue?.name, city: show.venue?.city?.name)
            VStack(alignment: .leading, spacing: 10) {
                // The entry already made is the thing to open — it holds the location
                // and does maps better — but opening it is Android's move and iOS has
                // no door to it yet, so here it stays the word that it was made.
                if alcove == .openCalendar {
                    Label("Added to your calendar", systemImage: "checkmark.circle")
                        .foregroundStyle(muted)
                } else if alcove == .addToCalendar {
                    Button { model.addToCalendar(show) } label: {
                        Label("Add to calendar", systemImage: "calendar.badge.plus")
                    }
                }
                if let mapsQuery {
                    Button { openVenueInMaps(mapsQuery) } label: {
                        Label("Open in Maps", systemImage: "map")
                    }
                }
                Button(role: .destructive) {
                    model.removePlannedGig(show.id)
                    nav.pop()
                } label: {
                    Text("I'm not going")
                }
            }
            .font(.system(size: 14))
            .foregroundStyle(ink)
            .tint(ink)
            .padding(.horizontal, 24).padding(.top, 14)
        }
    }

    /// One line of the woven set: a published row, one of my Log's entries, or the
    /// one both records hold.
    @ViewBuilder
    private func wovenRow(_ line: WovenSong, rows: [EventRow], log: StoredLog,
                          setlist: FmSetlist, canLog: Bool) -> some View {
        // Mine is an index into the Log, and the × and the correction panel act on it
        // there — the published row beside it is never touched by either.
        let remove: (() -> Void)? = (canLog ? line.logged : nil).map { j in
            { correctingLog = nil; model.removeFromLog(j) }
        }
        if let p = line.published {
            switch rows[p] {
            case .encore:
                EncoreLabel()
            case .song(let number, let name, let cover):
                SpineRow(
                    number: number,
                    title: name,
                    note: cover.map { "\($0) cover" },
                    remembered: line.logged.flatMap { log.rememberedAt($0) },
                    // The strongest thing a row can say: two records, independently,
                    // agree. Amber is what says it.
                    mine: line.both,
                    onRemove: remove
                )
            }
        } else if let j = line.logged {
            let title = log.songs[j]
            let gap = title.nilIfBlank == nil
            SpineRow(
                // A number is a position in a record. Where setlist.fm has a set the
                // numbers are its numbers and mine gets a bare dot; where it has
                // none my Log *is* the record, so the running order reads back.
                number: rows.isEmpty ? j + 1 : nil,
                // A Gap is a song that was played and could not be named. It is in
                // the record on purpose: an acknowledged hole is a true fact, and the
                // same song silently absent is the record lying about what it knows.
                title: gap ? "\u{2014} one I couldn\u{2019}t name \u{2014}" : title,
                note: nil,
                remembered: log.rememberedAt(j),
                mine: true,
                gap: gap,
                // A Gap offers no correction: "one I couldn't name" is an
                // acknowledged fact, not an invitation to guess.
                onTap: gap || !canLog ? nil : { correctingLog = correctingLog == j ? nil : j },
                onRemove: remove
            )
            if correctingLog == j {
                LogCorrection(setlist: setlist, index: j) { correctingLog = nil }
            }
        }
    }
}

/// Whether this night is one I may write a Log on: a night I checked into, or one
/// this app minted itself — which only ever happens by someone standing in front of
/// the stage tapping an Act. Twin of Android's `canLog`.
private extension GigView {
    func canLog(_ show: FmSetlist) -> Bool {
        model.state.selectedAttendance?.provenance == "checked_in" || show.url == nil
    }

    /// A night that hasn't happened has no setlist missing from it — nothing has been
    /// played yet, and saying "not logged" would blame setlist.fm for a gap that
    /// isn't one (ADR-0004).
    var emptySetLine: String {
        isPlanned(model.state.selectedAttendance?.provenance)
            ? "This show hasn't happened yet."
            : "This show has no setlist on setlist.fm yet."
    }
}

/// The title a published row puts into the weave — nil for anything that is not a
/// song, so an encore marker keeps its place without ever matching one.
private func publishedTitle(_ row: EventRow) -> String? {
    if case .song(_, let name, _) = row { return name }
    return nil
}
