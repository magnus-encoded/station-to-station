package io.github.magnusencoded.stationtostation.ui.flyover

/**
 * The **Flyover**'s arithmetic: where everything on the night stands, how big it draws
 * from where you are, and what a thumb on either half of the screen would take (#278).
 *
 * **All of it is pure.** Compose has no shared 3D scene — no `preserve-3d`, no depth
 * sorting, no world to translate — so there is nothing to hand a scene graph and every
 * item's screen rect has to be computed anyway. Computing it *here* rather than inside
 * a `graphicsLayer` block buys the thing #278 asks for by name: the projection hands
 * back every item's exact position, so tap-to-select is exact instead of approximate.
 * It also means the whole of the flyover except its pixels is asserted on the JVM,
 * which is the same trade [io.github.magnusencoded.stationtostation.ui.rowGeometry]
 * made for the Timelines resolution.
 *
 * **Units.** Everything here is in *flyover units*, an arbitrary scale shared with the
 * prototype so its measured numbers still mean something (a song marker every 260, a
 * minimum photo gap of 150). One unit is one density-independent point at the screen
 * plane; the caller converts once, at the canvas. Nothing here knows about pixels,
 * density, or Compose.
 *
 * **The z axis runs with the night.** `z` grows as the evening goes on: the **Cover**
 * stands at negative z in front of the first song, the **Wall** at the far end past the
 * last photograph. [travel] is how far you have walked. Everything visible is a
 * question about `net = travel - z` — how far *past* you a thing is — which is negative
 * for everything still ahead.
 */

// ---------------------------------------------------------------------------
// The fixed scale of the night. These are the prototype's, kept to the number:
// they were judged by eye on a real set and nothing here is free to drift from
// them without that judgement being made again.
// ---------------------------------------------------------------------------

/** Depth between two song markers. Even spacing, always — see [songZ]. */
const val SongGap = 260.0

/**
 * The closest two photographs on the **same flank** may ever stand.
 *
 * **A correctness constraint, not polish.** Photographs placed purely by `capturedAt`
 * stack unpickably wherever a burst happened, and the encore burst is real rather than
 * an artefact of invented data — everybody photographs the encore. Per flank, because
 * opposite flanks never occlude each other.
 *
 * The good consequence: a dense moment *stretches*. Density reads as duration of
 * travel rather than as a heap.
 */
const val MinGap = 150.0

/** How far off the spine each flank stands. */
const val FlankX = 150.0

/** The floor, below the spine. */
const val FloorY = 132.0

/**
 * The camera's focal length, in the same units. The projection is `f / (f - net)`, so
 * an item reaching `net == FocalLength` would be *at* the lens and blow up to
 * infinity — which is what [NearCull] stands clear of.
 */
const val FocalLength = 900.0

/**
 * The "you are here" plane, in front of the screen plane. The lit photograph on each
 * flank is whichever stands nearest this, and at this depth it draws about 1.5× its
 * authored size — big enough to be the thing you are looking at.
 */
const val FocalPlane = 300.0

/**
 * **The step** and **the turn**: a photograph leaves the rank to be looked at, and
 * turns flat to go past.
 *
 * Two movements, one after the other rather than at once, because they say different
 * things. On the way in a photograph **steps in toward the spine** — out of the rank
 * and into the aisle — at its resting angle: that is the walk saying *this is the one*,
 * and it is a step you can see coming a couple of gaps away. Once past the **Focal
 * plane** it stops being yours to take, so it turns **parallel to the walk** and falls
 * back to the wall, slipping by as a panel rather than smearing across the screen as a
 * billboard.
 *
 * This is what lets **Variant F** have no controls at all. With a fixed angle and a
 * fixed offset the three photographs standing between the walker and the plane are
 * nearer, so larger, and drawn on top: the one a tap would take was buried behind the
 * ones you were actually looking at. Stepped in it is the one nearest the spine, and
 * everything past it has turned edge-on and covers nothing.
 */

/** In the rank: dead-on. Every photograph the walk has not reached yet stands square
 *  to the walker, and because they share [FlankX] the nearer ones project further out —
 *  so the rank reads as a picket of overlapping left edges receding to the vanishing
 *  point. That cascade is the depth cue, and it is also what the step breaks: the one
 *  photograph out of line is the one that can be seen whole. */
