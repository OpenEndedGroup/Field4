# Field Document: petri_tala_layout

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 2 — TALA, via native/field-d2-layout

<!-- field-box id="petri_tala_tala" index="0" -->

```edn [field-box id="petri_tala_tala" role="properties"]
{"id" "petri_tala_tala" "index" 0 "name" "2 — TALA, via native/field-d2-layout" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 — TALA, via native/field-d2-layout" "_tombstone" false "frame" #afield/rect [780.0 60.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "petri_tala_tala_code_.kt" "lines" 29 "characters" 987 "sha256" "8bfb5305ba1e24bb163b30b13656f22a08e75c71f023cf15afb264e02dd7adf7"}]}
```

```kotlin [field-property box="petri_tala_tala" name="code" path="petri_tala_tala_code_.kt"]
import afield.simulation.petri.*

fun philosophers(count: Int = 5): PetriNet = petri {
    for (index in 0 until count) place("fork.$index", tokens = 1)
    for (index in 0 until count) {
        val right = (index + 1) % count
        place("think.$index", tokens = 1)
        place("eat.$index")
        t("take.$index") {
            from("think.$index")
            from("fork.$index")
            from("fork.$right")
            to("eat.$index")
        }
        t("drop.$index") {
            from("eat.$index")
            to("think.$index")
            to("fork.$index")
            to("fork.$right")
        }
    }
}

val net = philosophers()
// Asking for TALA is safe whether or not the helper exists — that is the whole contract.
showPetriNet(net, layout = PetriLayout.TALA, w = 620.0, h = 520.0)

if (D2Layout.available) "TALA: d2 ${D2Layout.version}, places sized as real boxes and arcs routed"
else "helper absent — this is FORCE's drawing. Box 3 says how to build it."
```

## 2. 1 — FORCE, the spring model

<!-- field-box id="petri_tala_force" index="1" -->

```edn [field-box id="petri_tala_force" role="properties"]
{"id" "petri_tala_force" "index" 1 "name" "1 — FORCE, the spring model" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 — FORCE, the spring model" "_tombstone" false "frame" #afield/rect [60.0 60.0 360.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "petri_tala_force_code_.kt" "lines" 39 "characters" 1408 "sha256" "abba907ee7bac65e16804eb6ca73fbb30d1ac5524249b631a45d02620e4a3400"}]}
```

```kotlin [field-property box="petri_tala_force" name="code" path="petri_tala_force_code_.kt"]
import afield.simulation.petri.*

/*
Five philosophers, five forks. Every philosopher needs both neighbouring forks to eat,
which is what makes this dense rather than deep: 25 nodes and 40 arcs, all in one
strongly connected lump with no layering to find.
*/
fun philosophers(count: Int = 5): PetriNet = petri {
    for (index in 0 until count) place("fork.$index", tokens = 1)
    for (index in 0 until count) {
        val right = (index + 1) % count
        place("think.$index", tokens = 1)
        place("eat.$index")
        t("take.$index") {
            from("think.$index")
            from("fork.$index")
            from("fork.$right")
            to("eat.$index")
        }
        t("drop.$index") {
            from("eat.$index")
            to("think.$index")
            to("fork.$index")
            to("fork.$right")
        }
    }
}

val net = philosophers()
showPetriNet(net, layout = PetriLayout.FORCE, w = 620.0, h = 520.0)

/*
PetriLayoutSolver does not trust the physics: a spring model finds *a* minimum of its
own energy, which is not the same as a drawing you can read. So it relaxes from 48
different random starts and scores each result on crossings, overlaps and edges
passing under unrelated nodes, then keeps the best. Seeded from the net's own
structure, so the same net always draws the same way.
*/
"FORCE: 48 seeded restarts, scored on crossings and overlaps, best kept"
```

## 3. 3 — which engine drew it

<!-- field-box id="petri_tala_status" index="2" -->

```edn [field-box id="petri_tala_status" role="properties"]
{"id" "petri_tala_status" "index" 2 "name" "3 — which engine drew it" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 — which engine drew it" "_tombstone" false "frame" #afield/rect [60.0 700.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "petri_tala_status_code_.kt" "lines" 100 "characters" 4354 "sha256" "f17609c48fd87eebacd591ebd482c085c4e1a819ab17ee0ed5cbf493aaee3424"}]}
```

