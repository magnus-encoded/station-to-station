package io.github.magnusencoded.stationtostation.ui

// PROTOTYPE for #221, throwaway. It lives on branch prototype/221-settings-graph and
// never merges. Question: what should Settings look like once it is the service flow
// graph? Six renderings of the same facts on the existing "settings" route, switched
// from a floating bar that only exists in debug builds:
//   0 Today        the current screen, untouched, for comparison
//   1 Graph        the diagram in the issue: sources → My timeline → sinks
//   2 Unlocks      capability first: what you can do, and which source lights it
//   3 Switchboard  one column, IN → timeline → OUT, each node opens in place with its controls
//   4 Graph →      the graph read left to right, inputs boxed by kind
//   5 Field        logos in horizontal strips by kind, on a field you pan and pinch
// The second chip on the bar fakes a state (fresh install, Spotify cap, spent quota)
// so the unlit states can be judged without logging anything out.

import android.Manifest
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import io.github.magnusencoded.stationtostation.R
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.magnusencoded.stationtostation.AppViewModel
import io.github.magnusencoded.stationtostation.BuildConfig
import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.data.setlistfm.SHARED_QUOTA_MESSAGE
import kotlinx.coroutines.launch

private val Ground = Color(0xFF0E0B14)
private val Raised = Color(0xFF17121F)
private val Raised2 = Color(0xFF1D1728)
private val Ink = Color(0xFFEDE9F2)
private val Muted = Color(0xFF8B8299)
private val Faint = Color(0xFF5A5368)
private val LineCol = Color(0xFF2E2740)
private val Amber = Color(0xFFE7B24C)

private val VariantNames = listOf("Today", "Graph", "Unlocks", "Switchboard", "Graph →", "Field")

private enum class Scenario(val label: String) {
    REAL("Real state"),
    FRESH("Fresh install"),
    SPOTIFY_CAP("Spotify refused"),
    QUOTA_SPENT("Quota spent"),
}

