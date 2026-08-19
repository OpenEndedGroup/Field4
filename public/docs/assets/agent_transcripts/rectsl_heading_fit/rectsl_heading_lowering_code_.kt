import afield.rectsl.*
import afield.stdlib.Functions
import afield.stdlib.extractParameters
import java.util.Base64
import kotlin.math.PI
import kotlin.math.atan

val duration = 4.0
fun clock(): Track<Double> =
    LinearScalarTrack(listOf(TcbKey(0.0, 0.0), TcbKey(duration, duration)))
fun wave(rate: Double, amplitude: Double): Track<Double> =
    BinaryDoubleTrack(
        MappedDoubleTrack(
            BinaryDoubleTrack(clock(), constantTrack(rate), TrackOp2.MUL), TrackOp1.SIN
        ),
        constantTrack(amplitude), TrackOp2.MUL
    )

// A figure of eight: wide, and travelling leftwards for half its length, which is where
// atan2's branch cut is and therefore where a lost quadrant shows.
val omega = 2.0 * PI / duration
val x = wave(omega, 8.0)
val y = wave(2.0 * omega, 2.6)

// The rotation, three ways.
val heading = HeadingTrack(x, y)
val lowered = heading.toFn(Functions.Domain(0.0, duration)).orThrow()
val quadrantLess: (Double) -> Double = { t ->
    val h = heading.eps
    atan((y.sample(t + h) - y.sample(t - h)) / (x.sample(t + h) - x.sample(t - h)))
}

val strobe = (0..15).map { it * duration / 15.0 }
val worst = strobe.maxOf { kotlin.math.abs(heading.sample(it) - lowered.eval(it)) }
val halfTurns = strobe.count {
    kotlin.math.abs(kotlin.math.abs(heading.sample(it) - quadrantLess(it)) - PI) < 1e-6
}

val png = RectSLFitFigure.render(
    bands = listOf(
        RectSLFitFigure.Band(
            "sampler", x, y, afield.Color4(0.86, 0.88, 0.91),
            note = "headingtrack.sample", rotation = heading::sample
        ),
        RectSLFitFigure.Band(
            "lowered", x, y, afield.Color4(0.29, 0.83, 0.7),
            note = "fn err %.0e".format(worst), rotation = lowered::eval
        ),
        RectSLFitFigure.Band(
            "atan only", x, y, afield.Color4(1.0, 0.42, 0.28),
            note = "$halfTurns half turns", rotation = quadrantLess
        )
    ),
    markers = emptyList(),
    wall = WallSpec(19.0, 7.0, UnitKind.Meters),
    rectSize = Vec2d(1.35, 0.4),
    times = strobe,
    bandHeight = 230
)
display(
    "figure",
    "<img src='data:image/png;base64,${Base64.getEncoder().encodeToString(png)}' " +
        "style='width:100%;border-radius:5px;display:block'/>"
)

"""worst |sampler - lowered| over ${strobe.size} strobes: $worst
|the quadrant-less form is a half turn out at $halfTurns of them
|lowered graph: ${lowered.extractParameters().size} numeric leaves""".trimMargin()
