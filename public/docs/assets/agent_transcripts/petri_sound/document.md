# Field Document: petri_sound

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 7 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. 4 — tokens that are rhythms

<!-- field-box id="petri_demo_tokens" index="0" -->

```edn [field-box id="petri_demo_tokens" role="properties"]
{"id" "petri_demo_tokens" "index" 0 "name" "4 — tokens that are rhythms" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 — tokens that are rhythms" "_tombstone" false "frame" #afield/rect [60.0 510.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "petri_demo_tokens_code_.kt" "lines" 24 "characters" 1031 "sha256" "ae573d42b95d9e433812d7057a710ffd194ea0bb7ed4b8892ba447926ffdf632"}]}
```

```kotlin [field-property box="petri_demo_tokens" name="code" path="petri_demo_tokens_code_.kt"]
import afield.petri.*
import afield.four.synth.*

// A token's colour can be anything, including a whole PatPattern. So a place is a
// *section*, one circulating token is "which section is sounding", and the net enforces
// that exactly one of them is — no scheduler required.
val song = petriSong(
    "verse"  to (pat("bd ~ sd ~") to 4),
    "chorus" to (pat("bd*4 hh*8") to 4),
)

showPetriNet(song, w = 660.0, h = 340.0)

// `unfold` plays whatever pattern each firing consumed, across that firing's whole arc:
// the slow net schedules the fast one, and both are the same mechanism.
val notes = song.pattern().unfold<String>()

fun bar(c: Long) = notes.events(PatArc(PatQ.of(c), PatQ.of(c + 1)))
    .filter { it.hasOnset }.sortedBy { it.arc.start }.joinToString(" ") { it.value }
for (c in 0L..7L) println("cycle %d   %s".format(c, bar(c)))

// Eight cycles: four bars of verse, four of chorus, and audibly round again.
println(showDirtAudio(notes, cycles = 8, cps = 2.0, channel = "sound").describe())
displayOpen("sound")
```

## 2. 3 — song structure

<!-- field-box id="petri_demo_structure" index="1" -->

```edn [field-box id="petri_demo_structure" role="properties"]
{"id" "petri_demo_structure" "index" 1 "name" "3 — song structure" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 — song structure" "_tombstone" false "frame" #afield/rect [60.0 360.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "petri_demo_structure_code_.kt" "lines" 27 "characters" 927 "sha256" "73a94dc5c2c4b9a7ab6e5d85d1a10e71b4046ba9b709632e89a1101d22ceeace"}]}
```

```kotlin [field-property box="petri_demo_structure" name="code" path="petri_demo_structure_code_.kt"]
import afield.petri.*

// Two nets in one. The fast ring plays the bar; the slow ring is the arrangement, one
// token walking verse -> verse -> chorus and back. Same mechanism at both timescales,
// which is the argument for using a net rather than a sequencer and a separate song mode.
val net = petri("""
    #petri
    !grid 1/4

    verse.a : 1
    verse.a -[ v1 @1     ]-> verse.b
    verse.b -[ v2 @1     ]-> chorus
    chorus  -[ back @1   ]-> verse.a

    beat : 1
    &verse.a + beat -[ vbeat @1/4 : bd ]-> beat2
    &verse.b + beat -[ vbeat2 @1/4 : bd ]-> beat2
    &chorus + beat  -[ cbeat @1/4 : sd ]-> beat2
    beat2 -[ off @1/4 : hh ]-> beat
""")

showPetriNet(net, w = 760.0, h = 420.0)

// Eight cycles: two verses, a chorus, and round again — audibly.
println(showDirtAudio(net.emits(), cycles = 8, cps = 2.0, channel = "sound").describe())
println("bar length: ${net.period()} cycles")
displayOpen("sound")
```

## 3. 7 — play it live

<!-- field-box id="petri_demo_live" index="2" -->

```edn [field-box id="petri_demo_live" role="properties"]
{"id" "petri_demo_live" "index" 2 "name" "7 — play it live" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "7 — play it live" "_tombstone" false "frame" #afield/rect [60.0 960.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "petri_demo_live_code_.kt" "lines" 39 "characters" 1390 "sha256" "c030dee76371a9a8fc30917216b01560e84ecaab9812ca91e8cff61877aa9ecb"}]}
```

