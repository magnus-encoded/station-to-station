package io.github.magnusencoded.stationtostation.telemetry

import androidx.activity.ComponentActivity
import io.github.magnusencoded.stationtostation.AppViewModel

internal object DebugTelemetry {
    fun observe(activity: ComponentActivity, model: AppViewModel) = Unit
    fun screen(route: String?) = Unit
}
