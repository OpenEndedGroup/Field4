package afield.rectsl.study

import afield.KotlinInterface2
import afield.Runloop
import afield.ai.core.ToolImage
import afield.rectsl.AnalysisSettings
import afield.rectsl.AnimationWarning
import afield.rectsl.GroupNode
import afield.rectsl.Origin
import afield.rectsl.PaintSpec
import afield.rectsl.RectEscapeContext
import afield.rectsl.RectLangTokenizer
import afield.rectsl.RectNode
import afield.rectsl.RectSLCompiled
import afield.rectsl.RectSLKnob
import afield.rectsl.RectSLOrigin
import afield.rectsl.RectSLProblem
import afield.rectsl.SampledRect
import afield.rectsl.SceneProgram
import afield.rectsl.VisualEvent
import afield.rectsl.analyzeAnimation
import afield.rectsl.compileRectSLWithSource
import afield.rectsl.formatRectSLKnob
import afield.rectsl.prewarmRectSLEscapes
import afield.rectsl.rectSLKnobs
import afield.rectsl.rectSLLineAndColumn
import afield.rectsl.setRectSLKnobs
import afield.stacksl.cpu.CpuRaster
import afield.stacksl.study.StackSLDifference
import afield.stacksl.study.StackSLStudyEvidence
import afield.stacksl.study.StackSLStudyShader
import afield.stdlib.KnobNames
import afield.stdlib.SourceRevision
import afield.stdlib.SourceStudy
import afield.stdlib.StudyAdmission
import afield.stdlib.StudyRejection
import afield.stdlib.StudyReport
import afield.stdlib.StudySourceEdit
import afield.util.PngEncoder
import kotlinx.coroutines.runBlocking
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Talking a scene into existence: the RectSL study a box's AI4 chat, and the person beside it, work
 * on together.
 *
 * The source is a box's `rectSL` property — an ordinary RectSL program that a person can read and
 * edit, and that the window above the box draws as it is typed ([RectSLLive]). Every change — a
 * model's edit, a person's knob turn, a person's own typing when they check it in — becomes a checked
 * revision through [SourceStudy]: stale edits are refused, a program that does not compile never
 * reaches the box, and every accepted version stays on disk to compare and restore.
 *
 * What it can show, all drawn the way the window draws ([RectSLStudyShader]) — on its GPU when a
 * window is running, by the CPU reference when not:
 *
 *  - [observe] — frames at chosen times, with measurements, optionally against another revision;
 *  - [timeline] — the whole clip as a filmstrip and a chart of what is visible and moving, with the
 *    events a viewer would notice (entering, leaving, stopping, touching);
 *  - [trace] — which line made each rectangle, drawn over the frame, or the scene built up line by line;
 *  - [knobs] and [sweep] — the numbers and colours a person typed, which of them matter, and a range of
 *    values for one of them, without committing any;
 *  - [tryVariants] — candidate programs side by side, held unpublished until adopted;
 *  - [compare] — whether an edit changed only what it claimed to: in pixels, outside a region, and in
 *    which rectangles moved, appeared or went.
 */