const val RestTilt = 0.0

/** Parallel to the walk. Not a clean 90°, which collapses the card to a line and
 *  gives the rasteriser nothing to hold on to; a hairline is the point of leaving it
 *  short. */
const val PassTilt = 88.0

/** How far in off [FlankX] a photograph steps to be the one. Half the flank, which is
 *  the geometric limit rather than a taste: a photograph at the **Focal plane** draws at
 *  one and a half times its authored size, so a landscape card standing at `FlankX / 2`
 *  has its inner edge exactly on the spine. Further, and the two flanks meet in the
 *  middle of the aisle. */
const val SlideIn = FlankX / 2

/**
 * The whole movement — come in, stand there, turn away and go — inside one gap.
 *
 * **One photograph off the wall at a time.** On a packed flank the cards are [MinGap]
 * apart, so a cycle any longer than that has the next one stepping out before the last
 * has finished leaving: two and three displaced cards at once, and none of them
 * obviously *the* one. Fitting the cycle to the packing is what makes "out of line"
 * mean something.
 *
 * It also buys the thing the step kept fighting for. The inward step has to outrun
 * [projectedScale]'s swelling (see [flankScreenX]); over a third of a gap it outruns it
 * nearly sevenfold instead of barely twice, so the card comes in hard and stays inward
 * the whole way. And nothing is ever left standing between the pick and the exit —
 * [TurnEnd] is less than a gap past the **Focal plane**, so there is no room for one.
 */
const val CycleSpread = MinGap

/** The depth the step takes, finishing at [HoldFrom]. */
const val SlideSpread = CycleSpread / 3

/**
 * How long a photograph stands fully in the aisle before it leaves — the runway the
 * step arrives on.
 *
 * Without it the step is a triangle: the card touches the aisle for a single value of
 * travel and is already unwinding, at its greatest speed at the one moment it should be
 * still. There is nothing to aim a thumb at and nowhere for the eye to rest.
 *
 * Centred on the **Focal plane**, so it sits in the middle of the stretch where this
 * photograph is what [focalPick] answers with, and never straddles a handover.
 */
const val HoldSpread = CycleSpread / 3

/** The stretch either side of the **Focal plane** where one photograph is the one. */
const val HoldFrom = FocalPlane - HoldSpread / 2
const val HoldTo = FocalPlane + HoldSpread / 2

/** The depth the turn takes, starting at [HoldTo], and the depth the step unwinds
 *  over. */
const val TurnSpread = CycleSpread / 3

/**
 * Where a photograph leaves: the instant the turn completes.
 *
 * **Parallel is the exit.** Carrying on past [PassTilt] is a card turning inside out —
 * it starts showing its back edge and reads as swinging *through* the walker, which is
 * the one thing a rack of records never does. It is also where the projection gets
 * silly: at the shared [NearCull] a photograph is thirteen times its authored size and
 * covers the screen, and every one of those frames was a solid quad the walk had
 * already finished with.
 *
 * Photographs alone leave here. The floor and the spine keep the shared window, because
 * the road does run under you.
 */
const val TurnEnd = HoldTo + TurnSpread

/** In view, for something on a flank. */
fun flankVisible(net: Double): Boolean = net <= TurnEnd && net >= -FarCull

/**
 * A photograph's alpha: the shared [opacity] while it approaches, then out over the
 * turn. Squared, so it holds while there is still something to look at and goes in the
 * last of the swing, where it is a hairline anyway — a hard cut at [TurnEnd] leaves a
 * lit outline snapping off mid-air.
 */
fun flankOpacity(net: Double): Float {
    if (!flankVisible(net)) return 0f
    val past = ((net - HoldTo) / TurnSpread).coerceIn(0.0, 1.0)
    return opacity(net) * (1.0 - past * past).toFloat()
}

