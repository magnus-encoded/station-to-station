package io.github.magnusencoded.stationtostation.data

import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A friend is just a setlist.fm username (the only thing needed to fetch their
 * attended concerts) plus a display name and, when available, the Spotify id
 * that username maps to. No server: friends are exchanged peer-to-peer as a
 * shareable deep link (see [toShareUri]) and stored on-device.
 */
@Serializable
data class Friend(
    val setlistfm: String,
    val name: String = setlistfm,
    val spotifyId: String? = null,
    /**
     * The device identity a **Contact** presented at Exchange (#28), base64
     * SubjectPublicKeyInfo, ECDSA P-256. Null for a friend added before this field
     * existed, or from a path that hasn't carried a key yet. What lets a later LAN
     * beacon (#257) be verified as this specific person rather than a stranger.
     *
     * Only the radio fills this in. A link or QR code never can (#271).
     */
    val publicKey: String? = null,
)

/**
 * What arriving at my **Contact** list means for a card I have just been handed (#188).
 *
 * A card arrives from a link any page can open, or from a write by any radio in range,
 * and the write it used to perform was a **replace**: an attacker who knew a real
 * contact's username could silently rewrite the name I see against their **Line**.
 *
 * The line is drawn where the risk is. **Writing into an empty space costs nothing** —
 * a contact I do not hold cannot be spoofed by adding them, and a swap that stopped to
 * ask on every first meeting would be ceremony at exactly the moment two people are
 * standing in front of each other. **Changing what is already there is different**: it
 * is the only case where a card can make my record say something about someone I
 * already know, so that one asks.
 */
sealed interface FriendArrival {
    /** Nobody by that username yet. Write it, say nothing. */
    data class New(val friend: Friend) : FriendArrival

    /**
     * Already held, and the card says the same thing. **Not a write and not a prompt.**
     *
     * Without this, meeting the same person twice — the ordinary case for people who go
     * to gigs together — would ask permission to change nothing, and a prompt that
     * routinely means nothing is a prompt nobody reads.
     */
    data object Unchanged : FriendArrival

    /**
     * Already held **without a key**, and the card brings one. **This is the Exchange.**
     *
     * The moment a **Followed line** becomes a **Contact**: the person was already on
     * screen — from a link, a QR scan, a typed username — and standing next to them is
     * what adds the key. Nothing is overwritten, because a **Followed line** grants
     * nothing and there was no trust there to overwrite.
     *
     * A distinct outcome rather than a special case of [New], because holding a key is
     * what *makes* a **Contact**: it is a change of kind, not a change of field.
     *
     * The card is taken **whole**, not merged with the record already held. The card
     * presented in person is more authoritative than anything a link guessed, and a
     * merge would leave a display name from an untrusted source attached to a
     * now-trusted identity. So a promotion never asks about the name — which is the
     * false positive this case exists to remove: without it, the ordinary first
     * **Exchange** with someone you already follow would ask whether they have a
     * different phone, about a phone you have never seen.
     */
    data class Promotion(val friend: Friend) : FriendArrival

    /** Already held, and the card differs. The one case that asks. */
    data class Conflict(val existing: Friend, val incoming: Friend) : FriendArrival
}

/**
 * Matched on the setlist.fm username, case-insensitively — the same key the list has
 * always de-duplicated on, because it is the identity setlist.fm itself uses.
 */
fun friendArrival(incoming: Friend, known: List<Friend>): FriendArrival {
    val existing = known.firstOrNull { it.setlistfm.equals(incoming.setlistfm, ignoreCase = true) }
        ?: return FriendArrival.New(incoming)
    // A first key is a promotion, not a change: nothing is being overwritten, because a
    // **Followed line** held no key to overwrite. Checked before anything else, so a name
    // or Spotify id arriving alongside that first key rides in with it unasked.
    if (existing.publicKey.isNullOrBlank() && !incoming.publicKey.isNullOrBlank()) {
        return FriendArrival.Promotion(incoming)
    }
    // The username is the identity and cannot differ here; only what the card *says*
    // about that identity can. A card carrying no Spotify id is not a claim that they
    // have none, so it does not count as a change on its own.
    val sameName = existing.name == incoming.name
    val sameSpotify = incoming.spotifyId == null || existing.spotifyId == incoming.spotifyId
    // A differing key is the one change that matters most: it is what #257 verifies a
    // LAN beacon against, so a card silently swapping it is exactly the impersonation
    // case this whole arrival check exists to catch.
    val sameKey = incoming.publicKey.isNullOrBlank() || existing.publicKey == incoming.publicKey
    return if (sameName && sameSpotify && sameKey) FriendArrival.Unchanged
    else FriendArrival.Conflict(existing, incoming)
}

private val friendsJson = Json { ignoreUnknownKeys = true }

fun encodeFriends(friends: List<Friend>): String = friendsJson.encodeToString(friends)

fun decodeFriends(stored: String?): List<Friend> =
    if (stored.isNullOrBlank()) emptyList()
    else runCatching { friendsJson.decodeFromString<List<Friend>>(stored) }.getOrDefault(emptyList())

/**
 * The link a user shares so a friend's app can add them with one tap.
 *
 * **No key, ever** — see [friendFromQuery]. A link can only ever make a **Followed
 * line**; the key rides the radio (#271).
 */