```kotlin [field-property box="petri_demo_live" name="code" path="petri_demo_live_code_.kt"]
import afield.petri.*
import afield.four.synth.*

// Boxes 1-6 render *offline* — they mix a wav and hand you a player, which needs no
// audio device and so works in a docs build and in CI. This is the other path: the real
// polyphonic Dirt player, driven by the transport, tempo-synced and interruptible.
val root = DirtRender.dirtSampleRoot()
println(root?.let { "samples: $it" }
    ?: "no Dirt-Samples found — set ${DirtRender.ENV_VAR}, or install the SuperCollider quark")

val net = petri("""
    #petri
    !grid 1/8
    !conflict priority

    pulse : 1
    fill  : 0 ^3

    pulse  -[ a @1/8 pri=9 : bd ]-> mid
    mid    -[ b @1/8 pri=9 : hh ]-> pulse
    &mid   -[ build @1/8 pri=3      ]-> fill
    3*fill -[ crash @1/8 pri=1 : sd ]->
""")
showPetriNet(net, w = 700.0, h = 360.0)

// Flip to true to hear it. It is false by default because `audio { }` opens a real
// output device, and this transcript is replayed end to end in CI on machines that have
// none — everything below still compiles either way, which is the point of the flag
// rather than a comment.
val live = false

if (live && root != null) {
    val kit = dirtPlayer(library = dirt(root.toString()))
    audio { kit }
    net.emits().playDirt(kit, cps = 1.5)
    println("playing — stop the box to stop the sound")
} else {
    println("set `live = true` and run again to hear this through the speakers")
}
```

## 4. 6 — playground

<!-- field-box id="petri_demo_playground" index="3" -->

```edn [field-box id="petri_demo_playground" role="properties"]
{"id" "petri_demo_playground" "index" 3 "name" "6 — playground" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "6 — playground" "_tombstone" false "frame" #afield/rect [60.0 810.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "petri_demo_playground_code_.kt" "lines" 32 "characters" 1150 "sha256" "f8442412fbc695a5c7c9501e9fa32b1d6021af4de548cad338dc625e73df6322"}]}
```

```kotlin [field-property box="petri_demo_playground" name="code" path="petri_demo_playground_code_.kt"]
import afield.petri.*

// A groove with a bit of everything: a read arc, a weighted arc, a capacity and
// priorities. `bd hh` goes round fast; meanwhile `build` watches `mid` without
// consuming it, and every third time it has counted, the weighted arc drains all
// three at once and you get a snare — `crash` has no output side, so it is a drain and
// the net stays bounded and periodic. Change a number and run the box again.
val net = petri("""
    #petri
    !grid 1/8
    !conflict priority

    pulse : 1
    fill  : 0 ^3

    pulse  -[ a @1/8 pri=9 : bd ]-> mid
    mid    -[ b @1/8 pri=9 : hh ]-> pulse

    &mid   -[ build @1/8 pri=3      ]-> fill
    3*fill -[ crash @1/8 pri=1 : sd ]->
""")

showPetriNet(net, w = 760.0, h = 400.0)

println("period: ${net.period()}   deadlocks: ${net.willDeadlock()}")
for (c in 0L..3L) println("cycle %d   %s".format(c, net.emits()
    .events(PatArc(PatQ.of(c), PatQ.of(c + 1)))
    .filter { it.hasOnset }.sortedBy { it.arc.start }.joinToString(" ") { it.value }))
println(net.report())

println(showDirtAudio(net.emits(), cycles = 8, cps = 1.5, channel = "sound").describe())
displayOpen("sound")
```

## 5. 5 — dining philosophers (silent)

<!-- field-box id="petri_demo_philosophers" index="4" -->

```edn [field-box id="petri_demo_philosophers" role="properties"]
{"id" "petri_demo_philosophers" "index" 4 "name" "5 — dining philosophers (silent)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 — dining philosophers (silent)" "_tombstone" false "frame" #afield/rect [60.0 660.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "petri_demo_philosophers_code_.kt" "lines" 34 "characters" 1525 "sha256" "6faa855b02fe49154c09fb3ca4e9df92f8dd5803a8c80b15ce1ebbe733f08c0d"}]}
```

```kotlin [field-property box="petri_demo_philosophers" name="code" path="petri_demo_philosophers_code_.kt"]
import afield.petri.*

// Nothing in the model knows what a note is. Here is the same engine as a concurrency
// checker: five philosophers, five forks, and the shape of the net computed in a loop —
// which is the one thing the `#petri` notation cannot do.
fun table(reverseLast: Boolean, fairly: Boolean) = petri {
    conflict = if (fairly) PetriConflict.ROUND_ROBIN else PetriConflict.ORDER
    grid = cyc(1, 4)
    for (i in 0 until 5) place("fork.$i", tokens = 1)
    for (i in 0 until 5) {
        val left = i
        val right = (i + 1) % 5
        val reversed = reverseLast && i == 4
        t("grab.$i") { from("fork.${if (reversed) right else left}"); to("holding.$i") }
        t("eat.$i") { from("holding.$i"); from("fork.${if (reversed) left else right}"); to("eating.$i") }
        t("done.$i") { from("eating.$i"); to("fork.$left"); to("fork.$right") }
    }
}

