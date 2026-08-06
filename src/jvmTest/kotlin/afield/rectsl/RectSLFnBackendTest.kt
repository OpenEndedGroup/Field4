package afield.rectsl

import afield.stdlib.Functions
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Does the differentiable backend compute the same animation the CPU sampler does?
 *
 * The oracle here is the best one available anywhere in this project. `ddf732260` shipped
 * `src/jvmTest/resources/rectsl/track_characterization.txt` — every sampled rect of all 34 gallery
 * programs at 41 times each, to full double precision — and it was **generated from pre-refactor
 * code**. So it is 37k lines of ground truth with no code path in common with anything written
 * tonight, or indeed with the symbolic IR at all.
 *
 * The comparison is arranged so that only one thing is under test. [walkScene] is a transcription
 * of [SceneProgram.sample]'s group/rect composition, because that composition has to happen
 * somewhere and the Fn backend is scalar-only. A transcription is exactly the kind of evidence that
 * quietly rots, so [theSceneWalkerIsAFaithfulTranscription] runs [walkScene] with the *original*
 * tracks and demands the result match the golden **bit for bit**. Once that passes, a disagreement
 * in the differential test belongs to the lowering and to nothing else.
 *
 * Coverage is reported alongside the error, and asserted, because a differential test that refused
 * every track would otherwise be a green no-op.
 */
class RectSLFnBackendTest {

    private val galleryDir: Path = repoRoot().resolve("public/docs/assets/rectsl_gallery")

    private val channelNames = listOf(
        "center.x", "center.y", "size.x", "size.y", "rotation", "scale.x", "scale.y", "alpha", "z"
    )

    @Test
    fun theSceneWalkerIsAFaithfulTranscription() {
        val golden = readGolden()
        assertTrue(golden.rows.isNotEmpty(), "the golden should not be empty")
        var compared = 0
        for ((program, rows) in golden.rows) {
            val compiled = compile(program) ?: continue
            val mine = walkScene(compiled) { track, t -> track.sample(t) }
            for (row in rows) {
                val actual = assertNotNull(
                    mine[row.id to row.time],
                    "$program/${row.id}@${row.time} missing from the walker"
                )
                for (channel in channelNames.indices) {
                    val got = assertNotNull(
                        actual[channel],
                        "$program/${row.id}@${row.time} ${channelNames[channel]} was declined by " +
                            "the CPU sampler, which cannot happen"
                    )
                    assertEquals(
                        row.values[channel].toRawBits(),
                        got.toRawBits(),
                        "$program/${row.id}@${row.time} ${channelNames[channel]}: " +
                            "golden=${row.values[channel]} walker=$got"
                    )
                    compared++
                }
            }
        }
        println("scene-walker transcription: $compared channel values bit-identical to the golden")
        assertTrue(compared > 100_000, "expected the whole golden, compared only $compared values")
    }

