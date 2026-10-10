package io.github.magnusencoded.stationtostation

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.magnusencoded.stationtostation.ui.BleProbeScreen
import io.github.magnusencoded.stationtostation.ui.ConfirmScreen
import io.github.magnusencoded.stationtostation.ui.FriendsScreen
import io.github.magnusencoded.stationtostation.ui.ProgrammeScreen
import io.github.magnusencoded.stationtostation.ui.SearchScreen
import io.github.magnusencoded.stationtostation.ui.SetlistsScreen
import io.github.magnusencoded.stationtostation.ui.ExchangeScreen
import io.github.magnusencoded.stationtostation.ui.FriendTimelineScreen
import io.github.magnusencoded.stationtostation.ui.HandoverScreen
import io.github.magnusencoded.stationtostation.ui.ImportScreen
import io.github.magnusencoded.stationtostation.ui.SettingsScreen
import io.github.magnusencoded.stationtostation.ui.StationEventScreen
import io.github.magnusencoded.stationtostation.ui.StationTimelineScreen
import io.github.magnusencoded.stationtostation.ui.flyover.GigFlyoverScreen
import io.github.magnusencoded.stationtostation.features.tour.TourEvent
import io.github.magnusencoded.stationtostation.features.tour.TourOverlay
import androidx.navigation.NavController
import io.github.magnusencoded.stationtostation.features.tour.ContextHintOverlay
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // The mark on Ground stays up until the first screen has what it needs, so the
        // launch goes straight from the icon to the settled timeline (or to onboarding)
        // with nothing half-loaded in between. The cap is only a backstop: if reading
        // the phone's own storage ever hangs, the app still opens.
        val splashFrom = System.currentTimeMillis()
        installSplashScreen().setKeepOnScreenCondition {
            !viewModel.state.value.launched && System.currentTimeMillis() - splashFrom < SPLASH_CAP_MS
        }
        super.onCreate(savedInstanceState)
        handleAuthIntent(intent)
        handleTicketIntent(intent)
        handleHandoverDebugIntent(intent)
        setContent {
            AppTheme {
                ContextHintOverlay(viewModel) { AppNavigation(viewModel) }
            }
        }
    }

    private companion object {
        const val SPLASH_CAP_MS = 3_000L
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuthIntent(intent)
        handleTicketIntent(intent)
        handleHandoverDebugIntent(intent)
    }

    /**
     * Zoom from a keyboard, because a pinch cannot be scripted. `adb shell input` sends
     * one pointer, and writing multitouch straight to the touchscreen is permission
     * denied on an unrooted phone — so the woven view was reachable only by a human's
     * hand, and every look at it needed one.
     *
     *   adb shell input keyevent 169   # zoom out — open the other lines
     *   adb shell input keyevent 168   # zoom in  — back to my own
     *
     * `-` and `+` do the same, so an attached keyboard works too.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_ZOOM_OUT, KeyEvent.KEYCODE_MINUS -> viewModel.setZoomedOut(true)
            KeyEvent.KEYCODE_ZOOM_IN, KeyEvent.KEYCODE_PLUS -> viewModel.setZoomedOut(false)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun handleAuthIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        when (val link = parseDeepLink(uri.toString())) {
            null -> Unit
            is LinkIntent.PassThrough -> when (link.kind) {
                PassThroughKind.FRIEND -> viewModel.handleFriendLink(uri)
                // The other phone's QR, read by whatever camera app the person pointed at it
                // — the same trick a friend card uses, and the reason there is no in-app
                // scanner and no camera permission on the receiving side.
                PassThroughKind.HANDOVER -> viewModel.joinHandover(uri)
                PassThroughKind.CALLBACK -> viewModel.handleAuthRedirect(uri)
                // A ticketing provider's own confirmation page, linking straight to a gig
                // with the fields it already knows rather than a PDF for this app to OCR
                // (see AppViewModel.handleTicketLink).
                PassThroughKind.TICKET -> viewModel.handleTicketLink(uri)
            }
            else -> viewModel.handleLink(link)
        }
    }

    /**
     * A PDF ticket shared in from the system share sheet (#411), or opened directly
     * ("Open with") — the sibling of [handleAuthIntent] rather than a change to it,
     * because these arrive by different actions and share nothing but "MainActivity
     * is the front door". `ACTION_SEND` with `application/pdf` is what an email or
     * wallet app's own "Share" offers, with the pdf usually as `EXTRA_STREAM` (rarely
     * as the intent's own [Intent.getData]); `ACTION_VIEW` is a Files app or browser's
     * "Open with", where the pdf is always [Intent.getData]. A share that sets neither
     * still carries the pdf as the first item of [Intent.getClipData] — the Files app's
     * own "Share" does exactly this, and without that fallback the ticket was silently
     * dropped (#514). The choice itself lives in [sharedTicketUri] so it can be tested.
     */
    private fun handleTicketIntent(intent: Intent?) {
        if (intent == null) return
        val uri = sharedTicketUri(
            action = intent.action,
            type = intent.type,
            stream = if (intent.action == Intent.ACTION_SEND) ticketUriExtra(intent) else null,
            data = intent.data,
            clip = intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri,
        ) ?: return
        viewModel.handleSharedTicketPdf(uri)
    }

    // Intent.getParcelableExtra(String) is deprecated from API 33 in favour of the
    // typed overload; minSdk here is 26, so both forms are live depending on the
    // phone answering the share.
    @Suppress("DEPRECATION")
    private fun ticketUriExtra(intent: Intent): android.net.Uri? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    /**
     * The manual two-device capture rig for #142's own verification procedure — never
     * app UI, never reachable from a release build. Two `adb shell am start` calls, one
     * per phone, drive it:
     *
     *   # host (prints its wifi IP, and a fingerprint when --ez insecure false):
     *   adb shell am start -n io.github.magnusencoded.stationtostation.debug/io.github.magnusencoded.stationtostation.MainActivity \
     *     -a io.github.magnusencoded.stationtostation.HANDOVER_DEBUG \
     *     --es role host --es linkKey deadbeef --ez insecure true
     *
     *   # join, once the host is listening (swap --ez insecure and add --es fingerprint
     *   # for the armed pass):
     *   adb shell am start -n io.github.magnusencoded.stationtostation.debug/io.github.magnusencoded.stationtostation.MainActivity \
     *     -a io.github.magnusencoded.stationtostation.HANDOVER_DEBUG \
     *     --es role join --es host 192.168.1.23 --es linkKey deadbeef --ez insecure true
     *
     * `adb logcat -s HandoverDebug` on the joining phone shows the result, including the
     * path (in its external files dir) `adb pull` can retrieve the received photo from
     * for visual reconstruction. The debug build type carries its own `.debug`
     * applicationId suffix, so it installs alongside any release/alpha-track build
     * rather than colliding with it. See the PR description for the full
     * unencrypted-then-armed procedure this feeds.
     */
    private fun handleHandoverDebugIntent(intent: Intent?) {
        if (!io.github.magnusencoded.stationtostation.BuildConfig.DEBUG) return
        if (intent?.action != "io.github.magnusencoded.stationtostation.HANDOVER_DEBUG") return
        val role = intent.getStringExtra("role")
        val linkKey = intent.getStringExtra("linkKey") ?: "deadbeef"
        val insecure = intent.getBooleanExtra("insecure", true)
        val host = intent.getStringExtra("host")
        val fingerprint = intent.getStringExtra("fingerprint")
        val log: (String) -> Unit = { android.util.Log.i("HandoverDebug", it) }

        Thread {
            runCatching {
                when (role) {
                    "host" -> io.github.magnusencoded.stationtostation.data.exchange
                        .runHandoverDebugHost(applicationContext, linkKey, insecure, log)
                    "join" -> io.github.magnusencoded.stationtostation.data.exchange
                        .runHandoverDebugJoin(applicationContext, host!!, linkKey, fingerprint, insecure, log)
                    else -> log("unknown role '$role' — expected 'host' or 'join'")
                }
            }.onFailure { log("handover debug session failed: $it") }
        }.start()
    }
}

