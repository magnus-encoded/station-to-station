package io.github.magnusencoded.stationtostation.data.setlistfm

import java.io.IOException

/**
 * The key a request goes out with, and whether it is the bundled one.
 *
 * The distinction is the whole of #457: the bundled key's quota is shared by every
 * install, so "the limit is reached" means something a person can act on — get a free
 * key of their own — where on their own key it means only "wait". A bare string cannot
 * tell the two apart, so the key source returns this instead.
 */
data class SetlistFmKey(val key: String, val shared: Boolean)

/** One HTTP answer, as little of it as this client cares about. */
data class SetlistFmResponse(val status: Int, val body: String)

/**
 * setlist.fm refused a request as rate-limited, and still refused it after the single
 * short retry that tells a burst from a spent day apart.
 *
 * [sharedKey] decides the advice, and nothing else does: the shared key has run out for
 * everyone and a key of your own fixes it; your own key has run out for you and only
 * time fixes it.
 */
class SetlistFmRateLimited(val sharedKey: Boolean) : IOException(
    if (sharedKey) SHARED_QUOTA_MESSAGE else OWN_KEY_RATE_LIMIT_MESSAGE
)

/**
 * What the user is told when the *shared* key is spent.
 *
 * It must not say when the key comes back: setlist.fm's limit is "max. 50000/DAY" and
 * the docs never define the day, so any time named here would be a guess. It must not
 * promise how fast setlist.fm issues a new key either — that is unverified.
 */
const val SHARED_QUOTA_MESSAGE: String =
    "The setlist.fm key bundled with this app is shared by every tester, and today's " +
        "requests are used up. A free setlist.fm key of your own gets you your own limit."

/** And when it is the user's own key, where there is nothing to do but wait. */
const val OWN_KEY_RATE_LIMIT_MESSAGE: String =
    "setlist.fm is limiting requests on your API key. Try again later."

/**
 * How long a spent shared quota is believed before the next request goes out as a probe.
 *
 * An hour rather than "until midnight": setlist.fm does not say whether its day is a
 * calendar day in some time zone or a rolling twenty-four hours, so a window that does
 * not depend on the answer is the only one that is right under all of them. It costs
 * each install about two requests an hour while the quota stays spent.
 */
const val SHARED_QUOTA_MEMORY_MS: Long = 60 * 60 * 1000L

/**
 * Whether a recorded shared-quota refusal is still in force.
 *
 * A timestamp in the future is treated as expired rather than as spent: that is a clock
 * that moved, and believing it would lock the app out of setlist.fm for as long as the
 * clock stays wrong. The worst an expired reading costs is one probe request.
 */
fun sharedQuotaSpent(spentAtMillis: Long?, nowMillis: Long): Boolean {
    val spentAt = spentAtMillis ?: return false
    val elapsed = nowMillis - spentAt
    return elapsed in 0 until SHARED_QUOTA_MEMORY_MS
}
