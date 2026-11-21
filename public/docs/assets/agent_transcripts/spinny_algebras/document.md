# Field Document: spinny_algebras

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 6 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. j² = -1, 0, +1

<!-- field-box id="kappa_three" index="0" -->

### Narrative

From `code`

Keep i² = -1 and ij = -ji = k, but let j² = κ be any number. κ = -1 is the quaternions.
Elements are (real, i, j, k); with these rules k² = κ as well.

```edn [field-box id="kappa_three" role="properties"]
{"id" "kappa_three" "index" 0 "name" "j² = -1, 0, +1" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "j² = -1, 0, +1" "_tombstone" false "frame" #afield/rect [800.0 2100.0 260.0 80.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kappa_three_code_.kt" "lines" 50 "characters" 1699 "sha256" "f108dc091ae977d26cf4849fbcc723289b2594826e2ec9944901dc9be6c908c8"}]}
```

```kotlin [field-property box="kappa_three" name="code" path="kappa_three_code_.kt"]
// Keep i² = -1 and ij = -ji = k, but let j² = κ be any number. κ = -1 is the quaternions.
// Elements are (real, i, j, k); with these rules k² = κ as well.
fun times(p: Vec4, q: Vec4, kappa: Double): Vec4 {
    val K = kappa.toFloat()
    return vec(
        p.x * q.x - p.y * q.y + K * (p.z * q.z + p.w * q.w),
        p.x * q.y + p.y * q.x + K * (p.w * q.z - p.z * q.w),
        p.x * q.z + p.z * q.x - p.y * q.w + p.w * q.y,
        p.x * q.w + p.w * q.x + p.y * q.z - p.z * q.y,
    )
}

fun exp(u: Vec4, t: Double, kappa: Double): Vec4 {
    var sum = vec(0, 0, 0, 0)
    var term = vec(1, 0, 0, 0)
    for (n in 1..40) {
        sum = sum + term
        term = times(term, u * (t / n).toFloat(), kappa)
    }
    return sum
}

val s = 45.0
fun panel(kappa: Double, ox: Double): List<FLine> {
    val ts = (0..240).map { it * 2 * Math.PI / 240 }
    return (0..6).flatMap { step ->
        val th = step * Math.PI / 6
        val u = vec(0.0, kotlin.math.cos(th), kotlin.math.sin(th), 0.0)
        ts.map { exp(u, it, kappa) }
            .zipWithNext()
            .filter { (a, _) -> a.length() < 3.5 }
            .map { (a, b) ->
                fun at(p: Vec4) = vec(ox + s * (p.y + 0.45 * p.x), -s * (p.z + 0.3 * p.x))
                val c = (a.w * 0.8).coerceIn(-1.0, 1.0)
                fline {
                    moveTo(at(a))
                    lineTo(at(b))
                    color = vec(0.6 + 0.4 * c, 0.65 - 0.25 * kotlin.math.abs(c), 0.6 - 0.4 * c, 0.95)
                    thickness = 1.6
                }
            }
    }
}

lines(
    *(panel(-1.0, 0.0) + panel(0.0, 260.0) + panel(1.0, 520.0)).toTypedArray(),
    w = 760.0,
    h = 280.0,
    fitToSize = true,
)
```

## 2. exp in Quat vs bicomplex

<!-- field-box id="quat_vs_bicomplex" index="1" -->

### Narrative

From `code`

exp(t·u) summed as a power series, once with Field's Quat and once in the bicomplex numbers.
Both are four numbers (real, i, j, k) with i² = j² = -1; they differ in what ij and ji are.

```edn [field-box id="quat_vs_bicomplex" role="properties"]
{"id" "quat_vs_bicomplex" "index" 1 "name" "exp in Quat vs bicomplex" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "exp in Quat vs bicomplex" "_tombstone" false "frame" #afield/rect [800.0 1400.0 260.0 80.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "quat_vs_bicomplex_code_.kt" "lines" 60 "characters" 2162 "sha256" "49f6121ed14c2ecf1619f1cc3e0c6e710f556da378efa9f22f9a891cd9c697fc"}]}
```

