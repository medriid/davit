package dev.medriid.davit.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import dev.medriid.davit.design.Inks
import dev.medriid.davit.map.Passage
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Where the map looks from: centre in scene metres, pixels per metre, turn (radians), and how far it leans (0 flat, 1 full 2.5D). */
data class Camera(val x: Float, val y: Float, val scale: Float, val rot: Float, val tilt: Float)

/** What to draw on top of the campus this frame. */
class Overlay(
    val selectedBuilding: Int? = null,
    val selectedPrp: String? = null,
    val selectedPoi: String? = null,
    /** Route points in scene metres, x0 y0 x1 y1… */
    val route: FloatArray? = null,
    /** Moves the route's dashes along, 0..1. */
    val routePhase: Float = 0f,
    /** You: x, y and accuracy in metres. */
    val me: FloatArray? = null,
    val mePulse: Float = 0f,
    /** Categories whose pins show; null = the map's own "pin" categories. */
    val pinCats: Set<String>? = null,
)

/**
 * Draws the campus in two inks on paper, in a 2.5D oblique view: the ground plane tilted
 * back and squashed, buildings raised straight up the screen by their storeys, painted
 * from the back to the front so nearer ones cover farther ones.
 */
class MapPainter(context: Context, private val scene: Scene, private val inks: Inks, private val density: Float) {
    private fun dp(v: Float) = v * density
    private fun argb(c: Color, alpha: Float = 1f) = c.copy(alpha = c.alpha * alpha).toArgb()

    private val typeface: Typeface = runCatching {
        ResourcesCompat.getFont(context, dev.medriid.davit.design.R.font.plex_mono_semibold)
    }.getOrNull() ?: Typeface.MONOSPACE

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dots = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotMatrix = Matrix()
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = this@MapPainter.typeface
        textAlign = Paint.Align.CENTER
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = this@MapPainter.typeface
        textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val path2 = Path()
    private val archPath = Path()

    /** The route on screen at ground level, built once a frame and reused by the archways. */
    private val routePath = Path()
    private var routeOn = false

    // Jeman's signature: his campus map is what this one is drawn from.
    private val display: Typeface = runCatching {
        ResourcesCompat.getFont(context, dev.medriid.davit.design.R.font.bagel_fat_one)
    }.getOrNull() ?: Typeface.DEFAULT_BOLD
    private val sign = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val signMatrix = Matrix()

    /** South of the campus, under the teaching blocks: out of the way, there when you look around. */
    private val signAt: Pair<Float, Float> = run {
        val core = scene.pois.filter { it.poi.cat in setOf("academic", "lecture", "admin") }
        (if (core.isEmpty()) scene.midX else core.map { it.x }.average().toFloat()) to scene.extent[1] - 150f
    }
    private val dashPath = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
    private val dashSteps = DashPathEffect(floatArrayOf(dp(1.6f), dp(2.2f)), 0f)
    private val dashEdge = DashPathEffect(floatArrayOf(dp(7f), dp(5f)), 0f)

    init {
        // One dot per cell, repeated: the halftone screen, in the text ink.
        val cell = max(4, dp(5.5f).toInt())
        val tile = Bitmap.createBitmap(cell, cell, Bitmap.Config.ARGB_8888)
        Canvas(tile).drawCircle(cell / 2f, cell / 2f, cell * 0.3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb(inks.text) })
        dots.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    // ---- the view -------------------------------------------------------------------------

    private var w = 0f
    private var h = 0f
    private var cx = 0f
    private var cy = 0f
    private var s = 1f
    private var cosR = 1f
    private var sinR = 0f
    private var squash = 1f
    private var lift = 0f

    private fun setView(width: Float, height: Float, c: Camera) {
        w = width
        h = height
        cx = c.x
        cy = c.y
        s = c.scale
        cosR = cos(c.rot)
        sinR = sin(c.rot)
        squash = 1f - 0.4f * c.tilt
        lift = 0.85f * c.tilt
    }

    fun sx(x: Float, y: Float) = w / 2 + ((x - cx) * cosR - (y - cy) * sinR) * s
    fun sy(x: Float, y: Float, z: Float = 0f) = h / 2 - ((x - cx) * sinR + (y - cy) * cosR) * s * squash - z * s * lift

