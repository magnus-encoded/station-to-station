import Foundation

/// My own **Line** as a **Contact** sees it (#180).
///
/// The Swift twin of Android's `data/ContactView.kt`, **ported rather than
/// re-derived**, which #180 asks for by name and for a reason worth restating: this is
/// the *one rule*. The contact's-eye view and the manifest a contact is actually sent
/// both come through it. Two implementations that can disagree eventually will, and
/// the direction of that disagreement is showing someone **less than they are being
/// sent** — a check that lies in the safe-looking direction is worse than no check.
///
/// There is no undo on sharing, which is what puts the whole weight on the moment of
/// it. Being able to look at what you are exposing is one of the three protections the
/// design has, alongside the granularity and the wording.

/// What a **Contact** is offered on a night: the shared band.
///
/// **Received media is excluded, and that is a decision rather than an oversight.**
/// `from` names whose camera it came from. Passing a **Contact**'s photograph on to my
/// other contacts would be publishing on their behalf — a second path for their picture
/// that they never agreed to and cannot see. Their media reaches whoever they share it
/// with, through them.
func visibleToContacts(_ media: [StoredMedia]) -> [StoredMedia] {
    media.filter { !$0.personal && $0.from == nil }
}

/// The other half of the same question: what I am holding back on a night — the vault.
///
/// The faithful view answers "what am I exposing" by simply not showing an item. That
/// cannot answer the opposite question — absence cannot tell a night I shared nothing
/// from a night I shared everything — and "what am I withholding" is the one that
/// catches the photograph never re-examined. It is my own data in both cases, which is
/// why received media is excluded here too.
func withheldFromContacts(_ media: [StoredMedia]) -> [StoredMedia] {
    media.filter { $0.from == nil && $0.personal }
}

/// Whether a night is **mine** — one on my own **Line**, as opposed to a **Contact**'s
/// that I am only looking at (#327).
///
/// Every editing affordance on a **Gig** has to ask this before it is drawn. Attaching
/// a photograph to someone else's night is not a disclosure hole — **Shared** is
/// by-person and any **Contact** is entitled to any Shared media — but it *acquires*
/// the night: the **Gig** becomes a record on my device and their Shared media for it
/// routes to me on the next **Reconcile**. An action that only makes sense on my own
/// night silently converted theirs into mine.
///
/// Three ways a night is mine, and an attendance claim is the first because it is the
/// most direct: a **Checked in** or **Attended** record is the app saying I was there.
/// The other two are the lists my own **Line** is drawn from.
func isMyNight(
    _ setlistId: String,
    attendance: StoredAttendance?,
    mine: [FmSetlist],
    planned: [FmSetlist]
) -> Bool {
    if attendance != nil { return true }
    return mine.contains { $0.id == setlistId } || planned.contains { $0.id == setlistId }
}

/// Every night's **Media**, as a **Contact** sees it. Nights sharing nothing stay,
/// empty — a night that vanished would answer a question nobody asked.
func contactMedia(_ media: [String: [StoredMedia]]) -> [String: [StoredMedia]] {
    media.mapValues(visibleToContacts)
}

/// What the source may offer, by far end. Ported from Android's `categoriesFor`.
///
/// **The Personal categories are absent for a Contact, not merely unticked.** An
/// invariant rather than a UI safety measure: there is no box to mis-tap and no path
/// where a **Personal** item is one boolean away from leaving. Sending one to a Contact
/// requires making it not Personal first, which is an explicit act on that item.
///
/// `accounts` (#143) is the same story: present for my own other device, absent for a
/// Contact, because a credential moves between my own devices only — the far end being
/// me is what makes it a move rather than a giveaway. iOS does hold a Spotify credential
/// worth moving (see `Settings.refreshTokenValue`), so the row is symmetric with
/// Android's rather than a one-way receive.
func categoriesFor(contact: Bool) -> Set<String> {
    let shared: Set<String> = [
        categorySetlists, StoredMedia.Kind.photo, StoredMedia.Kind.video, StoredMedia.Kind.note,
    ]
    if contact { return shared }
    return shared.union([
        categoryOf(kind: StoredMedia.Kind.photo, personal: true),
        categoryOf(kind: StoredMedia.Kind.video, personal: true),
        // A draft I never dragged up. It reaches my own other device and nobody else,
        // which is what keeps privacy from costing me the material I write from (#50).
        categoryOf(kind: StoredMedia.Kind.note, personal: true),
        categoryAccounts,
    ])
}

/// The manifest a **Contact** is offered (#265), ported from Android's `contactManifest`.
///
/// **Exclusion happens here, at construction.** A **Personal** item never enters a
/// manifest bound for anyone but me — not filtered out downstream, not left for a tick
/// box to keep out. This is what gives `visibleToContacts` a production caller on iOS:
/// the rule was already written and already tested, and had nothing calling it.
///
/// `me` is my own public key, written into every item's `from` so that **attribution
/// survives the transfer**: once a Contact's photographs are mingled into someone's
/// nights unattributed, which were whose is unrecoverable.
///
/// Nights are in the *source's* own **Gig** ids throughout, which is what the plan reads;
/// translating them is the receiver's job (`contactLanding`), not the sender's. Sorted by
/// that id only so the same timeline always produces the same manifest — a Swift
/// dictionary has no order of its own, and a wire format that reshuffles per run is one
/// nobody can assert against.
func contactManifest(_ cache: TimelineCache, me: String) -> HandoverManifest {
    let shared = cache.gigMedia.mapValues(visibleToContacts).filter { !$0.value.isEmpty }

    // **Built up from empty, never handed a copy of the cache to subtract from.** A
    // `TimelineCache` also holds my **Log**, my attendance and how it was decided, the
    // gigs I have tickets for, the playlists I made, every band's shows and my totals —
    // and a Contact is offered *media from a shared night*, which is the whole of #265's
    // ninth story. Sending the rest because it happened to be in the same struct is the
    // failure that story names.
    //
    // Two fields, because `contactLanding` reads exactly two: `gigMedia` for what is
    // offered and `gigs` for the `setlistId` that says which night it was. Anything added
    // here later should have to answer for itself.
    //
    // The nights are narrowed too, not just the media on them: the full `gigs` map is the
    // complete list of every gig I have ever attended, which is a different disclosure
    // than the one being made.
    var timeline = TimelineCache()
    timeline.gigMedia = shared
    timeline.gigs = cache.gigs.filter { shared[$0.key] != nil }

    let media = shared.keys.sorted().flatMap { gigId in
        shared[gigId, default: []].map { item in
            OfferedMedia(
                id: item.id,
                gigId: gigId,
                kind: item.kind,
                capturedAt: item.capturedAt,
                // Never true here, by construction rather than by filtering.
                personal: false,
                // Mine, said out loud. A picture that arrives unattributed silently
                // becomes the receiver's.
                from: me,
                // A **Note** carries its own payload: there is no second phase to fetch
                // it in, so it either rides the manifest or never arrives.
                text: item.text,
                verdict: item.verdict
            )
        }
    }
    return HandoverManifest(timeline: timeline, media: media)
}
