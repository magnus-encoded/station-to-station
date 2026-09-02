package io.github.magnusencoded.stationtostation.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.magnusencoded.stationtostation.AppViewModel
import io.github.magnusencoded.stationtostation.data.Position
import io.github.magnusencoded.stationtostation.data.ProgrammeAct
import io.github.magnusencoded.stationtostation.data.ProgrammeDiff
import io.github.magnusencoded.stationtostation.data.StoredProgramme
import io.github.magnusencoded.stationtostation.data.actKey
import io.github.magnusencoded.stationtostation.data.appliedActs
import io.github.magnusencoded.stationtostation.data.Departures
import io.github.magnusencoded.stationtostation.data.commitLabel
import io.github.magnusencoded.stationtostation.data.commitSub
import io.github.magnusencoded.stationtostation.data.departuresOf
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.clashfinder.ClashfinderBlocked
import io.github.magnusencoded.stationtostation.data.clashfinder.ClashfinderFestival
import io.github.magnusencoded.stationtostation.data.clashfinder.INDEX_PATH
import io.github.magnusencoded.stationtostation.data.clashfinder.isClashfinderId
import io.github.magnusencoded.stationtostation.data.clashfinder.parseClashfinderEvent
import io.github.magnusencoded.stationtostation.data.clashfinder.parseClashfinderIndex
import io.github.magnusencoded.stationtostation.data.clashfinder.decodeFestivals
import io.github.magnusencoded.stationtostation.data.clashfinder.encodeFestivals
import io.github.magnusencoded.stationtostation.data.clashfinder.rankFestivals
import io.github.magnusencoded.stationtostation.data.encodeProgramme
import io.github.magnusencoded.stationtostation.data.parseProgramme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val Ground = Color(0xFF0E0B14)
private val Raised = Color(0xFF17121F)
private val Ink = Color(0xFFEDE9F2)
private val Muted = Color(0xFF8B8299)
private val Faint = Color(0xFF5A5368)
private val Amber = Color(0xFFE7B24C)
private val Slate = Color(0xFF6D7E9B)
private val Serif = FontFamily.Serif

/**
 * Where the fetched timetable lives on this device. One file, overwritten.
 *
 * On disk rather than in DataStore: it is a document, not a setting, and the next thing
 * that happens to it is being handed to another phone whole. One festival at a time,
 * because that is how many festivals a person is at.
 */
private const val PROGRAMME_CACHE = "programme.json"

/**
 * The reduced festival index. Cached because the full one is 4 MB and the picker opens
 * far more often than the catalogue changes.
 */
private const val INDEX_CACHE = "clashfinder-index.json"

/** How many candidates the list shows before it is scrolled, and how many it grows by. */
private const val WINDOW = 40

private fun cachedProgramme(context: Context): StoredProgramme =
    File(context.filesDir, PROGRAMME_CACHE)
        .takeIf { it.exists() }
        ?.let { parseProgramme(it.readText()) }
        ?: StoredProgramme()

private fun cachedFestivals(context: Context): List<ClashfinderFestival> =
    File(context.filesDir, INDEX_CACHE)
        .takeIf { it.exists() }
        ?.let { decodeFestivals(it.readText()) }
        .orEmpty()