/**
 * How far out of the rank something at [net] has stepped: 0 at the wall, 1 fully into
 * the aisle. In three parts — come in over [SlideSpread], stand there for [HoldSpread],
 * go back to the wall over [TurnSpread] as it turns.
 *
 * **Both ramps ease toward the hold**, which is what makes the aisle read as somewhere
 * a photograph is *attracted to* rather than a value it passes through. Coming in it
 * decelerates to a stop; leaving, it creeps before it goes. A linear ramp gives the
 * card its greatest speed at exactly the moment it arrives, which is the one moment it
 * should be still.
 *
 * The easings are deliberately not the same shape. `t(2 - t)` is fast then slow and
 * `t²` is slow then fast, so the whole movement is one arrival and one departure and
 * never a symmetrical bounce.
 */
fun flankStep(net: Double): Double = when {
    net < HoldFrom -> {
        val t = ((net - (HoldFrom - SlideSpread)) / SlideSpread).coerceIn(0.0, 1.0)
        t * (2.0 - t)
    }
    net <= HoldTo -> 1.0
    else -> {
        val t = ((net - HoldTo) / TurnSpread).coerceIn(0.0, 1.0)
        1.0 - t * t
    }
}

/**
 * Where a photograph on a flank actually draws: units from the spine, at screen scale.
 *
 * **The step has to outrun the perspective, or it does not happen.** A card on its way
 * to the plane is swelling under [projectedScale], and swelling carries it outward from
 * the vanishing point; the inward step is subtracted from a number that is being
 * multiplied. Get the ratio wrong and the two cancel *exactly* — with [SlideIn] over
 * [SlideSpread] equal to `FlankX / (FocalLength - rampStart)` the photograph tracks a
 * dead-straight line up the screen while the arithmetic insists it moved sideways.
 * That was the first attempt at this, at 0.15 against 0.15. It is 0.5 against 0.2 now.
 *
 * Which is why this is the function the screen uses and the tests assert on. An inward
 * step is a claim about what reaches the eye, and only this side of the projection can
 * make it.
 */
fun flankScreenX(net: Double): Double = flankOffset(net) * projectedScale(net)

/** How far off the spine something at [net] stands, in units. */
fun flankOffset(net: Double): Double = FlankX - SlideIn * flankStep(net)

/**
 * The angle for something at [net], in degrees off face-on.
 *
 * Flat all the way in and through the hold — the turn is the *departure*, and turning
 * on approach as well would make the two halves of the movement say the same thing
 * twice. It begins where the photograph stops being the one a tap takes.
 */
fun flankTilt(net: Double): Double {
    val past = ((net - HoldTo) / TurnSpread).coerceIn(0.0, 1.0)
    return RestTilt + (PassTilt - RestTilt) * past
}

/** Where a passed item begins to go, and where it is gone. Softens the last moment
 *  so nothing pops; you pass *through* it rather than seeing it vanish. */
const val NearFadeFrom = 480.0
const val NearCull = 830.0

/**
 * The far end of the drawn window. Not in the prototype, which drew the whole night
 * every frame because a desktop could afford to: at 40 photographs and a 12-song
 * spine that is a hundred-odd layers, most of them a few points across. The horizon
 * fades in instead, and what is past it is not composed at all.
 */
const val FarFadeFrom = 2600.0
const val FarCull = 3400.0

/** The **Cover** stands in front of the night, occluding the first stretch while it
 *  is still being decoded. You walk through it. */
const val CoverZ = -420.0

/**
 * The dark between the last photograph and the **Wall**.
 *
 * It has to be longer than [NearCull] or you arrive at the wall while the final
 * photographs are still streaming past you — and the encore burst lands exactly
 * there, which is what makes this a real case rather than a tidy one.
 */
const val WallGap = 1220.0

/**
 * The dark between one **Gig**'s stretch and the next, when the walk is a run of them
 * (#313).
 *
 * **A fixed interval, belonging to the Cover that introduces the next stretch.** It is
 * not derived from the hours or days between the two nights, and nothing anywhere in
 * the model records how long that real gap was: the walk is a concatenation, not
 * continuous time, and a corridor scaled to a night's sleep would be a claim the record
 * cannot support.
 *
 * The same number as [WallGap] for the same reason — longer than [NearCull], so the
 * last night's encore burst has finished streaming past you before the next **Cover**
 * is in front of you. Also how far ahead of the first **Gig**'s **Cover** the run's own
 * billboard stands, so the two are read one after the other rather than together.
 */
