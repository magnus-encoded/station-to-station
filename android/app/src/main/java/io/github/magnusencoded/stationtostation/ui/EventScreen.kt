package io.github.magnusencoded.stationtostation.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.magnusencoded.stationtostation.AppViewModel
import io.github.magnusencoded.stationtostation.CoverCandidate
import io.github.magnusencoded.stationtostation.ErrorKind
import io.github.magnusencoded.stationtostation.MediaThumb
import io.github.magnusencoded.stationtostation.NOT_STAMPED
import io.github.magnusencoded.stationtostation.NightKind
import io.github.magnusencoded.stationtostation.data.Band
import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.StoredAdmission
import io.github.magnusencoded.stationtostation.data.StoredAttendance
import io.github.magnusencoded.stationtostation.data.StoredLog
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.StoredPlaylist
import io.github.magnusencoded.stationtostation.data.WovenSong
import io.github.magnusencoded.stationtostation.data.gigInviteUri
import io.github.magnusencoded.stationtostation.data.handle
import io.github.magnusencoded.stationtostation.data.isLocal
import io.github.magnusencoded.stationtostation.data.isMyNight
import io.github.magnusencoded.stationtostation.data.laneKey
import io.github.magnusencoded.stationtostation.data.nameOf
import io.github.magnusencoded.stationtostation.data.photos.PhotoRepository
import io.github.magnusencoded.stationtostation.data.postFiling
import io.github.magnusencoded.stationtostation.data.preamble
import io.github.magnusencoded.stationtostation.data.rankTitles
import io.github.magnusencoded.stationtostation.data.setlistEditEntry
import io.github.magnusencoded.stationtostation.data.setlistPaste
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.line
import io.github.magnusencoded.stationtostation.data.spineNights
import io.github.magnusencoded.stationtostation.data.visibleToContacts
import io.github.magnusencoded.stationtostation.data.waitingOn
import io.github.magnusencoded.stationtostation.data.weaveSetlist
import io.github.magnusencoded.stationtostation.data.withheldFromContacts
import io.github.magnusencoded.stationtostation.features.tour.nowForGig
import io.github.magnusencoded.stationtostation.nightKind
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Amber = Color(0xFFE7B24C)
private val Ink = Color(0xFFEDE9F2)
private val Muted = Color(0xFF8B8299)
private val Faint = Color(0xFF5A5368)
private val Slate = Color(0xFF6F809D) // the future / a connected-source, a cooler light
private val Serif = FontFamily.Serif
private val Ground = Color(0xFF0E0B14)
private val LineCol = Color(0xFF2E2740)
private val SpotifyGreen = Color(0xFF1DB954)
private val Danger = Color(0xFFE08A8A)

