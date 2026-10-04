package io.github.magnusencoded.stationtostation.ui

import android.graphics.Bitmap
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.magnusencoded.stationtostation.AppViewModel
import io.github.magnusencoded.stationtostation.CoverCandidate
import io.github.magnusencoded.stationtostation.MediaThumb
import io.github.magnusencoded.stationtostation.NOT_STAMPED
import io.github.magnusencoded.stationtostation.data.Band
import io.github.magnusencoded.stationtostation.data.ReleaseHint
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.bandsOf
import io.github.magnusencoded.stationtostation.data.handle
import io.github.magnusencoded.stationtostation.data.hintForAdding
import io.github.magnusencoded.stationtostation.data.hintForMoving
import io.github.magnusencoded.stationtostation.data.nameOf
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSong
import io.github.magnusencoded.stationtostation.data.setlistfm.line
import io.github.magnusencoded.stationtostation.ui.flyover.CollectionFlyoverScreen
import io.github.magnusencoded.stationtostation.ui.flyover.collectionBillboard
import io.github.magnusencoded.stationtostation.ui.flyover.collectionFlyoverGigs
import io.github.magnusencoded.stationtostation.ui.flyover.collectionMedia
import kotlin.math.abs
import kotlin.math.roundToInt

private val Amber = Color(0xFFE7B24C)
private val Ink = Color(0xFFEDE9F2)
private val Muted = Color(0xFF8B8299)
private val Faint = Color(0xFF5A5368)
private val Slate = Color(0xFF6F809D) // the future / a connected-source, a cooler light
private val Serif = FontFamily.Serif
private val Ground = Color(0xFF0E0B14)
private val Raised2 = Color(0xFF1D1728)
private val LineLit = Color(0xFF4A3F63)
private val AmberSoft = Color(0x29E7B24C)
private val Danger = Color(0xFFE08A8A)

/** A gig photo or video frame, decoded lazily and cached by its own [uri] key. */
@Composable
internal fun PhotoThumb(uri: Uri, size: Dp, loadPreview: suspend (Uri) -> MediaThumb, modifier: Modifier = Modifier) {
    var thumb by remember(uri) { mutableStateOf(MediaThumb(null)) }
    LaunchedEffect(uri) { thumb = loadPreview(uri) }
    Box(modifier.size(size).clip(RoundedCornerShape(6.dp)).background(Raised2)) {
        thumb.bitmap?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = "Your photo from this show",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (thumb.isVideo) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Video",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(size / 3)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f)),
            )
        }
    }
}

/**
 * The **Collection resolution**'s portrait face (#313 story 5): every **Gig** in the
 * run's media, combined into one place, so a three-day festival reads as one weekend
 * instead of a per-night crawl. Not a screen of its own — drawn over the **Line** in
 * portrait the same way [io.github.magnusencoded.stationtostation.ui.flyover.CollectionFlyoverScreen]
 * is drawn over it in landscape, so leaving is the same state change either way.
 *
 * **Follows the Room's own grammar rather than inventing a second media surface**:
 * [GigMediaBands] is the component the **Gig resolution**'s portrait face already
 * draws media in, called here with the run's combined list. It is read-only — a run
 * has no single **Gig** to attach into or arrange within — so arranging and adding are
 * never offered; `editable = false` and every mutating callback is a no-op.
 */