val naive = table(reverseLast = false, fairly = false)
showPetriNet(naive, w = 760.0, h = 460.0)

for ((label, net) in listOf(
    "all left-handed" to naive,
    "one reaches right first" to table(reverseLast = true, fairly = false),
    "…and round-robin conflict" to table(reverseLast = true, fairly = true),
)) {
    val stuck = net.deadTransitions()
    println("%-26s deadlocks=%-5s never fires: %s".format(
        label, net.willDeadlock(), stuck.ifEmpty { listOf("—") }.joinToString(", ")))
}

// The middle line is where breaking the symmetry removes the deadlock and leaves a
// starvation behind it. Two faults, two different fixes.
```

## 6. 2 — five against three

<!-- field-box id="petri_demo_polyrhythm" index="5" -->

```edn [field-box id="petri_demo_polyrhythm" role="properties"]
{"id" "petri_demo_polyrhythm" "index" 5 "name" "2 — five against three" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 — five against three" "_tombstone" false "frame" #afield/rect [60.0 210.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "petri_demo_polyrhythm_code_.kt" "lines" 30 "characters" 909 "sha256" "c0ab8ff98f80a3865e630c5f31b991fb5fab9dcae4ffb76dd2844e4138d9479a"}]}
```

```kotlin [field-property box="petri_demo_polyrhythm" name="code" path="petri_demo_polyrhythm_code_.kt"]
import afield.petri.*

// Two independent rings, one five long and one three long, sharing a clock. Nothing in
// this source says "fifteen" — that is a property of the net, and the net can tell you.
val net = petri("""
    #petri
    !grid 1/15

    a0 : 1
    a0 -[ a0.go : bd ]-> a1
    a1 -[ a1.go      ]-> a2
    a2 -[ a2.go : bd ]-> a3
    a3 -[ a3.go      ]-> a4
    a4 -[ a4.go      ]-> a0

    b0 : 1
    b0 -[ b0.go : hh ]-> b1
    b1 -[ b1.go      ]-> b2
    b2 -[ b2.go : hh ]-> b0
""")

showPetriNet(net, w = 700.0, h = 340.0)

val lasso = net.settle()!!
println("settles after ${lasso.loopSteps} steps = ${lasso.duration} cycles")
println("${net.tInvariants().size} T-invariants — one per independent ring")

// Four cycles, so you can hear the two rings pull apart and line back up.
println(showDirtAudio(net.emits(), cycles = 4, cps = 0.75, channel = "sound").describe())
displayOpen("sound")
```

## 7. 1 — the smallest loop

<!-- field-box id="petri_demo_loop" index="6" -->

```edn [field-box id="petri_demo_loop" role="properties"]
{"id" "petri_demo_loop" "index" 6 "name" "1 — the smallest loop" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 — the smallest loop" "_tombstone" false "frame" #afield/rect [60.0 60.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "petri_demo_loop_code_.kt" "lines" 27 "characters" 919 "sha256" "e3f48bbfcf0b028ac84a6ba29585933f11adfa80b27201b0115a6f6c6734dffc"}]}
```

```kotlin [field-property box="petri_demo_loop" name="code" path="petri_demo_loop_code_.kt"]
import afield.petri.*

// A place holds tokens; a transition fires when all its inputs have one. Give a
// transition a sound after the `:` and firing it plays that sound. That is the whole
// idea — everything else is more of it.
val net = petri("""
    #petri
    !timing step
    !grid 1/8
    !conflict order

    kick : 1
    kick -[ hit  @1/8 : bd ]-> hats
    hats -[ back @1/8 : sd ]-> kick
""")

showPetriNet(net, w = 640.0, h = 300.0)

// `emits()` is a PatPattern<String>, exactly like `pat("bd sd")` is. So every pattern
// combinator applies, and so does the pattern-language's own audio renderer.
println(showDirtAudio(net.emits(), cycles = 4, cps = 1.0, channel = "sound").describe())

println("plays: " + net.emits().events(PatArc(PatQ.ZERO, PatQ.ONE))
    .filter { it.hasOnset }.sortedBy { it.arc.start }.joinToString(" ") { it.value })
println("period: ${net.period()} cycles")

displayOpen("sound")
```

