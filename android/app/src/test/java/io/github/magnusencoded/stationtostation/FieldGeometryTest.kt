package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.ui.FieldDp
import io.github.magnusencoded.stationtostation.ui.FieldLayout
import io.github.magnusencoded.stationtostation.ui.FieldPoint
import io.github.magnusencoded.stationtostation.ui.FieldRect
import io.github.magnusencoded.stationtostation.ui.FieldView
import io.github.magnusencoded.stationtostation.ui.ServiceRole
import io.github.magnusencoded.stationtostation.ui.ServicesAsKnown
import io.github.magnusencoded.stationtostation.ui.curvePoints
import io.github.magnusencoded.stationtostation.ui.fieldLayout
import io.github.magnusencoded.stationtostation.ui.lanePolyline
import io.github.magnusencoded.stationtostation.ui.serviceGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * The Settings **Field**'s geometry (#563), asserted the way an eye would check it:
 * nothing crosses what it should not, the lines all arrive, and no pan or pinch loses
 * the **Field** off the screen. Mirrored by iOS's `FieldGeometryTests`.
 */
class FieldGeometryTest {

    private val graph = serviceGraph(ServicesAsKnown())
    private val portrait = fieldLayout(graph, 411f, 760f)
    private val landscape = fieldLayout(graph, 891f, 330f)

    private val inputStrips = portrait.strips.filter { it.role == ServiceRole.INPUT }
    private val alcoveStrips = portrait.strips.filter { it.role == ServiceRole.ALCOVE }

    /** Strictly inside: a line may run along an edge it leaves from. */
    private fun FieldRect.holds(p: FieldPoint) = p.x > left && p.x < right && p.y > top && p.y < bottom

    /** Every point along a polyline, [step] apart, so a segment cannot jump a rect. */
    private fun dense(points: List<FieldPoint>, step: Float = 1f): List<FieldPoint> =
        points.zipWithNext().flatMap { (a, b) ->
            val n = max(1, (kotlin.math.hypot(b.x - a.x, b.y - a.y) / step).toInt())
            (0 until n).map { k -> FieldPoint(a.x + (b.x - a.x) * k / n, a.y + (b.y - a.y) * k / n) }
        } + points.last()

    private fun cross(o: FieldPoint, a: FieldPoint, b: FieldPoint) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    /** A proper crossing: each segment's ends strictly either side of the other. */
    private fun crosses(p1: FieldPoint, p2: FieldPoint, q1: FieldPoint, q2: FieldPoint): Boolean {
        val d1 = cross(q1, q2, p1)
        val d2 = cross(q1, q2, p2)
        val d3 = cross(p1, p2, q1)
        val d4 = cross(p1, p2, q2)
        return d1 * d2 < 0 && d3 * d4 < 0
    }

    private fun anyCrossing(a: List<FieldPoint>, b: List<FieldPoint>): Boolean =
        a.zipWithNext().any { (p1, p2) -> b.zipWithNext().any { (q1, q2) -> crosses(p1, p2, q1, q2) } }

    @Test
    fun `input strips share one right edge and the widest strip's width`() {
        assertEquals(3, inputStrips.size)
        assertEquals(1, inputStrips.map { it.rect.right }.toSet().size)
        assertEquals(1, inputStrips.map { it.rect.left }.toSet().size)
        assertEquals(2 * FieldDp.StripPad + 3 * FieldDp.ColumnWidth, inputStrips.first().rect.width, 0f)
        inputStrips.zipWithNext().forEach { (a, b) -> assertEquals(FieldDp.StripGap, b.rect.top - a.rect.bottom, 0.001f) }
    }

    @Test
    fun `every tile sits inside its strip, and no two overlap`() {
        portrait.strips.forEach { s ->
            s.ids.forEach { id ->
                val t = portrait.tile(id)!!.rect
                assertTrue(id, t.left >= s.rect.left && t.right <= s.rect.right && t.top >= s.rect.top && t.bottom <= s.rect.bottom)
            }
        }
        portrait.tiles.forEach { a ->
            portrait.tiles.filter { it.id != a.id }.forEach { b ->
                val apart = a.rect.right <= b.rect.left || b.rect.right <= a.rect.left ||
                    a.rect.bottom <= b.rect.top || b.rect.bottom <= a.rect.top
                assertTrue("${a.id} overlaps ${b.id}", apart)
            }
        }
    }

