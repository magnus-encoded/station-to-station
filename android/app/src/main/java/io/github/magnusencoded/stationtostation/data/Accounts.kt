package io.github.magnusencoded.stationtostation.data

import kotlinx.serialization.Serializable

/**
 * Accounts move, they do not copy (#143).
 *
 * Collecting several sources into one view is the product rather than an accessory to
 * it, so "bring my connections across" is a real want. But a credential is the one item
 * in a transfer that is categorically different: every other item, arriving in the wrong
 * place, tells someone *about* me — a refresh token lets them *act as* me, and keep doing
 * it. Allowing a photograph is bounded. Allowing a token is unbounded in time and scope.
 *
 * So it moves rather than copies, and the interface says so.
 */

/** Ticked on the source's allow list, alongside the media categories. */
const val CATEGORY_ACCOUNTS = "accounts"

/**
 * Which setlist.fm user and which Spotify account this is. **Records, not secrets.**
 *
 * These travel with the records whether or not the accounts row is ticked, because they
 * deliver most of the felt convenience with no bearer secret moved: the new phone knows
 * who it is and can offer reconnect with the right account already filled in. Declining
 * to move accounts should not mean facing a setup wizard.
 *
 * The setlist.fm username in particular is public and is an identity, not a credential.
 */
@Serializable
data class Identities(
    val setlistFmUser: String? = null,
    val spotifyAccount: String? = null,
)

/**
 * The bearer secrets, and **the only shape that carries them anywhere**.
 *
 * Never in the records manifest, never in an export, never in a backup. One deliberate
 * route, chosen explicitly, or they stay put — so that no combination of ticked media
 * categories can move a credential as a side effect.
 */
@Serializable
data class Credentials(
    val spotifyRefreshToken: String? = null,
    val spotifyScope: String? = null,
) {
    val isEmpty: Boolean get() = spotifyRefreshToken.isNullOrBlank()
}

/**
 * How far the accounts step has got. Its own atomic step, **sent first**, before any bulk
 * transfer: small, structured and cheap to fail, where a failure after 4.6 GB of media
 * means redoing the expensive part or reasoning about a half-finished state.
 */
enum class AccountsMove {
    /** The row was not ticked. Both phones stay signed in, which is a supported outcome. */
    NOT_OFFERED,

    /** Sent, and not yet confirmed stored. The source is still signed in. */
    SENT,

    /** The receiver has it durably. Only now may the source let go. */
    ACKNOWLEDGED,

    /** The source has signed out. Exactly one device holds the credential. */
    CLEARED,
}

/**
 * **Acknowledgement gates the clear.** The source does not sign out on send; it signs out
 * on confirmation that the receiver stored the credential durably. Anything else risks a
 * credential existing nowhere — a dropped connection signing you out of both phones.
 */
fun mayClearCredentials(step: AccountsMove): Boolean = step == AccountsMove.ACKNOWLEDGED

/** Is the source still usable as an account? True until it has actually let go. */
fun sourceSignedIn(step: AccountsMove): Boolean = step != AccountsMove.CLEARED

/**
 * May the bulk transfer start?
 *
 * Accounts complete before bytes begin. A bulk failure afterwards does not undo the
 * accounts step: the small thing already landed, and it is the one that is miserable to
 * be halfway through.
 */
fun bulkMayStart(allow: Set<String>, step: AccountsMove): Boolean =
    CATEGORY_ACCOUNTS !in allow || step == AccountsMove.ACKNOWLEDGED || step == AccountsMove.CLEARED

/**
 * The approval button.
 *
 * Not a single word. "Move" would be wrong because the operation is genuinely mixed —
 * the records are **copied and nothing is removed**, while the accounts genuinely leave —
 * and one global verb flattens two semantics, in a direction that contradicts the
 * invariant that the source keeps everything. This names what happens on *this* device,
 * which is the surprising part, and it carries the consequence so no explanatory
 * paragraph is needed.
 */
fun approvalVerb(allow: Set<String>): String =
    if (CATEGORY_ACCOUNTS in allow) "Copy and sign out here" else "Copy"

/**
 * The accounts step's own payload — separate from the records manifest by construction,
 * not by a filter that could be forgotten.
 *
 * **Never offered to a Contact.** Accounts move between my own devices only; the far end
 * being me is what makes this a move rather than a giveaway. `categoriesFor` is what
 * enforces that, by never listing the row for a contact.
 */
@Serializable
data class AccountsPayload(
    val identities: Identities = Identities(),
    val credentials: Credentials = Credentials(),
)

/**
 * What travels when the row is *not* ticked: who I am, and nothing that acts as me.
 *
 * A separate function rather than a flag, so that the credential-free path cannot
 * accidentally acquire a credential by someone passing the wrong argument.
 */
fun identitiesOnly(identities: Identities): AccountsPayload =
    AccountsPayload(identities = identities, credentials = Credentials())
