package io.github.magnusencoded.stationtostation.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens the venue's point when supplied, otherwise lets Maps geocode its name.
 * Returns false when no Maps app can receive the handoff.
 */
fun openVenueInMaps(context: Context, query: String, pointUri: String? = null): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(pointUri ?: ("geo:0,0?q=" + Uri.encode(query))))
    try {
        context.startActivity(intent)
        return true
    } catch (e: ActivityNotFoundException) {
        return false
    }
}
