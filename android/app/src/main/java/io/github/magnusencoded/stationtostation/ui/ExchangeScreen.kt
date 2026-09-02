package io.github.magnusencoded.stationtostation.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import io.github.magnusencoded.stationtostation.AppViewModel
import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.exchange.ExchangePeer
import kotlinx.coroutines.delay

private val Ground = Color(0xFF0E0B14)
private val Raised = Color(0xFF17121F)
private val Raised2 = Color(0xFF1D1728)
private val LineLit = Color(0xFF4A3F63)
private val Ink = Color(0xFFEDE9F2)
private val Muted = Color(0xFF8B8299)
private val Faint = Color(0xFF5A5368)
private val Amber = Color(0xFFE7B24C)
private val AmberSoft = Color(0x29E7B24C)
private val Slate = Color(0xFF6D7E9B)
private val Serif = FontFamily.Serif

/**
 * One way to meet someone. Every radio runs behind this — Nearby between Androids, BLE
 * everywhere else, QR when they sulk — and the screen never says which found a person.
 * The moment is one thing: a row appears with a real name, you tap, "Connecting with
 * dizzi90", you are contacts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExchangeScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onConnected: () -> Unit,
    onViewFriend: (Friend) -> Unit,
    onSetUsername: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Permission is asked for here and nowhere else: this is the only screen that needs
    // the radios, and opening it is the user saying they want to be found.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.startExchange() }
    LaunchedEffect(Unit) {
        permissionLauncher.launch(viewModel.exchangePermissions().toTypedArray())
    }
    // Being discoverable is opted into by standing here, not a background state — and
    // that goes for #257's LAN reconcile too, which is this screen's fourth radio rather
    // than a service: it starts when the screen appears and is gone when it does. It used
    // to sit on the activity's onStart/onEnd, which made the phone discoverable on the
    // network for as long as the app was merely open — an app-foreground-wide trigger,
    // which is exactly what #257 excludes.
    DisposableEffect(Unit) {
        viewModel.startContactExchange()
        onDispose {
            viewModel.stopExchange()
            viewModel.stopContactExchange()
        }
    }
    // Whoever tapped, both phones leave for the woven view once the swap lands.
    LaunchedEffect(state.justConnected) { if (state.justConnected) onConnected() }

    // My share card for the QR/link fallback; null until I've set my username.
    var cardUri by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.mySetlistFmUser, state.spotifyConnected) {
        cardUri = viewModel.myCardUri()?.toString()
    }

    val peers = state.exchangePeers

    Scaffold(
        containerColor = Ground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ground, titleContentColor = Ink),
                title = { Text("Connect a timeline", fontFamily = Serif, fontSize = 18.sp, color = Ink) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Faint)
                    }
                },
            )
        },
    ) { padding ->
        // The refresh completes the moment the radios restart — there is no result to wait
        // for — so the spinner is a beat of acknowledgement, not a progress bar.
        var refreshing by remember { mutableStateOf(false) }
        LaunchedEffect(refreshing) {
            if (refreshing) { delay(900); refreshing = false }
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; viewModel.restartExchange() },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .swipeRightToBack(onBack = onBack)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val connecting = state.connectingWith
                // With no username there is no card, so `startExchange` starts no radio —
                // and a radar pulsing over "Looking for people around you…" would be
                // describing a search that is not happening. #225 made the card half of
                // this screen honest for an account-less user; this is the other half.
                val discoverable = state.mySetlistFmUser.isNotBlank()
                when {
                    connecting != null -> ConnectingBeat(connecting)
                    discoverable -> LookingForPeople(
                        peers = peers,
                        discovering = state.discovering,
                        onConnect = viewModel::connectWith,
                    )
                    else -> Spacer(Modifier.height(20.dp))
                }

                // My card, always here rather than behind a toggle: the radar above never
                // stops or hands off to it, so there is nothing to reveal — you just scroll.
                if (connecting == null) {
                    Spacer(Modifier.height(28.dp))
                    Text(
                        // "OR" only if there was a first option. Without a username the
                        // radios never ran, so this is the whole screen, not the fallback.
                        if (discoverable) "OR HAND OVER YOUR CARD" else "HANDING CARDS OVER",
                        color = Faint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.align(Alignment.Start).padding(bottom = 8.dp),
                    )
                    QrExchange(cardUri = cardUri, username = state.mySetlistFmUser, onSetUsername = onSetUsername)
                }

                // Your people: contacts and followed lines already on the timeline.
                if (state.friends.isNotEmpty()) {
                    Spacer(Modifier.height(28.dp))
                    Text(
                        "YOUR PEOPLE",
                        color = Faint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.align(Alignment.Start).padding(bottom = 8.dp),
                    )
                    state.friends.forEach { friend ->
                        FriendRow(friend, onClick = { onViewFriend(friend) })
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** The ambient "looking around you" state and the live list — one radar, one list of rows. */
@Composable
private fun LookingForPeople(
    peers: List<ExchangePeer>,
    discovering: Boolean,
    onConnect: (ExchangePeer) -> Unit,
) {
    Spacer(Modifier.height(20.dp))
    Text(
        "Stand next to someone with the app open. When they appear, add them and your " +
            "timelines weave together.",
        color = Muted,
        fontSize = 13.sp,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    Spacer(Modifier.height(24.dp))
    Radar(active = discovering || peers.isEmpty())
    Spacer(Modifier.height(28.dp))
    if (peers.isEmpty()) {
        Text("Looking for people around you…", color = Faint, fontSize = 13.sp)
    } else {
        Text(
            "NEARBY",
            color = Faint,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        peers.forEach { peer -> PeerRow(peer, onConnect = { onConnect(peer) }) }
    }
}

/** Row → "Connecting with dizzi90" → connected: the middle beat, whether 200ms or 2s. */
@Composable
private fun ConnectingBeat(name: String) {
    Spacer(Modifier.height(60.dp))
    CircularProgressIndicator(color = Amber)
    Spacer(Modifier.height(20.dp))
    Text("Connecting with $name", color = Ink, fontFamily = Serif, fontSize = 18.sp)
    Spacer(Modifier.height(60.dp))
}

/** A slow pulse to say the app is listening for other phones. */
@Composable
private fun Radar(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "radar")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Restart),
        label = "pulse",
    )
    Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
        if (active) {
            Box(
                Modifier
                    .size((60 + pulse * 60).dp)
                    .alpha((1f - pulse) * 0.5f)
                    .clip(CircleShape)
                    .border(1.5.dp, Amber, CircleShape),
            )
        }
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(AmberSoft).border(1.5.dp, Amber, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("◦", color = Amber, fontSize = 20.sp) }
    }
}