    @Test
    fun theFnBackendReproducesThePreRefactorGolden() {
        val golden = readGolden()
        var worstAbsolute = 0.0
        var worstRelative = 0.0
        var worstWhere = "(nothing compared)"
        var comparedValues = 0
        var skippedValues = 0
        var skippedRows = 0
        var comparedRows = 0
        val refusals = LinkedHashMap<String, Int>()
        val perProgram = LinkedHashMap<String, Triple<Int, Int, Int>>()

        for ((program, rows) in golden.rows) {
            val compiled = compile(program) ?: continue
            val window = Functions.Domain(0.0, (golden.duration[program] ?: 1.0) * 1.25)
            val cache = HashMap<Track<Double>, RectSLFnLowering<Functions.Fn>>()
            fun lower(track: Track<Double>): RectSLFnLowering<Functions.Fn> =
                cache.getOrPut(track) { track.toFn(window) }

            var lowered = 0
            var refused = 0
            for (track in scalarTracksOf(compiled)) {
                when (val r = lower(track)) {
                    is RectSLFnLowering.Lowered -> lowered++
                    is RectSLFnLowering.Refused -> {
                        refused++
                        refusals.merge(r.why.take(70), 1, Int::plus)
                    }
                }
            }

            // Availability is per channel, not per rect: a `loop-time` on a rotation should not
            // cost us the comparison of that rect's size, alpha and z as well. The one place it is
            // still all-or-nothing is a parent group's transform, which mixes every channel of
            // every descendant.
            val mine = walkScene(compiled) { track, t ->
                (lower(track) as? RectSLFnLowering.Lowered)?.value?.eval(t)
            }

            var valuesHere = 0
            for (row in rows) {
                val actual = mine[row.id to row.time]
                var any = false
                for (channel in channelNames.indices) {
                    val expected = row.values[channel]
                    val got = actual?.get(channel)
                    if (got == null) {
                        skippedValues++
                        continue
                    }
                    any = true
                    if (!expected.isFinite() && !got.isFinite()) continue
                    comparedValues++
                    valuesHere++
                    val delta = abs(expected - got)
                    val relative = delta / (1.0 + abs(expected))
                    if (relative > worstRelative || relative.isNaN()) {
                        worstRelative = relative
                        worstAbsolute = delta
                        worstWhere = "$program/${row.id}@${row.time} ${channelNames[channel]}: " +
                            "golden=$expected fn=$got"
                    }
                }
                if (any) comparedRows++ else skippedRows++
            }
            perProgram[program] = Triple(lowered, refused, valuesHere)
        }

        val totalLowered = perProgram.values.sumOf { it.first }
        val totalRefused = perProgram.values.sumOf { it.second }
        println("=== RectSL Fn backend vs the pre-refactor golden ===")
        println("programs:        ${perProgram.size}")
        println(
            "tracks:          $totalLowered lowered, $totalRefused refused (" +
                "%.1f".format(100.0 * totalLowered / (totalLowered + totalRefused)) + "% lowered)"
        )
        println("rows:            $comparedRows partly compared, $skippedRows skipped entirely")
        println("channel values:  $comparedValues compared, $skippedValues declined")
        println("worst relative:  $worstRelative")
        println("worst absolute:  $worstAbsolute")
        println("worst at:        $worstWhere")
        println("refusal reasons:")
        for ((why, count) in refusals.entries.sortedByDescending { it.value }) {
            println("  ${count.toString().padStart(5)}  $why")
        }
        println("per program (tracks lowered/total, channel values compared):")
        for ((program, counts) in perProgram) {
            println(
                "  ${program.padEnd(22)} tracks ${counts.first}/${counts.first + counts.second}" +
                    "   values ${counts.third}"
            )
        }
        val silent = perProgram.filterValues { it.third == 0 }.keys
        println("programs contributing nothing: ${if (silent.isEmpty()) "none" else silent.joinToString()}")

        // Coverage first: a refusal-only run must not read as a pass.
        assertTrue(perProgram.size >= 30, "expected the whole gallery, got ${perProgram.size}")
        assertTrue(
            comparedRows > 20_000,
            "the differential test only reached $comparedRows rows — coverage collapsed"
        )
        assertTrue(
            perProgram.values.all { it.third > 0 },
            "every gallery program should contribute at least one comparable channel; these did " +
                "not: " + perProgram.filterValues { it.third == 0 }.keys
        )
        // Then agreement. The lowering is exact arithmetic, not an approximation, so the only error
        // permitted is float rounding: a telescoping sum of key deltas, `pow(x, 0.5)` where the
        // sampler calls `sqrt`, and the odd re-association.
        assertTrue(worstRelative < 1e-12, "worst relative disagreement $worstRelative at $worstWhere")
    }

    @Test
    fun everyLoweredEasingAgreesWithItsOwnClosure() {
        val grid = (0..1000).map { it / 1000.0 }
        var worst = 0.0
        var worstAt = "(none)"
        for ((name, lowering) in RECTSL_EASING_FNS) {
            val closure = assertNotNull(
                RECTSL_EASINGS[name],
                "'$name' has a lowering but is not a real RectSL easing"
            )
            val fn = lowering(with(Functions) { identity(Functions.Domain.ALL) })
            for (u in grid) {
                val delta = abs(closure(u) - fn.eval(u))
                if (delta > worst) {
                    worst = delta
                    worstAt = "$name at u=$u: closure=${closure(u)} fn=${fn.eval(u)}"
                }
            }
            // The clamped-ramp sum only telescopes when a segment shape is 0 at 0 and 1 at 1.
            assertTrue(abs(fn.eval(0.0)) < 1e-12, "$name should be 0 at u=0, was ${fn.eval(0.0)}")
            assertTrue(
                abs(fn.eval(1.0) - 1.0) < 1e-12,
                "$name should be 1 at u=1, was ${fn.eval(1.0)}"
            )
        }
        println("easings lowered: ${RECTSL_EASING_FNS.size} of ${RECTSL_EASINGS.size}")
        println("worst easing disagreement: $worst ($worstAt)")
        assertTrue(worst < 1e-12, "worst easing disagreement $worst at $worstAt")
    }

