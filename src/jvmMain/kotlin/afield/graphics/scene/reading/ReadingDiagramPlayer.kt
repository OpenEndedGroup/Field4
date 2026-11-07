package afield.graphics.scene.reading

import afield.geometry.path.FLine
import afield.graphics.text.*
import afield.graphics.text.slug.SlugPath
import afield.math.linalg.*
import afield.simulation.physics.SimdVerlet
import java.io.File
import kotlin.math.*

/**
 * One immutable-in-time evaluation of anchors. Paths are cached and cycles fail with a useful name.
 */
class DiagramFrame
internal constructor(private val player: ReadingDiagramPlayer, private val time: Double) {
    private val poses = HashMap<String, DiagramPose>()
    private val paths = HashMap<String, FLine>()
    private val visiting = HashSet<String>()

    fun pose(item: DiagramItem): DiagramPose {
        require(item.owner === player.diagram.owner) { "Item belongs to another diagram" }
        return poses.getOrPut(item.id) { player.playback.score.pose(item, time) }
    }

    fun position(anchor: DiagramAnchor): Vector3f {
        require(anchor.owner === player.diagram.owner) { "Anchor belongs to another diagram" }
        return anchor.resolve(this)
    }

    fun particle(p: DiagramParticle): Vector3f {
        val i = player.particleIndex.getValue(p)
        return Vector3f(player.state.x[i], player.state.y[i], player.state.z[i]).add(pose(p).offset)
    }

    internal fun textPosition(run: DiagramText) = position(run.at).add(pose(run).offset)

    internal fun textScale(run: DiagramText) = player.textScale * run.scale * pose(run).scale

    internal fun textAnchor(span: DiagramTextSpan, fraction: Float, height: Float): Vector3f {
        val run = span.run
        val shape = player.shapes.getValue(run.text)
        val layout = requireNotNull(shape.layout) { "Span anchors require the prepared run layout" }
        fun boundary(index: Int): Float {
            if (index == run.text.length) return shape.advance
            val glyph = layout.glyphs.firstOrNull { it.charIndex == index }
            requireNotNull(glyph) {
                "Span boundary $index splits a shaped glyph in '${run.text}'; select the complete word or glyph cluster"
            }
            return glyph.origin.x
        }
        val a = boundary(span.start)
        val b = boundary(span.endExclusive)
        val local =
            Vector3f(
                (a + (b - a) * fraction - shape.advance * .5f) * textScale(run),
                player.typeface.ascent.toFloat() * height * textScale(run),
                0f,
            )
        val c = cos(run.rotation)
        val s = sin(run.rotation)
        val x = local.x
        val y = local.y
        local.x = c * x - s * y
        local.y = s * x + c * y
        return textPosition(run).add(local)
    }

    fun path(path: DiagramPath): FLine {
        require(path.owner === player.diagram.owner)
        paths[path.id]?.let {
            return it
        }
        check(visiting.add(path.id)) { "Cyclic path anchor at '${path.id}'" }
        try {
            val result = path.make(this)
            val offset = pose(path).offset
            val transformed =
                if (offset.lengthSquared() == 0f) result
                else result.copy().transform { Vector3f(it).add(offset) }
            paths[path.id] = transformed
            return transformed
        } finally {
            visiting.remove(path.id)
        }
    }

    internal fun pathPoint(path: DiagramPath, fraction: Float): Vector3f {
        val cursor = path(path).cursor(.0002f)
        cursor.setD(cursor.lengthD() * fraction)
        return Vector3f(cursor.position())
    }
}

/**
 * The physics layer knows only particles, layout targets and reciprocal distance constraints. The
 * score knows only named scene items. A frame joins them when it resolves drawing anchors.
 */
