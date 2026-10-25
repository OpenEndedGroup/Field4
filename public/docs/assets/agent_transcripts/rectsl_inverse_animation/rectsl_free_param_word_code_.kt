import afield.lang.rectsl.*

// `free-param` marks a number to be solved for. Until it is, every backend reads it as the
// constant it is now, so the scene plays as written.
val program =
    RectAnimationOracle.compile(
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
            """
                .trimIndent(),
        )
        .program

val node = program.nodes.filterIsInstance<RectNode>().single()
val x = node.x

// Two waypoints and two unknowns. The sweep follows the clock, so -4 at t=1 and +4 at t=3 means
// gain = 4 and bias = -8.
val targets = [TrackFitTarget(1.0, -4.0), TrackFitTarget(3.0, 4.0)]
val fit = x.fitParameters(targets, Functions.Domain(0.0, 4.0), iterations = 2000)

fun missOf(track: Track<Double>): Double = targets.maxOf { abs(track.sample(it.time) - it.value) }

display(
    "before / after",
    RectSLFitFigure.render(
        bands = [
            RectSLFitFigure.Band(
                "authored",
                x,
                node.y,
                Color4(1.0, 0.42, 0.28),
                note = "${x.freeParameters()} — worst miss %.2fm".format(missOf(x)),
            ),
            RectSLFitFigure.Band(
                "solved",
                fit.fitted,
                node.y,
                Color4(0.29, 0.83, 0.7),
                note = "${fit.parameters} — worst miss %.1em".format(missOf(fit.fitted)),
            ),
        ],
        markers = targets.map { RectSLFitFigure.Marker(it.time, it.value, 0.0) },
        wall = WallSpec(18.0, 8.0, UnitKind.Meters),
        rectSize = Vec2d(1.4, 1.4),
        times = (0..32).map { it * 4.0 / 32.0 },
    ),
)

markdown(
    "fit",
    """
    ~~~
    IR before:   ${x.describe()}
    free params: ${x.freeParameters()}
    solved:      ${fit.parameters}
    IR after:    ${fit.fitted.describe()}
    residual:    ${fit.initialResidual} -> ${fit.finalResidual}
    x(1.0) = ${fit.fitted.sample(1.0)}   x(3.0) = ${fit.fitted.sample(3.0)}
    ~~~
"""
        .trimIndent(),
)
