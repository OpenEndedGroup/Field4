import afield.rectsl.*

// `free-param` is the RectSL word for a number you would rather have solved for. Every
// backend still reads it as the constant it presently is, so the scene plays as authored.
val program = RectAnimationOracle.compile(
    """
    #rectsl
    ->wall 18 8 meters
    ->duration 4
    ->fps 30

    linear-track "sweep" '( 0=0   4=4 )

    "marker" rect
      0 0 ->xy
      1.4 1.4 ->wh
      "sweep" track-ref 1.0 free-param gain * 0.0 free-param bias + ->x
      0x44d4b4 solid ->fill
    ;
    """.trimIndent()
).programOrNull!!

val x = program.nodes.filterIsInstance<RectNode>().single().x

// The IR prints as its own algebra now, not as nested constructors.
val before = x.describe()

// Two waypoints, two unknowns: solve exactly.
val fit = x.fitParameters(
    listOf(TrackFitTarget(1.0, -6.0), TrackFitTarget(3.0, 6.0)),
    iterations = 1200
)

"""IR before:  $before
|free params: ${x.freeParameters()}
|solved:      ${fit.parameters}
|IR after:    ${fit.fitted.describe()}
|residual:    ${fit.initialResidual} -> ${fit.finalResidual}
|x(1.0) = ${fit.fitted.sample(1.0)}   x(3.0) = ${fit.fitted.sample(3.0)}""".trimMargin()
