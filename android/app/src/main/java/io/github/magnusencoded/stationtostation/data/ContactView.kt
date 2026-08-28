package io.github.magnusencoded.stationtostation.data

import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist

/**
 * The two tiers, and what a **Contact** can see of my **Line** (#144, #145).
 *
 * **Exactly two tiers, by design.** **Media** is either just me, or my **Audience** —
 * everyone I have formed an in-person **Contact** with. No per-contact permissions, no
 * groups, no per-item recipient lists. The single tier is comprehensible, and it is only
 * trustworthy because a **Contact** is added face to face, which ties a sender to a
 * person.
 *
 * **One question, asked once, per item (#162).** There were two — a night-level grant on
 * top of each item's own bit — and two boundaries that can disagree eventually do. What
 * is left is [StoredMedia.personal], set by the act of putting a photograph in one band
 * or the other, at the moment it is attached. **Personal** never leaves for anyone. It
 * travels freely between my own devices (#141), which is what keeps privacy from costing
 * me my own record.
 *
 * **This is the one rule.** The contact's-eye view and the manifest a **Contact** is
 * actually sent both come through here. If two implementations can disagree they
 * eventually will, and the direction of that disagreement is showing someone less than
 * they are being sent.
 *
 * **There is no undo, which puts the whole weight on the moment of sharing.** Retraction
 * was rejected — a mechanism for deleting information on someone else's device, with no
 * server to deliver it and the Streisand effect against it — so the protections here are
 * the granularity, the wording, and being able to look at what you are exposing.
 */

/**
 * The **Media** any **Contact** can see on one night: exactly the shared band.
 *
 * **The grant is prospective**, which is why it is per item and why the act happens at
 * the moment of attaching. Putting a photograph in the shared band does not grant it to
 * the contacts I have, it grants it to everyone who will ever become one, including
 * someone I meet in a year. Nobody revisits what they flagged eighteen months ago, so
 * nothing is shared unless it was placed there.
 *
 * **Received media is excluded, and that is a decision rather than an oversight.**
 * [StoredMedia.from] names whose camera it came from, and the record exists so that my
 * media and received media stay distinguishable at every layer. Passing a **Contact**'s
 * photograph on to my other contacts would be publishing on their behalf — a second path
 * for their picture that they never agreed to and cannot see. Under #28 their media
 * reaches whoever they share it with, through them.
 */
fun visibleToContacts(media: List<StoredMedia>): List<StoredMedia> =
    media.filter { !it.personal && it.from == null }

/**
 * The other half of the same question: what I am holding back on a night — the vault
 * band.
 *
 * The faithful view answers "what am I exposing" by simply not showing an item. That
 * cannot answer the opposite question — absence cannot tell a night I shared nothing from
 * a night I shared everything — and "what am I withholding" is the one that catches the
 * photograph never re-examined. It is my own data in both cases.
 */
fun withheldFromContacts(media: List<StoredMedia>): List<StoredMedia> =
    media.filter { it.from == null && it.personal }

/**
 * Whether a night is **mine** — one on my own **Line**, as opposed to a **Contact**'s
 * that I am only looking at (#327).
 *
 * Every editing affordance on a **Gig** has to ask this before it is drawn. Attaching a
 * photograph to someone else's night is not a disclosure hole — **Shared** is by-person
 * and any **Contact** is entitled to any Shared media — but it *acquires* the night: the
 * **Gig** becomes a record on my device and their Shared media for it routes to me on the
 * next **Reconcile**. An action that only makes sense on my own night silently converted
 * theirs into mine.
 *
 * Three ways a night is mine, and an attendance claim is the first because it is the most
 * direct: a **Checked in** or **Attended** record is the app saying I was there. The other
 * two are the lists my own **Line** is drawn from.
 */
fun isMyNight(
    setlistId: String,
    attendance: StoredAttendance?,
    mine: List<FmSetlist>,
    planned: List<FmSetlist>,
): Boolean {
    if (attendance != null) return true
    return mine.any { it.id == setlistId } || planned.any { it.id == setlistId }
}

/** Every night's **Media**, as a **Contact** sees it. Nights sharing nothing stay, empty. */
fun contactMedia(media: Map<String, List<StoredMedia>>): Map<String, List<StoredMedia>> =
    media.mapValues { (_, items) -> visibleToContacts(items) }

