package io.github.magnusencoded.setlist2spotify.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.magnusencoded.setlist2spotify.AppViewModel
import io.github.magnusencoded.setlist2spotify.data.spotify.SPOTIFY_REDIRECT_URI
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var apiKey by remember(state.setlistFmApiKey) { mutableStateOf(state.setlistFmApiKey) }
    var clientId by remember(state.spotifyClientId) { mutableStateOf(state.spotifyClientId) }

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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("setlist.fm", style = MaterialTheme.typography.titleMedium)
            Text(
                "Request a free API key at api.setlist.fm.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("setlist.fm API key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            Text("Spotify", style = MaterialTheme.typography.titleMedium)
            Text(
                "Create an app at developer.spotify.com/dashboard and add this redirect URI:\n$SPOTIFY_REDIRECT_URI",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it },
                label = { Text("Spotify Client ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { viewModel.saveSettings(apiKey, clientId) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }

            Spacer(Modifier.height(16.dp))
            if (state.spotifyConnected) {
                Text("✓ Connected to Spotify", color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.disconnectSpotify() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Disconnect Spotify") }
            } else {
                Button(
                    onClick = {
                        viewModel.saveSettings(apiKey, clientId)
                        scope.launch {
                            try {
                                val uri = viewModel.buildSpotifyAuthUri()
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar(e.message ?: "Could not start login")
                            }
                        }
                    },
                    enabled = clientId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Connect Spotify") }
            }
        }
    }
}