const val StretchGap = WallGap

/** How far short of the **Wall** you stop, at the least. Billboard distance: close
 *  enough that it dominates the frame and the notes are read, not squinted at. */
const val WallStopMin = 110.0

/**
 * How far short of the **Wall** you may be pushed by a tall one.
 *
 * #278 leaves the wall's height budget open and calls standing further back from a
 * long note "elegant, and means the stop distance can't be a constant". It is — and
 * unbounded it is also how the wall's text becomes unreadable, which is the other
 * half of the same tension (gotcha 6). So the stop distance grows to fit the wall and
 * stops growing here; past this the notes clamp instead. See [wallStop].
 */
const val WallStopMax = 620.0

/** How much of the frame a **Wall** is allowed to fill when you stop in front of it. */
const val WallFrameFill = 0.86

/** Spacing between two contacts' floor lines, and the widest the run of them may
 *  spread before they tighten instead — the same bargain [laneStep] strikes for
 *  **Lanes**, for the same reason: past it they push each other off the phone. */
const val FloorStep = 46.0
const val FloorSpread = 184.0

/** Where the first contact's floor line sits, right of the spine. */
const val FloorFirstX = 96.0

/** Units of travel per point of drag, before the night's own length is taken into
 *  account. See [travelGain]. */
const val BaseTravelGain = 2.2

/** The length of night [travelGain] is calibrated against — about twelve songs. */
const val ReferenceLength = 3600.0

/** One item on the walk, before it has been given a place. */
data class FlyoverItem(
    val id: String,
    /** Which flank: mine on the left, a **Contact**'s on the right. */
    val mine: Boolean,
    /** When the camera took it. Null is a real state — see [placeMedia]. */
    val capturedAt: Long?,
)

/** One item on the walk, placed. [z] is where it stands along the night. */
data class PlacedItem(val id: String, val mine: Boolean, val z: Double)

/**
 * Place a night's photographs and videos along the spine.
 *
 * **Placement is `capturedAt`, full stop** — each item at its position in the range
 * `min(capturedAt)..max(capturedAt)` of *this night's own* media. Not zero-based: a
 * night whose first photograph is an hour into the set must not leave an hour of empty
 * spine in front of it. There is no shared-first grouping and no vault-to-the-back:
 * here order is a position *in space*, and sorting by disposition would put
 * photographs where they were not taken. Whose camera it came from is carried by the
 * flank, and its sharing state by the outline — both attributes, neither a position.
 *
 * **Two constraints, and the second one is the subtle one.** Spacing per flank alone
 * lets a one-sided burst push its neighbour past photographs taken *after* it — the
 * prototype's own data does this, 6 inverted pairs out of 231, the worst by 225 units
 * against a song spacing of 260. So an item is placed no earlier than:
 *
 *  - a full [MinGap] past the last item **on its own flank**, or it stacks unpickably;
 *  - the last item on **either** flank, because opposite flanks do not occlude each
 *    other and so need order but not distance.
 *
 * The density rule fights chronology, and **chronology wins**.
 *
 * **An unknown capture time sorts last.** `capturedAt` is documented as null "when
 * unknowable", and a night can hold one. Nothing here may invent a moment for it — the
 * whole point of the field being nullable is that a wrong timestamp on a keepsake is
 * worse than an honest gap — so it goes after everything the record *does* know the
 * time of, in the order it was stored, which is the same answer
 * [io.github.magnusencoded.stationtostation.data.bandsOf] already gives when it sorts
 * **Received media** `nullsLast`. It is still a claim about position, and the only
 * alternative that isn't would be leaving the photograph off the night.
 */