@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onOpenBleProbe: () -> Unit = {},
    onOpenHandover: () -> Unit = {},
) {
    if (!BuildConfig.DEBUG) {
        SettingsScreenToday(viewModel, onBack, onOpenBleProbe, onOpenHandover)
        return
    }
    var variant by rememberSaveable { mutableIntStateOf(0) }
    var scenario by rememberSaveable { mutableIntStateOf(0) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val nodes = serviceNodes(state, context, Scenario.entries[scenario])

    Box(Modifier.fillMaxSize()) {
        when (variant) {
            0 -> SettingsScreenToday(viewModel, onBack, onOpenBleProbe, onOpenHandover)
            1 -> GraphVariant(nodes, state, viewModel, onBack)
            2 -> UnlocksVariant(nodes, state, viewModel, onBack)
            3 -> SwitchboardVariant(nodes, state, viewModel, onBack)
            4 -> GraphAcrossVariant(nodes, state, viewModel, onBack)
            else -> FieldVariant(nodes, state, viewModel, onBack)
        }
        PrototypeSwitcher(
            label = "$variant · ${VariantNames[variant]}",
            scenario = Scenario.entries[scenario].label,
            onPrev = { variant = (variant + VariantNames.size - 1) % VariantNames.size },
            onNext = { variant = (variant + 1) % VariantNames.size },
            onScenario = { scenario = (scenario + 1) % Scenario.entries.size },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

// ---------------------------------------------------------------------------------
// The facts every variant renders. Only what the app really does: MusicBrainz and
// setlist.fm are called independently, and nothing here feeds anything else except
// through the timeline.

private enum class Role { SOURCE, SINK }

private data class ServiceNode(
    val id: String,
    val name: String,
    val role: Role,
    /** The box it sits in on the left-to-right graph. */
    val group: String,
    val lit: Boolean,
    /** One line: why it is lit or not, right now. */
    val status: String,
    /** What it gives you, each phrased as something you can do. */
    val unlocks: List<String>,
    /** The single step that would light it, when it is unlit. */
    val nextStep: String? = null,
    val experimental: Boolean = false,
)

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun serviceNodes(state: UiState, context: Context, scenario: Scenario): List<ServiceNode> {
    val fresh = scenario == Scenario.FRESH
    val quotaSpent = state.setlistFmSharedQuotaSpent || scenario == Scenario.QUOTA_SPENT
    val setlistFmReady = state.setlistFmReady && !quotaSpent
    val ownKey = state.setlistFmApiKey.isNotBlank()
    val clashfinder = state.clashfinderReady && !fresh
    val friends = if (fresh) 0 else state.friends.size
    val gallery = !fresh && granted(context, Manifest.permission.READ_MEDIA_IMAGES)
    val calendar = !fresh && granted(context, Manifest.permission.WRITE_CALENDAR)
    val location = !fresh && (
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    val spotifyIn = state.spotifyConnected && !fresh && scenario != Scenario.SPOTIFY_CAP
    val scope = state.grantedScope.orEmpty()
    val playlists = spotifyIn && "playlist-modify" in scope

    return listOf(
        ServiceNode(
            id = "setlistfm", name = "setlist.fm", role = Role.SOURCE, group = "Databases",
            lit = setlistFmReady,
            status = when {
                quotaSpent -> "The shared key is spent for today"
                !state.setlistFmReady -> "Needs an API key"
                ownKey -> "Your own key"
                else -> "Shared key, bundled with the app"
            },
            unlocks = listOf("Your attended concerts", "The setlist of any gig", "A friend's line, by username"),
            nextStep = if (quotaSpent || !state.setlistFmReady) "Paste a free key of your own" else null,
        ),
        ServiceNode(
            id = "musicbrainz", name = "MusicBrainz", role = Role.SOURCE, group = "Databases",
            lit = true,
            status = "No account needed",
            unlocks = listOf("Song titles when a setlist is empty", "Artist names as you type"),
        ),
        ServiceNode(
            id = "clashfinder", name = "clashfinder", role = Role.SOURCE, group = "Databases",
            lit = clashfinder,
            status = if (clashfinder) "Signed in as ${state.clashfinderUser}" else "Needs a free account",
            unlocks = listOf("Festival timetables: stages, set times, clashes"),
            nextStep = if (clashfinder) null else "Register, then paste your private key",
        ),
        ServiceNode(
            id = "contacts", name = "Contacts", role = Role.SOURCE, group = "Other phones",
            lit = friends > 0,
            status = if (friends > 0) "$friends known timeline${if (friends == 1) "" else "s"}" else "Nobody yet",
            unlocks = listOf("Their lines beside yours", "Their photos from nights you shared"),
            nextStep = if (friends > 0) null else "Swipe left from your timeline to swap cards",
        ),
        ServiceNode(
            id = "gallery", name = "Photos", role = Role.SOURCE, group = "This phone",
            lit = gallery,
            status = if (gallery) "Allowed" else "Not allowed yet",
            unlocks = listOf("Photos from the night on the gig", "A cover for the playlist"),
            nextStep = if (gallery) null else "Allow photo access",
        ),
        ServiceNode(
            id = "tickets", name = "Ticket PDFs", role = Role.SOURCE, group = "This phone",
            lit = true,
            status = "Share a ticket from your mail or files",
            unlocks = listOf("A gig added from its ticket", "The ticket's code, on the day"),
        ),
        ServiceNode(
            id = "location", name = "Location", role = Role.SOURCE, group = "This phone",
            lit = location,
            status = if (location) "Allowed" else "Not allowed yet",
            unlocks = listOf("An offer to check in when you're at tonight's gig"),
            nextStep = if (location) null else "Allow location access",
        ),
        ServiceNode(
            id = "gossip", name = "Gossip", role = Role.SOURCE, group = "Other phones",
            lit = false,
            status = "Lights only while you're checked in at a gig",
            unlocks = listOf("Log lines from phones nearby"),
            nextStep = "Check in at tonight's gig",
            experimental = true,
        ),
        ServiceNode(
            id = "spotify", name = "Spotify", role = Role.SINK, group = "Services",
            lit = playlists,
            status = when {
                scenario == Scenario.SPOTIFY_CAP -> "Login refused: the shared app admits five people"
                !spotifyIn -> "Not logged in. The shared app admits five people"
                !playlists -> "Logged in, but playlist permission is missing"
                "ugc-image-upload" !in scope -> "Playlists yes, photo covers need a fresh login"
                else -> "Logged in"
            },
            unlocks = listOf("A playlist of the night", "Your photo as its cover"),
            nextStep = when {
                scenario == Scenario.SPOTIFY_CAP -> "Use a Spotify app of your own"
                !spotifyIn -> "Log in with Spotify"
                !playlists -> "Log out and in again"
                else -> null
            },
        ),
        ServiceNode(
            id = "calendar", name = "Calendar", role = Role.SINK, group = "This phone",
            lit = calendar,
            status = if (calendar) "Allowed" else "Not allowed yet",
            unlocks = listOf("An upcoming gig in your calendar"),
            nextStep = if (calendar) null else "Allow calendar access",
        ),
    )
}

private fun nightsLabel(state: UiState): String {
    val n = state.showsByFriend[state.mySetlistFmUser]?.size
    return if (n == null) "My timeline" else "My timeline · $n nights"
}

// ---------------------------------------------------------------------------------
// Both graphs draw their edges behind the nodes. Every node reports its bounds in root
// coordinates, and the container subtracts its own root position at draw time, so the
// two are always read in the same frame. Edges run into one join point before the
// timeline, a straight trunk runs under it, and they split again after it.

private class GraphGeometry {
    val rects = mutableStateMapOf<String, Rect>()
    var origin by mutableStateOf(Offset.Zero)
    fun local(id: String): Rect? = rects[id]?.translate(-origin)
}

private fun Modifier.reportBounds(geo: GraphGeometry, id: String) =
    onGloballyPositioned { geo.rects[id] = it.boundsInRoot() }

private fun DrawScope.edge(from: Offset, to: Offset, lit: Boolean, across: Boolean) {
    val path = Path().apply {
        moveTo(from.x, from.y)
        if (across) {
            val midX = (from.x + to.x) / 2
            cubicTo(midX, from.y, midX, to.y, to.x, to.y)
        } else {
            val midY = (from.y + to.y) / 2
            cubicTo(from.x, midY, to.x, midY, to.x, to.y)
        }
    }
    drawPath(
        path,
        color = if (lit) Amber else Faint,
        style = Stroke(
            width = if (lit) 2.dp.toPx() else 1.dp.toPx(),
            pathEffect = if (lit) null else PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
        ),
    )
}

private fun Modifier.graphEdges(
    geo: GraphGeometry,
    nodes: List<ServiceNode>,
    timelineLit: Boolean,
    across: Boolean,
) = onGloballyPositioned { geo.origin = it.boundsInRoot().topLeft }
    .drawBehind {
        val hub = geo.local("timeline") ?: return@drawBehind
        val (join, split) = joinAndSplit(hub, across)
        // The trunk: straight, and under the timeline box.
        edge(join, split, timelineLit, across)
        if (!across) branches(geo, nodes, timelineLit, join, split, across = false)
    }
    // Across, the category boxes would hide where each branch leaves its node, so the
    // branches go on top. They never cross a node or the timeline box on that layout.
    .drawWithContent {
        drawContent()
        val hub = geo.local("timeline") ?: return@drawWithContent
        val (join, split) = joinAndSplit(hub, across)
        if (across) branches(geo, nodes, timelineLit, join, split, across = true)
    }

private fun DrawScope.joinAndSplit(hub: Rect, across: Boolean): Pair<Offset, Offset> {
    val gap = 18.dp.toPx()
    return if (across) {
        Offset(hub.left - gap, hub.center.y) to Offset(hub.right + gap, hub.center.y)
    } else {
        Offset(hub.center.x, hub.top - gap) to Offset(hub.center.x, hub.bottom + gap)
    }
}

private fun DrawScope.branches(
    geo: GraphGeometry,
    nodes: List<ServiceNode>,
    timelineLit: Boolean,
    join: Offset,
    split: Offset,
    across: Boolean,
) {
    nodes.forEach { node ->
        val r = geo.local(node.id) ?: return@forEach
        if (node.role == Role.SOURCE) {
            edge(if (across) r.centerRight else r.bottomCenter, join, node.lit, across)
        } else {
            edge(split, if (across) r.centerLeft else r.topCenter, node.lit && timelineLit, across)
        }
    }
}

private val TimelineLitFill = Color(0xFF2A2215)

@Composable
private fun TimelineBox(state: UiState, lit: Boolean, geo: GraphGeometry, modifier: Modifier = Modifier, stacked: Boolean = false) {
    Box(
        modifier
            .reportBounds(geo, "timeline")
            .background(if (lit) TimelineLitFill else Raised, RoundedCornerShape(14.dp))
            .border(2.dp, if (lit) Amber else Faint, RoundedCornerShape(14.dp))
            .padding(horizontal = if (stacked) 8.dp else 22.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (stacked) {
            val n = state.showsByFriend[state.mySetlistFmUser]?.size
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("My", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("time-\nline", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 16.sp)
                if (n != null) {
                    Spacer(Modifier.height(6.dp))
                    Text("$n", color = Amber, fontSize = 13.sp)
                    Text("nights", color = Muted, fontSize = 11.sp)
                }
            }
        } else {
            Text(nightsLabel(state), color = Ink, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun GraphNode(
    node: ServiceNode,
    geo: GraphGeometry,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .reportBounds(geo, node.id)
            .background(if (node.lit) Raised2 else Ground, RoundedCornerShape(50))
            .border(if (node.lit) 1.5.dp else 1.dp, if (node.lit) Amber else Faint, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            node.name, color = if (node.lit) Ink else Muted, fontSize = if (compact) 13.sp else 14.sp,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodeSheet(node: ServiceNode, state: UiState, viewModel: AppViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Raised) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            NodeHeader(node)
            Spacer(Modifier.height(12.dp))
            UnlockList(node)
            Spacer(Modifier.height(16.dp))
            NodeControls(node, state, viewModel)
        }
    }
}

// Variant 1: the graph, as drawn in the issue. Sources on top, the timeline in the
// middle, sinks below. Tapping a node opens a sheet with what it unlocks and its
// real controls.

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GraphVariant(nodes: List<ServiceNode>, state: UiState, viewModel: AppViewModel, onBack: () -> Unit) {
    val geo = remember { GraphGeometry() }
    var open by remember { mutableStateOf<ServiceNode?>(null) }
    val timelineLit = nodes.any { it.role == Role.SOURCE && it.lit }

    PrototypeFrame("Settings", onBack) {
        Text("What feeds your timeline, and where it goes.", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(20.dp))
        Column(
            Modifier.fillMaxWidth().graphEdges(geo, nodes, timelineLit, across = false),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Label("IN")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                nodes.filter { it.role == Role.SOURCE }.forEach { node ->
                    GraphNode(node, geo) { open = node }
                }
            }
            Spacer(Modifier.height(72.dp))
            TimelineBox(state, timelineLit, geo)
            Spacer(Modifier.height(72.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth(),
            ) {
                nodes.filter { it.role == Role.SINK }.forEach { node ->
                    GraphNode(node, geo) { open = node }
                }
            }
            Label("OUT")
        }
        Spacer(Modifier.height(24.dp))
        Text("Dashed is not lit. Tap anything to see what it gives you.", color = Faint, fontSize = 12.sp)
    }

    open?.let { NodeSheet(it, state, viewModel) { open = null } }
}

// Variant 4: the same graph read left to right, with the inputs boxed by kind. The
// box is only a background: the edges still start at each node.

private val SourceGroups = listOf("Databases", "This phone", "Other phones")
private val SinkGroups = listOf("Services", "This phone")

@Composable
private fun GraphAcrossVariant(nodes: List<ServiceNode>, state: UiState, viewModel: AppViewModel, onBack: () -> Unit) {
    val geo = remember { GraphGeometry() }
    var open by remember { mutableStateOf<ServiceNode?>(null) }
    val timelineLit = nodes.any { it.role == Role.SOURCE && it.lit }

    @Composable
    fun Groups(role: Role, order: List<String>) {
        order.forEach { group ->
            val members = nodes.filter { it.role == role && it.group == group }
            if (members.isNotEmpty()) {
                GroupBox(group) {
                    members.forEach { node ->
                        GraphNode(node, geo, Modifier.fillMaxWidth(), compact = true) { open = node }
                    }
                }
            }
        }
    }

    PrototypeFrame("Settings", onBack) {
        Text("What feeds your timeline, and where it goes.", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth().graphEdges(geo, nodes, timelineLit, across = true),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1.4f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Label("IN")
                Groups(Role.SOURCE, SourceGroups)
            }
            Spacer(Modifier.width(46.dp))
            TimelineBox(state, timelineLit, geo, Modifier.width(62.dp).height(170.dp), stacked = true)
            Spacer(Modifier.width(30.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Label("OUT")
                Groups(Role.SINK, SinkGroups)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("Dashed is not lit. Tap anything to see what it gives you.", color = Faint, fontSize = 12.sp)
    }

    open?.let { NodeSheet(it, state, viewModel) { open = null } }
}

@Composable
private fun GroupBox(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Raised, RoundedCornerShape(12.dp))
            .border(1.dp, LineCol, RoundedCornerShape(12.dp))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title.uppercase(), color = Faint, fontSize = 10.sp, letterSpacing = 1.2.sp,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
        content()
    }
}

// ---------------------------------------------------------------------------------
// Variant 2: capability first. The rows are things you can do. Each says which source
// lights it, and an unlit row carries its one step inline. The sources themselves are
// an afterthought at the bottom.

@Composable
private fun UnlocksVariant(nodes: List<ServiceNode>, state: UiState, viewModel: AppViewModel, onBack: () -> Unit) {
    var open by remember { mutableStateOf<String?>(null) }
    val rows = nodes.flatMap { node -> node.unlocks.map { it to node } }
    val (lit, unlit) = rows.partition { it.second.lit }

    PrototypeFrame("What this app can do", onBack) {
        Text(
            "${lit.size} of ${rows.size} working on this phone.",
            color = Muted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(20.dp))
        Label("WORKING")
        lit.forEach { (what, node) -> CapabilityRow(what, node, expanded = false) {} }
        Spacer(Modifier.height(20.dp))
        Label("NOT YET")
        unlit.forEach { (what, node) ->
            val key = node.id + what
            CapabilityRow(what, node, expanded = open == key) { open = if (open == key) null else key }
            if (open == key) {
                Box(Modifier.padding(start = 22.dp, bottom = 12.dp)) { NodeControls(node, state, viewModel) }
            }
        }
        Spacer(Modifier.height(28.dp))
        Label("SOURCES")
        Text(
            nodes.joinToString("  ·  ") { (if (it.lit) "● " else "○ ") + it.name },
            color = Muted, fontSize = 12.sp,
        )
    }
}

@Composable
private fun CapabilityRow(what: String, node: ServiceNode, expanded: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !node.lit, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Dot(node.lit)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(what, color = if (node.lit) Ink else Muted, fontSize = 15.sp)
            Text(
                if (node.lit) "via ${node.name}" else "${node.name}: ${node.nextStep ?: node.status}",
                color = if (node.lit) Faint else Amber.copy(alpha = 0.8f),
                fontSize = 12.sp,
            )
        }
        if (!node.lit) Text(if (expanded) "−" else "+", color = Muted, fontSize = 18.sp)
    }
}

// ---------------------------------------------------------------------------------
// Variant 3: one column you read top to bottom, a line running through it. Every node
// is a row on that line and opens in place with its controls, so the diagram *is* the
// control surface, with no sheet and no second screen.

@Composable
private fun SwitchboardVariant(nodes: List<ServiceNode>, state: UiState, viewModel: AppViewModel, onBack: () -> Unit) {
    var open by remember { mutableStateOf<String?>(null) }
    val timelineLit = nodes.any { it.role == Role.SOURCE && it.lit }

    PrototypeFrame("Settings", onBack) {
        Label("IN")
        nodes.filter { it.role == Role.SOURCE }.forEach { node ->
            SwitchboardRow(node, open == node.id, state, viewModel) { open = if (open == node.id) null else node.id }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(22.dp).background(if (timelineLit) Amber else Faint, CircleShape),
            )
            Spacer(Modifier.width(14.dp))
            Text(nightsLabel(state), color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        Spacer(Modifier.height(8.dp))
        Label("OUT")
        nodes.filter { it.role == Role.SINK }.forEach { node ->
            SwitchboardRow(node, open == node.id, state, viewModel) { open = if (open == node.id) null else node.id }
        }
    }
}

@Composable
private fun SwitchboardRow(
    node: ServiceNode,
    expanded: Boolean,
    state: UiState,
    viewModel: AppViewModel,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
        // The spine: a segment of the line, lit when this node feeds it.
        Box(Modifier.width(22.dp), contentAlignment = Alignment.TopCenter) {
            Box(
                Modifier
                    .width(if (node.lit) 3.dp else 1.dp)
                    .height(if (expanded) 320.dp else 64.dp)
                    .background(if (node.lit) Amber else LineCol),
            )
            Box(
                Modifier
                    .padding(top = 20.dp)
                    .size(12.dp)
                    .background(if (node.lit) Amber else Ground, CircleShape)
                    .border(1.dp, if (node.lit) Amber else Faint, CircleShape),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(vertical = 12.dp),
            ) {
                NodeHeader(node)
            }
            if (expanded) {
                UnlockList(node)
                Spacer(Modifier.height(12.dp))
                NodeControls(node, state, viewModel)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------------
// Shared pieces. The controls are the real ones and call the real view model.

@Composable
private fun NodeHeader(node: ServiceNode) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(node.name, color = if (node.lit) Ink else Muted, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (node.experimental) {
            Spacer(Modifier.width(8.dp))
            Text("Experimental", color = Faint, fontSize = 11.sp)
        }
    }
    Text(node.status, color = if (node.lit) Amber else Faint, fontSize = 12.sp)
}

@Composable
private fun UnlockList(node: ServiceNode) {
    node.unlocks.forEach {
        Text((if (node.lit) "✓ " else "· ") + it, color = if (node.lit) Ink else Muted, fontSize = 13.sp)
    }
}

@Composable
private fun NodeControls(node: ServiceNode, state: UiState, viewModel: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val open = { url: String -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    val appSettings = {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        )
    }
    when (node.id) {
        "setlistfm" -> {
            var key by remember(state.setlistFmApiKey) { mutableStateOf(state.setlistFmApiKey) }
            if (state.setlistFmSharedQuotaSpent) Text(SHARED_QUOTA_MESSAGE, color = Muted, fontSize = 12.sp)
            KeyField("setlist.fm API key", key, state.bundledSetlistFmHint) { key = it }
            Row {
                TextButton(onClick = { open("https://www.setlist.fm/settings/api") }) { Text("Get a key") }
                Spacer(Modifier.weight(1f))
                AmberButton("Save") { viewModel.saveSettings(key, state.spotifyClientId) }
            }
        }
        "clashfinder" -> {
            var user by remember(state.clashfinderUser) { mutableStateOf(state.clashfinderUser) }
            var key by remember(state.clashfinderPrivateKey) { mutableStateOf(state.clashfinderPrivateKey) }
            KeyField("clashfinder username", user, "") { user = it }
            KeyField("clashfinder private key", key, "") { key = it }
            Row {
                TextButton(onClick = { open("https://clashfinder.com/m/account") }) { Text("Register") }
                Spacer(Modifier.weight(1f))
                AmberButton("Save") { viewModel.saveClashfinderCredentials(user, key) }
            }
        }
        "spotify" -> {
            var clientId by remember(state.spotifyClientId) { mutableStateOf(state.spotifyClientId) }
            if (state.spotifyConnected) {
                OutlinedButton(onClick = { viewModel.disconnectSpotify() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Log out", color = Ink)
                }
            } else {
                AmberButton("Log in with Spotify", Modifier.fillMaxWidth()) {
                    scope.launch { startSpotifyLogin(context, viewModel) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Or use a Spotify app of your own:", color = Muted, fontSize = 12.sp)
            KeyField("Spotify Client ID", clientId, state.bundledSpotifyHint) { clientId = it }
            Row {
                TextButton(onClick = { open("https://magnus-encoded.github.io/station-to-station/") }) {
                    Text("Step by step")
                }
                Spacer(Modifier.weight(1f))
                AmberButton("Save") { viewModel.saveSettings(state.setlistFmApiKey, clientId) }
            }
        }
        "gallery", "calendar", "location" ->
            if (!node.lit) AmberButton("Open app permissions") { appSettings() }
        "contacts" -> {
            state.friends.forEach { friend ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(friend.name, color = Ink, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.removeFriend(friend) }) { Text("Remove") }
                }
            }
            if (state.friends.isEmpty()) Text(node.nextStep.orEmpty(), color = Muted, fontSize = 13.sp)
        }
        "tickets" -> Text(
            "Share a ticket PDF to Station to Station from your mail or files app.",
            color = Muted, fontSize = 13.sp,
        )
        "gossip" -> Text(
            "Check in to share small public observations with nearby phones. Delivery is best effort.",
            color = Muted, fontSize = 13.sp,
        )
        else -> Text("Nothing to set up.", color = Faint, fontSize = 13.sp)
    }
}

@Composable
private fun KeyField(label: String, value: String, hint: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { if (hint.isNotEmpty()) Text(hint, color = Faint) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AmberButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color(0xFF241A08)),
        modifier = modifier,
    ) { Text(text) }
}

@Composable
private fun Dot(lit: Boolean) {
    Box(
        Modifier
            .padding(top = 5.dp)
            .size(10.dp)
            .background(if (lit) Amber else Ground, CircleShape)
            .border(1.dp, if (lit) Amber else Faint, CircleShape),
    )
}

@Composable
private fun Label(text: String) {
    Text(
        text, color = Faint, fontSize = 11.sp, letterSpacing = 1.5.sp,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun PrototypeFrame(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Ground)
            .statusBarsPadding()
            .swipeRightToBack(onBack = onBack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = Ink, fontSize = 28.sp, modifier = Modifier.clickable(onClick = onBack).padding(end = 16.dp))
            Text(title, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        content()
        // Room for the switcher, so it never covers the last row.
        Spacer(Modifier.height(120.dp))
    }
}

// ---------------------------------------------------------------------------------
// The switcher. White on a dark app so it reads as not part of any design.

@Composable
private fun PrototypeSwitcher(
    label: String,
    scenario: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onScenario: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color.White,
        contentColor = Color.Black,
        shape = RoundedCornerShape(50),
        shadowElevation = 8.dp,
        modifier = modifier.navigationBarsPadding().padding(bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
            TextButton(onClick = onPrev) { Text("‹", color = Color.Black, fontSize = 20.sp) }
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            TextButton(onClick = onNext) { Text("›", color = Color.Black, fontSize = 20.sp) }
            Box(Modifier.width(1.dp).height(20.dp).background(Color.LightGray))
            TextButton(onClick = onScenario) { Text(scenario, color = Color.DarkGray, fontSize = 12.sp) }
        }
    }
}

// ---------------------------------------------------------------------------------
// Variant 5: a field you move around in. Logos instead of words, left to right, the
// inputs in one horizontal strip per kind, and Spotify and Calendar as alcoves on the
// right. Drag to pan, pinch to zoom, double-tap to fit. Status lives in the sheet a
// tap opens, not on the field. Brand logos are never dimmed (both brands' guidelines
// forbid it): an unlit source says so with its ring, its label and its line.
//
// Inside a strip, each tile's line drops into a lane of its own under the tiles and
// runs out of the strip's right end. The leftmost tile takes the lowest lane, so no
// line ever crosses another or a tile.

private const val TileDp = 56f
private const val ColDp = 92f
private const val StripPad = 8f
private const val StripHeader = 30f
private const val LaneGap = 6f

private class FieldRegion(val title: String, val x: Float, val y: Float, val ids: List<String>) {
    val w = StripPad * 2 + ids.size * ColDp
    val h = 112f + ids.size * LaneGap + 6f
    fun center(i: Int) = Offset(x + StripPad + i * ColDp + ColDp / 2, y + StripHeader + TileDp / 2)
    fun laneY(i: Int) = y + h - 10f - i * LaneGap
}

private val FieldRegions = listOf(
    FieldRegion("Databases", 24f, 24f, listOf("setlistfm", "musicbrainz", "clashfinder")),
    FieldRegion("This phone", 24f, 188f, listOf("gallery", "tickets", "location")),
    FieldRegion("Other phones", 24f, 352f, listOf("contacts", "gossip")),
    FieldRegion("Services", 548f, 100f, listOf("spotify")),
    FieldRegion("This phone", 548f, 262f, listOf("calendar")),
)

private const val FieldW = 680f
private const val FieldH = 510f

// The timeline box, in field dp.
private const val HubX = 400f
private const val HubY = 150f
private const val HubW = 72f
private const val HubH = 210f

/** A line along [points] with its corners rounded, then a curve on to [end]. */
private fun DrawScope.lane(points: List<Offset>, end: Offset, lit: Boolean) {
    val r = 8.dp.toPx()
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size - 1) {
            val prev = points[i - 1]
            val at = points[i]
            val next = points[i + 1]
            val inDir = (at - prev) / (at - prev).getDistance()
            val outDir = (next - at) / (next - at).getDistance()
            val a = at - inDir * r
            val b = at + outDir * r
            lineTo(a.x, a.y)
            quadraticBezierTo(at.x, at.y, b.x, b.y)
        }
        val last = points.last()
        lineTo(last.x, last.y)
        val midX = (last.x + end.x) / 2
        cubicTo(midX, last.y, midX, end.y, end.x, end.y)
    }
    drawPath(
        path,
        color = if (lit) Amber else Faint,
        style = Stroke(
            width = if (lit) 2.dp.toPx() else 1.dp.toPx(),
            pathEffect = if (lit) null else PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
        ),
    )
}

@Composable
private fun FieldVariant(nodes: List<ServiceNode>, state: UiState, viewModel: AppViewModel, onBack: () -> Unit) {
    var open by remember { mutableStateOf<ServiceNode?>(null) }
    val byId = nodes.associateBy { it.id }
    val timelineLit = nodes.any { it.role == Role.SOURCE && it.lit }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ground)
            .statusBarsPadding(),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = Ink, fontSize = 28.sp, modifier = Modifier.clickable(onClick = onBack).padding(end = 16.dp))
            Text("Settings", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("drag · pinch · double-tap to fit", color = Faint, fontSize = 11.sp)
        }
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds(),
        ) {
            val density = LocalDensity.current
            val availW = with(density) { maxWidth.toPx() }
            val availH = with(density) { maxHeight.toPx() }
            val fieldW = with(density) { FieldW.dp.toPx() }
            val fieldH = with(density) { FieldH.dp.toPx() }
            // The whole field, centred, whichever way the phone is held.
            val fit = minOf(availW / fieldW, availH / fieldH, 1f)
            val fitOffset = Offset((availW - fieldW * fit) / 2f, (availH - fieldH * fit) / 2f)
            var scale by remember(fit) { mutableFloatStateOf(fit) }
            var offset by remember(fit) { mutableStateOf(fitOffset) }

            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(fit) {
                        detectTapGestures(onDoubleTap = {
                            scale = fit
                            offset = fitOffset
                        })
                    }
                    .pointerInput(fit) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val next = (scale * zoom).coerceIn(0.35f, 2.5f)
                            offset = centroid - (centroid - offset) * (next / scale) + pan
                            scale = next
                        }
                    },
            ) {
                Box(
                    Modifier
                        .wrapContentSize(Alignment.TopStart, unbounded = true)
                        .size(FieldW.dp, FieldH.dp)
                        .graphicsLayer {
                            transformOrigin = TransformOrigin(0f, 0f)
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val px = { v: Float -> v.dp.toPx() }
                        val at = { o: Offset -> Offset(px(o.x), px(o.y)) }
                        FieldRegions.forEach { r ->
                            drawRoundRect(
                                Raised, topLeft = Offset(px(r.x), px(r.y)), size = Size(px(r.w), px(r.h)),
                                cornerRadius = CornerRadius(px(14f)),
                            )
                            drawRoundRect(
                                LineCol, topLeft = Offset(px(r.x), px(r.y)), size = Size(px(r.w), px(r.h)),
                                cornerRadius = CornerRadius(px(14f)), style = Stroke(1.dp.toPx()),
                            )
                        }
                        val join = Offset(px(HubX - 22f), px(HubY + HubH / 2))
                        val split = Offset(px(HubX + HubW + 22f), px(HubY + HubH / 2))
                        edge(join, split, timelineLit, across = true)
                        FieldRegions.forEach { r ->
                            r.ids.forEachIndexed { i, id ->
                                val node = byId[id] ?: return@forEachIndexed
                                val c = r.center(i)
                                if (node.role == Role.SOURCE) {
                                    val dropX = c.x + TileDp / 2 + 10f
                                    val laneY = r.laneY(i)
                                    lane(
                                        listOf(
                                            at(Offset(c.x + TileDp / 2, c.y)),
                                            at(Offset(dropX, c.y)),
                                            at(Offset(dropX, laneY)),
                                            at(Offset(r.x + r.w, laneY)),
                                        ),
                                        join, node.lit,
                                    )
                                } else {
                                    edge(split, at(Offset(c.x - TileDp / 2, c.y)), node.lit && timelineLit, across = true)
                                }
                            }
                        }
                    }
                    FieldRegions.forEach { r ->
                        Text(
                            r.title.uppercase(), color = Faint, fontSize = 10.sp, letterSpacing = 1.2.sp,
                            modifier = Modifier.offset(r.x.dp + 12.dp, r.y.dp + 9.dp),
                        )
                        r.ids.forEachIndexed { i, id ->
                            val node = byId[id] ?: return@forEachIndexed
                            val c = r.center(i)
                            FieldTile(node, Modifier.offset((c.x - ColDp / 2).dp, (c.y - TileDp / 2).dp)) { open = node }
                        }
                    }
                    TimelineBox(
                        state, timelineLit, remember { GraphGeometry() },
                        Modifier.offset(HubX.dp, HubY.dp).size(HubW.dp, HubH.dp), stacked = true,
                    )
                }
            }
        }
    }

    open?.let { NodeSheet(it, state, viewModel) { open = null } }
}

@Composable
private fun FieldTile(node: ServiceNode, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.width(ColDp.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(TileDp.dp)
                .border(if (node.lit) 2.dp else 1.dp, if (node.lit) Amber else Faint, shape)
                .padding(3.dp)
                .clip(RoundedCornerShape(11.dp))
                // Pure black inside the ring: the one background Spotify allows its green on,
                // and the most contrast for every other mark.
                .background(Color.Black)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            NodeLogo(node)
        }
        Spacer(Modifier.height(4.dp))
        Text(node.name, color = if (node.lit) Ink else Muted, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun NodeLogo(node: ServiceNode) {
    val tint = if (node.lit) Ink else Muted
    when (node.id) {
        // App icons that are their own tile: they fill it.
        "setlistfm" -> Image(painterResource(R.drawable.proto221_setlistfm), "setlist.fm", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        "clashfinder" -> Image(painterResource(R.drawable.proto221_clashfinder), "clashfinder", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        // Marks that need clear space around them: Spotify asks for half the icon's height.
        "spotify" -> Image(painterResource(R.drawable.proto221_spotify), "Spotify", Modifier.size(25.dp))
        "musicbrainz" -> Image(painterResource(R.drawable.proto221_musicbrainz), "MusicBrainz", Modifier.height(28.dp))
        "gallery" -> Icon(Icons.Outlined.PhotoLibrary, "Photos", tint = tint, modifier = Modifier.size(26.dp))
        "tickets" -> Icon(Icons.Outlined.ConfirmationNumber, "Ticket PDFs", tint = tint, modifier = Modifier.size(26.dp))
        "location" -> Icon(Icons.Outlined.LocationOn, "Location", tint = tint, modifier = Modifier.size(26.dp))
        "contacts" -> Icon(Icons.Outlined.People, "Contacts", tint = tint, modifier = Modifier.size(26.dp))
        "gossip" -> Icon(Icons.Outlined.Sensors, "Gossip", tint = tint, modifier = Modifier.size(26.dp))
        "calendar" -> Icon(Icons.Outlined.CalendarMonth, "Calendar", tint = tint, modifier = Modifier.size(26.dp))
        else -> Text(node.name.take(1), color = tint)
    }
}