/** A share-sheet intent carrying a gig-invite deep link a contact's app can open. */
internal fun gigInviteChooser(setlist: FmSetlist): Intent {
    val label = listOfNotNull(setlist.artist?.name, setlist.venue?.name, setlist.readableDate())
        .joinToString(" · ")
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "Come to this with me — $label\n${gigInviteUri(setlist.id)}")
    }
    return Intent.createChooser(send, "Invite a friend")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StationEventScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onConvert: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val setlist = state.selectedSetlist
    val now = viewModel.tour.nowForGig(setlist?.id.orEmpty())
    val context = LocalContext.current
    // A night I'm going to, not one I was at. Everything this screen says about a
    // setlist has to change: there is no setlist to be missing yet.
    //
    // The claim decides this, not `gigPlanned` membership (#127) — see [isPlanned].
    val planned = setlist != null && isPlanned(state.attendanceByGig[setlist.id]?.provenance)
    // Read off state rather than asked of the view model, so checking in redraws
    // this screen instead of leaving the button sitting there.
    val checkedIn = setlist != null &&
        state.attendanceByGig[setlist.id]?.provenance == StoredAttendance.Provenance.CHECKED_IN
    val made = setlist?.let { state.playlistsBySetlist[it.id] }.orEmpty()
    val heldMedia = setlist?.let { state.mediaBySetlist[it.id] }.orEmpty()
    // Under the contact light the room holds what a Contact can see, through the one
    // rule that also builds their manifest (#145). Withheld items never come back as
    // content here — only as a count, and only when asked for.
    val gigMedia = if (state.contactLight) visibleToContacts(heldMedia) else heldMedia
    LaunchedEffect(setlist, gigMedia, state.contactLight, state.attendanceByGig, state.setlists, state.plannedGigs, state.logsByGig, state.tour) { viewModel.tour.hints.offerInRoom() }
    val withheld = if (state.contactLight) withheldFromContacts(heldMedia) else emptyList()
    // A **Note** has no bytes and an empty [StoredMedia.ref], so every path that
    // resolves a reference has to be handed the visual run instead of the night.
    // Split once, here, rather than guarded at each of the six call sites below.
    val gigVisuals = gigMedia.filterNot { it.kind == StoredMedia.Kind.NOTE }
    val gigPhotos = gigVisuals.map { Uri.parse(it.ref) }
    // The night's own facts, for the **Preamble** over a **Note** (#50). Derived on
    // every composition and never stored: **Reconcile** has no time bound, so who the
    // record knows was here changes, and a frozen sentence would be the app putting
    // words in my mouth about an evening it has since learned more about.
    val alsoThere = setlist?.let { s ->
        state.friends.filter { f ->
            f.laneKey.isNotBlank() && state.showsByFriend[f.laneKey].orEmpty().any { it.id == s.id }
        }.map { it.name }
    }.orEmpty()
    val gigPreamble = preamble(
        people = alsoThere,
        venue = setlist?.venue?.name,
        songCount = setlist?.performed()?.size ?: 0,
    )
    // Whether this night is one of my own, through the one rule (#327). A **Contact**'s
    // night is reachable from the timeline exactly like mine — it has to be — and every
    // control that *changes* it has to ask this first, because attaching to their night
    // acquires it: the **Gig** becomes a record here and their Shared media for it
    // routes to me on the next **Reconcile**.
    val mineNight = setlist != null && isMyNight(
        setlist.id,
        state.attendanceByGig[setlist.id],
        state.setlists,
        state.plannedGigs,
    )
    // The two reasons the room is read-only, folded once. They are different questions —
    // the light previews someone else's view of *my* night, this is *their* night — and
    // either one is enough.
    val editable = mineNight && !state.contactLight
    // Arranging belongs to the Room, not to the strip: that is the whole of "a tap
    // anywhere that is not an [x] leaves it".
    var arranging by remember(setlist?.id) { mutableStateOf(false) }
    // Which band the handle was released over, held across the picker's round trip —
    // the answer is given by the gesture and the picker cannot carry it.
    var attachTo by remember { mutableStateOf(Band.VAULT) }
    // The Reliver picks straight from the system photo (and video) picker — no
    // gallery permission needed for that path, unlike the suggestions below.
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        setlist?.let { viewModel.tour.returnedFromPhotos(it.id) }
        if (uris.isNotEmpty()) setlist?.let { viewModel.addPickedGigPhotos(it.id, uris, attachTo) }
    }
    // Gallery access is only ever asked for after the "suggest" tap, so opening
    // a gig never triggers a permission prompt on its own.
    val gigSuggestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.loadGigPhotoSuggestions() }
    // Silent when permission isn't there yet — same guard as the prompt above,
    // so a gig already granted access just re-searches without another tap.
    LaunchedEffect(setlist?.id) { viewModel.loadGigPhotoSuggestions() }
    // The disambiguation's answer, either way. It runs from this screen and until
    // now landed nowhere: "found them, songs are from X" was written into state and
    // no screen but Friends renders a notice, so the one gesture whose whole point
    // is to tell you *which* band you got told you nothing — and a dead end, which
    // deliberately leaves the old pool alone, was indistinguishable from success.
    // Toast because that is already how this screen answers publish and calendar.
    LaunchedEffect(state.notice) {
        state.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.consumeNotice()
        }
    }
    // A toast cannot be tapped, so the one error with a way out of it gets a dialog
    // instead: refreshing this setlist spent the last of the shared key, and a free key
    // of your own is the fix (#457).
    var sharedQuotaNudge by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.error) {
        state.error?.let {
            if (state.errorKind == ErrorKind.SETLISTFM_SHARED_QUOTA) {
                sharedQuotaNudge = it
            } else {
                Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            }
            viewModel.consumeError()
        }
    }
    sharedQuotaNudge?.let { message ->
        AlertDialog(
            onDismissRequest = { sharedQuotaNudge = null },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    sharedQuotaNudge = null
                    onOpenSettings()
                }) { Text(ADD_OWN_KEY_ACTION) }
            },
            dismissButton = {
                TextButton(onClick = { sharedQuotaNudge = null }) { Text("Not now") }
            },
        )
    }
    var viewerUri by remember { mutableStateOf<Uri?>(null) }
    // Where the viewer should open — set when a stamped song on the spine is tapped,
    // so the recording lands on that song instead of at the top of the night.
    var viewerStartMs by remember { mutableStateOf(NOT_STAMPED) }
    // The night's full recording: the first video among the keepsakes. Photos and
    // one-song clips sit alongside it and are not treated as the recording.
    // Kind comes off the record now (#97), not from asking the ContentResolver —
    // a reference that has died still knows what it was.
    val recordingMedia = gigVisuals.firstOrNull { it.kind == StoredMedia.Kind.VIDEO }
    val recording = recordingMedia?.let { Uri.parse(it.ref) }

    // The planned-gig leaf, staged like the Spotify convert (#55): the swipe adds the
    // gig to the calendar, then — once the event exists and its link is showing —
    // graduates to inviting a friend, which repeats forever. The event's URI is both
    // the "already added" flag and the thing the link opens.
    val scope = rememberCoroutineScope()
    val calendarEventUri = setlist?.let { state.calendarEventByGig[it.id] }
    val added = calendarEventUri != null
    // Only in the plan-ahead window does the swipe do the calendar/invite dance. PAST
    // keeps the setlist.fm crumb, DAY_OF is the check-in — both left to the fall-through
    // below, exactly as they behaved before, so the swipe never contradicts the hint.
    // --- The Historian's half: my own Log of this night, and where it goes ---------
    //
    // A Log makes sense the moment I am known to have been there — a check-in, or a
    // night this app minted itself, which only ever happens by someone standing in
    // front of the stage tapping an Act. It stays available *forever* after that:
    // remembering a song three days later must cost nothing, so nothing below removes
    // the editor. The clock only decides which action leads.
    val log = setlist?.let { state.logsByGig[it.id] } ?: StoredLog()
    // What there is to convert: setlist.fm's songs, or a **Log** I said was complete.
    // A night I checked into never reaches the convert branch below — `canLog` claims
    // the bottom bar first — so this has to be offered there too, or the one night the
    // app itself is the record of is the one night that cannot become a playlist.
    val convertible = setlist != null &&
        (setlist.performed().isNotEmpty() || (log.closed && log.named().isNotEmpty()))
    val localGig = setlist != null && setlist.isLocal()
    val canLog = setlist != null && (checkedIn || localGig)
    /**
     * This night's **Presence row**, hoisted because the bottom bar has two branches and a
     * checked-in night can arrive in either — `canLog` claims the bar first, and the plan-ahead
     * branch still draws it for a night checked into without a **Log** to keep. One definition
     * so the two can never say different things about the same night.
     */
    val presenceRow: @Composable () -> Unit = {
        if (setlist != null) GossipPresenceRow(
            eligibleUntil = state.gossipEligibleUntil[setlist.id],
            active = state.gossipActiveGig == setlist.id,
            stopped = setlist.id in state.gossipStoppedGigs,
            friends = state.friends,
            onSelect = { viewModel.selectGossipGig(setlist.id) },
            onExpiry = { viewModel.refreshGossip() },
        )
    }
    // Whose catalogue to offer when correcting an entry: the night's own setlist.fm
    // record. Hoisted above the Log editor because the pull-to-refresh curtain
    // (below) needs the same answer.
    val catalogueArtist = setlist?.artist?.mbid?.ifBlank { null }
    val catalogue = catalogueArtist?.let { state.catalogueByArtist[it] }.orEmpty()
    val catalogueLoading = catalogueArtist != null && state.catalogueFetching == catalogueArtist
    // Which of my **Log**'s entries has its correction panel open, if any. One at a
    // time: this is a room you are standing in, not a list of forms. It lives here
    // rather than in the editor because the entries themselves are on the spine now.
    var correctingLog by remember(setlist?.id) { mutableStateOf<Int?>(null) }
    // The state of this **Gig**, as known — one value, decided once (#129). Everything
    // on this screen is a rendering of this link's state, and before this each part
    // worked it out again from a different subset and they disagreed.
    val gigAsKnown = GigAsKnown(
        window = setlist?.localDate()?.let { nightWindow(it) },
        provenance = if (checkedIn) StoredAttendance.Provenance.CHECKED_IN else null,
        // An editor nobody has typed in is not a **Log**. `log` above defaults to an
        // empty one so there is always something to render; the decision needs the
        // difference between "never started" and "started and still open".
        log = log.takeIf { it.songs.isNotEmpty() || it.closed },
        setlistId = setlist?.id?.takeUnless { localGig },
        songCount = setlist?.performed()?.size ?: 0,
        calendarEvent = calendarEventUri,
        admissionCount = setlist?.let { state.attendanceByGig[it.id]?.admissions?.size } ?: 0,
    )
    // The phase and the curtain come off the same value as the offers, so they cannot
    // disagree. The alcove is still not dispatched from — the swipe's action order is
    // a separate, deliberately deferred change (#129).
    val offers = gigOffers(gigAsKnown, now)
    val leaf = offers.phase
    // What pulling the curtain down asks for, decided by the same fold that draws the
    // chip — never the same request on a night three weeks away, a night being stood
    // at, and a night from 1992. The dispatch itself (`curtainAction`) is pure and
    // tested; only the plumbing it names lives here.
    //
    // A local Gig is asked of setlist.fm first, whatever the curtain says (#531): the
    // pull is how a person says "is it there yet?". The curtain's own action then runs
    // as it always has, less the setlist refresh the lookup already was.
    val onPullToRefresh: () -> Unit = {
        val local = setlist?.isLocal() == true
        if (local) viewModel.refreshSelectedSetlist()
        when (curtainAction(offers.curtain)) {
            CurtainAction.FETCH_CATALOGUE -> catalogueArtist?.let(viewModel::fetchCatalogue)
            CurtainAction.FETCH_SETLIST -> if (!local) viewModel.refreshSelectedSetlist()
            CurtainAction.NONE -> {}
        }
    }
    // **Publish**: explicit, labelled, and never a side effect of anything else. The
    // clipboard is the entire channel — setlist.fm's form takes no prefill parameters
    // and its Text Field editor takes a whole ordered set in one paste — so the copy
    // and the door open together, announced, on a tap that says it will.
    //
    // The songs are one of five things the form wants, and the other four were crossing
    // the app switch in the Historian's memory because this screen is gone the moment
    // the browser is up. `postFiling` parks all five in the notification shade, which is
    // the one surface still in reach of Chrome. Songs stay on the clipboard as well —
    // the shade is an upgrade to the handoff, never a gate on it, so a denied
    // notification permission leaves this behaving exactly as it always did.
    val publish: () -> Unit = publish@{
        val s = setlist ?: return@publish
        val clip = context.getSystemService(ClipboardManager::class.java)
        clip?.setPrimaryClip(ClipData.newPlainText("setlist", setlistPaste(log)))
        postFiling(context, s, log)
        Toast.makeText(
            context,
            if (log.songs.isEmpty()) "Nothing logged yet — the gig itself is still worth adding."
            else "${log.songs.size} songs copied. The rest is in your notifications.",
            Toast.LENGTH_LONG,
        ).show()
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(setlistEditEntry(s))))
    }
    // Asked for on the way to publishing, never on launch, and the answer does not
    // gate anything: whichever way it goes, `publish` runs straight after.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { publish() }
    val onPublish: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            publish()
        } else {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    var adopting by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var askingMatch by remember { mutableStateOf(false) }
    // Which **Contact**'s offer is being answered, by their Night id (#405).
    var answeringOffer by remember { mutableStateOf<String?>(null) }
    // The *maybes* on this Night (#405): a Contact out the same date under a Night
    // nothing links to mine. The same rule the Spine's weave draws them by, asked of
    // this one Night, so the Room and the Line cannot disagree about which are open.
    val maybes = remember(
        setlist?.id, planned, state.setlists, state.plannedGigs, state.attendanceByGig,
        state.friends, state.showsByFriend, state.festivals, state.nightJoins, state.nightsApart,
    ) {
        val here = setlist
        if (here == null || planned) emptyList()
        else maybeNights(
            mine = spineNights(state.setlists, state.plannedGigs, state.attendanceByGig),
            friends = state.friends,
            theirs = state.showsByFriend,
            festivals = state.festivals,
            joins = state.nightJoins,
            apart = state.nightsApart,
        ).filter { it.mine.id == here.id }
    }
    // Which maybe is being asked, and — when the question came from going to share
    // media (story 22) — the band to carry on into once it is answered.
    var askingMaybe by remember { mutableStateOf<MaybeNight?>(null) }
    var shareAfterMaybe by remember { mutableStateOf<Band?>(null) }
    // A drag up out of the vault is sharing too, so it asks the same one question —
    // after the move, which has already happened by the time the finger lifts.
    fun moveAndAsk(key: String, id: String, band: Band, index: Int) {
        val wasKept = state.mediaBySetlist[key].orEmpty().any { it.id == id && it.personal }
        viewModel.moveGigMedia(key, id, band, index)
        if (band == Band.SHARED && wasKept) maybes.firstOrNull()?.let { askingMaybe = it }
    }

    val plannedTimeState = if (planned) setlist?.localDate()?.let { gigTimeState(now, it) } else null
    val planAhead = planned &&
        plannedTimeState != GigTimeState.PAST && plannedTimeState != GigTimeState.DAY_OF
    // The demo Gig is local, so its Log bar would hide S10's calendar action.
    val logBar = canLog && !(planAhead && setlist?.let { viewModel.tour.isDemoGig(it.id) } == true)
    // The insert is a couple of binder calls, so it runs off the main thread; success
    // persists the returned URI, and every failure (no writable calendar, provider
    // refusal) offers an explicit Tour opt-out, without inventing an event receipt.
    var calendarFailure by remember { mutableStateOf<String?>(null) }
    calendarFailure?.let { message ->
        AlertDialog(onDismissRequest = { calendarFailure = null },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { calendarFailure = null }) { Text("OK") } },
            dismissButton = {
                if (setlist != null && viewModel.tour.isDemoGig(setlist.id) &&
                    state.tour.step == io.github.magnusencoded.stationtostation.features.tour.TourStep.S10) {
                    TextButton(onClick = { calendarFailure = null; viewModel.tour.continueWithoutCalendar(setlist.id) }) {
                        Text("Continue without calendar")
                    }
                }
            })
    }
    val addToCalendar: () -> Unit = add@{
        val s = setlist ?: return@add
        val world = viewModel.tour.calendarWorld(s.id)
        scope.launch {
            val uri = withContext(Dispatchers.IO) { runCatching { insertCalendarEvent(context.contentResolver, s) }.getOrNull() }
            if (uri != null) viewModel.markCalendarAdded(s.id, uri.toString(), world)
            else calendarFailure = "No writable calendar was available, or the calendar refused the event. No event was created."
        }
    }
    val calendarPermission = arrayOf(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR)
    // A denied permission never claims an event was made.
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) addToCalendar()
        else calendarFailure = "Calendar access was not granted. No event was created."
    }
    val onAddToCalendar: () -> Unit = {
        if (calendarPermission.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            addToCalendar()
        } else {
            calendarPermissionLauncher.launch(calendarPermission)
        }
    }
    // The invite is unchanged from the button it replaces: the gig-invite deep link out
    // through the OS share sheet. Repeatable — an invite is per-person.
    val onInvite: () -> Unit = { setlist?.let { context.startActivity(gigInviteChooser(it)) } }
    val onTicketShown = remember(setlist?.id, state.tour.step) {
        { setlist?.let { viewModel.tour.ticketShown(it.id) }; Unit }
    }

    val maybeAnswers = remember { SnackbarHostState() }
    fun recordAnswer(maybe: MaybeNight, same: Boolean, adopt: Boolean = false) {
        if (adopt) { viewModel.adoptMaybe(maybe); return }
        val write = if (same) viewModel.joinNight(maybe.theirs.id, maybe.mine.id)
            else viewModel.dismissMaybe(maybe.theirs.id, maybe.mine.id)
        scope.launch {
            write.join()
            maybeAnswers.currentSnackbarData?.dismiss()
            if (maybeAnswers.showSnackbar(maybeAnswered(maybe, same), "Undo",
                    duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                if (same) viewModel.unjoinNight(maybe.theirs.id, maybe.mine.id)
                else viewModel.undismissMaybe(maybe.theirs.id, maybe.mine.id)
            }
        }
    }

    Scaffold(
        containerColor = Ground,
        snackbarHost = { SnackbarHost(maybeAnswers) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ground, titleContentColor = Faint),
                title = { Text(setlist?.year() ?: "", color = Faint, fontSize = 12.sp, letterSpacing = 1.5.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Faint)
                    }
                },
            )
        },
        bottomBar = {
            EventBottomBar(
                setlist = setlist,
                canLog = logBar,
                now = now,
                onTicketShown = onTicketShown,
                planned = planned,
                checkedIn = checkedIn,
                convertible = convertible,
                localGig = localGig,
                leaf = leaf,
                made = made,
                calendarEventUri = calendarEventUri,
                showTicket = offers.room.showTicket && (!viewModel.tour.isDemoGig(setlist?.id.orEmpty()) ||
                    state.tour.step in setOf(io.github.magnusencoded.stationtostation.features.tour.TourStep.S12,
                        io.github.magnusencoded.stationtostation.features.tour.TourStep.S13)),
                admissions = setlist?.let { state.attendanceByGig[it.id]?.admissions }.orEmpty(),
                presenceRow = presenceRow,
                onPublish = onPublish,
                onMakePlaylist = {
                    if (setlist != null) {
                        viewModel.selectSetlist(setlist)
                        onConvert()
                    }
                },
                onAdopt = { adopting = true },
                onDeleteNight = {
                    if (setlist != null) {
                        if (viewModel.photosLostByDeleting(setlist.id) > 0) deleting = true
                        else { viewModel.deleteGig(setlist.id); onBack() }
                    }
                },
                onCheckIn = { if (setlist != null) viewModel.checkIn(setlist.id) },
                onNotGoing = {
                    if (setlist != null) {
                        viewModel.removePlannedGig(setlist.id)
                        onBack()
                    }
                },
                onAddToCalendar = onAddToCalendar,
                onInvite = onInvite,
                onForgetPlaylist = { url -> if (setlist != null) viewModel.removePlaylist(setlist.id, url) },
            )
        },
    ) { padding ->
        if (setlist == null) {
            Box(Modifier.padding(padding).fillMaxSize()) {
                Text("No show selected.", color = Muted, modifier = Modifier.align(Alignment.Center))
            }
            return@Scaffold
        }
        if (adopting) {
            AdoptSetlistDialog(
                onAdopt = { link -> viewModel.adoptSetlistLink(setlist.id, link); adopting = false },
                onDismiss = { adopting = false },
            )
        }
        val lookup = state.attendanceByGig[setlist.id]?.setlistFmLookup
        if (askingMatch && setlist.isLocal() && lookup?.possibleMatchPending == true) {
            PossibleMatchDialog(
                gigId = setlist.id,
                yourVenue = setlist.venue?.name,
                fromTicket = state.attendanceByGig[setlist.id]?.admissions.orEmpty().isNotEmpty(),
                pendingIds = lookup.pendingHitIds,
                hits = { viewModel.setlistFmChipHits(setlist.id) },
                onPick = { hitId -> viewModel.acceptSetlistFmMatch(setlist.id, hitId); askingMatch = false },
                onNone = { viewModel.rejectSetlistFmMatches(setlist.id); askingMatch = false },
                onDismiss = { askingMatch = false },
            )
        }
        val answering = answeringOffer?.let { night -> state.mediaOffers[night]?.let { night to it } }
        if (answering != null && answering.second.media.isNotEmpty()) {
            val (night, offer) = answering
            MediaOfferDialog(
                offer = offer,
                sender = offerSender(offer, state.friends),
                onAccept = { viewModel.acceptMediaOffer(night, setlist.id); answeringOffer = null },
                onDecline = { viewModel.declineMediaOffer(night); answeringOffer = null },
                onDismiss = { answeringOffer = null },
            )
        }
        askingMaybe?.let { maybe ->
            val done = {
                askingMaybe = null
                shareAfterMaybe?.let { band ->
                    shareAfterMaybe = null
                    attachTo = band
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }
                Unit
            }
            // The same comparison the merge row opens (#580), so the question reads the
            // same wherever it is asked.
            MaybeCompareSheet(
                maybe = maybe,
                theirColour = laneColourOf(maybe.friend, state.friends),
                sharing = shareAfterMaybe != null,
                onSame = { adopt -> recordAnswer(maybe, true, adopt); done() },
                onApart = { recordAnswer(maybe, false); done() },
                onDismiss = done,
            )
        }
        if (deleting) {
            DeleteNightDialog(
                photos = viewModel.photosLostByDeleting(setlist.id),
                onDelete = { deleting = false; viewModel.deleteGig(setlist.id); onBack() },
                onDismiss = { deleting = false },
            )
        }
        val rows = setlist.eventRows()
        // One list, not two (#268). setlist.fm's record and my **Log** are two
        // descriptions of the same night, and printing them one under the other made
        // the reader do the alignment in their head. Woven, a song both hold is a
        // single line that says so — and neither record is changed by the other,
        // which is still the rule: this decides reading order and nothing else.
        val woven = remember(rows, log.songs) {
            weaveSetlist(rows.map { (it as? EventRow.SongItem)?.song?.name }, log.songs)
        }
        val canConvert = convertible
        val offsets = viewModel.songOffsets(recordingMedia?.id, setlist.songs().size)
        // Offsets are indexed over every song, tape included; row.number skips tape,
        // so it can't be used to look one up. -1 for the rows that aren't songs.
        val songIndexByRow = remember(rows) {
            buildList {
                var i = 0
                rows.forEach { add(if (it is EventRow.SongItem) i++ else -1) }
            }
        }
        // Pull down to re-fetch: you log the night here, go type the songs in on
        // setlist.fm, and come back to a screen that still says there's no setlist.
        PullToRefreshBox(
            isRefreshing = state.setlistsLoading ||
                (catalogueArtist != null && state.catalogueFetching == catalogueArtist),
            onRefresh = onPullToRefresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    // No imePadding here, deliberately: the Scaffold already insets for
                    // the keyboard, and a second one shrinks the viewport past where
                    // the list draws — the note field kept its layout and lost its
                    // bottom border, its "done" and the vault's write-line under it.
                    // Arranging is the Room's mode, so the whole Room dismisses it —
                    // every tap that is not an [x] on a thumbnail, not merely a tap on
                    // the strip that opened it (#162). Registered before the swipe so
                    // it never eats a horizontal gesture.
                    .pointerInput(arranging) {
                        if (arranging) detectTapGestures(onTap = { arranging = false })
                    }
                    // Swipe-left is THE action gesture; swipe-right is always back, the
                    // way out of any pushed screen. What left does depends on the gig:
                    // a plan-ahead gig adds it to the calendar, then invites once added
                    // (#55); a past night converts to a playlist, or opens on setlist.fm
                    // when there's nothing to convert. PAST/DAY_OF planned gigs fall
                    // through to that same open-on-setlist.fm, matching their crumb.
                    // Registered even with nothing to convert, or a show with no logged
                    // setlist would be the one screen you can't swipe out of.
                    .pointerInput(setlist.id, canConvert, planAhead, added, logBar, leaf) {
                        val threshold = 110.dp.toPx()
                        var dragX = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dragX = 0f },
                            onDragEnd = {
                                when {
                                    dragX >= threshold -> onBack()
                                    dragX > -threshold -> {}
                                    // A night I logged: the swipe is the labelled
                                    // publish, matching the "‹ copy the set and open
                                    // setlist.fm" hint under it — this file's rule is
                                    // that the swipe never contradicts the hint. Not
                                    // gated on the clock: a gesture that silently does
                                    // nothing for half the night is a dead gesture, and
                                    // this one publishes nothing by itself anyway — it
                                    // fills the clipboard and opens their form.
                                    logBar -> onPublish()
                                    planAhead && !added -> onAddToCalendar()
                                    planAhead && added -> onInvite()
                                    canConvert -> {
                                        viewModel.selectSetlist(setlist)
                                        onConvert()
                                    }
                                    else -> setlist.url?.let {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)))
                                    }
                                }
                            },
                            onHorizontalDrag = { _, delta -> dragX += delta },
                        )
                    },
            ) {
                item {
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp)) {
                        Text(setlist.artist?.name ?: "Unknown artist", fontFamily = Serif, fontSize = 27.sp, color = Ink, modifier = Modifier.asHeading())
                        Spacer(Modifier.height(5.dp))
                        Text(
                            listOfNotNull(setlist.venueLine(), setlist.readableDate()).joinToString(" · "),
                            color = Muted,
                            fontSize = 13.sp,
                        )
                        val demoMaps = viewModel.tour.mapsUri(setlist)
                        val mapsQuery = venueMapsQuery(setlist.venue?.name, setlist.venue?.city?.name)
                        if (demoMaps != null || mapsQuery != null) {
                            Text(
                                "Open venue in Maps ↗",
                                color = Slate,
                                fontSize = 13.sp,
                                modifier = Modifier.spokenAs("Open venue in Maps").clickable {
                                    if (openVenueInMaps(context, mapsQuery.orEmpty(), demoMaps)) {
                                        viewModel.tour.mapsOpened(setlist.id)
                                    }
                                }.padding(vertical = 6.dp),
                            )
                        }
                        Spacer(Modifier.height(11.dp))
                        EventChipRow(
                            setlist = setlist,
                            planned = planned,
                            checkedIn = checkedIn,
                            witnessed = setlist.id in state.witnessedGigs,
                            made = made,
                            now = now,
                        )
                        // A lookup found something but was not sure (#531): the question
                        // waits here, on the night, until it is answered. Dismissing the
                        // dialog leaves it waiting; the automatic checks pause meanwhile.
                        if (setlist.isLocal() &&
                            state.attendanceByGig[setlist.id]?.setlistFmLookup?.possibleMatchPending == true
                        ) {
                            Spacer(Modifier.height(8.dp))
                            EventTag(
                                POSSIBLE_MATCH_TITLE,
                                color = Amber,
                                onClick = { askingMatch = true },
                            )
                        }
                        // A **Contact** sent media for a Night of theirs on this date that
                        // I have not joined (#405). Offered, never filed: it waits here, on
                        // the Night it might be, until I say yes or no.
                        if (!planned) {
                            state.mediaOffers.waitingOn(setlist.eventDate).forEach { (night, offer) ->
                                val line = offerLine(offer, offerSender(offer, state.friends))
                                Spacer(Modifier.height(8.dp))
                                EventTag(
                                    line,
                                    color = Slate,
                                    onClick = { answeringOffer = night },
                                    label = "$line. Opens accept or decline.",
                                )
                            }
                        }
                        // A **Contact** was out this date under a Night nothing links to
                        // this one (#405): a *maybe*. It waits here costing nothing, and is
                        // asked outright only when I go to share media from this Night.
                        maybes.forEach { maybe ->
                            val line = maybeTagLine(maybe)
                            Spacer(Modifier.height(8.dp))
                            EventTag(
                                line,
                                color = Crossed,
                                onClick = { askingMaybe = maybe },
                                label = "$line. ${maybeTheirNight(maybe)}. Opens same night or not.",
                            )
                        }
                        // Nothing can be pinned to a night nobody has been to yet — the
                        // slot comes back once the gig is checked into or no longer planned.
                        if (showsMediaBlock(planned, checkedIn)) {
                            EventMedia(
                                gigMedia = gigMedia,
                                withheld = withheld,
                                gigPhotos = gigPhotos,
                                contactLight = state.contactLight,
                                showWithheld = state.showWithheld,
                                arranging = arranging && editable,
                                editable = editable,
                                suggestions = state.gigPhotoSuggestions,
                                suggestionsLoading = state.gigPhotoSuggestionsLoading,
                                suggestionsSearched = state.gigPhotoSuggestionsSearched,
                                suggestionsPermissionGranted = state.gigPhotoSuggestionsPermissionGranted,
                                loadPreview = viewModel::photoPreview,
                                senderName = { key -> state.friends.nameOf(key) },
                                onToggleWithheld = { viewModel.setShowWithheld(!state.showWithheld) },
                                onArrange = { arranging = true },
                                onDoneArranging = { arranging = false },
                                onAdd = { band ->
                                    // Going to share from a *maybe* Night asks the one
                                    // question, once, now, and then carries on into the
                                    // picker whatever the answer.
                                    val ask = maybes.firstOrNull()
                                    if (band == Band.SHARED && ask != null) {
                                        shareAfterMaybe = band
                                        askingMaybe = ask
                                    } else {
                                        attachTo = band
                                        photoPicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                                        )
                                    }
                                },
                                onOpen = { uri -> viewerUri = uri },
                                onRemove = { item -> viewModel.removeGigPhoto(setlist.id, Uri.parse(item.ref)) },
                                onMove = { id, band, index -> moveAndAsk(setlist.id, id, band, index) },
                                onRequestSuggestionPermission = {
                                    gigSuggestPermissionLauncher.launch(PhotoRepository.requiredPermissions())
                                },
                                onAddSuggestion = { uri -> viewModel.addGigPhotos(setlist.id, listOf(uri), Band.VAULT) },
                            )
                        }
                    }
                }
                if (!mineNight) item {
                    val going = nightKind(setlist.localDate(), LocalDate.now()) == NightKind.GOING_TO
                    TextButton(
                        onClick = { viewModel.joinGig(setlist) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    ) {
                        Text(if (going) "I am going too" else "I was there too", color = Amber)
                    }
                }
                setlistRows(
                    setlist = setlist,
                    rows = rows,
                    woven = woven,
                    log = log,
                    publicGossip = state.publicGossip,
                    friends = state.friends,
                    gigAliases = state.gossipGigAliases[setlist.id] ?: setOf(setlist.id),
                    planned = planned,
                    canLog = canLog,
                    offsets = offsets,
                    songIndexByRow = songIndexByRow,
                    hasRecording = recording != null,
                    correctingLog = correctingLog,
                    catalogue = catalogue,
                    catalogueLoading = catalogueLoading,
                    onOpenRecording = { at -> viewerStartMs = at; viewerUri = recording },
                    onCorrectingChange = { correctingLog = it },
                    onFetchCatalogue = { catalogueArtist?.let(viewModel::fetchCatalogue) },
                    onBlock = viewModel::blockGossip,
                    onRemoveFromLog = { viewModel.removeFromLog(setlist.id, it) },
                    onCorrectLogEntry = { index, title -> viewModel.correctLogEntry(setlist.id, index, title) },
                    onRestoreLogEntry = { viewModel.restoreLogEntry(setlist.id, it) },
                )
                // My own Log, and it is never taken away. A partial capture you can no
                // longer correct from inside the app is the exact trap this feature is
                // built to avoid, so this renders on a night's page forever after.
                //
                // **Under the set, not above it.** The entries themselves are on the
                // spine now (#268), so what is left here is the way in — what to add
                // and whether the set is complete — and a way in belongs below the
                // thing it adds to. It also puts the field next to the end of the
                // list, which is where a song lands when you tap it in.
                if (canLog) {
                    item {
                        Spacer(Modifier.height(6.dp))
                        LogEditor(
                            candidates = catalogue,
                            // Naming which artist a wrong match came from was the
                            // Bill's own act-search feature (#93), which went with it —
                            // this pool is the night's own MusicBrainz catalogue, not a
                            // namesake match to distrust.
                            poolArtist = "",
                            log = log,
                            // Only once I have written something down. An untouched log
                            // beside an imported setlist is not a divergence, it is a
                            // log I have not started — and "setlist.fm has 18, yours has
                            // 0" the instant you check in is noise, not information.
                            published = setlist.performed().size
                                .takeIf { setlist.url != null && log.songs.isNotEmpty() },
                            onAdd = { viewModel.addToLog(setlist.id, it) },
                            onClosed = { viewModel.setLogClosed(setlist.id, it) },
                        )
                        viewModel.tour.demoLogRecord(setlist.id)?.let { record ->
                            record.gapSong?.let { song ->
                                Text(song, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
                                Text(viewModel.tour.character.notes.gapFill,
                                    color = io.github.magnusencoded.stationtostation.features.tour.tourAccent(state),
                                    fontSize = 11.sp, modifier = Modifier.padding(horizontal = 20.dp))
                            }
                            if (record.filled) Text(viewModel.tour.character.notes.setlistFill,
                                color = Slate, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 20.dp))
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
                // Last of the night's own material, and after the set on purpose: the
                // sentence is written once the songs have been read back, which is
                // what "analysis happens after the show" means as a layout. Still
                // above the **Alcove**, which is the room's fixture rather than the
                // night's record.
                item {
                    Spacer(Modifier.height(14.dp))
                    GigNotes(
                        media = gigMedia,
                        preamble = gigPreamble,
                        senderName = { key -> state.friends.nameOf(key) },
                        contactLight = state.contactLight,
                        editable = editable,
                        onWrite = { band, text -> viewModel.setGigNote(setlist.id, band, text) },
                        onVerdict = { id, v -> viewModel.setGigVerdict(setlist.id, id, v) },
                        onMove = { id, band, index -> moveAndAsk(setlist.id, id, band, index) },
                    )
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }

    viewerUri?.let { uri ->
        // Only the night's own recording carries the setlist — a short clip of one
        // song is still just a keepsake, and a song list under it would be noise.
        val songs = if (setlist != null && uri == recording) setlist.songs() else emptyList()
        MediaViewerDialog(
            uri = uri,
            isVideo = viewModel.isVideo(uri),
            loadPhoto = viewModel::fullPhoto,
            onDismiss = { viewerUri = null; viewerStartMs = NOT_STAMPED },
            songs = songs,
            // The stamps belong to *this* recording, not to the night — a night with
            // two videos has two answers, and before #97 the second had nowhere to go.
            offsets = viewModel.songOffsets(recordingMedia?.id, songs.size),
            startAtMs = viewerStartMs,
            onStamp = { index, atMs ->
                recordingMedia?.let { viewModel.stampSong(it.id, index, atMs, songs.size) }
            },
        )
    }
}

@Composable
private fun EventBottomBar(
    setlist: FmSetlist?,
    canLog: Boolean,
    planned: Boolean,
    checkedIn: Boolean,
    convertible: Boolean,
    localGig: Boolean,
    leaf: GigLeaf,
    made: List<StoredPlaylist>,
    calendarEventUri: String?,
    showTicket: Boolean,
    admissions: List<StoredAdmission>,
    presenceRow: @Composable () -> Unit,
    onPublish: () -> Unit,
    onMakePlaylist: () -> Unit,
    onAdopt: () -> Unit,
    onDeleteNight: () -> Unit,
    onCheckIn: () -> Unit,
    onNotGoing: () -> Unit,
    onAddToCalendar: () -> Unit,
    onInvite: () -> Unit,
    onForgetPlaylist: (String) -> Unit,
    now: LocalDateTime,
    onTicketShown: () -> Unit,
) {
    val context = LocalContext.current
    if (canLog && setlist != null) {
        // A night I was at that this app is the record of. Capture is the leaf,
        // always — the chip in the header is the permanent door to setlist.fm,
        // so nothing here has to become a handoff when the night ends. The clock
        // only changes the wording: prompting while you are there, quiet
        // correction afterwards.
        Column(
            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Above the **Log** prompt, because standing somewhere comes before
            // writing anything down — and because this is the bar a night that was
            // checked into actually gets.
            if (checkedIn) {
                presenceRow()
            } else if (planned && canCheckInManually(setlist, now)) {
                // A hand-added night lands here while still planned, and this was the
                // only branch with no way to say "I'm here" — so it stayed planned, and
                // a planned night is never offered to a Contact on a Reconcile.
                if (showTicket) TicketAtTheDoor(admissions, onShown = onTicketShown)
                Text(
                    "I'm here — check in",
                    color = Amber,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable(onClick = onCheckIn)
                        .padding(vertical = 6.dp),
                )
            }
            Text(
                when (leaf) {
                    // The entries are the set; the way in is under it.
                    GigLeaf.CAPTURE -> "noting the set — add what they play below"
                    else -> "your log · add anything you remember below"
                },
                color = Faint,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Text(
                "‹ copy the set and open setlist.fm",
                color = Amber,
                fontSize = 13.sp,
                modifier = Modifier.spokenAs("Copy the set and open setlist.fm").clickable(onClick = onPublish).padding(vertical = 6.dp),
            )
            // A set I said was complete is a set, so it converts. Offered here
            // rather than only in the branch below, which a checked-in night
            // never reaches.
            if (convertible) {
                Text(
                    "make a playlist of this set",
                    color = Slate,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable(onClick = onMakePlaylist)
                        .padding(vertical = 6.dp),
                )
            }
            if (localGig) {
                Text(
                    "it's on setlist.fm now — paste the link",
                    color = Slate,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable(onClick = onAdopt)
                        .padding(vertical = 6.dp),
                )
                // Reachable from the night itself, on purpose: deletion must
                // not depend on anything else still existing.
                Text(
                    "delete this night",
                    color = Danger,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable(onClick = onDeleteNight)
                        .padding(vertical = 6.dp),
                )
            }
        }
    } else if (planned && setlist != null) {
        // What a planned gig lets you do follows the clock: plan it while
        // it's still ahead, check in on the night, nudge setlist.fm once it's
        // over. An unparseable date can't be placed on that line, so it falls
        // to the plan-ahead actions rather than losing them.
        val timeState = setlist.localDate()?.let { gigTimeState(now, it) }
        Column(
            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The ticket's own barcodes, every Admission in its own
            // symbology — see TicketAtTheDoor. Gone the moment checked in (see
            // `checkedIn` below) and never drawn at all when there is no ticket
            // to show. Worth showing on this gig's own page as soon as a ticket
            // is attached, not held back until the day-of check-in window the
            // way the offer to check in is.
            // The manual check-in, and the only one there is when location was
            // refused or the venue couldn't be geocoded. Same night window as
            // the ambient offer; no location involved at all.
            if (canCheckInManually(setlist, now)) {
                if (checkedIn) {
                    presenceRow()
                } else {
                    if (showTicket) TicketAtTheDoor(admissions, onShown = onTicketShown)
                    Text(
                        "I'm here — check in",
                        color = Amber,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clickable(onClick = onCheckIn)
                            .padding(vertical = 6.dp),
                    )
                }
            } else if (!checkedIn) {
                // Outside the check-in window: no "I'm here" offer yet, but
                // still worth showing that the ticket's barcode was captured.
                if (showTicket) TicketAtTheDoor(admissions, onShown = onTicketShown)
            }
            when (timeState) {
                // Over: adding a setlist is a past action, so the setlist.fm
                // crumb belongs here and only here.
                GigTimeState.PAST -> setlist.url?.let { url ->
                    Text(
                        "‹ swipe to open this show on setlist.fm",
                        color = Slate,
                        fontSize = 13.sp,
                        modifier = Modifier.spokenAs("Open this show on setlist.fm")
                            .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            .padding(vertical = 6.dp),
                    )
                }
                // The night itself: maps and check-in, handled above. No
                // crumb, no plan-ahead buttons.
                GigTimeState.DAY_OF -> {}
                // Still ahead (or an undated gig): the swipe is the action, in two
                // stages. The hint names what the next swipe does — the same
                // grammar as the Spotify convert, where the made-playlist link
                // persists and the hint moves on to "make another".
                else -> {
                    if (calendarEventUri != null) {
                        // The created event, as a persisted tappable link — the
                        // mirror of a made-playlist row. Opens the event with
                        // ACTION_VIEW on the URI the insert handed back.
                        Row(
                            Modifier
                                .clickable {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(calendarEventUri)))
                                }
                                .padding(vertical = 6.dp, horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(Slate))
                            Spacer(Modifier.width(8.dp))
                            Text("Open the calendar event ↗", color = Slate, fontSize = 14.sp)
                        }
                        Spacer(Modifier.height(2.dp))
                        // Graduated: the swipe now invites, and keeps inviting.
                        Text(
                            "‹ swipe to invite a friend",
                            color = Slate,
                            fontSize = 13.sp,
                            modifier = Modifier.spokenAs("Invite a friend").clickable(onClick = onInvite).padding(vertical = 6.dp),
                        )
                    } else {
                        Text(
                            "‹ swipe to add to calendar",
                            color = Slate,
                            fontSize = 13.sp,
                            modifier = Modifier.spokenAs("Add to calendar").clickable(onClick = onAddToCalendar).padding(vertical = 6.dp),
                        )
                    }
                }
            }
            Text(
                "I'm not going",
                color = Danger,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable(onClick = onNotGoing)
                    .padding(vertical = 6.dp),
            )
        }
    } else if (setlist != null && setlist.performed().isEmpty() && setlist.url != null) {
        // The Historian's crumb: nothing to convert here, but a nudge toward
        // fixing the gap at the source is better than nothing.
        Column(
            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "‹ swipe to open this setlist on setlist.fm",
                color = Amber,
                fontSize = 13.sp,
                modifier = Modifier.spokenAs("Open this setlist on setlist.fm")
                    .clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(setlist.url)))
                    }
                    .padding(vertical = 6.dp),
            )
        }
    } else if (setlist != null && setlist.performed().isNotEmpty()) {
        // A quiet, tappable hint rather than a big CTA — the same action the
        // swipe fires, kept visible so it's discoverable and reachable without
        // the gesture.
        val convert = onMakePlaylist
        Column(
            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Once a night has a playlist, opening it is the primary offer and
            // making another is the aside — converting twice is the rare case.
            if (made.isNotEmpty()) {
                made.forEach { playlist ->
                    Row(
                        Modifier
                            // Long-press drops the link — for when the playlist
                            // itself was deleted on Spotify and this pointer is
                            // just dead weight left behind.
                            .combinedClickable(
                                onClick = {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(playlist.url)),
                                    )
                                },
                                onLongClickLabel = "Forget this playlist link",
                                onLongClick = { onForgetPlaylist(playlist.url) },
                            )
                            .padding(vertical = 6.dp, horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(SpotifyGreen))
                        Spacer(Modifier.width(8.dp))
                        // One playlist needs no naming; several have to be told
                        // apart, because the one you sent is a particular one.
                        Text(
                            if (made.size == 1) "Open the playlist ↗"
                            else "${playlist.name.ifBlank { "Playlist" }} ↗",
                            color = SpotifyGreen,
                            fontSize = 14.sp,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "‹ swipe to make another",
                    color = Faint,
                    fontSize = 12.sp,
                    modifier = Modifier.spokenAs("Make another playlist").clickable(onClick = convert).padding(vertical = 4.dp),
                )
            } else {
                Text(
                    "‹ swipe to open as a Spotify playlist",
                    color = Amber,
                    fontSize = 13.sp,
                    modifier = Modifier.spokenAs("Open as a Spotify playlist").clickable(onClick = convert).padding(vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun EventChipRow(
    setlist: FmSetlist,
    planned: Boolean,
    checkedIn: Boolean,
    witnessed: Boolean,
    made: List<StoredPlaylist>,
    now: LocalDateTime,
) {
    val context = LocalContext.current
    Row {
        // Once the night has passed the record has the last word:
        // "no setlist yet" is a fact about what is stored, so a Gig
        // holding fifteen songs cannot print it and one holding none
        // keeps printing it.
        EventTag(
            gigStatus(planned, setlist.localDate(), setlist.performed().size, now),
            color = if (planned) Slate else Muted,
        )
        setlist.tour?.name?.let {
            Spacer(Modifier.width(6.dp))
            EventTag(it)
        }
        // The rule this row follows: a chip that names an
        // **external record** opens it; a chip stating a local fact
        // (song count, tour, "checked in") does not. That is what
        // makes the setlist.fm chip below learnable rather than a
        // special case.
        if (made.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            EventTag(
                if (made.size == 1) "playlist ↗" else "${made.size} playlists ↗",
                color = SpotifyGreen,
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(made.first().url)),
                    )
                },
            )
        }
        // How the app came to believe I was here. A check-in is
        // stronger evidence than setlist.fm's retroactive flag. A badge
        // marks the exceptional, so only "checked in" earns one; the default
        // (nearly every attended gig) would say nothing.
        // Self-assertion and evidence are two different claims and read
        // as two chips. A witness is another phone that
        // was checked in to this same night signing for mine, so it is
        // strictly more than "checked in" and says so on the same chip
        // rather than beside it — one claim, at its actual strength.
        // `state.witnessedGigs` is the same expression the Walk reads
        // (GigFlyover), so the two surfaces cannot disagree about a night.
        if (checkedIn) {
            Spacer(Modifier.width(6.dp))
            EventTag(
                if (witnessed) "checked in · witnessed"
                else "checked in",
                color = Amber,
            )
        }
        // The setlist.fm id, rendered. Not a button bolted on beside
        // the data — it *is* `StoredGig.setlistId`, and its absence
        // is the stub condition showing itself. That id is the
        // correspondence key between people, so this chip is the
        // joint where my record meets everyone else's.
        Spacer(Modifier.width(6.dp))
        if (setlist.url != null) {
            EventTag(
                // The glyph is the tell. No other chip in this row
                // answers a tap, so one that does cannot rely on anyone
                // trying it.
                "${setlist.id} ↗",
                color = Slate,
                // The canonical setlist page, never a constructed
                // edit url: this one is always valid, needs no login,
                // and editing is one click away on their own site.
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(setlist.url)),
                    )
                },
                label = "Link to this setlist on setlist.fm",
            )
        } else {
            // **Local**: a true property of the record — it exists on
            // this phone only, and cannot be a **Crossing** until it
            // has an id. Not "self-reported", which describes how
            // nearly every claim here was made and so marks nothing.
            //
            // Deliberately inert. `/edit` shows a signed-out user a
            // sign-in wall, and a dead-end link
            // is worse than no crumb — so the absence is stated and
            // the labelled action below is the door.
            EventTag("local", color = Faint)
        }
    }
}

