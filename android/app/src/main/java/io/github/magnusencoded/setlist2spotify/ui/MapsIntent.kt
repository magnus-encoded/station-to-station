package io.github.magnusencoded.setlist2spotify.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Fires the OS maps app at a venue by text query — see [venueMapsQuery]. No lat/long:
 * setlist.fm doesn't have any, so `geo:0,0?q=` lets the OS geocode the name itself.
 * A no-op (not a crash) when no maps app is installed; #33 needs its own fallback.
 */
fun openVenueInMaps(context: Context, query: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query)))
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // ponytail: no maps app to hand off to; degrade to nothing rather than crash.
    }
}