```kotlin [field-property box="quat_vs_bicomplex" name="code" path="quat_vs_bicomplex_code_.kt"]
// exp(t·u) summed as a power series, once with Field's Quat and once in the bicomplex numbers.
// Both are four numbers (real, i, j, k) with i² = j² = -1; they differ in what ij and ji are.
fun quatExp(u: Quat, t: Double): Quat {
    var sum = Quat(0f, 0f, 0f, 0f)
    var term = Quat() // the identity, 1
    for (n in 1..40) {
        sum = sum + term
        term = term * u * (t / n)
    }
    return sum
}

// Bicomplex: ij = ji = k, so k² = +1. Stored as (real, i, j, k).
fun biTimes(p: Vec4, q: Vec4) = vec(
    p.x * q.x - p.y * q.y - p.z * q.z + p.w * q.w,
    p.x * q.y + p.y * q.x - p.z * q.w - p.w * q.z,
    p.x * q.z + p.z * q.x - p.y * q.w - p.w * q.y,
    p.x * q.w + p.w * q.x + p.y * q.z + p.z * q.y,
)

fun biExp(u: Vec4, t: Double): Vec4 {
    var sum = vec(0, 0, 0, 0)
    var term = vec(1, 0, 0, 0)
    for (n in 1..40) {
        sum = sum + term
        term = biTimes(term, u * (t / n).toFloat())
    }
    return sum
}

// Draw (real, i, j) in an oblique projection and colour by k: blue for k < 0, red for k > 0.
val s = 55.0
fun path(points: List<Vec4>, ox: Double) =
    points
        .zipWithNext()
        .filter { (a, _) -> a.length() < 3.5 }
        .map { (a, b) ->
            fun at(p: Vec4) = vec(ox + s * (p.y + 0.45 * p.x), -s * (p.z + 0.3 * p.x))
            val c = (a.w * 0.8).coerceIn(-1.0, 1.0)
            fline {
                moveTo(at(a))
                lineTo(at(b))
                color = vec(0.6 + 0.4 * c, 0.65 - 0.25 * kotlin.math.abs(c), 0.6 - 0.4 * c, 0.95)
                thickness = 1.8
            }
        }

val ts = (0..240).map { it * 2 * Math.PI / 240 }
val angles = (0..6).map { it * Math.PI / 6 } // u = cos θ·i + sin θ·j, a unit "imaginary"

val quaternion = angles.flatMap { th ->
    val u = Quat(kotlin.math.cos(th).toFloat(), kotlin.math.sin(th).toFloat(), 0f, 0f)
    path(ts.map { quatExp(u, it).let { q -> vec(q.w, q.x, q.y, q.z) } }, 0.0)
}
val bicomplex = angles.flatMap { th ->
    val u = vec(0.0, kotlin.math.cos(th), kotlin.math.sin(th), 0.0)
    path(ts.map { biExp(u, it) }, 230.0)
}

lines(*(quaternion + bicomplex).toTypedArray(), w = 760.0, h = 340.0, fitToSize = true)
```

## 3. grid × w in three algebras

<!-- field-box id="grid_three" index="2" -->

### Narrative

From `code`

Multiply every point of a grid by one fixed number w = a + bε, in three algebras that differ only in ε².

```edn [field-box id="grid_three" role="properties"]
{"id" "grid_three" "index" 2 "name" "grid × w in three algebras" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "grid × w in three algebras" "_tombstone" false "frame" #afield/rect [800.0 0.0 260.0 80.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "grid_three_code_.kt" "lines" 40 "characters" 1579 "sha256" "910c0355db3ad95e6d2ef679540e69e98b1d76be2035bba295266c024c401dde"}]}
```

