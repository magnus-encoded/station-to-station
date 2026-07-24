package io.github.magnusencoded.setlist2spotify.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.magnusencoded.setlist2spotify.AppViewModel
import io.github.magnusencoded.setlist2spotify.SongMatch
import io.github.magnusencoded.setlist2spotify.data.spotify.SpotifyTrack
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var expandedIndex by rememberSaveable { mutableIntStateOf(-1) }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    val setlist = state.selectedSetlist
    val selectedCount = state.matches.count { it.included && it.selected != null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Confirm songs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column(Modifier.padding(16.dp)) {
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
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Log in with Spotify") }
                } else {
                    Button(
                        onClick = { viewModel.createPlaylist() },
                        enabled = selectedCount > 0 && !state.creatingPlaylist && !state.matching,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.creatingPlaylist) {
                            CircularProgressIndicator(Modifier.size(20.dp))
                        } else {
                            Text("Create playlist ($selectedCount songs)")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (setlist != null) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "${setlist.artist?.name ?: ""} · ${setlist.eventDate ?: ""}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(setlist.venueLine(), style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedTextField(
                value = state.playlistName,
                onValueChange = viewModel::setPlaylistName,
                label = { Text("Playlist name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            if (state.matching) {
                val done = state.matches.count { !it.loading }
                LinearProgressIndicator(
                    progress = {
                        if (state.matches.isEmpty()) 0f else done.toFloat() / state.matches.size
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                Text(
                    "Matching songs on Spotify… $done/${state.matches.size}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
                itemsIndexed(state.matches) { index, match ->
                    SongMatchRow(
                        match = match,
                        expanded = expandedIndex == index,
                        onToggleExpand = {
                            expandedIndex = if (expandedIndex == index) -1 else index
                        },
                        onToggleIncluded = { viewModel.toggleIncluded(index) },
                        onChooseCandidate = { viewModel.chooseCandidate(index, it) },
                        onResearch = { viewModel.researchSong(index, it) },
                    )
                }
            }
        }
    }

    // Success dialog with a link to the created playlist.
    state.createdPlaylistUrl?.let { url ->
        AlertDialog(
            onDismissRequest = onBack,
            title = { Text("Playlist created") },
            text = {
                Text("\"${state.createdPlaylistName}\" was created with ${state.createdTrackCount} songs.")
            },
            confirmButton = {
                Button(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }) { Text("Open in Spotify") }
            },
            dismissButton = { TextButton(onClick = onBack) { Text("Done") } },
        )
    }
}

@Composable
private fun SongMatchRow(
    match: SongMatch,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleIncluded: () -> Unit,
    onChooseCandidate: (SpotifyTrack) -> Unit,
    onResearch: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = match.included && match.selected != null,
                    onCheckedChange = { onToggleIncluded() },
                    enabled = match.selected != null,
                )
                Column(Modifier.weight(1f).clickable(onClick = onToggleExpand)) {
                    val label = buildString {
                        append(match.song.name)
                        if (match.isCover) append(" (${match.searchArtist} cover)")
                        if (match.song.tape) append(" [tape]")
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    when {
                        match.loading -> Text(
                            "Searching…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        match.selected != null -> Text(
                            "${match.selected.name} · ${match.selected.artistNames()}" +
                                (match.selected.album?.name?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        else -> Text(
                            match.error ?: "No match found — tap to search manually",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                IconButton(onClick = onToggleExpand) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                    )
                }
            }
            if (expanded) {
                CandidatePicker(match, onChooseCandidate, onResearch)
            }
        }
    }
}

@Composable
private fun CandidatePicker(
    match: SongMatch,
    onChooseCandidate: (SpotifyTrack) -> Unit,
    onResearch: (String) -> Unit,
) {
    var query by rememberSaveable(match.song.name) {
        mutableStateOf("${match.song.name} ${match.searchArtist}")
    }
    Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
        match.candidates.forEach { track ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onChooseCandidate(track) }
                    .padding(vertical = 6.dp),
            ) {
                if (track.uri == match.selected?.uri) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Spacer(Modifier.size(18.dp))
                }
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        track.artistNames() + (track.album?.name?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(formatDuration(track.durationMs), style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search Spotify") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onResearch(query) }),
            )
            IconButton(onClick = { onResearch(query) }) {
                Icon(Icons.Default.Search, contentDescription = "Search")
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
