package io.github.magnusencoded.stationtostation.data.photos

import java.time.LocalDate
import java.time.ZoneId

/**
 * The night a **Gig** was photographed on, and what a keepsake's capture time is
 * when the file does not carry one.
 *
 * This is the logic layer (ADR-0001): a domain decision, not a MediaStore or
 * PhotoKit detail, so it is the same rule on both platforms and both assert it
 * from the same cases. The plumbing that runs a query with it — [PhotoRepository]
 * here, `PhotoLibrary` on iOS — is idiomatic per platform and is not.
 *
 * Field for field with iOS's `PhotoWindow.swift`.
 */

/**
 * The hour the night is taken to end. A set that starts at 23:00 is photographed
 * on the following calendar day, and setlist.fm dates the show by when it
 * *started*, so the window has to reach past midnight or the whole night is
 * missed.
 */
const val PHOTO_WINDOW_END_HOUR = 6

/** The gig's date plus the small hours after it, in epoch milliseconds, inclusive. */
fun photoWindow(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): LongRange {
    val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val to = date.plusDays(1).atTime(PHOTO_WINDOW_END_HOUR, 0)
        .atZone(zone).toInstant().toEpochMilli()
    return from..to
}

/**
 * When the camera took it — not when it was attached, which is admin rather than
 * history.
 *
 * [taken] is the camera's own stamp (EXIF's `DATE_TAKEN` here,
 * `PHAsset.creationDate` on iOS) and is absent on anything carrying no timestamp.
 * [added] is the library's own record of first seeing it (`DATE_ADDED` /
 * `PHAsset.modificationDate`) and is at least the right order of magnitude for a
 * night.
 *
 * Null when neither answers, because a wrong timestamp on a keepsake is worse than
 * an honest gap. A non-positive value counts as absent: a zero epoch is a missing
 * field written out rather than a photo taken in 1970.
 */
fun capturedAtMs(taken: Long?, added: Long?): Long? =
    taken?.takeIf { it > 0 } ?: added?.takeIf { it > 0 }

/**
 * Whether a keepsake belongs to the night [window] describes. Anything with no
 * usable timestamp at all is out: it cannot be shown to be from that night.
 */
fun isInPhotoWindow(window: LongRange, taken: Long?, added: Long?): Boolean =
    capturedAtMs(taken, added)?.let { it in window } ?: false