```kotlin [field-property box="grid_three" name="code" path="grid_three_code_.kt"]
// Multiply every point of a grid by one fixed number w = a + bε, in three algebras that differ only in ε².
fun times(a: Double, b: Double, c: Double, d: Double, eps2: Double) = vec(a * c + eps2 * b * d, a * d + b * c)

fun gridTimes(a: Double, b: Double, eps2: Double, ox: Double, tint: Vec4): List<FLine> {
    val s = 40.0
    val grid =
        (-3..3).flatMap { k ->
            listOf(
                (0..60).map { vec(k / 3.0, -1 + it / 30.0) }, // a vertical line of the grid
                (0..60).map { vec(-1 + it / 30.0, k / 3.0) }, // a horizontal one
            )
        }
    fun draw(pts: List<Vec2>, col: Vec4, t: Double) = fline {
        pts.forEachIndexed { i, p ->
            val q = times(a, b, p.x.toDouble(), p.y.toDouble(), eps2)
            if (i == 0) moveTo(ox + s * q.x, -s * q.y) else lineTo(ox + s * q.x, -s * q.y)
        }
        color = col
        thickness = t
    }
    val before = grid.map { pts ->
        fline {
            pts.forEachIndexed { i, p ->
                if (i == 0) moveTo(ox + s * p.x, -s * p.y) else lineTo(ox + s * p.x, -s * p.y)
            }
            color = vec(1, 1, 1, 0.15)
        }
    }
    return before + grid.map { draw(it, tint, 1.5) }
}

lines(
    *(gridTimes(0.9, 0.5, -1.0, 0.0, vec(0.35, 0.8, 1.0, 0.9)) + // complex: ε² = -1
            gridTimes(0.9, 0.5, 0.0, 170.0, vec(0.95, 0.85, 0.3, 0.9)) + // dual: ε² = 0
            gridTimes(0.9, 0.5, 1.0, 340.0, vec(1.0, 0.45, 0.55, 0.9)))
        .toTypedArray(), // split-complex: ε² = +1
    w = 760.0,
    h = 260.0,
    fitToSize = true,
)
```

## 4. 1. ε² slider (complex → dual → split-complex)

<!-- field-box id="eps_slider" index="3" -->

### Narrative

From `code`

Drag ε² from -1 (complex) through 0 (dual) to +1 (split-complex).
Left: a grid multiplied by w = 0.9 + 0.5ε. Right: exp(tε) for t from -π to π.

```edn [field-box id="eps_slider" role="properties"]
{"id" "eps_slider" "index" 3 "name" "1. ε² slider (complex → dual → split-complex)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1. ε² slider (complex → dual → split-complex)" "_tombstone" false "frame" #afield/rect [0.0 0.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "eps_slider_code_.kt" "lines" 57 "characters" 1852 "sha256" "88a827a25f0bd4c24eb1dbcd9e66b0b64035567582488fb30cda313bb0367e06"}]}
```

```kotlin [field-property box="eps_slider" name="code" path="eps_slider_code_.kt"]
// Drag ε² from -1 (complex) through 0 (dual) to +1 (split-complex).
// Left: a grid multiplied by w = 0.9 + 0.5ε. Right: exp(tε) for t from -π to π.
fun times(p: Vec2, q: Vec2, eps2: Double) = vec(p.x * q.x + eps2 * p.y * q.y, p.x * q.y + p.y * q.x)

fun expSeries(t: Double, eps2: Double): Vec2 {
    var sum = vec(0.0, 0.0)
    var term = vec(1.0, 0.0)
    for (n in 1..30) {
        sum = sum + term
        term = times(term, vec(0.0, t / n), eps2)
    }
    return sum
}

fun draw(eps2: Double) {
    val s = 45.0
    val w = vec(0.9, 0.5)
    val grid =
        (-3..3).flatMap { k ->
            listOf((0..60).map { vec(k / 3.0, -1 + it / 30.0) }, (0..60).map { vec(-1 + it / 30.0, k / 3.0) })
        }
    val mapped = grid.map { pts ->
        fline {
            pts.forEachIndexed { i, p ->
                val q = times(w, p, eps2)
                if (i == 0) moveTo(s * q.x, -s * q.y) else lineTo(s * q.x, -s * q.y)
            }
            color = vec(0.4, 0.8, 1.0, 0.8)
        }
    }
    val curve = fline {
        (0..200).forEach {
            val p = expSeries(Math.PI * (-1.0 + 2.0 * it / 200), eps2)
            if (p.length() < 4.0) {
                if (nodes.isEmpty()) moveTo(260 + s * p.x, -s * p.y) else lineTo(260 + s * p.x, -s * p.y)
            }
        }
        color = vec(1.0, 0.8, 0.3, 1.0)
        thickness = 2.5
    }
    val axes = listOf(
        fline {
            moveTo(260 - 3 * s, 0)
            lineTo(260 + 4 * s, 0)
            color = vec(1, 1, 1, 0.25)
        },
        fline {
            moveTo(260, -3 * s)
            lineTo(260, 3 * s)
            color = vec(1, 1, 1, 0.25)
        },
    )
    lines(*(mapped + axes + curve).toTypedArray(), w = 620.0, h = 300.0, fitToSize = true)
}

slider("epsilon²", min = -1.0, max = 1.0, step = 0.01, initialValue = -1.0) { draw(it) }
draw(-1.0)
```

