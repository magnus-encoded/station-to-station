package io.github.magnusencoded.stationtostation.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import io.github.magnusencoded.stationtostation.data.commitLabel
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
 * The noticeboard: what is on, what each thing costs you, and what to put on your **Bill**.
 *
 * The whole screen is one question asked three ways: *right now* (the on-now block,
 * only while the festival is running), *later today* (the day list, where every act
 * carries what it clashes with), and *am I going to that* (add, which puts an **Act**
 * on a **Bill** — never a **Gig**, because planning to go is not having gone).
 *
 * Read-only about the festival and writable only about *me*: nothing here edits the
 * programme, and the only thing it can produce is an act on my own wall.
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
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Muted)
                    }
                },
                actions = {
                    if (!picking && programme.acts.isNotEmpty()) {
                        Text(
                            "Change",
                            color = Muted,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clickable { picking = true }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        )
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

            else -> Departures(
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
                onCommit = { viewModel.commitProgramme(programme, it) },
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
@Composable
private fun Departures(
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
    onCommit: (ProgrammeDiff) -> Unit,
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

    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                // Scrollable, because the number of days is the festival's: a clipped
                // chip takes no taps, so the last nights of a long festival would be
                // unreachable.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    board.days.forEach { d ->
                        DayChip(dayLabel(d), selected = d == day, onClick = { day = d })
                    }
                    DayChip("All days", selected = day == null, onClick = { day = null })
                }
            }

            shown.forEach { d ->
                if (shown.size > 1) {
                    item(key = "head-$d") {
                        Spacer(Modifier.height(6.dp))
                        Label(dayLabel(d).uppercase(Locale.ENGLISH), Slate)
                        Spacer(Modifier.height(8.dp))
                    }
                }
                items(board.positions[d].orEmpty(), key = { "$d-" + it.options.first().artist }) { position ->
                    PositionRow(
                        position = position,
                        picked = picked,
                        applied = applied,
                        onToggle = { key -> picked = if (key in picked) picked - key else picked + key },
                    )
                }
            }

            item {
                Spacer(Modifier.height(18.dp))
                // A clashfinder is edited up to and past the doors — the median one is
                // last touched the day before its own festival — so how old this copy is
                // decides whether to trust it, and refetching is one tap from that.
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
                // Room for the button to float over, so the licence line is readable
                // with a selection made.
                Spacer(Modifier.height(if (label == null) 32.dp else 96.dp))
            }
        }

        // One place to commit, and nothing at all when nothing would change.
        label?.let {
            Box(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                Action(it, loading = false, onClick = { onCommit(board.diff) })
            }
        }
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

/**
 * One position in the running order: a single act, or a **rung** of acts you are
 * choosing between.
 *
 * A rung is horizontal and a day is vertical, so the axis says which it is before a
 * word is read: sideways means "another option here", downwards means "and then".
 */
@Composable
private fun PositionRow(
    position: Position,
    picked: Set<String>,
    applied: Set<String>,
    onToggle: (String) -> Unit,
) {
    if (!position.isRung) {
        val act = position.options.first()
        ActCard(act, actKey(act) in picked, actKey(act) in applied, Modifier.fillMaxWidth(), onToggle)
        return
    }
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(
            "${position.options.size} AT ONCE",
            color = Faint,
            fontSize = 9.sp,
            letterSpacing = 1.2.sp,
            modifier = Modifier.padding(bottom = 5.dp),
        )
        // Scrolled rather than paged: reading every option is the work of a rung, and a
        // carousel that snaps hides how many are left. ponytail: no centring slide and
        // no snap — those need a thumb to judge and can be added once one has been on it.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            position.options.forEach { act ->
                ActCard(act, actKey(act) in picked, actKey(act) in applied, Modifier.width(240.dp), onToggle)
            }
        }
    }
}

/**
 * One act, and whether it is decided.
 *
 * Amber is decided; nothing else on the card changes. The line beneath says whether it
 * is already on the **Line**, so a decision already made is not offered as a new one.
 */
@Composable
private fun ActCard(
    act: ProgrammeAct,
    selected: Boolean,
    onLine: Boolean,
    modifier: Modifier,
    onToggle: (String) -> Unit,
) {
    Column(
        modifier
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Raised)
            .border(1.dp, if (selected) Amber else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable { onToggle(actKey(act)) }
            .semantics {
                this.selected = selected
                role = Role.Checkbox
                stateDescription = if (selected) "going" else "not going"
            }
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                // The published end where there is one: a headline set is not an hour,
                // and the length is half of what a choice costs.
                if (act.end.isBlank()) act.start else "${act.start}–${act.end}",
                color = if (selected) Amber else Slate,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(act.artist, fontFamily = Serif, fontSize = 16.sp, color = Ink)
                Text(act.stage, color = Muted, fontSize = 12.sp)
            }
        }
        if (onLine) {
            Spacer(Modifier.height(5.dp))
            Text("on your line", color = Faint, fontSize = 11.sp)
        }
    }
}
