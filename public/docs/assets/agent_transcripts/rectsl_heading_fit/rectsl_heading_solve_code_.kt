import afield.rectsl.*
import afield.stdlib.Functions
import kotlin.math.PI

// A dart running right at unit speed while climbing at an unknown rate. Its heading is
// atan2(rise, 1), so asking for 45 degrees is asking for rise = 1 — an answer you can
// check by hand, which is the point of choosing it.
val duration = 4.0
fun clock(): Track<Double> =
    LinearScalarTrack(listOf(TcbKey(0.0, 0.0), TcbKey(duration, duration)))

val rise = FreeParamTrack("rise", 0.15)
val heading = HeadingTrack(clock(), BinaryDoubleTrack(clock(), rise, TrackOp2.MUL))

// A heading reads each of its position tracks twice per probe and widens six times, so one
// named parameter lands in several theta slots. The fitter moves them as one variable.
val graph = heading.toFn(Functions.Domain(0.0, duration)).orThrow()
val slots = freeParameterSlots(graph)

val fit = heading.fitParameters(
    listOf(TrackFitTarget(1.0, PI / 4.0), TrackFitTarget(3.0, PI / 4.0)),
    over = Functions.Domain(0.0, duration),
    iterations = 600
)

val agreement = heading.gradientAgreement(
    (1..8).map { 0.4 * it }, over = Functions.Domain(0.0, duration)
)

"""IR:            ${heading.describe()}
|theta slots:   ${slots.mapValues { it.value.size }}
|free params:   ${heading.freeParameters()}
|solved:        ${fit.parameters}   (pi/4 wants rise = 1)
|residual:      ${fit.initialResidual} -> ${fit.finalResidual}
|heading(2.0):  ${fit.fitted.sample(2.0)}  vs  ${PI / 4.0}
|exact gradient vs central difference: $agreement""".trimMargin()
