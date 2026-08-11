package io.github.magnusencoded.setlist2spotify.data

import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSetlist
import io.github.magnusencoded.setlist2spotify.data.setlistfm.SetlistFmClient
import io.github.magnusencoded.setlist2spotify.ui.TimelineNode
import io.github.magnusencoded.setlist2spotify.ui.groupIntoFestivals

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
    /** **Festival** name by its cluster's first show id. */
    val festivalNames: Map<String, String> = emptyMap(),
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

    /** The real **Festival** name behind a setlist page, or null if it can't be had. */
    suspend fun festivalName(setlistUrl: String): String?

    /**
     * Persists resolved **Festival** names. Merge semantics belong to the store,
     * which already has them and is already the cross-platform contract.
     */
    suspend fun saveFestivalNames(names: Map<String, String>)
}

// --- The rules ---

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
            festivalNames: Map<String, String>,
        ): String {
            val artistName = setlist.artist?.name ?: ""
            val festival = groupIntoFestivals(mine, festivalNames)
                .filterIsInstance<TimelineNode.Festival>()
                .find { node -> node.shows.any { it.id == setlist.id } }
            val where = festival?.name?.let { name ->
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

        // A cached Spine may hold Festivals whose real names were never resolved —
        // the import failed the scrape, or predates it. Resolving only after a
        // fresh import is what left iOS showing venue names on a reopened app.
        val found = resolveFestivalNames(spine.mine, spine.festivalNames)
        if (found.isEmpty()) return
        onSpine(spine.copy(festivalNames = spine.festivalNames + found))
    }

    /**
     * Fills in the real **Festival** names for the clusters on [mine] — one page
     * fetch per Festival, only for ones [known] doesn't already have, and only
     * where there is a setlist page to scrape. Failures are silent: the venue name
     * stays as the label.
     *
     * Returns what it found, and saves it: a Festival name costs a fetch each, so
     * it is paid once.
     */
    suspend fun resolveFestivalNames(
        mine: List<FmSetlist>,
        known: Map<String, String>,
    ): Map<String, String> {
        val firsts = groupIntoFestivals(mine)
            .filterIsInstance<TimelineNode.Festival>()
            .map { it.shows.first() }
            .filter { it.id !in known && !it.url.isNullOrBlank() }
        if (firsts.isEmpty()) return emptyMap()

        val found = firsts.mapNotNull { show ->
            plumbing.festivalName(show.url!!)?.let { show.id to it }
        }.toMap()
        if (found.isEmpty()) return emptyMap()
        plumbing.saveFestivalNames(found)
        return found
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
        if (cache.shows.isEmpty() && cache.festivalNames.isEmpty()) return null
        return LoadedSpine(
            me = me,
            mine = cache.shows[me].orEmpty(),
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
            festivalNames = cache.festivalNames,
        )
    }

    override suspend fun attendedPage(user: String, page: Int): AttendedPage {
        val resp = setlistFm.userAttended(user, page)
        return AttendedPage(resp.setlist, resp.total)
    }

    override suspend fun festivalName(setlistUrl: String): String? =
        setlistFm.festivalName(setlistUrl)

    override suspend fun saveFestivalNames(names: Map<String, String>) {
        timelines.save(festivalNames = names)
    }
}