@Composable
private fun EventMedia(
    gigMedia: List<StoredMedia>,
    withheld: List<StoredMedia>,
    gigPhotos: List<Uri>,
    contactLight: Boolean,
    showWithheld: Boolean,
    arranging: Boolean,
    editable: Boolean,
    suggestions: List<CoverCandidate>,
    suggestionsLoading: Boolean,
    suggestionsSearched: Boolean,
    suggestionsPermissionGranted: Boolean,
    loadPreview: suspend (Uri) -> MediaThumb,
    senderName: (String) -> String?,
    onToggleWithheld: () -> Unit,
    onArrange: () -> Unit,
    onDoneArranging: () -> Unit,
    onAdd: (Band) -> Unit,
    onOpen: (Uri) -> Unit,
    onRemove: (StoredMedia) -> Unit,
    onMove: (String, Band, Int) -> Unit,
    onRequestSuggestionPermission: () -> Unit,
    onAddSuggestion: (Uri) -> Unit,
) {
    // The review, where the sharing decision is actually made:
    // one night at a time. At the timeline the lit and
    // unlit versions look almost identical; the difference is
    // visible here, which is the right place for it.
    if (contactLight) {
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                gigMedia.isEmpty() && withheld.isEmpty() ->
                    "Nothing to see on this night. They see that you were here."
                gigMedia.isEmpty() ->
                    "They see none of the ${withheld.size} here. They see that you were here."
                else ->
                    "They see ${gigMedia.size} of ${gigMedia.size + withheld.size} here."
            },
            color = Muted,
            fontSize = 12.sp,
        )
        if (withheld.isNotEmpty()) {
            Text(
                if (showWithheld) "hide what you are keeping back"
                else "show the ${withheld.size} you are keeping back",
                color = Slate,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable(onClick = onToggleWithheld)
                    .padding(vertical = 8.dp),
            )
        }
        // Placeholders, never content: the question this answers
        // is "how much am I keeping back", and re-rendering the
        // photographs would answer a different one.
        if (showWithheld) {
            Row(Modifier.padding(bottom = 6.dp)) {
                withheld.forEach { _ ->
                    Box(
                        Modifier
                            .padding(end = 6.dp)
                            .size(44.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(UnlitField)
                            .border(1.dp, LineCol, RoundedCornerShape(6.dp)),
                    )
                }
            }
        }
        // Stopping is a drag down into the vault, one photograph
        // at a time, so there is no button here — and
        // there must not be one: nothing retrieves what already
        // left, and no control may look as though it does.
    }
    Spacer(Modifier.height(12.dp))
    GigMediaBands(
        media = gigMedia,
        loadPreview = loadPreview,
        // Remove and the drag both hang off arrange mode, so
        // withholding it is the whole of gating them.
        arranging = arranging,
        // The light shows what they see, so the vault band and
        // the handle are absent under it rather than drawn over
        // a filtered list they could only misreport.
        contactLight = contactLight,
        editable = editable,
        // A sender is a public key and a Contact's name
        // lives on the friends list under a setlist.fm handle.
        // Nothing joins the two yet, so the promise degrades to
        // "someone else" rather than inventing a name.
        senderName = senderName,
        onArrange = onArrange,
        onDoneArranging = onDoneArranging,
        onAdd = onAdd,
        // Opens in the in-app viewer below rather than handing the uri to
        // whatever app the phone picks: an external app can fail to read
        // it (permission scoped to us, or the phone's own quirks) and
        // leave the user staring at a viewer with nothing in it.
        onOpen = onOpen,
        onRemove = onRemove,
        onMove = onMove,
    )
    Spacer(Modifier.height(8.dp))
    GigPhotoSuggestions(
        candidates = suggestions,
        loading = suggestionsLoading,
        searched = suggestionsSearched,
        permissionGranted = suggestionsPermissionGranted,
        already = gigPhotos,
        onRequestPermission = onRequestSuggestionPermission,
        // A suggestion has no gesture behind it, so it takes the
        // safe band. Moving it up is one drag.
        onAdd = onAddSuggestion,
    )
}

