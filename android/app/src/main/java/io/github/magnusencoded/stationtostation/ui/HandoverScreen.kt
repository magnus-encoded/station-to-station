package io.github.magnusencoded.stationtostation.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.magnusencoded.stationtostation.AppViewModel
import io.github.magnusencoded.stationtostation.HandoverRole
import io.github.magnusencoded.stationtostation.HandoverUi
import io.github.magnusencoded.stationtostation.data.AccountsMove
import io.github.magnusencoded.stationtostation.data.CATEGORY_ACCOUNTS
import io.github.magnusencoded.stationtostation.data.CATEGORY_SETLISTS
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.approvalVerb
import io.github.magnusencoded.stationtostation.data.categoryOf
import io.github.magnusencoded.stationtostation.data.exchange.HandoverPhase
import io.github.magnusencoded.stationtostation.data.exchange.HandoverReceipt

private val Ground = Color(0xFF0E0B14)
private val Raised = Color(0xFF17121F)
private val LineLit = Color(0xFF4A3F63)
private val Ink = Color(0xFFEDE9F2)
private val Muted = Color(0xFF8B8299)
private val Faint = Color(0xFF5A5368)
private val Amber = Color(0xFFE7B24C)
private val Serif = FontFamily.Serif

/**
 * Moving a whole phone's worth of nights onto the next phone (#142).
 *
 * One screen, both ends of it. The old phone ticks what goes and shows a code; the new
 * phone's camera reads that code, which opens this app on the deep link and starts
 * receiving — so there is no in-app scanner, no camera permission on the receiving side,
 * and the code is genuinely out-of-band key material rather than a convenience.
 *
 * **The tick list is the approval, and the button says what happens here.** Records are
 * copied and the old phone keeps everything; accounts genuinely leave, which is why
 * [approvalVerb] names that consequence instead of flattening both into "Move".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandoverScreen(viewModel: AppViewModel, onDone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val handover = state.handover

    // Leaving the screen ends the session, by whatever route — the back arrow calls onDone,
    // but the system back gesture and a pop from anywhere else do not, and a handover left
    // running behind a closed screen is a listening socket and an open connection nobody can
    // see or stop.
    DisposableEffect(Unit) { onDispose { viewModel.dismissHandover() } }

    Scaffold(
        containerColor = Ground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ground, titleContentColor = Ink),
                title = { Text("Move to a new phone", fontFamily = Serif, fontSize = 18.sp, color = Ink) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Faint)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                handover.receipt != null || handover.error != null -> Outcome(handover, onDone)
                handover.role == HandoverRole.SOURCE -> Offering(handover, viewModel::cancelHandover)
                handover.role == HandoverRole.RECEIVER -> Receiving(handover, viewModel::cancelHandover)
                else -> WhatGoes(onOffer = viewModel::offerHandover)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** One row of the tick list: a plain-language name, and the categories it stands for. */
private data class Choice(val label: String, val note: String, val categories: Set<String>)

private val choices = listOf(
    Choice(
        "Your nights",
        "Every gig, your log of it, who you were with, the playlists you made.",
        setOf(CATEGORY_SETLISTS),
    ),
    Choice(
        "Photos and videos",
        "Everything in the shared band, and the notes above them.",
        setOf(StoredMedia.Kind.PHOTO, StoredMedia.Kind.VIDEO, StoredMedia.Kind.NOTE),
    ),
    Choice(
        "The vault",
        "What you marked Personal. It reaches your own other phone and nobody else.",
        setOf(
            categoryOf(StoredMedia.Kind.PHOTO, personal = true),
            categoryOf(StoredMedia.Kind.VIDEO, personal = true),
            categoryOf(StoredMedia.Kind.NOTE, personal = true),
        ),
    ),
    Choice(
        "Accounts",
        "Spotify moves rather than copies: this phone signs out once the other one has it.",
        setOf(CATEGORY_ACCOUNTS),
    ),
)

/**
 * The source's approval. **Nothing starts ticked**, so every category that leaves this
 * phone was chosen rather than merely not un-chosen — and the button stays dead until at
 * least one is, which makes tapping through without reading the screen impossible rather
 * than merely unwise. The row that goes unread is the row that does not travel.
 */
@Composable
private fun WhatGoes(onOffer: (Set<String>) -> Unit) {
    var allow by remember { mutableStateOf(emptySet<String>()) }

    Spacer(Modifier.height(12.dp))
    Text(
        "On the old phone, tick what goes and show the code. On the new phone, point the " +
            "camera at it.",
        color = Muted,
        fontSize = 13.sp,
    )
    Spacer(Modifier.height(20.dp))
    choices.forEach { row ->
        val ticked = allow.containsAll(row.categories)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Raised)
                .border(1.dp, LineLit, RoundedCornerShape(12.dp))
                .clickable {
                    allow = if (ticked) allow - row.categories else allow + row.categories
                }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = ticked,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(checkedColor = Amber, uncheckedColor = Faint),
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(row.label, color = Ink, fontFamily = Serif, fontSize = 16.sp)
                Text(row.note, color = Faint, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
    }
    Spacer(Modifier.height(14.dp))
    Button(
        onClick = { onOffer(allow) },
        enabled = allow.isNotEmpty(),
        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ground),
    ) { Text(approvalVerb(allow), fontWeight = FontWeight.SemiBold) }
}

