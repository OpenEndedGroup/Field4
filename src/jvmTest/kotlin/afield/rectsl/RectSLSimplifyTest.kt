package afield.rectsl

import afield.stdlib.extractParameters
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A simplifier is exactly the kind of code where a plausible rule is wrong in one corner, so this
 * one is checked against the whole gallery rather than against examples.
 *
 * Every scalar channel of all 34 programs is rewritten and required to sample **bit-identically** to
 * the original at 41 times each — the same corpus and the same instants the differentiable backend's
 * differential test uses, reused because it was the expensive thing to assemble and is now free to
 * point at anything.
 *
 * The node-count reduction is reported rather than asserted at any particular figure: it is a
 * property of what the gallery happens to contain, and pinning it would make the test fail whenever
 * someone writes a new example.
 */
class RectSLSimplifyTest {

    private val galleryDir: Path = repoRoot().resolve("public/docs/assets/rectsl_gallery")

    @Test
    fun simplifyingPreservesEveryGallerySampleBitForBit() {
        var before = 0
        var after = 0
        var channels = 0
        var comparisons = 0
        var programs = 0

        for ((name, source) in gallerySources()) {
            val program = RectAnimationOracle.compile(source).programOrNull ?: continue
            programs++
            val span = (if (program.duration > 0.0) program.duration else 1.0) * 1.25
            val times = (0..40).map { it * span / 40.0 }

            for (track in scalarTracksOf(program)) {
                val simplified = track.simplify()
                channels++
                before += track.nodeCount()
                after += simplified.nodeCount()
                for (t in times) {
                    val expected = track.sample(t)
                    val got = simplified.sample(t)
                    assertEquals(
                        expected.toRawBits(),
                        got.toRawBits(),
                        "$name: simplifying changed the sample at t=$t " +
                            "($expected -> $got)\n  before: ${track.describe()}\n  after:  ${simplified.describe()}"
                    )
                    comparisons++
                }
                // Idempotent: a second pass must be a fixed point, or the rewriter is not converging
                // and a caller that loops it would loop forever.
                assertEquals(
                    simplified,
                    simplified.simplify(),
                    "$name: simplify is not a fixed point on its own output"
                )
            }
        }

        println("=== RectSL track simplifier over the gallery ===")
        println("programs:   $programs")
        println("channels:   $channels")
        println("nodes:      $before -> $after (${"%.1f".format(100.0 * (before - after) / before)}% smaller)")
        println("samples compared bit-for-bit: $comparisons")
        assertTrue(programs >= 30, "expected the whole gallery, got $programs")
        assertTrue(comparisons > 100_000, "only $comparisons samples compared")
        assertTrue(after < before, "the simplifier removed nothing at all: $before -> $after")
    }

