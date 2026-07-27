package io.github.magnusencoded.setlist2spotify

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
import io.github.magnusencoded.setlist2spotify.ui.ConfirmScreen
import io.github.magnusencoded.setlist2spotify.ui.FriendsScreen
import io.github.magnusencoded.setlist2spotify.ui.SearchScreen
import io.github.magnusencoded.setlist2spotify.ui.SetlistsScreen
import io.github.magnusencoded.setlist2spotify.ui.SettingsScreen
import io.github.magnusencoded.setlist2spotify.ui.StationEventScreen
import io.github.magnusencoded.setlist2spotify.ui.StationTimelineScreen

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleAuthIntent(intent)
        setContent {
            AppTheme {
                AppNavigation(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuthIntent(intent)
    }

    private fun handleAuthIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "setlist2spotify") return
        when (uri.authority) {
            "friend" -> viewModel.handleFriendLink(uri)
            else -> viewModel.handleAuthRedirect(uri)
        }
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
    NavHost(navController = navController, startDestination = "timeline") {
        composable("timeline") {
            StationTimelineScreen(
                onOpenEvent = { navController.navigate("event") },
                onOpenSearch = { navController.navigate("search") },
            )
        }
        composable("event") {
            StationEventScreen(onBack = { navController.popBackStack() })
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
            )
        }
    }
}
