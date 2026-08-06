import afield.rectsl.*
import afield.stdlib.Functions
import java.util.Base64

// A clamped identity in time — RectSL's own spelling of "follow the clock".
fun clock(d: Double): Track<Double> =
    LinearScalarTrack(listOf(TcbKey(0.0, 0.0), TcbKey(d, d)))

val duration = 4.0

// amp * trig(rate*t + phase) + centre, with amp / phase / centre all unknown.
fun channel(axis: String, rate: Double, op: TrackOp1): Track<Double> =
    BinaryDoubleTrack(
        BinaryDoubleTrack(
            MappedDoubleTrack(
                BinaryDoubleTrack(
                    BinaryDoubleTrack(clock(duration), constantTrack(rate), TrackOp2.MUL),
                    FreeParamTrack("$axis-phase", 0.0),
                    TrackOp2.ADD
                ),
                op
            ),
            FreeParamTrack("$axis-amp", 1.0),
            TrackOp2.MUL
        ),
        FreeParamTrack("$axis-centre", 0.0),
        TrackOp2.ADD
    )

val seedX = channel("x", 1.1, TrackOp1.COS)
val seedY = channel("y", 1.1, TrackOp1.SIN)

// Where the rect must be, and when.
val waypoints = listOf(
    RectSLFitFigure.Marker(0.4, -6.5, 2.6),
    RectSLFitFigure.Marker(1.8, 0.5, -2.4),
    RectSLFitFigure.Marker(3.4, 6.2, 1.8)
)
val window = Functions.Domain(0.0, duration)
val fitX = seedX.fitParameters(waypoints.map { TrackFitTarget(it.time, it.x) }, window, iterations = 1500)
val fitY = seedY.fitParameters(waypoints.map { TrackFitTarget(it.time, it.y) }, window, iterations = 1500)

fun miss(x: Track<Double>, y: Track<Double>): Double =
    waypoints.maxOf { kotlin.math.hypot(x.sample(it.time) - it.x, y.sample(it.time) - it.y) }
val seedMiss = miss(seedX, seedY)
val fittedMiss = miss(fitX.fitted, fitY.fitted)

val png = RectSLFitFigure.render(
    bands = listOf(
        RectSLFitFigure.Band(
            "target", constantTrack(0.0), constantTrack(0.0),
            afield.Color4(0.86, 0.88, 0.91), note = "3 waypoints", strobe = false
        ),
        RectSLFitFigure.Band(
            "seed", seedX, seedY, afield.Color4(1.0, 0.42, 0.28),
            note = "worst miss %.2fm".format(seedMiss)
        ),
        RectSLFitFigure.Band(
            "fitted", fitX.fitted, fitY.fitted, afield.Color4(0.29, 0.83, 0.7),
            note = "worst miss %.1em".format(fittedMiss)
        )
    ),
    markers = waypoints,
    wall = WallSpec(18.0, 8.0, UnitKind.Meters),
    rectSize = Vec2d(1.5, 1.5),
    times = (0..32).map { it * duration / 32.0 }
)
display(
    "figure",
    "<img src='data:image/png;base64,${Base64.getEncoder().encodeToString(png)}' " +
        "style='width:100%;border-radius:5px;display:block'/>"
)

"""x: ${fitX.initialParameters} -> ${fitX.parameters}
|   residual ${fitX.initialResidual} -> ${fitX.finalResidual}
|y: ${fitY.initialParameters} -> ${fitY.parameters}
|   residual ${fitY.initialResidual} -> ${fitY.finalResidual}
|worst waypoint miss: seed $seedMiss m -> fitted $fittedMiss m""".trimMargin()
