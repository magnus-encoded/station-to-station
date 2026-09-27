package io.github.magnusencoded.stationtostation.ui

// PROTOTYPE for #221, throwaway. It lives on branch prototype/221-settings-graph and
// never merges. Question: what should Settings look like once it is the service flow
// graph? Four renderings of the same facts on the existing "settings" route, switched
// from a floating bar that only exists in debug builds:
//   0 Today        the current screen, untouched, for comparison
//   1 Graph        the diagram in the issue: sources → My timeline → sinks
//   2 Unlocks      capability first: what you can do, and which source lights it
//   3 Switchboard  one column, IN → timeline → OUT, each node opens in place with its controls
// The second chip on the bar fakes a state (fresh install, Spotify cap, spent quota)
// so the unlit states can be judged without logging anything out.

import android.Manifest
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
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
private val AmberSoft = Color(0x29E7B24C)

private val VariantNames = listOf("Today", "Graph", "Unlocks", "Switchboard")

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
            else -> SwitchboardVariant(nodes, state, viewModel, onBack)
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
    val spotifyIn = state.spotifyConnected && !fresh && scenario != Scenario.SPOTIFY_CAP
    val scope = state.grantedScope.orEmpty()
    val playlists = spotifyIn && "playlist-modify" in scope

    return listOf(
        ServiceNode(
            id = "setlistfm", name = "setlist.fm", role = Role.SOURCE,
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
            id = "musicbrainz", name = "MusicBrainz", role = Role.SOURCE,
            lit = true,
            status = "No account needed",
            unlocks = listOf("Song titles when a setlist is empty", "Artist names as you type"),
        ),
        ServiceNode(
            id = "clashfinder", name = "clashfinder", role = Role.SOURCE,
            lit = clashfinder,
            status = if (clashfinder) "Signed in as ${state.clashfinderUser}" else "Needs a free account",
            unlocks = listOf("Festival timetables: stages, set times, clashes"),
            nextStep = if (clashfinder) null else "Register, then paste your private key",
        ),
        ServiceNode(
            id = "contacts", name = "Contacts", role = Role.SOURCE,
            lit = friends > 0,
            status = if (friends > 0) "$friends known timeline${if (friends == 1) "" else "s"}" else "Nobody yet",
            unlocks = listOf("Their lines beside yours", "Their photos from nights you shared"),
            nextStep = if (friends > 0) null else "Swipe left from your timeline to swap cards",
        ),
        ServiceNode(
            id = "gallery", name = "Photos", role = Role.SOURCE,
            lit = gallery,
            status = if (gallery) "Allowed" else "Not allowed yet",
            unlocks = listOf("Photos from the night on the gig", "A cover for the playlist"),
            nextStep = if (gallery) null else "Allow photo access",
        ),
        ServiceNode(
            id = "gossip", name = "Gossip", role = Role.SOURCE,
            lit = false,
            status = "Lights only while you're checked in at a gig",
            unlocks = listOf("Log lines from phones nearby"),
            nextStep = "Check in at tonight's gig",
            experimental = true,
        ),
        ServiceNode(
            id = "spotify", name = "Spotify", role = Role.SINK,
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
            id = "calendar", name = "Calendar", role = Role.SINK,
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
// Variant 1: the graph, as drawn in the issue. Sources on top, the timeline in the
// middle, sinks below. An edge is lit only when both of its ends are. Tapping a node
// opens a sheet with what it unlocks and its real controls.

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun GraphVariant(nodes: List<ServiceNode>, state: UiState, viewModel: AppViewModel, onBack: () -> Unit) {
    val centers = remember { mutableStateMapOf<String, Offset>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var open by remember { mutableStateOf<ServiceNode?>(null) }
    val timelineLit = nodes.any { it.role == Role.SOURCE && it.lit }

    PrototypeFrame("Settings", onBack) {
        Text(
            "What feeds your timeline, and where it goes.",
            color = Muted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(20.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
                .drawBehind {
                    val hub = centers["timeline"] ?: return@drawBehind
                    nodes.forEach { node ->
                        val c = centers[node.id] ?: return@forEach
                        val lit = node.lit && timelineLit
                        val (from, to) = if (node.role == Role.SOURCE) c to hub else hub to c
                        val path = Path().apply {
                            moveTo(from.x, from.y)
                            val midY = (from.y + to.y) / 2
                            cubicTo(from.x, midY, to.x, midY, to.x, to.y)
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
                },
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Label("IN")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    nodes.filter { it.role == Role.SOURCE }.forEach { node ->
                        GraphNode(node, centers, origin) { open = node }
                    }
                }
                Spacer(Modifier.height(64.dp))
                Box(
                    Modifier
                        .onGloballyPositioned {
                            centers["timeline"] = it.boundsInRoot().center - origin
                        }
                        .background(if (timelineLit) AmberSoft else Raised, RoundedCornerShape(14.dp))
                        .border(2.dp, if (timelineLit) Amber else Faint, RoundedCornerShape(14.dp))
                        .padding(horizontal = 22.dp, vertical = 14.dp),
                ) {
                    Text(nightsLabel(state), color = Ink, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(64.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    nodes.filter { it.role == Role.SINK }.forEach { node ->
                        GraphNode(node, centers, origin) { open = node }
                    }
                }
                Label("OUT")
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("Dashed is not lit. Tap anything to see what it gives you.", color = Faint, fontSize = 12.sp)
    }

    open?.let { node ->
        ModalBottomSheet(onDismissRequest = { open = null }, containerColor = Raised) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                NodeHeader(node)
                Spacer(Modifier.height(12.dp))
                UnlockList(node)
                Spacer(Modifier.height(16.dp))
                NodeControls(node, state, viewModel)
            }
        }
    }
}

@Composable
private fun GraphNode(
    node: ServiceNode,
    centers: MutableMap<String, Offset>,
    origin: Offset,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .onGloballyPositioned { centers[node.id] = it.boundsInRoot().center - origin }
            .background(if (node.lit) Raised2 else Ground, RoundedCornerShape(50))
            .border(if (node.lit) 1.5.dp else 1.dp, if (node.lit) Amber else Faint, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(node.name, color = if (node.lit) Ink else Muted, fontSize = 14.sp)
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
        "gallery", "calendar" ->
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
