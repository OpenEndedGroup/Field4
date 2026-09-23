# Field Document: certified_envelope

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory" "isClojureRoot" "isDatalogRoot" "isForthRoot" "isFieldMLRoot" "isMorphoRoot" "isNanoRoot" "nanoLaunchDelayMs" "nanoMemoryInit" "isKotlinJsRoot" "mainLanguage" "spreadsheet"]}
```

## 1. root

<!-- field-box id="root" index="0" -->

```edn [field-box id="root" role="properties"]
{"id" "root" "index" 0 "name" "root" "parents" ["metaroot"] "children" ["sampled" "certified" "range" "limits"] "properties" {"children" #{#afield/box "sampled" #afield/box "certified" #afield/box "range" #afield/box "limits"} "parents" #{#afield/box "metaroot"} "name" "root" "nested" #{}} "blockProperties" []}
```

## 2. 3 - certifiedRange, with witnesses

<!-- field-box id="range" index="1" -->

```edn [field-box id="range" role="properties"]
{"id" "range" "index" 1 "name" "3 - certifiedRange, with witnesses" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - certifiedRange, with witnesses" "_tombstone" false "frame" #afield/rect [60.0 1480.0 340.0 100.0] "preamble" ""} "blockProperties" [{"name" "code" "language" "kotlin" "path" "range_code_.kt" "lines" 29 "characters" 1375 "sha256" "0ac978c1c36d1d429d3bb3aadb5b67e80bf10d5cf7aeb52a26e4a92533096b9a"}]}
```

```kotlin [field-property box="range" name="code" path="range_code_.kt"]
import afield.stdlib.Functions
import afield.stdlib.certifiedNeverExceeds
import afield.stdlib.certifiedRange
import afield.stdlib.certifiedReaches

// Branch-and-bound over the hour: the true maximum is at least the value reached at the
// witness, and at most the proven bound. The same for the minimum.
val signal = with(Functions) {
    sin(1.0 / 40.0, amplitude = 0.2, offset = 0.45) +
        gaussian(1234.57, 0.002, 0.5, Functions.Domain.ALL)
}
val range = signal.certifiedRange(0.0, 3600.0, tolerance = 1e-9)
val max = range.max!!
val min = range.min!!
display("range", Table(listOf("side", "reached", "at x", "proven bound", "enclosures"),
    title = "certifiedRange(0, 3600): certified = ${range.certified}")
    .row("max", max.value, max.witness, max.bound, max.enclosures)
    .row("min", min.value, min.witness, min.bound, min.enclosures)
    .role("at x", CellRole.ACCENT))

// Yes/no questions with the same guarantee. A refutation carries the x that breaks the claim.
val claims = listOf(
    signal.certifiedNeverExceeds(0.7, 0.0, 3600.0),
    signal.certifiedNeverExceeds(0.8, 0.0, 3600.0),
    signal.certifiedReaches(0.75, 0.0, 3600.0),
)
display("claims", Table(listOf("claim", "verdict", "witness x"))
    .rows(claims.map { listOf(it.claim, it.kind.name, it.witness) })
    .roleBy("verdict") { if (it == "PROVEN") CellRole.OK else CellRole.WARN })
```

## 3. 2 - The certified band catches it

<!-- field-box id="certified" index="2" -->

```edn [field-box id="certified" role="properties"]
{"id" "certified" "index" 2 "name" "2 - The certified band catches it" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - The certified band catches it" "_tombstone" false "frame" #afield/rect [60.0 760.0 340.0 100.0] "preamble" ""} "blockProperties" [{"name" "code" "language" "kotlin" "path" "certified_code_.kt" "lines" 27 "characters" 1422 "sha256" "6225e8828cbc0902e2e940f5c660c17fa4a5b772b827a8addef958e90679b3dc"}]}
```

```kotlin [field-property box="certified" name="code" path="certified_code_.kt"]
import afield.stdlib.Functions
import afield.stdlib.SignalViewer
import afield.stdlib.SignalViewerOptions
import afield.stdlib.certifiedEnvelope
import afield.stdlib.certifiedSignalChannel
import afield.stdlib.displaySignal

// The same signal as box 1. certifiedSignalChannel draws its sampled envelope in white and,
// behind it in amber, a certified bound per bin: every value the function takes in the bin
// lies inside, whether or not a sample landed on it. Nothing here is sampled more finely.
val signal = with(Functions) {
    sin(1.0 / 40.0, amplitude = 0.2, offset = 0.45) +
        gaussian(1234.57, 0.002, 0.5, Functions.Domain.ALL)
}
val viewer = SignalViewer(listOf(certifiedSignalChannel("x", signal)), 0.0, 3600.0)
afield.Box.context.get()!!.displaySignal(viewer, "main",
    SignalViewerOptions(label = "sampled envelope, with its certified band", viewKey = "certified_envelope:certified"))

// The band, read back: bin 342 is bounded above by the glitch's true height.
val band = signal.certifiedEnvelope(0.0, 3600.0, 1000)
val bin = (1234.57 / 3.6).toInt()
display("numbers", Table(listOf("what", "value"), title = "the certified band, as numbers")
    .row("f(1234.57), the glitch's peak", signal(1234.57))
    .row("certified max, bin $bin", band.high[bin])
    .row("certified min, bin $bin", band.low[bin])
    .row("enclosures for 1000 bins", band.enclosures)
    .role("value", CellRole.ACCENT))
```

## 4. 1 - An hour, and a glitch the plot never sees

<!-- field-box id="sampled" index="3" -->

```edn [field-box id="sampled" role="properties"]
{"id" "sampled" "index" 3 "name" "1 - An hour, and a glitch the plot never sees" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - An hour, and a glitch the plot never sees" "_tombstone" false "frame" #afield/rect [60.0 40.0 340.0 100.0] "preamble" ""} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sampled_code_.kt" "lines" 28 "characters" 1422 "sha256" "111bedb80a3bad4582a25e10c4c9f669a08098c3fefdc7ebac21931c2496733f"}]}
```

```kotlin [field-property box="sampled" name="code" path="sampled_code_.kt"]
import afield.stdlib.Functions
import afield.stdlib.SignalChannel
import afield.stdlib.SignalViewer
import afield.stdlib.SignalViewerOptions
import afield.stdlib.displaySignal

// One hour: a slow 40-second swing, plus a glitch 10 ms wide at 1234.57 s — exactly halfway
// between two of the viewer's 0.02 s samples. Built from Functions ops rather than a
// fn { } lambda, so every node has a formula. Box 2 needs that.
val signal = with(Functions) {
    sin(1.0 / 40.0, amplitude = 0.2, offset = 0.45) +
        gaussian(1234.57, 0.002, 0.5, Functions.Domain.ALL)
}

// The sampled envelope, drawn the way the function workbench draws it: min and max per bin.
val viewer = SignalViewer(listOf(SignalChannel("x", signal::eval)), 0.0, 3600.0)
afield.Box.context.get()!!.displaySignal(viewer, "main",
    SignalViewerOptions(label = "sampled envelope only", viewKey = "certified_envelope:sampled"))

// The same query the plot makes, read back as numbers. Bin 342 holds the glitch.
val bins = viewer.window(0.0, 3600.0, 1000).lanes[0].current
val bin = (1234.57 / 3.6).toInt()
display("numbers", Table(listOf("what", "value"), title = "what the sampled plot can see")
    .row("f(1234.57), the glitch's peak", signal(1234.57))
    .row("sampled max, bin $bin", bins.high[bin])
    .row("sampled max, whole hour", bins.high.max())
    .row("missed by", signal(1234.57) - bins.high[bin])
    .role("value", CellRole.ACCENT))
```

## 5. 4 - What refuses, and an exact hold

<!-- field-box id="limits" index="4" -->

```edn [field-box id="limits" role="properties"]
{"id" "limits" "index" 4 "name" "4 - What refuses, and an exact hold" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 - What refuses, and an exact hold" "_tombstone" false "frame" #afield/rect [60.0 1800.0 340.0 100.0] "preamble" ""} "blockProperties" [{"name" "code" "language" "kotlin" "path" "limits_code_.kt" "lines" 29 "characters" 1511 "sha256" "78e73b8b53a97215111a5d8d67a837dc633b7eb21be681e4cfbcc6ff923d9d8b"}]}
```

```kotlin [field-property box="limits" name="code" path="limits_code_.kt"]
import afield.stdlib.Functions
import afield.stdlib.certifiedEquals
import afield.stdlib.certifiedNeverExceeds
import afield.stdlib.enclose

// What refuses. The function workbench's channels are fn(0.0..3600.0, name) { seconds -> ... }
// lambdas: one opaque node each, so there is nothing for an interval walk to read. It refuses by
// name rather than guess.
val lambdaChannel = with(Functions) { fn(0.0..3600.0, "x") { t -> 0.45 + 0.2 * kotlin.math.sin(t) } }
val refused = lambdaChannel.enclose(0.0, 3600.0)

// The workbench's claim that an event holds exactly 1 for 30 seconds, rebuilt from formula ops:
// rise 185..285, hold to 315, fall to 415, over the moving background.
val held = with(Functions) {
    val one = c(1.0, Functions.Domain.ALL)
    val background = sin(1.0 / 40.0, amplitude = 0.2, offset = 0.45)
    val envelope = smoothStep(185.0, 285.0, Functions.Domain.ALL) *
        (one - smoothStep(315.0, 415.0, Functions.Domain.ALL))
    one - (one - background) * (one - envelope)
}
val checks = listOf(
    held.certifiedEquals(1.0, 285.0, 315.0),
    held.certifiedEquals(1.0, 284.0, 315.0),
    held.certifiedNeverExceeds(1.0, 0.0, 3600.0, slack = 1e-12),
)
display("limits", Table(listOf("question", "answer"), title = "refusals and exact holds")
    .row("enclose the lambda channel", "certified = ${refused.certified}: ${refused.refusal}")
    .rows(checks.map { listOf(it.claim, it.kind.name + (it.witness?.let { w -> " at x = $w" } ?: "")) })
    .role("answer", CellRole.ACCENT))
```

