package io.github.magnusencoded.stationtostation.data

import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.ScrapedFestival
import io.github.magnusencoded.stationtostation.data.setlistfm.SetlistFmClient
import io.github.magnusencoded.stationtostation.ui.NIGHT_ENDS
import io.github.magnusencoded.stationtostation.ui.TimelineNode
import io.github.magnusencoded.stationtostation.ui.groupIntoFestivals
import java.util.Locale

/**
 * The rules and the sequence that drive the Timeline's Spine — ADR-0001's logic
 * layer, written the same shape here and in iOS's `TimelineLogic.swift`, and
 * asserted by the same cases in `TimelineLogicTest` / `TimelineLogicTests`.
 *
 * It holds no state of its own and reaches the device only through the
 * [TimelinePlumbing] handed to it, which is the whole seam: a test hands it a
 * fake and asks a question, with no device and no network. That is what the four
 * rules living here have in common — every one of them broke in the field and
 * none of them could be reached by a test before:
 *
 * - a **Festival** whose real name never resolved must be retried *on load*, not
 *   only after a fresh import (a reopened app kept showing the venue name);
 * - a fixture seeded at launch is the Spine for that run and the stored cache
 *   must not clobber it (a CI screenshot came back empty);
 * - a playlist is named `Year – Artist – Festival-or-Venue` (this drifted between
 *   the platforms and cost a commit to bring back in line);
 * - shared concerts are the intersection of two **Attended** lists, each paged to
 *   a named cap.
 *
 * Two of those are call-order rules, which is exactly why this layer is allowed
 * to call plumbing rather than being required to be pure: no pure function can
 * express "don't read the store when a fixture was seeded".
 *
 * On this platform the split reads partly as rearrangement — the rules were
 * already here and already worked. That is ADR-0001's accepted cost: the point is
 * that the shared half stops being interleaved with the Android-only half, so it
 * can be asserted against iOS.
 */

// --- What a source hands over ---

/**
 * A Spine as one source hands it over: my own **Line**, every **Lane** beside it,
 * and the **Festival** names resolved so far.
 *
 * The stored cache and a bundled weave fixture produce the same thing — the
 * fixture additionally knows *whose* line is mine and in what **Lane** order the
 * friends sit, which the store has no opinion about (it is keyed by username and
 * nothing more). Those two fields are empty coming off disk.
 */
data class LoadedSpine(
    /** The setlist.fm username whose **Line** is the Spine. */
    val me: String = "",
    /** The **Lanes**, nearest the Spine first. Empty from the store, which records no order. */
    val friends: List<Friend> = emptyList(),
    /** My **Attended** shows: the Spine itself. */
    val mine: List<FmSetlist> = emptyList(),
    /** Every other **Line**, by setlist.fm username. */
    val byFriend: Map<String, List<FmSetlist>> = emptyMap(),
    /** Every **Festival** identity known so far, and which **Gigs** carry one (#166). */
    val festivals: Festivals = Festivals(),
)

/** One page of an **Attended** list, with the total setlist.fm reports for it. */
data class AttendedPage(val shows: List<FmSetlist>, val total: Int)

// --- The device half ---

/**
 * Everything the logic layer needs from the device, and nothing more.
 *
 * Implemented for real by [DeviceTimelinePlumbing] below (the store, the client —
 * idiomatic Android, and deliberately unlike iOS's actor and IPv4-forced session),
 * and by a fake in the tests. If a fake ever becomes laborious to write, this
 * interface is wrong.
 */
interface TimelinePlumbing {

    /**
     * The Spine seeded at launch from a bundled weave fixture, when one was; null
     * in a normal run.
     *
     * This and [storedSpine] are two sources of the same thing, which is the
     * point: on iOS, seeding used to be a guard clause inside the loader plus an
     * instance flag. Which source is in play is now the logic layer's own
     * knowledge, decided in one place — [TimelineLogic.loadSpine].
     */
    suspend fun seededSpine(): LoadedSpine?

    /** The Spine as last written to disk, or null when nothing has been. */
    suspend fun storedSpine(me: String): LoadedSpine?

    /** One page of a user's **Attended** list. */
    suspend fun attendedPage(user: String, page: Int): AttendedPage

    /**
     * The **Festival** behind a setlist page, or null when that night belongs to none
     * — which is the common and correct answer, and is what stops it being asked again.
     */
    suspend fun festivalAt(setlistUrl: String): ScrapedFestival?

    /**
     * Persists resolved **Festival** identities, their membership, and which **Gigs**
     * have now been asked about. Merge semantics belong to the store, which already has
     * them and is already the cross-platform contract.
     */
    suspend fun saveFestivals(
        festivals: Map<String, StoredFestival>,
        idByShow: Map<String, String>,
        asked: Set<String>,
    )
}

