package io.github.magnusencoded.stationtostation.ui.flyover

import android.widget.MediaController
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.magnusencoded.stationtostation.AppViewModel
import io.github.magnusencoded.stationtostation.BuildConfig
import io.github.magnusencoded.stationtostation.MediaThumb
import io.github.magnusencoded.stationtostation.data.StoredAttendance
import io.github.magnusencoded.stationtostation.data.StoredLog
import io.github.magnusencoded.stationtostation.data.visibleToContacts
import io.github.magnusencoded.stationtostation.data.weaveSetlist
import io.github.magnusencoded.stationtostation.ui.EventRow
import io.github.magnusencoded.stationtostation.ui.eventRows
import io.github.magnusencoded.stationtostation.ui.railColor
import io.github.magnusencoded.stationtostation.ui.swipeRightToBack
import io.github.magnusencoded.stationtostation.ui.verdictGlyph
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs

/**
 * **Landscape is a read-only walk down the night** (#278).
 *
 * A **Cover** you start behind, a spine you travel, a **Wall** you reach. Turning the
 * phone does not open a different set of controls on the same night — it is a different
 * *posture*: both thumbs free, nothing to type, everything sideways because that is the
 * shape photographs are. There is no attach handle, no suggestion strip, no log editor
 * and no note field, and not because they were hard to fit: the IME takes two thirds of
 * 411dp, so typing here is bad regardless, and a room you can only read is a room that
 * can be about looking.
 *
 * **It replaces the landscape view rather than adding a mode.** The slideshow-and-ticker
 * design this subsumes had a fatal flaw of its own — a slideshow works exactly as well
 * in portrait, so it never justified being tied to the orientation. A receding line with
 * content down both sides cannot be done in portrait, and you travel it with a *vertical*
 * drag, which is the ergonomic claim the whole thing started from.
 *
 * ## How it draws
 *
 * Compose has no shared 3D scene: no `preserve-3d`, no depth sorting, no world to
 * translate. Every item is placed by hand from [projectedScale], and the **camera is
 * not built out of `cameraDistance`** — that is per-layer rather than a scene, and it
 * would hand back no screen rectangle to hit-test. Doing the projection directly is
 * what makes tap-to-select exact.
 *
 * **Depth order is sorted once, not every frame.** The usual rule for a painter's
 * algorithm is to re-sort back-to-front on every frame; here it is unnecessary and the
 * reason is worth stating, because it is load-bearing: the camera only ever moves along
 * one axis and nothing on the night ever changes its `z`, so no two items can swap
 * depth. Composition order — furthest first, the **Cover** last because it is nearest —
 * is correct for the entire walk.
 *
 * **Nothing here recomposes while you travel.** Travel is held outside the snapshot
 * system's composition phase and read inside `graphicsLayer` blocks and `Canvas` draw
 * lambdas, which re-run in the draw phase. A drag redraws; it does not rebuild.
 */
@Composable
fun GigFlyoverScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val setlist = state.selectedSetlist
    if (setlist == null) {
        Box(Modifier.fillMaxSize().background(Ground)) {
            Text("No show selected.", color = Muted, modifier = Modifier.align(Alignment.Center))
        }
        return
    }

    val held = state.mediaBySetlist[setlist.id].orEmpty()
    // The contact light is the **Room**'s review mode and it narrows what is on screen.
    // The walk must not widen it again: rotating the phone with the light on cannot be
    // a way to see what you are holding back.
    val media = if (state.contactLight) visibleToContacts(held) else held
    val log = state.logsByGig[setlist.id] ?: StoredLog()

    val checkedIn = state.attendanceByGig[setlist.id]?.provenance ==
        StoredAttendance.Provenance.CHECKED_IN

    // Who else was here. A floor line means **attended** now (#313), and the woven
    // timelines are where that is known — so the lanes are asked for, cached-and-
    // complete being the common case, rather than presence being read off who happened
    // to hand over a photograph.
    LaunchedEffect(Unit) { viewModel.loadFriendTimelines() }
    val night = remember(media, state.friends, setlist.id, log, state.showsByFriend, checkedIn) {
        val rows = setlist.eventRows()
        flyoverNight(
            // One night is the N=1 case of the run. There is no second composer.
            gigs = listOf(
                FlyoverGig(
                    id = setlist.id,
                    billboard = FlyoverBillboard(
                        title = setlist.artist?.name ?: "Unknown artist",
                        where = listOfNotNull(setlist.venueLine(), setlist.readableDate())
                            .joinToString(" · "),
                        chips = buildList {
                            val performed = setlist.performed().size
                            if (performed > 0) add("$performed songs")
                            setlist.tour?.name?.let { add(it) }
                            if (checkedIn) add("checked in")
                        },
                    ),
                    media = media,
                    rows = rows,
                    woven = weaveSetlist(
                        rows.map { (it as? EventRow.SongItem)?.song?.name },
                        log.songs,
                    ),
                    log = log,
                    date = setlist.localDate(),
                    attended = state.showsByFriend
                        .filterValues { shows -> shows.any { it.id == setlist.id } }
                        .keys,
                ),
            ),
            friends = state.friends,
        )
    }

    Flyover(
        night = night,
        loadThumb = viewModel::photoPreview,
        loadFull = viewModel::fullPhoto,
        onBack = onBack,
    )
}