@Composable
private fun PeerRow(peer: ExchangePeer, onConnect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Raised)
            .border(1.dp, LineLit, RoundedCornerShape(12.dp))
            .clickable(onClick = onConnect)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(Raised2).border(1.5.dp, Slate, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(peer.name.take(1).uppercase(), color = Slate, fontSize = 15.sp, fontFamily = Serif) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(peer.name, color = Ink, fontFamily = Serif, fontSize = 16.sp)
            // Only shown once the card is in hand; a BLE peer is a name until then.
            peer.setlistfm?.let { Text("@$it", color = Muted, fontSize = 12.sp) }
        }
        Text("Add ›", color = Amber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * My card as a QR code. No scan-mode toggle: earlier this had a button that claimed to
 * "scan theirs instead" but only hid your own code — scanning needs no in-app camera, the
 * friend deep link is registered so any phone camera that reads a code opens the app, and
 * that happens with your own code left on screen just as well as with it hidden.
 */
@Composable
private fun QrExchange(cardUri: String?, username: String, onSetUsername: () -> Unit) {
    val context = LocalContext.current
    if (cardUri == null) {
        // The exchange is not symmetric, and this used to read as though it were: one
        // prompt asking for a username, which for a user who has decided against a
        // setlist.fm account is a request they cannot satisfy (#225).
        //
        // The asymmetry is honest rather than a hole to fill. Weaving a line is a
        // subscription to someone's *public setlist.fm data* — with no account there
        // is no public data for a card to point at, so a card of theirs would resolve
        // to nothing, and ADR-0003 rules out a backend that could make it resolve. So
        // say what they can do, which is the half that works.
        Spacer(Modifier.height(10.dp))
        Text(
            "You can take their card. Point your camera at their code and their " +
                "line joins yours.",
            color = Muted,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Handing yours over needs a setlist.fm username, because a card points at " +
                "a public history and yours is on this phone only.",
            color = Faint,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onSetUsername) { Text("Add your username", color = Amber) }
        return
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        // Amber-on-Ground rather than the usual black-on-white block: a QR only needs
        // enough luminance contrast to decode, not literal black and white, so it can wear
        // the same palette as everything around it.
        val qr = remember(cardUri) {
            runCatching { qrBitmap(cardUri, 640, ink = Amber.toArgb(), paper = Ground.toArgb()) }.getOrNull()
        }
        if (qr != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Ground)
                    .border(1.dp, LineLit, RoundedCornerShape(14.dp))
                    .padding(14.dp),
            ) {
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = "Your card as a QR code",
                    modifier = Modifier.size(220.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text("@$username", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { shareLink(context, cardUri) }) {
            Text("Share a link instead", color = Amber)
        }
    }
}

@Composable
private fun FriendRow(friend: Friend, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Raised)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(friend.name, color = Ink, fontFamily = Serif, fontSize = 16.sp)
            Text("@${friend.setlistfm}", color = Muted, fontSize = 12.sp)
        }
        Text("View timeline ›", color = Amber, fontSize = 13.sp)
    }
}

/** A friend's collection timeline, reached by tapping them on the Exchange screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendTimelineScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onOpenEvent: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val friend = state.viewingFriend

    Scaffold(
        containerColor = Ground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ground, titleContentColor = Ink),
                title = { Text(friend?.name ?: "Their timeline", fontFamily = Serif, fontSize = 18.sp, color = Ink) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Faint)
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().swipeRightToBack(onBack = onBack)) {
            when {
                state.viewedFriendLoading ->
                    CircularProgressIndicator(color = Amber, modifier = Modifier.align(Alignment.Center))

                state.viewedFriendShows.isEmpty() ->
                    Text(
                        "No public shows on setlist.fm for @${friend?.setlistfm}.",
                        color = Muted,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Text(
                            "${state.viewedFriendShows.size} shows",
                            color = Faint,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 14.dp),
                        )
                    }
                    items(state.viewedFriendShows, key = { it.id }) { setlist ->
                        TimelineItem(
                            setlist = setlist,
                            highlight = setlist.id == state.viewedFriendShows.first().id,
                            onClick = {
                                viewModel.openShow(setlist)
                                onOpenEvent()
                            },
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

/** Encodes text as a QR bitmap. The friend deep link is registered, so any camera app that
 *  reads this opens Station to Station and adds the card's owner. */
internal fun qrBitmap(
    content: String,
    sizePx: Int,
    ink: Int = android.graphics.Color.BLACK,
    paper: Int = android.graphics.Color.WHITE,
): Bitmap {
    val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) {
        val row = y * sizePx
        for (x in 0 until sizePx) {
            pixels[row + x] = if (matrix[x, y]) ink else paper
        }
    }
    return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
    }
}

private fun shareLink(context: android.content.Context, uri: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, uri)
    }
    context.startActivity(Intent.createChooser(send, "Share your Station card"))
}
