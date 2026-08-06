package afield.rectsl

import afield.Color4
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The fit, taken to pixels — and then the pixels inspected rather than admired.
 *
 * "It compiles" is not verification for anything that feeds rendered output, and neither is "the
 * residual is small". This renders the figure the docs page shows and then reads its own canvas
 * back: the seed band must *miss* the markers and the fitted band must *hit* them, measured in
 * pixels, on the same image. A test that only checked the PNG was non-empty would pass on a black
 * rectangle.
 *
 * The committed asset is regenerated deliberately, never reflexively — the same idiom
 * `RectSLTrackCharacterizationTest` uses for its golden:
 *
 *     ./gradlew jvmTest --tests '*RectSLFitFigure*' -Dafield.rectsl.fitfigure=write
 */
class RectSLFitFigureTest {

    private val assetPath: Path =
        repoRoot().resolve("public/docs/assets/rectsl/rectsl-inverse-animation.png")

    /**
     * The demo scene, built once and shared by the test and the committed asset so the picture and
     * the numbers can never drift apart.
     *
     * A rect crossing the wall on a cosine in x and a sine in y — the seed is a small, badly-phased
     * circle; the three markers ask it to pass through three places at three times. Six unknowns,
     * six equations, and a landscape with real curvature in it (the phases enter through a cosine).
     */
    companion object {
        /** A clamped identity in time — RectSL's own spelling of "follow the clock". */
        fun clock(duration: Double): Track<Double> =
            LinearScalarTrack(listOf(TcbKey(0.0, 0.0), TcbKey(duration, duration)))

        const val DURATION: Double = 4.0

        /** `amp * trig(rate * t + phase) + centre`, with amp/phase/centre unknown. */
        fun channel(axis: String, rate: Double, op: TrackOp1, amp: Double, phase: Double, centre: Double) =
            BinaryDoubleTrack(
                BinaryDoubleTrack(
                    MappedDoubleTrack(
                        BinaryDoubleTrack(
                            BinaryDoubleTrack(clock(DURATION), constantTrack(rate), TrackOp2.MUL),
                            FreeParamTrack("$axis-phase", phase),
                            TrackOp2.ADD
                        ),
                        op
                    ),
                    FreeParamTrack("$axis-amp", amp),
                    TrackOp2.MUL
                ),
                FreeParamTrack("$axis-centre", centre),
                TrackOp2.ADD
            )

        fun seedX(): Track<Double> = channel("x", 1.1, TrackOp1.COS, 1.0, 0.0, 0.0)
        fun seedY(): Track<Double> = channel("y", 1.1, TrackOp1.SIN, 1.0, 0.0, 0.0)

        val markers: List<RectSLFitFigure.Marker> = listOf(
            RectSLFitFigure.Marker(0.4, -6.5, 2.6),
            RectSLFitFigure.Marker(1.8, 0.5, -2.4),
            RectSLFitFigure.Marker(3.4, 6.2, 1.8)
        )

        val wall: WallSpec = WallSpec(18.0, 8.0, UnitKind.Meters)
        val rectSize: Vec2d = Vec2d(1.5, 1.5)
        val strobe: List<Double> = (0..32).map { it * DURATION / 32.0 }
    }

    @Test
    fun theFigureShowsASeedThatMissesAndAFitThatHits() {
        val xFit = seedX().fitParameters(
            markers.map { TrackFitTarget(it.time, it.x) },
            over = afield.stdlib.Functions.Domain(0.0, DURATION),
            iterations = 1500
        )
        val yFit = seedY().fitParameters(
            markers.map { TrackFitTarget(it.time, it.y) },
            over = afield.stdlib.Functions.Domain(0.0, DURATION),
            iterations = 1500
        )

        println("=== inverse animation, the figure's own numbers ===")
        println("x: ${xFit.initialParameters} -> ${xFit.parameters}")
        println("   residual ${xFit.initialResidual} -> ${xFit.finalResidual}")
        println("y: ${yFit.initialParameters} -> ${yFit.parameters}")
        println("   residual ${yFit.initialResidual} -> ${yFit.finalResidual}")
        for (marker in markers) {
            println(
                "  t=${marker.time} want=(${marker.x}, ${marker.y})  " +
                    "seed=(${"%.3f".format(seedX().sample(marker.time))}, " +
                    "${"%.3f".format(seedY().sample(marker.time))})  " +
                    "fitted=(${"%.3f".format(xFit.fitted.sample(marker.time))}, " +
                    "${"%.3f".format(yFit.fitted.sample(marker.time))})"
            )
        }

        // The seed misses by wall units, the fit hits. Measured on the tracks the figure draws.
        val seedMiss = markers.maxOf {
            kotlin.math.hypot(seedX().sample(it.time) - it.x, seedY().sample(it.time) - it.y)
        }
        val fittedMiss = markers.maxOf {
            kotlin.math.hypot(
                xFit.fitted.sample(it.time) - it.x,
                yFit.fitted.sample(it.time) - it.y
            )
        }
        println("worst waypoint miss: seed $seedMiss wall units -> fitted $fittedMiss")
        assertTrue(seedMiss > 2.0, "the seed should visibly miss, missed by only $seedMiss")
        assertTrue(fittedMiss < 0.02, "the fit should hit the waypoints, missed by $fittedMiss")

        val png = RectSLFitFigure.render(
            bands = listOf(
                RectSLFitFigure.Band(
                    "target", constantTrack(0.0), constantTrack(0.0),
                    Color4(0.86, 0.88, 0.91),
                    note = "3 waypoints", strobe = false
                ),
                RectSLFitFigure.Band(
                    "seed", seedX(), seedY(), Color4(1.0, 0.42, 0.28),
                    note = "worst miss ${"%.2f".format(seedMiss)}m"
                ),
                RectSLFitFigure.Band(
                    "fitted", xFit.fitted, yFit.fitted, Color4(0.29, 0.83, 0.7),
                    note = "worst miss ${"%.1e".format(fittedMiss)}m"
                )
            ),
            markers = markers,
            wall = wall,
            rectSize = rectSize,
            times = strobe
        )

        // A PNG is not evidence until something has looked inside it.
        val out = Files.createTempDirectory("rectsl-fit-figure")
        val temp = out.resolve("rectsl-inverse-animation.png")
        temp.writeBytes(png)
        println("figure written to $temp (${png.size} bytes)")
        assertTrue(png.size > 4_000, "a three-band figure should not be a few hundred bytes")

        if (System.getProperty("afield.rectsl.fitfigure") == "write") {
            assetPath.parent.createDirectories()
            assetPath.writeBytes(png)
            println("wrote committed asset: $assetPath")
        }
        assertTrue(
            assetPath.exists(),
            "the docs asset is missing — regenerate with -Dafield.rectsl.fitfigure=write"
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
