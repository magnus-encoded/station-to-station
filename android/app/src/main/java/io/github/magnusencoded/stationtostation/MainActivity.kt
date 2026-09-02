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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
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
import io.github.magnusencoded.stationtostation.ui.SplashScreen
import io.github.magnusencoded.stationtostation.ui.StationEventScreen
import io.github.magnusencoded.stationtostation.ui.StationTimelineScreen
import io.github.magnusencoded.stationtostation.ui.flyover.GigFlyoverScreen
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleAuthIntent(intent)
        handleHandoverDebugIntent(intent)
        setContent {
            AppTheme {
                AppNavigation(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuthIntent(intent)
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
        // Everything now rides one scheme, station-to-station. The old setlist2spotify
        // scheme is still accepted so links shared before the rename keep resolving.
        // The authority tells the deep links apart from a timeline place, whose
        // authority is a line name (see AppViewModel.openGigLink) — a line literally
        // named friend/gig/callback would collide, which is acceptable.
        if (uri.scheme != "station-to-station" && uri.scheme != "setlist2spotify") return
        when (uri.authority) {
            "friend" -> viewModel.handleFriendLink(uri)
            // The other phone's QR, read by whatever camera app the person pointed at it
            // — the same trick a friend card uses, and the reason there is no in-app
            // scanner and no camera permission on the receiving side (#142).
            "handover" -> viewModel.joinHandover(uri)
            "gig" -> viewModel.handleGigInvite(uri)
            "callback" -> viewModel.handleAuthRedirect(uri)
            else -> viewModel.openGigLink(uri)
        }
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
        startDestination = "splash",
        enterTransition = { slideInHorizontally(tween(280)) { it } + fadeIn(tween(200)) },
        exitTransition = { slideOutHorizontally(tween(280)) { -it / 5 } + fadeOut(tween(200)) },
        popEnterTransition = { slideInHorizontally(tween(280)) { -it / 5 } + fadeIn(tween(200)) },
        popExitTransition = { slideOutHorizontally(tween(280)) { it } + fadeOut(tween(200)) },
    ) {
        composable("splash") {
            SplashScreen(
                viewModel = viewModel,
                onProceed = {
                    navController.navigate("timeline") {
                        popUpTo("splash") { inclusive = true }
                    }
                },
            )
        }
        composable("timeline") {
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
        composable("exchange") {
            ExchangeScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onConnected = {
                    // Back to the one timeline there is; it opens with their line showing.
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
                StationEventScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onConvert = { navController.navigate("confirm") },
                )
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
                onOpenBleProbe = { navController.navigate("bleprobe") },
                onOpenHandover = { navController.navigate("handover") },
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
}