    /** How far back a ground point is: larger is farther. */
    private fun depth(x: Float, y: Float) = (x - cx) * sinR + (y - cy) * cosR

    /** Ground point under a screen point, ignoring heights. */
    fun ground(px: Float, py: Float, c: Camera, width: Float, height: Float): Pair<Float, Float> {
        setView(width, height, c)
        val rx = (px - w / 2) / s
        val ry = -(py - h / 2) / (s * squash)
        return (cx + cosR * rx + sinR * ry) to (cy - sinR * rx + cosR * ry)
    }

    private fun ring(p: Path, sh: Shape, z: Float = 0f, close: Boolean = true) {
        if (sh.size == 0) return
        p.moveTo(sx(sh.x[0], sh.y[0]), sy(sh.x[0], sh.y[0], z))
        for (i in 1 until sh.size) p.lineTo(sx(sh.x[i], sh.y[i]), sy(sh.x[i], sh.y[i], z))
        if (close) p.close()
    }

    private fun shadeDots(alpha: Float) {
        dotMatrix.setTranslate(sx(0f, 0f), sy(0f, 0f))
        dots.shader.setLocalMatrix(dotMatrix)
        dots.alpha = (alpha * 255).toInt().coerceIn(0, 255)
    }

    // ---- drawing ----------------------------------------------------------------------------