@Composable
internal fun CollectionMediaScreen(viewModel: AppViewModel, node: TimelineNode.Several, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The platform back affordance leaves the resolution, the same as the landscape
    // face and for the same reason (#313): this rung was entered by a gesture or a
    // non-gestural action alike, and the system's own way out must always work.
    BackHandler(onBack = onBack)

    val gigs = remember(
        node, state.mediaBySetlist, state.logsByGig, state.festivals,
        state.showsByFriend, state.attendanceByGig, state.witnessedGigs, state.contactLight,
    ) {
        collectionFlyoverGigs(
            node = node,
            mediaBySetlist = state.mediaBySetlist,
            logsByGig = state.logsByGig,
            festivals = state.festivals,
            showsByFriend = state.showsByFriend,
            attendanceByGig = state.attendanceByGig,
            witnessedGigs = state.witnessedGigs,
            contactLight = state.contactLight,
        )
    }
    val media = remember(gigs) { collectionMedia(gigs) }
    val billboard = remember(node) { collectionBillboard(node) }

    var viewerUri by remember { mutableStateOf<Uri?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ground)
            .swipeRightToBack(onBack = onBack)
            .verticalScroll(rememberScrollState())
            .padding(top = 20.dp, bottom = 40.dp),
    ) {
        Text(
            billboard.title,
            color = Ink,
            fontFamily = Serif,
            fontSize = 24.sp,
            modifier = Modifier.padding(horizontal = 20.dp).asHeading(),
        )
        if (billboard.where.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                billboard.where,
                color = Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        GigMediaBands(
            media = media,
            loadPreview = viewModel::photoPreview,
            arranging = false,
            contactLight = state.contactLight,
            editable = false,
            senderName = { key -> state.friends.nameOf(key) },
            onArrange = {},
            onAdd = {},
            onOpen = { uri -> viewerUri = uri },
            onRemove = {},
            onMove = { _, _, _ -> },
        )
    }

    viewerUri?.let { uri ->
        MediaViewerDialog(
            uri = uri,
            isVideo = viewModel.isVideo(uri),
            loadPhoto = viewModel::fullPhoto,
            onDismiss = { viewerUri = null },
        )
    }
}

