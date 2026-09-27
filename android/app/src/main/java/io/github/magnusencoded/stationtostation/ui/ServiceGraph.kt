package io.github.magnusencoded.stationtostation.ui

/**
 * The services this app reads from and writes to, as known right now — and from them,
 * which are **Lit** on the Settings **Field** (#563).
 *
 * Settings used to be a scrolling form of keys and switches, grouped by vendor. It said
 * nothing about what any of it was *for*. The **Field** draws the same facts as the
 * picture of what feeds **My timeline** and where it goes: inputs on the left, the
 * **Alcoves** on the right, every line amber where that service can do its job now.
 * This is the fold behind the picture. Everything the screen asks — lit or not, what
 * to say, what lighting it would take — is decided here, once, with no device.
 *
 * Every string is word for word the same in `ServiceGraph.swift`, and
 * `fixtures/service-graph/cases.json` holds both platforms to it.
 *
 * The defaults are a fresh install: nothing granted, nobody known, no key of your own.
 */
data class ServicesAsKnown(
    /** A setlist.fm key is there to use, bundled or your own. `UiState.setlistFmReady`. */
    val setlistFmKeyAvailable: Boolean = false,
    /** The user pasted a key of their own. */
    val setlistFmOwnKey: Boolean = false,
    /**
     * The bundled key's shared daily quota ran out. Only means anything while the user
     * has no key of their own: their own key has its own quota.
     */
    val setlistFmSharedQuotaSpent: Boolean = false,
    val clashfinderUser: String = "",
    /** A clashfinder private key is saved. */
    val clashfinderKey: Boolean = false,
    val spotifyConnected: Boolean = false,
    /** The scopes Spotify granted at login, space-separated. Null where unknown. */
    val spotifyScope: String? = null,
    /** How many **Contacts**' timelines this phone holds. */
    val knownTimelines: Int = 0,
    val photos: PhotoAccess = PhotoAccess.NONE,
    val calendar: Boolean = false,
    val location: Boolean = false,
    /** Checked in at a gig, so **Gossip** is running. */
    val gigActive: Boolean = false,
)

/** Android 14 and iOS both let someone share *some* photos, which is still lit. */
enum class PhotoAccess { NONE, PARTIAL, FULL }

/** Which side of **My timeline** a service stands on: feeding it, or fed by it. */
enum class ServiceRole { INPUT, ALCOVE }

/** The strip a tile sits in on the **Field**, by where the service lives. */
enum class ServiceStrip(val title: String) {
    DATABASES("Databases"),
    THIS_PHONE("This phone"),
    OTHER_PHONES("Other phones"),
    SERVICES("Services"),
}

data class ServiceNode(
    /** Stable, shared with iOS and the fixtures. */
    val id: String,
    val name: String,
    val role: ServiceRole,
    val strip: ServiceStrip,
    val lit: Boolean,
    /** One line on where it stands, said the same way whether it is lit or not. */
    val status: String,
    /** What it gives you, each phrased as something you can have. */
    val unlocks: List<String>,
    /** The single step that would light it. Null when it is lit, or nothing would. */
    val nextStep: String? = null,
    val experimental: Boolean = false,
) {
    /**
     * What a screen reader says for the tile: the whole state in one element, since the
     * ring colour that says it on screen is not something TalkBack or VoiceOver can see.
     */
    val spoken: String
        get() = "$name, ${if (lit) "lit" else "not lit"}, ${status.replaceFirstChar { it.lowercase() }}"
}

/**
 * The **Field** as facts: the nodes in drawing order, and which lines are lit.
 *
 * Built from a node list rather than only from [ServicesAsKnown] so the line rules can
 * be checked on their own. With today's services MusicBrainz and Ticket PDFs are always
 * lit, so **My timeline** always is too and no [ServicesAsKnown] can show an alcove
 * whose line is dark — but that is a fact about today's services, not the rule, and the
 * rule is what the drawing follows.
 */
data class ServiceGraph(val nodes: List<ServiceNode>) {
    /** **My timeline** is lit when anything at all feeds it. */
    val timelineLit: Boolean = nodes.any { it.role == ServiceRole.INPUT && it.lit }

    fun node(id: String): ServiceNode? = nodes.firstOrNull { it.id == id }

    /**
     * The line out to an **Alcove** is lit only when both ends are: a Spotify login
     * with nothing on the timeline has nothing to make a playlist of.
     */
    fun alcoveLineLit(id: String): Boolean =
        node(id)?.let { it.role == ServiceRole.ALCOVE && it.lit && timelineLit } ?: false
}