/** Waiting to be joined, then sending. The code *is* the key material — see `HandoverInvite`. */
@Composable
private fun Offering(handover: HandoverUi, onCancel: () -> Unit) {
    val uri = handover.inviteUri
    Spacer(Modifier.height(16.dp))
    if (handover.progress.phase == HandoverPhase.CONNECTING && uri != null) {
        val qr = remember(uri) { runCatching { qrBitmap(uri, 640) }.getOrNull() }
        if (qr != null) {
            Box(Modifier.clip(RoundedCornerShape(14.dp)).background(Color.White).padding(14.dp)) {
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = "The code the new phone reads",
                    modifier = Modifier.size(240.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Point the new phone's camera at this.", color = Muted, fontSize = 13.sp)
        Text(
            "The code carries the key for this transfer and nothing else can join with it.",
            color = Faint,
            fontSize = 12.sp,
        )
    } else {
        Progress(handover, "Sending")
    }
    Spacer(Modifier.height(20.dp))
    OutlinedButton(onClick = onCancel) { Text("Stop", color = Amber) }
}

@Composable
private fun Receiving(handover: HandoverUi, onCancel: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Progress(handover, "Receiving")
    Spacer(Modifier.height(20.dp))
    OutlinedButton(onClick = onCancel) { Text("Stop", color = Amber) }
}

/** Size and progress together (#142 story 14): what was committed to, and how far in. */
@Composable
private fun Progress(handover: HandoverUi, verb: String) {
    val p = handover.progress
    val phase = when (p.phase) {
        HandoverPhase.CONNECTING -> "Connecting"
        HandoverPhase.ACCOUNTS -> "Moving accounts"
        HandoverPhase.MANIFEST -> "Agreeing what's missing"
        HandoverPhase.TRANSFER ->
            if (p.itemsTotal == 0) "Nothing left to fetch"
            else "$verb ${minOf(p.items + 1, p.itemsTotal)} of ${p.itemsTotal}"
        HandoverPhase.DONE -> "Done"
        HandoverPhase.FAILED -> "Stopped"
    }
    CircularProgressIndicator(color = Amber)
    Spacer(Modifier.height(18.dp))
    Text(phase, color = Ink, fontFamily = Serif, fontSize = 18.sp)
    Spacer(Modifier.height(10.dp))
    if (p.bytesTotal > 0) {
        LinearProgressIndicator(
            progress = { (p.bytesDone.toFloat() / p.bytesTotal).coerceIn(0f, 1f) },
            color = Amber,
            trackColor = Raised,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text("${humanBytes(p.bytesDone)} of ${humanBytes(p.bytesTotal)}", color = Muted, fontSize = 13.sp)
    }
}

/**
 * The plain receipt (#142 story 12). A deliberate act deserves a visible outcome, and a
 * transfer that stopped early gets one too — what landed, landed.
 */
@Composable
private fun Outcome(handover: HandoverUi, onDone: () -> Unit) {
    Spacer(Modifier.height(24.dp))
    val receipt = handover.receipt
    Text(
        when {
            handover.error != null -> "Nothing moved"
            receipt?.trouble?.isNotEmpty() == true -> "Stopped part way"
            else -> "Done"
        },
        color = Ink,
        fontFamily = Serif,
        fontSize = 22.sp,
    )
    Spacer(Modifier.height(14.dp))
    handover.error?.let { Text(it, color = Muted, fontSize = 13.sp) }
    receipt?.let { Receipt(it) }
    Spacer(Modifier.height(24.dp))
    TextButton(onClick = onDone) { Text("Done", color = Amber) }
}

@Composable
private fun Receipt(receipt: HandoverReceipt) {
    Column(Modifier.fillMaxWidth()) {
        if (receipt.trouble.isNotEmpty()) {
            Text(receipt.trouble, color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
        }
        Line("Arrived", "${receipt.landed} of ${receipt.requested} (${humanBytes(receipt.bytes)})")
        // Nothing when accounts were not part of this handover — declining the row is a
        // supported outcome (#143 story 11) and the receipt should not mention a step that
        // never ran (#143 story 9).
        if (receipt.accountsMove != AccountsMove.NOT_OFFERED) {
            Line(
                "Accounts",
                if (receipt.accountsMove == AccountsMove.ACKNOWLEDGED) "Arrived" else "Did not complete",
            )
        }
        if (receipt.fromGallery > 0) Line("Already on this phone", "${receipt.fromGallery}")
        if (receipt.held > 0) Line("Already held", "${receipt.held}")
        if (receipt.withheld > 0) Line("Not offered", "${receipt.withheld}")
        if (receipt.refused > 0) Line("Refused", "${receipt.refused}")
        if (receipt.countMismatch) {
            Spacer(Modifier.height(10.dp))
            // Counts live inside the manifest's signature, so this is not a rounding
            // disagreement — it is the manifest describing a library other than the one it
            // listed, and saying so is the whole point of counting per category.
            Text(
                "The other phone's list did not add up to what it sent. Worth running again.",
                color = Amber,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = Ink, fontSize = 13.sp)
    }
}

/** Decimal units, because a phone's storage is sold in them and this is a number people
 * compare against "how much room is left". */
internal fun humanBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f kB".format(bytes / 1_000.0)
    else -> "$bytes bytes"
}