// --- The rules ---

/**
 * What an evening of several acts is called when nothing knows it was a festival:
 * **the headliner, then its supports in parentheses.** "Devin Townsend (Haken)".
 *
 * This is the label half of #166. A **Node** was named after its venue whenever the
 * festival-name lookup had not landed — so 24 November 2019 at Sentrum Scene, a
 * headline show with support, read as a **Festival** called "Sentrum Scene". A room is
 * not an event, and 44 nights on the line are shaped like that one.
 *
 * **The headliner is who played last, and every fallback is a weaker answer to that
 * same question** — never a different question:
 *
 * 1. **the latest scheduled set time**, where the source published them. setlist.fm
 *    puts them on the festival page ([setTimes] is what that scrape found), and they
 *    are the real evidence for the running order rather than a stand-in for it;
 * 2. **the longest set** — right for support-plus-headliner, which is the case this
 *    fixes, and uninformative for a festival day, which is the case an identity is
 *    supposed to name anyway;
 * 3. **the order the source returned**, which at least makes the label stable rather
 *    than arbitrary. Ties at every rung fall through to it.
 *
 * **Supports are capped at two.** Beyond that a **Node** is growing into a list, and
 * the list already exists one **Resolution** in.
 */
fun billedAs(
    shows: List<FmSetlist>,
    setTimes: Map<String, String> = emptyMap(),
    supportCap: Int = SUPPORT_CAP,
): String {
    val named = shows.filter { !it.artist?.name.isNullOrBlank() }
    if (named.isEmpty()) return shows.firstOrNull()?.venue?.name ?: "Several acts"
    // maxByOrNull keeps the first on a tie, which is the source's own order.
    val headliner = named.filter { setTimes[it.id] != null }
        .maxByOrNull { playedLast(setTimes.getValue(it.id)) }
        ?: named.maxByOrNull { it.performed().size }
        ?: named.first()
    val supports = named.filter { it.id != headliner.id }.map { it.artist!!.name }
    val head = headliner.artist!!.name
    if (supports.isEmpty()) return head
    val shown = supports.take(supportCap)
    val tail = if (supports.size > shown.size) "${shown.joinToString(", ")} +${supports.size - shown.size}"
    else shown.joinToString(", ")
    return "$head ($tail)"
}

/** Two supports named, the rest counted. See [billedAs]. */
const val SUPPORT_CAP = 2

/**
 * What was scraped, as an identity this app owns.
 *
 * **The identity is ours; the vendors' are attributes.** The id is minted from
 * setlist.fm's slug so that two devices — and the same device twice — agree on it
 * without asking anyone, but it is *ours*: the slug sits beside it as enrichment, the
 * way a **Gig** already carries its setlist.fm id since #107, and storage never moves
 * because a vendor's key changed. A **Bill** you typed has no vendor key at all, which
 * is exactly why a vendor key cannot be the identity.
 *
 * Null when the page gave neither a name nor a slug: an identity with nothing to
 * identify it is not one.
 */
private fun ScrapedFestival.toStoredFestival(): StoredFestival? {
    val name = name?.takeUnless { it.isBlank() } ?: return null
    val id = festivalIdForSlug(slug ?: "name:${name.lowercase(Locale.ROOT)}")
    return StoredFestival(
        id = id,
        name = name,
        rangeFrom = rangeFrom,
        rangeTo = rangeTo,
        setlistFmSlug = slug,
        source = StoredFestival.FestivalSource.SCRAPED,
        dayMembership = dayMembership,
        setTimes = setTimes,
    )
}

/**
 * How late in the *night* a `HH:mm` set time is, as something sortable.
 *
 * A 00:30 slot closed the evening; it did not open it. This draws the same line
 * [NIGHT_ENDS] does for a check-in — the night is still going on at 01:30 — because a
 * headliner picked by clock time alone would hand the billing to the first band on.
 */
private fun playedLast(time: String): String =
    if (time < NIGHT_ENDS.toString()) "~$time" else time

class TimelineLogic(private val plumbing: TimelinePlumbing) {