## 5. 2. j² slider (quaternions → split-quaternions)

<!-- field-box id="kappa_slider" index="4" -->

### Narrative

From `code`

Drag j² = κ from -1 (quaternions) through 0 to +1 (split-quaternions). Colour is the k component.
Keep i² = -1 and ij = -ji = k, but let j² = κ be any number. κ = -1 is the quaternions.
Elements are (real, i, j, k); with these rules k² = κ as well.

```edn [field-box id="kappa_slider" role="properties"]
{"id" "kappa_slider" "index" 4 "name" "2. j² slider (quaternions → split-quaternions)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2. j² slider (quaternions → split-quaternions)" "_tombstone" false "frame" #afield/rect [0.0 800.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kappa_slider_code_.kt" "lines" 51 "characters" 1858 "sha256" "972fdfcd92593dbf03e8f28c8e22c43ecc27133960fd769603257cc88923dcf6"}]}
```

```kotlin [field-property box="kappa_slider" name="code" path="kappa_slider_code_.kt"]
/*
Drag j² = κ from -1 (quaternions) through 0 to +1 (split-quaternions). Colour is the k component.
Keep i² = -1 and ij = -ji = k, but let j² = κ be any number. κ = -1 is the quaternions.
Elements are (real, i, j, k); with these rules k² = κ as well.
*/
fun times(p: Vec4, q: Vec4, kappa: Double): Vec4 {
    val K = kappa.toFloat()
    return vec(
        p.x * q.x - p.y * q.y + K * (p.z * q.z + p.w * q.w),
        p.x * q.y + p.y * q.x + K * (p.w * q.z - p.z * q.w),
        p.x * q.z + p.z * q.x - p.y * q.w + p.w * q.y,
        p.x * q.w + p.w * q.x + p.y * q.z - p.z * q.y,
    )
}

fun exp(u: Vec4, t: Double, kappa: Double): Vec4 {
    var sum = vec(0, 0, 0, 0)
    var term = vec(1, 0, 0, 0)
    for (n in 1..40) {
        sum = sum + term
        term = times(term, u * (t / n).toFloat(), kappa)
    }
    return sum
}

val s = 45.0
fun panel(kappa: Double, ox: Double): List<FLine> {
    val ts = (0..240).map { it * 2 * Math.PI / 240 }
    return (0..6).flatMap { step ->
        val th = step * Math.PI / 6
        val u = vec(0.0, kotlin.math.cos(th), kotlin.math.sin(th), 0.0)
        ts.map { exp(u, it, kappa) }
            .zipWithNext()
            .filter { (a, _) -> a.length() < 3.5 }
            .map { (a, b) ->
                fun at(p: Vec4) = vec(ox + s * (p.y + 0.45 * p.x), -s * (p.z + 0.3 * p.x))
                val c = (a.w * 0.8).coerceIn(-1.0, 1.0)
                fline {
                    moveTo(at(a))
                    lineTo(at(b))
                    color = vec(0.6 + 0.4 * c, 0.65 - 0.25 * kotlin.math.abs(c), 0.6 - 0.4 * c, 0.95)
                    thickness = 1.6
                }
            }
    }
}

fun draw(kappa: Double) = lines(*panel(kappa, 0.0).toTypedArray(), w = 420.0, h = 340.0, fitToSize = true)

slider("j²", min = -1.0, max = 1.0, step = 0.01, initialValue = -1.0) { draw(it) }
draw(-1.0)
```

