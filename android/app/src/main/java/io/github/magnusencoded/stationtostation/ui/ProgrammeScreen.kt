package io.github.magnusencoded.stationtostation.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import io.github.magnusencoded.stationtostation.data.OYA_PROGRAMME_URL
import io.github.magnusencoded.stationtostation.data.ProgrammeAct
import io.github.magnusencoded.stationtostation.data.actsOn
import io.github.magnusencoded.stationtostation.data.clashesWith
import io.github.magnusencoded.stationtostation.data.encodeProgramme
import io.github.magnusencoded.stationtostation.data.oyaProgramme
import io.github.magnusencoded.stationtostation.data.parseProgramme
import io.github.magnusencoded.stationtostation.data.playingAt
import io.github.magnusencoded.stationtostation.data.programmeDays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
 * Where a fetched programme lives on this device. One file, overwritten.
 *
 * On disk rather than in DataStore: it is a document, not a setting, and the next thing
 * that happens to it is being handed to another phone whole.
 */
private const val PROGRAMME_CACHE = "programme.json"

private fun cachedProgramme(context: Context): List<ProgrammeAct> =
    File(context.filesDir, PROGRAMME_CACHE)
        .takeIf { it.exists() }
        ?.let { parseProgramme(it.readText()) }
        .orEmpty()

/**
 * Fetch the public page and keep what it says. Blocking; call off the main thread.
 *
 * The programme is retrieved by this device, from the festival's own site, and stored
 * only here. Nothing is redistributed and nothing was shipped — see [oyaProgramme].
 */
private fun fetchProgramme(context: Context): Result<List<ProgrammeAct>> = runCatching {
    val request = Request.Builder().url(OYA_PROGRAMME_URL).build()
    val html = OkHttpClient().newCall(request).execute().use { response ->
        if (!response.isSuccessful) error("The festival's site returned ${response.code}.")
        response.body?.string().orEmpty()
    }
    val acts = oyaProgramme(html, year = 2026)
    // An empty parse means the page changed shape. Saying so beats caching nothing and
    // showing an empty timetable that looks like a festival with no bands.
    if (acts.isEmpty()) error("Could not read the programme — the site's layout has changed.")
    File(context.filesDir, PROGRAMME_CACHE).writeText(encodeProgramme(acts))
    acts
}

/**
 * The noticeboard: what is on, and what each thing costs you.
 *
 * Deliberately read-only and deliberately not wired into the timeline. A programme is
 * what the festival announced, not evidence anybody went — turning a listing into
 * attendance is the check-in's job and the **Bill**'s, and conflating them here would
 * put 83 acts on a **Line** that nobody attended.
 *
 * The whole screen is one question asked two ways: *right now* (the on-now block, only
 * while the festival is running) and *later today* (the day list, where every act
 * carries what it clashes with).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgrammeScreen(onBack: () -> Unit, now: LocalDateTime = LocalDateTime.now()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var acts by remember { mutableStateOf(cachedProgramme(context)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        loading = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { fetchProgramme(context) }
            result.onSuccess { acts = it }
                .onFailure { error = it.message ?: "Could not reach the festival's site." }
            loading = false
        }
    }

    val days = remember(acts) { programmeDays(acts) }
    // Opens on the night you are actually standing in. Before the festival that is the
    // first day, after it the last — never an empty screen.
    var day by remember(days) {
        mutableStateOf(days.firstOrNull { it >= billNightOf(now) } ?: days.lastOrNull())
    }
    val onNow = remember(acts, now) { playingAt(now, acts) }

    Scaffold(
        containerColor = Ground,
        topBar = {
            TopAppBar(
                title = { Text("Øya 2026", fontFamily = Serif, color = Ink) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Muted)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ground),
            )
        },
    ) { padding ->
        if (acts.isEmpty()) {
            Column(Modifier.padding(padding).padding(24.dp)) {
                Text(
                    "The line-up lives on the festival's own site. Fetch it here and it " +
                        "stays on this phone — get it before you go, the signal in a field " +
                        "is not the signal you have now.",
                    color = Muted,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    if (loading) "Fetching…" else "Fetch the programme",
                    color = Ground,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (loading) Faint else Amber)
                        .clickable(enabled = !loading) { load() }
                        .padding(horizontal = 18.dp, vertical = 11.dp),
                )
                error?.let {
                    Spacer(Modifier.height(14.dp))
                    Text(it, color = Slate, fontSize = 13.sp)
                }
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            if (onNow.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Label("ON NOW", Amber)
                    Spacer(Modifier.height(8.dp))
                }
                items(onNow, key = { "now-" + it.artist + it.stage }) { act ->
                    ActRow(act, acts, accent = Amber)
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
                ActRow(act, acts, accent = Slate)
            }
            item { Spacer(Modifier.height(32.dp)) }
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
 * One act, and underneath it the acts it is a choice against.
 *
 * The clash list is the payload of the whole screen, so it is shown by default rather
 * than behind a tap: the moment you need it is while walking between stages, and a
 * gesture you have to remember is one you will not make. Tapping collapses it, for the
 * evenings where you have already decided.
 */
@Composable
private fun ActRow(act: ProgrammeAct, all: List<ProgrammeAct>, accent: Color) {
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
            Text(act.start, color = accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(act.artist, fontFamily = Serif, fontSize = 16.sp, color = Ink)
                Text(act.stage, color = Muted, fontSize = 12.sp)
            }
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