    fun draw(canvas: Canvas, width: Float, height: Float, cam: Camera, o: Overlay) {
        setView(width, height, cam)
        val night = inks.dark

        // Paper. Outside the campus a shade darker, so the campus reads as the sheet.
        canvas.drawColor(argb(if (night) inks.paper else inks.paper.copy(red = inks.paper.red * 0.95f, green = inks.paper.green * 0.95f, blue = inks.paper.blue * 0.94f)))
        path.reset()
        scene.boundary.forEach { ring(path, it) }
        fill.color = argb(inks.paper)
        canvas.drawPath(path, fill)

        // Grass and gardens: a light screen of dots. Water: a flat tint and dots.
        path.reset()
        scene.green.forEach { ring(path, it) }
        shadeDots(if (night) 0.35f else 0.28f)
        canvas.drawPath(path, dots)
        path.reset()
        scene.water.forEach { ring(path, it) }
        fill.color = argb(inks.b, 0.22f)
        canvas.drawPath(path, fill)
        shadeDots(0.35f)
        canvas.drawPath(path, dots)

        // Roads: paper channels with an ink edge, casings first so junctions merge.
        val roadW = { kind: String -> (when (kind) { "residential", "unclassified", "tertiary", "secondary" -> 7f; "service" -> 4.5f; else -> 5f } * s).coerceIn(dp(2f), dp(26f)) }
        stroke.pathEffect = null
        stroke.color = argb(inks.text, 0.55f)
        for (r in scene.roads) {
            path.reset()
            ring(path, r.shape, close = false)
            stroke.strokeWidth = roadW(r.kind) + dp(1.6f)
            canvas.drawPath(path, stroke)
        }
        stroke.color = argb(inks.paper)
        for (r in scene.roads) {
            path.reset()
            ring(path, r.shape, close = false)
            stroke.strokeWidth = roadW(r.kind)
            canvas.drawPath(path, stroke)
        }
        // Footpaths dashed; steps in short ticks.
        if (s / density > 0.13f) {
            stroke.color = argb(inks.text, 0.5f)
            stroke.strokeWidth = dp(1.3f)
            for (p in scene.paths) {
                path.reset()
                ring(path, p.shape, close = false)
                stroke.pathEffect = if (p.kind == "steps") dashSteps else dashPath
                if (p.kind == "steps") stroke.strokeWidth = dp(2.6f)
                canvas.drawPath(path, stroke)
                stroke.strokeWidth = dp(1.3f)
            }
        }
        stroke.pathEffect = null
        stroke.color = argb(inks.text, 0.35f)
        stroke.strokeWidth = dp(1f)
        for (wl in scene.walls) {
            path.reset()
            ring(path, wl.shape, close = false)
            stroke.pathEffect = if (wl.kind == "rail") dashPath else null
            canvas.drawPath(path, stroke)
        }
        // The campus edge, printed as a dashed rule.
        stroke.pathEffect = dashEdge
        stroke.color = argb(inks.text, 0.7f)
        stroke.strokeWidth = dp(1.5f)
        path.reset()
        scene.boundary.forEach { ring(path, it) }
        canvas.drawPath(path, stroke)
        stroke.pathEffect = null

        // Buildings and PRP blocks together, far to near.
        val prpOn = scene.prpBlocks.isNotEmpty()
        data class Solid(
            val shape: Shape,
            val holes: List<Shape>,
            val height: Float,
            val tone: Int,
            val selected: Boolean,
            val letter: String?,
            val arches: List<Passage> = emptyList(),
        )
        val solids = ArrayList<Solid>(scene.buildings.size + scene.prpBlocks.size)
        for (b in scene.buildings) {
            if (prpOn && b.cat == "prp") continue
            val tone = when (b.cat) { "academic", "lecture", "admin" -> 1; "hostel" -> 2; else -> 0 }
            solids += Solid(b.shape, b.holes, b.height, tone, b.index == o.selectedBuilding, null, b.arches)
        }
        for ((p, sh, height) in scene.prpBlocks) solids += Solid(sh, emptyList(), height, 3, p.id == o.selectedPrp, p.letter)
        solids.sortByDescending { depth(it.shape.cx, it.shape.cy) }

        // Shadows first, all of them, so no shadow falls across a nearer building's walls.
        if (lift > 0.01f) {
            path.reset()
            for (b in solids) {
                val off = b.height * 0.55f * (lift / 0.85f)
                // Thrown away from a light low in the south-west.
                val ox = off * 0.7f
                val oy = off * 0.7f
                path.moveTo(sx(b.shape.x[0] + ox, b.shape.y[0] + oy), sy(b.shape.x[0] + ox, b.shape.y[0] + oy))
                for (i in 1 until b.shape.size) path.lineTo(sx(b.shape.x[i] + ox, b.shape.y[i] + oy), sy(b.shape.x[i] + ox, b.shape.y[i] + oy))
                path.close()
            }
            shadeDots(if (night) 0.45f else 0.32f)
            canvas.drawPath(path, dots)
        }

        // Jeman's campus map, signed on the ground south of the campus.
        drawSignature(canvas)

        // The route lies on the ground: printed before the buildings, so they stand on it and
        // it shows through their archways, then ghosted over them so it's never lost behind one.
        val r = o.route?.takeIf { it.size >= 4 }
        routeOn = r != null
        if (r != null) {
            routePath.reset()
            routePath.moveTo(sx(r[0], r[1]), sy(r[0], r[1]))
            var i = 2
            while (i + 1 < r.size) {
                routePath.lineTo(sx(r[i], r[i + 1]), sy(r[i], r[i + 1]))
                i += 2
            }
            drawRoute(canvas, o.routePhase, ghost = false)
        }

        val edgeW = dp(1.1f)
        for (b in solids) drawSolid(canvas, b.shape, b.holes, b.height, b.tone, b.selected, b.letter, edgeW, b.arches)

        if (r != null) {
            drawRoute(canvas, o.routePhase, ghost = true)
            // Start: a ring.
            stroke.color = argb(inks.text)
            stroke.strokeWidth = dp(2.5f)
            fill.color = argb(inks.paper)
            canvas.drawCircle(sx(r[0], r[1]), sy(r[0], r[1]), dp(6f), fill)
            canvas.drawCircle(sx(r[0], r[1]), sy(r[0], r[1]), dp(6f), stroke)
        }

        drawPins(canvas, o)
        drawLabels(canvas, o)

        // You.
        o.me?.let { m ->
            val px = sx(m[0], m[1])
            val py = sy(m[0], m[1])
            val acc = (m[2] * s).coerceAtLeast(dp(10f))
            fill.color = argb(inks.a, 0.12f)
            canvas.drawOval(RectF(px - acc, py - acc * squash, px + acc, py + acc * squash), fill)
            if (o.mePulse > 0f) {
                stroke.color = argb(inks.a, (1f - o.mePulse) * 0.7f)
                stroke.strokeWidth = dp(2f)
                canvas.drawCircle(px, py, dp(8f) + dp(18f) * o.mePulse, stroke)
            }
            fill.color = argb(inks.paper)
            canvas.drawCircle(px, py, dp(9f), fill)
            fill.color = argb(inks.a)
            canvas.drawCircle(px, py, dp(6.5f), fill)
        }
    }