## 6. exp(tε) as ε² moves

<!-- field-box id="exp_series" index="5" -->

### Narrative

From `code`

exp(tε) = 1 + tε + (tε)²/2! + ..., summed with nothing but the algebra's own multiplication.

```edn [field-box id="exp_series" role="properties"]
{"id" "exp_series" "index" 5 "name" "exp(tε) as ε² moves" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "exp(tε) as ε² moves" "_tombstone" false "frame" #afield/rect [800.0 700.0 260.0 80.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "exp_series_code_.kt" "lines" 58 "characters" 1970 "sha256" "b4cc7d7f1df4efe9877ea519d57b4851b20751d6472dff77ff66c0521ed59704"}]}
```

```kotlin [field-property box="exp_series" name="code" path="exp_series_code_.kt"]
// exp(tε) = 1 + tε + (tε)²/2! + ..., summed with nothing but the algebra's own multiplication.
fun times(p: Vec2, q: Vec2, eps2: Double) = vec(p.x * q.x + eps2 * p.y * q.y, p.x * q.y + p.y * q.x)

fun expSeries(t: Double, eps2: Double, terms: Int): Vec2 {
    var sum = vec(0.0, 0.0)
    var term = vec(1.0, 0.0)
    for (n in 1..terms) {
        sum = sum + term
        term = times(term, vec(0.0, t / n), eps2) // term · tε / n
    }
    return sum
}

val s = 60.0
fun curve(eps2: Double, terms: Int, col: Vec4, ox: Double, width: Double = 1.5) = fline {
    (0..200).forEach {
        val t = Math.PI * (-1.0 + 2.0 * it / 200)
        val p = expSeries(t, eps2, terms)
        if (p.length() < 4.0) {
            if (nodes.isEmpty()) moveTo(ox + s * p.x, -s * p.y) else lineTo(ox + s * p.x, -s * p.y)
        }
    }
    color = col
    thickness = width
}

val axes =
    listOf(0.0, 480.0).flatMap { ox ->
        listOf(
            fline {
                moveTo(ox - 3 * s, 0)
                lineTo(ox + 4 * s, 0)
                color = vec(1, 1, 1, 0.25)
            },
            fline {
                moveTo(ox, -3 * s)
                lineTo(ox, 3 * s)
                color = vec(1, 1, 1, 0.25)
            },
        )
    }

// Left: the three named algebras, with the series cut off after 3, 5 and 30 terms.
val named =
    listOf(-1.0 to vec(0.35, 0.8, 1.0), 0.0 to vec(0.95, 0.85, 0.3), 1.0 to vec(1.0, 0.45, 0.55)).flatMap { (k, c) ->
        listOf(3 to 0.25, 5 to 0.5, 30 to 1.0).map { (n, a) ->
            curve(k, n, vec(c.x, c.y, c.z, a), 0.0, if (n == 30) 2.0 else 1.0)
        }
    }

// Right: ε² swept continuously from -1 to +1; the circle opens through a line into a hyperbola.
val sweep = (0..20).map {
    val k = -1.0 + 2.0 * it / 20
    val f = it / 20.0
    curve(k, 30, vec(0.35 + 0.65 * f, 0.8 - 0.35 * f, 1.0 - 0.45 * f, 0.9), 480.0)
}

lines(*(axes + named + sweep).toTypedArray(), w = 760.0, h = 340.0, fitToSize = true)
```

