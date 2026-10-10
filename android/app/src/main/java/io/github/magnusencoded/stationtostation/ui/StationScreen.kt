package io.github.magnusencoded.stationtostation.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.magnusencoded.stationtostation.AddGigLink
import io.github.magnusencoded.stationtostation.AppViewModel
import io.github.magnusencoded.stationtostation.BuildConfig
import io.github.magnusencoded.stationtostation.CoverCandidate
import io.github.magnusencoded.stationtostation.ErrorKind
import io.github.magnusencoded.stationtostation.GigLink
import io.github.magnusencoded.stationtostation.GigMenuItem
import io.github.magnusencoded.stationtostation.MediaThumb
import io.github.magnusencoded.stationtostation.NOT_STAMPED
import io.github.magnusencoded.stationtostation.NightKind
import io.github.magnusencoded.stationtostation.PendingTicket
import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.caption
import io.github.magnusencoded.stationtostation.data.AdmissionDrawing
import io.github.magnusencoded.stationtostation.data.AdmissionShape
import io.github.magnusencoded.stationtostation.data.Band
import io.github.magnusencoded.stationtostation.data.DeviceLocation
import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.FriendArrival
import io.github.magnusencoded.stationtostation.data.FutureRow
import io.github.magnusencoded.stationtostation.data.MediaOffer
import io.github.magnusencoded.stationtostation.data.ParsedTicket
import io.github.magnusencoded.stationtostation.data.ReleaseHint
import io.github.magnusencoded.stationtostation.data.StoredAdmission
import io.github.magnusencoded.stationtostation.data.StoredAttendance
import io.github.magnusencoded.stationtostation.data.StoredLog
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.StoredPlaylist
import io.github.magnusencoded.stationtostation.data.StoredSetlistFmHit
import io.github.magnusencoded.stationtostation.data.TicketOriginals
import io.github.magnusencoded.stationtostation.data.WovenSong
import io.github.magnusencoded.stationtostation.data.admissionDrawing
import io.github.magnusencoded.stationtostation.data.atUser
import io.github.magnusencoded.stationtostation.data.bandsOf
import io.github.magnusencoded.stationtostation.data.doorDrawing
import io.github.magnusencoded.stationtostation.data.futureRows
import io.github.magnusencoded.stationtostation.data.gigInviteUri
import io.github.magnusencoded.stationtostation.data.handle
import io.github.magnusencoded.stationtostation.data.hintForAdding
import io.github.magnusencoded.stationtostation.data.hintForMoving
import io.github.magnusencoded.stationtostation.data.isLocal
import io.github.magnusencoded.stationtostation.data.isMyNight
import io.github.magnusencoded.stationtostation.data.laneKey
import io.github.magnusencoded.stationtostation.data.musicbrainz.MbArtist
import io.github.magnusencoded.stationtostation.data.nameOf
import io.github.magnusencoded.stationtostation.data.parseFmDate
import io.github.magnusencoded.stationtostation.data.photos.PhotoRepository
import io.github.magnusencoded.stationtostation.data.postFiling
import io.github.magnusencoded.stationtostation.data.preamble
import io.github.magnusencoded.stationtostation.data.rankTitles
import io.github.magnusencoded.stationtostation.data.setlistEditEntry
import io.github.magnusencoded.stationtostation.data.setlistPaste
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSong
import io.github.magnusencoded.stationtostation.data.setlistfm.line
import io.github.magnusencoded.stationtostation.data.setlistfm.setlistFmQuestion
import io.github.magnusencoded.stationtostation.data.spineNights
import io.github.magnusencoded.stationtostation.data.visibleToContacts
import io.github.magnusencoded.stationtostation.data.waitingOn
import io.github.magnusencoded.stationtostation.data.weaveSetlist
import io.github.magnusencoded.stationtostation.data.withheldFromContacts
import io.github.magnusencoded.stationtostation.data.zxingFormatName
import io.github.magnusencoded.stationtostation.features.tour.ContextHint
import io.github.magnusencoded.stationtostation.features.tour.TourCoachMarkInline
import io.github.magnusencoded.stationtostation.features.tour.TourEvent
import io.github.magnusencoded.stationtostation.features.tour.nowForGig
import io.github.magnusencoded.stationtostation.features.tour.running
import io.github.magnusencoded.stationtostation.gigMenu
import io.github.magnusencoded.stationtostation.nearestGig
import io.github.magnusencoded.stationtostation.nightKind
import io.github.magnusencoded.stationtostation.ui.flyover.CollectionFlyoverScreen
import io.github.magnusencoded.stationtostation.ui.flyover.collectionBillboard
import io.github.magnusencoded.stationtostation.ui.flyover.collectionFlyoverGigs
import io.github.magnusencoded.stationtostation.ui.flyover.collectionMedia
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Station to Station — the timeline face of the app.
// Flow: splash (log in with Spotify, or skip to setlists-only) → the timeline
// of your setlist.fm shows → a single night's real setlist → convert to a
// Spotify playlist. Import lives behind the "+" node, not the front door.
// ponytail: the convert/login flow still lives in ConfirmScreen rather than
// here. Fold it in only if the hop between the two ever reads as a seam.

// --- Nocturnal palette. Amber only ever marks a live/lit moment. ---
private val Ground = Color(0xFF0E0B14)
private val Raised = Color(0xFF17121F)
private val Raised2 = Color(0xFF1D1728)
private val LineCol = Color(0xFF2E2740)
private val LineLit = Color(0xFF4A3F63)
private val Ink = Color(0xFFEDE9F2)
private val Muted = Color(0xFF8B8299)
private val Faint = Color(0xFF5A5368)
private val Amber = Color(0xFFE7B24C)
private val AmberSoft = Color(0x29E7B24C)

/** Amber with the light off: my own **Line** as a **Contact** sees it (#145). */
internal val Unlit = Color(0xFF7C7788)
internal val UnlitField = Color(0xFF1E1B26)
private val Slate = Color(0xFF6F809D) // the future / a connected-source, a cooler light
private val Danger = Color(0xFFE08A8A)

internal val SlateSoft = Color(0x296F809D)
internal val CrossedSoft = Color(0x296FBF9C)

