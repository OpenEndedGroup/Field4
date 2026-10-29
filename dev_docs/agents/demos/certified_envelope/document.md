# Field Document: certified_envelope

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 6 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory" "isClojureRoot" "isDatalogRoot" "isForthRoot" "isFieldMLRoot" "isMorphoRoot" "isNanoRoot" "nanoLaunchDelayMs" "nanoMemoryInit" "isKotlinJsRoot" "mainLanguage" "spreadsheet"]}
```

## 1. root

<!-- field-box id="root" index="0" -->

```edn [field-box id="root" role="properties"]
{"id" "root" "index" 0 "name" "root" "parents" ["metaroot"] "children" ["sampled" "certified" "range" "limits" "workbench"] "properties" {"children" #{#afield/box "sampled" #afield/box "certified" #afield/box "range" #afield/box "limits" #afield/box "workbench"} "parents" #{#afield/box "metaroot"} "name" "root" "nested" #{}} "blockProperties" []}
```

## 2. 3 - certifiedRange, with witnesses

<!-- field-box id="range" index="1" -->

```edn [field-box id="range" role="properties"]
{"id" "range" "index" 1 "name" "3 - certifiedRange, with witnesses" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - certifiedRange, with witnesses" "_tombstone" false "frame" #afield/rect [60.0 1480.0 340.0 100.0] "preamble" ""} "blockProperties" [{"name" "code" "language" "kotlin" "path" "range_code_.kt" "lines" 33 "characters" 1276 "sha256" "a17ed87488669cebe11491fc7d6c2ebd355a081728f0b7e3c51cb6e9f974f251"}]}
```

```kotlin [field-property box="range" name="code" path="range_code_.kt"]
// Branch-and-bound over the hour: the true maximum is at least the value reached at the
// witness, and at most the proven bound. The same for the minimum.
val signal = with(Functions) {
    sin(1.0 / 40.0, amplitude = 0.2, offset = 0.45) + gaussian(1234.57, 0.002, 0.5, Functions.Domain.ALL)
}
val range = signal.certifiedRange(0.0, 3600.0, tolerance = 1e-9)
val max = range.max!!
val min = range.min!!
display(
    "range",
    Table(
            ["side", "reached", "at x", "proven bound", "enclosures"],
            title = "certifiedRange(0, 3600): certified = ${range.certified}",
        )
        .row("max", max.value, max.witness, max.bound, max.enclosures)
        .row("min", min.value, min.witness, min.bound, min.enclosures)
        .role("at x", CellRole.ACCENT),
)

// Yes/no questions with the same guarantee. A refutation carries the x that breaks the claim.
val claims = [
    signal.certifiedNeverExceeds(0.7, 0.0, 3600.0),
    signal.certifiedNeverExceeds(0.8, 0.0, 3600.0),
    signal.certifiedReaches(0.75, 0.0, 3600.0),
]
display(
    "claims",
    Table(["claim", "verdict", "witness x"]).rows(claims.map { [it.claim, it.kind.name, it.witness] }).roleBy(
        "verdict",
    ) {
        if (it == "PROVEN") CellRole.OK else CellRole.WARN
    },
)
```

## 3. 2 - The certified band catches it

<!-- field-box id="certified" index="2" -->

```edn [field-box id="certified" role="properties"]
{"id" "certified" "index" 2 "name" "2 - The certified band catches it" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - The certified band catches it" "_tombstone" false "frame" #afield/rect [60.0 760.0 340.0 100.0] "preamble" ""} "blockProperties" [{"name" "code" "language" "kotlin" "path" "certified_code_.kt" "lines" 27 "characters" 1189 "sha256" "0f1057b748c8765e13dbdac3fd7ebc90e658625b4b16ab12e6ba83b116ec2888"}]}
```

```kotlin [field-property box="certified" name="code" path="certified_code_.kt"]
/*
The same signal as box 1. certifiedSignalChannel draws its sampled envelope in white and,
behind it in amber, a certified bound per bin: every value the function takes in the bin
lies inside, whether or not a sample landed on it. Nothing here is sampled more finely.
*/
val signal = with(Functions) {
    sin(1.0 / 40.0, amplitude = 0.2, offset = 0.45) + gaussian(1234.57, 0.002, 0.5, Functions.Domain.ALL)
}
val viewer = SignalViewer([certifiedSignalChannel("x", signal)], 0.0, 3600.0)
displaySignal(
    viewer,
    "main",
    SignalViewerOptions(label = "sampled envelope, with its certified band", viewKey = "certified_envelope:certified"),
)

// The band, read back: bin 342 is bounded above by the glitch's true height.
val band = signal.certifiedEnvelope(0.0, 3600.0, 1000)
val bin = (1234.57 / 3.6).toInt()
display(
    "numbers",
    Table(["what", "value"], title = "the certified band, as numbers")
        .row("f(1234.57), the glitch's peak", signal(1234.57))
        .row("certified max, bin $bin", band.high[bin])
        .row("certified min, bin $bin", band.low[bin])
        .row("enclosures for 1000 bins", band.enclosures)
        .role("value", CellRole.ACCENT),
)
```

## 4. 5 - The worked example, certified

<!-- field-box id="workbench" index="3" -->

```edn [field-box id="workbench" role="properties"]
{"id" "workbench" "index" 3 "name" "5 - The worked example, certified" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 - The worked example, certified" "_tombstone" false "frame" #afield/rect [60.0 2120.0 340.0 100.0] "preamble" ""} "blockProperties" [{"name" "code" "language" "kotlin" "path" "workbench_code_.kt" "lines" 140 "characters" 6173 "sha256" "5ddcbd9fbe17544576485cca94838ccc9181fc278d86b8c73b83f66de1b497a2"}]}
```

```kotlin [field-property box="workbench" name="code" path="workbench_code_.kt"]
/*
The function workbench's worked example (public/docs/assets/function_conversation/final.kt),
twice. `lambda` is its channel code, verbatim: one fn { } per channel, which the interval walk
refuses. `formula` is the same function from formula-bearing ops. The only construct that needed
a lambda was the piecewise event selection; here it is exact 0/1 `step` indicators, with every
product and sum in the lambda's order, so the two agree bit for bit.
*/
val events = [
    StudyEvent("e1", 185.0, 100.0, 30.0, 100.0),
    StudyEvent("e2", 785.0, 100.0, 30.0, 100.0),
    StudyEvent("e3", 1425.0, 200.0, 30.0, 100.0),
    StudyEvent("e5", 2585.0, 100.0, 30.0, 100.0),
    StudyEvent("e6", 3185.0, 100.0, 30.0, 100.0),
]
val names = ["x", "y", "z"]

fun smooth01(value: Double): Double {
    val u = value.coerceIn(0.0, 1.0)
    return u * u * u * (10.0 + u * (-15.0 + 6.0 * u))
}
fun backgroundOf(seconds: Double, channel: Int): Double {
    val drift = 2.0 * PI / 3.0 * kotlin.math.sin(2.0 * PI * seconds / 100.0)
    val phase = 2.0 * PI * seconds / 40.0 + (channel - 1) * drift
    return 0.45 + 0.20 * kotlin.math.sin(phase) + 0.04 * kotlin.math.sin(2.0 * PI * seconds / 230.0)
}
fun shoulderOf(base: Double, age: Double, span: Double): Double {
    val u = (age / span).coerceIn(0.0, 1.0)
    val cycles = 0.06 * age + 0.5 * (0.80 - 0.06) * age * age / span
    val pulse = 0.5 - 0.5 * kotlin.math.cos(2.0 * PI * cycles)
    val strength = smooth01(u)
    val pulsed = (1.0 - strength) * base + strength * (0.25 + (1.0 - 0.25) * pulse)
    val merge = smooth01((u - (1.0 - 0.20)) / 0.20)
    return (1.0 - merge) * pulsed + merge
}
fun lambda(index: Int, name: String) = with(Functions) {
    fn(0.0..3600.0, name) { seconds ->
        val base = backgroundOf(seconds, index)
        val event = events.firstOrNull { seconds >= it.start && seconds <= it.end }
        when {
            event == null -> base
            seconds < event.plateauStart -> {
                val age = seconds - event.start
                val lag = if (event.id == "e3" && name == "y" && age < 180.0) {
                    val u = age / 180.0
                    val v = 1.0 - u
                    6.0 * 64.0 * u * u * u * v * v * v
                } else 0.0
                shoulderOf(base, age - lag, event.rise)
            }
            seconds <= event.plateauEnd -> 1.0
            else -> shoulderOf(base, event.end - seconds, event.fall)
        }
    }
}

fun formula(index: Int, name: String) = with(Functions) {
    val all = Functions.Domain.ALL
    fun k(v: Double) = c(v, all)
    val t = identity(all)
    val one = k(1.0)
    fun sinOf(p: Functions.Fn) = sin(frequency = k(0.0), phase = p) // sin(0*x + p) is sin(p), exactly
    fun cosOf(p: Functions.Fn) = cos(frequency = k(0.0), phase = p)
    fun smooth(v: Functions.Fn): Functions.Fn {
        val u = v.clamp(0.0, 1.0)
        return u * u * u * (k(10.0) + u * (k(-15.0) + k(6.0) * u))
    }
    val drift = k(2.0 * PI / 3.0) * sinOf(k(2.0 * PI) * t / k(100.0))
    val base =
        k(0.45) +
            k(0.20) * sinOf(k(2.0 * PI) * t / k(40.0) + k((index - 1).toDouble()) * drift) +
            k(0.04) * sinOf(k(2.0 * PI) * t / k(230.0))
    fun shoulder(age: Functions.Fn, span: Double): Functions.Fn {
        val u = (age / k(span)).clamp(0.0, 1.0)
        val cycles = k(0.06) * age + k(0.5 * (0.80 - 0.06)) * age * age / k(span)
        val pulse = k(0.5) - k(0.5) * cosOf(k(2.0 * PI) * cycles)
        val strength = smooth(u)
        val pulsed = (one - strength) * base + strength * (k(0.25) + k(1.0 - 0.25) * pulse)
        val merge = smooth((u - k(1.0 - 0.20)) / k(0.20))
        return (one - merge) * pulsed + merge
    }
    fun from(a: Double) = step(a, all) // exactly [x >= a]; [x <= b] is 1 - from(nextUp(b))
    var sum: Functions.Fn = k(0.0) * t
    var inside: Functions.Fn = k(0.0)
    for (e in events) {
        val age = t - k(e.start)
        val local = if (e.id == "e3" && name == "y") {
            val u = age / k(180.0)
            val v = one - u
            age - (k(180.0) - age).sign().clamp(0.0, 1.0) * (k(6.0 * 64.0) * u * u * u * v * v * v)
        } else age
        sum =
            sum +
                ((from(e.start) - from(e.plateauStart)) * shoulder(local, e.rise) +
                    (from(e.plateauStart) - from(Math.nextUp(e.plateauEnd))) * one +
                    (from(Math.nextUp(e.plateauEnd)) - from(Math.nextUp(e.end))) * shoulder(k(e.end) - t, e.fall))
        inside = inside + (from(e.start) - from(Math.nextUp(e.end)))
    }
    sum + (one - inside) * base
}

val rows = mutableListOf<List<Any?>>()
for ((i, name) in names.withIndex()) {
    val l = lambda(i, name)
    val f = formula(i, name)
    val grid = (0..72_000).map { it * 0.05 }
    val mismatches = grid.count { l(it).toRawBits() != f(it).toRawBits() }
    val holds = events.count { f.certifiedEquals(1.0, it.plateauStart, it.plateauEnd).proven }
    val above = f.certifiedNeverExceeds(1.0, 0.0, 3600.0)
    val witness = above.witness!!
    rows +=
        [
            name,
            "${FnIntervals.unsupportedOps(l)} / ${FnIntervals.unsupportedOps(f)}",
            "$mismatches of ${grid.size}",
            "$holds of ${events.size} PROVEN",
            f.certifiedNeverBelow(0.0, 0.0, 3600.0).kind.name,
            "${above.kind} at $witness: lambda ${l(witness)}",
        ]
}
display(
    "claims",
    Table(
            ["ch", "refused ops (lambda / formula)", "bit mismatches", "== 1 on plateau", ">= 0", "<= 1"],
            title = "the worked example's claims, on its formula rewrite",
        )
        .rows(rows)
        .role("<= 1", CellRole.WARN),
)

// How far above 1: certifiedRange's proven bound is the certificate for <= 1 + 1e-9 (x only; ~11 s).
val xMax = formula(0, "x").certifiedRange(0.0, 3600.0, tolerance = 9e-10).max!!
display(
    "max",
    Table(["what", "value"], title = "channel x, the whole hour")
        .row("max reached", xMax.value.toString())
        .row("at x", xMax.witness.toString())
        .row("proven bound", xMax.bound.toString())
        .row("enclosures", xMax.enclosures)
        .role("value", CellRole.ACCENT),
)
```

## 5. 1 - An hour, and a glitch the plot never sees

<!-- field-box id="sampled" index="4" -->

```edn [field-box id="sampled" role="properties"]
{"id" "sampled" "index" 4 "name" "1 - An hour, and a glitch the plot never sees" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - An hour, and a glitch the plot never sees" "_tombstone" false "frame" #afield/rect [60.0 40.0 340.0 100.0] "preamble" ""} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sampled_code_.kt" "lines" 29 "characters" 1237 "sha256" "14031a7ea0c55d604db955653e53509cf893144b5df7741901bc7f5d14f5ceee"}]}
```

```kotlin [field-property box="sampled" name="code" path="sampled_code_.kt"]
/*
One hour: a slow 40-second swing, plus a glitch 10 ms wide at 1234.57 s — exactly halfway
between two of the viewer's 0.02 s samples. Built from Functions ops rather than a
fn { } lambda, so every node has a formula. Box 2 needs that.
*/
val signal = with(Functions) {
    sin(1.0 / 40.0, amplitude = 0.2, offset = 0.45) + gaussian(1234.57, 0.002, 0.5, Functions.Domain.ALL)
}

// The sampled envelope, drawn the way the function workbench draws it: min and max per bin.
val viewer = SignalViewer([SignalChannel("x", signal::eval)], 0.0, 3600.0)
displaySignal(
    viewer,
    "main",
    SignalViewerOptions(label = "sampled envelope only", viewKey = "certified_envelope:sampled"),
)

// The same query the plot makes, read back as numbers. Bin 342 holds the glitch.
val bins = viewer.window(0.0, 3600.0, 1000).lanes[0].current
val bin = (1234.57 / 3.6).toInt()
display(
    "numbers",
    Table(["what", "value"], title = "what the sampled plot can see")
        .row("f(1234.57), the glitch's peak", signal(1234.57))
        .row("sampled max, bin $bin", bins.high[bin])
        .row("sampled max, whole hour", bins.high.max())
        .row("missed by", signal(1234.57) - bins.high[bin])
        .role("value", CellRole.ACCENT),
)
```

## 6. 4 - What refuses, and an exact hold

<!-- field-box id="limits" index="5" -->

```edn [field-box id="limits" role="properties"]
{"id" "limits" "index" 5 "name" "4 - What refuses, and an exact hold" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 - What refuses, and an exact hold" "_tombstone" false "frame" #afield/rect [60.0 1800.0 340.0 100.0] "preamble" ""} "blockProperties" [{"name" "code" "language" "kotlin" "path" "limits_code_.kt" "lines" 29 "characters" 1372 "sha256" "62c451f7bb14c56a7624143be40c3d3fd0f3a3fa93bf117f33528dede38d878c"}]}
```

```kotlin [field-property box="limits" name="code" path="limits_code_.kt"]
/*
What refuses. The function workbench's channels are fn(0.0..3600.0, name) { seconds -> ... }
lambdas: one opaque node each, so there is nothing for an interval walk to read. It refuses by
name rather than guess.
*/
val lambdaChannel = with(Functions) { fn(0.0..3600.0, "x") { t -> 0.45 + 0.2 * kotlin.math.sin(t) } }
val refused = lambdaChannel.enclose(0.0, 3600.0)

// The workbench's claim that an event holds exactly 1 for 30 seconds, rebuilt from formula ops:
// rise 185..285, hold to 315, fall to 415, over the moving background.
val held = with(Functions) {
    val one = c(1.0, Functions.Domain.ALL)
    val background = sin(1.0 / 40.0, amplitude = 0.2, offset = 0.45)
    val envelope =
        smoothStep(185.0, 285.0, Functions.Domain.ALL) * (one - smoothStep(315.0, 415.0, Functions.Domain.ALL))
    one - (one - background) * (one - envelope)
}
val checks = [
    held.certifiedEquals(1.0, 285.0, 315.0),
    held.certifiedEquals(1.0, 284.0, 315.0),
    held.certifiedNeverExceeds(1.0, 0.0, 3600.0, slack = 1e-12),
]
display(
    "limits",
    Table(["question", "answer"], title = "refusals and exact holds")
        .row("enclose the lambda channel", "certified = ${refused.certified}: ${refused.refusal}")
        .rows(checks.map { [it.claim, it.kind.name + (it.witness?.let { w -> " at x = $w" } ?: "")] })
        .role("answer", CellRole.ACCENT),
)
```

