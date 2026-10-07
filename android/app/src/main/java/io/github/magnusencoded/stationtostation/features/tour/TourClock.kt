package io.github.magnusencoded.stationtostation.features.tour

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDateTime

@Composable
fun TourController.nowForGig(gigId: String): LocalDateTime {
    // A script jump must redraw an open Room without another app-state change.
    val clock by demoClock.collectAsStateWithLifecycle()
    return clock?.takeIf { isDemoGig(gigId) } ?: LocalDateTime.now()
}