/**
 * The planning surface: which festival, and then **Departures** — its days as a line of
 * choices, and one button that puts what you chose on your own **Line**.
 *
 * Read-only about the festival and writable only about *me*: nothing here edits the
 * programme, and the only thing it can produce is a **Gig** somebody picked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgrammeScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    now: LocalDateTime = LocalDateTime.now(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()

    var programme by remember { mutableStateOf(StoredProgramme()) }
    var festivals by remember { mutableStateOf(emptyList<ClashfinderFestival>()) }
    // The picker is where you start with nothing in hand, and where "change festival"
    // sends you back to.
    var picking by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Set when the host refuses this app outright, which is what puts the hand-import
    // fallback on screen.
    var blocked by remember { mutableStateOf(false) }

    // Both caches are documents on disk, and the index is ten thousand records of it.
    // Reading them in composition put a file read and that decode on the main thread
    // every time the screen was entered.
    LaunchedEffect(Unit) {
        val held = withContext(Dispatchers.IO) { cachedProgramme(context) to cachedFestivals(context) }
        programme = held.first
        festivals = held.second
        picking = held.first.acts.isEmpty()
    }

    // Everything here — the request, the 4 MB parse behind it, and the write of what it
    // yielded — happens off the main thread. The parse is the big one: it is the whole
    // reason the index is reduced before it is cached at all.
    fun loadIndex() {
        loading = true
        error = null
        blocked = false
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    viewModel.clashfinder.index()
                        .also { File(context.filesDir, INDEX_CACHE).writeText(encodeFestivals(it)) }
                }
            }
                .onSuccess { festivals = it }
                .onFailure {
                    error = it.message ?: "Could not reach clashfinder."
                    blocked = it is ClashfinderBlocked
                }
            loading = false
        }
    }

    fun loadProgramme(id: String) {
        loading = true
        error = null
        blocked = false
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    viewModel.clashfinder.event(id)
                        .also { File(context.filesDir, PROGRAMME_CACHE).writeText(encodeProgramme(it)) }
                }
            }
                // A failed fetch leaves the timetable already in hand untouched, and
                // writes nothing. A bad connection in a field must not cost somebody
                // their plans.
                .onSuccess {
                    programme = it
                    picking = false
                }
                .onFailure {
                    error = it.message ?: "Could not reach clashfinder."
                    blocked = it is ClashfinderBlocked
                }
            loading = false
        }
    }

    // The fallback, in full: a document the user's own browser fetched, read straight
    // off disk. Which kind it is comes from the document and not from the user — the
    // index is a map of entries and a timetable is not, so one affordance covers both.
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        loading = true
        error = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val text = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    val index = parseClashfinderIndex(text)
                    if (index.isNotEmpty()) {
                        File(context.filesDir, INDEX_CACHE).writeText(encodeFestivals(index))
                        index to null
                    } else {
                        val one = parseClashfinderEvent(text, importedId(context, uri))
                        if (one.acts.isEmpty()) throw IOException("That is not a clashfinder file.")
                        File(context.filesDir, PROGRAMME_CACHE).writeText(encodeProgramme(one))
                        null to one
                    }
                }
            }
                .onSuccess { (index, one) ->
                    index?.let { festivals = it }
                    one?.let {
                        programme = it
                        picking = false
                    }
                    blocked = false
                    error = null
                }
                .onFailure { error = it.message ?: "Could not read that file." }
            loading = false
        }
    }

    // The one thing this screen can do to the timeline answers on this screen. The
    // notice used to fire on whichever screen was next, out of context — and the
    // "already on the Bill" case was invisible, so an Add that correctly did nothing
    // looked broken. Toast, because that is how the timeline already answers.
    LaunchedEffect(state.notice) {
        state.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeNotice()
        }
    }

    // The board is something you reached *through* the picker, so back walks that step
    // back before it leaves the screen. Re-entering lands on the board again, because the
    // cached programme is what decides [picking].
    val goBack: () -> Unit = { if (picking) onBack() else picking = true }
    BackHandler(onBack = goBack)

    Scaffold(
        containerColor = Ground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (picking || programme.name.isBlank()) "Programme" else programme.name,
                        fontFamily = Serif,
                        color = Ink,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = goBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Muted)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ground),
            )
        },
    ) { padding ->
        val pad = Modifier.padding(padding)
        when {
            // No account, no source — and this is the one place that can say so. There
            // is deliberately no bundled credential: one account shared by every
            // install would put all of this app's traffic on one key against a host
            // that watches for exactly that. So the empty state is a signpost.
            !state.clashfinderReady -> NoAccount(pad, onOpenSettings)


            picking -> Picker(
                modifier = pad,
                festivals = festivals,
                query = query,
                onQuery = { query = it },
                today = billNightOf(now),
                loading = loading,
                error = error,
                blocked = blocked,
                onImport = { importer.launch(arrayOf("*/*")) },
                onOpenInBrowser = { scope.launch { viewModel.openClashfinderInBrowser(context, INDEX_PATH) } },
                onRefresh = { loadIndex() },
                onPick = { loadProgramme(it.id) },
            )

            else -> DeparturesBoard(
                modifier = pad,
                programme = programme,
                onLine = state.plannedGigs + state.setlists,
                now = now,
                loading = loading,
                error = error,
                blocked = blocked,
                onImport = { importer.launch(arrayOf("*/*")) },
                onOpenInBrowser = {
                    scope.launch {
                        viewModel.openClashfinderInBrowser(context, "event/${programme.id}.json")
                    }
                },
                onRefetch = { loadProgramme(programme.id) },
                onCommit = { diff, picked -> viewModel.commitProgramme(programme, diff, picked) },
            )
        }
    }
}