private val Serif = FontFamily.Serif

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationTimelineScreen(
    viewModel: AppViewModel,
    onOpenEvent: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenConnect: () -> Unit,
    onOpenNearby: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProgramme: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.setlists, state.plannedGigs, state.festivals, state.tour.running) { viewModel.tour.hints.offerProgramme() }
    // Reachable from both the future edge and the empty spine: a collector with no
    // history at all still has a ticket for something.
    var adding by remember { mutableStateOf(false) }
    // What a link pre-filled the open add dialog with; null for a dialog opened by hand.
    var prefill by remember { mutableStateOf<AddGigLink?>(null) }
    LaunchedEffect(state.addGigLink) {
        val link = state.addGigLink ?: return@LaunchedEffect
        prefill = link
        adding = true
        viewModel.consumeAddGigLink()
    }
    // The Gig a long press asked to delete, held while the dialog for lost photographs is up.
    var deleteAsked by remember { mutableStateOf<FmSetlist?>(null) }
    val timelineContext = LocalContext.current
    fun menuFor(gig: FmSetlist): GigMenuSpec? {
        val standing = viewModel.standing(gig.id)
        val entries = gigMenu(standing, gig.url != null).map { item ->
            when (item) {
                GigMenuItem.OPEN_ON_SETLIST_FM -> GigMenuEntry("Open on setlist.fm") {
                    timelineContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(gig.url)))
                }
                GigMenuItem.DELETE -> GigMenuEntry("Delete gig", danger = true) {
                    if (viewModel.photosLostByDeleting(gig.id) > 0) deleteAsked = gig
                    else viewModel.deleteGig(gig.id)
                }
            }
        }
        return if (entries.isEmpty()) null else GigMenuSpec(standing.caption(), entries)
    }
    deleteAsked?.let { gig ->
        DeleteNightDialog(
            photos = viewModel.photosLostByDeleting(gig.id),
            onDelete = { deleteAsked = null; viewModel.deleteGig(gig.id) },
            onDismiss = { deleteAsked = null },
        )
    }
    // Whether the legend's `+ N more` has been opened — where the reader left the
    // disclosure, not a fact to remember across a launch.
    var legendExpanded by remember { mutableStateOf(false) }

    // The *maybe* being compared from its merge row, and the snackbar that
    // offers the answer back. The undo is ephemeral: it lives as long as the snackbar.
    var comparing by remember { mutableStateOf<MaybeNight?>(null) }
    val answers = remember { SnackbarHostState() }
    val answerScope = rememberCoroutineScope()
    fun answer(maybe: MaybeNight, same: Boolean, adopt: Boolean = false) {
        comparing = null
        // Taking their setlist.fm entry can't be undone, so it offers no Undo; the
        // adoption says "Adopted" itself.
        if (adopt) { viewModel.adoptMaybe(maybe); return }
        val night = maybe.theirs.id
        val key = maybe.mine.id
        val write = if (same) viewModel.joinNight(night, key) else viewModel.dismissMaybe(night, key)
        answerScope.launch {
            write.join()
            answers.currentSnackbarData?.dismiss()
            val undo = answers.showSnackbar(
                maybeAnswered(maybe, same),
                actionLabel = "Undo",
                duration = SnackbarDuration.Long,
            )
            if (undo == SnackbarResult.ActionPerformed) {
                if (same) viewModel.unjoinNight(night, key) else viewModel.undismissMaybe(night, key)
            }
        }
    }
    comparing?.let { maybe ->
        MaybeCompareSheet(
            maybe = maybe,
            theirColour = laneColourOf(maybe.friend, state.friends),
            sharing = false,
            onSame = { adopt -> answer(maybe, same = true, adopt = adopt) },
            onApart = { answer(maybe, same = false) },
            onDismiss = { comparing = null },
        )
    }

    CheckInSection(
        plannedGigs = state.plannedGigs,
        offer = state.checkInOffer,
        conflict = state.friendConflict,
        checkInDue = { viewModel.checkInDue() },
        hasLocationPermission = { viewModel.hasLocationPermission() },
        onOffer = { viewModel.offerCheckIn() },
        onCheckIn = { viewModel.checkIn(it) },
        onDismissOffer = { viewModel.dismissCheckInOffer() },
        onConfirmOverwrite = { viewModel.confirmFriendOverwrite() },
        onDismissOverwrite = { viewModel.dismissFriendOverwrite() },
    )

    val actions = TimelineActions(
        gigNow = { viewModel.tour.nowForGig(it) },
        onOpenEvent = onOpenEvent,
        onOpenImport = onOpenImport,
        onOpenNearby = onOpenNearby,
        onOpenProgramme = onOpenProgramme,
        onAddGig = { adding = true; viewModel.tour.dispatch(TourEvent.CurtainPulled) },
        onCompare = { comparing = it },
        menuFor = { menuFor(it) },
        setZoomedOut = {
            viewModel.setZoomedOut(it)
            if (it) viewModel.tour.dispatch(TourEvent.PinchedOut)
        },
        consumeJustConnected = { viewModel.consumeJustConnected() },
        toggleContactLight = { viewModel.toggleContactLight() },
        toggleLineHidden = {
            viewModel.toggleLineHidden(it)
            viewModel.tour.hints.offer(ContextHint.LegendTap(hiding = it in viewModel.state.value.hiddenLines))
        },
        loadMoreSetlists = { viewModel.loadMoreSetlists() },
        resolveFestivals = { viewModel.resolveFestivals() },
        loadFriendTimelines = { viewModel.loadFriendTimelines() },
        timelinesLoading = { viewModel.state.value.timelinesLoading },
        consumeLinkedDate = { viewModel.consumeLinkedDate() },
        linkGig = { id, at -> viewModel.linkGig(id, at) },
        knownGig = { viewModel.knownGig(it) },
        openShow = { viewModel.openShow(it) },
        consumeGigLink = { viewModel.consumeGigLink() },
        openFestival = { viewModel.openFestival(it) },
        toggleFestival = { viewModel.toggleFestival(it) },
        openCollectionWalk = { viewModel.openCollectionWalk(it) },
        photoPreview = { viewModel.photoPreview(it) },
    )

    Scaffold(
        containerColor = Ground,
        snackbarHost = {
            SnackbarHost(answers) { data ->
                Snackbar(
                    data,
                    containerColor = Raised2,
                    contentColor = Ink,
                    actionColor = Amber,
                    shape = RoundedCornerShape(10.dp),
                )
            }
        },
        topBar = {
            TimelineTopBar(
                onOpenConnect = onOpenConnect,
                onOpenProgramme = onOpenProgramme,
                onOpenSettings = onOpenSettings,
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            // Which build is actually on the phone. It is installed over Wi-Fi from CI,
            // and answering that by hashing APKs cost more than it should have.
            Text(
                "${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_SHA}",
                color = Faint.copy(alpha = 0.5f),
                fontSize = 9.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 4.dp),
            )
            if (adding) {
                AddGigDialog(
                    initial = prefill,
                    suggestions = state.artistSuggestions,
                    onArtistTyped = { viewModel.suggestArtists(it) },
                    onArtistPicked = {
                        viewModel.pickArtist(it)
                        viewModel.tour.dispatch(TourEvent.BandPicked)
                    },
                    tour = { TourCoachMarkInline(viewModel) },
                    artistLookup = state.artistLookup,
                    nohit = viewModel.tour.character.lines.getValue("S3").nohit.orEmpty(),
                    failed = viewModel.tour.character.lines.getValue("S3").failed.orEmpty(),
                    tourPlanning = state.tour.running && state.tour.step in setOf(io.github.magnusencoded.stationtostation.features.tour.TourStep.S3, io.github.magnusencoded.stationtostation.features.tour.TourStep.S4),
                    onAdd = { artist, venue, date ->
                        viewModel.addGig(artist, venue, date)
                        adding = false
                        prefill = null
                    },
                    onAddByLink = { link -> viewModel.addPlannedGig(link); adding = false; prefill = null },
                    onDismiss = { viewModel.clearArtistSuggestions(); adding = false; prefill = null },
                )
            }
            state.pendingTicket?.let { pending ->
                // Keyed by the ticket, so the next one in the queue opens with its own
                // fields rather than the last one's edits.
                key(pending.id) {
                    TicketConfirmDialog(
                        pending = pending,
                        suggestions = state.artistSuggestions,
                        onArtistTyped = { viewModel.suggestArtists(it) },
                        onArtistPicked = { viewModel.clearArtistSuggestions() },
                        onConfirm = { artist, venue, date, chosen ->
                            viewModel.clearArtistSuggestions()
                            viewModel.confirmPendingTicket(pending.id, artist, venue, date, chosen)
                        },
                        onDismiss = { viewModel.clearArtistSuggestions(); viewModel.dismissPendingTicket(pending.id) },
                    )
                }
            }
            when {
                state.setlistsLoading && state.setlists.isEmpty() ->
                    CircularProgressIndicator(color = Amber, modifier = Modifier.align(Alignment.Center))

                // One gig I'm going to and nothing else is a timeline, not an empty
                // spine — it is exactly the collector's cold start.
                state.setlists.isEmpty() && state.plannedGigs.isEmpty() ->
                    EmptyTimeline(
                        onAdd = onOpenImport,
                        onAddGig = actions.onAddGig,
                    )

                else -> TimelineLine(
                    state = state,
                    legendExpanded = legendExpanded,
                    onExpandLegend = { legendExpanded = true },
                    actions = actions,
                )
            }
            if (state.contactLight) {
                ContactLightBanner(Modifier.align(Alignment.BottomCenter))
            }
            // The Collection resolution. Entered from the Line and drawn over it,
            // not pushed and not routed: nothing here ever navigates away, so leaving —
            // the same reverse-pinch or back gesture that leaves any resolution — lands
            // you back on the Line at the same scroll position, because you never left
            // it. Landscape is the walk; portrait is every Gig's media combined into one
            // place (story 5) — two faces of the same state, told apart only by
            // orientation, the same split a single Gig already has between the Room and
            // its Flyover.
            state.selectedCollection?.let { node ->
                if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    CollectionFlyoverScreen(
                        viewModel = viewModel,
                        node = node,
                        onBack = { viewModel.closeCollectionWalk() },
                    )
                } else {
                    CollectionMediaScreen(
                        viewModel = viewModel,
                        node = node,
                        onBack = { viewModel.closeCollectionWalk() },
                    )
                }
            }
        }
    }
}

