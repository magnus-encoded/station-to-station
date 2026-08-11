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
    if (user.isEmpty()) return null
    return Friend(
        setlistfm = user,
        name = name?.trim()?.ifBlank { null } ?: user,
        spotifyId = sid?.trim()?.ifBlank { null },
    )
}

/** Parses a `station-to-station://friend?...` link. Null if it isn't one / has no username. */
fun friendFromUri(uri: Uri): Friend? {
    if (uri.authority != "friend") return null
    return friendFromQuery(
        uri.getQueryParameter("u"),
        uri.getQueryParameter("name"),
        uri.getQueryParameter("sid"),
    )
}