    companion object {
        /**
         * How many pages of someone's **Attended** list a shared-concerts lookup
         * will pull — 20 per page, so 60 concerts each side.
         *
         * ponytail: a named runaway guard, not a policy. Raising it is an informed
         * decision about call volume against how far back two people's overlap
         * reaches; buried in a loop, nobody could make that decision at all.
         */
        const val ATTENDED_PAGE_CAP = 3

        /**
         * What a playlist made from [setlist] is called: `Year – Artist – Where`.
         *
         * Year first, so an alphabetical playlist library falls into chronological
         * order and the night reads as "when, who, where". A **Festival** cluster's
         * "where" is the Festival name standing in for the venue — a stage is not a
         * place — with the year stripped back out of it ("Tons of Rock 2026" →
         * "Tons of Rock"), since the year already leads. A lone show keeps its venue.
         *
         * Pure, so a test needs no plumbing at all to ask. This is the rule that
         * shipped wrong output from correct sequencing on iOS and had to be brought
         * back in line with this one by hand; it is asserted identically on both
         * platforms now.
         */
        fun playlistName(
            setlist: FmSetlist,
            mine: List<FmSetlist>,
            festivals: Festivals,
        ): String {
            val artistName = setlist.artist?.name ?: ""
            val festival = groupIntoFestivals(mine, festivals)
                .filterIsInstance<TimelineNode.Festival>()
                .find { node -> node.shows.any { it.id == setlist.id } }
            val where = festival?.identity?.name?.let { name ->
                setlist.year()?.let { name.replace(it, "").trim().trim('-', '–').trim() } ?: name
            } ?: setlist.venue?.name
            return listOfNotNull(setlist.year(), artistName.ifBlank { null }, where)
                .joinToString(" – ").ifBlank { "Setlist" }
        }
    }

    // --- The sequence ---

    /**
     * The Spine for this run, handed to [onSpine] as soon as it exists and again
     * if retrying the unresolved **Festival** names finds any.
     *
     * Two emissions on purpose. A cached Spine has to be on screen before any
     * network is — that is the whole reason it is cached — so the names cannot be
     * awaited before the first one. Expressing it as a sequence here rather than
     * as two methods and a flag in the view model is the point of the layer: the
     * order is readable in one place, and a test can assert it.
     *
     * The seeded fixture wins outright and the store is never even read: it is the
     * Spine for that run, and in CI the stored cache is empty, so reading it is
     * precisely how a screenshot came back blank.
     */
    suspend fun loadSpine(me: String, onSpine: (LoadedSpine) -> Unit) {
        plumbing.seededSpine()?.let { seeded ->
            onSpine(seeded)
            return
        }
        val spine = plumbing.storedSpine(me) ?: return
        onSpine(spine)

        // A cached Spine may hold evenings whose Festival identity was never resolved
        // — the import failed the scrape, or predates it. Resolving only after a
        // fresh import is what left iOS showing venue names on a reopened app.
        val found = resolveFestivals(spine.mine, spine.festivals)
        if (found == spine.festivals) return
        onSpine(spine.copy(festivals = found))
    }

    /**
     * Asks setlist.fm which **Festival**, if any, the unidentified evenings on [mine]
     * belong to, and folds the answers into [known].
     *
     * **A Section is the candidate, not a run of nights.** Several acts at one venue on
     * one date is the shape a festival day has; it is also the shape a headline show
     * with support has, and the *only* way to tell them apart is to ask a source that
     * knows. So this asks, and takes no for an answer: a night whose page carries no
     * festival link is recorded as asked ([TimelineCache.festivalsAsked]) and never
     * asked again, which is what keeps 44 multi-act nights from costing 44 fetches
     * every launch.
     *
     * Nothing here infers. If the page says nothing, the evening stays a **Section**
     * for good, and that is the record saying the smaller true thing.
     */
    suspend fun resolveFestivals(mine: List<FmSetlist>, known: Festivals): Festivals {
        val candidates = groupIntoFestivals(mine, known)
            .filterIsInstance<TimelineNode.Section>()
            .filter { section -> section.shows.none { it.id in known.asked } }
        if (candidates.isEmpty()) return known

        val identities = mutableMapOf<String, StoredFestival>()
        val idByShow = mutableMapOf<String, String>()
        val asked = mutableSetOf<String>()
        for (section in candidates) {
            // **A festival answers for every one of its days at once.** The candidates
            // were decided before the first fetch, so a three-day Øya arrives here as
            // three Sections — but the first one's `dayMembership` already claimed the
            // other two. Skipping them saves the setlist page *and* the festival page
            // behind it; memoising the second fetch by url would only have saved half.
            if (section.shows.any { it.id in idByShow }) continue
            val show = section.shows.firstOrNull { !it.url.isNullOrBlank() } ?: continue
            // Only a reply counts as having asked. A fetch that failed in a tunnel is
            // a question still open, and must be asked again on the next launch.
            val page = runCatching { plumbing.festivalAt(show.url!!) }.getOrElse { continue }
            // **The evening is asked about, not the Gig.** Every setlist of a festival
            // carries the same link on its own page, so one page answers for the whole
            // night — and asking act by act would pay per act for one answer.
            asked += section.shows.map { it.id }
            val identity = page?.toStoredFestival() ?: continue
            identities[identity.id] = identity
            // The nights the *source* says are the festival's, plus this evening, which
            // we know is: it is the night whose page carried the link.
            section.shows.forEach { idByShow[it.id] = identity.id }
            identity.dayMembership?.values?.flatten()?.forEach { idByShow[it] = identity.id }
        }
        if (asked.isEmpty()) return known
        plumbing.saveFestivals(identities, idByShow, asked)
        return Festivals(
            byId = known.byId.mergedWith(identities),
            idByShow = known.idByShow + idByShow,
            asked = known.asked + asked,
        )
    }