@Composable
private fun NoAccount(modifier: Modifier, onOpenSettings: () -> Unit) {
    Column(modifier.padding(24.dp)) {
        Text(
            "Timetables come from clashfinder, and clashfinder wants an account of " +
                "your own — this app does not carry a shared one. Register at " +
                "clashfinder.com, then put your username and the private key off your " +
                "account page into Settings. It is free, and it takes a minute.",
            color = Muted,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            "Open Settings",
            color = Ground,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Amber)
                .clickable(onClick = onOpenSettings)
                .padding(horizontal = 18.dp, vertical = 11.dp),
        )
    }
}

/**
 * What went wrong, and — where there is one — the way out of it.
 *
 * The bot check is the only failure this screen can actually do something about, and
 * "try again in a while" was a wall: the check clears by taking it in a browser, so the
 * error offers the browser. Everything else is a sentence, as before.
 */
@Composable
private fun ErrorNote(
    error: String?,
    blocked: Boolean,
    onImport: () -> Unit,
    onOpenInBrowser: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (error == null) return
    Column(modifier) {
        Text(error, color = Slate, fontSize = 13.sp)
        if (blocked) ByHand(onImport, onOpenInBrowser)
    }
}

/**
 * The way round a host that will not answer this app: fetch the file in a browser, which
 * it does answer, and hand it over.
 *
 * Deliberately plain and deliberately last. It is a stopgap until clashfinder lets this
 * client through, not a way anyone should have to work, and the copy says so rather than
 * dressing two manual steps up as a feature.
 */
@Composable
private fun ByHand(onImport: () -> Unit, onOpenInBrowser: () -> Unit) {
    Spacer(Modifier.height(14.dp))
    Text(
        "Until that is sorted out, a browser can still fetch it: open the file, then " +
            "hand it over. Two manual steps, and it should not be this way.",
        color = Faint,
        fontSize = 12.sp,
    )
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Action("Open in a browser", loading = false, onClick = onOpenInBrowser)
        Text(
            "Hand over the file",
            color = Muted,
            fontSize = 13.sp,
            modifier = Modifier
                .clickable(onClick = onImport)
                .padding(horizontal = 12.dp, vertical = 11.dp),
        )
    }
}

/** A timetable is fetched by id, and the browser saves it under that id. */
private fun importedId(context: Context, uri: Uri): String {
    val name = context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }
        .orEmpty()
    return name.removeSuffix(".json").takeIf { isClashfinderId(it) }.orEmpty()
}

/**
 * Which festival, out of ten and a half thousand.
 *
 * Ordered by nearness to tonight rather than filtered to the future: 1% of clashfinders
 * are upcoming at any moment, so a future-only list would be nearly empty *and* would
 * shut out anybody recording a festival they went to. The window is short because ten
 * thousand rows is not a list, and it grows on scroll; the search runs over the whole
 * catalogue and not over what is on screen.
 */
