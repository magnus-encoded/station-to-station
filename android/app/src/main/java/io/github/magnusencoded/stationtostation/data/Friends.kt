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
)

private val friendsJson = Json { ignoreUnknownKeys = true }

fun encodeFriends(friends: List<Friend>): String = friendsJson.encodeToString(friends)

fun decodeFriends(stored: String?): List<Friend> =
    if (stored.isNullOrBlank()) emptyList()
    else runCatching { friendsJson.decodeFromString<List<Friend>>(stored) }.getOrDefault(emptyList())

/** The link a user shares so a friend's app can add them with one tap. */
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
    return friendFromQuery(
        uri.getQueryParameter("u"),
        uri.getQueryParameter("name"),
        uri.getQueryParameter("sid"),
    )
}