    /**
     * "campus map by jeman", lettered on the ground like a field marking: tens of metres
     * tall, lying flat, leaning with the view, in the two inks.
     */
    private fun drawSignature(canvas: Canvas) {
        val (ox, oy) = signAt
        val px = sx(ox, oy)
        val py = sy(ox, oy)
        val reach = 160f * s
        if (px < -reach || px > w + reach || py < -reach || py > h + reach) return
        // Text space (metres, x east, y down the page) onto the ground plane, then the screen.
        signMatrix.setValues(floatArrayOf(cosR * s, sinR * s, px, -sinR * s * squash, cosR * s * squash, py, 0f, 0f, 1f))
        canvas.save()
        canvas.concat(signMatrix)
        sign.typeface = display
        sign.textSize = 46f
        sign.style = Paint.Style.FILL
        // Ink B a little off register under ink A, like everything else on the sheet.
        sign.color = argb(inks.b, 0.22f)
        canvas.drawText("jeman", 1.3f, 1.3f, sign)
        sign.color = argb(inks.a, 0.36f)
        canvas.drawText("jeman", 0f, 0f, sign)
        sign.style = Paint.Style.STROKE
        sign.strokeWidth = max(0.5f, dp(1.1f) / s)
        sign.color = argb(inks.a, 0.75f)
        canvas.drawText("jeman", 0f, 0f, sign)
        sign.style = Paint.Style.FILL
        sign.typeface = typeface
        sign.textSize = 8.5f
        sign.color = argb(inks.text, 0.6f)
        canvas.drawText("campus map by", 0f, -39f, sign)
        canvas.restore()
    }