@Composable
private fun Picker(
    modifier: Modifier,
    festivals: List<ClashfinderFestival>,
    query: String,
    onQuery: (String) -> Unit,
    today: LocalDate,
    loading: Boolean,
    error: String?,
    blocked: Boolean,
    onImport: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onRefresh: () -> Unit,
    onPick: (ClashfinderFestival) -> Unit,
) {
    var window by remember(query) { mutableIntStateOf(WINDOW) }
    val ranked = remember(festivals, query, today) { rankFestivals(festivals, today, query) }
    val listState = rememberLazyListState()
    // The window grows as the bottom of it comes into view, so the default being short
    // does not put the older half of the catalogue out of reach.
    val lastVisible by remember {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
    }
    LaunchedEffect(lastVisible) { if (lastVisible >= window - 5) window += WINDOW }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (festivals.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "The list of festivals clashfinder knows about lives on clashfinder. " +
                    "Fetch it here and it stays on this phone.",
                color = Muted,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(18.dp))
            Action(if (loading) "Fetching…" else "Fetch the festival list", loading, onRefresh)
            Spacer(Modifier.height(14.dp))
            ErrorNote(error, blocked, onImport, onOpenInBrowser)
            return@Column
        }

        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            label = { Text("Find a festival", color = Muted) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (loading) "Fetching…" else "${ranked.size} of ${festivals.size}",
                color = Faint,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Refresh the list",
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.clickable(enabled = !loading, onClick = onRefresh).padding(6.dp),
            )
        }
        ErrorNote(error, blocked, onImport, onOpenInBrowser, Modifier.padding(vertical = 6.dp))
        if (ranked.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            // The expected case, not a fault: clashfinder has ten thousand festivals
            // and there are rather more festivals than that in the world.
            Text(
                "Nothing here by that name. Not every festival is on clashfinder — " +
                    "somebody has to type a timetable in for it to exist.",
                color = Muted,
                fontSize = 14.sp,
            )
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(ranked.take(window), key = { it.id }) { festival ->
                FestivalRow(festival, onPick)
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

private val DAY_STAMP = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

@Composable
private fun FestivalRow(festival: ClashfinderFestival, onPick: (ClashfinderFestival) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Raised)
            .clickable { onPick(festival) }
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Text(festival.name, fontFamily = Serif, fontSize = 16.sp, color = Ink)
        Spacer(Modifier.height(3.dp))
        // The counts are how you tell a real timetable from an abandoned stub, which is
        // the difference between two entries for one festival that nothing else shows.
        Text(
            listOfNotNull(
                festival.startsOn()?.format(DAY_STAMP),
                "${festival.acts} acts",
                "${festival.stages} stages",
            ).joinToString(" · "),
            color = Muted,
            fontSize = 12.sp,
        )
    }
}

/**
 * **Departures**: the festival's days, each a vertical run of positions in running
 * order, and one button that commits what you picked.
 *
 * Tap an act to select it; tap it again to deselect. That is the only gesture, and it
 * means the same thing everywhere. Nothing is destroyed by choosing — the options you
 * did not take stay on their rung, and a rung outlives the night it describes, because
 * plans change on the day and adding to a programme after the festival is over is an
 * ordinary thing to do.
 *
 * The day tabs are **views**, not scopes: the selection is one selection across the
 * whole programme and the button's label is the diff, so it can be pressed from any tab
 * without checking the others.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeparturesBoard(
    modifier: Modifier,
    programme: StoredProgramme,
    onLine: List<FmSetlist>,
    now: LocalDateTime,
    loading: Boolean,
    error: String?,
    blocked: Boolean,
    onImport: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onRefetch: () -> Unit,
    onCommit: (ProgrammeDiff, Set<String>) -> Unit,
) {
    val applied = remember(programme, onLine) { appliedActs(programme, onLine) }
    // The working selection starts as what is already on the line, so that turning an
    // act off reads as a removal rather than as never having picked it. Re-seeded when
    // the line moves under it — a commit is exactly that, and it is what empties the
    // button again.
    var picked by remember(programme.id) { mutableStateOf(applied) }
    LaunchedEffect(applied) { picked = applied }

    val board = remember(programme, picked, applied) { departuresOf(programme, picked, applied) }
    // Opens on the night you are actually standing in. Before the festival that is the
    // first day, after it the last — never an empty screen. Null is "all days".
    var day by remember(board.days) {
        mutableStateOf(board.days.firstOrNull { it >= billNightOf(now) } ?: board.days.lastOrNull())
    }
    val shown = day?.let { listOf(it) } ?: board.days
    val label = commitLabel(board.diff, programme.name, firstCommit = applied.isEmpty())
    val toggle: (String) -> Unit = { key -> picked = if (key in picked) picked - key else picked + key }

    // What the head counts. Only what is in view: the tally answers "how much of this
    // night have I decided", and a number over days you cannot see answers nothing.
    val positions = shown.flatMap { board.positions[it].orEmpty() }
    val mine = positions.count { p -> p.options.any { actKey(it) in picked } }
    val open = positions.count { p -> p.isRung && p.options.none { actKey(it) in picked } }

    Column(modifier.fillMaxSize()) {
        Head(
            days = board.days,
            day = day,
            mine = mine,
            open = open,
            onDay = { day = it },
        )
        // Pulled down rather than tapped: a timetable is a document you refresh, and the
        // gesture is where the hand already is. The line runs upward, so the top of it is
        // the far end of the night — the footer keeps its own tap for the other end.
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = onRefetch,
            modifier = Modifier.fillMaxSize(),
        ) {
            // Days stack in time, so moving between them moves the line vertically: the
            // motion says "another day" where a sideways one would say "another option
            // on this rung", which is the gesture a rung already owns.
            AnimatedContent(
                targetState = shown,
                transitionSpec = {
                    val up = (targetState.firstOrNull() ?: LocalDate.MIN) >
                        (initialState.firstOrNull() ?: LocalDate.MIN)
                    val d = if (up) 1 else -1
                    (slideInVertically { h -> d * h / 8 } + fadeIn())
                        .togetherWith(slideOutVertically { h -> -d * h / 8 } + fadeOut())
                },
                label = "day",
            ) { days ->
                Line(
                    days = days,
                    board = board,
                    picked = picked,
                    applied = applied,
                    firstCommit = applied.isEmpty(),
                    programme = programme,
                    loading = loading,
                    error = error,
                    blocked = blocked,
                    onImport = onImport,
                    onOpenInBrowser = onOpenInBrowser,
                    onRefetch = onRefetch,
                    onToggle = toggle,
                )
            }
            CommitSlot(
                visible = label != null,
                label = label.orEmpty(),
                sub = commitSub(board.diff, programme.name, applied.isEmpty(), open),
                onClick = { onCommit(board.diff, picked) },
            )
        }
    }
}

/**
 * The bar's own slot at the foot of the board.
 *
 * Its own function so that only [BoxScope] is in scope here: nested inside the head's
 * Column, `AnimatedVisibility` resolved to the ColumnScope overload and would not
 * compile. It rises out of the bottom rather than appearing, so the first pick is
 * answered by something arriving.
 */