fun placeMedia(items: List<FlyoverItem>, songCount: Int): List<PlacedItem> {
    if (items.isEmpty()) return emptyList()
    // Stable, so items with no capture time — and items sharing one — keep the order
    // they were stored in rather than being shuffled against each other.
    val ordered = items.sortedWith(compareBy(nullsLast()) { it.capturedAt })
    val timed = ordered.mapNotNull { it.capturedAt }
    val first = timed.minOrNull()
    val last = timed.maxOrNull()
    val span = if (first == null || last == null) 1.0 else maxOf(1.0, (last - first).toDouble())
    val total = maxOf(1, songCount) * SongGap

    var lastMine = Double.NEGATIVE_INFINITY
    var lastTheirs = Double.NEGATIVE_INFINITY
    var lastAny = Double.NEGATIVE_INFINITY
    return ordered.map { item ->
        val raw = if (item.capturedAt == null || first == null) {
            // Nothing to place it by. The two constraints below are the whole of its
            // position, which puts it after everything that is dated and in front of
            // nothing at all.
            Double.NEGATIVE_INFINITY
        } else {
            ((item.capturedAt - first) / span) * (total - SongGap) + SongGap * 0.5
        }
        val sameFlank = if (item.mine) lastMine else lastTheirs
        val z = maxOf(raw, sameFlank + MinGap, lastAny)
        if (item.mine) lastMine = z else lastTheirs = z
        lastAny = z
        PlacedItem(item.id, item.mine, z)
    }
}

/** Where song number [index] stands. Evenly spaced, deliberately: the only offsets
 *  that exist for a night are the manual stamps inside a video, so anything that
 *  aligned a photograph to a song would be inventing a claim about the night. */
fun songZ(index: Int): Double = index * SongGap

/**
 * Where the night's content actually ends, once the bursts have been spread out.
 *
 * The spread is why this is a measurement and not `songCount * SongGap`: an encore
 * everybody photographed pushes the last photograph well past the last song.
 */
fun contentEnd(placed: List<PlacedItem>, songCount: Int): Double =
    maxOf(maxOf(1, songCount) * SongGap - SongGap, placed.maxOfOrNull { it.z } ?: 0.0)

/** Where the **Wall** stands: clear of the night by more than the cull distance. */
fun wallZ(placed: List<PlacedItem>, songCount: Int): Double = contentEnd(placed, songCount) + WallGap

/**
 * How far past you a thing at [z] is. Negative for everything still ahead.
 *
 * The one derived quantity the whole screen is written in terms of, so that "have I
 * gone through it yet" is a sign test rather than four different comparisons.
 */
fun net(z: Double, travel: Double): Double = travel - z

/**
 * How big a thing at [net] draws, as a multiple of its authored size.
 *
 * The projection, done directly and not out of `cameraDistance` — which is per-layer
 * rather than a scene, and would hand back no screen rect to hit-test against. At the
 * screen plane this is 1; at the [FocalPlane] about 1.5; far down the night it tends
 * to nothing.
 *
 * Guarded at the lens: `f - net` reaching zero is a division by zero and, one frame
 * earlier, a photograph smeared across the whole frame. [NearCull] means a caller
 * following the rules never gets near it, and this still refuses to produce it.
 */
fun projectedScale(net: Double): Double = FocalLength / maxOf(1.0, FocalLength - net)

/**
 * Whether a thing at [net] is drawn at all: past the near cull it is behind you, past
 * the far one it has not been reached yet.
 */
fun visible(net: Double): Boolean = net <= NearCull && net >= -FarCull

/**
 * How solid a thing at [net] is, 0 when it is not drawn.
 *
 * Both ends taper. The near one softens the moment you pass through something; the far
 * one is a horizon, so the night arrives out of the dark rather than appearing.
 */
fun opacity(net: Double): Float = when {
    !visible(net) -> 0f
    net > NearFadeFrom -> ((NearCull - net) / (NearCull - NearFadeFrom)).toFloat()
    net < -FarFadeFrom -> ((FarCull + net) / (FarCull - FarFadeFrom)).toFloat()
    else -> 1f
}.coerceIn(0f, 1f)

/**
 * The floor's own fade, on top of [opacity].
 *
 * Floor lines come up out of the dark — faint at distance, solid as you reach them —
 * so that the far convergence, where every dash piles into one bright point, stops
 * competing with what is actually ahead of you.
 */
