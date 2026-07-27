package io.github.magnusencoded.setlist2spotify.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.magnusencoded.setlist2spotify.AppViewModel
import io.github.magnusencoded.setlist2spotify.CoverCandidate
import io.github.magnusencoded.setlist2spotify.SongMatch
import io.github.magnusencoded.setlist2spotify.data.photos.PhotoRepository
import io.github.magnusencoded.setlist2spotify.data.spotify.SpotifyTrack
import kotlinx.coroutines.launch

// The same nocturnal ground as the Station screens, but with Spotify green as the
// accent — this is the one pure-Spotify function, so it earns the green while still
// looking like the rest of the app rather than a different UI. Every
// MaterialTheme.colorScheme.* reference below resolves through this.
private val NocturnalGreen = darkColorScheme(
    primary = Color(0xFF1DB954),
    onPrimary = Color(0xFF08210F),
    background = Color(0xFF0E0B14),
    onBackground = Color(0xFFEDE9F2),
    surface = Color(0xFF17121F),
    onSurface = Color(0xFFEDE9F2),
    surfaceVariant = Color(0xFF1D1728),
    onSurfaceVariant = Color(0xFF8B8299),
    outline = Color(0xFF4A3F63),
    error = Color(0xFFE08A8A),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) = MaterialTheme(colorScheme = NocturnalGreen) {
    ConfirmScreenContent(viewModel, onBack, onOpenSettings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmScreenContent(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var expandedIndex by rememberSaveable { mutableIntStateOf(-1) }
    // Granting gallery access is what makes the photo suggestions appear, so the
    // result feeds straight back into the search.
    val photoPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.loadCoverCandidates() }

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
                title = { Text("Open as a Spotify playlist", fontFamily = FontFamily.Serif) },
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
                        "${setlist.artist?.name ?: ""} · ${setlist.readableDate() ?: ""}",
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
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Public playlist", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (state.playlistPublic)
                            "Friends can discover it from the shared link, and it shows on your Spotify profile."
                        else
                            "Kept private — only people you send the link to can open it, and friends' apps can't auto-add you from it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = state.playlistPublic, onCheckedChange = viewModel::setPlaylistPublic)
            }
            Spacer(Modifier.height(8.dp))
            // Without a date there is no window to search the gallery for.
            val datedSetlist = setlist?.takeIf { it.localDate() != null }
            if (datedSetlist != null) {
                CoverPicker(
                    candidates = state.coverCandidates,
                    loading = state.coverLoading,
                    searched = state.coverSearched,
                    permissionGranted = state.coverPermissionGranted,
                    showDate = datedSetlist.readableDate(),
                    onRequestPermission = {
                        photoPermissionLauncher.launch(PhotoRepository.requiredPermissions())
                    },
                    onCoverChange = viewModel::setCover,
                )
                Spacer(Modifier.height(8.dp))
            }
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
                Text(
                    buildString {
                        append("\"${state.createdPlaylistName}\" was created with ")
                        append("${state.createdTrackCount} songs.")
                        if (state.createdRefusedCount > 0) {
                            append(" ${state.createdRefusedCount} were refused by Spotify.")
                        }
                        state.coverUploadError?.let { append(" ").append(it) }
                    }
                )
            },
            confirmButton = {
                Button(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    }
                    context.startActivity(Intent.createChooser(send, "Send playlist to a friend"))
                }) { Text("Send to a friend") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }) { Text("Open") }
                    TextButton(onClick = onBack) { Text("Done") }
                }
            },
        )
    }
}

/**
 * Offers the photos the phone took on the night of the show as the playlist
 * cover. Gallery access is only ever asked for after a tap here, so opening a
 * setlist never triggers a permission prompt on its own.
 */
@Composable
private fun CoverPicker(
    candidates: List<CoverCandidate>,
    loading: Boolean,
    searched: Boolean,
    permissionGranted: Boolean,
    showDate: String?,
    onRequestPermission: () -> Unit,
    onCoverChange: (Uri?) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            !permissionGranted -> {
                Text("Playlist cover", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Use one of your own photos from the show.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = onRequestPermission,
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) { Text("Find photos from that night") }
            }
            loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp))
                Spacer(Modifier.size(8.dp))
                Text("Looking through your gallery…", style = MaterialTheme.typography.bodySmall)
            }
            candidates.isEmpty() -> if (searched) {
                Text(
                    "No photos from ${showDate ?: "that night"} in your gallery — " +
                        "Spotify will build the cover from the album art.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // A new show means a new set of photos, so the pager starts over.
            else -> key(candidates) {
                // Page 0 is Spotify's collage and page 1 the suggested photo, so
                // the collage is always one swipe right of the suggestion however
                // many photos follow it to the left.
                val pagerState = rememberPagerState(initialPage = 1) { candidates.size + 1 }
                LaunchedEffect(pagerState.currentPage) {
                    onCoverChange(candidates.getOrNull(pagerState.currentPage - 1)?.uri)
                }
                HorizontalPager(
                    state = pagerState,
                    contentPadding = PaddingValues(horizontal = 72.dp),
                    pageSpacing = 12.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) { page ->
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (page == 0) {
                            Icon(
                                Icons.Default.GridView,
                                contentDescription = "Spotify's album-art collage",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(48.dp),
                            )
                        } else {
                            candidates[page - 1].preview?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = "Photo from the show",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (pagerState.currentPage == 0) {
                        "Spotify builds the cover from the album art"
                    } else {
                        "Your photo ${pagerState.currentPage} of ${candidates.size}" +
                            (showDate?.let { ", $it" } ?: "")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (pagerState.currentPage == 0) {
                        "Swipe left for your photos"
                    } else {
                        "Swipe for another photo, or right for Spotify's collage"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
