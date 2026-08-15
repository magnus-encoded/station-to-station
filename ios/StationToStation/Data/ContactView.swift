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

/// Every night's **Media**, as a **Contact** sees it. Nights sharing nothing stay,
/// empty — a night that vanished would answer a question nobody asked.
func contactMedia(_ media: [String: [StoredMedia]]) -> [String: [StoredMedia]] {
    media.mapValues(visibleToContacts)
}