/**
 * What the source may offer, by far end.
 *
 * **The Personal categories are absent for a Contact, not merely unticked.** This is an
 * invariant rather than a UI safety measure: there is no box to mis-tap and no path where
 * a **Personal** item is one boolean away from leaving. Sending one to a **Contact**
 * requires making it not **Personal** first, which is an explicit act on that item.
 *
 * It is also the one place identity genuinely decides data flow rather than convenience:
 * the handshake says which far end this is, and the far end says which categories exist
 * at all.
 */
fun categoriesFor(contact: Boolean): Set<String> = if (contact) {
    // No Personal categories, and no accounts: a credential moves between my own devices
    // only. The far end being me is what makes it a move rather than a giveaway (#143).
    setOf(CATEGORY_SETLISTS, StoredMedia.Kind.PHOTO, StoredMedia.Kind.VIDEO, StoredMedia.Kind.NOTE)
} else {
    setOf(
        CATEGORY_SETLISTS,
        StoredMedia.Kind.PHOTO,
        StoredMedia.Kind.VIDEO,
        StoredMedia.Kind.NOTE,
        categoryOf(StoredMedia.Kind.PHOTO, personal = true),
        categoryOf(StoredMedia.Kind.VIDEO, personal = true),
        // A draft I never dragged up. It reaches my own other device and nobody else,
        // which is what keeps privacy from costing me the material I write from (#50).
        categoryOf(StoredMedia.Kind.NOTE, personal = true),
        CATEGORY_ACCOUNTS,
    )
}

/**
 * The manifest a **Contact** is offered: the same shape as a device handover, differing
 * only in which categories exist and in attribution being someone else's name.
 *
 * **Exclusion happens here, at construction.** A **Personal** item never enters a
 * manifest bound for anyone but me — not filtered out downstream, not left for a tick box
 * to keep out. That the same manifest, signature, counts and transport carry both cases
 * is the evidence the shape is right; a second mechanism for sharing would be a second
 * thing to get wrong.
 *
 * [me] is my own public key, written into every item's [OfferedMedia.from] so that
 * **attribution survives the transfer**. It is in the envelope from the first version
 * even though a device handover makes the answer trivially "me", because once a
 * **Contact**'s photographs are mingled into someone's nights with no attribution, which
 * were whose is unrecoverable.
 */
fun contactManifest(cache: TimelineCache, me: String): HandoverManifest {
    // In the source's own **Gig** ids throughout, which is what the plan reads: the
    // manifest describes this device's timeline, and translating ids is the receiver's
    // job, not the sender's.
    val media = cache.gigMedia.mapValues { (_, items) ->
        visibleToContacts(items)
    }.filterValues { it.isNotEmpty() }

    return HandoverManifest(
        // **Built up from empty, never handed a copy of the cache to subtract from.** A
        // [TimelineCache] also holds my **Log**, my attendance and how it was decided, the
        // gigs I have tickets for, the playlists I made, every band's shows and my totals —
        // and a Contact is offered *media from a shared night*. `cache.copy(gigMedia = …)`,
        // which is what this was, sent all of it because it happened to be in the same
        // class (#267).
        //
        // Two fields, because [contactLanding] reads exactly two: [TimelineCache.gigMedia]
        // for what is offered and [TimelineCache.gigs] for the `setlistId` that says which
        // night it was. Anything added here later should have to answer for itself.
        //
        // The nights are narrowed too, not just the media on them: the full `gigs` map is
        // the complete list of every gig I have ever attended, which is a different
        // disclosure than the one being made.
        timeline = TimelineCache(gigMedia = media, gigs = cache.gigs.filterKeys { it in media }),
        media = media.flatMap { (gigId, items) ->
            items.map {
                OfferedMedia(
                    id = it.id,
                    gigId = gigId,
                    kind = it.kind,
                    capturedAt = it.capturedAt,
                    // Mine, said out loud. A picture that arrives unattributed silently
                    // becomes the receiver's.
                    from = me,
                    // Never true here, by construction rather than by filtering.
                    personal = false,
                    // A **Note** carries its own payload: there is no second phase to
                    // fetch it in, so it either rides the manifest or never arrives.
                    text = it.text,
                    verdict = it.verdict,
                )
            }
        },
    )
}

/**
 * Stopping sharing is now dragging a photograph down into the vault, one at a time —
 * [moveMedia] — so there is no night-level door left to close.
 *
 * That is finer than what it replaces rather than weaker, and it matches the standing
 * refusal of bulk operations: closing a door should be as considered an act as opening
 * one was. The wording it inherited still holds, and matters more than the mechanism
 * ever did — moving an item to the vault stops it being offered from now on, it does not
 * retrieve what already left, and **the one thing worse than having no undo is a button
 * that looks like one**.
 */