/**
 * The walk itself, given a night. Takes no view model so that the whole screen can be
 * put in front of a `@Preview` — the only way to look at this without a phone in your
 * hand and the right night open on it.
 */
@Composable
internal fun Flyover(
    night: FlyoverNight,
    loadThumb: suspend (Uri) -> MediaThumb,
    loadFull: suspend (Uri) -> android.graphics.Bitmap?,
    onBack: () -> Unit,
) {
    val density = LocalDensity.current.density
    var frame by remember { mutableStateOf(IntSize.Zero) }
    // The wall is measured, not guessed: how far short of it you stop is a function of
    // how tall somebody's writing made it. See [wallStop].
    var wallHeightUnits by remember(night) { mutableFloatStateOf(0f) }
    val frameHeightUnits = if (frame.height == 0) 0.0 else frame.height / density.toDouble()
    val stop = wallStop(wallHeightUnits.toDouble(), frameHeightUnits)
    val range = travelRange(night.wallZ, stop)
    val gain = travelGain(night.contentLength)

    val travel = remember(night) { Travel(range.start.toFloat()) }
    // Placed items, in the shape selection wants them. Sorted furthest-first once, for
    // the painter's order the whole walk keeps.
    val placed = remember(night) { night.photos.map { PlacedItem(it.id, it.mine, it.z) } }
    val farthestFirst = remember(night) { night.photos.sortedByDescending { it.z } }

    // Which photograph each half of the screen would take. Both are lit, faintly: you
    // can see what a thumb would give you before you commit one to it.
    val litMine by remember(night) {
        derivedStateOf { focalPick(placed, travel.value.toDouble(), mine = true) }
    }
    val litTheirs by remember(night) {
        derivedStateOf { focalPick(placed, travel.value.toDouble(), mine = false) }
    }

    var zoomed by remember(night) { mutableStateOf<FlyoverPhoto?>(null) }
    var fps by remember { mutableIntStateOf(0) }

    // Inertia, and the clamp that makes the two ends of the walk real. Runs for as long
    // as the screen is up; a frame on which nothing moved invalidates nothing and so
    // costs a coroutine resume and no drawing at all.
    LaunchedEffect(night, range.start, range.endInclusive) {
        var frames = 0
        var since = 0L
        while (true) {
            withFrameNanos { now ->
                travel.settle(range.start.toFloat(), range.endInclusive.toFloat())
                if (BuildConfig.DEBUG) {
                    frames++
                    if (since == 0L) since = now
                    val elapsed = now - since
                    if (elapsed >= 1_000_000_000L) {
                        fps = (frames * 1_000_000_000L / elapsed).toInt()
                        frames = 0
                        since = now
                    }
                }
            }
        }
    }

    // What is decoded, and when. **Travel is the loading schedule**: the cover is a
    // guaranteed decode window — you cannot get past it faster than the drag allows —
    // so passing it is what pays for the next stretch. The bucket is what keeps this
    // from being a per-frame question: it changes once every [PrefetchBucket] units of
    // travel, and only then does any of this recompose.
    val thumbs = remember(night) { mutableStateMapOf<String, MediaThumb>() }
    val bucket by remember(night) {
        derivedStateOf { (travel.value / PrefetchBucket).toInt() }
    }
    LaunchedEffect(night, bucket) {
        val at = travel.value.toDouble()
        val wanted = night.photos
            .filter { net(it.z, at) in -PrefetchAhead..TurnEnd }
            .sortedBy { abs(net(it.z, at) - FocalPlane) }
            .take(HeldThumbs)
        // Held bitmaps are bounded by count rather than by bytes: the grid tier is a
        // fixed 512px longest edge (#98), so a count *is* a byte budget — about a
        // megabyte each, and never a surprise.
        val keep = wanted.map { it.id }.toSet()
        if (thumbs.size > HeldThumbs) (thumbs.keys - keep).forEach { thumbs.remove(it) }
        wanted.forEach { photo ->
            if (thumbs[photo.id] == null && photo.ref.isNotBlank()) {
                thumbs[photo.id] = loadThumb(Uri.parse(photo.ref))
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Ground)
            .onSizeChanged { frame = it }
            // Right goes back, the same as everywhere else on the spine. Registered
            // first so it can never be eaten by the travel drag.
            .swipeRightToBack(onBack = onBack)
            .pointerInput(night, gain) {
                detectVerticalDragGestures(
                    onDragStart = { travel.velocity = 0f },
                    onVerticalDrag = { change, dy ->
                        // Drag *up* to go into the night: the hand moves the way the
                        // night does, not the way the camera does.
                        travel.drag(-dy * gain.toFloat(), change.uptimeMillis)
                    },
                )
            }
            .pointerInput(night, placed) {
                detectTapGestures { at ->
                    // The one bit travel cannot resolve is which flank, and it is
                    // carried by where the thumb already was. No control needed — and
                    // no control possible, since a stick spawning under your thumb
                    // makes every grab a tap as well.
                    val mine = at.x < size.width / 2f
                    val id = if (mine) litMine else litTheirs
                    zoomed = night.photos.firstOrNull { it.id == id }
                }
            },
    ) {
        Floor(night, travel, frame, density)
        Wall(
            night = night,
            travel = travel,
            frame = frame,
            density = density,
            onHeight = { wallHeightUnits = it },
        )
        Markers(night, travel, frame, density)
        farthestFirst.forEach { photo ->
            FlankPhoto(
                modifier = Modifier.align(Alignment.Center),
                photo = photo,
                travel = travel,
                frame = frame,
                density = density,
                thumb = thumbs[photo.id],
                lit = photo.id == (if (photo.mine) litMine else litTheirs),
            )
        }
        // The **Covers**, at the depths the composer gave them, furthest first like
        // everything else on the walk — the composer hands them back in walking order,
        // so reversed is back-to-front. The key to the ground stands on the first
        // billboard only, the run's if it has one and otherwise the one **Gig**'s,
        // because the cast belongs to the walk and not to each night of it.
        night.covers.asReversed().forEach { cover ->
            Cover(
                cover = cover,
                people = if (cover === night.covers.first()) night.people else emptyList(),
                travel = travel,
                frame = frame,
                density = density,
            )
        }
        // Whose the lit photograph is, said once, unscaled — the outline carries the
        // colour and this carries the name, and neither has to be read off a caption
        // rushing past at an angle.
        LitName(night, litMine, litTheirs)

        if (BuildConfig.DEBUG) {
            Text(
                "$fps fps · ${night.photos.size} on the night · ${thumbs.size} held",
                color = Faint,
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            )
        }
    }

    zoomed?.let { photo ->
        Zoomed(photo = photo, loadFull = loadFull, onDismiss = { zoomed = null })
    }
}

/**
 * Travel, held outside composition.
 *
 * A plain object with one snapshot-backed float in it. Everything that moves reads
 * [value] from a draw lambda, so a drag repaints without rebuilding anything — which is
 * the difference between a walk and a slideshow of recompositions.
 */
internal class Travel(start: Float) {
    var value by mutableFloatStateOf(start)
    var velocity = 0f
    private var lastAt = 0L

    fun drag(by: Float, atMillis: Long) {
        value += by
        val dt = (atMillis - lastAt).coerceIn(1L, 64L)
        // Units per frame, so the decay below is in frames and not in wall clock.
        velocity = by / dt * 16f
        lastAt = atMillis
    }

    /** One frame of coasting, and the two ends of the walk. */
    fun settle(from: Float, to: Float) {
        if (velocity != 0f) {
            value += velocity
            velocity *= Friction
            if (abs(velocity) < 0.05f) velocity = 0f
        }
        if (value < from) {
            value = from
            velocity = 0f
        }
        if (value > to) {
            value = to
            velocity = 0f
        }
    }
}

/**
 * The floor and the spine: **one `Canvas`, not four hundred composables.**
 *
 * Dashes are projected as quads rather than drawn flat and squashed — both ends of a
 * dash lie at different depths, which is the whole of why a receding line reads as a
 * floor. The runs pass *under* the wall and carry on into the dark: **one terminus per
 * ending**. The wall and the floor both stopping said "over" twice, and it read as
 * ominous rather than final.
 */
@Composable
private fun BoxScope.Floor(night: FlyoverNight, travel: Travel, frame: IntSize, density: Float) {
    Canvas(Modifier.fillMaxSize()) {
        if (frame.height == 0) return@Canvas
        val at = travel.value.toDouble()
        val vpX = size.width / 2f
        val vpY = size.height * VanishY
        val unit = density

        fun screen(x: Double, y: Double, z: Double): Offset? {
            val n = net(z, at)
            if (!visible(n)) return null
            val s = projectedScale(n).toFloat()
            return Offset(vpX + (x * unit * s).toFloat(), vpY + (y * unit * s).toFloat())
        }

        // The spine: dots receding, evenly, all the way to the wall.
        val firstDot = (((at - NearCull) / DotGap).toInt()) * DotGap
        var z = firstDot
        while (z < at + FarCull) {
            val n = net(z, at)
            if (visible(n)) {
                val s = projectedScale(n).toFloat()
                screen(0.0, 0.0, z)?.let {
                    drawCircle(Faint.copy(alpha = opacity(n) * 0.8f), radius = 1.6f * unit * s, center = it)
                }
            }
            z += DotGap
        }

        // Floor lines: amber under the left flank, because that side is yours, and one
        // per contact under the right, where their media sits.
        fun floorRun(x: Double, colour: Color, width: Double) {
            var dz = firstDot
            while (dz < at + FarCull) {
                val n = net(dz, at)
                if (visible(n)) {
                    val near = screen(x - width / 2, FloorY, dz)
                    val near2 = screen(x + width / 2, FloorY, dz)
                    val far = screen(x + width / 2, FloorY, dz + DashLength)
                    val far2 = screen(x - width / 2, FloorY, dz + DashLength)
                    if (near != null && near2 != null && far != null && far2 != null) {
                        val path = Path().apply {
                            moveTo(near.x, near.y)
                            lineTo(near2.x, near2.y)
                            lineTo(far.x, far.y)
                            lineTo(far2.x, far2.y)
                            close()
                        }
                        drawPath(path, colour.copy(alpha = opacity(n) * floorOpacity(n)))
                    }
                }
                dz += DashGap
            }
        }
        floorRun(-FlankX, Amber, 3.0)
        night.people.forEachIndexed { i, person ->
            // A line means they were there. Whether they also handed over photographs
            // is its weight — a thin line is somebody who stood beside you and took
            // nothing, which is not the same as somebody who was not there at all.
            floorRun(
                floorLineX(i, night.people.size),
                railColor(person.colourIndex),
                if (person.gaveAt.isEmpty()) 1.0 else 2.0,
            )
        }
    }
}

/**
 * The song markers, on their own `Canvas`.
 *
 * **Text at depth is re-rasterised, never scaled.** A layer scaled up blurs its glyphs,
 * and these are read across the whole range of the walk — so the size is computed from
 * the projection and the text is drawn at that size. Quantised to half a point so that
 * a slow drag re-measures a handful of times rather than sixty times a second.
 *
 * Drawn after the wall and before the photographs: a marker is always nearer than the
 * wall, so it may never be hidden by it. Against a photograph it is a coin toss the
 * geometry makes almost impossible to observe — markers sit on the centre line and
 * photographs flank it, and the two barely overlap at any depth.
 */
@Composable
private fun BoxScope.Markers(night: FlyoverNight, travel: Travel, frame: IntSize, density: Float) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxSize()) {
        if (frame.height == 0) return@Canvas
        val at = travel.value.toDouble()
        val vpX = size.width / 2f
        val vpY = size.height * VanishY
        night.markers.forEach { marker ->
            val n = net(marker.z, at)
            if (!visible(n)) return@forEach
            val s = projectedScale(n).toFloat()
            val alpha = opacity(n)
            if (alpha <= 0.02f) return@forEach
            val sizeSp = (MarkerText * s).let { (it * 2f).toInt() / 2f }
            if (sizeSp < 3f) return@forEach
            val label = marker.number?.let { "$it  ${marker.label}" } ?: marker.label
            val laid = measurer.measure(
                label,
                TextStyle(
                    fontSize = sizeSp.sp,
                    color = when {
                        marker.encore -> Faint
                        // A song only my **Log** caught is mine, and says so in the
                        // colour that means mine everywhere else.
                        marker.loggedOnly -> Amber
                        else -> Ink
                    }.copy(alpha = alpha),
                    fontWeight = if (marker.agreed) FontWeight.Medium else FontWeight.Normal,
                ),
            )
            drawText(
                laid,
                topLeft = Offset(
                    vpX - laid.size.width / 2f,
                    vpY + (MarkerY * density * s).toFloat() - laid.size.height / 2f,
                ),
            )
        }
    }
}

