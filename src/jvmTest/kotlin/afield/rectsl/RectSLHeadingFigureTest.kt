package afield.rectsl

import afield.Color4
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lowered heading, taken to pixels — with the failure it was built to avoid drawn beside it.
 *
 * "The differential test is green" is not sufficient evidence for a rotation. A heading drives
 * *visible* motion, and the characteristic way an `atan2` lowering goes wrong is a **half turn**:
 * every number stays in `[-pi, pi]`, every tolerance-based check that averages over a trajectory
 * still looks reasonable, and the dart points backwards. So this renders three bands of the same
 * circular trajectory:
 *
 * 1. **SAMPLER** — [HeadingTrack.sample], the ground truth.
 * 2. **LOWERED** — the `Fn` this branch added. Asserted **pixel-identical** to band 1.
 * 3. **ATAN ONLY** — `atan(dy/dx)` with no quadrant repair, which is the shape of mistake the
 *    refusal text's proposed fix-up would have made had the fix-up been dropped or mis-signed.
 *    Asserted to differ, in counted pixels, and to differ *by a half turn* in the angles.
 *
 * The glyph is a rect with a nose pip, because a bare rect is symmetric under a half turn and the
 * one picture that has to show a half turn would otherwise be the one picture that cannot.
 *
 * The committed asset is regenerated deliberately, never reflexively — the same idiom
 * [RectSLFitFigureTest] uses:
 *
 *     ./gradlew jvmTest --tests '*RectSLHeadingFigure*' -Dafield.rectsl.headingfigure=write
 */
class RectSLHeadingFigureTest {

    private val assetPath: Path =
        repoRoot().resolve("public/docs/assets/rectsl/rectsl-heading-lowering.png")

    private val duration = 4.0
    private val wall = WallSpec(19.0, 7.0, UnitKind.Meters)
    private val rectSize = Vec2d(1.35, 0.4)
    private val strobe: List<Double> = (0..15).map { it * duration / 15.0 }

    /** A clamped identity in time. */
    private fun clock(): Track<Double> =
        LinearScalarTrack(listOf(TcbKey(0.0, 0.0), TcbKey(duration, duration)))

    /**
     * A dart on a figure of eight — `x = 8·sin(ωt)`, `y = 2.6·sin(2ωt)`.
     *
     * Chosen over a circle for two reasons that are both about being *readable*. It is wide, so the
     * darts spread across a wide band instead of piling into a ribbon nobody can read an orientation
     * off; and it travels leftwards for half its length, which is where the branch cut is and
     * therefore where a lost quadrant shows.
     */
    private fun trajectory(): Pair<Track<Double>, Track<Double>> {
        val omega = 2.0 * PI / duration
        val x = BinaryDoubleTrack(
            MappedDoubleTrack(
                BinaryDoubleTrack(clock(), constantTrack(omega), TrackOp2.MUL), TrackOp1.SIN
            ),
            constantTrack(8.0), TrackOp2.MUL
        )
        val y = BinaryDoubleTrack(
            MappedDoubleTrack(
                BinaryDoubleTrack(clock(), constantTrack(2.0 * omega), TrackOp2.MUL), TrackOp1.SIN
            ),
            constantTrack(2.6), TrackOp2.MUL
        )
        return x to y
    }