fun Friend.toShareUri(): Uri = Uri.Builder()
    .scheme("station-to-station")
    .authority("friend")
    .appendQueryParameter("u", setlistfm)
    .appendQueryParameter("name", name)
    .apply { spotifyId?.let { appendQueryParameter("sid", it) } }
    .build()

/**
 * The link that invites someone to a gig I'm going to. Same deep-link mechanism as
 * the friend card, a different authority: the setlist.fm id is all a second device
 * needs — it fetches the rest (see [friendFromUri] for the parsing counterpart).
 */
fun gigInviteUri(setlistId: String): Uri = Uri.Builder()
    .scheme("station-to-station")
    .authority("gig")
    .appendQueryParameter("id", setlistId)
    .build()

/** The setlist.fm id out of a `station-to-station://gig?id=...` invite, or null. */
fun gigIdFromInvite(uri: Uri): String? =
    if (uri.authority != "gig") null
    else uri.getQueryParameter("id")?.trim()?.ifBlank { null }

// --- Playlist-as-card discovery ---
//
// A converted playlist's description carries the creator's setlist.fm username in
// a machine-parseable stamp. When a friend shares such a playlist, reading its
// description hands us their spotify->setlist.fm mapping with no server involved.

private const val SFM_STAMP_PREFIX = "[sfm:"

/** The stamp appended to a playlist description so a friend's app can find the creator. */
fun sfmStamp(username: String): String = "$SFM_STAMP_PREFIX${username.trim()}]"

private val stampRegex = Regex("""\[sfm:([^\]\s]+)]""")

/** Extracts the creator's setlist.fm username from a playlist description, if stamped. */
fun sfmUserFromDescription(description: String?): String? =
    description?.let { stampRegex.find(it)?.groupValues?.get(1)?.ifBlank { null } }

private val playlistIdRegex = Regex("""playlist[:/]([A-Za-z0-9]+)""")

/** Pulls the playlist id out of a Spotify link or URI (open.spotify.com/... or spotify:playlist:...). */
fun spotifyPlaylistId(input: String): String? =
    playlistIdRegex.find(input.trim())?.groupValues?.get(1)

/**
 * The value-shaping half of [friendFromUri], pulled out to take the three query values
 * directly rather than a `Uri` — android.net.Uri can't be constructed in a plain JVM
 * unit test (same reason [io.github.magnusencoded.stationtostation.parseGigLink] was
 * split from its Uri handler), so this is the part the link grammar check can run.
 *
 * **A link never carries a key, and this is the door that refuses it (#271).** Holding a
 * key is what makes a **Contact**, and a **Contact** is not addable remotely — ever: the
 * authentication is that two people stood together and ran an **Exchange**. A link comes
 * from any web page, any chat message, any other installed app, so a `k` parameter here
 * would let a crafted link mint a **Contact** at a distance and then **Reconcile** over
 * LAN (#257) for media of mine. A link produces a **Followed line** and nothing more;
 * promotion to **Contact** is #188's arrival case, over the radio, in person.
 *
 * Do not re-add the parameter as a convenience. iOS's parser refuses it at the same door.
 */
fun friendFromQuery(u: String?, name: String?, sid: String?): Friend? {
    val user = u?.trim().orEmpty()
    if (!isPlausibleSetlistFmUser(user)) return null
    return Friend(
        setlistfm = user,
        name = name?.trim()?.ifBlank { null } ?: user,
        spotifyId = sid?.trim()?.ifBlank { null },
    )
}

/**
 * Letters, digits, dot, hyphen, underscore — nothing that means something to a URL.
 *
 * A username is the least trusted string this app holds: it arrives from a link any
 * page can open, or from any radio in range, and it ends up in a **path segment**
 * against setlist.fm carrying our API key. #187 is what that costs when it is not
 * checked — a percent-encoded CRLF rode the path into the request line and split one
 * request into two. That fix encodes the path, which is the right root fix; this is
 * the other half, refusing the value at the door so it never travels at all.
 *
 * An allow-list, because the interesting characters here are the ones nobody thought
 * of. Unicode letters and digits rather than ASCII, so a name in a non-Latin script
 * is still a name — the point is to exclude URL and protocol syntax, not foreigners.
 *
 * Deliberately conservative, and it is worth saying what that costs: setlist.fm's own
 * rule is not published anywhere we can read, so this is a guess at the shape of a
 * username rather than a copy of their policy. If a real account is ever rejected,
 * widen this — but widen it to a character, not to "anything non-blank".
 */
fun isPlausibleSetlistFmUser(user: String): Boolean =
    user.isNotEmpty() && user.length <= 64 && SETLISTFM_USER.matches(user)

private val SETLISTFM_USER = Regex("""[\p{L}\p{N}._-]+""")

/** Parses a `station-to-station://friend?...` link. Null if it isn't one / has no username. */
fun friendFromUri(uri: Uri): Friend? {
    if (uri.authority != "friend") return null
    // No `k` read here — a link cannot carry identity (#271, see [friendFromQuery]).
    return friendFromQuery(
        uri.getQueryParameter("u"),
        uri.getQueryParameter("name"),
        uri.getQueryParameter("sid"),
    )
}