    @Test
    fun everyRefusalSaysWhy() {
        val refusals = listOf<RectSLFnLowering<*>>(
            TrackOp1.FLOOR.fn, TrackOp1.CEIL.fn, TrackOp1.FRACT.fn,
            TrackOp2.DIV.fn, TrackOp2.MOD.fn, TrackOp3.SMOOTHSTEP.fn,
            TimeWarpKind.LOOP.fn, TimeWarpKind.PINGPONG.fn
        )
        for (refusal in refusals) {
            val why = assertNotNull(refusal.refusalOrNull(), "expected a refusal, got $refusal")
            assertTrue(why.length > 40, "a refusal should explain itself: '$why'")
        }
        for (op in TrackOp1.entries - setOf(TrackOp1.FLOOR, TrackOp1.CEIL, TrackOp1.FRACT)) {
            assertTrue(op.fn.valueOrNull() != null, "$op should lower")
        }
        // A refused op refuses the whole track, and the message names the op.
        val refused = MappedDoubleTrack(
            LinearScalarTrack(listOf(TcbKey(0.0, 0.0), TcbKey(1.0, 4.0))),
            TrackOp1.FLOOR
        )
        val why = refused.toFn(Functions.Domain(0.0, 1.0)).refusalOrNull()
        assertTrue(why != null && "FLOOR" in why, "the refusal should name FLOOR: $why")
    }

    @Test
    fun aWrappingWarpLowersInsideOnePeriodAndRefusesOutside() {
        val source = LinearScalarTrack(listOf(TcbKey(0.0, 0.0), TcbKey(2.0, 10.0)))
        val looped = TimeWarpTrack(source, TimeWarpKind.LOOP, 2.0)
        val inside = assertNotNull(
            looped.toFn(Functions.Domain(0.0, 1.999)).valueOrNull(),
            "a loop over less than one period should lower"
        )
        for (t in listOf(0.0, 0.5, 1.0, 1.5, 1.9)) {
            assertEquals(looped.sample(t), inside.eval(t), 1e-12, "at t=$t")
        }
        assertTrue(
            looped.toFn(Functions.Domain(0.0, 4.0)).refusalOrNull() != null,
            "a loop across the wrap should be refused"
        )
    }

    @Test
    fun aTrackTreePrintsAsItsAlgebra() {
        val track = BinaryDoubleTrack(
            LinearScalarTrack(listOf(TcbKey(0.0, 0.0), TcbKey(2.0, 4.0))),
            MappedDoubleTrack(FreeParamTrack("wobble", 0.5), TrackOp1.SIN),
            TrackOp2.ADD
        )
        assertEquals("ADD(linear[0.0=0.0 2.0=4.0], SIN(?wobble=0.5))", track.describe())
        // A constant subtree folds rather than printing its own arithmetic.
        val folded = BinaryDoubleTrack(constantTrack(2.0), constantTrack(3.0), TrackOp2.MUL)
        assertEquals("k(6.0)", folded.describe())
    }

    // -----------------------------------------------------------------------------------------
    // the corpus
    // -----------------------------------------------------------------------------------------

    private data class Row(val time: Double, val id: String, val values: DoubleArray)

    private data class Golden(
        val rows: Map<String, List<Row>>,
        val duration: Map<String, Double>
    )

