package io.github.magnusencoded.stationtostation.ui

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import java.time.ZoneId

/**
 * Inserts a real calendar event for a gig you're going to and hands back its content
 * URI — content://com.android.calendar/events/<id>, the handle the gig screen keeps
 * and later opens with ACTION_VIEW. That handle is the whole reason this replaces the
 * old ACTION_INSERT intent (#55): a fire-and-forget intent could never tell us which
 * event it made, so the leaf could not show a link to it.
 *
 * Needs WRITE_CALENDAR (to insert) and READ_CALENDAR (to find a calendar to write to);
 * the caller requests both at swipe time. Returns null when there's no date to place
 * it on, no writable calendar, or the provider refuses — each degrades to a toast, no
 * link, no stage advance.
 *
 * Runs off the main thread (a couple of binder round-trips): the caller wraps it in IO.
 */
fun insertCalendarEvent(resolver: ContentResolver, setlist: FmSetlist): Uri? {
    val startMs = setlist.localDate()?.atTime(19, 0)
        ?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: return null
    val calendarId = primaryCalendarId(resolver) ?: return null
    val values = ContentValues().apply {
        put(CalendarContract.Events.CALENDAR_ID, calendarId)
        put(CalendarContract.Events.TITLE, setlist.artist?.name ?: "Concert")
        put(CalendarContract.Events.EVENT_LOCATION, setlist.venueLine())
        put(CalendarContract.Events.DTSTART, startMs)
        // setlist.fm records no running time; a three-hour block is a sane default the
        // user trims in their own calendar app, same spirit as the 19:00 start.
        put(CalendarContract.Events.DTEND, startMs + 3 * 60 * 60 * 1000L)
        put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
    }
    return resolver.insert(CalendarContract.Events.CONTENT_URI, values)
}

/**
 * Which calendar to write into: the account's primary if one is marked, otherwise the
 * first visible calendar. Good enough — the user moves the event in their own app if
 * it landed on the wrong one, and choosing a calendar is not a decision this app owns.
 *
 * ponytail: no calendar-picker UI. Add one only if "wrong calendar" is ever a complaint.
 */
private fun primaryCalendarId(resolver: ContentResolver): Long? {
    val projection = arrayOf(
        CalendarContract.Calendars._ID,
        CalendarContract.Calendars.IS_PRIMARY,
        CalendarContract.Calendars.VISIBLE,
    )
    resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
        val idCol = c.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
        val primaryCol = c.getColumnIndexOrThrow(CalendarContract.Calendars.IS_PRIMARY)
        val visibleCol = c.getColumnIndexOrThrow(CalendarContract.Calendars.VISIBLE)
        var firstVisible: Long? = null
        while (c.moveToNext()) {
            if (c.getInt(primaryCol) == 1) return c.getLong(idCol)
            if (firstVisible == null && c.getInt(visibleCol) == 1) firstVisible = c.getLong(idCol)
        }
        return firstVisible
    }
    return null
}