private fun LazyListScope.setlistRows(
    setlist: FmSetlist,
    rows: List<EventRow>,
    woven: List<WovenSong>,
    log: StoredLog,
    publicGossip: io.github.magnusencoded.stationtostation.data.gossip.PublicGossipState,
    friends: List<Friend>,
    gigAliases: Set<String>,
    planned: Boolean,
    canLog: Boolean,
    offsets: List<Long>,
    songIndexByRow: List<Int>,
    hasRecording: Boolean,
    correctingLog: Int?,
    catalogue: List<String>,
    catalogueLoading: Boolean,
    onOpenRecording: (Long) -> Unit,
    onCorrectingChange: (Int?) -> Unit,
    onFetchCatalogue: () -> Unit,
    onBlock: (String) -> Unit,
    onRemoveFromLog: (Int) -> Unit,
    onCorrectLogEntry: (Int, String) -> Unit,
    onRestoreLogEntry: (Int) -> Unit,
) {
    val gossipFacts = publicGossip.project(setOf(setlist.id))
        .filter { it.author !in publicGossip.localAuthors }
    val contactNames = io.github.magnusencoded.stationtostation.data.gossip.contactNamesOf(friends)
    // Who was here, and it stays. Asked under every id this night has been
    // known by — adopting a setlist.fm id must not split the record or count the
    // same device under both halves of it.
    val seenWith = publicGossip.seenWith(gigAliases, contactNames)
    if (!seenWith.isEmpty) item {
        Text(seenWithLine(seenWith), color = Slate,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    }
    val gossipRows = io.github.magnusencoded.stationtostation.data.gossip.weaveGossip(
        woven.map { line -> line.logged?.let { log.songs[it] }
            ?: (line.published?.let { rows[it] } as? EventRow.SongItem)?.song?.name }, gossipFacts)
    if (gossipRows.isEmpty() && !canLog) {
        item {
            Text(
                // A night that hasn't happened has no setlist missing from
                // it — nothing has been played yet, and saying "not logged"
                // would blame setlist.fm for a gap that isn't one.
                if (planned) "This show hasn't happened yet."
                else "This show has no setlist on setlist.fm yet.",
                color = Muted,
                fontSize = 14.sp,
                modifier = Modifier.padding(20.dp),
            )
        }
    }
    itemsIndexed(gossipRows) { _, gossipRow ->
        gossipRow.facts.forEach { fact ->
            val name = publicGossip.attributedName(fact.author, contactNames) ?: "Nearby listener"
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$name · gossip, experimental", color = Slate, fontSize = 11.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { onBlock(fact.author) }) { Text("Block") }
            }
        }
        if (gossipRow.base == null) {
            Text(gossipRow.text?.ifBlank { "a song they couldn't name" }.orEmpty(), color = Ink,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        } else {
        val line = woven[gossipRow.base]

        // Mine is an index into the **Log**, and the × and the correction
        // panel act on it there — the published row beside it is never
        // touched by either.
        val logAt = line.logged?.takeIf { canLog }
        val remembered = line.logged?.let { log.rememberedAt(it) }
        val remove = logAt?.let { j ->
            { onCorrectingChange(null); onRemoveFromLog(j) }
        }
        when (val row = line.published?.let { rows[it] }) {
            is EventRow.Encore -> EncoreLabel()
            is EventRow.SongItem -> {
                val at = offsets.getOrElse(songIndexByRow[line.published!!]) { NOT_STAMPED }
                SongRow(
                    number = row.number,
                    song = row.song,
                    offsetMs = at,
                    mine = line.both,
                    remembered = remembered,
                    onRemoveLog = remove,
                    // Only a stamped song knows where it is in the recording;
                    // the rest are inert until someone marks them.
                    onClick = if (at > NOT_STAMPED && hasRecording) {
                        { onOpenRecording(at) }
                    } else null,
                )
            }
            // Only mine. A **Gap** offers no correction: "one I couldn't
            // name" is an acknowledged fact, not an invitation to guess.
            null -> {
                val j = line.logged!!
                val title = log.songs[j]
                LoggedRow(
                    title = title,
                    // Only when nothing was published: then my Log is the
                    // record of this night and its order is the set's.
                    number = (j + 1).takeIf { rows.isEmpty() },
                    remembered = remembered,
                    onCorrect = if (canLog && title.isNotBlank()) {
                        { onCorrectingChange(if (correctingLog == j) null else j) }
                    } else null,
                    onRemove = remove,
                )
                if (correctingLog == j) {
                    LaunchedEffect(j) { onFetchCatalogue() }
                    val written = log.rememberedAt(j) ?: title
                    CorrectEntry(
                        written = written,
                        // Both sources, played first and recorded after,
                        // ranked as one list. A song they played tonight
                        // and have recorded appears once.
                        candidates = rankTitles(
                            written,
                            catalogue.distinctBy { it.lowercase() },
                        ),
                        canRestore = log.rememberedAt(j) != null,
                        loading = catalogueLoading,
                        onPick = {
                            onCorrectingChange(null)
                            onCorrectLogEntry(j, it)
                        },
                        onRestore = {
                            onCorrectingChange(null)
                            onRestoreLogEntry(j)
                        },
                    )
                }
            }
        }
    }
    }
}