class RectSLStudy(
    val directory: File,
    readSource: () -> String,
    replaceSource: (expected: String, replacement: String) -> Boolean
) {
    /** What draws the study's frames: the CPU reference until the runtime hands it a [RectSLGpuRasterizer]. */
    @Volatile
    var rasterizer: RectSLRasterizer = CpuRectSLRasterizer

    /** The revision history this study publishes through. */
    val revisions = SourceStudy(directory, "rectsl", readSource, replaceSource, { source, cancelled -> check(source, cancelled, rasterizer) }, maxSourceLength = 40000)

    /** Called after anything that changes what the card should show. */
    var onChange: (() -> Unit)? = null

    /** The line the person's editor cursor is on in this study's box, or null — see [afield.stdlib.BoxStudies.personAt]. */
    @Volatile
    var editorLine: () -> Int? = { null }

    private data class Speculation(val label: String, val note: String, val source: String)
    private val speculations = linkedMapOf<String, Speculation>()
    private val previews = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
    private var heldBatch: File? = null

    companion object {
        /** What RectSL is, for the fast model that names a scene's knobs ([knobNames]). */
        internal const val ABOUT_FOR_NAMES = "RectSL is a stack-based language for animated rectangles on a wall: each statement makes a named " +
            "rectangle, words starting with -> set its position, size, colour, fill and motion, and '( … ) quotes give values over time " +
            "(time=value keyframes)."

        /** How coordinates and time work, in the words every tool result repeats. */
        const val FRAME = "Each frame shows the whole wall (->wall width height units) and nothing outside it. The wall's centre is (0, 0), +x is right and +y is up, " +
            "so a wall 16 wide and 9 tall runs from x -8 to 8 and y -4.5 to 4.5. Time runs from 0 to the scene's ->duration in seconds, and the window loops it. " +
            "Colours are sRGB as typed: 0x808080 shows as mid grey."

        /**
         * Compile [source] with positions, compiling any `{{ … }}` Kotlin escapes on a dedicated engine
         * first (see [RectSLEscapes]). This is what the editor shows and the first half of [check].
         */
        fun compile(source: String): RectSLCompiled =
            compileRectSLWithSource(source, precompiledEscapes = if ("{{" in source) RectSLEscapes.precompiled(source) else emptyMap())

        /** [source]'s problems, errors first, each on its line. */
        fun diagnose(source: String): List<RectSLProblem> = try {
            compile(source).problems
        } catch (e: Exception) {
            listOf(RectSLProblem(e.message ?: "$e", null, null))
        }

        /** A compiled scene ready to draw, or a thrown [StudyRejection] saying why not. */
        fun drawable(source: String): RectSLDrawable {
            val compiled = compile(source)
            val program = compiled.program
            val errors = compiled.problems.filter { it.error }
            if (program == null || errors.isNotEmpty()) {
                throw StudyRejection("The scene does not compile:\n" + errors.joinToString("\n") { "  ${it.summary}" },
                    mapOf("problems" to errors.map { it.summary }))
            }
            return RectSLDrawable(source, program, compiled.materials, compiled.origins)
        }

        /**
         * [events] as sentences, one per happening rather than one per rectangle: an object — a top-level
         * group, or a rectangle in none — enters the wall with its first part and leaves it with its last,
         * the copies an `array` made that do something together are one line, and each line names the
         * lines of the program that made its rectangles —
         * `12.3 s: lantern ×2 (11 rectangles each, lines 23–35) enters the wall`. Touching is reported
         * between objects of comparable size, as the overlap warning counts it: not a part touching its
         * own object, a backdrop covering the wall, or a star a lantern passes.
         */
        fun describeEvents(events: List<VisualEvent>, scene: SceneProgram, origins: Map<String, afield.rectsl.RectSLOrigin>, fps: Double): List<String> {
            val parentOf = HashMap<String, String>()
            scene.nodes.filterIsInstance<afield.rectsl.GroupNode>().forEach { g -> g.children.forEach { parentOf[it] = g.id } }
            fun objectOf(id: String): String { var at = id; while (true) at = parentOf[at] ?: return at }
            fun family(id: String) = RectSLWindowLinks.baseId(objectOf(id))
            val start = scene.sample(0.0).rects
            val wallArea = scene.wall.width * scene.wall.height
            val areaOf = HashMap<String, Double>()
            start.forEach { r -> areaOf.merge(objectOf(r.id), r.size.x * abs(r.scale.x) * r.size.y * abs(r.scale.y), Double::plus) }
            fun comparable(a: String, b: String): Boolean {
                val x = areaOf[objectOf(a)] ?: return true; val y = areaOf[objectOf(b)] ?: return true
                return max(x, y) < 0.9 * wallArea && min(x, y) >= 0.1 * max(x, y)
            }
            // How many of each object's parts are on the wall, so it enters with the first and leaves with the last.
            val onWall = HashMap<String, Int>()
            start.filter { visibleOn(it, scene) }.forEach { onWall.merge(objectOf(it.id), 1, Int::plus) }
            data class Key(val what: String, val frame: Long, val family: String, val other: String?)
            fun key(e: VisualEvent): Key? {
                val frame = Math.round(e.time * fps)
                return when (e) {
                    is VisualEvent.EnterScreen -> {
                        val was = onWall.merge(objectOf(e.id), 1, Int::plus)!! - 1
                        if (was == 0) Key("enters the wall", frame, family(e.id), null) else null
                    }
                    is VisualEvent.LeaveScreen -> {
                        val left = onWall.merge(objectOf(e.id), -1, Int::plus)!!.coerceAtLeast(0).also { onWall[objectOf(e.id)] = it }
                        if (left == 0) Key("leaves the wall", frame, family(e.id), null) else null
                    }
                    is VisualEvent.Stop -> Key("stops", frame, family(e.id), null)
                    is VisualEvent.StartMoving -> Key("starts moving", frame, family(e.id), null)
                    is VisualEvent.DirectionChange -> Key("turns", frame, family(e.id), null)
                    is VisualEvent.RotationReversal -> Key("reverses its spin", frame, family(e.id), null)
                    is VisualEvent.OpacityCrossing -> Key("${if (e.rising) "fades in past" else "fades out below"} ${fmt(e.threshold)} opacity", frame, family(e.id), null)
                    is VisualEvent.TouchStart -> if (objectOf(e.a) == objectOf(e.b) || !comparable(e.a, e.b)) null else Key("touches", frame, family(e.a), family(e.b))
                    is VisualEvent.TouchEnd -> if (objectOf(e.a) == objectOf(e.b) || !comparable(e.a, e.b)) null else Key("parts from", frame, family(e.a), family(e.b))
                }
            }
            fun ids(e: VisualEvent) = when (e) {
                is VisualEvent.TouchStart -> listOf(e.a); is VisualEvent.TouchEnd -> listOf(e.a)
                is VisualEvent.EnterScreen -> listOf(e.id); is VisualEvent.LeaveScreen -> listOf(e.id); is VisualEvent.Stop -> listOf(e.id)
                is VisualEvent.StartMoving -> listOf(e.id); is VisualEvent.DirectionChange -> listOf(e.id)
                is VisualEvent.RotationReversal -> listOf(e.id); is VisualEvent.OpacityCrossing -> listOf(e.id)
            }
            // An object by what it is — how many rectangles, made on which lines — not by the parts that moved.
            val partsOf = HashMap<String, MutableList<String>>()
            scene.nodes.filterIsInstance<RectNode>().forEach { partsOf.getOrPut(objectOf(it.id)) { mutableListOf() } += it.id }
            fun name(family: String, rects: Collection<String>): String {
                val objects = rects.map(::objectOf).distinct()
                val parts = objects.flatMap { partsOf[it].orEmpty() }
                val perObject = parts.size / max(1, objects.size)
                val made = parts.mapNotNull { origins[it]?.line }.distinct().sorted()
                val detail = listOfNotNull(
                    (if (perObject > 1) "$perObject rectangles${if (objects.size > 1) " each" else ""}" else null),
                    when { made.isEmpty() -> null; made.size == 1 -> "line ${made[0]}"; else -> "lines ${made.first()}–${made.last()}" }
                ).joinToString(", ")
                return family + (if (objects.size > 1) " ×${objects.size}" else "") + (if (detail.isNotEmpty()) " ($detail)" else "")
            }
            // In time order, once each: [key] counts parts onto and off the wall as it goes.
            val happenings = events.sortedBy { it.time }.mapNotNull { e -> key(e)?.let { it to e } }
                .groupBy({ it.first }, { it.second }).entries
                .map { (k, happened) ->
                    val rects = happened.flatMap(::ids).distinct()
                    val with = k.other?.let { other -> other to happened.map { (it as? VisualEvent.TouchStart)?.b ?: (it as VisualEvent.TouchEnd).b }.distinct() }
                    Triple(k, happened.minOf { it.time }, "${name(k.family, rects)} ${k.what}" + (with?.let { (other, ids) -> " ${name(other, ids)}" } ?: ""))
                }
            // What recurs — a sway turning back every few seconds — is one line with its count and times.
            return happenings.groupBy { (k, _, _) -> Triple(k.family, k.what, k.other) }.values.flatMap { same ->
                if (same.size <= RECURRING) same.map { (_, time, text) -> time to "${secs(time)} s: $text" }
                else {
                    val times = same.map { it.second }
                    val text = same.first().third.replace(Regex(" ×\\d+ "), " ")
                    listOf(times.first() to "${secs(times.first())}–${secs(times.last())} s: $text, ${same.size} times " +
                        "(${times.take(8).joinToString(", ") { secs(it) }}${if (times.size > 8) ", …" else ""})")
                }
            }.sortedBy { it.first }.map { it.second }
        }

        /** More than this many of the same happening to the same kind of object is reported as recurring. */
        private const val RECURRING = 4

        /** A line note's picture, in pixels along the wall's longer side. */
        const val LINE_NOTE_LONG = 192

        /** The times a clip is shown at when nobody asked: evenly spaced, never the last instant (the loop's first). */
        fun defaultTimes(scene: SceneProgram, count: Int): List<Double> =
            if (count <= 1 || scene.duration <= 0) listOf(0.0) else List(count) { round4(scene.duration * it / count) }

        /**
         * Admit or refuse a candidate: it must compile, with its materials, and sample and draw at
         * probe times. The animation analysis's warnings are kept, not refused.
         */
        fun check(source: String, cancelled: () -> Boolean = { false }, rasterizer: RectSLRasterizer = CpuRectSLRasterizer): StudyAdmission<RectSLCheck> {
            val compiled = compile(source)
            val drawable = drawable(source)
            val scene = drawable.scene
            if (cancelled()) throw CancellationException("Study check cancelled")
            val probes = listOf(0.0, scene.duration / 3, 2 * scene.duration / 3, scene.duration * 0.999).map(::round4).distinct()
            val sampled = try {
                probes.map { scene.sample(it) }
            } catch (e: RuntimeException) {
                throw StudyRejection("The scene compiles but cannot be sampled: ${e.message}")
            }
            val (w, h) = RectSLStudyShader.frameSize(scene, 64)
            val frames = try {
                rasterizer.renderAll(drawable, w, h, probes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                throw StudyRejection("This scene cannot be drawn: ${e.message}")
            }
            val moved = sampled.zipWithNext().any { (a, b) -> a.rects.map(::state) != b.rects.map(::state) }
            val animated = moved || frames.zipWithNext().any { (a, b) -> a.rgba.indices.any { abs(a.rgba[it] - b.rgba[it]) > 1e-4 } }
            val rects = scene.nodes.filterIsInstance<RectNode>()
            val visible = sampled.map { s -> s.rects.count { visibleOn(it, scene) } }
            val warnings = mutableListOf<String>()
            compiled.problems.filter { !it.error }.forEach { warnings += it.summary }
            if (rects.isEmpty()) warnings += "The scene has no rectangles: it draws only its background."
            else if (visible.all { it == 0 }) warnings += "No rectangle is on the wall at any probe time: they are all outside it, or transparent."
            warnings += analysisWarnings(scene, compiled.origins)
            val knobs = rectSLKnobs(source)
            val result = RectSLCheck(
                wallWidth = scene.wall.width, wallHeight = scene.wall.height, unit = scene.wall.unit.name.lowercase(),
                origin = if (scene.wall.origin == Origin.TopLeft) "top-left, +y down" else "centre, +y up",
                duration = scene.duration, fps = scene.fps, background = hex(scene.background),
                rects = rects.size, groups = scene.nodes.count { it is GroupNode },
                visibleAtProbes = probes.zip(visible).map { (t, n) -> mapOf("seconds" to t, "visible" to n) },
                animated = animated,
                materials = drawable.materials.map { it.module.name },
                warnings = warnings,
                knobs = knobs,
                lines = lineReports(source, scene, compiled.origins)
            )
            return StudyAdmission(result, linkedMapOf(
                "warnings" to warnings, "animated" to animated, "rects" to result.rects, "groups" to result.groups,
                "duration" to scene.duration, "knobs" to knobs.size, "materials" to result.materials
            ))
        }

        private fun state(r: SampledRect) = listOf(r.id, r.center, r.size, r.rotation, r.scale, r.alpha, r.fill)

        /** Whether [r] shows on the wall: not transparent, and its bounds meet the wall's. */
        fun visibleOn(r: SampledRect, scene: SceneProgram): Boolean {
            if (r.alpha <= 0.01) return false
            val (x0, y0, x1, y1) = bounds(r)
            val wall = scene.wall
            val (wx0, wy0, wx1, wy1) = if (wall.origin == Origin.TopLeft) listOf(0.0, 0.0, wall.width, wall.height)
                else listOf(-wall.width / 2, -wall.height / 2, wall.width / 2, wall.height / 2)
            return x1 >= wx0 && x0 <= wx1 && y1 >= wy0 && y0 <= wy1
        }

        /** [r]'s axis-aligned bounds in wall units: x0, y0, x1, y1. */
        fun bounds(r: SampledRect): List<Double> {
            val hw = 0.5 * r.size.x * abs(r.scale.x); val hh = 0.5 * r.size.y * abs(r.scale.y)
            val c = abs(cos(r.rotation)); val s = abs(sin(r.rotation))
            val ex = c * hw + s * hh; val ey = s * hw + c * hh
            return listOf(r.center.x - ex, r.center.y - ey, r.center.x + ex, r.center.y + ey)
        }

        private fun analysisWarnings(scene: SceneProgram, origins: Map<String, RectSLOrigin>): List<String> {
            if (scene.duration <= 0) return emptyList()
            val fps = min(scene.fps, max(4.0, 90.0 / scene.duration))
            val report = runCatching { analyzeAnimation(scene, AnalysisSettings(fps = fps)) }.getOrNull() ?: return emptyList()
            fun where(id: String) = origins[id]?.let { "$id (line ${it.line})" } ?: id
            val out = mutableListOf<String>()
            val warnings = report.warnings
            warnings.filterIsInstance<AnimationWarning.MostlyBlank>().forEach { out += "Mostly blank from ${secs(it.start)} to ${secs(it.end)} s." }
            warnings.filterIsInstance<AnimationWarning.ObjectNeverVisible>().takeIf { it.isNotEmpty() }?.let { never ->
                out += "${never.size} rectangle${if (never.size == 1) " is" else "s are"} never visible: ${never.take(5).joinToString { where(it.id) }}${if (never.size > 5) ", …" else ""}."
            }
            warnings.filterIsInstance<AnimationWarning.ObjectLeavesTooEarly>().takeIf { it.isNotEmpty() }?.let { gone ->
                out += "${gone.size} rectangle${if (gone.size == 1) " leaves" else "s leave"} the wall early and do${if (gone.size == 1) "es" else ""} not return: ${gone.take(5).joinToString { "${where(it.id)} at ${secs(it.time)} s" }}${if (gone.size > 5) ", …" else ""}."
            }
            warnings.filterIsInstance<AnimationWarning.TooMuchChange>().takeIf { it.isNotEmpty() }?.let { jumps ->
                out += "Sudden change at ${jumps.take(4).joinToString { secs(it.time) + " s" }}${if (jumps.size > 4) " and ${jumps.size - 4} more times" else ""}."
            }
            // A backdrop covering the whole wall touches everything; overlap with it is not crowding.
            val backdrops = scene.sample(0.0).rects.filter { r ->
                val (x0, y0, x1, y1) = bounds(r)
                x0 <= -scene.wall.width / 2 && x1 >= scene.wall.width / 2 && y0 <= -scene.wall.height / 2 && y1 >= scene.wall.height / 2
            }.map { it.id }.toSet()
            // Parts of one object — the rectangles of one top-level group — overlap by design.
            val parentOf = scene.nodes.filterIsInstance<GroupNode>().flatMap { g -> g.children.map { it to g.id } }.toMap()
            fun objectOf(id: String): String = generateSequence(id) { parentOf[it] }.last()
            // The analysis reports overlap as a flat list of ids, so recount: distinct pairs of objects of
            // comparable size. A star behind a lantern is one thing in front of another, not a crowd.
            warnings.filterIsInstance<AnimationWarning.ExcessiveOverlap>().asSequence().mapNotNull { o ->
                val rects = scene.sample(o.time).rects.filter { it.id !in backdrops }
                val area = rects.associate { it.id to abs(it.size.x * it.scale.x * it.size.y * it.scale.y) }
                val crowded = afield.rectsl.detectContacts(rects).filter { c ->
                    val a = area[c.a] ?: 0.0; val b = area[c.b] ?: 0.0
                    objectOf(c.a) != objectOf(c.b) && min(a, b) >= 0.1 * max(a, b)
                }
                val pairs = crowded.map { setOf(objectOf(it.a), objectOf(it.b)) }.toSet()
                if (pairs.size >= 3) o to pairs.flatten().distinct() else null
            }.firstOrNull()?.let { (o, ids) ->
                out += "Heavy overlap (first at ${secs(o.time)} s): ${ids.take(4).joinToString(transform = ::where)}${if (ids.size > 4) ", …" else ""}."
            }
            return out
        }

        private fun lineReports(source: String, scene: SceneProgram, origins: Map<String, RectSLOrigin>): List<RectSLLineReport> {
            val lines = source.lines()
            val rectIds = scene.nodes.filterIsInstance<RectNode>().map { it.id }.toSet()
            return origins.entries.groupBy { it.value.line }.toSortedMap().map { (line, entries) ->
                val text = lines.getOrElse(line - 1) { "" }
                val ids = entries.map { it.key }
                RectSLLineReport(line, text.substringBefore('#').trim().take(120), comment(lines, line), ids.size, ids.count { it in rectIds }, ids.take(6))
            }
        }

        /** What a line says it does: its own comment, else a comment line directly above it. */
        fun comment(lines: List<String>, line: Int): String? {
            val own = lines.getOrNull(line - 1)?.let { l -> quoteFreeComment(l) }
            if (!own.isNullOrBlank()) return own
            return lines.getOrNull(line - 2)?.trim()?.takeIf { it.startsWith("#") && !it.startsWith("#rectsl") }?.removePrefix("#")?.trim()
        }

        private fun quoteFreeComment(line: String): String? {
            var inString = false
            line.forEachIndexed { i, c ->
                if (c == '"') inString = !inString
                if (c == '#' && !inString) return line.substring(i + 1).trim().takeIf { !line.trimStart().startsWith("#rectsl") }
            }
            return null
        }

        fun hex(c: afield.Color4): String = "#%02x%02x%02x".format(
            (c.r.coerceIn(0.0, 1.0) * 255 + 0.5).toInt(), (c.g.coerceIn(0.0, 1.0) * 255 + 0.5).toInt(), (c.b.coerceIn(0.0, 1.0) * 255 + 0.5).toInt())

        fun fmt(v: Double): String = StackSLStudyEvidence.format(v)

        /** A time in seconds to the millisecond, trailing zeros dropped: 17.967, 0.033, 4. Three significant figures would print 17.967 as 18.0. */
        fun secs(v: Double): String =
            if (!v.isFinite()) v.toString() else java.math.BigDecimal(v).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

        fun round4(v: Double) = kotlin.math.round(v * 10000) / 10000

        private fun png(bytes: ByteArray) = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
    }

    // ---- Drawing ------------------------------------------------------------------------------

    private fun drawableOf(r: SourceRevision<RectSLCheck>): RectSLDrawable = drawable(r.source)

    /** Frames of [source] at [longSide] along the wall's longer side. */
    internal fun frames(source: String, longSide: Int, seconds: List<Double>): List<CpuRaster> {
        val d = drawable(source)
        val (w, h) = RectSLStudyShader.frameSize(d.scene, longSide)
        return rasterizer.renderAll(d, w, h, seconds)
    }

    private fun framesOf(d: RectSLDrawable, longSide: Int, seconds: List<Double>): List<CpuRaster> {
        val (w, h) = RectSLStudyShader.frameSize(d.scene, longSide)
        return rasterizer.renderAll(d, w, h, seconds)
    }

    /** Tile width and height on a sheet for a scene, keeping its aspect, at most [long] on the longer side. */
    private fun tileSize(scene: SceneProgram, long: Int): Pair<Int, Int> {
        val aspect = scene.wall.width / scene.wall.height
        return if (aspect >= 1) long to max(24, (long / aspect).toInt()) else max(24, (long * aspect).toInt()) to long
    }

    private fun save(name: String, bytes: ByteArray): File {
        val folder = File(directory, "observations").also { it.mkdirs() }
        return File(folder, name.replace(Regex("[^A-Za-z0-9._-]"), "_")).also { it.writeBytes(bytes) }
    }

    private fun cost(clock: Long, frames: Int): Map<String, Any> {
        val seconds = (System.nanoTime() - clock) / 1e9
        return linkedMapOf("seconds" to (seconds * 1000).toInt() / 1000.0, "frames" to frames, "renderedBy" to rasterizer.description)
    }

    // ---- Reading ------------------------------------------------------------------------------

    /** Resume from `HEAD`, and the variants a previous session was holding against the revision still current. */
    fun resume(): String {
        val id = revisions.resume()
        val latest = File(directory, "speculations").listFiles().orEmpty()
            .filter { File(it, "variants.json").isFile }.maxByOrNull { it.name.toLongOrNull() ?: 0L }
        if (latest != null) runCatching {
            val batch = mapper.readTree(File(latest, "variants.json"))
            if (batch["against"]?.asText() == id && batch["adopted"]?.isNull != false) {
                synchronized(speculations) {
                    speculations.clear()
                    batch["variants"].forEach { v ->
                        val label = v["label"].asText()
                        speculations[label] = Speculation(label, v["note"].asText(), File(latest, v["file"].asText()).readText())
                    }
                }
            }
        }
        return id
    }

    /** The checked revision's id, or `empty`. */
    fun currentId(): String = revisions.currentId()

    /**
     * One knob as one short line — `k7 L9:32 0.22 keyframes of track four-beats: time` — index, where, as
     * typed, what reads it (and the colour an `rgb`'s number is part of), and the name the person's card
     * shows it by when it has one: a scene has dozens, and a list of objects was most of every Inspect.
     */
    private fun knobSummary(k: RectSLKnob, name: String? = null): String =
        "k${k.knob} L${k.line}:${k.column} ${k.text} ${k.role}" + (k.colour?.takeIf { !k.text.startsWith("0x") }?.let { " ($it)" } ?: "") +
            (name?.let { " [$it]" } ?: "")

    /**
     * The names the person's card shows [knobs] of [source] by ([KnobNames]), those that are known; [ask]
     * asks the fast model for the rest, calling [ready] once they are stored.
     */
    internal fun knobNames(source: String, knobs: List<RectSLKnob>, ask: Boolean = false, ready: () -> Unit = {}): Map<Int, String> =
        KnobNames.names("RectSL", ABOUT_FOR_NAMES, source, knobs.map { KnobNames.Knob(it.knob, it.line, it.text, it.role) }, directory, ask, ready).orEmpty()

    /** Everything a model needs before editing: source, live-edit state, history, the scene's shape, lines and knobs. */
    fun inspect(id: String = "current"): StudyReport {
        val live = revisions.liveState()
        if (revisions.currentId() == SourceStudy.EMPTY) {
            return StudyReport(SourceStudy.EMPTY, live + mapOf("message" to "No checked scene yet. Make the first edit with expectedRevision=empty and this liveSourceHash.", "frame" to FRAME))
        }
        val r = revisions.revision(id)
        val liveText = live["liveSource"] as String?
        val liveCheck = liveText?.let { text ->
            val problems = diagnose(text)
            if (problems.none { it.error }) mapOf("compiles" to true, "warnings" to problems.map { it.summary }.ifEmpty { null })
            else mapOf("compiles" to false, "problems" to problems.filter { it.error }.map { it.summary })
        }
        val history = revisions.history()
        return StudyReport(r.id, linkedMapOf(
            "revision" to r.id, "parent" to r.parent, "description" to r.description,
            "source" to r.source,
            "liveSourceHash" to live["liveSourceHash"], "dirty" to live["dirty"], "liveSource" to liveText,
            "liveCheck" to liveCheck,
            "personAt" to afield.stdlib.BoxStudies.personAt(editorLine(), liveText ?: r.source),
            "history" to history.takeLast(24).map { mapOf("id" to it.id, "description" to it.description) },
            "otherRevisions" to (revisions.allRevisions().size - history.size).takeIf { it > 0 },
            "scene" to sceneSummary(r.value), "warnings" to r.value.warnings.ifEmpty { null },
            "lines" to r.value.lines,
            "knobs" to knobNames(r.source, r.value.knobs).let { names -> r.value.knobs.take(80).map { knobSummary(it, names[it.knob]) } },
            "moreKnobs" to (r.value.knobs.size - 80).takeIf { it > 0 },
            "pendingVariants" to synchronized(speculations) { speculations.values.map { mapOf("label" to it.label, "note" to it.note) } }.ifEmpty { null },
            "exports" to exports().ifEmpty { null },
            "frame" to FRAME
        ))
    }

    private fun sceneSummary(c: RectSLCheck) = linkedMapOf(
        "wall" to "${fmt(c.wallWidth)} × ${fmt(c.wallHeight)} ${c.unit}, origin ${c.origin}", "duration" to c.duration, "fps" to c.fps,
        "background" to c.background, "rects" to c.rects, "groups" to c.groups, "animated" to c.animated,
        "materials" to c.materials.filter { it !in setOf("plain", "soft-edge", "diagnostic") }.ifEmpty { null },
        "visible" to c.visibleAtProbes)

    // ---- Publishing ---------------------------------------------------------------------------

    private fun published(r: SourceRevision<RectSLCheck>, previewSeconds: List<Double>, message: String): StudyReport {
        previews.keys.removeIf { it.startsWith(r.id) }
        val base = StudyReport(r.id, linkedMapOf(
            "message" to message, "revision" to r.id, "parent" to r.parent, "description" to r.description,
            "sourceHash" to SourceStudy.hash(r.source),
            "validation" to mapOf("compileSuccess" to true, "checksPassed" to true, "revision" to r.id,
                "sourceHash" to SourceStudy.hash(r.source), "scope" to "complete RectSL program and its materials, sampled and drawn at probe times"),
            "scene" to sceneSummary(r.value), "warnings" to r.value.warnings.ifEmpty { null },
            "knobs" to r.value.knobs.size
        ))
        val report = try {
            // Against the parent, unless the parent drew nothing: a first draft on a blank wall differs everywhere.
            val parent = r.parent?.takeIf { id -> runCatching { revisions.revision(id).value.rects > 0 }.getOrDefault(false) }
            val preview = observe(r.id, previewSeconds, 0, parent.orEmpty())
            base.copy(facts = base.facts + ("preview" to preview.facts), toolImages = preview.toolImages)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            base.copy(facts = base.facts + ("previewError" to "Revision ${r.id} is already active; request ObserveRectSLStudy instead of repeating the edit. ${e.message}"))
        }
        runCatching { onChange?.invoke() }
        return report
    }

    /** Publish a complete program. See [SourceStudy.edit] for the guards. */
    fun edit(expectedRevision: String, expectedSourceHash: String, source: String, description: String,
             previewSeconds: List<Double> = emptyList()): StudyReport =
        published(revisions.edit(expectedRevision, expectedSourceHash, source, description), previewSeconds, "Compiled, checked and activated")

    /** Publish exact replacements against the live source. */
    fun patch(expectedRevision: String, expectedSourceHash: String, edits: List<StudySourceEdit>, description: String,
              previewSeconds: List<Double> = emptyList()): StudyReport =
        published(revisions.patch(expectedRevision, expectedSourceHash, edits, description), previewSeconds, "Patched, checked and activated")

    /** Publish new values for knobs of the live source (knob index to value), rewritten in place. */
    fun setNumbers(expectedRevision: String, expectedSourceHash: String, numbers: Map<Int, Double>, description: String,
                   previewSeconds: List<Double> = emptyList()): StudyReport {
        if (numbers.isEmpty()) throw StudyRejection("Name at least one knob to set")
        val live = revisions.liveSource()
        if (expectedRevision != revisions.currentId() || expectedSourceHash != SourceStudy.hash(live)) {
            throw StudyRejection("Conflict: inspect again; knob numbers refer to the live source you read.")
        }
        val rewritten = try { setRectSLKnobs(live, numbers) } catch (e: IllegalArgumentException) { throw StudyRejection(e.message ?: "Could not set those numbers") }
        return published(revisions.edit(expectedRevision, expectedSourceHash, rewritten, description), previewSeconds, "Numbers set, checked and activated")
    }

    /** Check in whatever is in the editor now — a person's own edit — as a revision. */
    fun checkIn(description: String = "edited by hand"): StudyReport {
        val live = revisions.liveSource()
        val current = revisions.currentId()
        if (current != SourceStudy.EMPTY && revisions.revision().source == live) {
            return StudyReport(current, mapOf("message" to "Nothing to check in: the editor holds $current."))
        }
        return published(revisions.edit(current, SourceStudy.hash(live), live, description), emptyList(), "Checked in the editor's scene")
    }

    /** Make an earlier revision current again, as a new revision. */
    fun restore(expectedRevision: String, expectedSourceHash: String, id: String, description: String = ""): StudyReport =
        published(revisions.restore(expectedRevision, expectedSourceHash, id, description), emptyList(), "Restored as a new revision")

    // ---- Looking ------------------------------------------------------------------------------

    private fun timesFor(r: SourceRevision<RectSLCheck>, scene: SceneProgram, seconds: List<Double>, count: Int = 4): List<Double> {
        val requested = seconds.filter { it.isFinite() }.distinct().take(8)
        return when {
            !r.value.animated -> listOf(requested.firstOrNull() ?: 0.0)
            requested.isEmpty() -> defaultTimes(scene, count)
            else -> requested
        }
    }

    /**
     * Frames of a revision at [seconds] (a still scene is drawn once), with measurements, and
     * optionally the same frames of [compareTo] and a difference map beneath.
     */
    fun observe(id: String = "current", seconds: List<Double> = emptyList(), size: Int = 0, compareTo: String = ""): StudyReport {
        val r = revisions.revision(id)
        val d = drawableOf(r)
        val other = compareTo.takeIf { it.isNotBlank() }?.let { revisions.revision(it) }
        val times = timesFor(r, d.scene, seconds).let { t -> if (other != null && other.value.animated && !r.value.animated) timesFor(other, d.scene, seconds) else t }
        val long = (if (size > 0) size else if (times.size == 1) 512 else 320).coerceIn(48, 1024)
        val clock = System.nanoTime()
        val epoch = revisions.epoch()
        if (revisions.isCancelled(epoch)) throw CancellationException()
        val frames = framesOf(d, long, times)
        val before = other?.let { o -> framesOf(drawableOf(o), long, times) }
        val stats = frames.mapIndexed { i, f -> StackSLStudyEvidence.stats(f, times[i]) }
        val sampled = times.map { d.scene.sample(it) }
        val rows = mutableListOf(frames.mapIndexed { i, f -> StackSLStudyEvidence.tile(StackSLStudyShader.presented(f), "t = ${secs(times[i])} s",
            "${sampled[i].rects.count { visibleOn(it, d.scene) }} visible · luma ${fmt(stats[i].luma)}") })
        val labels = mutableListOf(r.id)
        val differences = before?.let { b ->
            val o = other
            rows += b.mapIndexed { i, f -> StackSLStudyEvidence.tile(StackSLStudyShader.presented(f), "t = ${secs(times[i])} s", o.id) }
            labels += o.id
            val diffs = b.indices.map { i -> StackSLStudyEvidence.difference(b[i], frames[i], null, false).first }
            if (diffs.any { it.mean <= 40.0 }) {
                rows += b.indices.map { i -> StackSLStudyEvidence.differenceTile(b[i], frames[i], "difference", "max ${diffs[i].max.toInt()} · mean ${fmt(diffs[i].mean)} levels") }
                labels += "difference\nwhite ≥ 32 levels"
            }
            diffs.mapIndexed { i, diff -> mapOf("seconds" to times[i], "difference" to diff) }
        }
        val motion = frames.zipWithNext().mapIndexed { i, (a, b) -> StackSLStudyEvidence.motion(a, b, times[i], times[i + 1]) }
        val (tw, th) = tileSize(d.scene, if (times.size == 1) 640 else 320)
        val bytes = StackSLStudyEvidence.sheet("RectSL study · ${r.id}", r.description.take(140), rows, tw, labels,
            if (!r.value.animated) "Nothing in this scene changes over time: one frame is all there is." else "", th)
        val file = save("${r.id}-${times.joinToString("_")}-$long${other?.let { "-vs-${it.id}" }.orEmpty()}.png", bytes)
        return StudyReport(r.id, linkedMapOf(
            "revision" to r.id, "comparedTo" to other?.id, "seconds" to times, "longSide" to long, "animated" to r.value.animated,
            "frames" to stats.mapIndexed { i, s -> linkedMapOf("seconds" to s.seconds, "visibleRects" to sampled[i].rects.count { visibleOn(it, d.scene) },
                "luma" to s.luma, "detail" to s.detail, "colourfulness" to s.colourfulness, "palette" to s.palette.take(4), "meanRgb" to s.meanRgb) },
            "motion" to motion.ifEmpty { null }, "differences" to differences,
            "renderSeconds" to cost(clock, frames.size + (before?.size ?: 0)),
            "warnings" to r.value.warnings.ifEmpty { null }, "imageFile" to file.absolutePath
        ), listOf(ToolImage(png(bytes), "${r.id}: frames at ${times.joinToString { secs(it) }} s" +
            (other?.let { "; second row ${it.id}, third row the difference" } ?: ""))))
    }

    /**
     * The whole clip: [frames] frames evenly across the duration, then a chart of how many rectangles
     * are visible, how much of the wall they cover and how fast they move, over time — with the
     * events the scene's analysis finds (entering, leaving, stopping, starting, touching), each named
     * with the line that made the rectangle.
     */
    fun timeline(id: String = "current", frames: Int = 8, size: Int = 0): StudyReport {
        val r = revisions.revision(id)
        val d = drawableOf(r)
        val scene = d.scene
        val compiled = compile(r.source)
        val count = frames.coerceIn(2, 16)
        val times = defaultTimes(scene, count)
        val clock = System.nanoTime()
        val long = (if (size > 0) size else 240).coerceIn(48, 512)
        val rasters = framesOf(d, long, times)
        val fps = min(scene.fps, max(4.0, 240.0 / max(scene.duration, 1e-6)))
        val report = analyzeAnimation(scene, AnalysisSettings(fps = fps))
        val events = describeEvents(report.events, scene, compiled.origins, fps)
        val metrics = report.frameMetrics
        val wallArea = scene.wall.width * scene.wall.height
        val (tw, th) = tileSize(scene, 240)
        val tiles = rasters.mapIndexed { i, f -> StackSLStudyEvidence.tile(StackSLStudyShader.presented(f), "t = ${secs(times[i])} s",
            "${scene.sample(times[i]).rects.count { visibleOn(it, scene) }} visible") }
        val frames = StackSLStudyEvidence.sheet("RectSL timeline · ${r.id} · ${secs(scene.duration)} s",
            "Frames evenly across the clip; below, what is visible and moving over time, events as ticks.", tiles.chunked(4), tw,
            footer = "Coverage counts overlapping rectangles twice; speed is in wall units per second.", tileHeight = th)
        val bytes = belowSheet(frames) { width ->
            activityChart(metrics.map { it.time }, listOf(
                "visible rectangles" to metrics.map { it.visibleObjectCount.toDouble() },
                "share of the wall covered" to metrics.map { it.totalVisibleAreaSquareMeters / wallArea },
                "mean speed" to metrics.map { it.averageSpeedMetersPerSecond }),
                report.events.map { it.time }, scene.duration, width)
        }
        val file = save("${r.id}-timeline-$count.png", bytes)
        val step = max(1, metrics.size / 24)
        return StudyReport(r.id, linkedMapOf(
            "revision" to r.id, "duration" to scene.duration, "seconds" to times,
            "activity" to metrics.filterIndexed { i, _ -> i % step == 0 }.map {
                linkedMapOf("seconds" to round4(it.time), "visible" to it.visibleObjectCount, "wallCovered" to round4(it.totalVisibleAreaSquareMeters / wallArea),
                    "meanSpeed" to round4(it.averageSpeedMetersPerSecond))
            },
            "events" to events.take(60), "moreEvents" to (events.size - 60).takeIf { it > 0 },
            "warnings" to r.value.warnings.ifEmpty { null },
            "imageFile" to file.absolutePath, "renderSeconds" to cost(clock, rasters.size),
            "method" to "Frames drawn by ${rasterizer.description}; activity and events from the scene's own analysis (afield.rectsl.analyzeAnimation) at ${fmt(fps)} samples a second."
        ), listOf(ToolImage(png(bytes), "${r.id}: $count frames across ${secs(scene.duration)} s, and activity over time")))
    }

    /** A chart of [series] over time, one strip each with its own range, events as ticks along the bottom. */
    private fun activityChart(times: List<Double>, series: List<Pair<String, List<Double>>>, events: List<Double>, duration: Double, width: Int): BufferedImage {
        val strip = 46
        val h = 12 + series.size * (strip + 10) + 26
        val image = BufferedImage(width, h, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(10, 10, 12); g.fillRect(0, 0, width, h)
        val left = 250; val right = width - 28
        fun x(t: Double) = left + ((t / max(duration, 1e-9)) * (right - left)).toInt()
        val colours = listOf(Color(120, 200, 255), Color(255, 190, 90), Color(170, 240, 150))
        g.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        series.forEachIndexed { s, (name, values) ->
            val top = 12 + s * (strip + 10); val bottom = top + strip
            val lo = values.minOrNull() ?: 0.0; val hi = values.maxOrNull() ?: 0.0
            val span = (hi - lo).takeIf { it > 1e-12 }
            g.color = Color(22, 22, 26); g.fillRect(left, top, right - left, strip)
            g.color = colours[s % colours.size]; g.stroke = BasicStroke(1.6f)
            fun y(v: Double) = if (span == null) (top + bottom) / 2 else bottom - (((v - lo) / span) * (strip - 4)).toInt() - 2
            for (i in 1 until values.size) g.drawLine(x(times[i - 1]), y(values[i - 1]), x(times[i]), y(values[i]))
            g.drawString(name, 28, top + 18)
            g.color = Color(150, 150, 155)
            g.drawString(if (span == null) "constant ${fmt(lo)}" else "${fmt(lo)} … ${fmt(hi)}", 28, top + 36)
        }
        val base = 12 + series.size * (strip + 10)
        g.color = Color(200, 120, 90)
        events.forEach { t -> g.drawLine(x(t), base - 4, x(t), base + 4) }
        g.color = Color(150, 150, 155)
        g.drawString("0 s", left, base + 20); g.drawString("${secs(duration)} s", right - 60, base + 20)
        if (events.isNotEmpty()) g.drawString("${events.size} events", 28, base + 20)
        g.dispose()
        return image
    }

    /** [sheet] with a picture [below] it, as one PNG; [below] is drawn at the sheet's width, less its margins. */
    private fun belowSheet(sheet: ByteArray, below: (width: Int) -> BufferedImage): ByteArray {
        val top = javax.imageio.ImageIO.read(sheet.inputStream())
        val bottom = below(top.width)
        val out = BufferedImage(top.width, top.height + bottom.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.drawImage(top, 0, 0, null); g.drawImage(bottom, 0, top.height, null)
        g.dispose()
        return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(out, "png", it) }.toByteArray()
    }

    /**
     * Which line made what. `identify` draws the frame at [seconds] with every rectangle outlined in its
     * line's colour and labelled `L<line>`, and lists the lines; `build` shows the scene as it stands
     * after each line that makes something, so a program reads as a sequence of pictures.
     */
    fun trace(id: String = "current", seconds: Double = 0.0, mode: String = "identify", size: Int = 0): StudyReport {
        val r = revisions.revision(id)
        return when (mode) {
            "identify" -> identify(r, seconds, size)
            "build" -> buildUp(r, seconds, size)
            else -> throw StudyRejection("mode is identify or build")
        }
    }

    private val lineColours = listOf(0xff6b6b, 0x4dabf7, 0xffd43b, 0x69db7c, 0xda77f2, 0xff922b, 0x3bc9db, 0xf783ac, 0xa9e34b, 0x748ffc)

    private fun identify(r: SourceRevision<RectSLCheck>, seconds: Double, size: Int): StudyReport {
        val d = drawableOf(r)
        val scene = d.scene
        val compiled = compile(r.source)
        val long = (if (size > 0) size else 720).coerceIn(160, 1280)
        val clock = System.nanoTime()
        val raster = framesOf(d, long, listOf(seconds)).single()
        val shown = StackSLStudyShader.presented(raster)
        val w = raster.width; val h = raster.height
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, w, h, shown.toArgb(), 0, w)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val wall = scene.wall
        fun px(x: Double) = if (wall.origin == Origin.TopLeft) x / wall.width * w else (x + wall.width / 2) / wall.width * w
        fun py(y: Double) = if (wall.origin == Origin.TopLeft) y / wall.height * h else (wall.height / 2 - y) / wall.height * h
        val sampled = scene.sample(seconds).rects.filter { visibleOn(it, scene) }
        val byLine = sampled.groupBy { compiled.origins[it.id]?.line ?: 0 }.toSortedMap()
        val colourOf = byLine.keys.withIndex().associate { (i, line) -> line to Color(lineColours[i % lineColours.size]) }
        g.font = Font(Font.MONOSPACED, Font.BOLD, 12)
        val labelled = HashSet<Int>()
        sampled.forEach { rect ->
            val line = compiled.origins[rect.id]?.line ?: 0
            val colour = colourOf.getValue(line)
            val hw = 0.5 * rect.size.x * rect.scale.x; val hh = 0.5 * rect.size.y * rect.scale.y
            val c = cos(rect.rotation); val s = sin(rect.rotation)
            val corners = listOf(-hw to -hh, hw to -hh, hw to hh, -hw to hh).map { (x, y) -> px(rect.center.x + c * x - s * y) to py(rect.center.y + s * x + c * y) }
            g.color = Color(colour.red, colour.green, colour.blue, 220); g.stroke = BasicStroke(1.5f)
            g.drawPolygon(corners.map { it.first.toInt() }.toIntArray(), corners.map { it.second.toInt() }.toIntArray(), 4)
            // One label per line when a line makes many rectangles, else one per rectangle.
            if (sampled.size <= 40 || labelled.add(line)) {
                val label = if (line > 0) "L$line" else "?"
                val lx = px(rect.center.x).toInt() - 10; val ly = py(rect.center.y).toInt() + 4
                g.color = Color(0, 0, 0, 170); g.fillRect(lx - 2, ly - 11, g.fontMetrics.stringWidth(label) + 4, 14)
                g.color = colour; g.drawString(label, lx, ly)
            }
        }
        g.dispose()
        val argb = IntArray(w * h); image.getRGB(0, 0, w, h, argb, 0, w)
        val lines = r.source.lines()
        val (tw, th) = tileSize(scene, min(long, 900))
        val bytes = StackSLStudyEvidence.sheet("RectSL trace · ${r.id} · t = ${secs(seconds)} s",
            "Each visible rectangle outlined in its line's colour; L<n> is the line of the program that made it.",
            listOf(listOf(StackSLStudyEvidence.Tile(argb, w, h, "${sampled.size} visible rectangles", ""))), tw, tileHeight = th,
            footer = byLine.keys.take(8).joinToString("   ") { "L$it ${lines.getOrElse(it - 1) { "" }.substringBefore('#').trim().take(28)}" })
        val file = save("${r.id}-identify-$seconds.png", bytes)
        return StudyReport(r.id, linkedMapOf(
            "revision" to r.id, "seconds" to seconds,
            "lines" to byLine.map { (line, rects) ->
                linkedMapOf("line" to line, "text" to lines.getOrElse(line - 1) { "" }.trim().take(120),
                    "colour" to "#%06x".format(colourOf.getValue(line).rgb and 0xffffff),
                    "visible" to rects.size, "made" to compiled.origins.count { it.value.line == line },
                    "examples" to rects.take(3).map { rectFacts(it) })
            },
            "hidden" to (scene.nodes.count { it is RectNode } - sampled.size).takeIf { it > 0 }?.let { "$it rectangles are not visible at this time" },
            "imageFile" to file.absolutePath, "renderSeconds" to cost(clock, 1),
            "frame" to FRAME
        ), listOf(ToolImage(png(bytes), "${r.id}: rectangles at ${secs(seconds)} s outlined and labelled with the line that made them")))
    }

    private fun rectFacts(r: SampledRect) = linkedMapOf(
        "id" to r.id, "centre" to listOf(round4(r.center.x), round4(r.center.y)), "size" to listOf(round4(r.size.x * r.scale.x), round4(r.size.y * r.scale.y)),
        "rotationDegrees" to round4(Math.toDegrees(r.rotation)).takeIf { abs(it) > 1e-6 }, "alpha" to round4(r.alpha).takeIf { it < 1.0 },
        "fill" to when (val f = r.fill) { is PaintSpec.Solid -> hex(f.color); is PaintSpec.LinearGradient -> "${hex(f.c0)} → ${hex(f.c1)}" },
        "material" to r.material.name.takeIf { it != "plain" })

    /** The root-level cut points of [source]: for each line that starts a root-level token, the source before the next line's first token. */
    private fun cuts(source: String): List<Pair<Int, String>> {
        val tokenizer = RectLangTokenizer(source)
        val tokens = tokenizer.tokenize()
        val starts = tokens.mapNotNull { tokenizer.offsets[it] }
        val lineOf = starts.map { rectSLLineAndColumn(source, it).first }
        return lineOf.indices.filter { i -> i == lineOf.lastIndex || lineOf[i + 1] != lineOf[i] }.map { i ->
            lineOf[i] to (if (i == lineOf.lastIndex) source else source.substring(0, starts[i + 1]))
        }
    }

    /**
     * What [line] of [source] shows, for the editor ([afield.stdlib.LineNotes]): the scene built up to the
     * end of the statement the line is in — the program cut there, as Trace's build mode cuts it — at
     * [seconds], with the rectangles the line itself makes outlined. So a line reads as how far the
     * scene has got, as a StackSL line reads as its stage. Null when the scene does not compile up to
     * the line. Recent notes are kept.
     */
    fun lineNote(source: String, line: Int, seconds: Double): afield.stdlib.LineNotes.Note? {
        val t = Math.round(seconds * 10) / 10.0
        val key = "${SourceStudy.hash(source)}:$line:$t"
        synchronized(lineNotes) { lineNotes[key]?.let { return it.orElse(null) } }
        val note = runCatching {
            val full = runCatching { drawable(source) }.getOrNull()
            val made = full?.let { RectSLWindowLinks.idsForLine(it, line) }.orEmpty()
            val (through, pd) = cuts(source).filter { it.first >= line }
                .firstNotNullOfOrNull { (l, prefix) -> runCatching { l to drawable(prefix) }.getOrNull() } ?: return@runCatching null
            val raster = framesOf(pd, LINE_NOTE_LONG, listOf(t)).single()
            val shown = StackSLStudyShader.presented(raster)
            val w = raster.width; val h = raster.height
            val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            image.setRGB(0, 0, w, h, shown.toArgb(), 0, w)
            val wall = pd.scene.wall
            fun px(x: Double) = if (wall.origin == Origin.TopLeft) x / wall.width * w else (x + wall.width / 2) / wall.width * w
            fun py(y: Double) = if (wall.origin == Origin.TopLeft) y / wall.height * h else (wall.height / 2 - y) / wall.height * h
            val sampled = pd.scene.sample(t).rects
            val outlined = sampled.filter { it.id in made }
            val g = image.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.stroke = BasicStroke(1.5f); g.color = Color(0x5c, 0xe1, 0xff)
            outlined.forEach { r ->
                val hw = 0.5 * r.size.x * abs(r.scale.x); val hh = 0.5 * r.size.y * abs(r.scale.y)
                val c = cos(r.rotation); val sn = sin(r.rotation)
                val corners = listOf(-hw to -hh, hw to -hh, hw to hh, -hw to hh).map { (x, y) -> px(r.center.x + c * x - sn * y) to py(r.center.y + sn * x + c * y) }
                g.drawPolygon(corners.map { it.first.toInt() }.toIntArray(), corners.map { it.second.toInt() }.toIntArray(), 4)
            }
            g.dispose()
            val argb = IntArray(w * h).also { image.getRGB(0, 0, w, h, it, 0, w) }
            val onWall = sampled.count { visibleOn(it, pd.scene) }
            val scope = if (through == line) "the scene so far" else "the scene through line $through, where its statement ends"
            afield.stdlib.LineNotes.Note(
                "$scope, at ${secs(t)} s: $onWall rectangle${if (onWall == 1) "" else "s"} on the wall" +
                    (if (outlined.isNotEmpty()) ", the ${outlined.size} it makes outlined" else "") + " · ${rasterizer.description}",
                PngEncoder.encode(argb, w, h))
        }.getOrNull()
        synchronized(lineNotes) {
            lineNotes[key] = java.util.Optional.ofNullable(note)
            while (lineNotes.size > 64) lineNotes.remove(lineNotes.keys.first())
        }
        return note
    }

    private val lineNotes = LinkedHashMap<String, java.util.Optional<afield.stdlib.LineNotes.Note>>()

    private fun buildUp(r: SourceRevision<RectSLCheck>, seconds: Double, size: Int): StudyReport {
        val d = drawableOf(r)
        val compiled = compile(r.source)
        val making = compiled.origins.values.map { it.statementLine }.toSet()
        val all = cuts(r.source).filter { it.first in making }
        val shown = if (all.size <= 16) all else (all.indices.filter { it % ((all.size + 14) / 15) == 0 } + all.lastIndex).distinct().map { all[it] }
        val long = (if (size > 0) size else 240).coerceIn(48, 480)
        val clock = System.nanoTime()
        val lines = r.source.lines()
        val (tw, th) = tileSize(d.scene, 240)
        val facts = mutableListOf<Map<String, Any?>>()
        val tiles = shown.map { (line, prefix) ->
            val text = lines.getOrElse(line - 1) { "" }.trim()
            try {
                val pd = drawable(prefix)
                val raster = framesOf(pd, long, listOf(seconds)).single()
                val count = pd.scene.nodes.count { it is RectNode }
                facts += linkedMapOf("line" to line, "text" to text.take(120), "rects" to count, "compiles" to true)
                StackSLStudyEvidence.tile(StackSLStudyShader.presented(raster), "after line $line · $count rects", text)
            } catch (e: StudyRejection) {
                facts += linkedMapOf("line" to line, "text" to text.take(120), "compiles" to false)
                StackSLStudyEvidence.tile(null, "after line $line", text, "does not compile on its own")
            }
        }
        val bytes = StackSLStudyEvidence.sheet("RectSL build · ${r.id} · t = ${secs(seconds)} s",
            "The scene as it stands after each line that makes something" + if (shown.size < all.size) " (${shown.size} of ${all.size}, evenly spaced)" else "",
            tiles.chunked(4), tw, tileHeight = th)
        val file = save("${r.id}-build-$seconds.png", bytes)
        return StudyReport(r.id, linkedMapOf("revision" to r.id, "seconds" to seconds, "stages" to facts,
            "imageFile" to file.absolutePath, "renderSeconds" to cost(clock, tiles.size),
            "method" to "Each tile draws the program cut after that line; a cut inside a definition does not compile on its own."),
            listOf(ToolImage(png(bytes), "${r.id}: the scene after each line that makes something, at ${secs(seconds)} s")))
    }

    /** The knobs of a revision, and — unless [sensitivity] is false — how much a small nudge to each changes the frames. */
    fun knobs(id: String = "current", seconds: List<Double> = emptyList(), sensitivity: Boolean = true): StudyReport {
        val r = revisions.revision(id)
        val d = drawableOf(r)
        val times = timesFor(r, d.scene, seconds, 2)
        val base = if (sensitivity) framesOf(d, 96, times) else null
        val epoch = revisions.epoch()
        val rows = r.value.knobs.mapIndexed { i, k ->
            val summary: Map<String, Any?> = linkedMapOf("knob" to knobSummary(k), "context" to k.context)
            if (base == null || i >= 40) return@mapIndexed summary
            if (revisions.isCancelled(epoch)) throw CancellationException()
            val step = when {
                k.colour != null && k.text.startsWith("0x") -> null
                abs(k.value) > 1e-3 -> abs(k.value) * 0.1
                else -> 0.05
            } ?: return@mapIndexed summary
            val effect = try {
                val nudged = frames(setRectSLKnobs(r.source, mapOf(k.knob to k.value + step)), 96, times)
                val diffs = base.indices.map { StackSLStudyEvidence.difference(base[it], nudged[it], null, false).first }
                val mean = diffs.maxOf { it.mean }; val maxLevels = diffs.maxOf { it.max }
                mapOf("nudge" to round4(step), "meanLevels" to round4(mean), "maxLevels" to maxLevels,
                    "reading" to when { maxLevels == 0.0 -> "no visible effect at these times"; mean < 0.5 -> "subtle"; mean < 4 -> "moderate"; else -> "strong" })
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mapOf("nudge" to round4(step), "error" to e.message)
            }
            summary + ("sensitivity" to effect)
        }
        return StudyReport(r.id, linkedMapOf("revision" to r.id, "seconds" to times, "knobs" to rows,
            "method" to "Each knob nudged by 10% of its value (0.05 near zero), frames drawn 96 px long at these times, compared in 8-bit levels; hex colours are not nudged. " +
                "A knob with no visible effect may matter only at another time, or move something off the wall."))
    }

    /** One knob at several values, side by side at [seconds]. Nothing is published. */
    fun sweep(id: String = "current", knob: Int, values: List<Double>, seconds: Double = 0.0, size: Int = 0): StudyReport {
        val r = revisions.revision(id)
        val k = r.value.knobs.getOrNull(knob) ?: throw StudyRejection("No knob $knob; ${r.id} has ${r.value.knobs.size}")
        if (values.size !in 2..9) throw StudyRejection("Sweep 2..9 values")
        val d = drawableOf(r)
        val long = (if (size > 0) size else 240).coerceIn(48, 480)
        val clock = System.nanoTime()
        val results = values.map { value ->
            try {
                Triple(value, frames(setRectSLKnobs(r.source, mapOf(knob to value)), long, listOf(seconds)).single(), null as String?)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Triple(value, null, e.message)
            }
        }
        val (tw, th) = tileSize(d.scene, 240)
        val tiles = results.map { (value, raster, error) ->
            StackSLStudyEvidence.tile(raster?.let(StackSLStudyShader::presented), formatRectSLKnob(value, k.text) + if (abs(value - k.value) < 1e-12) "  (now)" else "",
                "", error)
        }
        val bytes = StackSLStudyEvidence.sheet("RectSL sweep · ${r.id} · knob $knob '${k.text}' at ${k.line}:${k.column}", "${k.role} — ${k.context}", tiles.chunked(4), tw, tileHeight = th)
        val file = save("${r.id}-sweep-$knob-${values.joinToString("_")}.png", bytes)
        return StudyReport(r.id, linkedMapOf("revision" to r.id, "knob" to knobSummary(k), "seconds" to seconds,
            "values" to results.map { (value, _, error) -> linkedMapOf("value" to value, "error" to error) },
            "imageFile" to file.absolutePath, "published" to false, "renderSeconds" to cost(clock, values.size)),
            listOf(ToolImage(png(bytes), "${r.id}: knob $knob ('${k.text}') at ${values.joinToString()}")))
    }

    /** A candidate for [tryVariants]: a complete program, or exact edits to the live source. */
    data class Variant(val label: String, val source: String = "", val edits: List<StudySourceEdit> = emptyList(), val note: String = "")

    /** Check and draw several candidates beside the current scene, and hold them — unpublished — for [adoptVariant]. */
    fun tryVariants(variants: List<Variant>, seconds: List<Double> = emptyList(), size: Int = 0): StudyReport {
        if (variants.size !in 1..8) throw StudyRejection("Try 1..8 variants at a time")
        if (variants.map { it.label }.distinct().size != variants.size || variants.any { !it.label.matches(Regex("[A-Za-z0-9][A-Za-z0-9 _.-]{0,31}")) }) {
            throw StudyRejection("Variant labels must be distinct and short: up to 32 letters, digits, spaces, '.', '-' or '_', starting with a letter or digit")
        }
        val current = revisions.revision()
        val live = revisions.liveSource()
        val d = drawableOf(current)
        val times = seconds.filter { it.isFinite() }.distinct().take(4).ifEmpty { if (current.value.animated) defaultTimes(d.scene, 3) else listOf(0.0) }
        val long = (if (size > 0) size else 240).coerceIn(48, 480)
        val clock = System.nanoTime()
        val rows = mutableListOf(framesOf(d, long, times).mapIndexed { i, f -> StackSLStudyEvidence.tile(StackSLStudyShader.presented(f), "t = ${secs(times[i])} s", "current") })
        val labels = mutableListOf("${current.id}\n(current)")
        val held = linkedMapOf<String, Speculation>()
        val epoch = revisions.epoch()
        val facts = variants.map { v ->
            if (revisions.isCancelled(epoch)) throw CancellationException()
            labels += v.label + if (v.note.isNotBlank()) "\n" + v.note.take(40) else ""
            try {
                val source = v.source.ifBlank { SourceStudy.applyEdits(live, v.edits) }
                val admitted = revisions.check(source)
                val frames = framesOf(drawable(source), long, times)
                rows += frames.mapIndexed { i, f -> StackSLStudyEvidence.tile(StackSLStudyShader.presented(f), "t = ${secs(times[i])} s", v.label) }
                held[v.label] = Speculation(v.label, v.note, source)
                linkedMapOf("label" to v.label, "ok" to true, "note" to v.note, "warnings" to admitted.value.warnings.ifEmpty { null },
                    "rects" to admitted.value.rects, "animated" to admitted.value.animated, "knobs" to admitted.value.knobs.size)
            } catch (e: StudyRejection) {
                rows += listOf(StackSLStudyEvidence.tile(null, v.label, "", e.message))
                linkedMapOf("label" to v.label, "ok" to false, "error" to e.message)
            }
        }
        synchronized(speculations) { speculations.clear(); speculations.putAll(held) }
        val (tw, th) = tileSize(d.scene, 240)
        val bytes = StackSLStudyEvidence.sheet("RectSL variants · against ${current.id}", "Unpublished candidates; adopt one with AdoptRectSLVariant, or ask for more.",
            rows, tw, labels, tileHeight = th)
        val batch = File(directory, "speculations/${System.currentTimeMillis()}").also { it.mkdirs() }
        File(batch, "sheet.png").writeBytes(bytes)
        val files = held.values.associate { it.label to it.label.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".rectsl" }
        held.values.forEach { File(batch, files.getValue(it.label)).writeText(it.source) }
        File(batch, "variants.json").writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(linkedMapOf(
            "against" to current.id, "liveSourceHash" to SourceStudy.hash(live), "adopted" to null,
            "variants" to held.values.map { linkedMapOf("label" to it.label, "note" to it.note, "file" to files.getValue(it.label)) })))
        heldBatch = batch
        runCatching { onChange?.invoke() }
        return StudyReport(current.id, linkedMapOf("against" to current.id, "liveDirty" to (live != current.source), "seconds" to times,
            "variants" to facts, "held" to held.keys.toList(), "imageFile" to File(batch, "sheet.png").absolutePath, "published" to false,
            "renderSeconds" to cost(clock, (1 + variants.size) * times.size)),
            listOf(ToolImage(png(bytes), "Variants against ${current.id}: first row current, then one row per candidate")))
    }

    private fun latestBatch(): File? = heldBatch ?: File(directory, "speculations").listFiles().orEmpty()
        .filter { File(it, "variants.json").isFile }.maxByOrNull { it.name.toLongOrNull() ?: 0L }

    /** The candidates [tryVariants] is holding: label, note, source. */
    fun heldVariants(): List<Triple<String, String, String>> = synchronized(speculations) { speculations.values.map { Triple(it.label, it.note, it.source) } }

    /** Publish a held candidate. */
    fun adoptVariant(expectedRevision: String, expectedSourceHash: String, label: String, description: String = ""): StudyReport {
        val chosen = synchronized(speculations) { speculations[label] } ?: throw StudyRejection("No held variant '$label'; held: ${heldVariants().joinToString { it.first }.ifBlank { "none" }}")
        val report = published(revisions.edit(expectedRevision, expectedSourceHash, chosen.source,
            description.ifBlank { "adopt variant $label" + if (chosen.note.isNotBlank()) ": ${chosen.note}" else "" }), emptyList(), "Adopted variant $label")
        synchronized(speculations) { speculations.clear() }
        latestBatch()?.let { batch ->
            runCatching {
                val record = mapper.readTree(File(batch, "variants.json")) as com.fasterxml.jackson.databind.node.ObjectNode
                record.put("adopted", label).put("as", report.revision)
                File(batch, "variants.json").writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(record))
            }
        }
        return report
    }

    /**
     * Test what an edit preserved: pixels (`rgb`, or `luma` for brightness only), held to one level
     * outside [region] (`x0, y0, x1, y1` in wall units), and which rectangles appeared, went, or
     * changed — by id, with the line that made them.
     */
    fun compare(before: String, after: String = "current", seconds: List<Double> = emptyList(), size: Int = 0, mode: String = "rgb",
                region: List<Double>? = null): StudyReport {
        if (mode !in setOf("rgb", "luma")) throw StudyRejection("mode is rgb or luma")
        val a = revisions.revision(before); val b = revisions.revision(after)
        val da = drawableOf(a); val db = drawableOf(b)
        val times = seconds.filter { it.isFinite() }.distinct().take(6).ifEmpty {
            if (a.value.animated || b.value.animated) defaultTimes(db.scene, 3) else listOf(0.0)
        }
        val uvRegion = region?.let {
            if (it.size != 4 || it[0] >= it[2] || it[1] >= it[3]) throw StudyRejection("A region is [x0, y0, x1, y1] in wall units, x0 < x1 and y0 < y1")
            val wall = db.scene.wall
            if (wall.origin == Origin.TopLeft) listOf(it[0] / wall.width, 1 - it[3] / wall.height, it[2] / wall.width, 1 - it[1] / wall.height)
            else listOf((it[0] + wall.width / 2) / wall.width, (it[1] + wall.height / 2) / wall.height, (it[2] + wall.width / 2) / wall.width, (it[3] + wall.height / 2) / wall.height)
        }
        val long = (if (size > 0) size else 320).coerceIn(48, 640)
        val clock = System.nanoTime()
        // Both drawn at the after-scene's frame size, so two walls of different shapes still line up pixel for pixel.
        val (w, h) = RectSLStudyShader.frameSize(db.scene, long)
        val allA = rasterizer.renderAll(da, w, h, times); val allB = rasterizer.renderAll(db, w, h, times)
        val rows = mutableListOf<List<StackSLStudyEvidence.Tile>>()
        val results = times.mapIndexed { i, t ->
            val (outside, inside) = StackSLStudyEvidence.difference(allA[i], allB[i], uvRegion, mode == "luma")
            rows += listOf(
                StackSLStudyEvidence.tile(StackSLStudyShader.presented(allA[i]), a.id, "t = ${secs(t)} s"),
                StackSLStudyEvidence.tile(StackSLStudyShader.presented(allB[i]), b.id, "t = ${secs(t)} s"),
                StackSLStudyEvidence.differenceTile(allA[i], allB[i], "difference", "outside: max ${fmt(outside.max)} levels"))
            linkedMapOf("seconds" to t, "outside" to outside, "inside" to inside)
        }
        val preserved = results.all { (it["outside"] as StackSLDifference).max <= 1.0 }
        val originsB = compile(b.source).origins; val originsA = compile(a.source).origins
        val changes = sceneChanges(da.scene, db.scene, times, originsA, originsB)
        val (tw, th) = tileSize(db.scene, 280)
        val bytes = StackSLStudyEvidence.sheet("RectSL compare · ${a.id} → ${b.id} · $mode",
            (region?.let { "Change allowed inside [${it.joinToString()}]; " } ?: "No change allowed; ") + "held to one 8-bit level.", rows, tw, tileHeight = th)
        val file = save("${a.id}-vs-${b.id}-$mode.png", bytes)
        return StudyReport(b.id, linkedMapOf("before" to a.id, "after" to b.id, "mode" to mode, "region" to region, "results" to results,
            "preservedOutsideRegion" to preserved, "rectangles" to changes,
            "imageFile" to file.absolutePath, "renderSeconds" to cost(clock, 2 * times.size),
            "method" to "Both drawn by ${rasterizer.description} at the same times and size; rectangles compared by id, sampled at the same times."),
            listOf(ToolImage(png(bytes), "${a.id} → ${b.id}: before | after | difference, per time")))
    }

    private fun sceneChanges(a: SceneProgram, b: SceneProgram, times: List<Double>, originsA: Map<String, RectSLOrigin>, originsB: Map<String, RectSLOrigin>): Map<String, Any?> {
        val idsA = a.nodes.filterIsInstance<RectNode>().map { it.id }.toSet(); val idsB = b.nodes.filterIsInstance<RectNode>().map { it.id }.toSet()
        fun lineOf(id: String, origins: Map<String, RectSLOrigin>) = origins[id]?.line
        val changed = linkedMapOf<String, MutableSet<String>>()
        times.forEach { t ->
            val sa = a.sample(t).rects.associateBy { it.id }; val sb = b.sample(t).rects.associateBy { it.id }
            (sa.keys intersect sb.keys).forEach { id ->
                val x = sa.getValue(id); val y = sb.getValue(id)
                val what = mutableSetOf<String>()
                if ((x.center - y.center).length() > 1e-6) what += "position"
                if (abs(x.size.x * x.scale.x - y.size.x * y.scale.x) > 1e-6 || abs(x.size.y * x.scale.y - y.size.y * y.scale.y) > 1e-6) what += "size"
                if (abs(x.rotation - y.rotation) > 1e-6) what += "rotation"
                if (abs(x.alpha - y.alpha) > 1e-6) what += "opacity"
                if (x.fill != y.fill) what += "fill"
                if (x.material.name != y.material.name) what += "material"
                if (abs(x.z - y.z) > 1e-9) what += "order"
                if (what.isNotEmpty()) changed.getOrPut(id) { mutableSetOf() } += what
            }
        }
        val byLine = changed.entries.groupBy { lineOf(it.key, originsB) }.map { (line, entries) ->
            linkedMapOf("line" to line, "rects" to entries.size, "what" to entries.flatMap { it.value }.toSortedSet().toList(), "examples" to entries.take(4).map { it.key })
        }
        return linkedMapOf(
            "unchanged" to ((idsA intersect idsB).size - changed.size),
            "changed" to byLine.ifEmpty { null },
            "added" to (idsB - idsA).groupBy { lineOf(it, originsB) }.map { (line, ids) -> mapOf("line" to line, "rects" to ids.size, "examples" to ids.take(4)) }.ifEmpty { null },
            "removed" to (idsA - idsB).groupBy { lineOf(it, originsA) }.map { (line, ids) -> mapOf("lineBefore" to line, "rects" to ids.size, "examples" to ids.take(4)) }.ifEmpty { null },
            "note" to "Rectangles are matched by id. An id that depends on creation order (rect-12) can shift when a line above it adds or removes one; name rectangles (\"sun\" rect) to compare them across edits.")
    }

    // ---- Leaving ------------------------------------------------------------------------------

    private fun exportable(id: String, expectedSourceHash: String): SourceRevision<RectSLCheck> {
        val r = revisions.revision(id)
        if (SourceStudy.hash(r.source) != expectedSourceHash) throw StudyRejection("Export source hash differs from ${r.id}'s")
        if (r.id == revisions.currentId() && revisions.liveSource() != r.source) {
            throw StudyRejection("The editor holds an unchecked edit; check it in or reconcile it before exporting ${r.id}.")
        }
        return r
    }

    /** One export running or finished in the background — see [startExport]. */
    data class ExportJob(val id: String, val kind: String, val path: String, val revision: String, val frames: Int) {
        @Volatile var framesDone: Int = 0
        @Volatile var status: String = "queued"
        @Volatile var message: String? = null
        @Volatile var elapsedSeconds: Double = 0.0
        @Volatile var cancelled: Boolean = false
    }

    private val exportJobs = java.util.concurrent.CopyOnWriteArrayList<ExportJob>()
    private val exportWorker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "rectsl-study-export").apply { isDaemon = true } }

    /** Every export this session started, newest last. */
    fun exports(): List<Map<String, Any?>> = exportJobs.map {
        linkedMapOf("id" to it.id, "kind" to it.kind, "path" to it.path, "revision" to it.revision, "status" to it.status,
            "frames" to "${it.framesDone}/${it.frames}", "elapsedSeconds" to it.elapsedSeconds, "message" to it.message)
    }

    /**
     * Export a checked revision in the background and return at once: `png` (one frame at [seconds]) or
     * `mp4` (the whole clip at the scene's fps, looped [loops] times). Progress appears in [exports],
     * [inspect] and the card.
     */
    fun startExport(id: String, expectedSourceHash: String, file: File, kind: String, size: Int = 0, seconds: Double = 0.0,
                    loops: Int = 1, overwrite: Boolean = false): StudyReport {
        val r = exportable(id, expectedSourceHash)
        if (kind !in setOf("png", "mp4")) throw StudyRejection("kind is png or mp4")
        if (file.exists() && !overwrite) throw StudyRejection("${file.name} exists; ask for overwrite to replace it")
        if (kind == "mp4" && ffmpeg() == null) throw StudyRejection("No ffmpeg found; install it to export video")
        if (exportJobs.any { it.path == file.absolutePath && it.status in setOf("queued", "running") }) throw StudyRejection("${file.name} is already being exported")
        val scene = drawableOf(r).scene
        val long = (if (size > 0) size else if (kind == "png") 1920 else 1280).coerceIn(64, if (kind == "png") 4096 else 1920)
        val frames = if (kind == "png") 1 else (scene.duration * scene.fps).toInt().coerceAtLeast(1) * loops.coerceIn(1, 8)
        if (kind == "mp4" && frames > 3600) throw StudyRejection("That clip is $frames frames; the limit is 3600")
        val job = ExportJob("x${exportJobs.size + 1}", kind, file.absolutePath, r.id, frames)
        exportJobs += job
        exportWorker.execute {
            val clock = System.nanoTime()
            job.status = "running"
            try {
                if (kind == "png") exportPng(r, file, long, seconds)
                else exportVideo(r, file, long, loops.coerceIn(1, 8)) { done ->
                    job.framesDone = done
                    if (job.cancelled) throw CancellationException("cancelled")
                }
                job.framesDone = frames
                job.status = "done"
                job.message = "${file.length()} bytes"
            } catch (e: Exception) {
                job.status = if (job.cancelled) "cancelled" else "failed"
                job.message = if (job.cancelled) null else e.message
            } finally {
                job.elapsedSeconds = ((System.nanoTime() - clock) / 1e8).toInt() / 10.0
                runCatching { onChange?.invoke() }
            }
        }
        runCatching { onChange?.invoke() }
        return StudyReport(r.id, linkedMapOf("message" to "Export started in the background", "job" to job.id, "kind" to kind,
            "path" to file.absolutePath, "longSide" to long, "frames" to frames, (if (kind == "png") "atSeconds" else "fps") to (if (kind == "png") seconds else scene.fps),
            "note" to "The file appears when the job finishes; its progress is in InspectRectSLStudy's exports and on the card."))
    }

    /** Stop a running export after its current frame. */
    fun cancelExport(jobId: String): StudyReport {
        val job = exportJobs.firstOrNull { it.id == jobId } ?: throw StudyRejection("No export $jobId")
        if (job.status !in setOf("queued", "running")) return StudyReport(job.revision, mapOf("message" to "Export $jobId is already ${job.status}"))
        job.cancelled = true
        return StudyReport(job.revision, mapOf("message" to "Export $jobId will stop after its current frame"))
    }

    private fun exportPng(r: SourceRevision<RectSLCheck>, file: File, long: Int, seconds: Double) {
        val bytes = StackSLStudyShader.presented(frames(r.source, long, listOf(seconds)).single()).toPngBytes()
        file.absoluteFile.parentFile.mkdirs()
        val temp = Files.createTempFile(file.absoluteFile.parentFile.toPath(), ".rectsl-export-", ".png")
        Files.write(temp, bytes)
        Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /** Where ffmpeg is, or null. */
    fun ffmpeg(): File? = (listOf("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg").map(::File) +
        System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "ffmpeg") }).firstOrNull { it.canExecute() }

    /**
     * Frames straight into ffmpeg's stdin as raw RGB, one after another as they are drawn: writing each
     * as a PNG first cost more than drawing it (a 36 s clip ran at two frames a second).
     */
    private fun exportVideo(r: SourceRevision<RectSLCheck>, file: File, long: Int, loops: Int, progress: (Int) -> Unit) {
        val encoder = ffmpeg() ?: throw StudyRejection("No ffmpeg found; install it to export video")
        val d = drawableOf(r)
        val perLoop = (d.scene.duration * d.scene.fps).toInt().coerceAtLeast(1)
        val times = List(perLoop * loops) { (it % perLoop) / d.scene.fps }
        val (w, h) = RectSLStudyShader.frameSize(d.scene, long)
        file.absoluteFile.parentFile.mkdirs()
        val temp = File(file.absoluteFile.parentFile, ".rectsl-export-${System.nanoTime()}.mp4")
        val log = File.createTempFile("rectsl-ffmpeg", ".log")
        val process = ProcessBuilder(encoder.path, "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", "${w}x$h",
            "-framerate", "${d.scene.fps}", "-i", "-", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-movflags", "+faststart", temp.path)
            .redirectErrorStream(true).redirectOutput(log).start()
        try {
            val frame = ByteArray(w * h * 3)
            process.outputStream.buffered(1 shl 20).use { out ->
                // Drawn in chunks so a GPU readback batch stays short and progress moves.
                times.chunked(24).forEachIndexed { chunk, slice ->
                    rasterizer.render(d, w, h, slice) { i, raw ->
                        for (p in 0 until w * h) for (c in 0 until 3) frame[p * 3 + c] = shownByte(raw.rgba[p * 4 + c]).toByte()
                        out.write(frame)
                        progress(chunk * 24 + i + 1)
                    }
                }
            }
            if (!process.waitFor(10, TimeUnit.MINUTES) || process.exitValue() != 0 || !temp.isFile) {
                temp.delete(); throw StudyRejection("ffmpeg failed: ${log.readText().take(600)}")
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            temp.delete()
            log.delete()
        }
    }

    /** A linear value as the byte the window shows: clamped and sRGB-encoded, through a table fine enough to give back the drawn byte. */
    private fun shownByte(v: Float): Int = if (!v.isFinite()) 0 else SHOWN_BYTES[(v.coerceIn(0f, 1f) * (SHOWN_BYTES.size - 1) + 0.5f).toInt()].toInt() and 0xff

    private val SHOWN_BYTES: ByteArray by lazy {
        ByteArray(65536) { i -> Math.round(StackSLStudyShader.linearToSrgb(i / 65535f).coerceIn(0f, 1f) * 255f).toByte() }
    }

    /**
     * A PNG of a revision at [long] px along its longer side and time [seconds] — what the card shows.
     * Frames the GPU drew are cached; frames the CPU reference drew because the window was not up yet
     * are not, so the card's next redraw, once it is, draws them on the GPU.
     */
    fun previewPng(id: String = "current", long: Int = 240, seconds: Double = 0.0): ByteArray {
        val r = revisions.revision(id)
        val key = "${r.id}@$long@$seconds"
        previews[key]?.let { return it }
        val file = File(File(directory, "thumbnails"), "${r.id}-$long-$seconds.png")
        if (file.isFile) return file.readBytes().also { previews[key] = it }
        val bytes = StackSLStudyShader.presented(frames(r.source, long, listOf(seconds)).single()).toPngBytes()
        if (rasterizer.description.startsWith("GPU")) {
            previews[key] = bytes
            file.parentFile.mkdirs()
            file.writeBytes(bytes)
        }
        return bytes
    }
}

/** What the check found about an admitted scene. */
data class RectSLCheck(
    val wallWidth: Double,
    val wallHeight: Double,
    val unit: String,
    /** `centre, +y up` or `top-left, +y down`. */
    val origin: String,
    val duration: Double,
    val fps: Double,
    val background: String,
    val rects: Int,
    val groups: Int,
    /** Rectangles on the wall at the check's probe times. */
    val visibleAtProbes: List<Map<String, Any>>,
    /** Whether anything changes over time: a track, or a material that reads time. */
    val animated: Boolean,
    val materials: List<String>,
    /** Material warnings and what the animation analysis noticed; kept, not refused. */
    val warnings: List<String>,
    val knobs: List<RectSLKnob>,
    /** The lines that make rectangles or groups, with how many each makes. */
    val lines: List<RectSLLineReport>
)

/** A line of the program that makes rectangles or groups. */
data class RectSLLineReport(val line: Int, val text: String, val comment: String?, val nodes: Int, val rects: Int, val ids: List<String>)

/**
 * `{{ … }}` Kotlin escapes for study and runtime compiles: each distinct escape is compiled to its lambda
 * once, on a dedicated Kotlin engine created on first use, and reused by every later compile. Not the
 * box's own engine — a box engine busy running code would be re-entered — and never on Field's main
 * thread, which a compile could block.
 */
object RectSLEscapes {
    private val engine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { KotlinInterface2(filePrefix = "RECTSL_STUDY_ESCAPE_") }
    private val compiled = java.util.concurrent.ConcurrentHashMap<String, (RectEscapeContext) -> Any?>()

    /** The lambdas for every escape in [source], compiling the ones not seen before. */
    fun precompiled(source: String): Map<String, (RectEscapeContext) -> Any?> {
        val wanted = LinkedHashSet<String>()
        fun walk(tokens: List<afield.rectsl.Token>) {
            tokens.forEach { t -> if (t is afield.rectsl.Token.KotlinEscape) wanted += t.source else if (t is afield.rectsl.Token.Quote) walk(t.tokens) }
        }
        walk(RectLangTokenizer(source).tokenize())
        if (wanted.all { compiled.containsKey(it) }) return wanted.associateWith { compiled.getValue(it) }
        check(!Runloop.isMainThread) { "RectSL {{ … }} escapes compile off Field's main thread" }
        val missing = wanted.filter { !compiled.containsKey(it) }.joinToString(" ") { "{{ $it }}" }
        compiled.putAll(runBlocking { prewarmRectSLEscapes(missing, engine) })
        return wanted.mapNotNull { key -> compiled[key]?.let { key to it } }.toMap()
    }
}
