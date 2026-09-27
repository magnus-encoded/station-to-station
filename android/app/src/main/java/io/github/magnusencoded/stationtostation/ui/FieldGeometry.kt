package io.github.magnusencoded.stationtostation.ui

import kotlin.math.max
import kotlin.math.min

/**
 * Where everything on the Settings **Field** goes, as numbers (#563).
 *
 * The **Field** is a picture you move around in, and a picture is exactly the thing that
 * cannot be checked by looking once: a line that crosses a tile on one phone is fine on
 * another, and panning that loses the whole **Field** off the edge only happens at the
 * zoom nobody tried. So the drawing and the gestures both read [fieldLayout] and never
 * work out a position of their own, and `FieldGeometryTest` asserts what the eye would.
 *
 * Everything here is in density-independent points, with its own point and rect types
 * rather than Compose's, so it runs in a plain JVM test and carries over term for term
 * to `FieldGeometry.swift`. The canvas is the only thing that converts to pixels.
 *
 * Read left to right it is **Outer** to **Inner**, like everywhere else: the inputs,
 * one strip per kind, run their lines into a join; **My timeline** sits on the trunk;
 * past it the lines split out to the **Alcoves**.
 */
object FieldDp {
    const val Margin = 24f
    const val TileSize = 56f
    const val ColumnWidth = 92f
    const val StripPad = 8f
    /** The strip's title row, above its tiles. */
    const val StripHeader = 30f
    /** The name under a tile, with the 4 between them. */
    const val LabelRoom = 18f
    const val LaneGap = 6f
    const val LaneBottomPad = 10f
    const val StripGap = 28f
    const val CornerRadius = 8f
    /** How far right of its tile a line turns down into its lane. */
    const val DropOffset = 10f
    /** From the inputs' shared right edge to where their lines meet. */
    const val JoinRun = 62f
    /** Join to the timeline box, and the box to the split. */
    const val HubGap = 22f
    const val HubW = 72f
    const val HubH = 210f
    /** From the split to the alcove strips. */
    const val AlcoveRun = 54f
    const val FitMargin = 16f
    const val MinZoom = 0.35f
    const val MaxZoom = 2.5f
    /** Screen points of the **Field** that panning always leaves in view. */
    const val Keep = 48f
    /** A rightward drag from the left edge past this goes back, as `swipeRightToBack` does. */
    const val BackOutThreshold = 110f
}

data class FieldPoint(val x: Float, val y: Float)

data class FieldRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2

    fun contains(p: FieldPoint): Boolean = p.x in left..right && p.y in top..bottom
}

/** One strip of tiles. Its [ids] run left to right in the order the graph lists them. */
data class FieldStrip(
    val strip: ServiceStrip,
    val role: ServiceRole,
    val rect: FieldRect,
    val ids: List<String>,
)

data class FieldTile(val id: String, val center: FieldPoint, val rect: FieldRect)

/**
 * An input's line: out of its tile, down into a lane of its own under the tiles, along
 * to the strip's right edge, then a curve to the join.
 *
 * The leftmost tile takes the *lowest* lane. A line only ever runs right, under the
 * tiles to its right, whose lines turn down short of it into lanes above it — so no
 * line crosses another or a tile, however many a strip holds.
 */
data class FieldLane(
    val id: String,
    /** The corners, before the curve. Drawn with [FieldDp.CornerRadius] at each bend. */
    val corners: List<FieldPoint>,
    val join: FieldPoint,
)

/** A line out to an **Alcove**: the same curve, from the split to the tile's left edge. */
data class FieldAlcoveLine(val id: String, val from: FieldPoint, val to: FieldPoint)

/** How the **Field** sits on screen: `screen = offset + zoom * field`. */
data class FieldView(val zoom: Float, val offsetX: Float, val offsetY: Float)

