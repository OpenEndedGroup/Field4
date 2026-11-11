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
        val m = measure(span)
        return textPoint(
            run,
            m.advance.left + m.advance.width * fraction,
            m.baseline + m.ascent * height,
        )
    }

    /** Snapshot of prepared font-space metrics. Resolving anchors applies this frame's pose. */
    fun measure(span: DiagramTextSpan): DiagramTextMetrics {
        require(span.run.owner === player.diagram.owner)
        return player.shape(span.run).measure(span.start, span.endExclusive)
    }

    fun measure(run: DiagramText) = measure(run.all)

    internal fun textCaret(run: DiagramText, index: Int): Vector3f {
        val layout = requireNotNull(player.shape(run).layout)
        if (index == run.text.length || run.text[index] == '\n') {
            val line = layout.lines[run.text.take(index).count { it == '\n' }]
            val bodyY = line.baselineY.toFloat()
            val baselineY = if (line.glyphs.any { kotlin.math.abs(it.origin.y + it.offset.y - bodyY) < 1e-4f }) bodyY
                else line.glyphs.minOfOrNull { it.origin.y + it.offset.y } ?: bodyY
            return textPoint(run, line.advanceWidth.toFloat(), -baselineY)
        }
        val glyph =
            requireNotNull(layout.glyphs.firstOrNull { it.charIndex == index }) {
                "Caret $index splits a shaped glyph cluster in '${run.text}'"
            }
        return textPoint(run, glyph.origin.x, -glyph.origin.y)
    }

    /** Font-space baseline-left coordinates -> diagram space; y is up, z is toward the reader. */
    fun textPoint(run: DiagramText, x: Float, y: Float, z: Float = 0f): Vector3f {
        val shape = player.shape(run)
        val scale = textScale(run)
        return textPosition(run)
            .add(
                textDirection(
                    run,
                    (x - shape.advance * .5f) * scale,
                    y * scale,
                    z * scale,
                ),
            )
    }

    internal fun textOrientation(run: DiagramText) = player.textOrientation(run, this)

    /** Metres in the text's rotated axes. Use for padding that should not change with font size. */
    fun textDirection(run: DiagramText, x: Float, y: Float, z: Float): Vector3f {
        val c = cos(run.rotation)
        val s = sin(run.rotation)
        return textOrientation(run).transform(Vector3f(c * x - s * y, s * x + c * y, z))
    }

    /** Transform any locally-authored FLine in font units, including cubic controls. */
    fun textLine(run: DiagramText, line: FLine): FLine =
        line.copy().transform {
            textPoint(run, it.x, it.y, it.z)
        }

    internal fun connectionNodes(connection: DiagramConnection): List<Vector3f> =
        player.connectionNodes(connection, this)

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
                if (path.connection != null || offset.lengthSquared() == 0f) result
                else result.copy().transform { Vector3f(it).add(offset) }
            paths[path.id] = transformed
            return transformed
        } finally {
            visiting.remove(path.id)
        }
    }

    internal fun pathPoint(path: DiagramPath, fraction: Float): Vector3f {
        val line = path(path)
        // Endpoint markers need the exact endpoint, without flattening a whole curve each frame.
        if (fraction == 0f) line.nodes.firstOrNull()?.toOrNull?.let { return Vector3f(it) }
        if (fraction == 1f) line.currentPoint?.let { return Vector3f(it) }
        val cursor = line.cursor(.0002f)
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
    private val preparedRuns: Map<DiagramText, ReadingText> =
        diagram.texts.associateWith { shapes.getValue(it.text) },
) : ReadingScene {
    val playback = DiagramPlayback(score)
    val state = SimdVerlet.State3.empty(diagram.particles.size)
    private val constraints = SimdVerlet.DirectedConstraints4.ofCapacity(diagram.particles.size)
    private val solver =
        SimdVerlet(state).apply { constraints4 = this@ReadingDiagramPlayer.constraints }
    internal val particleIndex = diagram.particles.withIndex().associate { it.value to it.index }
    override val blurRequests: Map<ReadingText, Float>
        get() {
            val requests = LinkedHashMap<ReadingText, Float>()
            for (run in diagram.texts) {
                val amount = maxOf(run.blurCapacity, run.blur + (playback.score.blurCapacities[run] ?: 0f))
                if (amount > 0f) for (part in shape(run).renderShapes) requests.merge(part, amount, ::maxOf)
            }
            return requests
        }

    override val maximumLabels
        get() = diagram.texts.sumOf { shape(it).renderShapes.size }

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
    /**
     * Named CPU acceleration contributors, evaluated once per unpinned particle per fixed step.
     * The callback receives its index, current diagram-local position and a zeroed output vector.
     * Write acceleration in local units/second squared; it is added to layout, wind and gravity.
     * Arguments are borrowed scratch vectors: do not retain them. Nonfinite outputs are ignored.
     * Named entries allow an interaction box to remove only its own contribution on reload.
     */
    val externalAccelerations = linkedMapOf<String, (Int, Vector3f, Vector3f) -> Unit>()
    private val accelerationPosition = Vector3f()
    private val accelerationOutput = Vector3f()
    var physicsSteps = 0L
        private set

    private val readerPosition = Vector3f(0f, 0f, 2.35f)
    private val textFacing = HashMap<DiagramText, Quaternionf>()
    override val frostSurfaces: List<ReadingFrostSurface>
        get() {
            val f = frame()
            return diagram.textFrames.mapNotNull { border ->
                val opacity = f.pose(border.path).opacity * f.pose(border.span.run).opacity
                if (!border.frost.enabled || opacity <= 0f || border.frost.opacity <= 0f) null
                else ReadingFrostSurface(border, f, opacity)
            }
        }

    override val individualTextFacing get() = diagram.texts.any { it.faceReader }
    override fun setReaderPosition(position: Vector3f) {
        require(position.isFinite())
        readerPosition.set(position)
    }

    internal fun textOrientation(run: DiagramText, frame: DiagramFrame): Quaternionf {
        val facing = if (run.faceReader) textFacing[run] ?: readingFacingTarget(frame.textPosition(run), readerPosition, null)
            else Quaternionf()
        return Quaternionf(facing).mul(run.orientation)
    }

    private fun updateTextFacing(frame: DiagramFrame, dt: Float) {
        for (run in diagram.texts) if (run.faceReader) {
            val current = textFacing[run]
            val target = readingFacingTarget(frame.textPosition(run), readerPosition, current)
            if (current == null) textFacing[run] = target
            else readingTurn(current, target, dt, run.facingHalfLife, run.facingMaximumTurnRate)
        }
    }

    private val connectionPhysics by lazy { DiagramConnectionPhysics(this) }

    internal fun connectionNodes(connection: DiagramConnection, frame: DiagramFrame) =
        connectionPhysics.nodes(connection, frame)

    private var accumulator = 0.0
    private var physicsTime = 0.0
    private val fixedStep = 1.0 / 120.0

    init {
        require(score.diagram === diagram)
        require(diagram.texts.all { preparedRuns.containsKey(it) })
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
            val cache = LinkedHashMap<ReadingPreparationKey, ReadingText>()
            val runs = LinkedHashMap<DiagramText, ReadingText>()
            for ((i, run) in diagram.texts.withIndex()) {
                val key = readingPreparationKey(run, typeface)
                val shape =
                    cache.getOrPut(key) {
                        val layout = prepareReadingLayout(run, key)
                        val outline =
                            layout.toFLine(filled = true, stroked = false).copy().transform { p ->
                                Vector3f(p.x - layout.advanceWidth.toFloat() * .5f, -p.y, 0f)
                            }
                        val faces = (listOf(key.face) + key.ranges.map { it.face }).distinct()
                        val glyphFaces = layout.glyphs.associate { g ->
                            g.charIndex to (key.ranges.lastOrNull { g.charIndex in it.start until it.end }?.face ?: key.face)
                        }
                        val parts = if (key.dropCap == null && layout.lines.size <= 1) emptyList() else {
                            // Slug's per-path band offsets are 16-bit. Keep the large initial and
                            // each body line in separate atlases, in the SAME paragraph coordinates.
                            layout.glyphs.groupBy { if (key.dropCap != null && it.charIndex < key.dropCap.end) -1 else it.lineIndex }
                                .values.filter { glyphs -> glyphs.any { !it.glyph.isBlank } }.map { glyphs ->
                                    val partLayout = TextLayout(layout.text, layout.typeface, glyphs, layout.lines,
                                        layout.tracking, layout.lineHeight, layout.align)
                                    val partOutline = partLayout.toFLine(filled = true, stroked = false).copy().transform { p ->
                                        Vector3f(p.x - layout.advanceWidth.toFloat() * .5f, -p.y, 0f)
                                    }
                                    ReadingText(run.text, layout.advanceWidth.toFloat(), partOutline,
                                        SlugPath.compile(partOutline, helper, .01f), layout, faces, glyphFaces)
                                }
                        }
                        ReadingText(run.text, layout.advanceWidth.toFloat(), outline,
                            parts.firstOrNull()?.slug ?: SlugPath.compile(outline, helper, .01f),
                            layout, faces, glyphFaces, parts)
                    }
                runs[run] = shape
                progress(i + 1, diagram.texts.size, run.text)
            }
            return ReadingDiagramPlayer(
                diagram,
                typeface,
                runs.mapKeys { it.key.id },
                score,
                runs,
            )
        }
    }

    /** Prepared face and outline for this item; equal strings may have different typography. */
    fun shape(run: DiagramText): ReadingText = preparedRuns.getValue(run)

    /** Anchor/measurement access without advancing physics or the score. */
    fun frame(time: Double = playback.sampleTime): DiagramFrame {
        require(time.isFinite())
        return DiagramFrame(this, time)
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
        connectionPhysics.reset()
        textFacing.clear()
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
                    for (contribute in externalAccelerations.values) {
                        accelerationPosition.set(s.x[i], s.y[i], s.z[i])
                        accelerationOutput.set(0f, 0f, 0f)
                        contribute(i, accelerationPosition, accelerationOutput)
                        if (accelerationOutput.isFinite()) {
                            s.ax[i] += accelerationOutput.x
                            s.ay[i] += accelerationOutput.y
                            s.az[i] += accelerationOutput.z
                        }
                    }
                }
            }
            val physicsFrame = DiagramFrame(this, playback.sampleTime)
            updateTextFacing(physicsFrame, fixedStep.toFloat())
            connectionPhysics.step(physicsFrame, fixedStep.toFloat())
            accumulator -= fixedStep
            physicsTime += fixedStep
            physicsSteps++
        }
        val frame = DiagramFrame(this, playback.sampleTime)
        geometry.clear()
        labels.clear()
        for (text in diagram.texts) {
            val p = frame.pose(text)
            if (p.opacity * text.color.w > 1e-5f) for (part in shape(text).renderShapes)
                labels.add(
                    ReadingLabel(
                        part,
                        frame.textPosition(text),
                        p.opacity,
                        frame.textScale(text),
                        Vector4f(text.color),
                        text.rotation,
                        text.blur + p.blur,
                        frame.textOrientation(text),
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
