package io.github.magnusencoded.setlist2spotify.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.magnusencoded.setlist2spotify.AppViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: AppViewModel,
    onOpenSetlists: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFriends: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setlist to Spotify") },
                actions = {
                    IconButton(onClick = onOpenFriends) {
                        Icon(Icons.Default.Person, contentDescription = "Friends")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!state.setlistFmReady) {
                Text(
                    "Add your setlist.fm API key in Settings to get started.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenSettings)
                        .padding(16.dp),
                )
            }
            if (!state.spotifyConnected) {
                Button(
                    onClick = {
                        if (state.spotifyLoginReady) {
                            scope.launch {
                                startSpotifyLogin(context, viewModel)?.let {
                                    snackbarHostState.showSnackbar(it)
                                }
                            }
                        } else {
                            onOpenSettings()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text("Log in with Spotify") }
            }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Search artist") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("My concerts") })
            }
            when (tab) {
                0 -> ArtistTab(viewModel, onOpenSetlists)
                1 -> UserTab(viewModel, onOpenSetlists)
            }
        }
    }
}

@Composable
private fun ArtistTab(viewModel: AppViewModel, onOpenSetlists: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.artistQuery,
                onValueChange = viewModel::setArtistQuery,
                label = { Text("Artist name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.searchArtists() }),
            )
            IconButton(onClick = { viewModel.searchArtists() }) {
                Icon(Icons.Default.Search, contentDescription = "Search")
            }
        }
        if (state.searchLoading) {
            Row(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }
        }
        LazyColumn {
            items(state.artistResults, key = { it.mbid }) { artist ->
                ListItem(
                    headlineContent = { Text(artist.name) },
                    supportingContent = artist.disambiguation
                        ?.takeIf { it.isNotBlank() }
                        ?.let { { Text(it) } },
                    modifier = Modifier.clickable {
                        viewModel.openArtist(artist)
                        onOpenSetlists()
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun UserTab(viewModel: AppViewModel, onOpenSetlists: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Load the concerts you marked as attended on setlist.fm. " +
                "Enter your setlist.fm username — the setlist.fm API has no app login, " +
                "so no password is needed here.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.userQuery,
                onValueChange = viewModel::setUserQuery,
                label = { Text("setlist.fm user ID") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    viewModel.openUserAttended()
                    onOpenSetlists()
                }),
            )
            IconButton(onClick = {
                viewModel.openUserAttended()
                onOpenSetlists()
            }) {
                Icon(Icons.Default.Search, contentDescription = "Load")
            }
        }
        TextButton(onClick = {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.setlist.fm/signin"))
            )
        }) {
            Text("Forgot your username? Sign in on setlist.fm (Google login supported)")
        }
    }
}