@Composable
private fun BoxScope.CommitSlot(visible: Boolean, label: String, sub: String, onClick: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
    ) {
        CommitBar(label, sub, onClick)
    }
}

/** The festival's days, what is decided, and what is still open. */
@Composable
private fun Head(
    days: List<LocalDate>,
    day: LocalDate?,
    mine: Int,
    open: Int,
    onDay: (LocalDate?) -> Unit,
) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
            Label("DEPARTURES", Faint)
            Spacer(Modifier.weight(1f))
            Tally(mine, "PLANNED", Amber)
            Spacer(Modifier.width(14.dp))
            Tally(open, "OPEN", Slate)
        }
        // Scrollable, because the number of days is the festival's: a clipped chip takes
        // no taps, so the last nights of a long festival would be unreachable. It also
        // scrolls itself to the day in view — a festival opened after its last night
        // starts on that night, and a tab you cannot see reads as no tab selected.
        val chips = rememberLazyListState()
        LaunchedEffect(day, days) {
            val i = if (day == null) days.size else days.indexOf(day)
            if (i >= 0) chips.animateScrollToItem(i)
        }
        LazyRow(
            state = chips,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(days) { d -> DayChip(dayLabel(d), d == day) { onDay(d) } }
            item { DayChip("All days", day == null) { onDay(null) } }
        }
    }
}

@Composable
private fun Tally(count: Int, label: String, colour: Color) {
    Column(horizontalAlignment = Alignment.End) {
        Text("$count", color = colour, fontSize = 17.sp, fontFamily = FontFamily.Monospace)
        Text(label, color = Faint, fontSize = 9.sp, letterSpacing = 1.2.sp)
    }
}

/**
 * The line itself: a rail with the night hung off it, reading **upward into later**.
 *
 * Reversed, because that is which way the **Line** runs everywhere else in this app —
 * the future is up, and a programme is entirely future until it isn't. The footer sits
 * at the bottom, where the day starts.
 */