/** Everything the timeline's sections do to the world, so no section holds the [AppViewModel]. */
private class TimelineActions(
    val gigNow: @Composable (String) -> LocalDateTime,
    val onOpenEvent: () -> Unit,
    val onOpenImport: () -> Unit,
    val onOpenNearby: () -> Unit,
    val onOpenProgramme: () -> Unit,
    val onAddGig: () -> Unit,
    val onCompare: (MaybeNight) -> Unit,
    val menuFor: (FmSetlist) -> GigMenuSpec?,
    val setZoomedOut: (Boolean) -> Unit,
    val consumeJustConnected: () -> Unit,
    val toggleContactLight: () -> Unit,
    val toggleLineHidden: (String) -> Unit,
    val loadMoreSetlists: () -> Unit,
    val resolveFestivals: () -> Unit,
    val loadFriendTimelines: () -> Unit,
    val timelinesLoading: () -> Boolean,
    val consumeLinkedDate: () -> Unit,
    val linkGig: (String, GigLink) -> Unit,
    val knownGig: (String) -> FmSetlist?,
    val openShow: (FmSetlist) -> Unit,
    val consumeGigLink: () -> Unit,
    val openFestival: (String) -> Unit,
    val toggleFestival: (String) -> Unit,
    val openCollectionWalk: (TimelineNode.Several) -> Unit,
    val photoPreview: suspend (Uri) -> MediaThumb,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelineTopBar(
    onOpenConnect: () -> Unit,
    onOpenProgramme: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Ground, titleContentColor = Muted),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("◦ ", color = Amber, fontSize = 13.sp, modifier = Modifier.clearAndSetSemantics {})
                Text("Station to Station", fontFamily = Serif, fontSize = 16.sp, color = Muted, modifier = Modifier.asHeading())
            }
        },
        actions = {
            // Left/right axis is people: the way to others starts here.
            IconButton(onClick = onOpenConnect) {
                Icon(Icons.Filled.Person, contentDescription = "Connect with people", tint = Faint)
            }
            IconButton(onClick = onOpenProgramme) {
                Icon(
                    Icons.Filled.Schedule,
                    contentDescription = "Festival programme",
                    tint = Faint,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Faint)
            }
        },
    )
}

