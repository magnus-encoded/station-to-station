package io.github.magnusencoded.setlist2spotify.data

/**
 * What a **Contact** can see of my **Line** (#145).
 *
 * The flag that makes **Media** shareable is **prospective**: it does not grant access
 * to my contacts, it grants access to everyone who will ever become one. Adding a
 * contact is deliberate and face to face; nobody revisits what they marked shareable
 * eighteen months ago. With retraction rejected, *knowing what I am currently exposing*
 * is the only protection there is — and a settings screen listing filenames cannot
 * answer it, because the thing you need to notice is a person in the background of a
 * photograph.
 *
 * **This is the one rule.** The contact's-eye view and the manifest a **Contact** is
 * actually sent must both come through here. If two implementations can disagree they
 * eventually will, and the direction of that disagreement is showing someone less than
 * they are being sent.
 *
 * There is exactly one perspective, never one per contact. **Personal** is a single bit
 * with one **Audience**, so "as seen by <name>" would imply permissions that do not
 * exist and invite requests for them.
 */

/**
 * The **Media** any **Contact** can see: mine, and not **Personal**.
 *
 * **Received media is excluded, and that is a decision rather than an oversight.**
 * [StoredMedia.from] names whose camera it came from, and the record exists so that
 * my media and received media stay distinguishable at every layer. Passing a contact's
 * photograph on to my other contacts would be publishing on their behalf — a second
 * path for their picture that they never agreed to and cannot see. Under #28 their
 * media reaches whoever they share it with, through them.
 */
fun visibleToContacts(media: List<StoredMedia>): List<StoredMedia> =
    media.filter { !it.personal && it.from == null }

/**
 * The other half of the same question: what I am holding back.
 *
 * The faithful view answers "what am I exposing" by simply not showing a **Personal**
 * item. That cannot answer the opposite question — absence cannot tell a night I shared
 * nothing from a night I shared everything — and "what am I withholding" is the one
 * that catches the photograph never re-examined. It is my own data in both cases.
 */
fun withheldFromContacts(media: List<StoredMedia>): List<StoredMedia> =
    media.filter { it.personal && it.from == null }

/** Every night's **Media**, as a **Contact** sees it. Nights with nothing shared stay. */
fun contactMedia(media: Map<String, List<StoredMedia>>): Map<String, List<StoredMedia>> =
    media.mapValues { (_, items) -> visibleToContacts(items) }

/**
 * Everything on this night stops being shared, forward only.
 *
 * The wording matters more than the mechanism: this closes the door, it does not
 * retrieve what already left. **Personal** rather than deletion, because the photograph
 * is still mine and still belongs to the night — what changes is who it is for.
 */
fun stopSharing(media: List<StoredMedia>): List<StoredMedia> =
    media.map { if (it.from == null) it.copy(personal = true) else it }