class FieldLayout(
    val strips: List<FieldStrip>,
    val tiles: List<FieldTile>,
    val lanes: List<FieldLane>,
    val alcoveLines: List<FieldAlcoveLine>,
    val join: FieldPoint,
    val split: FieldPoint,
    val timeline: FieldRect,
    /** The input strips together: what the default view is fitted to. */
    val inputBlock: FieldRect,
    val width: Float,
    val height: Float,
    val viewportW: Float,
    val viewportH: Float,
) {
    fun tile(id: String): FieldTile? = tiles.firstOrNull { it.id == id }

    /**
     * The inputs filling the height, with room around them, and never wider than the
     * screen: in portrait that is the width that binds. Then left-aligned, so the
     * **Field** opens on its **Outer** edge, or centred when all of it fits.
     */
    val defaultView: FieldView
        get() {
            val zoom = min(
                viewportH / (inputBlock.height + 2 * FieldDp.FitMargin),
                viewportW / (inputBlock.right + FieldDp.FitMargin),
            ).coerceIn(FieldDp.MinZoom, FieldDp.MaxZoom)
            return FieldView(
                zoom = zoom,
                offsetX = leftEdgeOffsetX(zoom),
                offsetY = viewportH / 2 - zoom * inputBlock.centerY,
            )
        }

    /**
     * The furthest right the **Field** can sit at [zoom]: its left edge at the screen's,
     * or centred when it is narrower than the screen. Being here is what lets a
     * rightward drag mean back rather than pan.
     */
    fun leftEdgeOffsetX(zoom: Float): Float = max(0f, (viewportW - width * zoom) / 2)

    /** Zoom inside its limits, and at least [FieldDp.Keep] of the **Field** on screen each way. */
    fun clamp(view: FieldView): FieldView {
        val zoom = view.zoom.coerceIn(FieldDp.MinZoom, FieldDp.MaxZoom)
        return FieldView(
            zoom = zoom,
            offsetX = within(view.offsetX, FieldDp.Keep - width * zoom, leftEdgeOffsetX(zoom)),
            offsetY = within(view.offsetY, FieldDp.Keep - height * zoom, viewportH - FieldDp.Keep),
        )
    }

    /** Zoom by [factor] keeping the point under the fingers ([cx], [cy], screen) where it is. */
    fun zoomAbout(view: FieldView, cx: Float, cy: Float, factor: Float): FieldView {
        val zoom = (view.zoom * factor).coerceIn(FieldDp.MinZoom, FieldDp.MaxZoom)
        val k = zoom / view.zoom
        return clamp(FieldView(zoom, cx - (cx - view.offsetX) * k, cy - (cy - view.offsetY) * k))
    }

    fun pan(view: FieldView, dx: Float, dy: Float): FieldView =
        clamp(view.copy(offsetX = view.offsetX + dx, offsetY = view.offsetY + dy))

    fun atLeftEdge(view: FieldView): Boolean = view.offsetX >= leftEdgeOffsetX(view.zoom) - 0.5f

    private fun within(v: Float, lo: Float, hi: Float): Float =
        if (lo > hi) (lo + hi) / 2 else v.coerceIn(lo, hi)
}