    /**
     * The nights [friend] and I were both at: the intersection of two **Attended**
     * lists, each paged to [ATTENDED_PAGE_CAP].
     *
     * An intersection and not a merge — **Attended** is the only thing that makes a
     * **Gig** someone's, so a night is shared exactly when it is on both lists.
     * Mine keeps its order, so the result is newest first like every other list.
     */
    suspend fun sharedConcerts(me: String, friend: String): List<FmSetlist> {
        val mine = attended(me)
        val theirs = attended(friend).map { it.id }.toSet()
        return mine.filter { it.id in theirs }
    }

    /**
     * One user's **Attended** list, up to [ATTENDED_PAGE_CAP] pages. Stops early
     * once setlist.fm's reported total is in hand, or a page comes back empty.
     */
    private suspend fun attended(user: String): List<FmSetlist> {
        val all = mutableListOf<FmSetlist>()
        for (page in 1..ATTENDED_PAGE_CAP) {
            val (shows, total) = plumbing.attendedPage(user, page)
            all += shows
            if (all.size >= total || shows.isEmpty()) break
        }
        return all
    }
}

// --- The device half, for real ---

/**
 * The Android plumbing: the store and the setlist.fm client. Stateful and unlovely
 * because the OS makes it so, and not expected to resemble iOS's — ADR-0001 draws
 * the parity line above here, not through here.
 */
class DeviceTimelinePlumbing(
    private val timelines: TimelineStore,
    private val setlistFm: SetlistFmClient,
) : TimelinePlumbing {

    /**
     * Always null: this build has no launch-seed path. Seeding a **Resolution**
     * from a bundled weave fixture is an iOS-only entry point today (CI
     * photographs the Spine there), and a fixture loader nothing invokes would be
     * dead code. The *rule* — a seeded Spine wins and the store is not read —
     * lives in the logic layer and is asserted on both platforms, so the day this
     * platform grows a seed path there is nothing left to get wrong.
     */
    override suspend fun seededSpine(): LoadedSpine? = null

    override suspend fun storedSpine(me: String): LoadedSpine? {
        val cache = timelines.load()
        // Nothing written yet is null, not an empty Spine: a first run must leave
        // whatever is already on screen alone rather than blanking it.
        if (cache.shows.isEmpty() && cache.festivals.isEmpty()) return null
        return LoadedSpine(
            me = me,
            // Not `shows[me]` alone: a night I checked into that setlist.fm has never
            // heard of is on no attended list, and leaves the future lane the moment
            // it stops being a plan. See [spineNights].
            mine = spineNights(cache.shows[me].orEmpty(), cache.planned(), cache.attendance()),
            // Not `shows - me`. A **Contact** whose **Card** carries my own setlist.fm
            // username is my other device, and subtracting my key left that lane empty:
            // every night rendered as mine-only instead of **Joined**, which is the
            // opposite of the truth. Only friends are ever read out of this map
            // (`weaveTimelines` iterates the friends list), so carrying my own key costs
            // nothing and is what makes the self-comparison work.
            //
            // It also closes a worse one: with the lane missing, `loadFriendTimelines`
            // treated it as stale, refetched my own attended list as a friend's, and
            // saved it back over my own — a page-limited fetch overwriting my Spine.
            byFriend = cache.shows,
            festivals = cache.festivalIdentities(),
        )
    }

    override suspend fun attendedPage(user: String, page: Int): AttendedPage {
        val resp = setlistFm.userAttended(user, page)
        return AttendedPage(resp.setlist, resp.total)
    }

    override suspend fun festivalAt(setlistUrl: String): ScrapedFestival? =
        setlistFm.festivalAt(setlistUrl)

    override suspend fun saveFestivals(
        festivals: Map<String, StoredFestival>,
        idByShow: Map<String, String>,
        asked: Set<String>,
    ) {
        timelines.save(festivals = festivals, festivalIdByShow = idByShow, festivalsAsked = asked)
    }
}