@Composable
private fun Line(
    days: List<LocalDate>,
    board: Departures,
    picked: Set<String>,
    applied: Set<String>,
    firstCommit: Boolean,
    programme: StoredProgramme,
    loading: Boolean,
    error: String?,
    blocked: Boolean,
    onImport: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onRefetch: () -> Unit,
    onToggle: (String) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        reverseLayout = true,
        contentPadding = PaddingValues(bottom = 8.dp, top = 8.dp),
    ) {
        item(key = "foot") { Footer(programme, loading, error, blocked, onImport, onOpenInBrowser, onRefetch) }

        days.forEach { d ->
            items(board.positions[d].orEmpty(), key = { "$d|" + it.options.first().artist }) { position ->
                PositionRow(position, picked, applied, firstCommit, onToggle)
            }
            item(key = "band-$d") {
                // At the top of its own band, because the band is read downward from
                // its label even though the line is built upward.
                Text(
                    dayLabel(d).uppercase(Locale.ENGLISH),
                    color = Faint,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.rail().padding(start = RailInset, top = 14.dp, bottom = 6.dp),
                )
            }
        }

        item(key = "later") {
            Text(
                "↑ later",
                color = Faint,
                fontSize = 10.sp,
                letterSpacing = 1.5.sp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Footer(
    programme: StoredProgramme,
    loading: Boolean,
    error: String?,
    blocked: Boolean,
    onImport: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onRefetch: () -> Unit,
) {
    Column(Modifier.padding(top = 8.dp, bottom = 84.dp)) {
        // A clashfinder is edited up to and past the doors — the median one is last
        // touched the day before its own festival — so how old this copy is decides
        // whether to trust it, and refetching is one tap from that sentence.
        if (programme.lastEdit.isNotBlank()) {
            Text("Timetable last edited ${programme.lastEdit}", color = Faint, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
        }
        Text(
            if (loading) "Fetching…" else "Fetch it again",
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.clickable(enabled = !loading, onClick = onRefetch).padding(vertical = 6.dp),
        )
        ErrorNote(error, blocked, onImport, onOpenInBrowser)
        // Attribution is a condition of the licence the data comes under, not a
        // courtesy — so it is rendered with the data every time.
        if (programme.copyright.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(programme.copyright, color = Faint, fontSize = 11.sp)
        }
    }
}

/**
 * What commits, and what it will cost — a bar rather than a button, because the label
 * is a sentence about the whole programme and not a word about this screen.
 */
@Composable
private fun CommitBar(label: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(Amber)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Ground, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (sub.isNotBlank()) {
                Text(
                    sub.uppercase(Locale.ENGLISH),
                    color = Ground.copy(alpha = 0.72f),
                    fontSize = 10.sp,
                    letterSpacing = 0.8.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Text("→", color = Ground, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
    }
}

private val DAY_LABEL = DateTimeFormatter.ofPattern("EEEE d", Locale.ENGLISH)

private fun dayLabel(day: LocalDate): String = day.format(DAY_LABEL)

/** The same night boundary the rest of the app uses, so 01:30 is still tonight here too. */
private fun billNightOf(now: LocalDateTime): LocalDate =
    if (now.toLocalTime() < NIGHT_ENDS) now.toLocalDate().minusDays(1) else now.toLocalDate()

@Composable
private fun Label(text: String, color: Color) {
    Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
}

@Composable
private fun Action(text: String, loading: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = Ground,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (loading) Faint else Amber)
            .clickable(enabled = !loading, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
    )
}

@Composable
private fun DayChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Ground else Muted,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) Amber else Color.Transparent)
            .border(1.dp, if (selected) Amber else ChipEdge, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .semantics {
                this.selected = selected
                role = Role.Tab
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

private val ChipEdge = Color(0xFF2E2740)

/** Where the rail runs, and how far the cards stand clear of it. */
private val RailX = 34.dp
private val RailInset = RailX + 18.dp
private val Rail = Color(0xFF2E2740)
private val AmberDim = Color(0xFF8C6A28)

/**
 * The rail, drawn behind every row.
 *
 * Per row rather than once behind the whole list, because the list is lazy: a single
 * full-height line would have to know a height nothing has measured yet, and each row
 * drawing its own segment makes one continuous rail for free.
 */
private fun Modifier.rail(): Modifier = drawBehind {
    val x = RailX.toPx()
    drawLine(Rail, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
}

/** What this position is about to become on the **Line**. */
private enum class NodeState { Settled, Pending, Leaving, Off }

private fun stateOf(picked: Boolean, applied: Boolean): NodeState = when {
    picked && applied -> NodeState.Settled
    picked -> NodeState.Pending
    applied -> NodeState.Leaving
    else -> NodeState.Off
}

/**
 * One position in the running order: a single act, or a **rung** of acts you are
 * choosing between.
 *
 * A rung is horizontal and a day is vertical, so the axis says which it is before a
 * word is read: sideways means "another option here", upwards means "and then".
 */
@Composable
private fun PositionRow(
    position: Position,
    picked: Set<String>,
    applied: Set<String>,
    firstCommit: Boolean,
    onToggle: (String) -> Unit,
) {
    if (position.isRung) RungNode(position, picked, applied, onToggle)
    else GigNode(position.options.first(), picked, applied, firstCommit, onToggle)
}

/**
 * A settled night on the line — or one on its way on or off it.
 *
 * The four states are the whole of what a diff can do to one act, and each says so in
 * words as well as in colour: a preview nobody can read is a preview of nothing.
 */
@Composable
private fun GigNode(
    act: ProgrammeAct,
    picked: Set<String>,
    applied: Set<String>,
    firstCommit: Boolean,
    onToggle: (String) -> Unit,
) {
    val key = actKey(act)
    val chosen = key in picked
    val state = stateOf(chosen, key in applied)
    Box(Modifier.fillMaxWidth().rail().dot(state)) {
        Column(
            Modifier
                .padding(start = RailInset, top = 5.dp, bottom = 5.dp)
                .fillMaxWidth()
                .background(if (state == NodeState.Settled) Raised else Color.Transparent)
                .edge(
                    when (state) {
                        NodeState.Settled -> Amber
                        NodeState.Pending -> AmberDim
                        else -> Rail
                    },
                )
                .clickable { onToggle(key) }
                .semantics {
                    selected = chosen
                    role = Role.Checkbox
                    stateDescription = if (chosen) "going" else "not going"
                }
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
                .alpha(if (state == NodeState.Off) 0.45f else 1f),
        ) {
            Text(
                act.artist,
                fontFamily = Serif,
                fontSize = 16.sp,
                color = if (state == NodeState.Off) Muted else Ink,
            )
            Text(when_(act), color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            // Only while the change is pending. A line already on the line says nothing;
            // the whole point of this row is what pressing the bar would do to it.
            val flag = when (state) {
                NodeState.Pending -> "Will be added"
                NodeState.Leaving -> "Will be removed"
                NodeState.Off -> if (firstCommit) "" else "Not taken"
                NodeState.Settled -> ""
            }
            if (flag.isNotBlank()) {
                Text(flag.uppercase(Locale.ENGLISH), color = Faint, fontSize = 9.sp, letterSpacing = 1.1.sp)
            }
        }
    }
}

/**
 * A rung: everything you are choosing between, side by side and none of it decided
 * for you.
 *
 * The options never collapse. Picking one lights it and leaves the rest exactly where
 * they were, because the day itself changes its mind and a rung outlives its night.
 * The last card is **Neither**, which is what "I am going to none of these" looks like
 * when it has to be a thing you can point at.
 */
@Composable
private fun RungNode(
    position: Position,
    picked: Set<String>,
    applied: Set<String>,
    onToggle: (String) -> Unit,
) {
    val options = position.options
    val taken = options.firstOrNull { actKey(it) in picked }
    val row = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // The card in front of you is the one nearest the left edge, not the first one
    // *touching* it: firstVisibleItemIndex flips after a pixel of scroll, so the lit card
    // and the pip ran a whole option ahead of the track and you tapped one you couldn't
    // see. Nearest-start is also exactly where the snap fling settles.
    val focus by remember {
        derivedStateOf {
            val info = row.layoutInfo
            info.visibleItemsInfo.minByOrNull { abs(it.offset - info.viewportStartOffset) }?.index ?: 0
        }
    }

    Box(Modifier.fillMaxWidth().rail().cross()) {
        Column(Modifier.padding(start = RailInset, top = 8.dp, bottom = 8.dp)) {
            Text(
                "${options.size} WAYS · ${options.first().start}",
                color = Slate,
                fontSize = 9.sp,
                letterSpacing = 1.2.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            LazyRow(
                state = row,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                flingBehavior = rememberSnapFlingBehavior(row),
            ) {
                itemsIndexed(options, key = { _, a -> a.artist }) { i, act ->
                    val key = actKey(act)
                    Option(
                        act = act,
                        state = stateOf(key in picked, key in applied),
                        lit = i == focus,
                        // Reading and choosing are one gesture: a card you tapped to
                        // read comes to the front, and taking it is the same tap again.
                        onClick = {
                            scope.launch { row.animateScrollToItem(i) }
                            onToggle(key)
                        },
                    )
                }
                item(key = "neither") {
                    Neither(
                        lit = options.size == focus,
                        chosen = taken == null,
                        onClick = {
                            scope.launch { row.animateScrollToItem(options.size) }
                            options.forEach { a -> if (actKey(a) in picked) onToggle(actKey(a)) }
                        },
                    )
                }
            }
            // How many are left, and which of them is yours — the count a scrolling
            // track hides.
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                options.forEachIndexed { i, act ->
                    Pip(on = i == focus, taken = actKey(act) in picked)
                }
                Pip(on = options.size == focus, taken = taken == null)
            }
        }
    }
}

/** One option on a rung. Dimmed until it is the one in front of you. */
@Composable
private fun Option(act: ProgrammeAct, state: NodeState, lit: Boolean, onClick: () -> Unit) {
    val chosen = state == NodeState.Settled || state == NodeState.Pending
    Column(
        Modifier
            .width(216.dp)
            .background(if (lit) Raised else Color.Transparent)
            .border(1.dp, if (chosen) Amber else if (lit) Slate else SlateDim)
            .edge(if (chosen) Amber else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics {
                selected = chosen
                role = Role.Checkbox
                stateDescription = if (chosen) "going" else "not going"
            }
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .alpha(if (lit) 1f else 0.45f),
    ) {
        Text(act.artist, fontFamily = Serif, fontSize = 15.sp, color = if (chosen) Amber else Ink)
        Text(when_(act), color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

/** The choice the programme does not offer: none of them. */
@Composable
private fun Neither(lit: Boolean, chosen: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .width(150.dp)
            .border(1.dp, if (chosen) Slate else SlateDim)
            .clickable(onClick = onClick)
            .semantics { selected = chosen; role = Role.Checkbox }
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .alpha(if (lit) 1f else 0.45f),
    ) {
        Text("Neither", fontFamily = Serif, fontSize = 15.sp, color = if (chosen) Slate else Faint)
        Text("nothing planned here", color = Faint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Pip(on: Boolean, taken: Boolean) {
    Box(
        Modifier
            .size(5.dp)
            .clip(CircleShape)
            .background(if (taken) Amber else if (on) Slate else SlateDim),
    )
}

private val SlateDim = Color(0xFF3D4654)

/** The published end where there is one: a headline set is not an hour. */
private fun when_(act: ProgrammeAct): String =
    (if (act.end.isBlank()) act.start else "${act.start}–${act.end}") + " · " + act.stage

/** The 2dp bar down a card's left edge: which way this act is going, in one stroke. */
private fun Modifier.edge(colour: Color): Modifier = drawBehind {
    if (colour != Color.Transparent) drawRect(colour, size = Size(2.dp.toPx(), size.height))
}

/** A **Gig**'s mark on the rail. Filled is on the line; an outline is not yet. */
private fun Modifier.dot(state: NodeState): Modifier = drawBehind {
    val centre = Offset(RailX.toPx(), 22.dp.toPx())
    val r = 5.5.dp.toPx()
    // Punched out of the rail first, so the line does not run through the mark.
    drawCircle(Ground, r + 3.dp.toPx(), centre)
    when (state) {
        NodeState.Settled -> drawCircle(Amber, r, centre)
        NodeState.Pending -> drawCircle(AmberDim, r, centre, style = Stroke(2.dp.toPx(), pathEffect = Dashes))
        NodeState.Leaving -> drawCircle(Rail, r, centre)
        NodeState.Off -> drawCircle(Rail, r, centre, style = Stroke(2.dp.toPx()))
    }
}

/** A rung's mark: bigger, dashed, and cool — a crossing rather than a stop. */
private fun Modifier.cross(): Modifier = drawBehind {
    val centre = Offset(RailX.toPx(), 26.dp.toPx())
    val r = 7.5.dp.toPx()
    drawCircle(Ground, r + 3.dp.toPx(), centre)
    drawCircle(Slate, r, centre, style = Stroke(2.dp.toPx(), pathEffect = Dashes))
}

private val Dashes = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
