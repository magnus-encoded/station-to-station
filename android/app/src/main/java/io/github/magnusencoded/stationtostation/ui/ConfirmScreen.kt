package io.github.magnusencoded.stationtostation.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.magnusencoded.stationtostation.AppViewModel
import io.github.magnusencoded.stationtostation.CoverCandidate
import io.github.magnusencoded.stationtostation.SongMatch
import io.github.magnusencoded.stationtostation.data.photos.PhotoRepository
import io.github.magnusencoded.stationtostation.data.spotify.SpotifyTrack
import kotlinx.coroutines.launch

// The same nocturnal ground as the Station screens, but with Spotify green as the
// accent — this is the one pure-Spotify function, so it earns the green while still
// looking like the rest of the app rather than a different UI. Every
// MaterialTheme.colorScheme.* reference below resolves through this.
// ponytail: the palette is restated per screen file, as every other Station screen
// already does. Lift it into one Palette.kt when a colour actually needs changing
// in more than one place at once.
private val Ground = Color(0xFF0E0B14)
private val Raised = Color(0xFF17121F)
private val LineCol = Color(0xFF2E2740)
private val LineLit = Color(0xFF4A3F63)
private val Ink = Color(0xFFEDE9F2)
private val Muted = Color(0xFF8B8299)
private val Faint = Color(0xFF5A5368)
private val SpotifyGreen = Color(0xFF1DB954)
private val OnGreen = Color(0xFF08210F)
private val Danger = Color(0xFFE08A8A)
private val Serif = FontFamily.Serif

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
    // Set when "create" is pressed with a clip as the cover: the frame picker stands
    // between the press and the playlist.
    var pickingFrameFor by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    val setlist = state.selectedSetlist
    val selectedCount = state.matches.count { it.included && it.selected != null }

    Scaffold(
        containerColor = Ground,
        // The same bar the night wore one screen ago — converting is a thing you do
        // to this show, not a different app you were handed.
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Ground,
                    titleContentColor = Faint,
                ),
                title = {
                    Text(setlist?.year() ?: "", color = Faint, fontSize = 12.sp, letterSpacing = 1.5.sp)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Faint)
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
                        // A clip has no one obvious cover frame, so picking it is the
                        // last step before creating — asked for once, here, rather than
                        // sitting on the screen the whole time.
                        onClick = {
                            val cover = state.selectedCoverUri
                            if (cover != null && viewModel.isVideoCover(cover)) pickingFrameFor = cover
                            else viewModel.createPlaylist()
                        },
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
        Column(Modifier.padding(padding).fillMaxSize().swipeRightToBack(onBack = onBack)) {
            // The same heading block as the event screen, so the night stays put
            // on screen while the machinery changes underneath it.
            if (setlist != null) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
                    Text(
                        setlist.artist?.name ?: "Unknown artist",
                        fontFamily = Serif,
                        fontSize = 24.sp,
                        color = Ink,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        listOfNotNull(setlist.venueLine(), setlist.readableDate()).joinToString(" · "),
                        color = Muted,
                        fontSize = 13.sp,
                    )
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
                Switch(
                    checked = state.playlistPublic,
                    onCheckedChange = viewModel::setPlaylistPublic,
                    modifier = Modifier.semantics { contentDescription = "Public playlist" },
                )
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
            // Track numbers in the playlist being built — not the setlist positions
            // the event screen shows. Two different questions: that screen answers
            // "what did they play", this one answers "what is going in, in what
            // order". Deriving it from the setlist meant including a tape left it
            // unnumbered and excluding a song left its number behind, so neither
            // matched what was about to be created.
            val numbers = remember(state.matches) {
                var n = 0
                state.matches.map { if (it.included && it.selected != null) ++n else null }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                itemsIndexed(state.matches) { index, match ->
                    SongMatchRow(
                        number = numbers.getOrNull(index),
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

    // Stands between "create" and the playlist when the cover is a clip.
    pickingFrameFor?.let { videoUri ->
        VideoFrameDialog(
            uri = videoUri,
            frameMs = state.selectedCoverFrameMs,
            durationOf = viewModel::videoDurationMs,
            frameAt = viewModel::videoFrameAt,
            onFrameChange = viewModel::setCoverFrame,
            onDismiss = { pickingFrameFor = null },
            onConfirm = {
                pickingFrameFor = null
                viewModel.createPlaylist()
            },
        )
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
            // Keepsakes already pinned to the gig need no gallery permission, so they
            // take priority over the permission prompt below — a Reliver who added
            // photos to the event already handed us the picture, no need to ask again.
            candidates.isNotEmpty() -> key(candidates) {
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
                if (!permissionGranted) {
                    TextButton(
                        onClick = onRequestPermission,
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) { Text("Find more from your gallery") }
                }
            }
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
            searched -> Text(
                "No photos from ${showDate ?: "that night"} in your gallery — " +
                    "Spotify will build the cover from the album art.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Picks the one frame of a clip worth being the cover — the last step before the
 * playlist is made, so it gets the whole screen rather than a slot on the confirm
 * form. Scrubbed vertically, like the timeline the night itself is read on: the start
 * of the clip at the top, the end at the bottom.
 */
@Composable
private fun VideoFrameDialog(
    uri: Uri,
    frameMs: Long,
    durationOf: suspend (Uri) -> Long,
    frameAt: suspend (Uri, Long) -> Bitmap?,
    onFrameChange: (Long) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var duration by remember(uri) { mutableStateOf(0L) }
    var frame by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var trackHeight by remember { mutableStateOf(1) }
    LaunchedEffect(uri) { duration = durationOf(uri) }
    // Decoding trails the drag rather than racing it: a frame grab costs more than a
    // finger moves, so this only ever chases the value the scrub has settled on.
    LaunchedEffect(uri, frameMs) { frame = frameAt(uri, frameMs) }

    val scrubTo: (Float) -> Unit = { y ->
        if (duration > 0L) onFrameChange(((y / trackHeight).coerceIn(0f, 1f) * duration).toLong())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose the cover frame") },
        text = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(ScrubHeight)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    frame?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "The frame this clip will use as the cover",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } ?: CircularProgressIndicator(Modifier.size(20.dp))
                }
                Spacer(Modifier.width(16.dp))
                // The track: tall and thin, dragged with a thumb marking where in the
                // clip this frame sits.
                Box(
                    Modifier
                        .width(32.dp)
                        .height(ScrubHeight)
                        .onSizeChanged { trackHeight = it.height.coerceAtLeast(1) }
                        .pointerInput(uri, duration) {
                            detectVerticalDragGestures { change, _ -> scrubTo(change.position.y) }
                        }
                        .pointerInput(uri, duration) {
                            detectTapGestures { offset -> scrubTo(offset.y) }
                        },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    val fraction =
                        if (duration <= 0L) 0f else (frameMs.toFloat() / duration).coerceIn(0f, 1f)
                    Box(
                        Modifier
                            .padding(top = (ScrubHeight - ScrubThumb) * fraction)
                            .size(ScrubThumb)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Use this frame (%d:%02d)".format(frameMs / 60_000, (frameMs / 1000) % 60))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val ScrubHeight = 220.dp
private val ScrubThumb = 14.dp

/**
 * One song on the same spine it was read on a screen ago — no card, no checkbox.
 * The node carries the whole state of the match: filled green and numbered means it
 * is going into the playlist at that position, hollow means it isn't, red means
 * Spotify had nothing to offer. Tapping the node includes or drops the song;
 * tapping the row opens the choices.
 *
 * [number] is the track's place in the playlist, so it is null whenever the song
 * isn't going in — and every remaining number closes up behind it.
 */
@Composable
private fun SongMatchRow(
    number: Int?,
    match: SongMatch,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleIncluded: () -> Unit,
    onChooseCandidate: (SpotifyTrack) -> Unit,
    onResearch: (String) -> Unit,
) {
    val on = match.included && match.selected != null
    val nodeColor = when {
        match.loading -> LineLit
        match.selected == null -> Danger
        on -> SpotifyGreen
        else -> LineLit
    }
    Column {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(end = 12.dp)) {
            Box(Modifier.width(50.dp).fillMaxHeight()) {
                Box(
                    Modifier.align(Alignment.TopCenter).width(2.dp).fillMaxHeight().background(LineCol),
                )
                // Full size whether or not it carries a number: this node is the
                // include/drop control, and a target that shrinks the moment you
                // use it is a target you then have to hunt for to undo.
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 2.dp)
                        .minimumInteractiveComponentSize()
                        .clickable(enabled = match.selected != null, onClick = onToggleIncluded)
                        .semantics {
                            contentDescription = when {
                                match.selected == null -> "No Spotify match — ${match.song.name}"
                                on -> "Included in playlist — ${match.song.name}"
                                else -> "Not included in playlist — ${match.song.name}"
                            }
                            this.selected = on
                            role = Role.Button
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(if (on) SpotifyGreen else Raised)
                            .border(1.5.dp, nodeColor, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (number != null) {
                            Text(number.toString(), color = OnGreen, fontSize = 10.sp)
                        }
                    }
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onToggleExpand)
                    .padding(top = 1.dp, bottom = 14.dp),
            ) {
                // Say "tape" here: it is the reason the row starts dropped, and
                // without it an unnumbered node looks like something went wrong.
                Text(
                    match.song.name + when {
                        match.isCover -> " · ${match.searchArtist} cover"
                        match.song.tape -> " · tape"
                        else -> ""
                    },
                    color = if (on) Ink else Muted,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                when {
                    match.loading -> Text("searching…", color = Faint, fontSize = 11.sp)
                    match.selected != null -> Text(
                        "${match.selected.name} · ${match.selected.artistNames()}",
                        color = if (on) SpotifyGreen else Faint,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    else -> Text(
                        match.error ?: "nothing on Spotify — tap to search",
                        color = Danger,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onToggleExpand) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = Faint,
                )
            }
        }
        if (expanded) {
            CandidatePicker(match, onChooseCandidate, onResearch)
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
    // Indented to the spine, so the alternatives hang off the song's own node.
    Column(Modifier.padding(start = 50.dp, end = 16.dp, bottom = 10.dp)) {
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