    @Test
    fun theRulesFireWhereTheyShouldAndNotWhereTheyShouldNot() {
        val ramp = LinearScalarTrack(listOf(TcbKey(0.0, 0.0), TcbKey(2.0, 4.0)))

        // The identities that are bit-exact for every double, both zeros included.
        assertEquals(ramp, BinaryDoubleTrack(ramp, constantTrack(0.0), TrackOp2.SUB).simplify())
        assertEquals(ramp, BinaryDoubleTrack(ramp, constantTrack(1.0), TrackOp2.MUL).simplify())
        assertEquals(ramp, BinaryDoubleTrack(ramp, constantTrack(1.0), TrackOp2.DIV).simplify())
        assertEquals(ramp, TimeWarpTrack(ramp, TimeWarpKind.SHIFT, 0.0).simplify())

        // Structural equality is what makes this possible at all.
        assertEquals(ramp, BinaryDoubleTrack(ramp, ramp, TrackOp2.MIN).simplify())

        // And the reflexive ones that are NOT kept, each with the reason it is not — asserted, so
        // nobody re-adds one in a tidying pass. See the file KDoc's table.
        val plusZero = BinaryDoubleTrack(ramp, constantTrack(0.0), TrackOp2.ADD)
        assertEquals(plusZero, plusZero.simplify(), "`x + 0` turns -0.0 into +0.0")
        val zeroMinus = BinaryDoubleTrack(constantTrack(0.0), ramp, TrackOp2.SUB)
        assertEquals(zeroMinus, zeroMinus.simplify(), "`0 - x` is not `-x` at x = 0")
        val mixAtOne = TernaryDoubleTrack(ramp, ramp, constantTrack(1.0), TrackOp3.MIX)
        assertEquals(mixAtOne, mixAtOne.simplify(), "`a + (b - a)` is not `b`")
        // The hazard is not hypothetical: this is why signed zero is observable in this IR.
        assertTrue(
            kotlin.math.atan2(0.0, -1.0) != kotlin.math.atan2(-0.0, -1.0),
            "atan2 distinguishes the zeros, which is what HeadingTrack rides on"
        )

        // Involutions.
        assertEquals(
            ramp,
            MappedDoubleTrack(MappedDoubleTrack(ramp, TrackOp1.NEG), TrackOp1.NEG).simplify()
        )

        // The fold, all the way through an array-shaped tree of constants.
        val arrayish = BinaryDoubleTrack(
            BinaryDoubleTrack(constantTrack(3.0), constantTrack(0.5), TrackOp2.MUL),
            constantTrack(-1.0),
            TrackOp2.ADD
        )
        assertEquals(constantTrack(0.5), arrayish.simplify())

        // A free parameter is a number and is not *a* number: folding it would delete the unknown.
        val withParam = BinaryDoubleTrack(
            FreeParamTrack("gain", 2.0), constantTrack(3.0), TrackOp2.MUL
        )
        assertEquals(withParam, withParam.simplify())
        assertEquals(setOf("gain"), withParam.simplify().freeParameters().keys)

        // `x * 0` is deliberately left alone for a non-constant x — see the file's KDoc.
        val timesZero = BinaryDoubleTrack(ramp, constantTrack(0.0), TrackOp2.MUL)
        assertEquals(timesZero, timesZero.simplify())
    }

    /** A smaller tree should still lower, and lower to the same numbers. */
    @Test
    fun aSimplifiedTrackLowersToTheSameFunction() {
        val messy = BinaryDoubleTrack(
            TimeWarpTrack(
                BinaryDoubleTrack(
                    MappedDoubleTrack(
                        MappedDoubleTrack(
                            LinearScalarTrack(listOf(TcbKey(0.0, 0.0), TcbKey(2.0, 4.0))),
                            TrackOp1.NEG
                        ),
                        TrackOp1.NEG
                    ),
                    constantTrack(1.0),
                    TrackOp2.MUL
                ),
                TimeWarpKind.SHIFT,
                0.0
            ),
            BinaryDoubleTrack(constantTrack(2.0), constantTrack(3.0), TrackOp2.MUL),
            TrackOp2.ADD
        )
        val simple = messy.simplify()
        println("messy ${messy.nodeCount()} nodes: ${messy.describe()}")
        println("simple ${simple.nodeCount()} nodes: ${simple.describe()}")
        assertTrue(simple.nodeCount() < messy.nodeCount())

        val window = afield.stdlib.Functions.Domain(0.0, 2.5)
        val a = messy.toFn(window).orThrow()
        val b = simple.toFn(window).orThrow()
        println("Fn leaves: ${a.extractParameters().size} -> ${b.extractParameters().size}")
        for (i in 0..250) {
            val t = i / 100.0
            assertEquals(a.eval(t), b.eval(t), 1e-15, "at t=$t")
        }
    }

    private fun gallerySources(): List<Pair<String, String>> =
        Files.list(galleryDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".rectsl") }
                .map { it.fileName.toString().removeSuffix(".rectsl").removeSuffix("_rectsl_") to it.readText() }
                .toList()
        }.sortedBy { it.first }

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
