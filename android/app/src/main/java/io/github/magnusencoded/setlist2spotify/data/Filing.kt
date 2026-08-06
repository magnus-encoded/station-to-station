package io.github.magnusencoded.setlist2spotify.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSetlist

/**
 * The night's facts, parked in the notification shade while you fill in setlist.fm's
 * form in the browser.
 *
 * **The problem this solves.** setlist.fm's add form takes no prefill parameters
 * (verified, see [SETLISTFM_ADD_URL]), so the clipboard is the only channel into it —
 * and the clipboard holds exactly one thing while the form asks for five. Worse, the
 * app screen that knows the artist, venue, town and date is by definition *not* on
 * screen once Chrome has the foreground. So those four were crossing the app switch
 * in the Historian's memory, which is where a misremembered venue on a shared public
 * record comes from.
 *
 * **Why a notification.** It is the one surface Android keeps in reach of another
 * app. One notification per field, grouped: pull the shade down over the form, tap
 * the value you need, paste, carry on. Any order, any number of times — an
 * autocomplete that swallowed the first paste is answered by tapping again rather
 * than by going back to the app.
 *
 * A tap must **not** open the app. Foregrounding would throw you out of the browser
 * mid-form, which is the whole thing being avoided, so the tap is a broadcast to
 * [CopyReceiver] and the app never comes forward.
 *
 * ponytail: no re-posting, no stepper, no state. Each notification is a fixed value
 * with a fixed action, and swiping the group summary clears all of them.
 */
private const val FILING_CHANNEL = "filing"
private const val FILING_GROUP = "io.github.magnusencoded.setlist2spotify.FILING"

/** Summary owns the base id; each field sits above it. */
private const val FILING_SUMMARY_ID = 8100

const val EXTRA_FILING_LABEL = "filing_label"
const val EXTRA_FILING_VALUE = "filing_value"

/**
 * Puts one field on the clipboard, from the shade, without waking the app.
 *
 * **The bit that has to be true for any of this to work:** a background broadcast can
 * still *write* the clipboard. Android 10 restricted clipboard access for apps without
 * focus — that restriction is on reading, and writing is what this does. If a future
 * Android closes that too, this fails in the worst possible way: silently, leaving
 * whatever was on the clipboard before to be pasted into a public database. Hence the
 * toast — it is not a courtesy, it is the only evidence the copy happened, and its
 * absence is the signal that this approach has died.
 */
class CopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra(EXTRA_FILING_LABEL) ?: return
        val value = intent.getStringExtra(EXTRA_FILING_VALUE) ?: return
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(context, "$label copied — paste it in", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Post one notification per field of [setlist]'s filing, plus the summary that groups
 * them. Replaces any previous filing: ids are positional, so filing a second night
 * overwrites the first rather than stacking two sets of values in the shade — two
 * "Venue" notifications from different gigs is exactly the confusion this exists to
 * prevent.
 *
 * Silently does nothing when there is nothing to file. Notification permission is the
 * caller's problem: [postFiling] is a best-effort *upgrade* to the handoff, and the
 * clipboard-and-browser path it accompanies must work whether or not this ran.
 */
fun postFiling(context: Context, setlist: FmSetlist, log: StoredLog) {
    val fields = filingFields(setlist, log)
    if (fields.isEmpty()) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    manager.createNotificationChannel(
        NotificationChannel(
            FILING_CHANNEL,
            "Filing a night",
            // Low: this is a tray of values you went looking for, not news. It must
            // never buzz — you are standing in a form when it appears.
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "The night's details, ready to paste into setlist.fm." },
    )
    clearFiling(context)
    fields.forEachIndexed { i, field ->
        val intent = Intent(context, CopyReceiver::class.java)
            .putExtra(EXTRA_FILING_LABEL, field.label)
            .putExtra(EXTRA_FILING_VALUE, field.value)
        val tap = PendingIntent.getBroadcast(
            context,
            FILING_SUMMARY_ID + 1 + i,
            intent,
            // IMMUTABLE is required from 31 and correct anyway — nothing outside this
            // app has any business editing which value a tap copies. UPDATE_CURRENT so
            // re-filing the same slot carries the new night's value, not the old one.
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            FILING_SUMMARY_ID + 1 + i,
            Notification.Builder(context, FILING_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle(field.label)
                .setContentText(field.shown)
                // The songs are a paste, not a line: expanded, the shade shows what is
                // actually going to land in the form.
                .setStyle(Notification.BigTextStyle().bigText(field.value))
                .setContentIntent(tap)
                // A tap is a copy, not a dismissal. Pasting the venue twice because the
                // autocomplete ate the first one is the ordinary case, not an error.
                .setAutoCancel(false)
                .setGroup(FILING_GROUP)
                .build(),
        )
    }
    manager.notify(
        FILING_SUMMARY_ID,
        Notification.Builder(context, FILING_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("Filing ${setlist.artist?.name ?: "this night"}")
            .setContentText("Tap a value to copy it, then paste. Swipe to finish.")
            .setGroup(FILING_GROUP)
            .setGroupSummary(true)
            // The summary is the "done" gesture: dismissing it takes the whole tray
            // with it, so finishing costs one swipe rather than five.
            .build(),
    )
}

/** Take the tray down. Filing again, or finishing, both end here. */
fun clearFiling(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    // One more than any filing can have, so a night with fewer fields than the last
    // one leaves nothing of the last one behind.
    for (id in FILING_SUMMARY_ID..FILING_SUMMARY_ID + 8) manager.cancel(id)
}
