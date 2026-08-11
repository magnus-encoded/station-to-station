package io.github.magnusencoded.stationtostation.ui

import android.content.Context
import android.content.Intent
import io.github.magnusencoded.stationtostation.AppViewModel

/**
 * Launches the Spotify OAuth flow in the browser.
 * Returns null on success, or an error message to show the user.
 */
suspend fun startSpotifyLogin(context: Context, viewModel: AppViewModel): String? {
    return try {
        val uri = viewModel.buildSpotifyAuthUri()
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        null
    } catch (e: Exception) {
        e.message ?: "Could not start Spotify login"
    }
}
