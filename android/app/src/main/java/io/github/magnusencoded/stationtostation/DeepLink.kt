package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.StoredLog
import io.github.magnusencoded.stationtostation.data.setlistfm.parseSetlistId
import java.net.URLDecoder
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** The screens a link can name and that do something. `log` is reserved and is not one. */
enum class LinkScreen { TIMELINE, TIMELINES, PROGRAMME, SETTINGS }

/** The links that keep their own handler, carried to it unchanged. */
enum class PassThroughKind { FRIEND, HANDOVER, CALLBACK, TICKET }

/** What a `station-to-station://` link asks for. Closed: a link outside it does nothing. */
sealed interface LinkIntent {
    /** [date] is ISO `yyyy-MM-dd`; only [LinkScreen.TIMELINE] and [LinkScreen.TIMELINES] carry one. */
    data class Open(val screen: LinkScreen, val date: String? = null) : LinkIntent

    data class OpenGig(val id: String) : LinkIntent

    /** [date] is ISO `yyyy-MM-dd`. A field the link did not give, or gave blank, is null. */
    data class AddGig(val artist: String?, val venue: String?, val date: String?) : LinkIntent

    /** [replacements] is keyed by 1-based song number. */
    data class WriteToLog(
        val gigId: String,
        val appends: List<String>,
        val replacements: Map<Int, String>,
    ) : LinkIntent

    data class LegacyPlace(val gigId: String, val at: GigLink) : LinkIntent

    data object LegacyMe : LinkIntent

    data class LegacyFixture(val name: String, val open: Boolean) : LinkIntent

    data class PassThrough(val kind: PassThroughKind, val link: String) : LinkIntent
}

private const val SCHEME = "station-to-station"
private const val OLD_SCHEME = "setlist2spotify"

private val passThroughs = mapOf(
    "friend" to PassThroughKind.FRIEND,
    "handover" to PassThroughKind.HANDOVER,
    "callback" to PassThroughKind.CALLBACK,
    "ticket" to PassThroughKind.TICKET,
)

/**
 * Reads a whole link into what it asks for, or null when it asks for nothing: a foreign
 * scheme, a blank link, a reserved-but-unwired screen (`log`), an unknown screen or an
 * unknown action on a known one.
 *
 * Screen and action names match case-insensitively; ids and text keep their case.
 * Reserved screen names are checked before the legacy place grammar, so a **Line**
 * named like one has no place link. Grammar and cases: `fixtures/deeplinks/README.md`.
 */
fun parseDeepLink(link: String): LinkIntent? {
    val trimmed = link.trim()
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd < 0) return null
    val scheme = trimmed.substring(0, schemeEnd).lowercase()
    if (scheme != SCHEME && scheme != OLD_SCHEME) return null

    val rest = trimmed.substring(schemeEnd + 3).substringBefore('#')
    val query = rest.substringAfter('?', "")
    val segments = rest.substringBefore('?').split('/').filter { it.isNotEmpty() }.map(::decodePath)
    val screen = segments.firstOrNull()?.lowercase() ?: return null
    val action = segments.getOrNull(2)?.lowercase()
    val items = queryItems(query)

    passThroughs[screen]?.let { return LinkIntent.PassThrough(it, trimmed) }
    return when (screen) {
        "timeline" -> when {
            segments.size == 1 -> LinkIntent.Open(LinkScreen.TIMELINE, isoDate(items.value("date")))
            segments.size == 2 && segments[1].lowercase() == "add-gig" -> LinkIntent.AddGig(
                artist = items.value("artist")?.trim()?.ifEmpty { null },
                venue = items.value("venue")?.trim()?.ifEmpty { null },
                date = isoDate(items.value("date")),
            )
            else -> null
        }
        "timelines" ->
            if (segments.size == 1) LinkIntent.Open(LinkScreen.TIMELINES, isoDate(items.value("date"))) else null
        "programme" -> if (segments.size == 1) LinkIntent.Open(LinkScreen.PROGRAMME) else null
        "settings" -> if (segments.size == 1) LinkIntent.Open(LinkScreen.SETTINGS) else null
        "log" -> null
        "gig" -> when {
            segments.size == 1 -> items.value("id")?.trim()?.ifEmpty { null }?.let { LinkIntent.OpenGig(it) }
            segments.size == 2 -> segments[1].trim().ifEmpty { null }?.let { LinkIntent.OpenGig(it) }
            segments.size == 3 && action == "write-to-log" -> writeToLog(segments[1].trim(), query)
            else -> null
        }
        "me" -> LinkIntent.LegacyMe
        "fixture" ->
            if (segments.size in 2..3 && (segments.size == 2 || action == "open"))
                LinkIntent.LegacyFixture(segments[1], open = segments.size == 3)
            else null
        else ->
            if (segments.size <= 2) parseGigLink(segments)?.let { (gig, at) -> LinkIntent.LegacyPlace(gig, at) }
            else null
    }
}