/**
 * A night's **Media**, in its two bands (#162).
 *
 * **Position is the bit.** The upper band is what a **Contact** can see, the lower is
 * what only I can, and which band a photograph sits in *is* its **Personal** bit.
 * **Amber** edges mine in *both* bands, because Amber means mine and never
 * held-back; the cooler light edges **Received media**, which sits to the right of my
 * own and cannot be dragged at all — its disposition is not mine to set.
 *
 * **The handle teaches itself.** At rest it is a two-way arrow, which says only that
 * it moves. Drag it and the band you are over answers with the whole sentence, so you
 * learn both halves of the model before spending anything — the drag is reversible
 * right up to the release. Down is the vault, deliberately: it is the easier reach,
 * and the direction an unfamiliar thumb drifts must be the one that shares nothing.
 *
 * Long-press a photograph to arrange. [arranging] is owned by the **Room** rather
 * than by this composable, which is what lets a tap anywhere that is not an [x] leave
 * it again.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GigMediaBands(
    media: List<StoredMedia>,
    loadPreview: suspend (Uri) -> MediaThumb,
    arranging: Boolean,
    contactLight: Boolean,
    /**
     * Whether this night is mine to change (#327). Distinct from [contactLight], which
     * is a *preview* of someone else's view of my own night — this is someone else's
     * night. Both suppress editing and they are not the same question, so the room may
     * be read-only for either reason.
     */
    editable: Boolean,
    senderName: (String) -> String?,
    onArrange: () -> Unit,
    onAdd: (Band) -> Unit,
    onOpen: (Uri) -> Unit,
    onRemove: (StoredMedia) -> Unit,
    onMove: (String, Band, Int) -> Unit,
    /** Leaves arrange mode — what a tap anywhere else in the Room does. */
    onDoneArranging: () -> Unit = {},
) {
    // Two splits of the same night, and the difference between them is the whole of
    // #50's wiring. [all] is every item and answers *who is in the commons* — a
    // **Note** in the shared band makes me a contributor exactly as a photograph
    // does. [bands] is the visual run only and answers *what the strip draws*: the
    // strip's index maths is tile-strided, and a full-width prose row is not a tile.
    //
    // MediaBands itself stays kind-blind, which is the claim this feature rests on.
    val all = bandsOf(media)
    val bands = bandsOf(media.filterNot { it.kind == StoredMedia.Kind.NOTE })
    val density = LocalDensity.current
    val strideX = with(density) { (GigPhotoSize + ItemGap).toPx() }
    val padStart = with(density) { 20.dp.toPx() }

    val sharedScroll = rememberScrollState()
    val vaultScroll = rememberScrollState()
    // Each strip's rectangle in root coordinates, so a drop lands in the band the
    // finger is actually over. Guessing it from the sign of the vertical travel put
    // the shared band 44dp from a vault photograph, which is inside the vault's own
    // row — the one direction that must be hard to hit by accident was the cheapest.
    val strips = remember { mutableStateMapOf<Band, Rect>() }

    var over by remember { mutableStateOf<Band?>(null) }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragFrom by remember { mutableStateOf(Band.SHARED) }
    var dragTo by remember { mutableStateOf<Band?>(null) }
    var dragIndex by remember { mutableStateOf(0) }

    fun listOf(band: Band) = if (band == Band.SHARED) bands.shared else bands.vault
    fun scrollOf(band: Band) = if (band == Band.SHARED) sharedScroll else vaultScroll

    fun bandUnder(p: Offset): Band {
        strips.forEach { (band, r) -> if (p.y >= r.top && p.y <= r.bottom) return band }
        val shared = strips[Band.SHARED] ?: return dragFrom
        val vault = strips[Band.VAULT] ?: return dragFrom
        return if (abs(p.y - shared.center.y) <= abs(p.y - vault.center.y)) Band.SHARED else Band.VAULT
    }

    /**
     * Where in [band] the finger is, counted over that band *without* the item being
     * carried — which is the list [moveMedia] inserts into, so the slot that opens is
     * the position the photograph actually takes.
     */
    fun indexUnder(band: Band, p: Offset): Int {
        val r = strips[band] ?: return 0
        val x = p.x - r.left + scrollOf(band).value - padStart
        val room = listOf(band).size - if (band == dragFrom) 1 else 0
        return ((x + strideX / 2f) / strideX).toInt().coerceIn(0, room.coerceAtLeast(0))
    }

    // What letting go would do to the shared band, asked the same way by both
    // gestures — see [releaseHint]. Nothing here special-cases the direction.
    val hint = when {
        dragId != null && dragTo != null -> hintForMoving(media, dragId!!, dragTo!!)
        over != null -> hintForAdding(media, over!!)
        else -> ReleaseHint.NONE
    }
    val promised = if (dragId != null) dragTo else over

    val startDrag = { band: Band, p: Offset ->
        val r = strips[band]
        val at = if (r == null) -1 else ((p.x - r.left + scrollOf(band).value - padStart) / strideX).toInt()
        val item = listOf(band).getOrNull(at)
        if (item != null) {
            dragId = item.id
            dragFrom = band
            dragTo = band
            dragIndex = at
        }
    }
    val moveDrag = { p: Offset ->
        if (dragId != null) {
            val band = bandUnder(p)
            dragTo = band
            dragIndex = indexUnder(band, p)
        }
    }
    val endDrag = {
        dragId?.let { onMove(it, dragTo ?: dragFrom, dragIndex) }
        dragId = null
        dragTo = null
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MediaBand(
                band = Band.SHARED,
                label = "Shared",
                mine = bands.shared,
                received = bands.received,
                // What the band *would* hold, not what changes: the outline is a
                // statement about the collection, so a band already crossed keeps
                // saying so while you hover over it (#268). Off the whole night, not
                // the strip — a **Contact** who sent only a **Note** is still someone
                // I shared the night with.
                crossed = when {
                    promised != Band.SHARED -> all.crossed
                    hint == ReleaseHint.GAINED -> true
                    hint == ReleaseHint.LOST -> false
                    else -> all.crossed
                },
                say = when {
                    promised != Band.SHARED -> null
                    hint == ReleaseHint.GAINED -> {
                        val who = all.received.mapNotNull { it.from }.distinct()
                            .mapNotNull(senderName)
                        // Named where the name is known. There is no join from a
                        // sender's key to a Contact's name yet, so this degrades
                        // rather than inventing one.
                        val subject = when (who.size) {
                            0 -> "someone else is"
                            1 -> who.single() + " is"
                            else -> who.joinToString(" and ") + " are"
                        }
                        "$subject already here — let go and it becomes a night you shared"
                    }
                    hint == ReleaseHint.LOST -> "let go and this stops being a night you shared"
                    else -> null
                },
                offering = over == Band.SHARED,
                offerText = "Share a picture or video",
                arranging = arranging,
                draggingId = dragId,
                slotAt = if (dragTo == Band.SHARED) dragIndex else null,
                scroll = sharedScroll,
                loadPreview = loadPreview,
                onBounds = { strips[Band.SHARED] = it },
                onOpen = onOpen,
                onRemove = onRemove,
                onArrange = onArrange,
                onDoneArranging = onDoneArranging,
                onDragStart = { startDrag(Band.SHARED, it) },
                onDragAt = moveDrag,
                onDrop = endDrag,
                // The drag's two outcomes, for a reader that cannot drag. Only where
                // the vault is drawn: under the contact light it is not there to land in.
                moveAcrossLabel = if (editable && !contactLight) "Move to the vault" else null,
                onMoveAcross = { onMove(it.id, Band.VAULT, 0) },
            )
            // Under the contact light the room holds what a Contact can see, and they
            // cannot see the vault at all — so it is absent rather than drawn empty,
            // which would have it claim "nothing held back" over a full vault.
            if (!contactLight) {
                MediaBand(
                    band = Band.VAULT,
                    label = "In the vault",
                    mine = bands.vault,
                    received = emptyList(),
                    crossed = false,
                    say = null,
                    offering = over == Band.VAULT,
                    offerText = "Add a picture or video just for you",
                    arranging = arranging,
                    draggingId = dragId,
                    slotAt = if (dragTo == Band.VAULT) dragIndex else null,
                    scroll = vaultScroll,
                    loadPreview = loadPreview,
                    onBounds = { strips[Band.VAULT] = it },
                    onOpen = onOpen,
                    onRemove = onRemove,
                    onArrange = onArrange,
                    onDoneArranging = onDoneArranging,
                    onDragStart = { startDrag(Band.VAULT, it) },
                    onDragAt = moveDrag,
                    onDrop = endDrag,
                    moveAcrossLabel = if (editable) "Share it" else null,
                    onMoveAcross = { onMove(it.id, Band.SHARED, 0) },
                )
            }
        }
        if (editable) {
            Spacer(Modifier.width(10.dp))
            AttachHandle(
                // The travel is the distance to the bands themselves, so the handle
                // stops where the thing it is pointing at is rather than at a number
                // (#268). Measured off the same rects the drop test uses.
                travel = { at ->
                    val up = strips[Band.SHARED]?.let { it.center.y - at } ?: -160f
                    val down = strips[Band.VAULT]?.let { it.center.y - at } ?: 160f
                    up.coerceAtMost(0f)..down.coerceAtLeast(0f)
                },
                onOver = { over = it },
                onRelease = { band -> over = null; band?.let(onAdd) },
                onAdd = onAdd,
            )
            Spacer(Modifier.width(4.dp))
        }
    }
}