/**
 * One photograph, on its flank.
 *
 * The scene's projection is done by hand and applied as a plain scale and translation.
 * The only thing left to `graphicsLayer`'s own camera is [flankTilt]'s turn. At
 * [RestTilt] there is no turn at all, so nothing is left to it and the two cameras
 * cannot disagree. On the way past it reaches [PassTilt], where they do: a card that
 * far round keystones hard under whatever `cameraDistance`
 * defaults to. That is wanted rather than tolerated — a panel going by does recede —
 * but it does mean the two cameras disagree visibly at exactly the moment a photograph
 * is leaving, and if the departure ever looks wrong it is this and not the arithmetic.
 * The fix would be to set `cameraDistance` from [FocalLength]; left alone until
 * something on a screen says it needs setting.
 */
@Composable
private fun FlankPhoto(
    modifier: Modifier,
    photo: FlyoverPhoto,
    travel: Travel,
    frame: IntSize,
    density: Float,
    thumb: MediaThumb?,
    /** The one this flank's half of the screen would give you. */
    lit: Boolean,
) {
    val bitmap = thumb?.bitmap
    // The authored size, from the picture's own shape. A night is portraits and
    // landscapes mixed, and squaring them all off would be a different night.
    val long = PhotoLongEdge
    val short = if (bitmap == null || bitmap.width == 0 || bitmap.height == 0) long * 0.72
    else long * (minOf(bitmap.width, bitmap.height).toDouble() / maxOf(bitmap.width, bitmap.height))
    val portrait = bitmap != null && bitmap.height > bitmap.width
    val w = if (portrait) short else long
    val h = if (portrait) long else short
    val outline = if (photo.mine) Amber else railColor(photo.person?.colourIndex ?: 0)

    Box(
        modifier
            .size(w.toFloat().dp, h.toFloat().dp)
            .graphicsLayer {
                val n = net(photo.z, travel.value.toDouble())
                val a = flankOpacity(n)
                alpha = a
                if (a <= 0f) return@graphicsLayer
                val s = projectedScale(n).toFloat()
                scaleX = s
                scaleY = s
                // Stepped out of the rank toward the spine as the walk reaches it,
                // and back to the wall as it goes by. Post-projection: the step is a
                // claim about the screen, and [flankScreenX] is where it is true.
                val off = flankScreenX(n).toFloat()
                translationX = (if (photo.mine) -off else off) * density
                translationY = frame.height * VanishY - frame.height / 2f
                // The turn is the departure: at rest all the way in, round to
                // parallel with the walk on the way past. Mirrored across the spine,
                // so both flanks turn away from the walker and not through each other.
                val turn = flankTilt(n).toFloat()
                rotationY = if (photo.mine) turn else -turn
            }
            .clip(RoundedCornerShape(6.dp))
            .background(Raised)
            // The **Focal plane** picks in silence otherwise: nothing on screen said
            // which of a dozen overlapping cards a thumb was about to take, so you
            // aimed at what you were looking at and got something else. The walk is
            // the selector (Variant F, no controls), which only works if the walk can
            // be seen selecting. Its own colour at full strength and a heavier edge;
            // everything else on that flank steps back to a hairline.
            .border(
                if (lit) 2.dp else 1.dp,
                if (lit) outline else outline.copy(alpha = 0.4f),
                RoundedCornerShape(6.dp),
            ),
    ) {
        bitmap?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (photo.isVideo) {
            // A poster frame at the same dwell as a photograph. Pressing play is what
            // makes it take over, and that happens in the zoom, not here.
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(22.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(Color.Black.copy(alpha = 0.45f)),
            ) {
                Text("▶", color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

/**
 * The **Cover**: the night's identity, and cover for the first stretch of decoding.
 *
 * You start at billboard distance and walk through it — it is culled like anything else
 * once you have gone through it, unlike the wall, which you never pass. The bottom row
 * is a key to the ground: you in amber on the left, the others on the right in the same
 * left-to-right order as their floor lines.
 */
@Composable
private fun BoxScope.Cover(
    cover: FlyoverCover,
    people: List<FlyoverPerson>,
    travel: Travel,
    frame: IntSize,
    density: Float,
) {
    Panel(travel = travel, frame = frame, density = density, z = cover.z, onHeight = {}) {
        Text(cover.billboard.title, color = Ink, fontFamily = FontFamily.Serif, fontSize = 30.sp)
        Spacer(Modifier.height(6.dp))
        Text(cover.billboard.where, color = Muted, fontSize = 14.sp)
        if (cover.billboard.chips.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                cover.billboard.chips.take(3).forEach { chip ->
                    Text(
                        chip,
                        color = Muted,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .border(1.dp, LineCol, RoundedCornerShape(999.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
        // The key stands on one billboard only, so a run states its cast once rather
        // than repeating it on every night's **Cover**. A **Cover** given nobody is a
        // **Gig**'s inside a run, and says nothing about the ground.
        if (people.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("you", color = Amber, fontSize = 12.sp)
                Row {
                    // Three names fit and twelve do not, so the key says how many it is
                    // not showing rather than running off the panel. The floor answers
                    // the same question in the same order for anyone who wants to count.
                    people.take(CoverNames).forEachIndexed { i, person ->
                        if (i > 0) Text(" · ", color = LineCol, fontSize = 12.sp)
                        Text(
                            person.name.ifBlank { "someone else" },
                            color = railColor(person.colourIndex),
                            fontSize = 12.sp,
                        )
                    }
                    if (people.size > CoverNames) {
                        Text("  +${people.size - CoverNames}", color = Faint, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("drag up to walk the night", color = Faint, fontSize = 12.sp)
        }
    }
}

/**
 * The **Wall**: what was said about the night.
 *
 * Anchored by its foot, so a long note grows it *upward* rather than down through the
 * floor — and how far short of it you stop follows that height ([wallStop]), which is
 * why its measurement is reported back up. The **Verdict** sits on each note's own
 * right edge: a verdict belongs to a note, not to the night.
 */
@Composable
private fun BoxScope.Wall(
    night: FlyoverNight,
    travel: Travel,
    frame: IntSize,
    density: Float,
    onHeight: (Float) -> Unit,
) {
    Panel(travel = travel, frame = frame, density = density, z = night.wallZ, onHeight = onHeight) {
        Text(
            "what was said about this night",
            color = Muted,
            fontFamily = FontFamily.Serif,
            fontSize = 15.sp,
        )
        Spacer(Modifier.height(12.dp))
        if (night.notes.isEmpty()) {
            // A curtain onto an empty view is a legitimate answer, and saying so is
            // better than a wall that looks like it failed to load.
            Text("Nothing yet.", color = Faint, fontSize = 13.sp)
        }
        night.notes.forEach { note ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 7.dp)) {
                Box(
                    Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(if (note.mine) Amber else railColor(note.person?.colourIndex ?: 0)),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            note.mine && note.personal -> "you · private"
                            note.mine -> "you · shared"
                            else -> note.person?.name?.ifBlank { null } ?: "someone else"
                        },
                        color = Faint,
                        fontSize = 10.sp,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        note.text,
                        color = Ink,
                        fontSize = 13.sp,
                        // The backstop on the height budget. Stepping back is what makes
                        // the wall's own text small, so past some length the honest fix
                        // is fewer words on the wall — the whole note is still in the
                        // room, one turn of the phone away.
                        maxLines = NoteLines,
                    )
                }
                verdictGlyph(note.verdict).takeIf { it.isNotEmpty() }?.let {
                    Spacer(Modifier.width(12.dp))
                    Text(it, fontSize = 15.sp)
                }
            }
        }
    }
}

/**
 * A panel standing on the floor at [z], projected.
 *
 * Scaled as a layer rather than re-laid-out, unlike the song markers, and the difference
 * is which way the scaling goes: a panel is only ever *minified* — you stop short of the
 * wall and you read the cover from in front of it — and minified text stays legible
 * where magnified text turns to mush. It also means the panel rasterises once and is
 * composited for the rest of the walk, which is what keeps an essay on the wall from
 * costing a text layout every frame.
 */
@Composable
private fun BoxScope.Panel(
    travel: Travel,
    frame: IntSize,
    density: Float,
    z: Double,
    onHeight: (Float) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var height by remember { mutableIntStateOf(0) }
    Column(
        Modifier
            .align(Alignment.Center)
            .width(PanelWidth)
            .onSizeChanged {
                height = it.height
                onHeight(it.height / density)
            }
            .graphicsLayer {
                val n = net(z, travel.value.toDouble())
                val a = opacity(n)
                alpha = a
                if (a <= 0f) return@graphicsLayer
                val s = projectedScale(n).toFloat()
                scaleX = s
                scaleY = s
                // Anchored by the foot: scaling about the bottom edge is what keeps the
                // panel standing on the floor whatever its height.
                transformOrigin = TransformOrigin(0.5f, 1f)
                translationY = (frame.height * VanishY + FloorY.toFloat() * density * s) -
                    (frame.height / 2f + height / 2f)
            }
            .clip(RoundedCornerShape(10.dp))
            .background(Raised)
            .border(1.dp, LineCol, RoundedCornerShape(10.dp))
            .padding(horizontal = 26.dp, vertical = 22.dp),
        content = content,
    )
}

/** Whose the lit photograph on each flank is, drawn flat and unscaled. */
@Composable
private fun BoxScope.LitName(night: FlyoverNight, litMine: String?, litTheirs: String?) {
    Row(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(if (litMine != null) "you" else "", color = Amber, fontSize = 11.sp)
        val person = night.photos.firstOrNull { it.id == litTheirs }?.person
        Text(
            person?.name?.ifBlank { "someone else" } ?: "",
            color = railColor(person?.colourIndex ?: 0),
            fontSize = 11.sp,
        )
    }
}

/**
 * One picture, taken out of the night and filled to the frame.
 *
 * The zoom is where the full-screen tier earns its place (#98) — and where a video
 * stops being a poster frame and takes over.
 */
@Composable
private fun Zoomed(
    photo: FlyoverPhoto,
    loadFull: suspend (Uri) -> android.graphics.Bitmap?,
    onDismiss: () -> Unit,
) {
    val uri = remember(photo.id) { Uri.parse(photo.ref) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(photo.id) { detectTapGestures { onDismiss() } },
    ) {
        if (photo.isVideo) {
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
            var full by remember(photo.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
            LaunchedEffect(photo.id) { full = loadFull(uri) }
            full?.let {
                Image(
                    it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

// --- The look ---------------------------------------------------------------
// Its own copies, as every screen in this package keeps: the palette is shared by
// agreement rather than by import, which is what lets one screen's dark be judged
// against its own content without moving everybody else's.

private val Ground = Color(0xFF0E0B14)
private val Raised = Color(0xFF14111B)
private val LineCol = Color(0xFF2E2740)
private val Ink = Color(0xFFEDE9F2)
private val Muted = Color(0xFF8B8299)
private val Faint = Color(0xFF5A5368)
private val Amber = Color(0xFFE7B24C)

/** Where the horizon sits: a little above the middle, so the floor has room to read. */
private const val VanishY = 0.46f

/** Degrees each photograph turns toward the walker. */

/** A photograph's longest edge, in flyover units. */
private const val PhotoLongEdge = 148.0

/** Spine dots, and the floor's dashes: length, and the gap between their starts. */
private const val DotGap = 30.0
private const val DashLength = 26.0
private const val DashGap = 60.0

/** The song marker's authored point size, and how far above the spine it sits. */
private const val MarkerText = 14f
private const val MarkerY = -46.0

/** How many names the cover's key shows before it starts counting instead. */
private const val CoverNames = 4

/** How much of a note the wall shows. */
private const val NoteLines = 8
private val PanelWidth = 560.dp

/**
 * How far ahead pictures are decoded, and how many are held at once.
 *
 * The grid tier is a fixed 512px longest edge and about a megabyte decoded, so a count
 * is a byte budget: twenty-four of them is ~24 MB, which is native-heap allocation on
 * every version this app runs on (minSdk 26) and so does not eat the Java heap that a
 * cheap phone is actually short of.
 */
private const val PrefetchAhead = 2200.0
private const val PrefetchBucket = 400f
private const val HeldThumbs = 24

/** How fast coasting dies. Per frame, at sixty of them a second. */
private const val Friction = 0.94f