class ReadingDiagramPlayer(
    val diagram: ReadingDiagram,
    val typeface: Typeface,
    override val shapes: Map<String, ReadingText>,
    score: DiagramScore,
) : ReadingScene {
    val playback = DiagramPlayback(score)
    val state = SimdVerlet.State3.empty(diagram.particles.size)
    private val constraints = SimdVerlet.DirectedConstraints4.ofCapacity(diagram.particles.size)
    private val solver =
        SimdVerlet(state).apply { constraints4 = this@ReadingDiagramPlayer.constraints }
    internal val particleIndex = diagram.particles.withIndex().associate { it.value to it.index }
    override val maximumLabels
        get() = diagram.texts.size

    override val geometry = DiagramGeometry()
    override val labels = ArrayList<ReadingLabel>()
    var textScale = .0009f // metres per font-space unit, shared by text and span anchors
    var spring = 28f
    var constraintStrength = 18f
    var gravity = -.025f
    var wind = .018f
    var dampingRate = 4.5f
    var strokeWidthScale = 1f
    var dotRadiusScale = 1f
    var showDots = true
    var physicsSteps = 0L
        private set

    private var accumulator = 0.0
    private var physicsTime = 0.0
    private val fixedStep = 1.0 / 120.0

    init {
        require(score.diagram === diagram)
        require(diagram.texts.all { shapes.containsKey(it.text) })
        for (p in diagram.particles) {
            state.addParticle()
            if (p.pinned) state.invMass!![particleIndex.getValue(p)] = 0f
        }
        resetPositions()
    }

    companion object {
        /**
         * Native Slug preparation happens once, outside the frame loop. Repeated strings share it.
         */
        fun prepare(
            diagram: ReadingDiagram,
            typeface: Typeface,
            helper: File,
            score: DiagramScore,
            progress: (Int, Int, String) -> Unit = { _, _, _ -> },
        ): ReadingDiagramPlayer {
            val strings = diagram.texts.map { it.text }.distinct()
            val shapes = LinkedHashMap<String, ReadingText>()
            for ((i, text) in strings.withIndex()) {
                require(typeface.canDisplay(text)) { "${typeface.fontName} cannot display '$text'" }
                val layout = layoutText(text, typeface)
                val outline =
                    layout.toFLine(filled = true, stroked = false).copy().transform { p ->
                        Vector3f(p.x - layout.advanceWidth.toFloat() * .5f, -p.y, 0f)
                    }
                shapes[text] =
                    ReadingText(
                        text,
                        layout.advanceWidth.toFloat(),
                        outline,
                        SlugPath.compile(outline, helper, .01f),
                        layout,
                    )
                progress(i + 1, strings.size, text)
            }
            return ReadingDiagramPlayer(diagram, typeface, shapes, score)
        }
    }

    fun resetPositions() {
        for ((i, p) in diagram.particles.withIndex()) {
            state.x[i] = p.rest.x
            state.y[i] = p.rest.y
            state.z[i] = p.rest.z
            state.px[i] = p.rest.x
            state.py[i] = p.rest.y
            state.pz[i] = p.rest.z
        }
        accumulator = 0.0
    }

    fun restart() {
        playback.restart()
        resetPositions()
    }

    private fun updateConstraints() {
        val slots = IntArray(state.size)
        for (link in diagram.links) {
            val a = particleIndex.getValue(link.a)
            val b = particleIndex.getValue(link.b)
            val length = link.a.rest.distance(link.b.rest)
            constraints.setSlot(a, slots[a]++, b, length, link.strength * constraintStrength)
            constraints.setSlot(b, slots[b]++, a, length, link.strength * constraintStrength)
        }
    }

    override fun update(elapsedSeconds: Double) {
        require(elapsedSeconds.isFinite() && elapsedSeconds >= 0)
        require(playback.score.diagram === diagram) {
            "A new topology needs a new player; replace just the score to rescore this graph"
        }
        playback.advance(elapsedSeconds)
        accumulator += min(elapsedSeconds, .1)
        updateConstraints()
        var steps = 0
        while (accumulator >= fixedStep && steps++ < 12) {
            for ((i, p) in diagram.particles.withIndex()) if (p.pinned) {
                state.x[i] = p.rest.x
                state.y[i] = p.rest.y
                state.z[i] = p.rest.z
                state.px[i] = p.rest.x
                state.py[i] = p.rest.y
                state.pz[i] = p.rest.z
            }
            solver.step(
                fixedStep.toFloat(),
                exp(-dampingRate * fixedStep).toFloat(),
                gravity,
                2,
                constraints,
            ) { s, _ ->
                for ((i, p) in diagram.particles.withIndex()) if (!p.pinned) {
                    s.ax[i] +=
                        (p.rest.x - s.x[i]) * spring +
                            wind * sin(physicsTime * .7 + i * .63).toFloat()
                    s.ay[i] += (p.rest.y - s.y[i]) * spring
                    s.az[i] +=
                        (p.rest.z - s.z[i]) * spring +
                            wind * .35f * cos(physicsTime * .43 + i * .51).toFloat()
                }
            }
            accumulator -= fixedStep
            physicsTime += fixedStep
            physicsSteps++
        }
        val frame = DiagramFrame(this, playback.sampleTime)
        geometry.clear()
        labels.clear()
        for (text in diagram.texts) {
            val p = frame.pose(text)
            if (p.opacity * text.color.w > 1e-5f)
                labels.add(
                    ReadingLabel(
                        shapes.getValue(text.text),
                        frame.textPosition(text),
                        p.opacity,
                        frame.textScale(text),
                        Vector4f(text.color),
                        text.rotation,
                    ),
                )
        }
        for (path in diagram.paths) {
            val p = frame.pose(path)
            if (p.opacity <= 1e-5f) continue
            val line = frame.path(path)
            // Per-node colours remain supported, including score alpha rather than bypassing it.
            val painted =
                if (p.opacity == 1f) line
                else
                    line.copy().also { copy ->
                        (copy.properties["color"] as? Vector4f)?.let {
                            copy.properties["color"] = Vector4f(it).apply { w *= p.opacity }
                        }
                        for (n in copy.nodes) (n.properties["color"] as? Vector4f)?.let {
                            n.properties["color"] = Vector4f(it).apply { w *= p.opacity }
                        }
                    }
            geometry.stroke(
                painted,
                path.ink.copy(
                    color = Vector4f(path.ink.color).apply { w *= p.opacity },
                    drawFrom = maxOf(path.ink.drawFrom, p.erase),
                    drawTo = minOf(path.ink.drawTo, p.reveal),
                ),
                widthScale = strokeWidthScale,
            )
        }
        if (showDots)
            for (dot in diagram.dots) {
                val p = frame.pose(dot)
                if (p.opacity * dot.color.w > 1e-5f)
                    geometry.points.add(
                        DiagramGeometry.Point(
                            frame.position(dot.at).add(p.offset),
                            dot.radius * p.scale * dotRadiusScale,
                            Vector4f(dot.color).apply { w *= p.opacity },
                            dot.shape,
                        ),
                    )
            }
    }

    /** Fresh anchor evaluation for inspectors and CPU tests; it does not advance either clock. */
    fun currentFrame() = DiagramFrame(this, playback.sampleTime)
}