/**
 * The two-way arrow, and the only control that adds.
 *
 * It carries no state during the drag on purpose: a thumb is on top of it for the
 * whole gesture, so anything it said would be said where nobody can read it. The
 * bands answer instead. A tap does nothing, which reads as the wrong gesture rather
 * than as a broken app — a plus that ignored a tap would read as the second.
 *
 * Half a tile wide and a full tile tall: it is a rail the thumb runs along, not a
 * button, and at tile-square it read as a missing photograph (#268). [travel] answers
 * how far it may run given where it is resting — the band centres, so it arrives at
 * the thing it is pointing at instead of stopping at an arbitrary 160px.
 */
@Composable
internal fun AttachHandle(
    travel: (restingCentreY: Float) -> ClosedFloatingPointRange<Float>,
    onOver: (Band?) -> Unit,
    onRelease: (Band?) -> Unit,
    onAdd: (Band) -> Unit,
) {
    val commit = with(LocalDensity.current) { 14.dp.toPx() }
    var offsetY by remember { mutableStateOf(0f) }
    var chosen by remember { mutableStateOf<Band?>(null) }
    // Read off the *outer* box, which never moves — measuring the offset one would
    // fold the drag back into its own limits.
    var restingY by remember { mutableStateOf(0f) }

    Box(
        Modifier
            .onGloballyPositioned { restingY = it.boundsInRoot().center.y }
            .offset { IntOffset(0, offsetY.roundToInt()) }
            .width(GigPhotoSize / 2)
            .height(GigPhotoSize)
            .clip(RoundedCornerShape(10.dp))
            .background(Raised2)
            // Never Amber, and never anything else either: the doc above is the rule
            // and this line was the exception to it, left over from #162 — before
            // #268 settled that amber is the vault's and an upward drag must not
            // reach for it. A handle that lit amber on the way *up* said the one
            // thing the colour is not allowed to say, under a thumb, where nobody
            // could read it anyway. The bands answer.
            .border(1.dp, LineLit, RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, amount ->
                        change.consume()
                        offsetY = (offsetY + amount.y).coerceIn(travel(restingY))
                        chosen = when {
                            offsetY < -commit -> Band.SHARED
                            offsetY > commit -> Band.VAULT
                            else -> null
                        }
                        onOver(chosen)
                    },
                    onDragEnd = {
                        onRelease(chosen)
                        offsetY = 0f
                        chosen = null
                    },
                    onDragCancel = {
                        onRelease(null)
                        offsetY = 0f
                        chosen = null
                    },
                )
            }
            // The drag is the only way in, and TalkBack sends a drag to the reader — so
            // the handle names itself and offers both ends of its travel as actions, with
            // the same sentences the bands light up with (#164). Cleared rather than
            // merged so the arrow glyph is not read out as "up down arrow".
            .clearAndSetSemantics {
                contentDescription = "Add a picture or video"
                customActions = listOf(
                    CustomAccessibilityAction("Share a picture or video") { onAdd(Band.SHARED); true },
                    CustomAccessibilityAction("Add a picture or video just for you") { onAdd(Band.VAULT); true },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "↕",
            color = Muted,
            fontSize = 26.sp,
        )
    }
}

