# Field Document: rectsl_heading_fit

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. solve for where it points

<!-- field-box id="rectsl_heading_solve" index="0" -->

```edn [field-box id="rectsl_heading_solve" role="properties"]
{"id" "rectsl_heading_solve" "index" 0 "name" "solve for where it points" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "solve for where it points" "_tombstone" false "frame" #afield/rect [80.0 900.0 380.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rectsl_heading_solve_code_.kt" "lines" 36 "characters" 1514 "sha256" "55e3a2f64e7505156ade465481dc65a8c99a5f8526e4f8c86d55e375237242ff"}]}
```

```kotlin [field-property box="rectsl_heading_solve" name="code" path="rectsl_heading_solve_code_.kt"]
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
```

## 2. a heading, and the half turn it avoids

<!-- field-box id="rectsl_heading_lowering" index="1" -->

```edn [field-box id="rectsl_heading_lowering" role="properties"]
{"id" "rectsl_heading_lowering" "index" 1 "name" "a heading, and the half turn it avoids" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "a heading, and the half turn it avoids" "_tombstone" false "frame" #afield/rect [80.0 60.0 380.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rectsl_heading_lowering_code_.kt" "lines" 68 "characters" 2459 "sha256" "93420a63b42fce305570e4f6a7b3ae929a159792d40e52071664aabbedcbe5e5"}]}
```

```kotlin [field-property box="rectsl_heading_lowering" name="code" path="rectsl_heading_lowering_code_.kt"]
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
```

