package io.github.magnusencoded.stationtostation.data

/**
 * The night's set as one line, woven from the two records that describe it (#268).
 *
 * **Two lists of the same night is one list too many.** setlist.fm's record and my
 * **Log** were drawn as separate lists, one above the other, so a song both of them
 * held was printed twice — and the reader had to do the alignment in their head to
 * find out that the two lists agreed. Which is precisely the thing an inline diff
 * exists to stop doing: matching lines are shown *once*, marked as matching.
 *
 * **Neither side is merged into the other.** This produces a reading order and
 * nothing else; the two records stay exactly as they were on disk, which is the rule
 * [StoredLog] was built around — someone else filling in what I missed is the good
 * case, and quietly overwriting either side with the other loses a fact.
 *
 * **Why a real diff and not an index walk.** The two orders are the same order right
 * up until they aren't: my log is what I managed to type, so it drops songs, and one
 * dropped song puts every later index out by one against the published set. Pairing
 * by position would then report every remaining song as a disagreement. An LCS is
 * the ordinary answer to that and it costs nothing on a set of thirty.
 */

/**
 * One line of the woven set: a published row, one of my log's entries, or both.
 *
 * Indices rather than titles, so the caller keeps whatever each side actually holds —
 * a cover credit and a tape marker on one, a **Remembered Line** and a **Gap** on the
 * other. Never both null.
 */
data class WovenSong(val published: Int?, val logged: Int?) {
    /** The strongest thing a row can say: two records, independently, agree. */
    val both: Boolean get() = published != null && logged != null
}

/**
 * Align the published titles against my logged ones, longest-common-subsequence.
 *
 * [published] is nullable per entry so a row that is not a song — an encore marker —
 * can be passed through in its place without ever matching anything. A **Gap** (a
 * blank log entry) matches nothing either: "one I couldn't name" is a statement that
 * no title was captured, so pairing it with a published title would be inventing the
 * very claim it exists to avoid making.
 *
 * On a tie, the published row goes first: the shared record is the spine, and mine is
 * what hangs off it.
 */
fun weaveSetlist(published: List<String?>, logged: List<String>): List<WovenSong> {
    fun match(i: Int, j: Int): Boolean {
        val p = published[i] ?: return false
        val l = logged[j]
        return l.isNotBlank() && sameSong(p, l)
    }

    // lcs[i][j] = length of the longest common subsequence of the tails from i and j.
    val lcs = Array(published.size + 1) { IntArray(logged.size + 1) }
    for (i in published.indices.reversed()) {
        for (j in logged.indices.reversed()) {
            lcs[i][j] =
                if (match(i, j)) lcs[i + 1][j + 1] + 1
                else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
    }

    return buildList {
        var i = 0
        var j = 0
        while (i < published.size && j < logged.size) {
            when {
                match(i, j) -> add(WovenSong(i++, j++))
                lcs[i + 1][j] >= lcs[i][j + 1] -> add(WovenSong(i++, null))
                else -> add(WovenSong(null, j++))
            }
        }
        while (i < published.size) add(WovenSong(i++, null))
        while (j < logged.size) add(WovenSong(null, j++))
    }
}