/**
 * What a band outlines itself in, media and prose alike (#268).
 *
 * Three colours for three facts, and no colour carries two: **Amber** is the vault
 * and means *only I can see this*, **Slate** is a shared band holding only mine, and
 * **Crossed** is a shared band more than one of us is in. The upward gesture can
 * therefore never light amber, which is the whole point — the direction that spends
 * something must not be drawn in the colour of the direction that spends nothing.
 */
internal fun bandAccent(band: Band, crossed: Boolean): Color = when {
    band == Band.VAULT -> Amber
    crossed -> Crossed
    else -> Slate
}

/** The same three, at the alpha the offer overlay washes the strip with. */
internal fun bandWash(band: Band, crossed: Boolean): Color = when {
    band == Band.VAULT -> AmberSoft
    crossed -> CrossedSoft
    else -> SlateSoft
}

/**
 * One band: my own media, then **Received media**, then whatever the gesture in
 * progress is promising.
 *
 * [say] and [offerText] are drawn *over* the strip and never displace it — a state
 * change here is colour, never geometry, which is the rule the contact light
 * established. The landing slot is a real slot in the row, so the photographs open a
 * gap where the one you are carrying will go.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MediaBand(
    band: Band,
    label: String,
    mine: List<StoredMedia>,
    received: List<StoredMedia>,
    crossed: Boolean,
    say: String?,
    offering: Boolean,
    offerText: String,
    arranging: Boolean,
    draggingId: String?,
    slotAt: Int?,
    scroll: ScrollState,
    loadPreview: suspend (Uri) -> MediaThumb,
    onBounds: (Rect) -> Unit,
    onOpen: (Uri) -> Unit,
    onRemove: (StoredMedia) -> Unit,
    onArrange: () -> Unit,
    onDoneArranging: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDragAt: (Offset) -> Unit,
    onDrop: () -> Unit,
    /**
     * The drag across to the other band, as an action a screen reader can reach (#164).
     * Null where the drag itself is not on offer.
     */
    moveAcrossLabel: String? = null,
    onMoveAcross: (StoredMedia) -> Unit = {},
) {
    // The band's own colour, and the only thing the offer overlay recolours with.
    // **Amber is the vault's**, in both states: it means private here and nothing
    // else, so an upward drag must never reach for it (#268). The shared band answers
    // Slate while it would hold only mine, and **Crossed** once letting go means more
    // than one of us is in it.
    val accent = bandAccent(band, crossed)
    val wash = bandWash(band, crossed)
    val tilePx = with(LocalDensity.current) { (GigPhotoSize + ItemGap).toPx() }
    // The gesture lives on the strip, never on a tile. A tile leaves the composition
    // the moment it is picked up — that is how the gap opens — and a pointerInput on
    // a detached node has its coroutine cancelled, so onDragEnd would never arrive
    // and the drop would silently never commit.
    var here by remember { mutableStateOf<LayoutCoordinates?>(null) }

    // The strip follows the landing slot rather than the finger. ponytail: this is
    // the whole of "I cannot drag to a position I cannot see" — a free-running edge
    // scroll is more code and the same outcome.
    LaunchedEffect(slotAt) {
        val at = slotAt ?: return@LaunchedEffect
        val left = (at * tilePx).toInt()
        val right = left + tilePx.toInt()
        when {
            left < scroll.value -> scroll.animateScrollTo(left)
            right > scroll.value + scroll.viewportSize ->
                scroll.animateScrollTo((right - scroll.viewportSize).coerceAtLeast(0))
        }
    }

    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 5.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                label,
                color = if (say != null) {
                    if (crossed) Crossed else Muted
                } else {
                    Faint
                },
                fontSize = 10.sp,
            )
            if (say != null) {
                Spacer(Modifier.width(8.dp))
                Text(say, color = if (crossed) Crossed else Muted, fontSize = 10.sp)
            }
        }
        // One frame, and the gesture changes *it* rather than adding a second. Two
        // outlines around one band is what this looked like when the armed state drew
        // its own: they do not even share a rect, because the strip's own border sits
        // inside the scroll container and travels with the content (#268).
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .border(
                    if (offering) 2.dp else 1.dp,
                    accent,
                    RoundedCornerShape(6.dp),
                ),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned {
                        here = it
                        onBounds(it.boundsInRoot())
                    }
                    .horizontalScroll(scroll)
                    .pointerInput(arranging, band, mine.size) {
                        if (!arranging) return@pointerInput
                        detectDragGestures(
                            onDragStart = { at -> here?.let { onDragStart(it.localToRoot(at)) } },
                            onDrag = { change, _ ->
                                change.consume()
                                here?.let { onDragAt(it.localToRoot(change.position)) }
                            },
                            onDragEnd = onDrop,
                            onDragCancel = onDrop,
                        )
                    }
                    // Content padding: it is inside the scroll, so the first tile
                    // starts clear of the edge and scrolls away under it — and
                    // [indexUnder] counts from it. The frame is on the Box outside,
                    // which is the rect that stays still.
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Counted over the band without the carried item, so the gap opens
                // exactly where [moveMedia] will put it.
                var placed = 0
                mine.forEach { item ->
                    if (item.id == draggingId) return@forEach
                    if (slotAt == placed) LandingSlot(accent, wash)
                    MediaTile(
                        item = item,
                        arranging = arranging,
                        loadPreview = loadPreview,
                        onOpen = onOpen,
                        onRemove = onRemove,
                        onArrange = onArrange,
                        onDoneArranging = onDoneArranging,
                        moveAcrossLabel = moveAcrossLabel,
                        onMoveAcross = { onMoveAcross(item) },
                    )
                    Spacer(Modifier.width(ItemGap))
                    placed++
                }
                if (slotAt != null && slotAt >= placed) LandingSlot(accent, wash)
                received.forEach { item ->
                    MediaTile(
                        item = item,
                        arranging = arranging,
                        loadPreview = loadPreview,
                        onOpen = onOpen,
                        onRemove = onRemove,
                        onArrange = onArrange,
                        onDoneArranging = onDoneArranging,
                    )
                    Spacer(Modifier.width(ItemGap))
                }
                if (mine.none { it.id != draggingId } && received.isEmpty() && slotAt == null) {
                    // Rendered empty rather than hidden: a band nobody can see is a
                    // gesture nobody can find on a fresh install.
                    Box(Modifier.height(GigPhotoSize), contentAlignment = Alignment.CenterStart) {
                        Text(
                            if (label == "Shared") "Nothing shared yet" else "Nothing held back",
                            color = Faint,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
            if (offering) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(6.dp))
                        .background(wash),
                    contentAlignment = Alignment.Center,
                ) { Text(offerText, color = Ink, fontSize = 12.sp) }
            }
        }
    }
}

