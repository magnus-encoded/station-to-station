package io.github.magnusencoded.stationtostation.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.magnusencoded.stationtostation.AppViewModel
import io.github.magnusencoded.stationtostation.BuildConfig
import io.github.magnusencoded.stationtostation.data.spotify.SPOTIFY_REDIRECT_URI
import kotlinx.coroutines.launch

private val SpotifyGreen = Color(0xFF1DB954)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onOpenBleProbe: () -> Unit = {},
    onOpenHandover: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var apiKey by remember(state.setlistFmApiKey) { mutableStateOf(state.setlistFmApiKey) }
    var clientId by remember(state.spotifyClientId) { mutableStateOf(state.spotifyClientId) }
    var clashfinderUser by remember(state.clashfinderUser) { mutableStateOf(state.clashfinderUser) }
    var clashfinderKey by remember(state.clashfinderPrivateKey) {
        mutableStateOf(state.clashfinderPrivateKey)
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .swipeRightToBack(onBack = onBack)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("Spotify", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (state.spotifyConnected) {
                // Green, said out loud, because here it really is about Spotify.
                Text("✓ Logged in with Spotify", color = SpotifyGreen)
                val scope = state.grantedScope
                Text(
                    when {
                        scope == null ->
                            "Granted permissions unknown — log out and log in again."
                        "playlist-modify" !in scope ->
                            "⚠ Playlist permissions MISSING ($scope) — log out and log in again."
                        // Logins made before photo covers existed carry every
                        // playlist permission but not the one covers need.
                        "ugc-image-upload" !in scope ->
                            "Playlist permissions granted. Photo covers need one more " +
                                "permission — log out and log in again to enable them."
                        else -> "Playlist and cover permissions granted ($scope)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.disconnectSpotify() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Log out") }
            } else {
                Button(
                    onClick = {
                        scope.launch {
                            viewModel.saveSettingsNow(apiKey, clientId)
                            startSpotifyLogin(context, viewModel)?.let {
                                snackbarHostState.showSnackbar(it)
                            }
                        }
                    },
                    enabled = state.bundledSpotifyClientId || clientId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Log in with Spotify") }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Spotify allows five signed-in users per app, so logging in may be " +
                    "refused. Use your own Spotify app instead: create one at " +
                    "developer.spotify.com/dashboard with Web API enabled and " +
                    "redirect URI $SPOTIFY_REDIRECT_URI, paste its Client ID " +
                    "below, Save, then log out and back in.",
                style = MaterialTheme.typography.bodySmall,
            )
            // The steps above are the ones people get wrong, and a phone is a bad
            // place to follow them. The site has the same list plus a way to ask
            // for one of the five slots.
            TextButton(onClick = {
                context.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://magnus-encoded.github.io/station-to-station/"),
                    )
                )
            }) { Text("Step by step, and how to ask for a slot") }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it },
                label = { Text("Spotify Client ID") },
                // Empty means "using the bundled one", and the placeholder says which
                // bundled one. It used to arrive pre-filled with the bundled id, which
                // made a value the user never typed look like one they had — and Save
                // then pinned it as their override for good.
                placeholder = {
                    if (state.bundledSpotifyClientId) {
                        Text(state.bundledSpotifyHint, color = MaterialTheme.colorScheme.outline)
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            Text("setlist.fm", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                if (state.bundledSetlistFmKey) {
                    "Using the bundled setlist.fm API key. The setlist.fm API has no " +
                        "user login — to load your attended concerts, just enter your " +
                        "setlist.fm username on the My concerts tab."
                } else {
                    "This app needs a free setlist.fm API key of your own."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.setlist.fm/settings/api"))
                )
            }) { Text("Request one at setlist.fm/settings/api") }
            // The field is here whether or not a key is bundled. Hiding it when one was
            // meant "the bundled key cannot be replaced without a rebuild", which is the
            // opposite of what a bring-your-own-source app should offer — and it is the
            // only way out if the bundled key is ever revoked or rate-limited.
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("setlist.fm API key") },
                placeholder = {
                    if (state.bundledSetlistFmKey) {
                        Text(state.bundledSetlistFmHint, color = MaterialTheme.colorScheme.outline)
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { viewModel.saveSettings(apiKey, clientId) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // No bundled fallback here, unlike setlist.fm above: one account shared by
            // every install would put all of this app's traffic on a single credential
            // against a host that runs active bot protection. The price is that the
            // programme button does nothing until these two fields are filled in, so
            // the text says what to get and where.
            Text("clashfinder", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                if (state.clashfinderReady) {
                    "Festival timetables come from clashfinder, using this account."
                } else {
                    "Festival timetables — stages, set times and clashes — come from " +
                        "clashfinder, which needs a free account of your own. Register, " +
                        "then copy the private key off your account page. It is not " +
                        "your password."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://clashfinder.com/m/account"))
                )
            }) { Text("Register at clashfinder.com") }
            OutlinedTextField(
                value = clashfinderUser,
                onValueChange = { clashfinderUser = it },
                label = { Text("clashfinder username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = clashfinderKey,
                onValueChange = { clashfinderKey = it },
                label = { Text("clashfinder private key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { viewModel.saveClashfinderCredentials(clashfinderUser, clashfinderKey) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save clashfinder account") }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // Cards are swapped peer to peer and never expire on their own, so
            // without this the only way to drop a lane was to wipe the app.
            Text("Known timelines", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (state.friends.isEmpty()) {
                Text(
                    "Nobody yet. Swipe left from your timeline to swap cards with " +
                        "someone, and their line opens beside yours.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                state.friends.forEach { friend ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(friend.name)
                            Text(
                                "@${friend.setlistfm} · " +
                                    "${state.showsByFriend[friend.setlistfm]?.size ?: 0} shows",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(
                            onClick = { viewModel.removeFriend(friend) },
                            modifier = Modifier.semantics {
                                contentDescription = "Remove ${friend.name}"
                            },
                        ) { Text("Remove") }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // Moving to a new phone lives here rather than on the Exchange screen: that
            // screen is for meeting *people*, and this is the same person's second device.
            OutlinedButton(
                onClick = onOpenHandover,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Move to a new phone") }

            Spacer(Modifier.height(12.dp))

            // #30 field-test: dev-only screen, not part of the shipped feature set.
            OutlinedButton(
                onClick = onOpenBleProbe,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("GATT card probe (#30 field test)") }

            Spacer(Modifier.height(24.dp))
            Text(
                "Build ${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_SHA}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