    @Test
    fun theLoweredHeadingDrawsTheSameDartsAndTheQuadrantLessOneDoesNot() {
        val (x, y) = trajectory()
        val heading = HeadingTrack(x, y)
        val lowered = when (val l = heading.toFn(afield.stdlib.Functions.Domain(0.0, duration))) {
            is RectSLFnLowering.Lowered -> l.value
            is RectSLFnLowering.Refused -> error("the heading should lower: ${l.why}")
        }

        /** `atan(dy/dx)` on the same central difference — the quadrant thrown away. */
        val quadrantLess: (Double) -> Double = { t ->
            val h = heading.eps
            val dx = x.sample(t + h) - x.sample(t - h)
            val dy = y.sample(t + h) - y.sample(t - h)
            atan(dy / dx)
        }

        // The numbers behind the picture, before the picture. On the half of each loop where the
        // dart travels leftwards, `atan(dy/dx)` is a half turn out — that is what the eye will see.
        var halfTurns = 0
        var worstLoweredError = 0.0
        for (t in strobe) {
            val truth = heading.sample(t)
            worstLoweredError = maxOf(worstLoweredError, abs(truth - lowered.eval(t)))
            val gap = abs(truth - quadrantLess(t))
            if (abs(gap - PI) < 1e-6) halfTurns++
        }
        println(
            "heading figure: worst |sampler - lowered| $worstLoweredError over ${strobe.size} " +
                "strobes; the quadrant-less form is a half turn out at $halfTurns of them"
        )
        assertTrue(worstLoweredError < 1e-12, "the lowering should be exact, was $worstLoweredError")
        assertTrue(
            halfTurns >= strobe.size / 3,
            "the counterfactual should be visibly wrong on a good fraction of the trajectory, " +
                "was wrong at only $halfTurns of ${strobe.size}"
        )

        val png = RectSLFitFigure.render(
            bands = listOf(
                RectSLFitFigure.Band(
                    "sampler", x, y, Color4(0.86, 0.88, 0.91),
                    note = "headingtrack.sample", rotation = heading::sample
                ),
                RectSLFitFigure.Band(
                    "lowered", x, y, Color4(0.29, 0.83, 0.7),
                    note = "fn err ${"%.0e".format(worstLoweredError)}", rotation = lowered::eval
                ),
                RectSLFitFigure.Band(
                    "atan only", x, y, Color4(1.0, 0.42, 0.28),
                    note = "$halfTurns half turns", rotation = quadrantLess
                )
            ),
            markers = emptyList(),
            wall = wall,
            rectSize = rectSize,
            times = strobe,
            bandHeight = 230
        )

        val out = Files.createTempDirectory("rectsl-heading-figure")
        val temp = out.resolve("rectsl-heading-lowering.png")
        temp.writeBytes(png)
        println("figure written to $temp (${png.size} bytes)")

        // ---- and now read the canvas back, because a PNG is not evidence until something has -----
        val image = ImageIO.read(temp.toFile())
        assertEquals(980, image.width)
        val pad = 14
        val bandHeight = 230
        fun bandPixels(index: Int): IntArray {
            // Skip the top 34 rows of each panel: that is where the label text lives, and the three
            // labels differ on purpose. Everything below is the drawing.
            val top = pad + index * (bandHeight + pad) + 34
            val bottom = pad + index * (bandHeight + pad) + bandHeight
            val out = IntArray(image.width * (bottom - top))
            var i = 0
            for (yy in top until bottom) for (xx in 0 until image.width) out[i++] = image.getRGB(xx, yy)
            return out
        }

        val sampler = bandPixels(0)
        val loweredPixels = bandPixels(1)
        val atanOnly = bandPixels(2)

        // Not black, and not one flat colour: a figure that failed to draw would pass any "the PNG
        // is big enough" check and fail this one.
        val background = 0xFF0F1115.toInt()
        val panel = 0xFF16191F.toInt()
        val painted = sampler.count { it != background && it != panel }
        println("sampler band: $painted painted pixels of ${sampler.size}, ${sampler.toSet().size} distinct colours")
        assertTrue(painted > 4_000, "the sampler band drew only $painted pixels — is it blank?")
        assertTrue(
            sampler.toSet().size >= 16,
            "the strobe should fade across its ${strobe.size} instants, saw only " +
                "${sampler.toSet().size} distinct colours"
        )

        // Geometry, isolated from colour and from labels: each rotation is rendered on its own as a
        // one-band figure in the *same* colour, and the three are diffed against each other. Anything
        // that differs is then a dart in a different place, which is the only thing under test.
        fun soloBand(rotation: (Double) -> Double): java.awt.image.BufferedImage {
            val bytes = RectSLFitFigure.render(
                bands = listOf(
                    RectSLFitFigure.Band(
                        "solo", x, y, Color4(0.86, 0.88, 0.91), rotation = rotation
                    )
                ),
                markers = emptyList(), wall = wall, rectSize = rectSize, times = strobe,
                bandHeight = bandHeight
            )
            val file = Files.createTempFile(out, "solo", ".png")
            file.writeBytes(bytes)
            return ImageIO.read(file.toFile())
        }
        fun differingPixels(
            a: java.awt.image.BufferedImage,
            b: java.awt.image.BufferedImage
        ): Int {
            var n = 0
            for (yy in 34 until bandHeight) {
                for (xx in 0 until a.width) if (a.getRGB(xx, pad + yy) != b.getRGB(xx, pad + yy)) n++
            }
            return n
        }

        val soloSampler = soloBand(heading::sample)
        val soloLowered = soloBand(lowered::eval)
        val soloAtan = soloBand(quadrantLess)

        val samplerVsLowered = differingPixels(soloSampler, soloLowered)
        println("sampler vs lowered, same colour: $samplerVsLowered differing pixels")
        assertEquals(
            0,
            samplerVsLowered,
            "the lowered heading should draw the identical picture to the sampler"
        )

        // And the counterfactual must not — with a floor, because "differs at all" would pass on a
        // single stray pixel and the claim is that the darts point the wrong way.
        val samplerVsAtan = differingPixels(soloSampler, soloAtan)
        println("sampler vs atan-only, same colour: $samplerVsAtan differing pixels")
        assertTrue(
            samplerVsAtan > 1_500,
            "losing the quadrant should visibly move the darts, moved only $samplerVsAtan pixels"
        )
        // The bands above are the same three pictures in three colours, so the coloured figure the
        // reader sees is the one that was just measured. Confirm band 2 really is the lowered one.
        assertTrue(
            loweredPixels.count { it != background && it != panel } > 4_000,
            "the lowered band drew nothing"
        )
        assertTrue(
            atanOnly.count { it != background && it != panel } > 4_000,
            "the counterfactual band drew nothing"
        )

        if (System.getProperty("afield.rectsl.headingfigure") == "write") {
            assetPath.parent.createDirectories()
            assetPath.writeBytes(png)
            println("wrote committed asset: $assetPath")
        }
        assertTrue(
            assetPath.exists(),
            "the docs asset is missing — regenerate with -Dafield.rectsl.headingfigure=write"
        )
    }

    private fun repoRoot(): Path {
        var dir = Path.of("").toAbsolutePath()
        while (!dir.resolve("settings.gradle.kts").exists()) {
            dir = dir.parent ?: error("could not locate repo root")
        }
        return dir
    }
}