/**
 * Check-in: opening the timeline takes one fix and compares it against what's already
 * known. Foreground, one-shot, nothing scheduled. Also hosts the friend-overwrite dialog.
 */
@Composable
private fun CheckInSection(
    plannedGigs: List<FmSetlist>,
    offer: FmSetlist?,
    conflict: FriendArrival.Conflict?,
    checkInDue: () -> Boolean,
    hasLocationPermission: () -> Boolean,
    onOffer: () -> Unit,
    onCheckIn: (String) -> Unit,
    onDismissOffer: () -> Unit,
    onConfirmOverwrite: () -> Unit,
    onDismissOverwrite: () -> Unit,
) {
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Refusing is not a dead end and not an error: the offer just never appears,
        // and the gig's own screen still has a check-in you can press by hand.
        onOffer()
    }
    LaunchedEffect(plannedGigs) {
        // The permission is only ever asked for on a night there is something to
        // check into — never merely for opening the app.
        if (!checkInDue()) return@LaunchedEffect
        if (hasLocationPermission()) onOffer()
        else locationPermission.launch(DeviceLocation.requiredPermissions())
    }
    offer?.let { gig ->
        CheckInDialog(
            gig = gig,
            onCheckIn = { onCheckIn(gig.id) },
            onDismiss = onDismissOffer,
        )
    }
    conflict?.let {
        FriendOverwriteDialog(
            conflict = it,
            onConfirm = onConfirmOverwrite,
            onDismiss = onDismissOverwrite,
        )
    }
}

/**
 * The light is on, and it says so across the whole width. Not a badge: a mode
 * you can forget you are in would make withheld photographs read as data loss.
 *
 * Floated over the timeline rather than placed above it, because **flipping
 * the switch must not move the line**. Lighting a corridor does not shorten
 * it: everything here changes colour and opacity and nothing changes size or
 * position, so the night you were looking at is still under your thumb.
 */
@Composable
private fun ContactLightBanner(modifier: Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(UnlitField)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            "AS YOUR CONTACTS SEE IT",
            color = Ink,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp,
            modifier = Modifier.asHeading(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "One view for everyone you have met in person — there are no per-contact " +
                "settings. Walk into a night to see what they see of it. Swipe right " +
                "again to come back.",
            color = Muted,
            fontSize = 11.sp,
        )
    }
}

/** The pull-down gap above the Line: its nested-scroll connection and how far it is open. */
private class PlanningPullGesture(
    val nest: NestedScrollConnection,
    val progress: () -> Float,
    val heightPx: () -> Float,
)

/**
 * Pulling down at the top of the line opens a gap toward the future,
 * and the doors hang in that gap. How far you pull is what
 * picks one: a continuous gesture, not a latched boolean.
 * Release calls [onOpenDoor] with the lit door.
 */
@Composable
private fun rememberPlanningPull(onOpenDoor: (PlanningDoor) -> Unit): PlanningPullGesture {
    val scope = rememberCoroutineScope()
    val pull = remember { Animatable(0f) }
    // 200dp of gap: enough travel to separate three detents by more than
    // a twitch, and enough drag that none is reached by an ordinary flick
    // at the top of the list.
    val pullMax = with(LocalDensity.current) { 200.dp.toPx() }
    val haptics = LocalHapticFeedback.current
    val openDoor = onOpenDoor
    return remember {
        val nest = object : NestedScrollConnection {
            /** Last detent crossed, so each one ticks once. */
            var lastArmed = PlanningDoor.None

            /** Move the gap by a raw drag delta, ticking on each detent. */
            fun drag(dy: Float) {
                scope.launch {
                    pull.snapTo((pull.value + dy * PullDamping).coerceIn(0f, pullMax))
                    // A detent you cannot feel is a threshold, and two
                    // outcomes separated by a bare distance are a coin
                    // flip in the hand.
                    val now = armedDoor(pull.value / pullMax)
                    if (now != lastArmed) {
                        lastArmed = now
                        if (now != PlanningDoor.None) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                }
            }

            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // Closing has to happen *before* the list sees the drag,
                // or the list eats it and the gap never comes back up.
                // See curtainTakes for why.
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val take = curtainTakes(available.y, pull.value)
                if (take == 0f) return Offset.Zero
                drag(take)
                return Offset(0f, take)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // Opening: only the leftover downward scroll at the list's
                // own top edge reaches here, so this never steals an
                // ordinary scroll. Upward is handled in onPreScroll above.
                if (available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
                drag(available.y)
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                // Release takes the lit door. Releasing with none lit
                // closes the gap, so a short pull stays cheap to abandon.
                openDoor(armedDoor(pull.value / pullMax))
                lastArmed = PlanningDoor.None
                pull.animateTo(0f)
                return Velocity.Zero
            }
        }
        PlanningPullGesture(nest, { pull.value / pullMax }, { pull.value })
    }
}

/**
 * Whose line is whose, only while more than one is showing.
 * Scrolls sideways: the key is the one thing that grows without
 * limit as friends are added, and it must not push the line off.
 *
 * Also the filter: tapping a name hides that line and tapping it
 * again brings it back, so the control sits where the names
 * already are rather than on a screen of its own. Shown while
 * zoomed out even with everyone hidden — a name you cannot see
 * is a name you cannot restore.
 */
@Composable
private fun TimelineLegend(
    allLanes: List<Friend>,
    hiddenAt: Map<String, Long>,
    expanded: Boolean,
    onExpand: () -> Unit,
    onToggle: (String) -> Unit,
) {
    // Grouped by recency of hiding, most recently toggled off
    // first — one order for the whole legend, so the
    // disclosure below just continues it.
    val colourByUsername = remember(allLanes) {
        allLanes.withIndex().associate { (i, f) -> f.laneKey to i }
    }
    val (head, rest) = remember(allLanes, hiddenAt) {
        legendSplit(allLanes, hiddenAt, LegendHeadSize)
    }
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LaneKey(Amber, "You")
        (if (expanded) head + rest else head).forEach { friend ->
            Spacer(Modifier.width(14.dp))
            LaneKey(
                // The unfiltered index, never the legend's
                // re-ordered position — a Lane colour comes
                // from `allLanes.enumerated()`.
                color = railColor(colourByUsername[friend.laneKey] ?: 0),
                label = friend.name,
                hidden = friend.laneKey in hiddenAt.keys,
                onToggle = { onToggle(friend.laneKey) },
            )
        }
        // A disclosure, never a truncation: every name
        // above stays reachable, just not drawn until tapped.
        if (!expanded && rest.isNotEmpty()) {
            Spacer(Modifier.width(14.dp))
            Text(
                "+ ${rest.size} more",
                color = Slate,
                fontSize = 11.sp,
                modifier = Modifier
                    .clickable(onClick = onExpand)
                    .padding(vertical = 6.dp),
            )
        }
    }
}