fun floorOpacity(net: Double): Float =
    ((net + 1500.0) / 1000.0).coerceIn(0.1, 1.0).toFloat()

/**
 * Where the walk begins and ends.
 *
 * You start at billboard distance from the **Cover** and you stop [stop] short of the
 * **Wall**: **you reach it, you never pass it**. There is exactly one terminus, which
 * is why the floor keeps going underneath it.
 */
fun travelRange(wallZ: Double, stop: Double): ClosedFloatingPointRange<Double> =
    (CoverZ - WallStopMin)..(wallZ - stop)

/**
 * How far short of the **Wall** to stop, given how tall it turned out to be.
 *
 * #278's open question, answered in the direction it calls elegant: the wall is
 * anchored by its foot and a long note grows it *upward*, so rather than clamping the
 * night's prose to fit a fixed viewpoint, the viewpoint steps back until the whole
 * wall is in frame. A night nobody wrote about is met at billboard distance; a night
 * somebody wrote an essay about is met from further back.
 *
 * Bounded at [WallStopMax] because the same step backwards is what makes the text
 * small (gotcha 6), and past some height the honest fix is fewer words on the wall
 * rather than a longer walk away from it.
 *
 * Both heights are in flyover units — the caller measures the wall at its authored
 * size, which is exactly what the layout gives it before any of this is applied.
 */
fun wallStop(wallHeight: Double, frameHeight: Double): Double {
    if (wallHeight <= 0.0 || frameHeight <= 0.0) return WallStopMin
    // projected = wallHeight * f / (f + stop) <= frameHeight * fill
    val fits = FocalLength * (wallHeight / (frameHeight * WallFrameFill) - 1.0)
    return fits.coerceIn(WallStopMin, WallStopMax)
}

/**
 * How much travel one point of drag buys, on a night of [contentLength].
 *
 * #278's gotcha 9: two hundred photographs at a 150 gap is thirty thousand units of
 * travel, and it "needs a cap or an adaptive gap — a design decision, not a perf one".
 * Both of those spend something the walk cannot afford. A cap drops photographs off
 * the end of the night. An adaptive gap shrinks [MinGap], which is the one constraint
 * here that exists for correctness rather than for looks — the burst goes back to
 * being an unpickable heap.
 *
 * So neither: the *spacing* is fixed and the *speed* follows the night. A long night
 * is still longer to walk — density must keep reading as duration — but it is longer
 * by a factor of four at the most, rather than by a factor of ten. Left for the review
 * pass to judge against a thumb, which is the only thing that can judge it.
 */
fun travelGain(contentLength: Double): Double =
    BaseTravelGain * (contentLength / ReferenceLength).coerceIn(1.0, 4.0)

/**
 * Which photograph a thumb on one flank would take: whatever stands nearest the
 * **focal plane** on that side.
 *
 * **Travel already implies selection**, which is what killed the twin sticks — they
 * were a HUD over a solved problem, and worse, on-screen sticks spawn where your thumb
 * lands so every stick grab was also a tap. The one bit travel cannot resolve is
 * *which flank*, and that is carried by where the thumb already was.
 *
 * Only ever picks something that is actually on screen: a tap must never open a
 * photograph you cannot see, which is what restricting this to the drawn window buys.
 * Null when that flank has nothing in view.
 */
fun focalPick(placed: List<PlacedItem>, travel: Double, mine: Boolean): String? =
    placed.asSequence()
        .filter { it.mine == mine }
        .map { it to net(it.z, travel) }
        .filter { (_, n) -> flankVisible(n) }
        .minByOrNull { (_, n) -> kotlin.math.abs(n - FocalPlane) }
        ?.first?.id

/**
 * Where contact number [index] of [count] draws their floor line.
 *
 * One line each, under the right flank, where their media sits. They tighten rather
 * than spreading off the phone once there are more than a few — the same answer
 * [io.github.magnusencoded.stationtostation.ui.laneStep] gives for **Lanes**, and for
 * the same reason.
 */
fun floorLineX(index: Int, count: Int): Double {
    val step = if (count <= 0) FloorStep else minOf(FloorStep, FloorSpread / count)
    return FloorFirstX + index * step
}