fun serviceGraph(known: ServicesAsKnown): ServiceGraph {
    // The shared quota is the bundled key's problem. Someone with their own key never
    // hears about it, even if the flag was set before they pasted one.
    val quotaSpent = known.setlistFmSharedQuotaSpent && !known.setlistFmOwnKey
    val setlistFm = known.setlistFmKeyAvailable && !quotaSpent
    val clashUser = known.clashfinderUser.isNotBlank()
    val clashfinder = clashUser && known.clashfinderKey
    val photos = known.photos != PhotoAccess.NONE
    val contacts = known.knownTimelines >= 1
    val scope = known.spotifyScope.orEmpty()
    // Logged in is not enough: a login from before playlists were asked for can read
    // but not write, and a playlist is the whole of what Spotify is here for.
    val playlists = known.spotifyConnected && "playlist-modify" in scope

    return ServiceGraph(
        listOf(
            ServiceNode(
                id = "setlistfm", name = "setlist.fm",
                role = ServiceRole.INPUT, strip = ServiceStrip.DATABASES,
                lit = setlistFm,
                status = when {
                    quotaSpent -> "The shared key is spent for today"
                    !known.setlistFmKeyAvailable -> "Needs an API key"
                    known.setlistFmOwnKey -> "Your own key"
                    else -> "Shared key, bundled with the app"
                },
                unlocks = listOf(
                    "Your attended concerts",
                    "The setlist of any gig",
                    "A friend's line, by username",
                ),
                nextStep = if (setlistFm) null else "Paste a free key of your own",
            ),
            ServiceNode(
                id = "musicbrainz", name = "MusicBrainz",
                role = ServiceRole.INPUT, strip = ServiceStrip.DATABASES,
                lit = true,
                status = "No account needed",
                unlocks = listOf("Song titles when a setlist is empty", "Artist names as you type"),
            ),
            ServiceNode(
                id = "clashfinder", name = "clashfinder",
                role = ServiceRole.INPUT, strip = ServiceStrip.DATABASES,
                lit = clashfinder,
                status = when {
                    clashfinder -> "Signed in as ${known.clashfinderUser}"
                    clashUser -> "Needs your private key as well"
                    known.clashfinderKey -> "Needs your username as well"
                    else -> "Needs a free account"
                },
                unlocks = listOf("Festival timetables: stages, set times, clashes"),
                nextStep = if (clashfinder) null else "Register, then paste your private key",
            ),
            ServiceNode(
                id = "photos", name = "Photos",
                role = ServiceRole.INPUT, strip = ServiceStrip.THIS_PHONE,
                lit = photos,
                status = when (known.photos) {
                    PhotoAccess.FULL -> "Allowed"
                    PhotoAccess.PARTIAL -> "Allowed for the photos you picked"
                    PhotoAccess.NONE -> "Not allowed yet"
                },
                unlocks = listOf("Photos from the night on the gig", "A cover for the playlist"),
                nextStep = if (photos) null else "Allow photo access",
            ),
            ServiceNode(
                id = "tickets", name = "Ticket PDFs",
                role = ServiceRole.INPUT, strip = ServiceStrip.THIS_PHONE,
                lit = true,
                status = "Share a ticket from your mail or files",
                unlocks = listOf("A gig added from its ticket", "The ticket's code, on the day"),
            ),
            ServiceNode(
                id = "location", name = "Location",
                role = ServiceRole.INPUT, strip = ServiceStrip.THIS_PHONE,
                lit = known.location,
                status = if (known.location) "Allowed" else "Not allowed yet",
                unlocks = listOf("An offer to check in when you're at tonight's gig"),
                nextStep = if (known.location) null else "Allow location access",
            ),
            ServiceNode(
                id = "contacts", name = "Contacts",
                role = ServiceRole.INPUT, strip = ServiceStrip.OTHER_PHONES,
                lit = contacts,
                status = when (known.knownTimelines) {
                    0 -> "Nobody yet"
                    1 -> "1 known timeline"
                    else -> "${known.knownTimelines} known timelines"
                },
                unlocks = listOf("Their lines beside yours", "Their photos from nights you shared"),
                nextStep = if (contacts) null else "Swipe left from your timeline to swap cards",
            ),
            // Experimental (#462): lit only for as long as a check-in keeps it running,
            // so most of the time it is dark, and that is its normal state.
            ServiceNode(
                id = "gossip", name = "Gossip",
                role = ServiceRole.INPUT, strip = ServiceStrip.OTHER_PHONES,
                lit = known.gigActive,
                status = if (known.gigActive) {
                    "Checked in, and gossip is active"
                } else {
                    "Lights only while you're checked in at a gig"
                },
                unlocks = listOf("Log lines from phones nearby"),
                nextStep = if (known.gigActive) null else "Check in at tonight's gig",
                experimental = true,
            ),
            ServiceNode(
                id = "spotify", name = "Spotify",
                role = ServiceRole.ALCOVE, strip = ServiceStrip.SERVICES,
                lit = playlists,
                status = when {
                    !known.spotifyConnected -> "Not logged in. The shared app admits five people"
                    !playlists -> "Logged in, but playlist permission is missing"
                    // Logins made before photo covers existed carry every playlist
                    // permission but not the one covers need. Still lit: the playlist
                    // is the job, the cover is a nicety.
                    "ugc-image-upload" !in scope -> "Playlists yes, photo covers need a fresh login"
                    else -> "Logged in"
                },
                unlocks = listOf("A playlist of the night", "Your photo as its cover"),
                nextStep = when {
                    !known.spotifyConnected -> "Log in with Spotify"
                    !playlists -> "Log out and in again"
                    else -> null
                },
            ),
            ServiceNode(
                id = "calendar", name = "Calendar",
                role = ServiceRole.ALCOVE, strip = ServiceStrip.THIS_PHONE,
                lit = known.calendar,
                status = if (known.calendar) "Allowed" else "Not allowed yet",
                unlocks = listOf("An upcoming gig in your calendar"),
                nextStep = if (known.calendar) null else "Allow calendar access",
            ),
        ),
    )
}