```kotlin [field-property box="petri_tala_status" name="code" path="petri_tala_status_code_.kt"]
import afield.simulation.petri.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun philosophers(count: Int = 5): PetriNet = petri {
    for (index in 0 until count) place("fork.$index", tokens = 1)
    for (index in 0 until count) {
        val right = (index + 1) % count
        place("think.$index", tokens = 1)
        place("eat.$index")
        t("take.$index") {
            from("think.$index")
            from("fork.$index")
            from("fork.$right")
            to("eat.$index")
        }
        t("drop.$index") {
            from("eat.$index")
            to("think.$index")
            to("fork.$index")
            to("fork.$right")
        }
    }
}

val net = philosophers()

val headline =
    if (D2Layout.available) "helper present — d2 ${D2Layout.version}"
    else "helper absent — boxes 1 and 2 are the same drawing"

/*
Timing each layout once, in order, is the obvious thing and it lies: the first one pays
for JVM warmup and the second does not. Both are warmed, then measured three times from
a cleared cache, and the minimum kept — the reading least polluted by whatever else the
machine is doing.
*/
fun best(layout: PetriLayout): Long {
    net.frame(layout)
    var bestNs = Long.MAX_VALUE
    repeat(3) {
        // BOTH caches. `clearPetriLayoutCache` alone leaves D2Layout's own request
        // cache warm, and TALA then times at 0 ms because it is a map lookup.
        clearPetriLayoutCache()
        D2Layout.clearCache()
        val at = System.nanoTime()
        net.frame(layout)
        bestNs = minOf(bestNs, System.nanoTime() - at)
    }
    return bestNs / 1_000_000
}

// Eight solves of a dense net is many seconds of work, too long to hold the canvas still for:
// it runs on a worker, and the table appears when it is done.
display("main", "<div style=\"font:13px ui-monospace,monospace;color:#8a8a8a\">timing FORCE and TALA…</div>")
go {
    val (forceMs, talaMs, differ) = withContext(Dispatchers.Default) {
        val forceMs = best(PetriLayout.FORCE)
        // Without the helper TALA runs FORCE again; reuse its measured fallback instead of
        // running a second identical benchmark.
        val talaMs = if (D2Layout.available) best(PetriLayout.TALA) else forceMs
        val force = net.frame(PetriLayout.FORCE)
        val tala = net.frame(PetriLayout.TALA)
        Triple(forceMs, talaMs, net.placeNames.any { force.position(it) != tala.position(it) })
    }

    val detail =
        if (D2Layout.available) "The two drawings differ: <b>$differ</b>. False here would mean TALA fell back silently."
        else
            "differ = <b>$differ</b>, which is the fallback working. Build it with " +
                "<code>(cd native/field-d2-layout &amp;&amp; go build -o build/field-d2-layout .)</code> — " +
                "Field prints that line itself, with an install hint when <code>go</code> is not on PATH."

    // Rendered rather than returned as a string, so the demo is something to look at.
    display(
        "main",
        """
      <div style="font:13px/1.55 ui-monospace,SFMono-Regular,monospace;background:#0d0d0d;
                  color:#dcdcdc;border:1px solid #2b2b2b;padding:14px 16px;max-width:720px">
        <div style="font-size:15px;color:#7fd7c4;margin-bottom:8px">$headline</div>
        <div style="margin-bottom:10px">$detail</div>
        <table style="border-collapse:collapse">
          <tr><td style="padding:2px 18px 2px 0;color:#8a8a8a">FORCE</td>
              <td style="text-align:right">${forceMs} ms</td>
              <td style="padding-left:14px;color:#8a8a8a">48 seeded restarts, scored</td></tr>
          <tr><td style="padding:2px 18px 2px 0;color:#8a8a8a">TALA</td>
              <td style="text-align:right">${talaMs} ms</td>
              <td style="padding-left:14px;color:#8a8a8a">${if (D2Layout.available) "subprocess, 3 seeds, best kept" else "FORCE fallback; same measurement"}</td></tr>
        </table>
        <div style="margin-top:10px;color:#8a8a8a">best of 3, both warmed first —
          timing them once each in order flatters whichever runs second</div>
        <div style="margin-top:8px;color:#8a8a8a">TALA is never chosen by
          <code>PetriLayout.AUTO</code>, so nothing already drawn changes.</div>
      </div>
    """
            .trimIndent(),
    )
}

headline
```

