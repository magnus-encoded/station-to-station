package io.github.magnusencoded.stationtostation

import android.content.Intent
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
import androidx.compose.ui.graphics.Color
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
import io.github.magnusencoded.stationtostation.ui.ImportScreen
import io.github.magnusencoded.stationtostation.ui.SettingsScreen
import io.github.magnusencoded.stationtostation.ui.SplashScreen
import io.github.magnusencoded.stationtostation.ui.StationEventScreen
import io.github.magnusencoded.stationtostation.ui.StationTimelineScreen

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

    /** #257's LAN reconcile runs only while this screen is on screen — see [AppViewModel]'s
     * contactExchange doc comment for why it's foreground-scoped, not a background service. */
    override fun onStart() {
        super.onStart()
        viewModel.startContactExchange()
    }

    override fun onStop() {
        viewModel.stopContactExchange()
        super.onStop()
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

private val SpotifyGreen = Color(0xFF1DB954)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colorScheme =
        if (isSystemInDarkTheme()) darkColorScheme(primary = SpotifyGreen)
        else lightColorScheme(primary = Color(0xFF14833B))
    MaterialTheme(colorScheme = colorScheme) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

@Composable
fun AppNavigation(viewModel: AppViewModel) {
    val navController = rememberNavController()
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
            StationEventScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onConvert = { navController.navigate("confirm") },
            )
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
            )
        }
        composable("bleprobe") {
            BleProbeScreen(onBack = { navController.popBackStack() })
        }
        composable("programme") {
            ProgrammeScreen(onBack = { navController.popBackStack() })
        }
    }
}