/**
 * A date is only a place once the rows exist. Friends' Lanes load
 * after zooming out, so it waits for them rather than landing on
 * the nearest Gig of a weave with nobody in it yet.
 */
@Composable
private fun LinkedDateScroll(
    linkedDate: LocalDate?,
    rows: List<WovenRow>,
    future: List<FutureRow>,
    timelinesLoading: Boolean,
    zoomedOut: Boolean,
    actions: TimelineActions,
) {
    LaunchedEffect(linkedDate, rows, future, timelinesLoading) {
        val date = linkedDate ?: return@LaunchedEffect
        if (zoomedOut) {
            actions.loadFriendTimelines()
            if (actions.timelinesLoading()) return@LaunchedEffect
        }
        val dated = (rows.flatMap { it.shows + it.showsHereByFriends } +
            future.flatMap { it.node.shows })
            .mapNotNull { show -> show.localDate()?.let { show.id to it } }
        actions.consumeLinkedDate()
        nearestGig(dated, date)?.let {
            actions.linkGig(it, if (zoomedOut) GigLink.WOVEN else GigLink.SINGLE_LINE)
        }
    }
}

/**
 * A station-to-station:// link names a gig, and only here can a
 * gig be turned into a place: one inside a collapsed festival
 * has no row of its own until the festival opens, so this may
 * take two passes — open it, let the rows rebuild, then scroll.
 */
@Composable
private fun LinkedGigScroll(
    linkedGig: String?,
    linkedGigAs: GigLink?,
    rows: List<WovenRow>,
    future: List<FutureRow>,
    expanded: Set<String>,
    listState: LazyListState,
    actions: TimelineActions,
) {
    LaunchedEffect(linkedGig, rows) {
        val gig = linkedGig ?: return@LaunchedEffect
        if (linkedGigAs == GigLink.SETLIST) {
            actions.knownGig(gig)?.let {
                actions.openShow(it)
                actions.consumeGigLink()
                actions.onOpenEvent()
            }
            return@LaunchedEffect
        }
        // A collapsed festival's own shows are only mine, so a night
        // of theirs absorbed into it would never be found and never
        // open the festival holding it.
        // Last, not first: an open festival lists the gig again as a
        // row of its own below its header, and that row is the place
        // the link actually means.
        val at = rows.indexOfLast { row ->
            row.shows.any { it.id == gig } ||
                row.showsHereByFriends.any { it.id == gig }
        }
        if (at < 0) {
            val ahead = future.indexOfFirst { row -> row.node.shows.any { it.id == gig } }
            if (ahead < 0) return@LaunchedEffect
            listState.animateScrollToItem(1 + ahead)
            actions.consumeGigLink()
            return@LaunchedEffect
        }
        val row = rows[at]
        val insideClosedFestival =
            row.node is TimelineNode.Several && row.key !in expanded
        if (insideClosedFestival) {
            actions.openFestival(row.key)
            return@LaunchedEffect
        }
        // The rows don't start at item 0: the future prompt is, and
        // every gig I'm going to sits between it and them. Counted
        // off the same list the LazyColumn emits, so the two cannot
        // drift.
        // …and every merge row down to and including this one's.
        val merges = rows.take(at + 1).count { it.maybeAbove.isNotEmpty() }
        listState.animateScrollToItem(at + 1 + future.size + merges)
        actions.consumeGigLink()
    }
}

