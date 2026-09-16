package io.github.magnusencoded.stationtostation.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import io.github.magnusencoded.stationtostation.ErrorKind

/** The way out of a spent shared setlist.fm key, worded the same everywhere (#457). */
const val ADD_OWN_KEY_ACTION = "Add your own key"

/**
 * An error in a snackbar, with a way out of it where there is one.
 *
 * The shared setlist.fm key running out is the one error a person can fix in thirty
 * seconds, and only if the app says where — so that message gets a button to the
 * setlist.fm section of Settings, and every other message is shown as it always was.
 */
suspend fun SnackbarHostState.showAppError(
    message: String,
    kind: ErrorKind?,
    onOpenSettings: () -> Unit,
) {
    val nudge = kind == ErrorKind.SETLISTFM_SHARED_QUOTA
    val result = showSnackbar(
        message = message,
        actionLabel = if (nudge) ADD_OWN_KEY_ACTION else null,
        duration = if (nudge) SnackbarDuration.Long else SnackbarDuration.Short,
    )
    if (nudge && result == SnackbarResult.ActionPerformed) onOpenSettings()
}
