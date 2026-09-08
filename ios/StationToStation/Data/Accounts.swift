import Foundation

/// Accounts move, they do not copy (#143). Ported from Android's `data/Accounts.kt`,
/// term for term — this is the logic layer ADR-0001 asks the two twins to agree on, and
/// `Identities`/`Credentials`/`AccountsPayload` already live in `HandoverWire.swift`
/// (this file's Android counterpart keeps the wire format), so this file holds the pure
/// decisions that sit on top of them.
///
/// Collecting several sources into one view is the product rather than an accessory to
/// it, so "bring my connections across" is a real want. But a credential is the one item
/// in a transfer that is categorically different: every other item, arriving in the
/// wrong place, tells someone *about* me — a refresh token lets them *act as* me, and
/// keep doing it. Allowing a photograph is bounded. Allowing a token is unbounded in
/// time and scope.
///
/// So it moves rather than copies, and the interface says so.

/// Ticked on the source's allow list, alongside the media categories.
let categoryAccounts = "accounts"

/// How far the accounts step has got. Its own atomic step, **sent first**, before any
/// bulk transfer: small, structured and cheap to fail, where a failure after several GB
/// of media means redoing the expensive part or reasoning about a half-finished state.
///
/// Raw values match Android's `AccountsMove` enum names exactly — this crosses the wire
/// inside `HandoverReceipt`, so the two twins have to agree on the literal string.
enum AccountsMove: String, Codable, Equatable {
    /// The row was not ticked. Both phones stay signed in, which is a supported outcome.
    case notOffered = "NOT_OFFERED"
    /// Sent, and not yet confirmed stored. The source is still signed in.
    case sent = "SENT"
    /// The receiver has it durably. Only now may the source let go.
    case acknowledged = "ACKNOWLEDGED"
    /// The source has signed out. Exactly one device holds the credential.
    case cleared = "CLEARED"
}

/// **Acknowledgement gates the clear.** The source does not sign out on send; it signs
/// out on confirmation that the receiver stored the credential durably. Anything else
/// risks a credential existing nowhere — a dropped connection signing you out of both
/// phones.
func mayClearCredentials(_ step: AccountsMove) -> Bool { step == .acknowledged }

/// Is the source still usable as an account? True until it has actually let go.
func sourceSignedIn(_ step: AccountsMove) -> Bool { step != .cleared }

/// May the bulk transfer start?
///
/// Accounts complete before bytes begin. A bulk failure afterwards does not undo the
/// accounts step: the small thing already landed, and it is the one that is miserable to
/// be halfway through.
func bulkMayStart(allow: Set<String>, step: AccountsMove) -> Bool {
    !allow.contains(categoryAccounts) || step == .acknowledged || step == .cleared
}

/// The approval button.
///
/// Not a single word. "Move" would be wrong because the operation is genuinely mixed —
/// the records are **copied and nothing is removed**, while the accounts genuinely leave
/// — and one global verb flattens two semantics, in a direction that contradicts the
/// invariant that the source keeps everything. This names what happens on *this* device,
/// which is the surprising part, and it carries the consequence so no explanatory
/// paragraph is needed.
func approvalVerb(_ allow: Set<String>) -> String {
    allow.contains(categoryAccounts) ? "Copy and sign out here" : "Copy"
}

/// What travels when the row is *not* ticked: who I am, and nothing that acts as me.
///
/// A separate function rather than a flag, so that the credential-free path cannot
/// accidentally acquire a credential by someone passing the wrong argument.
func identitiesOnly(_ identities: Identities) -> AccountsPayload {
    AccountsPayload(identities: identities, credentials: Credentials())
}
