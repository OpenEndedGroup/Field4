# Field Document: rectsl_inverse_animation

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. RectSL inverse animation

<!-- field-box id="rectsl_inverse_fit" index="0" -->

```edn [field-box id="rectsl_inverse_fit" role="properties"]
{"id" "rectsl_inverse_fit" "index" 0 "name" "RectSL inverse animation" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "RectSL inverse animation" "_tombstone" false "frame" #afield/rect [80.0 60.0 360.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rectsl_inverse_fit_code_.kt" "lines" 80 "characters" 2662 "sha256" "2ef9150279c6b15729fbf1d9784f55f95afc28f5310867702cb0d7cc13cfa4ac"}]}
```

```kotlin [field-property box="rectsl_inverse_fit" name="code" path="rectsl_inverse_fit_code_.kt"]
import afield.lang.rectsl.*

val duration = 4.0

// A clamped identity in time: RectSL's way of saying "follow the clock".
fun clock(d: Double): Track<Double> = LinearScalarTrack([TcbKey(0.0, 0.0), TcbKey(d, d)])

fun channel(axis: String, rate: Double, op: TrackOp1): Track<Double> = BinaryDoubleTrack(
    BinaryDoubleTrack(
        MappedDoubleTrack(
            BinaryDoubleTrack(
                BinaryDoubleTrack(clock(duration), constantTrack(rate), TrackOp2.MUL),
                FreeParamTrack("$axis-phase", 0.0),
                TrackOp2.ADD,
            ),
            op,
        ),
        FreeParamTrack("$axis-amp", 1.0),
        TrackOp2.MUL,
    ),
    FreeParamTrack("$axis-centre", 0.0),
    TrackOp2.ADD,
)

val seedX = channel("x", 1.1, TrackOp1.COS)
val seedY = channel("y", 1.1, TrackOp1.SIN)

// Where the rect must be, and when.
val waypoints = [
    RectSLFitFigure.Marker(0.4, -6.5, 2.6),
    RectSLFitFigure.Marker(1.8, 0.5, -2.4),
    RectSLFitFigure.Marker(3.4, 6.2, 1.8),
]
val window = Functions.Domain(0.0, duration)
val fitX = seedX.fitParameters(waypoints.map { TrackFitTarget(it.time, it.x) }, window, iterations = 1500)
val fitY = seedY.fitParameters(waypoints.map { TrackFitTarget(it.time, it.y) }, window, iterations = 1500)

fun miss(x: Track<Double>, y: Track<Double>): Double = waypoints.maxOf {
    hypot(x.sample(it.time) - it.x, y.sample(it.time) - it.y)
}

display(
    "figure",
    RectSLFitFigure.render(
        bands = [
            RectSLFitFigure.Band(
                "target",
                constantTrack(0.0),
                constantTrack(0.0),
                Color4(0.86, 0.88, 0.91),
                note = "3 waypoints",
                strobe = false,
            ),
            RectSLFitFigure.Band(
                "seed",
                seedX,
                seedY,
                Color4(1.0, 0.42, 0.28),
                note = "worst miss %.2fm".format(miss(seedX, seedY)),
            ),
            RectSLFitFigure.Band(
                "fitted",
                fitX.fitted,
                fitY.fitted,
                Color4(0.29, 0.83, 0.7),
                note = "worst miss %.1em".format(miss(fitX.fitted, fitY.fitted)),
            ),
        ],
        markers = waypoints,
        wall = WallSpec(18.0, 8.0, UnitKind.Meters),
        rectSize = Vec2d(1.5, 1.5),
        times = (0..32).map { it * duration / 32.0 },
    ),
)

"""x: ${fitX.initialParameters} -> ${fitX.parameters}
|   residual ${fitX.initialResidual} -> ${fitX.finalResidual}
|y: ${fitY.initialParameters} -> ${fitY.parameters}
|   residual ${fitY.initialResidual} -> ${fitY.finalResidual}"""
    .trimMargin()
```

## 2. the free-param word

<!-- field-box id="rectsl_free_param_word" index="1" -->

```edn [field-box id="rectsl_free_param_word" role="properties"]
{"id" "rectsl_free_param_word" "index" 1 "name" "the free-param word" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "the free-param word" "_tombstone" false "frame" #afield/rect [80.0 960.0 360.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rectsl_free_param_word_code_.kt" "lines" 75 "characters" 2305 "sha256" "d089c38d6619e39e3f029c8194fb6b57cf7686dddd17dc0a3cb54200157bf0e0"}]}
```

```kotlin [field-property box="rectsl_free_param_word" name="code" path="rectsl_free_param_word_code_.kt"]
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
```