/**
 * Where the photograph will land, opened as a real gap in the row.
 *
 * In the band's own colour, not Amber: it appears mid-drag, and a drag *upward* that
 * lights amber is saying "private" about the thing you are about to share (#268).
 */
@Composable
internal fun LandingSlot(accent: Color, wash: Color) {
    Box(
        Modifier
            .size(GigPhotoSize)
            .clip(RoundedCornerShape(10.dp))
            .background(wash)
            .border(1.dp, accent, RoundedCornerShape(10.dp)),
    )
    Spacer(Modifier.width(ItemGap))
}

/**
 * One photograph. **Amber** if it is mine, the cooler light if it was given to me.
 *
 * Carries no drag gesture of its own — the strip owns that (see [MediaBand]). Tap and
 * long-press only, and only while not arranging, so a press that begins a drag is not
 * competing with a click.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MediaTile(
    item: StoredMedia,
    arranging: Boolean,
    loadPreview: suspend (Uri) -> MediaThumb,
    onOpen: (Uri) -> Unit,
    onRemove: (StoredMedia) -> Unit,
    onArrange: () -> Unit,
    onDoneArranging: () -> Unit = {},
    moveAcrossLabel: String? = null,
    onMoveAcross: () -> Unit = {},
) {
    val uri = remember(item.ref) { Uri.parse(item.ref) }

    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .border(
                1.5.dp,
                if (item.from == null) Amber else Slate,
                RoundedCornerShape(10.dp),
            )
            .then(
                if (!arranging) {
                    Modifier.combinedClickable(
                        onClickLabel = "Open",
                        onClick = { onOpen(uri) },
                        onLongClickLabel = "Arrange",
                        onLongClick = onArrange,
                    )
                } else {
                    Modifier
                },
            )
            // Moving between bands is a drag in arrange mode, which a screen reader
            // cannot make; the same move is offered on the tile itself (#164).
            .then(
                if (moveAcrossLabel != null) {
                    Modifier.semantics {
                        customActions = listOf(
                            CustomAccessibilityAction(moveAcrossLabel) { onMoveAcross(); true },
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        PhotoThumb(uri, size = GigPhotoSize, loadPreview = loadPreview)
        if (arranging) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    // The visible chip stays 20dp, but the tap target itself is
                    // padded out to the 48dp minimum so it's actually reachable.
                    .minimumInteractiveComponentSize()
                    .clickable { onRemove(item) }
                    // Arrange mode is left by tapping anywhere else, which TalkBack never
                    // sends. The x is where a reader's focus is while arranging (#164).
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction("Done arranging") { onDoneArranging(); true },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Danger),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Remove",
                        tint = Color.White,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
    }
}

/** Big enough to actually look like a keepsake, not a chip. */
internal val GigPhotoSize = 108.dp
internal val ItemGap = 10.dp