/** My Line, with the other timelines woven in beside it while zoomed out. */
@Composable
private fun TimelineLine(
    state: UiState,
    legendExpanded: Boolean,
    onExpandLegend: () -> Unit,
    actions: TimelineActions,
) {
    val earliest = state.setlists.mapNotNull { it.year()?.toIntOrNull() }.minOrNull()
    val listState = rememberLazyListState()
    // Zooming out doesn't go anywhere: the strip beside my line opens and
    // the other timelines slide into it, at my scale, on my spine.
    // A card swap lands you here already zoomed out — you just went
    // looking for their line, so it should be on screen.
    val zoomedOut = state.zoomedOut
    LaunchedEffect(state.justConnected) {
        if (state.justConnected) {
            actions.setZoomedOut(true)
            actions.consumeJustConnected()
        }
    }
    // An immutable set, swapped out on each toggle: a mutable list here
    // is the same instance before and after, so remember() below could
    // never see it change and the rows never rebuilt.
    val expanded = state.openFestivals
    // The legend keeps the whole list — it has to offer a hidden person
    // back — and everything that draws reads the filtered one.
    val allLanes = remember(state.friends) { state.friends.reversed() }
    val lanes = remember(allLanes, state.hiddenLines) {
        visibleLanes(allLanes, state.hiddenLines)
    }
    val colours = remember(allLanes, state.hiddenLines) {
        laneColours(allLanes, state.hiddenLines)
    }
    // Springy rather than timed: the other lines settle into place like
    // something physical arriving, instead of a panel sliding.
    val laneWidth by animateDpAsState(
        if (zoomedOut) stripWidth(lanes.size) else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "lanes",
    )
    // Pulls the next page in before the bottom. Measured against the rows laid
    // out, not the show count: a festival collapses many shows into one row.
    val nearPast by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearPast, state.setlistsLoading, state.setlists.size) {
        if (nearPast && !state.setlistsLoading && state.setlists.size < state.setlistsTotal) {
            actions.loadMoreSetlists()
        }
    }
    // What a door does, whichever way it was reached — the gesture's
    // release and the reader's custom action both call this, so a future
    // rewire of one door can't silently leave the other stale.
    fun openDoor(door: PlanningDoor) {
        when (door) {
            PlanningDoor.Gig -> actions.onAddGig()
            PlanningDoor.Programme -> actions.onOpenProgramme()
            PlanningDoor.Import -> actions.onOpenImport()
            PlanningDoor.None -> {}
        }
    }
    val pull = rememberPlanningPull(::openDoor)

    val timelineActions = listOf(
        CustomAccessibilityAction("Connect with someone nearby") {
            actions.onOpenNearby(); true
        },
        CustomAccessibilityAction(
            if (state.contactLight) "Turn the contact light off"
            else "Turn the contact light on, to see your line as a contact sees it"
        ) { actions.toggleContactLight(); true },
        CustomAccessibilityAction(
            if (zoomedOut) "Close the other timelines"
            else "Open the other timelines beside yours"
        ) { actions.setZoomedOut(!zoomedOut); true },
        // The three doors live in the curtain, and a pull
        // depth is not a thing TalkBack can express — so
        // without these the only way into planning would
        // be a gesture the reader intercepts. Each label
        // matches the door's own text and calls openDoor,
        // the same function the gesture's release calls,
        // so the two paths cannot drift apart.
        CustomAccessibilityAction("Add a gig you're going to") {
            openDoor(PlanningDoor.Gig); true
        },
        CustomAccessibilityAction("Open the festival programme") {
            openDoor(PlanningDoor.Programme); true
        },
        CustomAccessibilityAction("Import your setlist.fm history") {
            openDoor(PlanningDoor.Import); true
        },
    )

    Column(Modifier.fillMaxSize()) {
        Text(
            buildString {
                append("${state.setlists.size} shows")
                if (earliest != null) append(" · since $earliest")
            },
            color = Faint,
            fontSize = 12.sp,
            modifier = Modifier
                .padding(start = 20.dp, top = 2.dp, bottom = 14.dp)
                // The first stop on the line for a screen reader, and so
                // where its moves live: TalkBack lands on a line of text,
                // not on the list under it.
                .semantics { customActions = timelineActions },
        )
        if (zoomedOut || laneWidth > 0.dp) {
            TimelineLegend(
                allLanes = allLanes,
                hiddenAt = state.hiddenAt,
                expanded = legendExpanded,
                onExpand = onExpandLegend,
                onToggle = actions.toggleLineHidden,
            )
        }
        PlanningPull(progress = pull.progress, heightPx = pull.heightPx)
        // Planned nights become Sections too, so adding one can create an
        // evening nothing has been asked about yet.
        LaunchedEffect(state.setlists, state.plannedGigs) {
            actions.resolveFestivals()
        }
        LaunchedEffect(zoomedOut) { if (zoomedOut) actions.loadFriendTimelines() }
        val rows = remember(
            state.setlists, state.plannedGigs, state.attendanceByGig,
            state.festivals, lanes, state.showsByFriend, zoomedOut, expanded,
            state.nightJoins, state.nightsApart,
        ) {
            weaveTimelines(
                // Through `spineNights`, not `setlists` alone: a local gig
                // that stops being a plan — checked into, or committed off
                // a programme whose set has already finished — leaves the
                // future lane at once, and the spine only picked it up on
                // the next cold start.
                // Deduped on id there, so a night on both lists is one.
                mine = spineNights(
                    state.setlists, state.plannedGigs, state.attendanceByGig,
                ),
                festivals = state.festivals,
                friends = if (zoomedOut) lanes else emptyList(),
                theirs = if (zoomedOut) state.showsByFriend else emptyMap(),
                expanded = expanded,
                // What I said about a Contact's Night nothing else links
                // to mine: joined draws Joined, apart draws nothing.
                joins = state.nightJoins,
                apart = state.nightsApart,
            )
        }
        LaunchedEffect(rows, lanes) { logWovenRows(rows, lanes, colours) }
        // Everything above today, in one date-ordered list — furthest
        // out first, the same descending order the attended rows use.
        // Hoisted out of the LazyColumn because the deep-link scroll
        // below counts it too, and the two must not drift.
        val future = remember(
            state.plannedGigs, state.attendanceByGig, state.festivals,
        ) {
            futureRows(
                tickets = state.plannedGigs,
                attendance = state.attendanceByGig,
                festivals = state.festivals,
            )
        }

        LinkedDateScroll(
            linkedDate = state.linkedDate,
            rows = rows,
            future = future,
            timelinesLoading = state.timelinesLoading,
            zoomedOut = zoomedOut,
            actions = actions,
        )
        LinkedGigScroll(
            linkedGig = state.linkedGig,
            linkedGigAs = state.linkedGigAs,
            rows = rows,
            future = future,
            expanded = expanded,
            listState = listState,
            actions = actions,
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(pull.nest)
                // Swipe the timeline left to start connecting with someone
                // nearby — the "act on this level" gesture, people axis.
                .pointerInput(Unit) {
                    val threshold = 90.dp.toPx()
                    var dragX = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragX = 0f },
                        onDragEnd = {
                            // Left is Exchange; right is the light switch,
                            // which is free here because there is nothing
                            // further out than my own Line. A light
                            // is not a place, so the same flick returns.
                            if (dragX <= -threshold) actions.onOpenNearby()
                            else if (dragX >= threshold) actions.toggleContactLight()
                        },
                        onHorizontalDrag = { _, delta -> dragX += delta },
                    )
                }
                // Pinch out to open the other timelines beside mine; pinch
                // back in to close them again. Nothing navigates.
                .pointerInput(state.friends) {
                    detectPinch(
                        onZoomOut = { actions.setZoomedOut(true) },
                        onZoomIn = { actions.setZoomedOut(false) },
                    )
                }
                // The same three moves, for anyone not making them with
                // their fingers. A flick and a pinch are the whole of how
                // this screen changes **Resolution**, and TalkBack sends
                // both to the reader instead — so without this the light
                // and the other lines are not merely awkward to reach,
                // they do not exist. The gestures above stay exactly as
                // they are; this is the same call from another door.
                //
                // Labels are verbs and say which way the toggle goes,
                // because the actions menu reads them out of context with
                // nothing on screen to disambiguate them.
                // Also on the header line above, where a reader's focus
                // can land — a list is not itself a stop for TalkBack.
                .semantics { customActions = timelineActions },
        ) {
            // The top of the line. Nothing sits here now but the lookup
            // notice: "↑ THE FUTURE" captioned a direction the layout
            // already states, and the add-rows that outlived it were the
            // curtain's doors printed a second time — the doors were
            // meant to *replace* them, not join them.
            item { FuturePrompt(loading = state.planningLoading) }
            futureItems(future, expanded, laneWidth, actions)
            wovenItems(rows, lanes, colours, laneWidth, expanded, state, actions)
            // The past edge: a quiet spinner while the next page flows in.
            if (state.setlistsLoading && state.setlists.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator(color = Amber, modifier = Modifier.size(22.dp)) }
                }
            }
        }
    }
}

