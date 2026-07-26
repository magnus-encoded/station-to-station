package io.github.magnusencoded.setlist2spotify.data

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
    .scheme("setlist2spotify")
    .authority("friend")
    .appendQueryParameter("u", setlistfm)
    .appendQueryParameter("name", name)
    .apply { spotifyId?.let { appendQueryParameter("sid", it) } }
    .build()

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

/** Parses a `setlist2spotify://friend?...` link. Null if it isn't one / has no username. */
fun friendFromUri(uri: Uri): Friend? {
    if (uri.authority != "friend") return null
    val user = uri.getQueryParameter("u")?.trim().orEmpty()
    if (user.isEmpty()) return null
    return Friend(
        setlistfm = user,
        name = uri.getQueryParameter("name")?.trim()?.ifBlank { null } ?: user,
        spotifyId = uri.getQueryParameter("sid")?.trim()?.ifBlank { null },
    )
}
