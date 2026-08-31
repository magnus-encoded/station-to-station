package io.github.magnusencoded.stationtostation.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import io.github.magnusencoded.stationtostation.data.ProgrammeAct
import io.github.magnusencoded.stationtostation.data.StoredProgramme
import io.github.magnusencoded.stationtostation.data.actsOn
import io.github.magnusencoded.stationtostation.data.clashesWith
import io.github.magnusencoded.stationtostation.data.clashfinder.ClashfinderFestival
import io.github.magnusencoded.stationtostation.data.clashfinder.decodeFestivals
import io.github.magnusencoded.stationtostation.data.clashfinder.encodeFestivals
import io.github.magnusencoded.stationtostation.data.clashfinder.rankFestivals
import io.github.magnusencoded.stationtostation.data.encodeProgramme
import io.github.magnusencoded.stationtostation.data.nextAfter
import io.github.magnusencoded.stationtostation.data.parseProgramme
import io.github.magnusencoded.stationtostation.data.playingAt
import io.github.magnusencoded.stationtostation.data.programmeDays
import kotlinx.coroutines.launch
import java.io.File
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

    var programme by remember { mutableStateOf(cachedProgramme(context)) }
    var festivals by remember { mutableStateOf(cachedFestivals(context)) }
    // The picker is where you start with nothing in hand, and where "change festival"
    // sends you back to.
    var picking by remember { mutableStateOf(programme.acts.isEmpty()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadIndex() {
        loading = true
        error = null
        scope.launch {
            runCatching { viewModel.clashfinder.index() }
                .onSuccess {
                    festivals = it
                    File(context.filesDir, INDEX_CACHE).writeText(encodeFestivals(it))
                }
                .onFailure { error = it.message ?: "Could not reach clashfinder." }
            loading = false
        }
    }

    fun loadProgramme(id: String) {
        loading = true
        error = null
        scope.launch {
            runCatching { viewModel.clashfinder.event(id) }
                // A failed fetch leaves the timetable already in hand untouched. A bad
                // connection in a field must not cost somebody their plans.
                .onSuccess {
                    programme = it
                    File(context.filesDir, PROGRAMME_CACHE).writeText(encodeProgramme(it))
                    picking = false
                }
                .onFailure { error = it.message ?: "Could not reach clashfinder." }
            loading = false
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
                onRefresh = { loadIndex() },
                onPick = { loadProgramme(it.id) },
            )

            else -> Timetable(
                modifier = pad,
                programme = programme,
                now = now,
                loading = loading,
                error = error,
                onRefetch = { loadProgramme(programme.id) },
                onAdd = { viewModel.addActFromProgramme(programme, it) },
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
            error?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, color = Slate, fontSize = 13.sp)
            }
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
        error?.let {
            Text(it, color = Slate, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
        }
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

@Composable
private fun Timetable(
    modifier: Modifier,
    programme: StoredProgramme,
    now: LocalDateTime,
    loading: Boolean,
    error: String?,
    onRefetch: () -> Unit,
    onAdd: (ProgrammeAct) -> Unit,
) {
    val acts = programme.acts
    val days = remember(acts) { programmeDays(acts) }
    // Opens on the night you are actually standing in. Before the festival that is the
    // first day, after it the last — never an empty screen.
    var day by remember(days) {
        mutableStateOf(days.firstOrNull { it >= billNightOf(now) } ?: days.lastOrNull())
    }
    val onNow = remember(acts, now) { playingAt(now, acts) }

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (onNow.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                Label("ON NOW", Amber)
                Spacer(Modifier.height(8.dp))
            }
            items(onNow, key = { "now-" + it.artist + it.stage }) { act ->
                ActRow(act, acts, accent = Amber, onAdd = onAdd)
            }
            // What starts next, so a break can be timed. Only while something is on:
            // "next" a fortnight before the festival is the whole timetable, which the
            // day list below already is.
            val next = nextAfter(now, acts, limit = 3)
            if (next.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(14.dp))
                    Label("AND THEN", Slate)
                    Spacer(Modifier.height(6.dp))
                    next.forEach {
                        Text(
                            "${it.start}  ${it.artist} · ${it.stage}",
                            color = Muted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                days.forEach { d ->
                    DayChip(d, selected = d == day, onClick = { day = d })
                }
            }
        }

        val listed = day?.let { actsOn(it, acts) }.orEmpty()
        items(listed, key = { it.artist + it.stage + it.start }) { act ->
            ActRow(act, acts, accent = Slate, onAdd = onAdd)
        }

        item {
            Spacer(Modifier.height(18.dp))
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
            error?.let { Text(it, color = Slate, fontSize = 13.sp) }
            // Attribution is a condition of the licence the data comes under, not a
            // courtesy — so it is rendered with the data every time.
            if (programme.copyright.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(programme.copyright, color = Faint, fontSize = 11.sp)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

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
private fun DayChip(day: LocalDate, selected: Boolean, onClick: () -> Unit) {
    val fmt = DateTimeFormatter.ofPattern("EEE d", Locale.ENGLISH)
    Text(
        day.format(fmt),
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
 * One act, what it is a choice against, and the way onto a **Bill**.
 *
 * The clash list is the payload of the whole screen, so it is shown by default rather
 * than behind a tap: the moment you need it is while walking between stages, and a
 * gesture you have to remember is one you will not make. Tapping collapses it, for the
 * evenings where you have already decided.
 */
@Composable
private fun ActRow(
    act: ProgrammeAct,
    all: List<ProgrammeAct>,
    accent: Color,
    onAdd: (ProgrammeAct) -> Unit,
) {
    var open by remember(act) { mutableStateOf(true) }
    val clashes = remember(act, all) { clashesWith(act, all) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Raised)
            .clickable { open = !open }
            .semantics { stateDescription = if (open) "expanded" else "collapsed" }
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                // The published end where there is one: a headline set is not an hour,
                // and the length is half of what a choice costs.
                if (act.end.isBlank()) act.start else "${act.start}–${act.end}",
                color = accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(act.artist, fontFamily = Serif, fontSize = 16.sp, color = Ink)
                Text(act.stage, color = Muted, fontSize = 12.sp)
            }
            Text(
                "Add",
                color = Amber,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable { onAdd(act) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        if (clashes.isEmpty()) {
            Spacer(Modifier.height(5.dp))
            // Worth saying out loud. "No clash" is the rarest and most useful fact on
            // a festival timetable, and silence would read as "not computed".
            Text("clear", color = Faint, fontSize = 11.sp)
        } else if (open) {
            Spacer(Modifier.height(9.dp))
            Text("INSTEAD OF", color = Faint, fontSize = 9.sp, letterSpacing = 1.2.sp)
            Spacer(Modifier.height(4.dp))
            clashes.forEach {
                Text(
                    "${it.start}  ${it.artist} · ${it.stage}",
                    color = Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        } else {
            Spacer(Modifier.height(5.dp))
            Text("${clashes.size} clash${if (clashes.size == 1) "" else "es"}", color = Faint, fontSize = 11.sp)
        }
    }
}