fun fieldLayout(graph: ServiceGraph, viewportW: Float, viewportH: Float): FieldLayout {
    fun groups(role: ServiceRole): List<Pair<ServiceStrip, List<String>>> =
        graph.nodes.filter { it.role == role }
            .groupBy { it.strip } // keeps first-seen order
            .map { (strip, nodes) -> strip to nodes.map { it.id } }

    val inputGroups = groups(ServiceRole.INPUT)
    val alcoveGroups = groups(ServiceRole.ALCOVE)
    val tileBand = FieldDp.StripHeader + FieldDp.TileSize + FieldDp.LabelRoom

    // Every input strip is as wide as the widest, so their lines all leave from one
    // right edge and the curves to the join start level with each other.
    val maxTiles = inputGroups.maxOfOrNull { it.second.size } ?: 0
    val inputW = 2 * FieldDp.StripPad + maxTiles * FieldDp.ColumnWidth
    val rightEdge = FieldDp.Margin + inputW

    val strips = mutableListOf<FieldStrip>()
    var y = FieldDp.Margin
    inputGroups.forEach { (strip, ids) ->
        val h = tileBand + ids.size * FieldDp.LaneGap + FieldDp.LaneBottomPad
        strips += FieldStrip(strip, ServiceRole.INPUT, FieldRect(FieldDp.Margin, y, rightEdge, y + h), ids)
        y += h + FieldDp.StripGap
    }
    val inputBlock = if (strips.isEmpty()) {
        FieldRect(FieldDp.Margin, FieldDp.Margin, rightEdge, FieldDp.Margin)
    } else {
        FieldRect(FieldDp.Margin, strips.first().rect.top, rightEdge, strips.last().rect.bottom)
    }
    val hubY = inputBlock.centerY
    val join = FieldPoint(rightEdge + FieldDp.JoinRun, hubY)
    val timeline = FieldRect(
        join.x + FieldDp.HubGap, hubY - FieldDp.HubH / 2,
        join.x + FieldDp.HubGap + FieldDp.HubW, hubY + FieldDp.HubH / 2,
    )
    val split = FieldPoint(timeline.right + FieldDp.HubGap, hubY)

    // The alcoves stand as one block centred on the trunk.
    val alcoveLeft = split.x + FieldDp.AlcoveRun
    val alcoveH = tileBand + FieldDp.LaneBottomPad
    val alcoveBlockH = alcoveGroups.size * alcoveH + max(0, alcoveGroups.size - 1) * FieldDp.StripGap
    y = hubY - alcoveBlockH / 2
    alcoveGroups.forEach { (strip, ids) ->
        val w = 2 * FieldDp.StripPad + ids.size * FieldDp.ColumnWidth
        strips += FieldStrip(strip, ServiceRole.ALCOVE, FieldRect(alcoveLeft, y, alcoveLeft + w, y + alcoveH), ids)
        y += alcoveH + FieldDp.StripGap
    }

    val tiles = mutableListOf<FieldTile>()
    val lanes = mutableListOf<FieldLane>()
    val alcoveLines = mutableListOf<FieldAlcoveLine>()
    strips.forEach { s ->
        s.ids.forEachIndexed { i, id ->
            val c = FieldPoint(
                s.rect.left + FieldDp.StripPad + i * FieldDp.ColumnWidth + FieldDp.ColumnWidth / 2,
                s.rect.top + FieldDp.StripHeader + FieldDp.TileSize / 2,
            )
            val half = FieldDp.TileSize / 2
            val rect = FieldRect(c.x - half, c.y - half, c.x + half, c.y + half)
            tiles += FieldTile(id, c, rect)
            if (s.role == ServiceRole.INPUT) {
                val dropX = rect.right + FieldDp.DropOffset
                val laneY = s.rect.bottom - FieldDp.LaneBottomPad - i * FieldDp.LaneGap
                lanes += FieldLane(
                    id,
                    listOf(
                        FieldPoint(rect.right, c.y),
                        FieldPoint(dropX, c.y),
                        FieldPoint(dropX, laneY),
                        FieldPoint(s.rect.right, laneY),
                    ),
                    join,
                )
            } else {
                alcoveLines += FieldAlcoveLine(id, split, FieldPoint(rect.left, c.y))
            }
        }
    }

    val width = (strips.maxOfOrNull { it.rect.right } ?: split.x) + FieldDp.Margin
    val height = max(strips.maxOfOrNull { it.rect.bottom } ?: 0f, timeline.bottom) + FieldDp.Margin
    return FieldLayout(
        strips, tiles, lanes, alcoveLines, join, split, timeline, inputBlock,
        width, height, viewportW, viewportH,
    )
}

/**
 * The curve every line ends on, flattened to [samples] segments: a cubic whose two
 * control points both sit halfway across, so it leaves [from] and arrives at [to]
 * horizontally. Lines that start level and end together never cross on the way.
 */
fun curvePoints(from: FieldPoint, to: FieldPoint, samples: Int = 16): List<FieldPoint> {
    val mid = (from.x + to.x) / 2
    return (0..samples).map { k ->
        val t = k.toFloat() / samples
        val u = 1 - t
        val b0 = u * u * u
        val b1 = 3 * u * u * t
        val b2 = 3 * u * t * t
        val b3 = t * t * t
        FieldPoint(
            b0 * from.x + (b1 + b2) * mid + b3 * to.x,
            (b0 + b1) * from.y + (b2 + b3) * to.y,
        )
    }
}

/** A whole input line as points: its corners, then the curve to the join. */
fun lanePolyline(lane: FieldLane, samples: Int = 16): List<FieldPoint> =
    lane.corners + curvePoints(lane.corners.last(), lane.join, samples).drop(1)
