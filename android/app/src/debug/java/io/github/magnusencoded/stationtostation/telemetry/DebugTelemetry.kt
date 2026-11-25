package io.github.magnusencoded.stationtostation.telemetry

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.magnusencoded.stationtostation.AppViewModel
import io.github.magnusencoded.stationtostation.BuildConfig
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

internal object DebugTelemetry {
    private var provider: SdkTracerProvider? = null
    private var sdk: OpenTelemetrySdk? = null
    private var currentScreen: Span? = null
    private var screenName = "unknown"
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var foreground = false
    @Volatile private var lastBeat = SystemClock.elapsedRealtime()
    private var stalled = false

    fun observe(activity: ComponentActivity, model: AppViewModel) {
        initialize(activity)
        val startup = tracer().spanBuilder("app.ready").startSpan()
        activity.lifecycleScope.launch {
            var ready = false
            model.state.collect { state ->
                if (state.launched && !ready) {
                    ready = true
                    startup.end()
                }
            }
        }.invokeOnCompletion { startup.end() }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                screen(screenName)
                foreground = true
                lastBeat = SystemClock.elapsedRealtime()
                val operations = mutableMapOf<String, Span>()
                var previousErrors = emptySet<String>()
                try {
                    model.state.collect { state ->
                        val errors = buildSet {
                            if (state.error != null) add("app")
                            if (state.coverUploadError != null) add("cover_upload")
                            if (state.handover.error != null) add("handover")
                        }
                        for (source in errors - previousErrors) {
                            tracer().spanBuilder("feature.error")
                                .setAttribute("screen.name", screenName)
                                .setAttribute("error.source", source)
                                .startSpan().also { it.setStatus(StatusCode.ERROR); it.end() }
                        }
                        previousErrors = errors
                        val busy = mapOf(
                            "search" to state.searchLoading,
                            "setlists" to state.setlistsLoading,
                            "planning" to state.planningLoading,
                            "timelines" to state.timelinesLoading,
                            "contact_line" to state.viewedFriendLoading,
                            "song_matching" to state.matching,
                            "playlist" to state.creatingPlaylist,
                            "cover" to state.coverLoading,
                            "media_suggestions" to state.gigPhotoSuggestionsLoading,
                        )
                        for ((feature, running) in busy) {
                            if (running && feature !in operations) {
                                operations[feature] = tracer().spanBuilder("feature.loading")
                                    .setAttribute("feature.name", feature)
                                    .setAttribute("screen.name", screenName).startSpan()
                            } else if (!running) {
                                operations.remove(feature)?.also {
                                    it.setAttribute("outcome", "settled")
                                    it.end()
                                }
                            }
                        }
                    }
                } finally {
                    foreground = false
                    operations.values.forEach { it.setAttribute("outcome", "interrupted"); it.end() }
                    currentScreen?.end()
                    currentScreen = null
                    provider?.forceFlush()
                }
            }
        }
    }

    fun screen(route: String?) {
        val name = route?.takeIf { it in screens } ?: "unknown"
        if (name == screenName && currentScreen != null) return
        currentScreen?.end()
        screenName = name
        currentScreen = tracer().spanBuilder("screen.visible").setAttribute("screen.name", name).startSpan()
        tracer().spanBuilder("screen.enter").setAttribute("screen.name", name).startSpan().end()
    }

    private fun tracer() = sdk!!.getTracer("station-to-station.debug.quality", "1")

    private fun initialize(activity: ComponentActivity) {
        if (sdk != null) return
        provider = SdkTracerProvider.builder()
            .setResource(Resource.create(Attributes.of(
                AttributeKey.stringKey("service.name"), "station-to-station",
                AttributeKey.stringKey("service.version"), BuildConfig.VERSION_NAME,
                AttributeKey.stringKey("build.sha"), BuildConfig.GIT_SHA,
                AttributeKey.stringKey("build.type"), "debug",
                AttributeKey.stringKey("service.instance.id"), java.util.UUID.randomUUID().toString(),
            )))
            .addSpanProcessor(BatchSpanProcessor.builder(
                LocalSpanExporter(activity.noBackupFilesDir.resolve("telemetry"))
            ).setMaxQueueSize(512).setScheduleDelay(1, TimeUnit.SECONDS).build())
            .build()
        sdk = OpenTelemetrySdk.builder().setTracerProvider(provider!!).build()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                tracer().spanBuilder("app.crash").setAttribute("screen.name", screenName)
                    .startSpan().also { it.setStatus(StatusCode.ERROR); it.end() }
                provider?.forceFlush()?.join(1, TimeUnit.SECONDS)
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }
        val beat = object : Runnable {
            override fun run() {
                lastBeat = SystemClock.elapsedRealtime()
                handler.postDelayed(this, 500)
            }
        }
        handler.post(beat)
        Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "quality-watchdog").apply { isDaemon = true } }
            .scheduleAtFixedRate({
                val delay = SystemClock.elapsedRealtime() - lastBeat
                if (foreground && delay > 5_000 && !stalled) {
                    stalled = true
                    tracer().spanBuilder("app.main_thread_stall")
                        .setAttribute("screen.name", screenName).setAttribute("stall.ms", delay)
                        .startSpan().also { it.setStatus(StatusCode.ERROR); it.end() }
                }
                if (delay < 1_000) stalled = false
            }, 1, 1, TimeUnit.SECONDS)
    }

    private val screens = setOf("splash", "timeline", "event", "import", "exchange", "settings",
        "programme", "friend", "friends", "setlists", "search", "confirm", "handover", "ble-probe")
}