    @Test
    fun `every input has a lane, and every lane ends at the join`() {
        assertEquals(graph.nodes.filter { it.role == ServiceRole.INPUT }.map { it.id }, portrait.lanes.map { it.id })
        portrait.lanes.forEach { assertEquals(it.id, portrait.join, lanePolyline(it).last()) }
    }

    @Test
    fun `no lane runs through a strip other than its own, or through any tile`() {
        portrait.lanes.forEach { lane ->
            val own = inputStrips.first { lane.id in it.ids }
            val points = dense(lanePolyline(lane, samples = 64))
            (portrait.strips - own).forEach { s ->
                assertTrue("${lane.id} enters ${s.strip}", points.none { s.rect.holds(it) })
            }
            portrait.tiles.forEach { t ->
                assertTrue("${lane.id} crosses tile ${t.id}", points.none { t.rect.holds(it) })
            }
            assertFalse("${lane.id} crosses the timeline", points.any { portrait.timeline.holds(it) })
        }
    }

    @Test
    fun `no two lanes cross before they meet at the join`() {
        // All of them meet at the join, so the final approach is left out.
        val lines = portrait.lanes.associate { it.id to lanePolyline(it, samples = 64).dropLast(4) }
        lines.keys.forEach { a ->
            lines.keys.filter { it > a }.forEach { b ->
                assertFalse("$a crosses $b", anyCrossing(lines.getValue(a), lines.getValue(b)))
            }
        }
    }

    @Test
    fun `the leftmost tile takes the lowest lane`() {
        inputStrips.forEach { s ->
            val laneYs = s.ids.map { id -> portrait.lanes.first { it.id == id }.corners.last().y }
            assertEquals(laneYs.sortedDescending(), laneYs)
            assertTrue(laneYs.all { it < s.rect.bottom })
            // Below the names under the tiles.
            assertTrue(laneYs.all { it >= s.rect.top + FieldDp.StripHeader + FieldDp.TileSize + FieldDp.LabelRoom })
        }
    }

    @Test
    fun `the trunk is straight, through the timeline`() {
        assertEquals(portrait.join.y, portrait.split.y, 0f)
        assertEquals(portrait.join.y, portrait.timeline.centerY, 0.001f)
        assertEquals(portrait.inputBlock.centerY, portrait.join.y, 0.001f)
        assertTrue(portrait.join.x < portrait.timeline.left && portrait.split.x > portrait.timeline.right)
        assertTrue(alcoveStrips.all { it.rect.left > portrait.split.x })
    }

    @Test
    fun `alcove lines cross neither each other nor another strip`() {
        assertEquals(listOf("spotify", "calendar"), portrait.alcoveLines.map { it.id })
        val lines = portrait.alcoveLines.associate { it.id to curvePoints(it.from, it.to, 64) }
        portrait.alcoveLines.forEach { line ->
            assertEquals(portrait.tile(line.id)!!.rect.left, line.to.x, 0f)
            val own = alcoveStrips.first { line.id in it.ids }
            val points = dense(lines.getValue(line.id))
            (portrait.strips - own).forEach { s ->
                assertTrue("${line.id} enters ${s.strip}", points.none { s.rect.holds(it) })
            }
            assertFalse(points.any { portrait.timeline.holds(it) })
        }
        // They leave the split together, so the first stretch is left out.
        assertFalse(anyCrossing(lines.getValue("spotify").drop(4), lines.getValue("calendar").drop(4)))
    }

    @Test
    fun `the field holds everything with a margin`() {
        val right = portrait.strips.maxOf { it.rect.right }
        val bottom = max(portrait.strips.maxOf { it.rect.bottom }, portrait.timeline.bottom)
        assertEquals(right + FieldDp.Margin, portrait.width, 0.001f)
        assertEquals(bottom + FieldDp.Margin, portrait.height, 0.001f)
        assertTrue(portrait.timeline.top >= 0f)
    }

    private fun FieldLayout.onScreen(r: FieldRect, v: FieldView): FieldRect =
        FieldRect(v.offsetX + v.zoom * r.left, v.offsetY + v.zoom * r.top, v.offsetX + v.zoom * r.right, v.offsetY + v.zoom * r.bottom)

