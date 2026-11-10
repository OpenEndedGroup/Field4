# Field Document: noiselang_distributions

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 4 · a distribution of FLines

<!-- field-box id="noise_walks" index="0" -->

```edn [field-box id="noise_walks" role="properties"]
{"id" "noise_walks" "index" 0 "name" "4 · a distribution of FLines" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 · a distribution of FLines" "_tombstone" false "frame" #afield/rect [500.0 180.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "noise_walks_code_.kt" "lines" 33 "characters" 1108 "sha256" "48276aaf40bef0d14a89620d5af66403d1d695d0e371583c4364dd88f8b5d769"}]}
```

```kotlin [field-property box="noise_walks" name="code" path="noise_walks_code_.kt"]
import afield.math.distribution.*

// One graph: heading drifts by an independent normal each step; x and y accumulate.
val steps = 40
var heading: Dist = Dist.dirac(0)
var x: Dist = Dist.dirac(0)
var y: Dist = Dist.dirac(0)
val xs = ArrayList<Dist>()
val ys = ArrayList<Dist>()
for (k in 0 until steps) {
    heading = heading + normal(0, 0.25)
    x = x + heading.cos() * 4
    y = y + heading.sin() * 4
    xs += x
    ys += y
}
val reach = (x * x + y * y).sqrt()

// 120 joint draws of every node: each sample is one whole path.
val walks = 120
val drawn = jointSamples(*(xs + ys).toTypedArray(), n = walks, seed = 7)
val paths =
    (0 until walks).map { w ->
        fline {
            moveTo(200, 150)
            for (k in 0 until steps) lineTo(200 + drawn[k][w], 150 - drawn[steps + k][w])
            color = vec(0.0, 0.67, 1.0, 0.35)
        }
    }
lines(paths, channel = "walks", w = 400.0, h = 300.0)

println("E(reach) = ${E(reach, n = 50_000)}   (a straight line would be ${steps * 4})")
__m("reach").distribution(reach, batch = 20_000, title = "distance from the start after $steps steps")
```

## 2. 1 · spike, die, two dice — launch me

<!-- field-box id="noise_spike_to_triangle" index="1" -->

```edn [field-box id="noise_spike_to_triangle" role="properties"]
{"id" "noise_spike_to_triangle" "index" 1 "name" "1 · spike, die, two dice — launch me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · spike, die, two dice — launch me" "_tombstone" false "frame" #afield/rect [40.0 40.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "noise_spike_to_triangle_code_.kt" "lines" 13 "characters" 365 "sha256" "f175368cb641f43424446b4d4cba3671b7981b95b32c7dfd0308f4255399385a"}]}
```

```kotlin [field-property box="noise_spike_to_triangle" name="code" path="noise_spike_to_triangle_code_.kt"]
import afield.math.distribution.*

val a = Dist.dirac(5)
val d1 = die(6)
val d2 = die(6)
val s = a + d1 + d2

loop {
    __m("spike").distribution(a, batch = 500, title = "A = 5, a Dirac spike")
    __m("die").distribution(d1, batch = 500, title = "D1, a fair die")
    __m("sum").distribution(s, batch = 500, title = "S = A + D1 + D2", exact = 12.0)
    frame()
}
```

## 3. 2 · P, E, Var and quantiles, with standard errors

<!-- field-box id="noise_queries" index="2" -->

```edn [field-box id="noise_queries" role="properties"]
{"id" "noise_queries" "index" 2 "name" "2 · P, E, Var and quantiles, with standard errors" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · P, E, Var and quantiles, with standard errors" "_tombstone" false "frame" #afield/rect [40.0 180.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "noise_queries_code_.kt" "lines" 20 "characters" 754 "sha256" "7823faaeef227f49f83123b9299d6a17767c8aea428e11370c940ac1f08ea10d"}]}
```

```kotlin [field-property box="noise_queries" name="code" path="noise_queries_code_.kt"]
import afield.math.distribution.*

fun show(label: String, e: Estimate, exact: Double) =
    println("%-24s %-28s exact %.5f   z = %+.2f".format(label, e, exact, e.zFrom(exact)))

val x = die(6)
val y = die(6)
show("E(5 + X + Y)", E(5 + x + y), 12.0)
show("P(X + Y < 10)", P(x + y lt 10), 30.0 / 36)
show("E(X - X)", E(x - x), 0.0)
show("Var(X - X')", Var(x - x.iid()), 35.0 / 6)

val birthday = draws(23, uniformInt(1, 365))
val noShared = (0 until 23).fold(1.0) { p, k -> p * (365.0 - k) / 365.0 }
show("P(shared birthday)", P(hasDuplicates(birthday)), 1 - noShared)

val u = uniform(-1, 1)
val v = uniform(-1, 1)
show("4 P(u² + v² < 1)", P(u * u + v * v lt 1) * 4, Math.PI)
show("median of exp(1)", quantile(exponential(1), 0.5), kotlin.math.ln(2.0))
```

## 4. 3 · samples settling under a density — launch me

<!-- field-box id="noise_density" index="3" -->

```edn [field-box id="noise_density" role="properties"]
{"id" "noise_density" "index" 3 "name" "3 · samples settling under a density — launch me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 · samples settling under a density — launch me" "_tombstone" false "frame" #afield/rect [500.0 40.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "noise_density_code_.kt" "lines" 10 "characters" 319 "sha256" "597a3efa3d95f8e6905d9d543be1f6943c83363b3b0ba8ece609a3146f95b897"}]}
```

```kotlin [field-property box="noise_density" name="code" path="noise_density_code_.kt"]
import afield.math.distribution.*

val sum = normal(0, 1) + normal(2, 0.5)
val squared = normal(0, 1).squared()

loop {
    __m("sum").distribution(sum, batch = 400, title = "N(0,1) + N(2,0.5)", exact = 2.0)
    __m("sq").distribution(squared, batch = 400, title = "Z², no closed form here", exact = 1.0)
    frame()
}
```