private fun writeToLog(gigId: String, query: String): LinkIntent? {
    if (gigId.isEmpty()) return null
    val appends = mutableListOf<String>()
    val replacements = sortedMapOf<Int, String>()
    for (raw in query.split('&').filter { it.isNotEmpty() }) {
        if (!raw.contains('=')) {
            decodeQuery(raw).trim().takeIf { it.isNotEmpty() }?.let(appends::add)
            continue
        }
        val n = decodeQuery(raw.substringBefore('=')).trim().toIntOrNull()?.takeIf { it >= 1 } ?: continue
        val text = decodeQuery(raw.substringAfter('=')).trim().takeIf { it.isNotEmpty() } ?: continue
        replacements[n] = text
    }
    return LinkIntent.WriteToLog(gigId, appends, replacements)
}

/** Raw items in order, split before decoding: `Uri.getQueryParameter` cannot tell `Song` from `3=Song`. */
private fun queryItems(query: String): List<Pair<String, String?>> =
    query.split('&').filter { it.isNotEmpty() }.map {
        decodeQuery(it.substringBefore('=')) to
            if (it.contains('=')) decodeQuery(it.substringAfter('=')) else null
    }

private fun List<Pair<String, String?>>.value(name: String): String? =
    firstOrNull { it.first == name }?.second

/** ISO `yyyy-MM-dd` and a real calendar day, or null. */
private fun isoDate(text: String?): String? {
    if (text == null || !Regex("""\d{4}-\d{2}-\d{2}""").matches(text)) return null
    return try {
        LocalDate.parse(text).toString()
    } catch (e: DateTimeParseException) {
        null
    }
}

private fun decodePath(s: String): String = decode(s)

private fun decodeQuery(s: String): String = decode(s)

/** Percent-decoding only: `+` stays a plus, as on iOS, so a link means the same on both. */
private fun decode(s: String): String = try {
    URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
} catch (e: IllegalArgumentException) {
    s
}

/**
 * The **Gig** in a nearest-date scroll: the smallest distance in days from [target],
 * ties to the earlier **Gig**. Null when there is nothing to scroll to.
 */
fun nearestGig(gigs: List<Pair<String, LocalDate>>, target: LocalDate): String? =
    gigs.minWithOrNull(
        compareBy<Pair<String, LocalDate>> { kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(it.second, target)) }
            .thenBy { it.second },
    )?.first

/** Which rule the add form applies: a night before [today] is one I was at, anything else is one I am going to. */
fun nightKind(date: LocalDate?, today: LocalDate): NightKind =
    if (date == null || !date.isBefore(today)) NightKind.GOING_TO else NightKind.WAS_AT

/**
 * [GOING_TO] is planned and claims nothing; [WAS_AT] is attended, minted locally with
 * nothing on setlist.fm to collide with.
 */
enum class NightKind { GOING_TO, WAS_AT }

enum class OpenGigPlan { OPEN, FETCH_THEN_OPEN, REFUSE }

/**
 * What a link to a **Gig** does. On my **Line**, planned or attended, it opens and
 * adds nothing: an invite must not turn a night I attended into a plan. Otherwise a
 * setlist.fm id is fetched and opened unkept, and anything else is refused.
 */
fun planOpenGig(id: String, onMyLine: Boolean): OpenGigPlan = when {
    onMyLine -> OpenGigPlan.OPEN
    parseSetlistId(id) != null -> OpenGigPlan.FETCH_THEN_OPEN
    else -> OpenGigPlan.REFUSE
}

/**
 * The **Log** after a `write-to-log` link, as typed input would leave it. Replacements
 * go first, in song order: song N replaces, N just past the end appends, and one
 * further out is ignored, so a link never makes a **Gap** typing could not. The
 * [appends] then follow in order.
 */
fun StoredLog.writing(appends: List<String>, replacements: Map<Int, String>): StoredLog {
    var log = this
    for ((n, text) in replacements.toSortedMap()) {
        val at = n - 1
        log = when {
            at < log.songs.size -> log.correctingAt(at, text)
            at == log.songs.size -> log.adding(text)
            else -> log
        }
    }
    return appends.fold(log) { l, text -> l.adding(text) }
}