/**
 * Everything above today, in one date-ordered list — furthest out first, the same
 * descending order the attended rows below use. Planned gigs that share a venue and a
 * night are a Festival like any other, grouped by the same function the attended rows
 * use.
 */
private fun LazyListScope.futureItems(
    future: List<FutureRow>,
    expanded: Set<String>,
    laneWidth: Dp,
    actions: TimelineActions,
) {
    items(
        future,
        key = { row ->
            when (val n = row.node) {
                is TimelineNode.Concert -> "planned-${n.setlist.id}"
                // Prefixed for the same reason the concert above
                // it is: both lanes are items of one LazyColumn,
                // and a Festival with a night still planned and a
                // night already attended is a node in each. The
                // bare identity key would be used twice and throw.
                is TimelineNode.Several -> "planned-${n.key}"
            }
        },
    ) { row ->
        when (val node = row.node) {
            is TimelineNode.Concert -> TimelineItem(
                setlist = node.setlist,
                now = actions.gigNow(node.setlist.id),
                highlight = false,
                planned = true,
                laneWidth = laneWidth,
                menu = actions.menuFor(node.setlist),
                onClick = {
                    actions.openShow(node.setlist)
                    actions.onOpenEvent()
                },
            )

            // Opens in place, like every other node holding
            // several nights. It has to open: collapsing two
            // planned nights into one node with no way back
            // in would take away the only handle each had.
            is TimelineNode.Several -> {
                val key = node.key
                Column {
                    FestivalItem(
                        festival = node,
                        highlight = false,
                        open = key in expanded,
                        laneWidth = laneWidth,
                        onClick = { actions.toggleFestival(key) },
                    )
                    if (key in expanded) {
                        node.shows.forEach { gig ->
                            TimelineItem(
                                setlist = gig,
                                now = actions.gigNow(gig.id),
                                highlight = false,
                                planned = true,
                                inside = true,
                                laneWidth = laneWidth,
                                menu = actions.menuFor(gig),
                                onClick = {
                                    actions.openShow(gig)
                                    actions.onOpenEvent()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The attended rows: my Nights with the other timelines woven in, merge rows between. */
private fun LazyListScope.wovenItems(
    rows: List<WovenRow>,
    lanes: List<Friend>,
    colours: List<Int>,
    laneWidth: Dp,
    expanded: Set<String>,
    state: UiState,
    actions: TimelineActions,
) {
    rows.forEachIndexed { index, row ->
        // The merge row: its own row, between my Night and the
        // Night of theirs the weave put right below it. The rails run
        // on through it; under the contact light it keeps its height
        // and loses its question, so flipping the switch moves nothing.
        val above = rows.getOrNull(index - 1)
        if (row.maybeAbove.isNotEmpty() && above != null) {
            item(key = "maybe-${row.key}") {
                MergeRow(
                    mine = above,
                    theirs = row,
                    lanes = lanes,
                    laneWidth = laneWidth,
                    colours = colours,
                    maybes = if (state.contactLight) emptyList() else row.maybeAbove,
                    onCompare = actions.onCompare,
                )
            }
        }
        item(key = row.key) {
            val isFirst = index == 0
            val rails: @Composable () -> Unit =
                { PeopleRails(row, rows.getOrNull(index + 1), lanes, laneWidth, colours) }
            val nodeX = crossingX(row, lanes, laneWidth)
            when (val node = row.node) {
                is TimelineNode.Concert -> {
                    // Visuals only. A Note has no bytes and an empty
                    // `ref`, and one would draw a blank tile on the row.
                    val nightMedia = state.mediaBySetlist[node.setlist.id]
                        .orEmpty().filterNot { it.kind == StoredMedia.Kind.NOTE }
                    TimelineItem(
                        setlist = node.setlist,
                        now = actions.gigNow(node.setlist.id),
                        highlight = isFirst && row.mine,
                        mine = row.mine,
                        menu = if (row.mine) actions.menuFor(node.setlist) else null,
                        laneWidth = laneWidth,
                        inside = row.depth > 0,
                        nodeX = nodeX,
                        shared = row.shared && !state.contactLight,
                        unlit = state.contactLight,
                        rails = rails,
                        // Unfiltered on purpose. Filtering here removed a
                        // night's whole photo strip, so every row changed
                        // height and the line moved under you — the one
                        // thing a light switch must never do.
                        photos = nightMedia.map { Uri.parse(it.ref) },
                        // Which is why the answer rides alongside instead:
                        // the same thumbnails in the same places, lit one
                        // by one. The Room still holds the detail and the
                        // sharing decision; the timeline says
                        // truthfully which nights are worth opening.
                        litPhotos = visibleToContacts(nightMedia)
                            .map { Uri.parse(it.ref) }.toSet(),
                        loadPhotoPreview = actions.photoPreview,
                        // Off under the light, like the green: a
                        // generic contact view has no "we" to ask about.
                        // Only the maybes no merge row asks: the
                        // rest have a row of their own right below.
                        maybeWith = if (state.contactLight) emptyList()
                        else row.maybeInWords.map { it.name },
                        joinedWith = if (state.contactLight) emptyList()
                        else row.joinedWith.map { it.name },
                        onClick = {
                            actions.openShow(node.setlist)
                            actions.onOpenEvent()
                        },
                    )
                }

                // A festival opens where it stands rather than pushing
                // you into a screen of its own.
                is TimelineNode.Several -> FestivalItem(
                    festival = node,
                    highlight = isFirst,
                    open = row.key in expanded,
                    mine = row.mine,
                    laneWidth = laneWidth,
                    nodeX = nodeX,
                    sharedCount = row.sharedCount,
                    theirCount = row.theirsCount,
                    // Company has a colour of its own — a night two
                    // friends shared is nobody's lane colour either.
                    // …and the lane colour is the host's *stable* one,
                    // so hiding someone never repaints this.
                    theirColor = if (row.others.size > 1) Crossed
                    else railColor(colours.getOrElse(nodeHost(row, lanes)) { 0 }),
                    unlit = state.contactLight,
                    rails = rails,
                    maybeWith = if (state.contactLight) emptyList()
                    else row.maybeInWords.map { it.name },
                    onClick = {
                        actions.toggleFestival(row.key)
                    },
                    // The non-gestural route to the Collection
                    // resolution: the pinch is aimed by where
                    // the fingers land, and a reader with no fingers
                    // to aim needs the same node named instead. Calls
                    // the same function the (not yet built) pinch
                    // will call, so the two paths cannot drift.
                    onWalk = { actions.openCollectionWalk(node) },
                )
            }
        }
    }
}

/**
 * The empty spine: one lit node you tap to bring in your shows.
 *
 * Two doors, and the second is not decoration. The lit node imports from setlist.fm,
 * so without the by-hand row every way onto a fresh timeline would run through an
 * account the app insists is optional. That row is what makes the claim true at the
 * front door as well as in the data model.
 */
@Composable
internal fun EmptyTimeline(onAdd: () -> Unit, onAddGig: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.width(2.dp).height(64.dp).background(LineCol))
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(AmberSoft)
                .border(1.5.dp, Amber, CircleShape)
                .clickable(onClick = onAdd)
                .semantics { contentDescription = "Add your first show" },
            contentAlignment = Alignment.Center,
        ) { Text("+", color = Amber, fontSize = 28.sp) }
        Box(Modifier.width(2.dp).height(30.dp).background(LineCol))
        Spacer(Modifier.height(16.dp))
        Text("Add your first show", fontFamily = Serif, fontSize = 18.sp, color = Ink)
        Spacer(Modifier.height(4.dp))
        Text("Pull your history from setlist.fm.", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        // The one row that does not end at setlist.fm, and a line can start above today
        // as easily as below it: someone with no history yet still has a ticket for something.
        Text(
            "or add a gig by hand",
            color = Slate,
            fontSize = 13.sp,
            modifier = Modifier.clickable(onClick = onAddGig).padding(8.dp),
        )
    }
}

/** The setlist.fm import, reached from the "+" node. Pops itself once shows land. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val startCount = remember { viewModel.state.value.setlists.size }
    var username by remember { mutableStateOf(state.mySetlistFmUser) }
    var apiKey by remember { mutableStateOf("") }
    var byHand by remember { mutableStateOf(false) }

    if (byHand) {
        AddGigDialog(
            initial = null,
            suggestions = state.artistSuggestions,
            onArtistTyped = { viewModel.suggestArtists(it) },
            onArtistPicked = { viewModel.pickArtist(it) },
            onAdd = { artist, venue, date ->
                viewModel.addGig(artist, venue, date)
                byHand = false
                onDone()
            },
            onAddByLink = { link -> viewModel.addPlannedGig(link); byHand = false; onDone() },
            onDismiss = { viewModel.clearArtistSuggestions(); byHand = false },
        )
    }

    // Leave for the timeline the moment an import actually brings shows in.
    LaunchedEffect(state.setlists.size) {
        if (state.setlists.size != startCount && state.setlists.isNotEmpty()) onDone()
    }

    Scaffold(
        containerColor = Ground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ground, titleContentColor = Ink),
                title = { Text("Add your shows", fontFamily = Serif, fontSize = 18.sp, color = Ink, modifier = Modifier.asHeading()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Faint)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(28.dp).fillMaxWidth().swipeRightToBack(onBack = onBack)) {
            Text(
                "Your concert history already lives on setlist.fm. Enter your username and your line fills itself in.",
                color = Muted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(22.dp))
            if (!state.setlistFmReady) {
                StationField(apiKey, { apiKey = it }, "setlist.fm API key")
                // The field alone asks for something a stranger has no way to find.
                Text(
                    "Get a free key at setlist.fm/settings/api ›",
                    color = Amber,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.setlist.fm/settings/api"))
                            )
                        }
                        .padding(vertical = 6.dp),
                )
                Spacer(Modifier.height(10.dp))
            }
            StationField(username, { username = it }, "setlist.fm username", imeDone = true)
            state.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = Danger, fontSize = 12.sp, modifier = Modifier.spokenOnChange())
                // The one error with something to do about it: the bundled key is shared
                // by every tester and today's requests are gone, and a free key of their
                // own is a short trip to Settings away (#457).
                if (state.errorKind == ErrorKind.SETLISTFM_SHARED_QUOTA) {
                    Text(
                        "$ADD_OWN_KEY_ACTION ›",
                        color = Amber,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clickable(onClick = onOpenSettings)
                            .padding(vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    viewModel.importAttended(username.trim(), if (!state.setlistFmReady) apiKey else null)
                },
                enabled = username.isNotBlank() &&
                    (state.setlistFmReady || apiKey.isNotBlank()) &&
                    !state.setlistsLoading,
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color(0xFF241A06)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.setlistsLoading) {
                    CircularProgressIndicator(
                        color = Color(0xFF241A06),
                        modifier = Modifier
                            .size(18.dp)
                            .semantics { contentDescription = "Importing from setlist.fm" }
                            .spokenOnChange(),
                    )
                } else {
                    Text("Import from setlist.fm", fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(20.dp))
            // The door that does not run through an account, kept on the screen whose
            // whole job is "add your shows" — the empty spine offers it too, but the
            // empty spine is gone the moment there is one night on the line (#225).
            Text(
                "or add a gig by hand",
                color = Slate,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable { byHand = true }
                    .padding(vertical = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StationField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    imeDone: Boolean = false,
    /** A pasted lineup is many lines; every other field here is one. */
    singleLine: Boolean = true,
    onDone: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        keyboardOptions = if (imeDone) KeyboardOptions(imeAction = ImeAction.Done) else KeyboardOptions.Default,
        keyboardActions = if (onDone != null) KeyboardActions(onDone = { onDone() }) else KeyboardActions.Default,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Amber,
            unfocusedBorderColor = LineLit,
            focusedTextColor = Ink,
            unfocusedTextColor = Ink,
            cursorColor = Amber,
            focusedLabelColor = Amber,
            unfocusedLabelColor = Faint,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
