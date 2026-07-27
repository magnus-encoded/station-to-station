package io.github.magnusencoded.setlist2spotify.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import io.github.magnusencoded.setlist2spotify.AppViewModel
import io.github.magnusencoded.setlist2spotify.data.Friend

private val Ground = Color(0xFF0E0B14)
private val Raised = Color(0xFF17121F)
private val Ink = Color(0xFFEDE9F2)
private val Muted = Color(0xFF8B8299)
private val Faint = Color(0xFF5A5368)
private val Amber = Color(0xFFE7B24C)
private val LineLit = Color(0xFF4A3F63)
private val Serif = FontFamily.Serif

/** Encodes text as a QR bitmap. The friend deep link is registered, so any camera
 *  app that reads this opens Station to Station and adds the card's owner. */
private fun qrBitmap(content: String, sizePx: Int): Bitmap {
    val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) {
        val row = y * sizePx
        for (x in 0 until sizePx) {
            pixels[row + x] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onViewFriend: (Friend) -> Unit,
    onSetUsername: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // My share URI (setlist2spotify://friend?...); null until I've set my username.
    var cardUri by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.mySetlistFmUser, state.spotifyConnected) {
        cardUri = viewModel.myCardUri()?.toString()
    }
    val qr = remember(cardUri) { cardUri?.let { runCatching { qrBitmap(it, 640) }.getOrNull() } }

    Scaffold(
        containerColor = Ground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ground, titleContentColor = Ink),
                title = { Text("People", fontFamily = Serif, fontSize = 18.sp, color = Ink) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Faint)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = 20.dp)) {
            item {
                Text(
                    "Standing next to someone? Let them scan this with their camera — it opens the app and swaps your setlist.fm ↔ Spotify cards, so each of you gets the other's timeline.",
                    color = Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
                )
            }
            item {
                if (qr != null) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.clip(RoundedCornerShape(14.dp)).background(Color.White).padding(14.dp),
                        ) {
                            Image(
                                bitmap = qr.asImageBitmap(),
                                contentDescription = "Your friend card as a QR code",
                                modifier = Modifier.size(220.dp),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "@${state.mySetlistFmUser}",
                            color = Ink,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { cardUri?.let { shareLink(context, it) } }) {
                            Text("Share a link instead", color = Amber)
                        }
                    }
                } else {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Set your setlist.fm username to make your card.", color = Muted, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = onSetUsername) { Text("Add your username", color = Amber) }
                    }
                }
            }
            item {
                Spacer(Modifier.height(28.dp))
                Text(
                    "FRIENDS",
                    color = Faint,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            if (state.friends.isEmpty()) {
                item {
                    Text(
                        "No one yet. Trade cards with someone at a show.",
                        color = Faint,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            items(state.friends, key = { it.setlistfm }) { friend ->
                FriendRow(friend, onClick = { onViewFriend(friend) })
            }
            // ponytail: Nearby Connections (auto-discovery) + NFC/BLE transfer of the
            // same card are the next transports — QR carries it today.
            item { Spacer(Modifier.height(24.dp)) }
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

/** A friend's collection timeline, reached by tapping them on the Connect screen. */
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
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.friendTimelineLoading ->
                    CircularProgressIndicator(color = Amber, modifier = Modifier.align(Alignment.Center))

                state.friendTimeline.isEmpty() ->
                    Text(
                        "No public shows on setlist.fm for @${friend?.setlistfm}.",
                        color = Muted,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Text(
                            "${state.friendTimeline.size} shows",
                            color = Faint,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 14.dp),
                        )
                    }
                    items(state.friendTimeline, key = { it.id }) { setlist ->
                        TimelineItem(
                            setlist = setlist,
                            highlight = setlist.id == state.friendTimeline.first().id,
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

private fun shareLink(context: android.content.Context, uri: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, uri)
    }
    context.startActivity(Intent.createChooser(send, "Share your Station card"))
}