    @Test
    fun `the default view fits the inputs, in portrait and in landscape`() {
        listOf(portrait, landscape).forEach { layout ->
            val v = layout.defaultView
            val block = layout.onScreen(layout.inputBlock, v)
            val at = "${layout.viewportW}x${layout.viewportH}"
            assertTrue("$at: $block", block.left >= 0f && block.right <= layout.viewportW)
            assertTrue("$at: $block", block.top >= 0f && block.bottom <= layout.viewportH)
            assertEquals(at, layout.viewportH / 2, block.centerY, 0.01f)
            assertEquals(at, v, layout.clamp(v))
            assertTrue(at, layout.atLeftEdge(v))
        }
    }

    @Test
    fun `the default view starts at the left edge, or centred when the field is narrower`() {
        val p = portrait.defaultView
        assertTrue(portrait.width * p.zoom > portrait.viewportW)
        assertEquals(0f, p.offsetX, 0f)

        val l = landscape.defaultView
        assertTrue(landscape.width * l.zoom < landscape.viewportW)
        assertEquals((landscape.viewportW - landscape.width * l.zoom) / 2, l.offsetX, 0.001f)
    }

    @Test
    fun `the back-out edge is as far right as the field can be panned`() {
        listOf(portrait, landscape).forEach { layout ->
            listOf(FieldDp.MinZoom, 0.8f, 1.3f, FieldDp.MaxZoom).forEach { z ->
                val v = layout.clamp(FieldView(z, 1e6f, 0f))
                assertEquals(layout.leftEdgeOffsetX(z), v.offsetX, 0f)
                assertTrue(layout.atLeftEdge(v))
                assertFalse(layout.atLeftEdge(layout.pan(v, -40f, 0f)))
            }
        }
    }

    @Test
    fun `no pan or pinch leaves less than Keep of the field on screen`() {
        val extremes = listOf(-1e6f, -5000f, -300f, 0f, 300f, 5000f, 1e6f)
        listOf(portrait, landscape).forEach { layout ->
            listOf(0.01f, FieldDp.MinZoom, 1f, FieldDp.MaxZoom, 100f).forEach { z ->
                extremes.forEach { ox ->
                    extremes.forEach { oy ->
                        val v = layout.clamp(FieldView(z, ox, oy))
                        assertTrue(v.zoom in FieldDp.MinZoom..FieldDp.MaxZoom)
                        val f = layout.onScreen(FieldRect(0f, 0f, layout.width, layout.height), v)
                        val seenW = min(f.right, layout.viewportW) - max(f.left, 0f)
                        val seenH = min(f.bottom, layout.viewportH) - max(f.top, 0f)
                        assertTrue("$v: $seenW wide", seenW >= min(FieldDp.Keep, f.width) - 0.01f)
                        assertTrue("$v: $seenH high", seenH >= min(FieldDp.Keep, f.height) - 0.01f)
                    }
                }
            }
        }
    }

    @Test
    fun `a pinch keeps the point under the fingers where it is`() {
        val v = portrait.pan(portrait.defaultView, -100f, 0f)
        val cx = 200f
        val cy = 300f
        val fieldX = (cx - v.offsetX) / v.zoom
        val fieldY = (cy - v.offsetY) / v.zoom
        val z = portrait.zoomAbout(v, cx, cy, 1.4f)
        assertEquals(v.zoom * 1.4f, z.zoom, 0.0001f)
        assertEquals(cx, z.offsetX + z.zoom * fieldX, 0.01f)
        assertEquals(cy, z.offsetY + z.zoom * fieldY, 0.01f)
        // And never past the limits, however hard.
        assertEquals(FieldDp.MaxZoom, portrait.zoomAbout(v, cx, cy, 50f).zoom, 0f)
        assertEquals(FieldDp.MinZoom, portrait.zoomAbout(v, cx, cy, 0.001f).zoom, 0f)
    }

    @Test
    fun `the curve leaves and arrives level`() {
        val pts = curvePoints(FieldPoint(0f, 0f), FieldPoint(100f, 50f), 16)
        assertEquals(17, pts.size)
        assertEquals(FieldPoint(0f, 0f), pts.first())
        assertEquals(FieldPoint(100f, 50f), pts.last())
        assertTrue(pts.zipWithNext().all { (a, b) -> b.x >= a.x && b.y >= a.y })
    }
}