    private fun readGolden(): Golden {
        val text = javaClass.getResource("/rectsl/track_characterization.txt")?.readText()
            ?: error("the characterization golden is not on the classpath")
        val rows = LinkedHashMap<String, MutableList<Row>>()
        val durations = LinkedHashMap<String, Double>()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            if (line.startsWith("#")) {
                // "# name  duration=6.0 fps=30.0 nodes=13", or a "does not compile" note.
                val name = line.removePrefix("#").trim().substringBefore(" ").removeSuffix(":")
                Regex("duration=([0-9.eE+-]+)").find(line)?.let {
                    durations[name] = it.groupValues[1].toDouble()
                }
                continue
            }
            val parts = line.split('\t')
            if (parts.size < 13) continue
            rows.getOrPut(parts[0]) { mutableListOf() } += Row(
                time = parts[1].toDouble(),
                id = parts[2],
                values = DoubleArray(9) { parts[3 + it].toDouble() }
            )
        }
        return Golden(rows, durations)
    }

    private val compiledPrograms = HashMap<String, SceneProgram?>()

    private fun compile(program: String): SceneProgram? = compiledPrograms.getOrPut(program) {
        val source = galleryDir.resolve("${program}_rectsl_.rectsl")
        if (!source.exists()) null else RectAnimationOracle.compile(source.readText()).programOrNull
    }

    /**
     * [SceneProgram.sample]'s composition, transcribed so a channel can be evaluated by something
     * other than [Track.sample], and so an individual channel can *decline* to answer.
     *
     * Keyed by rect id and time, which is how the golden addresses a value; the nine entries are in
     * [channelNames] order and a null one means "no differentiable form for this channel". Held
     * honest by [theSceneWalkerIsAFaithfulTranscription], which runs it with [Track.sample] — where
     * nothing declines — and requires bit-equality with the golden.
     */
    private fun walkScene(
        program: SceneProgram,
        channel: (Track<Double>, Double) -> Double?
    ): Map<Pair<String, Double>, Array<Double?>> {
        val nodeMap = program.nodes.associateBy { it.id }
        val childIds = program.nodes.filterIsInstance<GroupNode>().flatMap { it.children }.toSet()
        val roots = program.nodes.filter { it.id !in childIds }
        val out = HashMap<Pair<String, Double>, Array<Double?>>()

        /**
         * A group's own transform, all six channels or nothing.
         *
         * A group is the one place granularity has to be given up: `then` rotates and scales its
         * child's translation, so a declined group rotation genuinely makes every descendant
         * channel unknown.
         */
        fun groupTransform(node: GroupNode, t: Double): Transform2d? {
            val tx = channel(node.x, t) ?: return null
            val ty = channel(node.y, t) ?: return null
            val r = channel(node.rotation, t) ?: return null
            val sx = channel(node.scaleX, t) ?: return null
            val sy = channel(node.scaleY, t) ?: return null
            val a = channel(node.alpha, t) ?: return null
            return Transform2d(Vec2d(tx, ty), r, Vec2d(sx, sy), a)
        }

        fun visit(node: Node, t: Double, parent: Transform2d) {
            when (node) {
                is RectNode -> {
                    // Each output channel is composed from exactly the parts it needs, using
                    // `then` itself for the position so the arithmetic is not re-derived: a
                    // neutral child transform carrying only the translation composes to the same
                    // world position, bit for bit, as the full one would.
                    val lx = channel(node.x, t)
                    val ly = channel(node.y, t)
                    val position = if (lx == null || ly == null) {
                        null
                    } else {
                        (parent then Transform2d(Vec2d(lx, ly), 0.0, Vec2d(1.0, 1.0), 1.0)).translation
                    }
                    val rotation = channel(node.rotation, t)?.let { parent.rotation + it }
                    val scaleX = channel(node.scaleX, t)?.let { parent.scale.x * it }
                    val scaleY = channel(node.scaleY, t)?.let { parent.scale.y * it }
                    val alpha = channel(node.alpha, t)?.let { (parent.alpha * it).coerceIn(0.0, 1.0) }
                    out[node.id to t] = arrayOf(
                        position?.x, position?.y,
                        channel(node.w, t), channel(node.h, t),
                        rotation,
                        scaleX, scaleY,
                        alpha,
                        channel(node.z, t)
                    )
                }
                is GroupNode -> {
                    val local = groupTransform(node, t) ?: return
                    val world = parent then local
                    node.children.mapNotNull { nodeMap[it] }.forEach { visit(it, t, world) }
                }
            }
        }

        val span = (if (program.duration > 0.0) program.duration else 1.0) * 1.25
        for (i in 0..40) {
            val t = i * span / 40.0
            roots.forEach { visit(it, t, Transform2d.IDENTITY) }
        }
        return out
    }

    /** Every distinct scalar track a program's nodes feed the sampler, group transforms included. */
    private fun scalarTracksOf(program: SceneProgram): Set<Track<Double>> {
        val out = LinkedHashSet<Track<Double>>()
        for (node in program.nodes) {
            out += when (node) {
                is RectNode -> listOf(
                    node.x, node.y, node.w, node.h, node.rotation,
                    node.scaleX, node.scaleY, node.alpha, node.z
                )
                is GroupNode -> listOf(
                    node.x, node.y, node.rotation, node.scaleX, node.scaleY, node.alpha
                )
            }
        }
        return out
    }

    private fun repoRoot(): Path {
        var dir = Path.of("").toAbsolutePath()
        while (!dir.resolve("settings.gradle.kts").exists()) {
            dir = dir.parent ?: error("could not locate repo root")
        }
        return dir
    }
}
