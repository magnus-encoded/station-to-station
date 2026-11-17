package io.github.magnusencoded.stationtostation.data

import android.net.Uri
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A person on my timeline: a **Followed line**, a **Contact**, or both.
 *
 * A **Followed line** is a setlist.fm username and nothing more — the address its
 * attended list is fetched from — added by a link, a QR code or typing it. A **Contact**
 * is whoever I ran an **Exchange** with, and the key they handed over is the identity
 * (#405): [setlistfm] is an attribute of theirs, blank for someone with no account. Only
 * the radio can make a Friend with a blank username, because only the radio carries a key
 * (see [friendFromQuery]). [laneKey] is what their **Lane** is held under either way.
 */
@Serializable
data class Friend(
    /** Their setlist.fm username, or blank for a **Contact** with no account (#405). */
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
 * What a Friend's **Lane** is held under, in `TimelineCache.shows` and every map keyed
 * by a Line (#405).
 *
 * The setlist.fm username, exactly as it always was, so every Contact and Lane held
 * before this is found where it was left. A **Contact** with no account has no username
 * to be filed under, so theirs is `key:` and [keyFingerprint]. The colon is what keeps
 * the two apart: [isPlausibleSetlistFmUser] refuses it, so no username can ever collide
 * with a key's Lane. Blank only for a Friend with neither, which no door lets in.
 */
val Friend.laneKey: String
    get() = when {
        setlistfm.isNotBlank() -> setlistfm
        !publicKey.isNullOrBlank() -> "key:" + keyFingerprint(publicKey)
        else -> ""
    }

/** " (@username)", or nothing for a **Contact** with no account — for a line of copy. */
val Friend.atUser: String get() = if (setlistfm.isBlank()) "" else " (@$setlistfm)"

/** How copy refers to them: "@username", or their name when there is no username. */
val Friend.handle: String get() = if (setlistfm.isBlank()) name else "@$setlistfm"

/**
 * The name of whoever [from] is — a **Card** key, as media and notes are attributed, or a
 * [laneKey]. Null for a blank [from]: a blank is nobody, and matching it would hand an
 * unattributed item to the first Contact with no account.
 */
fun List<Friend>.nameOf(from: String): String? {
    if (from.isBlank()) return null
    return firstOrNull { it.publicKey?.trim() == from.trim() || it.laneKey == from }?.name
}

/**
 * A short, stable name for a **Card** key: SHA-256 over the base64 text as it came off
 * the wire, the first 16 hex digits. Over the text rather than the decoded bytes so it
 * cannot fail, and so both twins and both of my own devices (Handover) derive the same
 * name from the same card. iOS's `keyFingerprint` is the same function, and both suites
 * pin the same digest.
 */
fun keyFingerprint(publicKey: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(publicKey.trim().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(16)

/**
 * The Friend I already hold who [incoming] is, or null (#405).
 *
 * **The key first**, because it is the identity: a card carrying a key I hold is that
 * person whatever username it carries, or none. Then the setlist.fm username,
 * case-insensitively, which is how a **Followed line** — keyless by construction — is
 * recognised, and how a new phone (a new key) is still asked about rather than added as
 * a stranger. A blank username never matches anything: two people without an account
 * are not one person.
 */
fun heldFriend(incoming: Friend, known: List<Friend>): Friend? {
    val key = incoming.publicKey?.trim()?.ifBlank { null }
    if (key != null) known.firstOrNull { it.publicKey?.trim() == key }?.let { return it }
    if (incoming.setlistfm.isBlank()) return null
    return known.firstOrNull { it.setlistfm.equals(incoming.setlistfm, ignoreCase = true) }
}

/** Matched by [heldFriend]: the key when the card carries one, the username otherwise. */
fun friendArrival(incoming: Friend, known: List<Friend>): FriendArrival {
    val existing = heldFriend(incoming, known) ?: return FriendArrival.New(incoming)
    // A first key is a promotion, not a change: nothing is being overwritten, because a
    // **Followed line** held no key to overwrite. Checked before anything else, so a name
    // or Spotify id arriving alongside that first key rides in with it unasked.
    if (existing.publicKey.isNullOrBlank() && !incoming.publicKey.isNullOrBlank()) {
        return FriendArrival.Promotion(incoming)
    }
    // Only what the card *says* about the person can differ here. A card carrying no
    // Spotify id is not a claim that they have none, so it does not count as a change on
    // its own — and nor does a card carrying no username (#405): matched by key, it is the
    // same person saying less, not asking to be unfollowed. A username that differs, or
    // one arriving for a Contact held without one, changes where their Lane comes from,
    // and asks.
    val sameName = existing.name == incoming.name
    val sameSpotify = incoming.spotifyId == null || existing.spotifyId == incoming.spotifyId
    val sameUser = incoming.setlistfm.isBlank() ||
        existing.setlistfm.equals(incoming.setlistfm, ignoreCase = true)
    // A differing key is the one change that matters most: it is what #257 verifies a
    // LAN beacon against, so a card silently swapping it is exactly the impersonation
    // case this whole arrival check exists to catch.
    val sameKey = incoming.publicKey.isNullOrBlank() || existing.publicKey == incoming.publicKey
    return if (sameName && sameSpotify && sameKey && sameUser) FriendArrival.Unchanged
    else FriendArrival.Conflict(existing, incoming)
}

/**
 * The list once [incoming] is written: whoever [heldFriend] says it is comes out, and
 * [incoming] goes on the end, where the most recently added person's **Lane** is drawn.
 *
 * A key already held is never dropped by a later, thinner way of meeting the same person
 * (#188) — only the radio carries a key, so writing a keyless card wholesale would
 * silently unmake the **Contact**, and there is no second moment to collect it. The same
 * goes for a username (#405): a card silent about it keeps the one held, so the Lane stays
 * where it is filed.
 */
fun withFriend(known: List<Friend>, incoming: Friend): List<Friend> {
    val held = heldFriend(incoming, known)
    val written = incoming.copy(
        setlistfm = incoming.setlistfm.ifBlank { held?.setlistfm.orEmpty() },
        publicKey = incoming.publicKey?.ifBlank { null } ?: held?.publicKey,
    )
    return known.filterNot { it == held } + written
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