    /** The route in ink A over an ink edge with paper dashes running to the end; [ghost] is the faint copy over buildings. */
    private fun drawRoute(canvas: Canvas, phase: Float, ghost: Boolean) {
        if (!ghost) {
            stroke.color = argb(inks.text, 0.85f)
            stroke.strokeWidth = dp(8f)
            canvas.drawPath(routePath, stroke)
        }
        stroke.color = argb(inks.a, if (ghost) 0.38f else 1f)
        stroke.strokeWidth = dp(5.5f)
        canvas.drawPath(routePath, stroke)
        stroke.color = argb(inks.paper, if (ghost) 0.4f else 0.9f)
        stroke.strokeWidth = dp(1.8f)
        stroke.pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(9f)), -phase * dp(12f))
        canvas.drawPath(routePath, stroke)
        stroke.pathEffect = null
    }

    /**
     * An archway in wall [i]: a round-topped opening from the ground, a storey high (taller
     * for a road), in shadow, with the route showing through it when it runs that way.
     */
    private fun drawArch(canvas: Canvas, sh: Shape, i: Int, a: Passage, height: Float, edgeW: Float) {
        val j = (i + 1) % sh.size
        val ex = sh.x[j] - sh.x[i]
        val ey = sh.y[j] - sh.y[i]
        val len = sqrt(ex * ex + ey * ey)
        val hw = min(a.halfWidth.toFloat(), len / 2 - 0.3f)
        if (hw < 0.8f || hw * s < dp(1.2f)) return
        val top = min(if (a.halfWidth > 2.5) 5.2f else 3.8f, height * 0.75f)
        if (top < 1.8f) return
        val ux = ex / len
        val uy = ey / len
        val mx = sh.x[i] + ex * a.at.toFloat()
        val my = sh.y[i] + ey * a.at.toFloat()
        // A semicircle on two straight sides; flattened when the opening is wider than it is tall.
        val rise = min(hw, top * 0.45f)
        val spring = top - rise
        archPath.reset()
        archPath.moveTo(sx(mx - ux * hw, my - uy * hw), sy(mx - ux * hw, my - uy * hw))
        for (k in 0..14) {
            val ang = PI.toFloat() * (1f - k / 14f)
            val u = hw * cos(ang)
            val z = spring + rise * sin(ang)
            archPath.lineTo(sx(mx + ux * u, my + uy * u), sy(mx + ux * u, my + uy * u, z))
        }
        archPath.lineTo(sx(mx + ux * hw, my + uy * hw), sy(mx + ux * hw, my + uy * hw))
        archPath.close()
        fill.color = if (inks.dark) argb(Color.Black, 0.72f) else argb(inks.text, 0.74f)
        canvas.drawPath(archPath, fill)
        if (routeOn) {
            canvas.save()
            canvas.clipPath(archPath)
            stroke.color = argb(inks.a)
            stroke.strokeWidth = dp(5.5f)
            canvas.drawPath(routePath, stroke)
            canvas.restore()
        }
        stroke.color = argb(inks.text, 0.9f)
        stroke.strokeWidth = edgeW
        canvas.drawPath(archPath, stroke)
    }

    private fun drawSolid(canvas: Canvas, sh: Shape, holes: List<Shape>, height: Float, tone: Int, selected: Boolean, letter: String?, edgeW: Float, arches: List<Passage>) {
        val n = sh.size
        if (n < 3) return
        val raise = height * s * lift
        if (raise >= 1f) {
            // Walls that face you, back ones first. Outward normals depend on which way the ring turns.
            val ccw = sh.turn > 0
            val order = (0 until n).filter { i ->
                val j = (i + 1) % n
                val ex = sh.x[j] - sh.x[i]
                val ey = sh.y[j] - sh.y[i]
                val nx = if (ccw) ey else -ey
                val ny = if (ccw) -ex else ex
                // Its normal, turned into the view: facing you when it points down the screen.
                nx * sinR + ny * cosR < 0f
            }.sortedByDescending { i -> depth((sh.x[i] + sh.x[(i + 1) % n]) / 2, (sh.y[i] + sh.y[(i + 1) % n]) / 2) }
            for (i in order) {
                val j = (i + 1) % n
                val ex = sh.x[j] - sh.x[i]
                val ey = sh.y[j] - sh.y[i]
                val len = max(1e-3f, sqrt(ex * ex + ey * ey))
                val nx = (if (ccw) ey else -ey) / len
                val ny = (if (ccw) -ex else ex) / len
                // Light from the south-west of the view: walls turned to it print lighter.
                val vx = nx * cosR - ny * sinR
                val vy = nx * sinR + ny * cosR
                val lit = (-0.6f * vx - 0.8f * vy).coerceIn(0f, 1f)
                path.reset()
                path.moveTo(sx(sh.x[i], sh.y[i]), sy(sh.x[i], sh.y[i]))
                path.lineTo(sx(sh.x[j], sh.y[j]), sy(sh.x[j], sh.y[j]))
                path.lineTo(sx(sh.x[j], sh.y[j]), sy(sh.x[j], sh.y[j], height))
                path.lineTo(sx(sh.x[i], sh.y[i]), sy(sh.x[i], sh.y[i], height))
                path.close()
                // Paper under the ink, so walls hide what's behind them.
                fill.color = argb(inks.paper)
                canvas.drawPath(path, fill)
                fill.color = if (selected) argb(inks.a, 0.95f - 0.35f * lit) else argb(inks.b, (if (inks.dark) 0.62f else 0.55f) - 0.32f * lit)
                canvas.drawPath(path, fill)
                stroke.color = argb(inks.text, 0.4f)
                stroke.strokeWidth = edgeW * 0.8f
                canvas.drawPath(path, stroke)
                for (a in arches) if (a.edge == i) drawArch(canvas, sh, i, a, height, edgeW)
            }
        }
        // The roof.
        path2.reset()
        ring(path2, sh, height)
        holes.forEach { ring(path2, it, height) }
        path2.fillType = Path.FillType.EVEN_ODD
        fill.color = argb(inks.paper)
        canvas.drawPath(path2, fill)
        when {
            selected -> {
                fill.color = argb(inks.a)
                canvas.drawPath(path2, fill)
            }
            tone == 1 -> {
                fill.color = argb(inks.b, 0.12f)
                canvas.drawPath(path2, fill)
            }
            tone == 2 -> {
                fill.color = argb(inks.a, 0.12f)
                canvas.drawPath(path2, fill)
            }
            tone == 3 -> {
                fill.color = argb(inks.a, 0.28f)
                canvas.drawPath(path2, fill)
            }
        }
        stroke.color = argb(inks.text, 0.9f)
        stroke.strokeWidth = edgeW
        canvas.drawPath(path2, stroke)
        if (letter != null && s / density > 0.34f) {
            label.color = argb(if (selected) inks.paper else inks.text)
            label.textSize = dp(13f)
            canvas.drawText(letter, sx(sh.cx, sh.cy), sy(sh.cx, sh.cy, height) + dp(4.5f), label)
        }
    }

    /** Which places get a pin first when pins crowd: the ones people look for most, and a building before the offices inside it. */
    private val pinOrder: List<ScenePoi> = scene.pois.filter { !it.poi.unnamed }.sortedWith(compareBy<ScenePoi>({ p ->
        when (p.poi.cat) {
            "gate" -> 0
            "mess" -> 1
            "canteen" -> 2
            "atm", "bank" -> 3
            "health" -> 4
            "print" -> 5
            "academic", "lecture", "admin", "library" -> 6
            "laundry" -> 7
            "shop" -> 8
            else -> 9
        }
    }, { p -> if (p.poi.near.isBlank() && p.poi.anchor.isBlank()) 0 else 1 }))

    /** The pins drawn last time, so a tap only ever finds a pin you can see. */
    private val shown = ArrayList<ScenePoi>()

    private fun pinned(p: ScenePoi, cats: Set<String>?): Boolean {
        val c = p.poi.cat
        return if (cats != null) c in cats else scene.map.categories[c]?.pin == true && c != "hostel" && c != "prp"
    }

    /**
     * Pins for the places in view, the selected one big and in ink A. Where pins would crowd,
     * the more useful place keeps its pin; zooming in brings the rest back.
     */
    private fun drawPins(canvas: Canvas, o: Overlay) {
        val cats = o.pinCats
        shown.clear()
        val gap = dp(if (cats != null) 9f else 12f)
        val placed = ArrayList<Long>()
        for (p in pinOrder) {
            if (p.poi.id != o.selectedPoi && !pinned(p, cats)) continue
            val px = sx(p.x, p.y)
            val py = sy(p.x, p.y)
            if (px < -dp(20f) || px > w + dp(20f) || py < -dp(20f) || py > h + dp(20f)) continue
            val sel = p.poi.id == o.selectedPoi
            if (!sel && placed.any { v -> hypot(Float.fromBits((v shr 32).toInt()) - px, Float.fromBits(v.toInt()) - py) < gap }) continue
            placed += (px.toRawBits().toLong() shl 32) or (py.toRawBits().toLong() and 0xffffffffL)
            shown += p
        }
        // Small from afar, a little bigger close up, so they never swamp the buildings.
        val pinR = dp(3.4f + 1.6f * ((s / density - 0.3f) / 1.2f).coerceIn(0f, 1f))
        // Drawn back to front, so nearer pins sit over farther ones.
        for (p in shown.sortedByDescending { depth(it.x, it.y) }) {
            val c = p.poi.cat
            val px = sx(p.x, p.y)
            val py = sy(p.x, p.y)
            val sel = p.poi.id == o.selectedPoi
            val r = if (sel) dp(9f) else pinR
            if (sel) {
                // A stalk, so the pin stands up off the ground like the buildings.
                stroke.color = argb(inks.text)
                stroke.strokeWidth = dp(2f)
                canvas.drawLine(px, py, px, py - dp(16f), stroke)
                fill.color = argb(inks.text, 0.25f)
                canvas.drawOval(RectF(px - dp(5f), py - dp(2f), px + dp(5f), py + dp(2f)), fill)
            }
            val top = if (sel) py - dp(16f) else py
            fill.color = argb(inks.paper)
            canvas.drawCircle(px, top, r + if (sel) dp(2f) else dp(1.5f), fill)
            fill.color = if (sel) argb(inks.a) else argb(if (c == "gate" || c == "mess" || c == "canteen") inks.a else inks.b)
            canvas.drawCircle(px, top, r, fill)
        }
    }

    /** Building names on their roofs and place names by their pins, biggest first, never overlapping. */
    private fun drawLabels(canvas: Canvas, o: Overlay) {
        val taken = ArrayList<RectF>()
        fun place(text: String, x: Float, y: Float, size: Float, strong: Boolean): Boolean {
            label.textSize = size
            val tw = label.measureText(text)
            val box = RectF(x - tw / 2 - dp(2.5f), y - size, x + tw / 2 + dp(2.5f), y + dp(3f))
            if (box.right < 0 || box.left > w || box.bottom < 0 || box.top > h) return false
            // A little air between names, so two never read as one.
            val room = RectF(box.left - dp(5f), box.top - dp(2f), box.right + dp(5f), box.bottom + dp(2f))
            if (taken.any { RectF.intersects(it, room) }) return false
            taken += room
            // A paper tag under the name: lines behind never show through the gaps between words.
            fill.color = argb(inks.paper, 0.86f)
            canvas.drawRoundRect(box, dp(3f), dp(3f), fill)
            halo.textSize = size
            halo.strokeWidth = dp(2.6f)
            halo.color = argb(inks.paper, 0.92f)
            canvas.drawText(text, x, y, halo)
            label.color = argb(if (strong) inks.a else inks.text)
            canvas.drawText(text, x, y, label)
            return true
        }
        // The selection always gets its name, once: a place named like its building isn't labelled twice.
        val picked = scene.pois.firstOrNull { it.poi.id == o.selectedPoi }
        o.selectedBuilding?.let { i ->
            val b = scene.buildings.getOrNull(i) ?: return@let
            if (b.name.isNotBlank() && !b.name.equals(picked?.poi?.name, ignoreCase = true)) place(b.name, sx(b.shape.cx, b.shape.cy), sy(b.shape.cx, b.shape.cy, b.height) - dp(6f), dp(12f), true)
        }
        picked?.let { p ->
            place(p.poi.name, sx(p.x, p.y), sy(p.x, p.y) - dp(30f), dp(12f), true)
        }
        // Zoom in dp per metre, so the same view shows the same names on any screen.
        val zoom = s / density
        if (zoom < 0.18f) return
        val named = scene.buildings.filter { it.name.isNotBlank() && it.index != o.selectedBuilding }.sortedByDescending { it.footprint }
        for (b in named) {
            // Small footprints only when zoomed in enough to have room.
            if (b.footprint * s * s < dp(900f)) continue
            place(shortName(b.name), sx(b.shape.cx, b.shape.cy), sy(b.shape.cx, b.shape.cy, b.height) + dp(4f), dp(9.5f), false)
        }
        if (zoom < 0.6f) return
        for (p in shown) {
            if (p.poi.id == o.selectedPoi) continue
            place(shortName(p.poi.name), sx(p.x, p.y), sy(p.x, p.y) - dp(7f), dp(9f), false)
        }
    }

    /** Hit test: a pin within reach first, then the nearest building whose roof or walls are under the finger. */
    fun hit(px: Float, py: Float, cam: Camera, width: Float, height: Float): Any? {
        setView(width, height, cam)
        val reach = dp(22f)
        var best: ScenePoi? = null
        var bestD = reach
        for (p in shown) {
            val d = hypot(sx(p.x, p.y) - px, sy(p.x, p.y) - py)
            if (d < bestD) {
                bestD = d
                best = p
            }
        }
        if (best != null) return best
        // Nearest first: a tall building in front wins over the one behind it.
        val prpOn = scene.prpBlocks.isNotEmpty()
        val candidates = scene.buildings.filter { !(prpOn && it.cat == "prp") }.map { it as Any to it.shape } +
            scene.prpBlocks.map { it as Any to it.second }
        for ((item, sh) in candidates.sortedBy { depth(it.second.cx, it.second.cy) }) {
            val hgt = when (item) {
                is SceneBuilding -> item.height
                is Triple<*, *, *> -> item.third as Float
                else -> 0f
            }
            if (screenContains(sh, hgt, px, py) || screenContains(sh, 0f, px, py)) return item
        }
        return null
    }

    private fun screenContains(sh: Shape, z: Float, px: Float, py: Float): Boolean {
        var inside = false
        var j = sh.size - 1
        for (i in 0 until sh.size) {
            val xi = sx(sh.x[i], sh.y[i])
            val yi = sy(sh.x[i], sh.y[i], z)
            val xj = sx(sh.x[j], sh.y[j])
            val yj = sy(sh.x[j], sh.y[j], z)
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    companion object {
        /** Long names lose a trailing "Block" ("G.D Naidu Block" → "G.D Naidu"); "A Block" stays as it is. */
        fun shortName(n: String): String =
            n.replace("Mahatma Gandhi Block (MGB)", "MGB").let { if (it.length > 9) it.replace(" Block", "") else it }.trim()
    }
}
