# Field Document: spatial_rules_web

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. A web, one rule at a time

<!-- field-box id="srw_web" index="0" -->

```edn [field-box id="srw_web" role="properties"]
{"id" "srw_web" "index" 0 "name" "A web, one rule at a time" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "A web, one rule at a time" "_tombstone" false "frame" #afield/rect [60.0 60.0 290.0 78.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "srw_web_code_.kt" "lines" 108 "characters" 3831 "sha256" "1b8f1527dcf625e0a9ad625756ab1c07a6ca6779b20c3f0997e8698770c91518"}]}
```

```kotlin [field-property box="srw_web" name="code" path="srw_web_code_.kt"]
import afield.data.graph.SpatialLifetime.MATCH

val world = SpatialRuleWorld()
val w = 900.0
val h = 500.0
val ink = vec(0.85, 0.9, 1.0, 1.0)
val beadInk = vec(1.0, 0.72, 0.35, 1.0)
val webInk = vec(0.45, 0.8, 1.0, 0.8)
val skin = vec(0.35, 0.55, 0.9, 0.22)
val glow = vec(1.0, 0.9, 0.4, 0.6)

// Each seed crosses the drawing at its own pace and turns back at the edges.
fun zigzag(u: Double) = asin(sin(u)) * 2 / PI

fun wander(i: Int, t: Double) = vec(
    w / 2 + 0.45 * w * zigzag((0.06 + 0.12 * (i * 0.618 % 1.0)) * t + 5.2 * i),
    h / 2 + 0.45 * h * zigzag((0.06 + 0.12 * (i * 0.382 % 1.0)) * t + 2.4 * i),
    0,
)

fun tremble(i: Int, t: Double) = vec(2.5 * sin(19 * t + i), 2.5 * cos(23 * t + 2 * i), 0)

world.source("drifting seeds") {
    val seeds = 30 { i -> point("seed", wander(i, 0.0)) }
    while (true) {
        for ((i, seed) in seeds.withIndex()) {
            move(seed, wander(i, age) + tremble(i, age))
            draw(spatialDot(seed.pos, 3.0, ink))
        }
        nextFrame()
    }
}

var bondsMade = 0

world.rules("bonds") {
    onEnter("bond nearby seeds", points("seed").pairsWithin(110.0, leave = 130.0)) { (a, b) ->
        bondsMade++
        val bond = edge("bond", a, b, lifetime = MATCH)
        whileLive { draw(spatialStroke(bond, ink)) }
        during(0.8) { t -> draw(spatialStroke(bond, ink, opacity = 1 - t)) }
    }
}

world.rules("beads") {
    onEnter("bead a bond", edges("bond")) { bond ->
        val beads = mutableListOf<SpatialPoint>()
        whileLive {
            val beadSpacing = 22 // Drawing units per bead along the bond.
            val wanted = (bond.length / beadSpacing).toInt()
            if (beads.size < wanted && age > 0.2 * beads.size) {
                beads += point("bead", bond.at(0.5), mapOf("bond" to bond.id))
            }
            while (beads.size > wanted) retract(beads.removeLast())
            for ((i, bead) in beads.withIndex()) {
                move(bead, bond.at((i + 1.0) / (beads.size + 1)))
                draw(spatialDot(bead.pos, 1.8, beadInk))
            }
        }
        val from = beads.map { it.pos }
        during(1.5) { t ->
            for ((i, bead) in beads.withIndex()) {
                move(bead, from[i] + vec(0, 120 * t * t, 0))
                draw(spatialDot(bead.pos, 1.8, beadInk, opacity = 1 - t))
            }
        }
    }
}

world.rules("web") {
    val across = points("bead").pairsWithin(24.0, leave = 32.0).where { (a, b) -> a["bond"] != b["bond"] }
    onEnter("web across bonds", across) { (a, b) ->
        edge("web", a, b, lifetime = MATCH)
        whileLive { draw(spatialStroke([a.pos, b.pos], webInk, width = 0.8)) }
        during(0.4) { t -> draw(spatialStroke([a.pos, b.pos], webInk, 0.8, 1 - t)) }
    }
}

world.rules("membranes") {
    onEnter("fill a triangle", edges("bond").triangles()) { triangle ->
        whileLive { draw(spatialFill(triangle, skin, opacity = min(1.0, age / 0.5))) }
        during(0.5) { t -> draw(spatialFill(triangle, skin, opacity = 1 - t)) }
    }
}

var traced = emptyList<SpatialFact>()

world.source("highlight") {
    while (true) {
        for (fact in traced.filter { it.alive }) when (fact) {
            is SpatialPoint -> draw(spatialDot(fact.pos, 6.0, glow))
            is SpatialEdge -> draw(spatialStroke(fact, glow, width = 5.0))
            is SpatialLink -> draw(spatialStroke(fact.attached.map { it.pos }, glow, width = 5.0))
        }
        nextFrame()
    }
}

play(
    world,
    w = w,
    h = h,
    after = { markdown("Counts", "$bondsMade bonds made, ${world.edges("web").size} web strands now") },
    onPress = { at ->
        traced = world.nearest(at, within = 12.0)?.let(world::lineage).orEmpty()
        markdown("Why", traced.joinToString("\n") { "- **${it.kind} #${it.id}** by *${it.origin.process}*" })
    },
)
```

