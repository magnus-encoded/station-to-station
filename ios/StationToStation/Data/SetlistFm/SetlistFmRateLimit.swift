import Foundation

/// The key a request goes out with, and whether it is the bundled one.
///
/// The distinction is the whole of #457: the bundled key's quota is shared by every
/// install, so "the limit is reached" means something a person can act on — get a free
/// key of their own — where on their own key it means only "wait". A bare string cannot
/// tell the two apart, so the key source returns this instead. Term for term with
/// Android's `SetlistFmKey`.
struct SetlistFmKey: Equatable {
    let key: String
    let shared: Bool
}

/// One HTTP answer, as little of it as this client cares about.
struct SetlistFmResponse: Equatable {
    let status: Int
    let body: Data
}

/// setlist.fm refused a request as rate-limited, and still refused it after the single
/// short retry that tells a burst from a spent day apart.
///
/// `sharedKey` decides the advice, and nothing else does: the shared key has run out for
/// everyone and a key of your own fixes it; your own key has run out for you and only
/// time fixes it.
struct SetlistFmRateLimited: LocalizedError, Equatable {
    let sharedKey: Bool
    var errorDescription: String? {
        sharedKey ? sharedQuotaMessage : ownKeyRateLimitMessage
    }
}

/// What the user is told when the *shared* key is spent.
///
/// It must not say when the key comes back: setlist.fm's limit is "max. 50000/DAY" and
/// the docs never define the day, so any time named here would be a guess. It must not
/// promise how fast setlist.fm issues a new key either — that is unverified. Word for
/// word with Android's `SHARED_QUOTA_MESSAGE`.
let sharedQuotaMessage =
    "The setlist.fm key bundled with this app is shared by every tester, and today's "
    + "requests are used up. A free setlist.fm key of your own gets you your own limit."

/// And when it is the user's own key, where there is nothing to do but wait.
let ownKeyRateLimitMessage =
    "setlist.fm is limiting requests on your API key. Try again later."

/// How long a spent shared quota is believed before the next request goes out as a probe.
///
/// An hour rather than "until midnight": setlist.fm does not say whether its day is a
/// calendar day in some time zone or a rolling twenty-four hours, so a window that does
/// not depend on the answer is the only one that is right under all of them. It costs
/// each install about two requests an hour while the quota stays spent.
let sharedQuotaMemorySeconds: TimeInterval = 60 * 60

/// The way out of a spent shared setlist.fm key, worded the same everywhere (#457).
let addOwnKeyAction = "Add your own key"

/// About a second — long enough for a per-second burst to have passed.
let rateLimitRetrySeconds: TimeInterval = 1

/// Unchanged from before #457: 5xx is ridden out, three tries and a growing backoff.
let maxServerAttempts = 3

/// Whether a recorded shared-quota refusal is still in force.
///
/// A timestamp in the future is treated as expired rather than as spent: that is a clock
/// that moved, and believing it would lock the app out of setlist.fm for as long as the
/// clock stays wrong. The worst an expired reading costs is one probe request.
func sharedQuotaSpent(spentAt: TimeInterval?, now: TimeInterval) -> Bool {
    guard let spentAt else { return false }
    let elapsed = now - spentAt
    return elapsed >= 0 && elapsed < sharedQuotaMemorySeconds
}