/**
 * The same same-night gallery search [CoverPicker] does for a playlist cover,
 * offered as one-tap adds to the gig's keepsakes instead of a single chosen cover.
 */
@Composable
internal fun GigPhotoSuggestions(
    candidates: List<CoverCandidate>,
    loading: Boolean,
    searched: Boolean,
    permissionGranted: Boolean,
    already: List<Uri>,
    onRequestPermission: () -> Unit,
    onAdd: (Uri) -> Unit,
) {
    val offered = remember(candidates, already) { candidates.filter { it.uri !in already } }
    when {
        !permissionGranted -> TextButton(onClick = onRequestPermission, contentPadding = PaddingValues(vertical = 2.dp)) {
            Text("Suggest photos from that night", color = Muted, fontSize = 12.sp)
        }
        loading -> Text("Looking through your gallery…", color = Faint, fontSize = 12.sp, modifier = Modifier.spokenOnChange())
        offered.isEmpty() -> if (searched) {
            Text("No more photos from that night in your gallery.", color = Faint, fontSize = 12.sp, modifier = Modifier.spokenOnChange())
        }
        else -> Column {
            Text("From that night — tap to add", color = Faint, fontSize = 11.sp, modifier = Modifier.spokenOnChange())
            Spacer(Modifier.height(4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                offered.forEach { candidate ->
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Raised2)
                            .clickable { onAdd(candidate.uri) },
                    ) {
                        candidate.preview?.let {
                            Image(
                                it.asImageBitmap(),
                                contentDescription = "Suggested photo from that night",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
    }
}

/**
 * A tap on a keepsake opens it here rather than in an external app — a photo enlarged,
 * a video played back — since a picker/FileProvider uri handed to whatever app the phone
 * chooses can fail to actually load it there.
 *
 * When the keepsake is a whole night's recording, the setlist rides along underneath it:
 * play, and tap a song as it starts to record where it sits in the video. Nothing is
 * inferred — one tap stamps one song — because the recording and the setlist do not
 * always hold the same songs.
 */
@Composable
internal fun MediaViewerDialog(
    uri: Uri,
    isVideo: Boolean,
    loadPhoto: suspend (Uri) -> Bitmap?,
    onDismiss: () -> Unit,
    songs: List<FmSong> = emptyList(),
    offsets: List<Long> = emptyList(),
    startAtMs: Long = NOT_STAMPED,
    onStamp: (Int, Long) -> Unit = { _, _ -> },
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (isVideo && songs.isNotEmpty()) {
                var player by remember(uri) { mutableStateOf<VideoView?>(null) }
                Column(Modifier.fillMaxSize()) {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().weight(0.45f),
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                                setVideoURI(uri)
                                setOnPreparedListener {
                                    if (startAtMs > NOT_STAMPED) seekTo(startAtMs.toInt())
                                    it.start()
                                }
                                player = this
                            }
                        },
                    )
                    Text(
                        "Tap a song as it starts. Long-press to clear.",
                        color = Faint,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 6.dp),
                    )
                    LazyColumn(Modifier.weight(0.55f)) {
                        itemsIndexed(songs) { index, song ->
                            StampRow(
                                number = index + 1,
                                song = song,
                                offsetMs = offsets.getOrElse(index) { NOT_STAMPED },
                                // A stamped song is a place to jump to; an unstamped one
                                // is a place to mark. Same row, told apart by whether it
                                // already knows where it lives.
                                onTap = {
                                    val at = offsets.getOrElse(index) { NOT_STAMPED }
                                    if (at > NOT_STAMPED) player?.seekTo(at.toInt())
                                    else player?.let { onStamp(index, it.currentPosition.toLong()) }
                                },
                                onLongPress = { onStamp(index, NOT_STAMPED) },
                            )
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            } else if (isVideo) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                            setVideoURI(uri)
                            setOnPreparedListener { it.start() }
                        }
                    },
                )
            } else {
                var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(uri) { bitmap = loadPhoto(uri) }
                bitmap?.let {
                    Image(
                        it.asImageBitmap(),
                        contentDescription = "Your photo from this show",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().clickable(onClick = onDismiss),
                    )
                } ?: CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}