// The spine's accent, the same amber the Line and the rungs are drawn in. It used to be
// Spotify green, which every Material default then picked up — outlined text fields,
// outlined buttons, checkboxes — and the whole app read as if it were a Spotify client.
// Green now means Spotify and only Spotify, said explicitly where it is meant.
private val Amber = Color(0xFFE7B24C)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colorScheme =
        if (isSystemInDarkTheme()) darkColorScheme(primary = Amber, onPrimary = Color(0xFF241A08))
        else lightColorScheme(primary = Color(0xFF8C6A28))
    MaterialTheme(colorScheme = colorScheme) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

@Composable
fun AppNavigation(viewModel: AppViewModel) {
    val navController = rememberNavController()
    // The Room steps of the Tour: a Gig opened from the timeline, then any way back to it,
    // the swipe included, since the system back gesture pops without a callback of ours.
    DisposableEffect(navController) {
        var previous: String? = null
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            val route = destination.route
            viewModel.tour.currentScreen = { when (navController.currentDestination?.route) { "timeline" -> "line"; "event" -> "room"; else -> "other" } }
            if (previous == "timeline" && route == "event") viewModel.tour.dispatch(TourEvent.RoomOpened)
            if (previous == "event" && route == "timeline") viewModel.tour.dispatch(TourEvent.SwipedBack)
            previous = route
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }
    // setlist.fm's automatic checks for local Gigs run while the app is in the
    // foreground and only then (#531): at launch, on coming back, and on their timer.
    LifecycleStartEffect(Unit) {
        viewModel.startLookupChecks()
        onStopOrDispose { viewModel.stopLookupChecks() }
    }
    // Nothing is drawn until launch has read the settings and the saved timeline (the
    // system splash covers it), and then the first screen is the right one outright:
    // the timeline, with the Tour's coach marks over it on a first launch. Ahead of the
    // handover effect too, which needs the NavHost's graph.
    val launched by remember(viewModel) { viewModel.state.map { it.launched } }
        .collectAsStateWithLifecycle(viewModel.state.value.launched)
    if (!launched) return
    // A handover can begin from outside any screen: the QR is read by the phone's camera
    // app, which opens the deep link, which starts the receiving side. Whatever was on
    // screen, that is the thing to be looking at.
    //
    // Remembered, because mapping in the composition builds a new Flow on every
    // recomposition — each one restarting the collection from null, which flaps the
    // LaunchedEffect key below and re-navigates. launchSingleTop hid it; it was never
    // the guard.
    val handoverRole by remember(viewModel) { viewModel.state.map { it.handover.role } }
        .collectAsStateWithLifecycle(null)
    LaunchedEffect(handoverRole) {
        if (handoverRole != null) navController.navigate("handover") { launchSingleTop = true }
    }
    // Every move follows the gesture that caused it: going deeper comes in from the
    // right while the screen behind it eases left, and coming back reverses exactly
    // that. Without this the swipe-to-convert cut straight to the next screen, which
    // reads as the app changing rather than as one place leading to another.
    NavHost(
        navController = navController,
        startDestination = "timeline",
        enterTransition = { slideInHorizontally(tween(280)) { it } + fadeIn(tween(200)) },
        exitTransition = { slideOutHorizontally(tween(280)) { -it / 5 } + fadeOut(tween(200)) },
        popEnterTransition = { slideInHorizontally(tween(280)) { -it / 5 } + fadeIn(tween(200)) },
        popExitTransition = { slideOutHorizontally(tween(280)) { it } + fadeOut(tween(200)) },
    ) {
        composable("timeline") {
            TourOverlay(viewModel) {
                StationTimelineScreen(
                    viewModel = viewModel,
                    onOpenEvent = { navController.navigate("event") },
                    onOpenImport = { navController.navigate("import") },
                    // Both the people icon and the swipe-left gesture now lead to the one
                    // Exchange — there is a single way to meet someone.
                    onOpenConnect = { navController.navigate("exchange") },
                    onOpenNearby = { navController.navigate("exchange") },
                    onOpenSettings = { navController.navigate("settings") },
                    onOpenProgramme = { navController.navigate("programme") },
                )
            }
        }
        composable("exchange") {
            ExchangeScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onConnected = {
                    navController.popBackStack("timeline", inclusive = false)
                },
                onViewFriend = { friend ->
                    viewModel.viewFriendTimeline(friend)
                    navController.navigate("friend")
                },
                onSetUsername = { navController.navigate("import") },
            )
        }
        composable("friend") {
            FriendTimelineScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenEvent = { navController.navigate("event") },
            )
        }
        composable("import") {
            ImportScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onDone = { navController.popBackStack() },
                onOpenSettings = { navController.navigate("settings") },
            )
        }
        composable("event") {
            // **The flyover replaces the landscape view** (#278). Not a second mode and
            // not a re-layout of the room: turned sideways, a night is a read-only walk
            // down its own spine, and the room's editing surfaces are absent because
            // typing here is bad regardless — the IME takes two thirds of 411dp.
            //
            // Branched at the destination rather than inside the room, so neither screen
            // carries a modifier the other has to read around. Rotating recreates the
            // activity and the back stack is restored, so the night stays open across
            // the turn.
            if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                GigFlyoverScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                )
            } else {
                TourOverlay(viewModel, inRoom = true) {
                    StationEventScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                        onConvert = { navController.navigate("confirm") },
                        onOpenSettings = { navController.navigate("settings") },
                    )
                }
            }
        }
        composable("search") {
            SearchScreen(
                viewModel = viewModel,
                onOpenSetlists = { navController.navigate("setlists") },
                onOpenSettings = { navController.navigate("settings") },
                onOpenFriends = { navController.navigate("friends") },
            )
        }
        composable("friends") {
            FriendsScreen(
                viewModel = viewModel,
                onOpenShared = { navController.navigate("setlists") },
                onOpenSettings = { navController.navigate("settings") },
                onBack = { navController.popBackStack() },
            )
        }
        composable("setlists") {
            SetlistsScreen(
                viewModel = viewModel,
                onSetlistPicked = { navController.navigate("confirm") },
                onBack = { navController.popBackStack() },
            )
        }
        composable("confirm") {
            ConfirmScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate("settings") },
            )
        }
        composable("settings") {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenHandover = { navController.navigate("handover") },
                onTour = { replay ->
                    if (replay) viewModel.tour.replay {
                        navController.navigate("timeline") { popUpTo("timeline") { inclusive = true } }
                    } else {
                        navController.popBackStack("timeline", inclusive = false)
                        viewModel.tour.resume()
                    }
                },
            )
        }
        composable("handover") {
            HandoverScreen(
                viewModel = viewModel,
                onDone = {
                    viewModel.dismissHandover()
                    navController.popBackStack()
                },
            )
        }
        composable("bleprobe") {
            BleProbeScreen(onBack = { navController.popBackStack() })
        }
        composable("programme") {
            ProgrammeScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate("settings") },
            )
        }
    }
    // A link names a screen from wherever the person is: back to the timeline first, so
    // one command always starts from the same place.
    val linkScreen by remember(viewModel) { viewModel.state.map { it.linkScreen } }
        .collectAsStateWithLifecycle(null)
    LaunchedEffect(linkScreen) {
        val screen = linkScreen ?: return@LaunchedEffect
        if (runCatching { navController.getBackStackEntry("timeline") }.isSuccess) {
            navController.popBackStack("timeline", inclusive = false)
            when (screen) {
                LinkScreen.SETTINGS -> navController.navigate("settings")
                LinkScreen.PROGRAMME -> navController.navigate("programme")
                LinkScreen.TIMELINE, LinkScreen.TIMELINES -> Unit
            }
        }
        viewModel.consumeLinkScreen()
    }
}

/**
 * Which uri a ticket intent's pdf lives at (#411, #514) — pulled out of
 * [MainActivity.handleTicketIntent] so it can be unit tested without an [Intent], and
 * generic over the uri because `android.net.Uri` is only a stub under plain JUnit.
 * `ACTION_SEND` tries `EXTRA_STREAM`, then the intent's own data, then the first clip
 * item; `ACTION_VIEW` only ever has the data. Anything but a pdf is not a ticket.
 */
internal fun <U : Any> sharedTicketUri(
    action: String?,
    type: String?,
    stream: U?,
    data: U?,
    clip: U?,
): U? {
    if (type != "application/pdf") return null
    return when (action) {
        Intent.ACTION_SEND -> stream ?: data ?: clip
        Intent.ACTION_VIEW -> data
        else -> null
    }
}
